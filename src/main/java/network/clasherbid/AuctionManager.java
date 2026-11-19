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
    private final java.util.Set<String> recordedHistoryTransactions = new java.util.HashSet<>();
    private ItemStack item;
    private UUID seller;
    private UUID bidder;
    private String bidderName = "";
    private double currentBid;
    private double escrow;
    private long endsAt;
    private long lastBidAt;
    private boolean active;
    private boolean recoveryBlocked;
    private String settlementReview = "";
    private UUID pendingBidder;
    private UUID pendingBidTransactionId;
    private UUID pendingRefundPlayer;
    private double pendingRefundAmount;
    private String pendingBidderName = "";
    private double pendingBidAmount;
    private BukkitTask timer;

    AuctionManager(ClasherBidPlugin plugin, Economy economy) {
        this.plugin = plugin;
        this.economy = economy;
        this.file = new File(plugin.getDataFolder(), "active-auction.yml");
        this.historyFile = new File(plugin.getDataFolder(), "auction-history.yml");
    }

    public boolean isActive() { return active; }
    public boolean isRecoveryBlocked() { return recoveryBlocked; }
    public String settlementReview() { return settlementReview; }
    public ItemStack auctionItem() { return item == null ? null : item.clone(); }
    public double currentBid() { return currentBid; }
    public UUID highestBidder() { return bidder; }
    public String highestBidderName() { return bidderName; }
    public long secondsLeft() { return active ? Math.max(0, (endsAt - System.currentTimeMillis() + 999) / 1000) : 0; }
    public double nextMinimum() {
        if (!active) return 0;
        double increment = plugin.getConfig().getDouble("minimum-increment", 100.0);
        if (!Double.isFinite(increment) || increment <= 0) increment = 100.0;
        double minimum = currentBid + increment;
        return Double.isFinite(minimum) ? minimum : Double.MAX_VALUE;
    }
    private final java.util.ArrayDeque<String> bidHistory = new java.util.ArrayDeque<>();
    public java.util.List<String> recentBids() { return java.util.List.copyOf(bidHistory); }
    public java.util.List<String> auctionHistory() { return java.util.List.copyOf(history); }
    private void recordBid(String name, double amount) { bidHistory.addFirst(name + " : " + economy.format(amount)); while (bidHistory.size() > 8) bidHistory.removeLast(); }

    public boolean start(Player admin, ItemStack stack, double openingBid, long durationSeconds) {
        if (active || recoveryBlocked || stack == null || stack.getType().isAir() || durationSeconds < 30
                || !Double.isFinite(openingBid) || openingBid < 0) return false;
        auctionId = UUID.randomUUID();
        item = stack.clone();
        seller = admin.getUniqueId();
        bidder = null;
        bidderName = "";
        currentBid = openingBid;
        escrow = 0;
        lastBidAt = 0;
        bidHistory.clear();
        endsAt = System.currentTimeMillis() + durationSeconds * 1000L;
        settlementReview = "START_PENDING";
        active = true;
        if (!persist()) {
            active = false;
            item = null;
            seller = null;
            bidder = null;
            bidderName = "";
            currentBid = 0;
            escrow = 0;
            endsAt = 0;
            settlementReview = "";
            return false;
        }
        // Auction remains frozen until AdminCommand confirms the held item was removed.
        return true;
    }

    public boolean completeStart() {
        if (!active || !"START_PENDING".equals(settlementReview)) return false;
        settlementReview = "";
        if (!persist()) {
            settlementReview = "START_PENDING";
            return false;
        }
        schedule();
        Bukkit.broadcastMessage("§6§lCLASHERBID §eAuction started: §f" + item.getType()
                + " §7| Opening bid: §a" + economy.format(currentBid) + " §7| §e/bid");
        return true;
    }

    /** Resolve a crash during auction start after verifying whether the item was removed from the admin. */
    public boolean resolveStartReview(boolean itemRemoved) {
        if (!active || !"START_PENDING".equals(settlementReview)) return false;
        if (itemRemoved) {
            settlementReview = "";
            if (!persist()) {
                settlementReview = "START_PENDING";
                return false;
            }
            schedule();
            Bukkit.broadcastMessage("§6§lCLASHERBID §eAuction start reconciled; auction is active.");
            return true;
        }
        ItemStack oldItem = item;
        UUID oldSeller = seller, oldBidder = bidder;
        String oldBidderName = bidderName;
        double oldCurrentBid = currentBid, oldEscrow = escrow;
        long oldEndsAt = endsAt;
        active = false;
        item = null;
        seller = null;
        bidder = null;
        bidderName = "";
        currentBid = 0;
        escrow = 0;
        endsAt = 0;
        settlementReview = "";
        if (!persist()) {
            active = true;
            item = oldItem;
            seller = oldSeller;
            bidder = oldBidder;
            bidderName = oldBidderName;
            currentBid = oldCurrentBid;
            escrow = oldEscrow;
            endsAt = oldEndsAt;
            settlementReview = "START_PENDING";
            return false;
        }
        return true;
    }

    public String placeBid(Player player, double amount) {
        if (!active) return "§cThere is no active auction.";
        if (System.currentTimeMillis() >= endsAt) return "§cAuction time has ended. Settlement is processing; new bids are closed.";
        if (!settlementReview.isEmpty()) return "§cThis auction is frozen for admin settlement review. Bidding is disabled.";
        if (player.getUniqueId().equals(seller)) return "§cYou cannot bid on your own auction.";
        long cooldownSeconds = Math.max(0, Math.min(3600, plugin.getConfig().getLong("bid-cooldown-seconds", 2)));
        long cooldown = cooldownSeconds * 1000L;
        if (System.currentTimeMillis() - lastBidAt < cooldown) return "§cPlease wait before bidding again.";
        if (!Double.isFinite(amount) || amount < nextMinimum())
            return "§cMinimum next bid: §e" + economy.format(nextMinimum());

        // Durable intent before Vault withdrawal: an uncertain crash is frozen for admin review.
        pendingBidder = player.getUniqueId();
        pendingBidTransactionId = UUID.randomUUID();
        pendingBidderName = player.getName();
        pendingBidAmount = amount;
        settlementReview = "BID_PENDING";
        if (!persist()) {
            settlementReview = "";
            pendingBidder = null;
            pendingBidderName = "";
            pendingBidAmount = 0;
            pendingBidTransactionId = null;
            persist();
            return "§cBid paused: auction state could not be saved.";
        }
        EconomyResponse withdrawal;
        try {
            withdrawal = economy.withdrawPlayer(player, amount);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "Vault threw during bid withdrawal; bid remains frozen for manual review.", ex);
            return "§eBid payment result is uncertain. Auction frozen for admin review; do not retry.";
        }
        if (withdrawal == null) {
            plugin.getLogger().severe("Vault returned no bid withdrawal response; bid remains frozen for manual review.");
            return "§eBid payment result is uncertain. Auction frozen for admin review; do not retry.";
        }
        if (!withdrawal.transactionSuccess()) {
            UUID failedTransactionId = pendingBidTransactionId;
            settlementReview = "";
            pendingBidder = null;
            pendingBidderName = "";
            pendingBidAmount = 0;
            pendingBidTransactionId = null;
            if (!persist()) {
                settlementReview = "BID_PENDING";
                pendingBidder = player.getUniqueId();
                pendingBidderName = player.getName();
                pendingBidAmount = amount;
                pendingBidTransactionId = failedTransactionId;
                Bukkit.broadcastMessage("§cClasherBid: failed withdrawal could not be cleared durably; auction frozen for admin review.");
                return "§eBid was not accepted, but auction is frozen for admin review.";
            }
            return "§cBid failed: " + withdrawal.errorMessage;
        }

        UUID previousBidder = bidder;
        double previousEscrow = escrow;
        bidder = pendingBidder;
        bidderName = pendingBidderName;
        currentBid = pendingBidAmount;
        escrow = pendingBidAmount;
        pendingBidder = null;
        pendingBidderName = "";
        pendingBidAmount = 0;
        settlementReview = "BID_PENDING_ACCEPTED";
        lastBidAt = System.currentTimeMillis();
        recordBid(bidderName, amount);
        if (previousBidder != null && previousEscrow > 0) {
            // Keep the refund source in the auction journal until its idempotent ledger entry is durable.
            pendingRefundPlayer = previousBidder;
            pendingRefundAmount = previousEscrow;
            // Persist the accepted bid and refund liability before writing the refund ledger.
            // A crash after the ledger write can then only recover through BID_PENDING_ACCEPTED.
            if (!persist()) {
                Bukkit.broadcastMessage("§cClasherBid: accepted bid journal could not be saved; auction frozen for admin review.");
                return "§eBid payment accepted, but recovery state could not be saved. Auction frozen for admin review.";
            }
            if (!queueRefund(pendingRefundPlayer, pendingRefundAmount, "outbid-" + pendingBidTransactionId)) {
                Bukkit.broadcastMessage("§cClasherBid: previous bidder refund could not be saved; auction frozen for admin review.");
                return "§eBid received, but refund accounting could not be saved. Auction frozen for admin review.";
            }
            pendingRefundPlayer = null;
            pendingRefundAmount = 0;
        }
        if (plugin.getConfig().getBoolean("anti-sniping.enabled", true)) {
            long trigger = Math.max(0, Math.min(3600, plugin.getConfig().getLong("anti-sniping.trigger-seconds", 15)));
            long extension = Math.max(0, Math.min(3600, plugin.getConfig().getLong("anti-sniping.extend-seconds", 30)));
            if (extension > 0 && secondsLeft() <= trigger) endsAt += extension * 1000L;
        }
        if (!persist()) {
            Bukkit.broadcastMessage("§cClasherBid: bid accepted but persistence is uncertain; auction frozen for admin review.");
            return "§eBid payment accepted, but auction is frozen for admin review.";
        }
        UUID acceptedTransactionId = pendingBidTransactionId;
        settlementReview = "";
        pendingBidder = null;
        pendingBidderName = "";
        pendingBidAmount = 0;
        pendingBidTransactionId = null;
        if (!persist()) {
            settlementReview = "BID_PENDING_ACCEPTED";
            pendingBidTransactionId = acceptedTransactionId;
            Bukkit.broadcastMessage("§cClasherBid: accepted bid marker could not be cleared; auction frozen for admin review.");
            return "§eBid payment accepted, but auction is frozen for admin review.";
        }
        Bukkit.broadcastMessage("§6§lCLASHERBID §e" + player.getName() + " §7bid §a"
                + economy.format(amount) + " §7on §f" + item.getType());
        return "§aYour bid of §e" + economy.format(amount) + " §awas accepted. Funds are held until outbid or auction end.";
    }

    public boolean cancel() {
        if (!active || !settlementReview.isEmpty()) return false;
        // Cancellation is a transaction too: freeze and persist intent first.
        // Admin must reconcile the refund/item outcome instead of risking duplicate side effects.
        settlementReview = "CANCEL_PENDING";
        if (!persist()) {
            settlementReview = "";
            return false;
        }
        Bukkit.broadcastMessage("§cClasherBid cancellation frozen for admin reconciliation. Verify balances and item delivery, then use /clasherbid settlement <paid|unpaid>.");
        return true;
    }

    private void schedule() {
        if (timer != null) timer.cancel();
        timer = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (active && System.currentTimeMillis() >= endsAt) finish();
        }, 20L, 20L);
    }

    public void finish() {
        if (!active) return;
        if (!settlementReview.isEmpty() && !settlementReview.equals("PAYOUT_RETRY")) return;
        if (settlementReview.equals("PAYOUT_RETRY")) {
            settlementReview = "";
            if (!persist()) {
                settlementReview = "PAYOUT_RETRY";
                return;
            }
        }
        if (bidder == null) {
            settlementReview = "DELIVERY_PENDING";
            if (!persist()) { settlementReview = ""; return; }
            if (!queueDelivery(seller, item, "auction-" + auctionTransactionId() + "-seller-return")) return;
            if (!recordHistory("No bids", 0.0, auctionTransactionId())) return;
            Bukkit.broadcastMessage("§6ClasherBid §7Auction ended without bids; item queued for claim.");
        } else {
            UUID payee = payoutAccount();
            if (payee == null) {
                // Keep auction and escrow intact so the admin can fix the payout
                // account instead of silently destroying money or the item.
                Bukkit.broadcastMessage("§cClasherBid payout account unavailable; auction frozen. Fix payout-player and use /clasherbid retry.");
                settlementReview = "PAYOUT_ACCOUNT_INVALID";
                endsAt = System.currentTimeMillis() + 60_000L;
                if (!persist()) plugin.getLogger().severe("Could not persist payout-account review marker; auction remains frozen in memory.");
                return;
            }
            // Persist a transaction intent before touching Vault. If the process crashes
            // in the uncertain window, startup will freeze settlement for admin review.
            settlementReview = "PAYOUT_PENDING";
            if (!persist()) {
                // Never touch Vault unless the transaction intent is durably recorded.
                // No payout was attempted, so retrying after a delayed save is safe.
                Bukkit.broadcastMessage("§cClasherBid payout paused: could not persist transaction intent.");
                endsAt = System.currentTimeMillis() + 60_000L;
                settlementReview = "";
                persist();
                schedule();
                return;
            }
            EconomyResponse deposit;
            try {
                deposit = economy.depositPlayer(Bukkit.getOfflinePlayer(payee), escrow);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.SEVERE, "Vault threw during auction payout; payout remains frozen for manual review.", ex);
                return;
            }
            if (deposit == null) {
                plugin.getLogger().severe("Vault returned no payout response; payout remains frozen for manual review.");
                return;
            }
            if (!deposit.transactionSuccess()) {
                settlementReview = "PAYOUT_RETRY";
                endsAt = System.currentTimeMillis() + 60_000L;
                if (!persist()) {
                    settlementReview = "PAYOUT_PENDING";
                    Bukkit.broadcastMessage("§cClasherBid payout failed and retry state could not be saved; auction remains frozen for admin review.");
                    return;
                }
                Bukkit.broadcastMessage("§cClasherBid payout failed; bidding is paused and settlement will retry in 60 seconds.");
                schedule();
                return;
            }
            settlementReview = "PAYOUT_COMPLETED";
            if (!persist()) return;
            if (!queueDelivery(bidder, item, "auction-" + auctionTransactionId() + "-winner")) return;
            if (!recordHistory(bidderName, currentBid, auctionTransactionId())) return;
            Bukkit.broadcastMessage("§6§lAUCTION ENDED §e" + bidderName + " §7won §f"
                    + item.getType() + " §7for §a" + economy.format(currentBid) + "§7! Item queued for claim.");
        }
        clear();
    }

    public String bidReviewInfo() {
        return settlementReview.startsWith("BID_PENDING") ? settlementReview + " | " + pendingBidderName + " | " + pendingBidAmount : "";
    }

    /** Resolve an interrupted bid withdrawal; paid accepts it, unpaid clears the intent. */
    public boolean resolveBidReview(boolean paid) {
        if (!active || !settlementReview.startsWith("BID_PENDING")) return false;
        // Accepted state was persisted with a review marker: only clear it after successful save.
        if (settlementReview.equals("BID_PENDING_ACCEPTED")) {
            if (!paid) return false;
            UUID previousRefundPlayer = pendingRefundPlayer;
            double previousRefundAmount = pendingRefundAmount;
            if (pendingRefundPlayer != null && pendingRefundAmount > 0) {
                if (!queueRefund(pendingRefundPlayer, pendingRefundAmount, "outbid-" + pendingBidTransactionId)) return false;
                pendingRefundPlayer = null;
                pendingRefundAmount = 0;
            }
            settlementReview = "";
            if (persist()) return true;
            // Keep the accepted-bid journal exactly as it was if the auction save fails.
            settlementReview = "BID_PENDING_ACCEPTED";
            pendingRefundPlayer = previousRefundPlayer;
            pendingRefundAmount = previousRefundAmount;
            return false;
        }
        if (pendingBidder == null || pendingBidAmount <= 0) return false;
        UUID previousBidder = bidder;
        String previousBidderName = bidderName;
        double previousBid = currentBid;
        double previousEscrow = escrow;
        String previousReview = settlementReview;
        UUID intentBidder = pendingBidder;
        UUID intentTransactionId = pendingBidTransactionId;
        UUID previousRefundPlayer = pendingRefundPlayer;
        double previousRefundAmount = pendingRefundAmount;
        String intentName = pendingBidderName;
        double intentAmount = pendingBidAmount;
        if (paid) {
            bidder = intentBidder;
            bidderName = intentName;
            currentBid = intentAmount;
            escrow = intentAmount;
            recordBid(bidderName, currentBid);
            if (previousBidder != null && previousEscrow > 0) {
                pendingRefundPlayer = previousBidder;
                pendingRefundAmount = previousEscrow;
                settlementReview = "BID_PENDING_ACCEPTED";
                pendingBidder = null;
                pendingBidderName = "";
                pendingBidAmount = 0;
                if (!persist()) {
                    bidder = previousBidder;
                    bidderName = previousBidderName;
                    currentBid = previousBid;
                    escrow = previousEscrow;
                    settlementReview = previousReview;
                    pendingBidder = intentBidder;
                    pendingBidderName = intentName;
                    pendingBidAmount = intentAmount;
                    pendingRefundPlayer = previousRefundPlayer;
                    pendingRefundAmount = previousRefundAmount;
                    return false;
                }
                if (!queueRefund(pendingRefundPlayer, pendingRefundAmount, "outbid-" + intentTransactionId)) return false;
                pendingRefundPlayer = null;
                pendingRefundAmount = 0;
            }
        }
        settlementReview = "";
        pendingBidder = null;
        pendingBidderName = "";
        pendingBidAmount = 0;
        pendingBidTransactionId = null;
        if (persist()) return true;
        // Restore in-memory frozen intent if the save failed; keep the server frozen.
        bidder = previousBidder;
        bidderName = previousBidderName;
        currentBid = previousBid;
        escrow = previousEscrow;
        settlementReview = previousReview;
        pendingBidder = intentBidder;
        pendingBidderName = intentName;
        pendingBidAmount = intentAmount;
        pendingBidTransactionId = intentTransactionId;
        pendingRefundPlayer = previousRefundPlayer;
        pendingRefundAmount = previousRefundAmount;
        return false;
    }

    public boolean retrySettlement() {
        if (!active || !settlementReview.equals("PAYOUT_ACCOUNT_INVALID")) return false;
        settlementReview = "";
        endsAt = System.currentTimeMillis();
        if (!persist()) {
            settlementReview = "PAYOUT_ACCOUNT_INVALID";
            return false;
        }
        schedule();
        return true;
    }

    public boolean resolveSettlementPaid() {
        boolean alreadyChosen = settlementReview.equals("SETTLEMENT_PAID_PENDING");
        if (!active || settlementReview.isEmpty() || settlementReview.startsWith("BID_PENDING")
                || settlementReview.equals("CANCEL_PENDING") || settlementReview.equals("AUCTION_DATA_CORRUPT")
                || settlementReview.equals("PAYOUT_ACCOUNT_INVALID") || settlementReview.equals("PAYOUT_RETRY")
                || settlementReview.equals("SETTLEMENT_UNPAID_PENDING")
                || bidder == null || item == null) return false;
        if (!alreadyChosen) {
            String previousReview = settlementReview;
            settlementReview = "SETTLEMENT_PAID_PENDING";
            if (!persist()) {
                settlementReview = previousReview;
                return false;
            }
        }
        if (!queueDelivery(bidder, item, "auction-" + auctionTransactionId() + "-winner")) return false;
        if (!recordHistory(bidderName, currentBid, auctionTransactionId())) return false;
        Bukkit.broadcastMessage("§6ClasherBid §eSettlement manually confirmed paid by admin. Winner item queued for claim.");
        return clear();
    }

    public boolean resolveSettlementUnpaid() {
        boolean alreadyChosen = settlementReview.equals("SETTLEMENT_UNPAID_PENDING");
        if (!active || settlementReview.isEmpty() || settlementReview.startsWith("BID_PENDING")
                || settlementReview.equals("START_PENDING") || settlementReview.equals("PAYOUT_COMPLETED")
                || settlementReview.equals("SETTLEMENT_PAID_PENDING")
                || settlementReview.equals("AUCTION_DATA_CORRUPT") || item == null || seller == null) return false;
        if (!alreadyChosen) {
            String previousReview = settlementReview;
            settlementReview = "SETTLEMENT_UNPAID_PENDING";
            if (!persist()) {
                settlementReview = previousReview;
                return false;
            }
        }
        if (bidder != null && escrow > 0) {
            // Persist an unpaid refund liability instead of depositing immediately.
            // The guarded recovery command will require review before any payment.
            if (!queueRefund(bidder, escrow, "settlement-" + auctionTransactionId())) {
                plugin.getLogger().severe("Could not persist bidder refund liability; settlement remains frozen and item was not returned.");
                return false;
            }
        }
        if (!queueDelivery(seller, item, "auction-" + auctionTransactionId() + "-seller-return")) return false;
        Bukkit.broadcastMessage("§6ClasherBid §eSettlement manually confirmed unpaid by admin. Auction item queued for claim.");
        return clear();
    }

    private UUID auctionId;

    private String auctionTransactionId() {
        if (auctionId == null) auctionId = UUID.randomUUID();
        return auctionId.toString();
    }

    /** Adds a refund liability exactly once for a stable transaction key. */
    private boolean queueRefund(UUID playerId, double amount, String transactionId) {
        if (playerId == null || !Double.isFinite(amount) || amount <= 0 || transactionId == null) return false;
        String transactionPath = "recovery.refund-transactions." + transactionId;
        if (plugin.getConfig().contains(transactionPath)) {
            String recordedPlayer = plugin.getConfig().getString(transactionPath + ".player", "");
            double recordedAmount = plugin.getConfig().getDouble(transactionPath + ".amount", Double.NaN);
            if (playerId.toString().equals(recordedPlayer) && Double.isFinite(recordedAmount)
                    && Math.abs(recordedAmount - amount) < 0.000001) return true;
            plugin.getLogger().severe("Refund transaction key collision or corrupt ledger entry: " + transactionId);
            return false;
        }
        String refundPath = "recovery.refund." + playerId;
        double previous = plugin.getConfig().getDouble(refundPath, 0.0);
        if (!Double.isFinite(previous) || previous < 0 || !Double.isFinite(previous + amount)) {
            plugin.getLogger().severe("Invalid refund balance or overflow for " + playerId);
            return false;
        }
        plugin.getConfig().set(transactionPath + ".player", playerId.toString());
        plugin.getConfig().set(transactionPath + ".amount", amount);
        plugin.getConfig().set(refundPath, previous + amount);
        if (persistConfig()) return true;
        plugin.getConfig().set(transactionPath, null);
        plugin.getConfig().set(refundPath, previous > 0 ? previous : null);
        return false;
    }

    /** Records each auction once, even when settlement is retried after a crash. */
    private boolean recordHistory(String winner, double amount, String transactionId) {
        if (transactionId == null) return false;
        if (!recordedHistoryTransactions.add(transactionId)) return true;
        java.util.List<String> previousHistory = new java.util.ArrayList<>(history);
        String entry = item.getType() + " | " + winner + " | " + economy.format(amount) + " | " + java.time.Instant.now();
        history.add(0, entry);
        while (history.size() > 25) history.remove(history.size() - 1);
        YamlConfiguration data = new YamlConfiguration();
        data.set("entries", history);
        data.set("transactions", new java.util.ArrayList<>(recordedHistoryTransactions));
        File temp = new File(plugin.getDataFolder(), "auction-history.yml.tmp");
        try {
            data.save(temp);
            try {
                Files.move(temp.toPath(), historyFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                try {
                    Files.move(temp.toPath(), historyFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException fallbackFailure) {
                    fallbackFailure.addSuppressed(atomicFailure);
                    throw fallbackFailure;
                }
            }
        } catch (IOException ex) {
            recordedHistoryTransactions.remove(transactionId);
            history.clear();
            history.addAll(previousHistory);
            plugin.getLogger().log(Level.WARNING, "Could not safely save auction history; settlement remains recoverable.", ex);
            return false;
        }
        return true;
    }

    private UUID payoutAccount() {
        String configured = plugin.getConfig().getString("payout-player", "");
        if (configured == null || configured.isBlank()) return seller;
        configured = configured.trim();
        OfflinePlayer account = Bukkit.getOfflinePlayer(configured);
        if (!account.isOnline() && !account.hasPlayedBefore()) {
            plugin.getLogger().severe("Configured payout-player has never joined this server: " + configured);
            return null;
        }
        return account.getUniqueId();
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
        String reviewPath = "recovery.refund-review." + playerId;
        String previousReview = plugin.getConfig().getString(reviewPath, "");
        plugin.getConfig().set(reviewPath, "REFUND_PENDING");
        if (!persistConfig()) {
            plugin.getConfig().set(reviewPath, previousReview.isEmpty() ? null : previousReview);
            return false;
        }
        EconomyResponse response;
        try {
            response = economy.depositPlayer(Bukkit.getOfflinePlayer(playerId), amount);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "Vault threw during refund recovery; refund remains frozen for manual review.", ex);
            return false;
        }
        if (response == null || !response.transactionSuccess()) return false; // keep marker for manual review
        plugin.getConfig().set("recovery.refund." + playerId, null);
        plugin.getConfig().set("recovery.refund-review." + playerId, null);
        if (persistConfig()) return true;
        plugin.getConfig().set("recovery.refund." + playerId, amount);
        plugin.getConfig().set("recovery.refund-review." + playerId, "REFUND_PENDING");
        return false;
    }

    /** paid=true clears the debt; paid=false authorizes a later retry after balance verification. */
    public boolean resolveRefundReview(UUID playerId, boolean paid) {
        String reviewPath = "recovery.refund-review." + playerId;
        String refundPath = "recovery.refund." + playerId;
        String previousReview = plugin.getConfig().getString(reviewPath, "");
        double previousAmount = plugin.getConfig().getDouble(refundPath, 0.0);
        if (previousReview.isEmpty()) return false;
        if (paid) plugin.getConfig().set(refundPath, null);
        plugin.getConfig().set(reviewPath, null);
        if (persistConfig()) return true;
        plugin.getConfig().set(reviewPath, previousReview);
        if (previousAmount > 0) plugin.getConfig().set(refundPath, previousAmount);
        return false;
    }

    /** Durable, idempotent delivery queue; actual inventory changes happen only in /claim. */
    private boolean queueDelivery(UUID playerId, ItemStack stack, String transactionId) {
        if (playerId == null || stack == null || stack.getType().isAir() || transactionId == null) return false;
        String tx = "recovery.delivery-transactions." + transactionId;
        if (plugin.getConfig().contains(tx)) {
            String recordedPlayer = plugin.getConfig().getString(tx + ".player", "");
            ItemStack recordedItem = plugin.getConfig().getItemStack(tx + ".item");
            if (playerId.toString().equals(recordedPlayer) && recordedItem != null
                    && recordedItem.isSimilar(stack) && recordedItem.getAmount() == stack.getAmount()) return true;
            plugin.getLogger().severe("Delivery transaction key collision or corrupt ledger entry: " + transactionId);
            return false;
        }
        String path = "pending-delivery." + playerId;
        Object previousValue = plugin.getConfig().get(path);
        java.util.List<?> raw = plugin.getConfig().getList(path);
        java.util.List<ItemStack> pending = new java.util.ArrayList<>();
        if (raw != null) {
            for (Object value : raw) {
                if (value instanceof ItemStack old && !old.getType().isAir() && old.getAmount() > 0) pending.add(old.clone());
                else {
                    plugin.getLogger().severe("Refusing to append delivery: existing pending-delivery data is corrupt for " + playerId);
                    return false;
                }
            }
        } else if (previousValue instanceof ItemStack legacy && !legacy.getType().isAir() && legacy.getAmount() > 0) {
            pending.add(legacy.clone());
        } else if (previousValue != null) {
            plugin.getLogger().severe("Refusing to overwrite unsupported pending-delivery data for " + playerId);
            return false;
        }
        pending.add(stack.clone());
        plugin.getConfig().set(path, pending);
        plugin.getConfig().set(tx + ".player", playerId.toString());
        plugin.getConfig().set(tx + ".item", stack.clone());
        if (persistConfig()) return true;
        plugin.getConfig().set(path, previousValue);
        plugin.getConfig().set(tx, null);
        return false;
    }

    public String claimReview(UUID playerId) {
        return plugin.getConfig().getString("recovery.claim-review." + playerId, "");
    }

    public boolean resolveClaimReview(UUID playerId, boolean delivered) {
        String reviewPath = "recovery.claim-review." + playerId;
        String deliveryPath = "pending-delivery." + playerId;
        String previousReview = plugin.getConfig().getString(reviewPath, "");
        Object previousDelivery = plugin.getConfig().get(deliveryPath);
        if (previousReview.isEmpty()) return false;
        if (delivered) plugin.getConfig().set(deliveryPath, null);
        plugin.getConfig().set(reviewPath, null);
        if (persistConfig()) return true;
        plugin.getConfig().set(reviewPath, previousReview);
        if (delivered) plugin.getConfig().set(deliveryPath, previousDelivery);
        return false;
    }

    public void claim(Player player) {
        String path = "pending-delivery." + player.getUniqueId();
        String reviewPath = "recovery.claim-review." + player.getUniqueId();
        if (!claimReview(player.getUniqueId()).isEmpty()) {
            player.sendMessage("§cPending delivery is frozen for admin review. Contact an admin before retrying.");
            return;
        }
        Object stored = plugin.getConfig().get(path);
        java.util.List<?> raw = plugin.getConfig().getList(path);
        java.util.List<ItemStack> pending = new java.util.ArrayList<>();
        boolean corrupt = false;
        if (raw != null) {
            for (Object value : raw) {
                if (value instanceof ItemStack stack && !stack.getType().isAir() && stack.getAmount() > 0) pending.add(stack.clone());
                else corrupt = true;
            }
        } else if (stored instanceof ItemStack legacyStack && !legacyStack.getType().isAir() && legacyStack.getAmount() > 0) {
            pending.add(legacyStack.clone());
        } else if (stored != null) {
            corrupt = true;
        }
        if (corrupt || (stored != null && pending.isEmpty() && (raw == null || !raw.isEmpty()))) {
            plugin.getConfig().set(reviewPath, "CLAIM_DATA_CORRUPT");
            if (!persistConfig()) plugin.getLogger().severe("Corrupt pending delivery data could not be marked for review: " + player.getUniqueId());
            player.sendMessage("§cPending delivery data is invalid and has been frozen. Contact an admin; no items were delivered.");
            return;
        }
        if (pending.isEmpty()) return;

        // Persist a claim intent before inventory changes. A crash in the delivery
        // window freezes this claim for manual reconciliation instead of blind retry.
        String previousReview = plugin.getConfig().getString(reviewPath, "");
        plugin.getConfig().set(reviewPath, "CLAIM_PENDING");
        if (!persistConfig()) {
            plugin.getConfig().set(reviewPath, previousReview.isEmpty() ? null : previousReview);
            player.sendMessage("§cCould not save claim review marker. No items were delivered; contact an admin.");
            return;
        }

        java.util.List<ItemStack> remaining = new java.util.ArrayList<>();
        int deliveredAmount = 0;
        for (ItemStack stack : pending) {
            if (stack == null || stack.getType().isAir()) continue;
            java.util.Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack.clone());
            int overflowAmount = overflow.values().stream().mapToInt(ItemStack::getAmount).sum();
            deliveredAmount += stack.getAmount() - overflowAmount;
            if (!overflow.isEmpty()) remaining.addAll(overflow.values());
        }
        plugin.getConfig().set(path, remaining.isEmpty() ? null : remaining);
        plugin.getConfig().set(reviewPath, null);
        if (!persistConfig()) {
            plugin.getConfig().set(path, stored);
            plugin.getConfig().set(reviewPath, "CLAIM_PENDING");
            player.sendMessage("§eDelivery was attempted but its final state could not be saved. Claim is frozen for admin review; do not retry.");
            return;
        }
        if (deliveredAmount > 0) player.sendMessage("§aDelivered " + deliveredAmount + " pending auction item(s).");
        if (!remaining.isEmpty()) player.sendMessage("§eInventory full. Remaining items will be kept for later.");
    }

    /** Writes recovery/config state to a temporary file and replaces config.yml only after a successful save. */
    private boolean persistConfig() {
        File target = new File(plugin.getDataFolder(), "config.yml");
        File temp = new File(plugin.getDataFolder(), "config.yml.tmp");
        try {
            plugin.getConfig().save(temp);
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                try {
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException fallbackFailure) {
                    fallbackFailure.addSuppressed(atomicFailure);
                    throw fallbackFailure;
                }
            }
            return true;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not safely persist recovery/config state. Related transaction remains frozen.", ex);
            return false;
        }
    }

    private boolean clear() {
        ItemStack oldItem = item;
        UUID oldSeller = seller, oldBidder = bidder;
        String oldName = bidderName, oldReview = settlementReview;
        double oldBid = currentBid, oldEscrow = escrow;
        long oldEndsAt = endsAt;
        active = false; item = null; seller = null; bidder = null;
        bidderName = ""; currentBid = 0; escrow = 0; endsAt = 0; settlementReview = "";
        if (!persist()) {
            active = true; item = oldItem; seller = oldSeller; bidder = oldBidder;
            bidderName = oldName; currentBid = oldBid; escrow = oldEscrow; endsAt = oldEndsAt;
            settlementReview = oldReview;
            plugin.getLogger().severe("Delivery queued but auction clear failed; original settlement state restored for admin review.");
            return false;
        }
        if (timer != null) timer.cancel();
        timer = null;
        return true;
    }

    public void load() {
        if (historyFile.exists()) {
            YamlConfiguration savedHistory = YamlConfiguration.loadConfiguration(historyFile);
            history.clear();
            history.addAll(savedHistory.getStringList("entries"));
            recordedHistoryTransactions.clear();
            recordedHistoryTransactions.addAll(savedHistory.getStringList("transactions"));
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
        pendingBidder = parseUuid(yaml.getString("pendingBidder"));
        pendingBidderName = yaml.getString("pendingBidderName", "");
        pendingBidAmount = yaml.getDouble("pendingBidAmount", 0.0);
        pendingBidTransactionId = parseUuid(yaml.getString("pendingBidTransactionId"));
        pendingRefundPlayer = parseUuid(yaml.getString("pendingRefundPlayer"));
        pendingRefundAmount = yaml.getDouble("pendingRefundAmount", 0.0);
        auctionId = parseUuid(yaml.getString("auctionId"));
        boolean migratedAuctionId = auctionId == null;
        if (migratedAuctionId) {
            auctionId = UUID.randomUUID();
            if (settlementReview.isEmpty()) settlementReview = "AUCTION_ID_MIGRATION_PENDING";
        }
        active = item != null && !item.getType().isAir() && seller != null;
        if (!active) {
            recoveryBlocked = true;
            plugin.getLogger().severe("Saved auction is marked active but its item or seller data is missing/invalid. The original file was preserved; new auctions are disabled until manual file-level recovery.");
            return;
        }
        boolean invalidCoreState = !Double.isFinite(currentBid) || currentBid < 0
                || !Double.isFinite(escrow) || escrow < 0 || endsAt <= 0
                || (bidder == null && escrow > 0)
                || (bidder != null && (escrow <= 0 || Math.abs(escrow - currentBid) > 0.000001))
                || ((pendingRefundPlayer == null) != (pendingRefundAmount <= 0))
                || (pendingRefundPlayer != null && !Double.isFinite(pendingRefundAmount));
        boolean invalidPendingBid = settlementReview.startsWith("BID_PENDING")
                && (pendingBidTransactionId == null
                || (settlementReview.equals("BID_PENDING") && (pendingBidder == null
                    || !Double.isFinite(pendingBidAmount) || pendingBidAmount <= 0))
                || (settlementReview.equals("BID_PENDING_ACCEPTED") && pendingBidder != null));
        if (invalidCoreState || invalidPendingBid) {
            settlementReview = "AUCTION_DATA_CORRUPT";
            plugin.getLogger().severe("Saved auction data failed integrity checks. Auction frozen; inspect active-auction.yml and economy balances before recovery.");
        }
        if (active && migratedAuctionId && !invalidCoreState && !invalidPendingBid && !persist()) {
            plugin.getLogger().severe("Legacy auction ID migration could not be persisted; auction remains frozen for admin settlement review.");
        }
        if (active) {
            if (settlementReview.equals("PAYOUT_RETRY")) {
                if (endsAt <= System.currentTimeMillis()) finish();
                else schedule();
            } else if (!settlementReview.isEmpty()) {
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
        // Never overwrite the original file when startup detected an invalid active auction.
        if (!recoveryBlocked) persist();
    }

    private boolean persist() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("active", active);
        yaml.set("settlementReview", settlementReview);
        yaml.set("pendingBidder", pendingBidder == null ? null : pendingBidder.toString());
        yaml.set("pendingBidderName", pendingBidderName);
        yaml.set("pendingBidAmount", pendingBidAmount);
        yaml.set("pendingBidTransactionId", pendingBidTransactionId == null ? null : pendingBidTransactionId.toString());
        yaml.set("pendingRefundPlayer", pendingRefundPlayer == null ? null : pendingRefundPlayer.toString());
        yaml.set("pendingRefundAmount", pendingRefundAmount);
        yaml.set("auctionId", auctionId == null ? null : auctionId.toString());
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
                return false;
            }
        }
        return true;
    }
}