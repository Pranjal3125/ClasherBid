package network.clasherbid;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public final class AuctionGui implements Listener, CommandExecutor {
    private static final String TITLE = "§8ClasherBid §7| §6Live Auction";
    private static final String CONFIRM_TITLE = "§8Confirm bid";
    private final ClasherBidPlugin plugin;
    private final AuctionManager auctions;
    private final NamespacedKey actionKey;
    private final Set<UUID> awaitingCustom = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<UUID, Double> pendingConfirm = new HashMap<>();

    AuctionGui(ClasherBidPlugin plugin, AuctionManager auctions) {
        this.plugin = plugin;
        this.auctions = auctions;
        this.actionKey = new NamespacedKey(plugin, "gui-action");
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshOpenAuctions, 20L, 20L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        open(player);
        return true;
    }

    public void open(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, TITLE);
        if (!auctions.isActive()) {
            gui.setItem(13, button(Material.BARRIER, "§cNo active auction", List.of("§7Check again later."), "none"));
        } else if (!auctions.settlementReview().isEmpty()) {
            gui.setItem(13, button(Material.BARRIER, "§cAuction frozen for review",
                    List.of("§7Bidding is temporarily disabled."), "none"));
        } else {
            gui.setItem(13, auctionDisplay());

            double balance = plugin.economy().getBalance(player);
            gui.setItem(10, button(Material.EMERALD, "§aBid minimum",
                    List.of("§7Amount: §e" + plugin.economy().format(auctions.nextMinimum()),
                            "§7Balance: §e" + plugin.economy().format(balance)), "minimum"));
            gui.setItem(11, button(Material.GOLD_INGOT, "§6Bid +500",
                    List.of("§7Amount: §e" + plugin.economy().format(auctions.currentBid() + 500),
                            "§7Balance: §e" + plugin.economy().format(balance)), "plus500"));
            gui.setItem(12, button(Material.DIAMOND, "§bBid +1,000",
                    List.of("§7Amount: §e" + plugin.economy().format(auctions.currentBid() + 1000),
                            "§7Balance: §e" + plugin.economy().format(balance)), "plus1000"));
            gui.setItem(14, button(Material.EMERALD_BLOCK, "§aBid +5,000",
                    List.of("§7Amount: §e" + plugin.economy().format(auctions.currentBid() + 5000),
                            "§7Balance: §e" + plugin.economy().format(balance)), "plus5000"));
            gui.setItem(15, button(Material.PAPER, "§eCustom bid", List.of("§7Enter amount in chat"), "custom"));
            gui.setItem(22, button(Material.CLOCK, "§eRefresh", List.of("§7Refresh auction details"), "refresh"));
        }
        player.openInventory(gui);
    }

    private ItemStack auctionDisplay() {
        ItemStack display = auctions.auctionItem();
        if (display == null) return new ItemStack(Material.BARRIER);
        ItemMeta meta = display.getItemMeta();
        meta.setDisplayName("§6§lAuction Item");
        List<String> lore = new ArrayList<>(List.of(
                "§7" + (auctions.highestBidder() == null ? "Starting price: §a" : "Current bid: §a")
                        + plugin.economy().format(auctions.currentBid()),
                "§7Minimum next bid: §e" + plugin.economy().format(auctions.nextMinimum()),
                "§7Highest bidder: §f" + (auctions.highestBidderName().isEmpty() ? "None" : auctions.highestBidderName()),
                "§7Time remaining: §e" + auctions.secondsLeft() + "s", "",
                "§aChoose a bid amount below.", "", "§6Recent bids:"));
        if (auctions.recentBids().isEmpty()) lore.add("§7No bids yet");
        else auctions.recentBids().stream().limit(5).forEach(b -> lore.add("§7" + b));
        meta.setLore(lore);
        display.setItemMeta(meta);
        return display;
    }

    private void refreshOpenAuctions() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (TITLE.equals(player.getOpenInventory().getTitle())) {
                Inventory top = player.getOpenInventory().getTopInventory();
                if (!auctions.isActive()) {
                    top.setItem(13, button(Material.BARRIER, "§cNo active auction", List.of("§7Check again later."), "none"));
                    for (int slot : new int[]{10, 11, 12, 14, 15, 22}) top.setItem(slot, null);
                    continue;
                }
                if (!auctions.settlementReview().isEmpty()) {
                    top.setItem(13, button(Material.BARRIER, "§cAuction frozen for review",
                            List.of("§7Bidding is temporarily disabled."), "none"));
                    for (int slot : new int[]{10, 11, 12, 14, 15, 22}) top.setItem(slot, null);
                    continue;
                }
                top.setItem(13, auctionDisplay());
                double balance = plugin.economy().getBalance(player);
                top.setItem(10, button(Material.EMERALD, "§aBid minimum",
                        List.of("§7Amount: §e" + plugin.economy().format(auctions.nextMinimum()),
                                "§7Balance: §e" + plugin.economy().format(balance)), "minimum"));
                top.setItem(11, button(Material.GOLD_INGOT, "§6Bid +500",
                        List.of("§7Amount: §e" + plugin.economy().format(auctions.currentBid() + 500),
                                "§7Balance: §e" + plugin.economy().format(balance)), "plus500"));
                top.setItem(12, button(Material.DIAMOND, "§bBid +1,000",
                        List.of("§7Amount: §e" + plugin.economy().format(auctions.currentBid() + 1000),
                                "§7Balance: §e" + plugin.economy().format(balance)), "plus1000"));
                top.setItem(14, button(Material.EMERALD_BLOCK, "§aBid +5,000",
                        List.of("§7Amount: §e" + plugin.economy().format(auctions.currentBid() + 5000),
                                "§7Balance: §e" + plugin.economy().format(balance)), "plus5000"));
                top.setItem(15, button(Material.PAPER, "§eCustom bid", List.of("§7Enter amount in chat"), "custom"));
                top.setItem(22, button(Material.CLOCK, "§eRefresh", List.of("§7Refresh auction details"), "refresh"));
            }
        }
    }

    private ItemStack button(Material material, String name, List<String> lore, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (!TITLE.equals(title) && !CONFIRM_TITLE.equals(title)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getView().getTopInventory()) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String action = clicked.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;

        switch (action) {
            case "minimum" -> confirm(player, auctions.nextMinimum());
            case "plus500" -> confirm(player, auctions.currentBid() + 500);
            case "plus1000" -> confirm(player, auctions.currentBid() + 1000);
            case "plus5000" -> confirm(player, auctions.currentBid() + 5000);
            case "custom" -> {
                awaitingCustom.add(player.getUniqueId());
                player.closeInventory();
                player.sendMessage("§6ClasherBid §7Enter your bid amount in chat, or type §ccancel§7.");
            }
            case "refresh" -> open(player);
            case "confirm" -> {
                Double amount = pendingConfirm.remove(player.getUniqueId());
                if (amount != null) place(player, amount);
            }
            case "back" -> {
                pendingConfirm.remove(player.getUniqueId());
                open(player);
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (CONFIRM_TITLE.equals(event.getView().getTitle())) {
            pendingConfirm.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (TITLE.equals(event.getView().getTitle()) || CONFIRM_TITLE.equals(event.getView().getTitle()))
            event.setCancelled(true);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!awaitingCustom.remove(id)) return;
        event.setCancelled(true);
        String input = event.getMessage().trim();
        if (input.equalsIgnoreCase("cancel")) {
            Bukkit.getScheduler().runTask(plugin, () -> event.getPlayer().sendMessage("§7Custom bid cancelled."));
            return;
        }
        if (!input.matches("(?:\\d+|\\d{1,3}(?:,\\d{3})+)(?:\\.\\d+)?")) {
            Bukkit.getScheduler().runTask(plugin, () -> event.getPlayer().sendMessage("§cInvalid amount. Use digits with optional thousands separators, e.g. 1500 or 1,500. Reopen /bid and try again."));
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(input.replace(",", ""));
        } catch (NumberFormatException ex) {
            Bukkit.getScheduler().runTask(plugin, () -> event.getPlayer().sendMessage("§cInvalid amount. Reopen /bid and try again."));
            return;
        }
        final double finalAmount = amount;
        Bukkit.getScheduler().runTask(plugin, () -> confirm(event.getPlayer(), finalAmount));
    }

    private void confirm(Player player, double amount) {
        if (!Double.isFinite(amount) || amount <= 0 || amount > plugin.economy().getBalance(player)) {
            player.sendMessage("§cBid amount exceeds your balance or is invalid.");
            open(player);
            return;
        }
        pendingConfirm.put(player.getUniqueId(), amount);
        Inventory inv = Bukkit.createInventory(null, 9, CONFIRM_TITLE);
        inv.setItem(3, button(Material.GOLD_INGOT, "§eConfirm " + plugin.economy().format(amount),
                List.of("§7Funds will be escrowed if accepted."), "confirm"));
        inv.setItem(5, button(Material.BARRIER, "§cCancel", List.of(), "back"));
        player.openInventory(inv);
    }

    private void place(Player player, double amount) {
        player.sendMessage(auctions.placeBid(player, amount));
        open(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        awaitingCustom.remove(id);
        pendingConfirm.remove(id);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        auctions.claim(event.getPlayer());
    }
}
