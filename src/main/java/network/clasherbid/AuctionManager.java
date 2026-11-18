package network.clasherbid;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Single-auction state machine. All public mutations are invoked on the server thread.
 * The highest bid is escrowed immediately and refunded when outbid/cancelled.
 */
public final class AuctionManager implements Listener {
    private final ClasherBidPlugin plugin;
    private final Economy economy;
    private final File file;
    private final File historyFile;
    private final java.util.ArrayList<String> history = new java.util.ArrayList<>();
    private ItemStack item;
    private UUID seller;
    private UUID bidder;
    private String bidderName = "";
    private double currentBid;
    private double escrow;
    private long endsAt;
    private long lastBidAt;
    private boolean active;
    private String settlementReview = "";
    private BukkitTask timer;

    AuctionManager(ClasherBidPlugin plugin, Economy economy) {
        this.plugin = plugin;
        this.economy = economy;
        this.file = new File(plugin.getDataFolder(), "active-auction.yml");
        this.historyFile = new File(plugin.getDataFolder(), "auction-history.yml");
    }

    public boolean isActive() { return active; }
    public String settlementReview() { return settlementReview; }
    public ItemStack auctionItem() { return item == null ? null : item.clone(); }
    public double currentBid() { return currentBid; }
    public UUID highestBidder() { return bidder; }
    public String highestBidderName() { return bidderName; }
    public long secondsLeft() { return active ? Math.max(0, (endsAt - System.currentTimeMillis() + 999) / 1000) : 0; }
    public double nextMinimum() { return active ? currentBid + plugin.getConfig().getDouble("minimum-increment", 100.0) : 0; }
    private final java.util.ArrayDeque<String> bidHistory = new java.util.ArrayDeque<>();
    public java.util.List<String> recentBids() { return java.util.List.copyOf(bidHistory); }
    public java.util.List<String> auctionHistory() { return java.util.List.copyOf(history); }
    private void recordBid(String name, double amount) { bidHistory.addFirst(name + " : " + economy.format(amount)); while (bidHistory.size() > 8) bidHistory.removeLast(); }

    public boolean start(Player admin, ItemStack stack, double openingBid, long durationSeconds) {
        if (active || stack == null || stack.getType().isAir() || durationSeconds < 30
                || !Double.isFinite(openingBid) || openingBid < 0) return false;
        item = stack.clone();
        seller = admin.getUniqueId();
        bidder = null;
        bidderName = "";
        currentBid = openingBid;
        escrow = 0;
        endsAt = System.currentTimeMillis() + durationSeconds * 1000L;
        settlementReview = "";
        active = true;
        persist();
        schedule();
        Bukkit.broadcastMessage("§6§lCLASHERBID §eAuction started: §f" + item.getType()
                + " §7| Opening bid: §a" + economy.format(openingBid) + " §7| §e/bid");
        return true;
    }

    public String placeBid(Player player, double amount) {
        if (!active) return "§cThere is no active auction.";
        if (!settlementReview.isEmpty()) return "§cThis auction is frozen for admin settlement review. Bidding is disabled.";
        if (player.getUniqueId().equals(seller)) return "§cYou cannot bid on your own auction.";
        long cooldown = plugin.getConfig().getLong("bid-cooldown-seconds", 2) * 1000L;
        if (System.currentTimeMillis() - lastBidAt < cooldown) return "§cPlease wait before bidding again.";
        if (!Double.isFinite(amount) || amount < nextMinimum())
            return "§cMinimum next bid: §e" + economy.format(nextMinimum());

        // Withdraw the full new bid first. The previous escrow is refunded only
        // after the new withdrawal succeeds, avoiding an unbacked high bid.
        EconomyResponse withdrawal = economy.withdrawPlayer(player, amount);
        if (!withdrawal.transactionSuccess()) return "§cBid failed: " + withdrawal.errorMessage;

        UUID previousBidder = bidder;
        double previousEscrow = escrow;
        bidder = player.getUniqueId();
        bidderName = player.getName();
        currentBid = amount;
        escrow = amount;
        lastBidAt = System.currentTimeMillis();
        recordBid(bidderName, amount);

        if (previousBidder != null) refund(previousBidder, previousEscrow);
        if (plugin.getConfig().getBoolean("anti-sniping.enabled", true)
                && secondsLeft() <= plugin.getConfig().getLong("anti-sniping.trigger-seconds", 15)) {
            endsAt += plugin.getConfig().getLong("anti-sniping.extend-seconds", 30) * 1000L;
        }
        persist();
        Bukkit.broadcastMessage("§6§lCLASHERBID §e" + player.getName() + " §7bid §a"
                + economy.format(amount) + " §7on §f" + item.getType());
        return "§aYour bid of §e" + economy.format(amount) + " §awas accepted. Funds are held until outbid or auction end.";
    }

