package network.clasherbid.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import network.clasherbid.auction.AuctionManager;
import network.clasherbid.config.MessageService;
import network.clasherbid.config.PluginConfig;
import network.clasherbid.util.FormatUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interactive Paper GUI for live bidding, bid confirmation, pending item claims, and auction history.
 */
public final class AuctionGui implements Listener {
    private static final int MAIN_SIZE = 45;
    private static final int CONFIRM_SIZE = 27;
    private static final int HISTORY_SIZE = 36;

    private static final Material[] QUICK_MATERIALS = {
            Material.GOLD_INGOT,
            Material.DIAMOND,
            Material.EMERALD_BLOCK
    };
    private static final int[] QUICK_SLOTS = {21, 22, 23};

    private final JavaPlugin plugin;
    private final Economy economy;
    private final PluginConfig config;
    private final MessageService messages;
    private final AuctionManager auctions;
    private final NamespacedKey actionKey;
    private final NamespacedKey amountKey;

    private final Map<UUID, BukkitTask> awaitingCustom = new ConcurrentHashMap<>();
    private final Map<UUID, Double> pendingConfirm = new HashMap<>();
    private final BukkitTask refreshTask;

    public AuctionGui(
            JavaPlugin plugin,
            Economy economy,
            PluginConfig config,
            MessageService messages,
            AuctionManager auctions
    ) {
        this.plugin = plugin;
        this.economy = economy;
        this.config = config;
        this.messages = messages;
        this.auctions = auctions;
        this.actionKey = new NamespacedKey(plugin, "gui-action");
        this.amountKey = new NamespacedKey(plugin, "gui-amount");
        this.refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshOpenAuctions, 20L, 20L);
    }

    public void shutdown() {
        if (refreshTask != null) refreshTask.cancel();
        for (BukkitTask task : awaitingCustom.values()) {
            task.cancel();
        }
        awaitingCustom.clear();
        pendingConfirm.clear();
    }

    // -------------------------------------------------------------------------
    // Opening & rendering menus
    // -------------------------------------------------------------------------

    public void open(Player player) {
        AuctionMenuHolder holder = new AuctionMenuHolder(AuctionMenuHolder.ViewType.LIVE_AUCTION);
        Component title = messages.parse("<dark_gray><bold>ClasherBid</bold></dark_gray> <gray>|</gray> <gold>Live Auction</gold>");
        Inventory inv = Bukkit.createInventory(holder, MAIN_SIZE, title);
        holder.setInventory(inv);
        renderMain(inv, player);
        player.openInventory(inv);
    }

    public void openHistory(Player player) {
        AuctionMenuHolder holder = new AuctionMenuHolder(AuctionMenuHolder.ViewType.HISTORY);
        Component title = messages.parse("<dark_gray><bold>ClasherBid</bold></dark_gray> <gray>|</gray> <yellow>Auction History</yellow>");
        Inventory inv = Bukkit.createInventory(holder, HISTORY_SIZE, title);
        holder.setInventory(inv);

        fillBorder(inv, HISTORY_SIZE);
        List<String> entries = auctions.auctionHistory();
        if (entries.isEmpty()) {
            inv.setItem(13, button(
                    Material.PAPER,
                    "<gray>No recorded auctions yet</gray>",
                    List.of("<dark_gray>Completed auctions will appear here.</dark_gray>"),
                    "none",
                    0.0,
                    false
            ));
        } else {
            int slot = 10;
            for (int i = 0; i < entries.size() && slot <= 25; i++) {
                if (slot % 9 == 0) slot++;
                if (slot % 9 == 8) {
                    slot += 2;
                    if (slot > 25) break;
                }
                String[] parts = entries.get(i).split(" \\| ");
                String itemPart = parts.length > 0 ? parts[0] : entries.get(i);
                String winnerPart = parts.length > 1 ? parts[1] : "Unknown";
                String amountPart = parts.length > 2 ? parts[2] : "-";
                String timePart = parts.length > 3 ? parts[3] : "";
                List<String> lore = new ArrayList<>();
                lore.add("<gray>Winner:</gray> <yellow>" + escapeMini(winnerPart) + "</yellow>");
                lore.add("<gray>Final Price:</gray> <green>" + escapeMini(amountPart) + "</green>");
                if (!timePart.isEmpty()) {
                    lore.add("<gray>Ended:</gray> <white>" + escapeMini(timePart) + "</white>");
                }
                inv.setItem(slot++, button(
                        Material.BOOK,
                        "<gold>#" + (i + 1) + "</gold> <white>" + escapeMini(itemPart) + "</white>",
                        lore,
                        "none",
                        0.0,
                        false
                ));
            }
        }
        inv.setItem(31, button(
                Material.ARROW,
                "<yellow>Back to Auction</yellow>",
                List.of("<gray>Return to the live auction menu.</gray>"),
                "back",
                0.0,
                false
        ));
        player.openInventory(inv);
    }

    public void openConfirm(Player player, double amount) {
        if (!auctions.isActive()) {
            messages.send(player, "player.no-active-auction");
            messages.playSound(player, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f);
            return;
        }
        if (!auctions.settlementReview().isEmpty()) {
            messages.send(player, "player.auction-frozen");
            messages.playSound(player, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f);
            return;
        }
        double balance = economy.getBalance(player);
        if (!Double.isFinite(amount) || amount <= 0.0 || amount > balance) {
            messages.send(player, "player.insufficient-balance");
            messages.playSound(player, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f);
            open(player);
            return;
        }
        double minNext = auctions.nextMinimum();
        if (amount < minNext) {
            messages.send(player, "player.minimum-bid", Placeholder.unparsed("amount", economy.format(minNext)));
            messages.playSound(player, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f);
            open(player);
            return;
        }

        pendingConfirm.put(player.getUniqueId(), amount);
        AuctionMenuHolder holder = new AuctionMenuHolder(AuctionMenuHolder.ViewType.CONFIRM_BID);
        Component title = messages.parse("<dark_gray><bold>Confirm Bid</bold></dark_gray> <gray>|</gray> <green>" + escapeMini(economy.format(amount)) + "</green>");
        Inventory inv = Bukkit.createInventory(holder, CONFIRM_SIZE, title);
        holder.setInventory(inv);

        fillBorder(inv, CONFIRM_SIZE);
        inv.setItem(13, auctionDisplayItem());

        double afterBalance = Math.max(0.0, balance - amount);
        inv.setItem(11, button(
                Material.LIME_CONCRETE,
                "<green><bold>Confirm Bid</bold></green>",
                List.of(
                        "<gray>Bid Amount:</gray> <yellow>" + escapeMini(economy.format(amount)) + "</yellow>",
                        "<gray>Current Balance:</gray> <white>" + escapeMini(economy.format(balance)) + "</white>",
                        "<gray>After Escrow:</gray> <aqua>" + escapeMini(economy.format(afterBalance)) + "</aqua>",
                        "",
                        "<dark_gray>Funds are held in escrow and refunded</dark_gray>",
                        "<dark_gray>automatically if you are outbid.</dark_gray>"
                ),
                "confirm",
                amount,
                true
        ));
        inv.setItem(15, button(
                Material.RED_CONCRETE,
                "<red><bold>Cancel</bold></red>",
                List.of("<gray>Return to the auction menu.</gray>"),
                "back",
                0.0,
                false
        ));
        player.openInventory(inv);
    }

    private void renderMain(Inventory inv, Player player) {
        inv.clear();
        fillBorder(inv, MAIN_SIZE);

        double balance = economy.getBalance(player);
        int pendingCount = auctions.pendingDeliveryCount(player.getUniqueId());
        double pendingRefund = auctions.pendingRefund(player.getUniqueId());

        if (!auctions.isActive()) {
            inv.setItem(22, button(
                    Material.CLOCK,
                    "<red><bold>No Active Auction</bold></red>",
                    List.of(
                            "<gray>There is no live auction right now.</gray>",
                            "<gray>Check back soon or view past auctions below!</gray>"
                    ),
                    "none",
                    0.0,
                    false
            ));
        } else if (!auctions.settlementReview().isEmpty()) {
            inv.setItem(13, auctionDisplayItem());
            inv.setItem(22, button(
                    Material.BARRIER,
                    "<red><bold>Auction Paused for Review</bold></red>",
                    List.of(
                            "<gray>Bidding is temporarily paused while an</gray>",
                            "<gray>administrator verifies a transaction.</gray>"
                    ),
                    "none",
                    0.0,
                    false
            ));
        } else {
            inv.setItem(13, auctionDisplayItem());

            // Minimum bid button
            double minBid = auctions.nextMinimum();
            boolean canAffordMin = balance >= minBid;
            inv.setItem(20, button(
                    Material.EMERALD,
                    "<green><bold>Bid Minimum</bold></green>",
                    List.of(
                            "<gray>Amount:</gray> <yellow>" + escapeMini(economy.format(minBid)) + "</yellow>",
                            "<gray>Your Balance:</gray> <white>" + escapeMini(economy.format(balance)) + "</white>",
                            "",
                            canAffordMin ? "<green>Click to bid!</green>" : "<red>Insufficient funds</red>"
                    ),
                    "bid",
                    minBid,
                    canAffordMin
            ));

            // Quick-bid increment buttons
            List<Double> increments = config.quickBidIncrements();
            for (int i = 0; i < QUICK_SLOTS.length && i < increments.size(); i++) {
                double inc = increments.get(i);
                double targetAmount = Math.max(minBid, auctions.currentBid() + inc);
                boolean canAfford = balance >= targetAmount;
                inv.setItem(QUICK_SLOTS[i], button(
                        QUICK_MATERIALS[i],
                        "<gold><bold>+" + escapeMini(economy.format(inc)) + "</bold></gold>",
                        List.of(
                                "<gray>Total Bid:</gray> <yellow>" + escapeMini(economy.format(targetAmount)) + "</yellow>",
                                "<gray>Your Balance:</gray> <white>" + escapeMini(economy.format(balance)) + "</white>",
                                "",
                                canAfford ? "<green>Click to bid!</green>" : "<red>Insufficient funds</red>"
                        ),
                        "bid",
                        targetAmount,
                        false
                ));
            }

            // Custom bid button
            inv.setItem(24, button(
                    Material.OAK_SIGN,
                    "<aqua><bold>Custom Bid</bold></aqua>",
                    List.of(
                            "<gray>Enter any custom amount in chat.</gray>",
                            "<dark_gray>Supports 1500, 1,500, 2.5k, 1m</dark_gray>",
                            "",
                            "<yellow>Click to type amount</yellow>"
                    ),
                    "custom",
                    0.0,
                    false
            ));
        }

        // Bottom bar: Player info (38), Claim pending items (40), History (42), Refresh (44)
        boolean isTopBidder = auctions.isActive() && player.getUniqueId().equals(auctions.highestBidder());
        List<String> statusLore = new ArrayList<>();
        statusLore.add("<gray>Balance:</gray> <green>" + escapeMini(economy.format(balance)) + "</green>");
        if (auctions.isActive()) {
            statusLore.add("<gray>Status:</gray> " + (isTopBidder
                    ? "<green>Highest Bidder!</green>"
                    : "<gray>Not leading</gray>"));
        }
        if (pendingRefund > 0.0) {
            statusLore.add("<gray>Pending Refund:</gray> <yellow>" + escapeMini(economy.format(pendingRefund)) + "</yellow>");
        }
        inv.setItem(38, button(
                Material.GOLD_NUGGET,
                "<yellow><bold>Your Account</bold></yellow>",
                statusLore,
                "none",
                0.0,
                isTopBidder
        ));

        if (pendingCount > 0) {
            inv.setItem(40, button(
                    Material.CHEST,
                    "<green><bold>Claim Pending Items</bold></green> <yellow>(" + pendingCount + ")</yellow>",
                    List.of(
                            "<gray>You have</gray> <yellow>" + pendingCount + "</yellow> <gray>item(s) waiting!</gray>",
                            "",
                            "<green>Click to claim now</green>"
                    ),
                    "claim",
                    0.0,
                    true
            ));
        } else {
            inv.setItem(40, button(
                    Material.BARREL,
                    "<gray>Claim Pending Items</gray>",
                    List.of("<dark_gray>No pending auction items.</dark_gray>"),
                    "claim",
                    0.0,
                    false
            ));
        }

        inv.setItem(42, button(
                Material.BOOK,
                "<gold><bold>Auction History</bold></gold>",
                List.of(
                        "<gray>View the last " + auctions.auctionHistory().size() + " completed auction(s).</gray>",
                        "",
                        "<yellow>Click to view</yellow>"
                ),
                "history",
                0.0,
                false
        ));

        inv.setItem(44, button(
                Material.CLOCK,
                "<yellow>Refresh</yellow>",
                List.of("<gray>Updates automatically every second.</gray>"),
                "refresh",
                0.0,
                false
        ));
    }

    private ItemStack auctionDisplayItem() {
        ItemStack display = auctions.auctionItem();
        if (display == null) return new ItemStack(Material.BARRIER);
        ItemMeta meta = display.getItemMeta();
        if (meta == null) return display;

        List<Component> lore = new ArrayList<>();
        List<Component> existingLore = meta.lore();
        if (existingLore != null && !existingLore.isEmpty()) {
            lore.addAll(existingLore);
            lore.add(Component.empty());
        }

        lore.add(FormatUtil.noItalic(messages.parse("<dark_gray>────────────────────────</dark_gray>")));
        lore.add(FormatUtil.noItalic(messages.parse("<gold><bold>Live Auction Details</bold></gold>")));
        String priceLabel = auctions.highestBidder() == null ? "Opening Price" : "Current Bid";
        lore.add(FormatUtil.noItalic(messages.parse("<gray>" + priceLabel + ":</gray> <green><bold>" + escapeMini(economy.format(auctions.currentBid())) + "</bold></green>")));
        lore.add(FormatUtil.noItalic(messages.parse("<gray>Next Minimum:</gray> <yellow>" + escapeMini(economy.format(auctions.nextMinimum())) + "</yellow>")));
        String leader = auctions.highestBidderName().isEmpty() ? "None yet" : auctions.highestBidderName();
        lore.add(FormatUtil.noItalic(messages.parse("<gray>Highest Bidder:</gray> <white>" + escapeMini(leader) + "</white>")));
        lore.add(FormatUtil.noItalic(messages.parse("<gray>Seller:</gray> <white>" + escapeMini(auctions.sellerName()) + "</white>")));
        lore.add(FormatUtil.noItalic(messages.parse("<gray>Time Remaining:</gray> <yellow><bold>" + FormatUtil.formatDuration(auctions.secondsLeft()) + "</bold></yellow>")));
        if (config.antiSnipingEnabled()) {
            lore.add(FormatUtil.noItalic(messages.parse("<dark_gray>Anti-Snipe: +" + config.antiSnipingExtendSeconds() + "s if bid within " + config.antiSnipingTriggerSeconds() + "s</dark_gray>")));
        }
        lore.add(Component.empty());
        lore.add(FormatUtil.noItalic(messages.parse("<gold>Recent Bids:</gold>")));
        List<String> recent = auctions.recentBids();
        if (recent.isEmpty()) {
            lore.add(FormatUtil.noItalic(messages.parse("<dark_gray>  No bids yet — be the first!</dark_gray>")));
        } else {
            for (int i = 0; i < Math.min(5, recent.size()); i++) {
                lore.add(FormatUtil.noItalic(messages.parse("<gray>  • " + escapeMini(recent.get(i)) + "</gray>")));
            }
        }
        lore.add(FormatUtil.noItalic(messages.parse("<dark_gray>────────────────────────</dark_gray>")));

        meta.lore(lore);
        display.setItemMeta(meta);
        return display;
    }

    private void refreshOpenAuctions() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof AuctionMenuHolder holder
                    && holder.viewType() == AuctionMenuHolder.ViewType.LIVE_AUCTION) {
                renderMain(top, player);
            }
        }
    }

    private void fillBorder(Inventory inv, int size) {
        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE);
        ItemStack corner = pane(Material.ORANGE_STAINED_GLASS_PANE);
        int rows = size / 9;
        for (int slot = 0; slot < size; slot++) {
            int row = slot / 9;
            int col = slot % 9;
            boolean isBorder = row == 0 || row == rows - 1 || col == 0 || col == 8;
            if (!isBorder) continue;
            boolean isCorner = (row == 0 || row == rows - 1) && (col == 0 || col == 8);
            inv.setItem(slot, isCorner ? corner : filler);
        }
    }

    private ItemStack pane(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            meta.setHideTooltip(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack button(
            Material material,
            String nameMini,
            List<String> loreMini,
            String action,
            double amount,
            boolean glow
    ) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(FormatUtil.noItalic(messages.parse(nameMini)));
            if (loreMini != null && !loreMini.isEmpty()) {
                List<Component> lore = new ArrayList<>(loreMini.size());
                for (String line : loreMini) {
                    lore.add(line.isEmpty() ? Component.empty() : FormatUtil.noItalic(messages.parse(line)));
                }
                meta.lore(lore);
            }
            if (glow) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
            if (amount > 0.0) {
                meta.getPersistentDataContainer().set(amountKey, PersistentDataType.DOUBLE, amount);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String escapeMini(String raw) {
        return raw == null ? "" : raw.replace("<", "\\<");
    }

    // -------------------------------------------------------------------------
    // Event listeners
    // -------------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof AuctionMenuHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null || action.equals("none")) return;

        messages.playSound(player, Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        switch (action) {
            case "bid" -> {
                Double amount = meta.getPersistentDataContainer().get(amountKey, PersistentDataType.DOUBLE);
                if (amount != null) openConfirm(player, amount);
            }
            case "custom" -> startCustomBidPrompt(player);
            case "claim" -> {
                player.closeInventory();
                auctions.claim(player, true);
            }
            case "history" -> openHistory(player);
            case "refresh" -> open(player);
            case "confirm" -> {
                Double amount = pendingConfirm.remove(player.getUniqueId());
                if (amount != null) place(player, amount);
            }
            case "back" -> {
                pendingConfirm.remove(player.getUniqueId());
                open(player);
            }
            default -> {
                // Ignore unknown actions.
            }
        }
    }

    private void startCustomBidPrompt(Player player) {
        UUID id = player.getUniqueId();
        BukkitTask previous = awaitingCustom.remove(id);
        if (previous != null) previous.cancel();

        long timeoutSec = config.customBidTimeoutSeconds();
        BukkitTask timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (awaitingCustom.remove(id) != null && player.isOnline()) {
                messages.send(player, "player.custom-bid-timeout");
            }
        }, timeoutSec * 20L);
        awaitingCustom.put(id, timeoutTask);

        player.closeInventory();
        messages.send(player, "player.custom-bid-prompt",
                Placeholder.unparsed("timeout", String.valueOf(timeoutSec)));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof AuctionMenuHolder holder
                && holder.viewType() == AuctionMenuHolder.ViewType.CONFIRM_BID) {
            pendingConfirm.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof AuctionMenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncChat(AsyncChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        BukkitTask timeoutTask = awaitingCustom.remove(id);
        if (timeoutTask == null) return;

        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            timeoutTask.cancel();
            Player player = event.getPlayer();
            if (!player.isOnline()) return;
            if (input.equalsIgnoreCase("cancel")) {
                messages.send(player, "player.custom-bid-cancelled");
                return;
            }
            OptionalDouble parsed = FormatUtil.parseAmount(input);
            if (parsed.isEmpty()) {
                messages.send(player, "player.custom-bid-invalid");
                messages.playSound(player, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f);
                return;
            }
            openConfirm(player, parsed.getAsDouble());
        });
    }

    private void place(Player player, double amount) {
        AuctionManager.BidResult result = auctions.placeBid(player, amount);
        player.sendMessage(result.message());
        if (result.accepted()) {
            messages.playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.25f);
        } else {
            messages.playSound(player, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f);
        }
        open(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        BukkitTask timeout = awaitingCustom.remove(id);
        if (timeout != null) timeout.cancel();
        pendingConfirm.remove(id);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Attempt automatic delivery on join (delayed by 1 second so player inventory is ready).
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                auctions.claim(player, false);
            }
        }, 20L);
    }
}