    public void cancel() {
        if (!active) return;
        if (bidder != null) refund(bidder, escrow);
        deliver(seller, item);
        clear();
        Bukkit.broadcastMessage("§6ClasherBid §cAuction cancelled. Item returned and bidder refunded.");
    }

    private void schedule() {
        if (timer != null) timer.cancel();
        timer = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (active && System.currentTimeMillis() >= endsAt) finish();
        }, 20L, 20L);
    }

    public void finish() {
        if (!active || !settlementReview.isEmpty()) return;
        if (bidder == null) {
            recordHistory("No bids", 0.0);
            deliver(seller, item);
            Bukkit.broadcastMessage("§6ClasherBid §7Auction ended without bids; item returned.");
        } else {
            UUID payee = payoutAccount();
            if (payee == null) {
                // Keep auction and escrow intact so the admin can fix the payout
                // account instead of silently destroying money or the item.
                Bukkit.broadcastMessage("§cClasherBid payout account unavailable; auction settlement paused.");
                endsAt = System.currentTimeMillis() + 60_000L;
                persist();
                schedule();
                return;
            }
            // Persist a transaction intent before touching Vault. If the process crashes
            // in the uncertain window, startup will freeze settlement for admin review.
            settlementReview = "PAYOUT_PENDING";
            persist();
            EconomyResponse deposit = economy.depositPlayer(Bukkit.getOfflinePlayer(payee), escrow);
            if (!deposit.transactionSuccess()) {
                settlementReview = "";
                Bukkit.broadcastMessage("§cClasherBid payout failed; settlement will retry.");
                endsAt = System.currentTimeMillis() + 60_000L;
                persist();
                schedule();
                return;
            }
            settlementReview = "PAYOUT_COMPLETED";
            persist();
            recordHistory(bidderName, currentBid);
            deliver(bidder, item);
            Bukkit.broadcastMessage("§6§lAUCTION ENDED §e" + bidderName + " §7won §f"
                    + item.getType() + " §7for §a" + economy.format(currentBid) + "§7!");
        }
        clear();
    }

    public boolean resolveSettlementPaid() {
        if (!active || settlementReview.isEmpty() || bidder == null || item == null) return false;
        recordHistory(bidderName, currentBid);
        deliver(bidder, item);
        Bukkit.broadcastMessage("§6ClasherBid §eSettlement manually confirmed paid by admin. Winner item delivery completed.");
        clear();
        return true;
    }

    public boolean resolveSettlementUnpaid() {
        if (!active || settlementReview.isEmpty() || item == null || seller == null) return false;
        if (bidder != null && escrow > 0) {
            // Persist an unpaid refund liability instead of depositing immediately.
            // The guarded recovery command will require review before any payment.
            String refundPath = "recovery.refund." + bidder;
            plugin.getConfig().set(refundPath, plugin.getConfig().getDouble(refundPath, 0.0) + escrow);
            plugin.saveConfig();
        }
        deliver(seller, item);
        Bukkit.broadcastMessage("§6ClasherBid §eSettlement manually confirmed unpaid by admin. Auction item returned.");
        clear();
        return true;
    }

    private void recordHistory(String winner, double amount) {
        String entry = item.getType() + " | " + winner + " | " + economy.format(amount) + " | " + java.time.Instant.now();
        history.add(0, entry);
        while (history.size() > 25) history.remove(history.size() - 1);
        YamlConfiguration data = new YamlConfiguration();
        data.set("entries", history);
        try { data.save(historyFile); } catch (IOException ex) { plugin.getLogger().log(Level.WARNING, "Could not save auction history", ex); }
    }

    private UUID payoutAccount() {
        String configured = plugin.getConfig().getString("payout-player", "").trim();
        if (configured.isEmpty()) return seller;
        OfflinePlayer account = Bukkit.getOfflinePlayer(configured);
        return account.getUniqueId();
    }

    private void refund(UUID playerId, double amount) {
        EconomyResponse response = economy.depositPlayer(Bukkit.getOfflinePlayer(playerId), amount);
        if (!response.transactionSuccess()) {
            plugin.getLogger().severe("Refund failed for " + playerId + ": " + response.errorMessage
                    + ". Escrow remains recorded for manual recovery.");
            plugin.getConfig().set("recovery.refund." + playerId, plugin.getConfig().getDouble("recovery.refund." + playerId) + amount);
            plugin.saveConfig();
        }
    }

    public double pendingRefund(UUID playerId) {
        return plugin.getConfig().getDouble("recovery.refund." + playerId, 0.0);
    }

    public String refundReview(UUID playerId) {
        return plugin.getConfig().getString("recovery.refund-review." + playerId, "");
    }

    /**
     * Starts a refund recovery with a durable review marker before Vault is called.
     * A crash after the deposit but before config cleanup must never permit blind retry.
     */
    public boolean recoverRefund(UUID playerId) {
        double amount = pendingRefund(playerId);
        if (!Double.isFinite(amount) || amount <= 0 || !refundReview(playerId).isEmpty()) return false;
        plugin.getConfig().set("recovery.refund-review." + playerId, "REFUND_PENDING");
        plugin.saveConfig();
        EconomyResponse response = economy.depositPlayer(Bukkit.getOfflinePlayer(playerId), amount);
        if (!response.transactionSuccess()) return false; // keep marker for manual review
        plugin.getConfig().set("recovery.refund." + playerId, null);
        plugin.getConfig().set("recovery.refund-review." + playerId, null);
        plugin.saveConfig();
        return true;
    }

    /** paid=true clears the debt; paid=false authorizes a later retry after balance verification. */
    public boolean resolveRefundReview(UUID playerId, boolean paid) {
        if (refundReview(playerId).isEmpty()) return false;
        if (paid) plugin.getConfig().set("recovery.refund." + playerId, null);
        plugin.getConfig().set("recovery.refund-review." + playerId, null);
        plugin.saveConfig();
        return true;
    }

    private void deliver(UUID playerId, ItemStack stack) {
        if (playerId == null || stack == null) return;
        Player online = Bukkit.getPlayer(playerId);
        if (online != null && online.isOnline()) {
            if (!online.getInventory().addItem(stack.clone()).isEmpty())
                online.getWorld().dropItemNaturally(online.getLocation(), stack.clone());
        } else {
            String path = "pending-delivery." + playerId;
            java.util.List<?> raw = plugin.getConfig().getList(path);
            java.util.List<ItemStack> pending = new java.util.ArrayList<>();
            if (raw != null) for (Object value : raw) if (value instanceof ItemStack oldStack) pending.add(oldStack.clone());
            ItemStack legacy = plugin.getConfig().getItemStack(path);
            if (pending.isEmpty() && legacy != null) pending.add(legacy);
            pending.add(stack.clone());
            plugin.getConfig().set(path, pending);
            plugin.saveConfig();
        }
    }

    public void claim(Player player) {
        String path = "pending-delivery." + player.getUniqueId();
        java.util.List<?> raw = plugin.getConfig().getList(path);
        java.util.List<ItemStack> pending = new java.util.ArrayList<>();
        if (raw != null) for (Object value : raw) if (value instanceof ItemStack stack) pending.add(stack.clone());
        ItemStack legacy = plugin.getConfig().getItemStack(path);
        if (pending.isEmpty() && legacy != null) pending.add(legacy);
        if (pending.isEmpty()) return;

        java.util.List<ItemStack> remaining = new java.util.ArrayList<>();
        int delivered = 0;
        for (ItemStack stack : pending) {
            if (stack == null || stack.getType().isAir()) continue;
            java.util.Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack.clone());
            if (overflow.isEmpty()) delivered++;
            else remaining.addAll(overflow.values());
        }
        plugin.getConfig().set(path, remaining.isEmpty() ? null : remaining);
        plugin.saveConfig();
        if (delivered > 0) player.sendMessage("§aDelivered " + delivered + " pending auction item(s).");
        if (!remaining.isEmpty()) player.sendMessage("§eInventory full. Remaining items will be kept for later.");
    }

    private void clear() {
        if (timer != null) timer.cancel();
        timer = null;
        active = false;
        item = null;
        seller = null;
        bidder = null;
        bidderName = "";
        currentBid = 0;
        escrow = 0;
        endsAt = 0;
        settlementReview = "";
        persist();
    }

    public void load() {
        if (historyFile.exists()) {
            YamlConfiguration savedHistory = YamlConfiguration.loadConfiguration(historyFile);
            history.clear();
            history.addAll(savedHistory.getStringList("entries"));
            while (history.size() > 25) history.remove(history.size() - 1);
        }
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (!yaml.getBoolean("active")) return;
        item = yaml.getItemStack("item");
        seller = parseUuid(yaml.getString("seller"));
        bidder = parseUuid(yaml.getString("bidder"));
        bidderName = yaml.getString("bidderName", "");
        currentBid = yaml.getDouble("currentBid");
        escrow = yaml.getDouble("escrow");
        endsAt = yaml.getLong("endsAt");
        bidHistory.clear();
        bidHistory.addAll(yaml.getStringList("recentBids"));
        settlementReview = yaml.getString("settlementReview", "");
        active = item != null && seller != null;
        if (active) {
            if (!settlementReview.isEmpty()) {
                plugin.getLogger().severe("Auction settlement frozen for admin review: " + settlementReview
                        + ". Do NOT manually retry payout until economy balances are checked.");
            } else if (endsAt <= System.currentTimeMillis()) finish();
            else schedule();
        }
    }

    private UUID parseUuid(String raw) {
        try { return raw == null ? null : UUID.fromString(raw); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    public void shutdown() {
        if (timer != null) timer.cancel();
        persist();
    }

    private void persist() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("active", active);
        yaml.set("settlementReview", settlementReview);
        yaml.set("item", item);
        yaml.set("seller", seller == null ? null : seller.toString());
        yaml.set("bidder", bidder == null ? null : bidder.toString());
        yaml.set("bidderName", bidderName);
        yaml.set("currentBid", currentBid);
        yaml.set("escrow", escrow);
        yaml.set("endsAt", endsAt);
        yaml.set("recentBids", new java.util.ArrayList<>(bidHistory));
        File tempFile = new File(plugin.getDataFolder(), "active-auction.yml.tmp");
        try {
            yaml.save(tempFile);
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            try {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackFailure) {
                fallbackFailure.addSuppressed(atomicFailure);
                plugin.getLogger().log(Level.SEVERE, "Could not safely persist active auction. The previous file may remain intact.", fallbackFailure);
            }
        }
    }
}