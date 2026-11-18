package network.clasherbid;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public final class AuctionGui implements Listener, CommandExecutor {
    private static final String TITLE = "§8ClasherBid §7| §6Live Auction";
    private final ClasherBidPlugin plugin;
    private final AuctionManager auctions;
    private final NamespacedKey actionKey;
    private final Set<UUID> awaitingCustom = new HashSet<>();

    AuctionGui(ClasherBidPlugin plugin, AuctionManager auctions) {
        this.plugin = plugin;
        this.auctions = auctions;
        this.actionKey = new NamespacedKey(plugin, "gui-action");
    }

    @Override public boolean onCommand(CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
        open(player);
        return true;
    }

    public void open(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, TITLE);
        if (!auctions.isActive()) {
            gui.setItem(13, button(Material.BARRIER, "§cNo active auction", List.of("§7Check again later."), "none"));
        } else {
            ItemStack display = auctions.auctionItem();
            ItemMeta meta = display.getItemMeta();
            meta.setDisplayName("§6§lAuction Item");
            meta.setLore(List.of(
                    "§7Current bid: §a" + plugin.economy().format(auctions.currentBid()),
                    "§7Minimum next bid: §e" + plugin.economy().format(auctions.nextMinimum()),
                    "§7Highest bidder: §f" + (auctions.highestBidderName().isEmpty() ? "None" : auctions.highestBidderName()),
                    "§7Time remaining: §e" + auctions.secondsLeft() + "s",
                    "", "§aChoose a bid amount below."
            ));
            display.setItemMeta(meta);
            gui.setItem(13, display);
            gui.setItem(10, button(Material.EMERALD, "§aBid minimum", List.of("§7Amount: §e" + plugin.economy().format(auctions.nextMinimum())), "minimum"));
            gui.setItem(11, button(Material.GOLD_INGOT, "§6Bid +$500", List.of("§7Amount: §e" + plugin.economy().format(auctions.currentBid() + 500)), "plus500"));
            gui.setItem(12, button(Material.DIAMOND, "§bBid +$1,000", List.of("§7Amount: §e" + plugin.economy().format(auctions.currentBid() + 1000)), "plus1000"));
            gui.setItem(14, button(Material.EMERALD_BLOCK, "§aBid +$5,000", List.of("§7Amount: §e" + plugin.economy().format(auctions.currentBid() + 5000)), "plus5000"));
            gui.setItem(15, button(Material.PAPER, "§eCustom bid", List.of("§7Enter amount in chat"), "custom"));
            gui.setItem(22, button(Material.CLOCK, "§eRefresh", List.of("§7Refresh auction details"), "refresh"));
        }
        player.openInventory(gui);
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

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!TITLE.equals(event.getView().getTitle())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getView().getTopInventory()) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String action = clicked.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;
        switch (action) {
            case "minimum" -> place(player, auctions.nextMinimum());
            case "plus500" -> place(player, auctions.currentBid() + 500);
            case "plus1000" -> place(player, auctions.currentBid() + 1000);
            case "plus5000" -> place(player, auctions.currentBid() + 5000);
            case "custom" -> {
                awaitingCustom.add(player.getUniqueId());
                player.closeInventory();
                player.sendMessage("§6ClasherBid §7Enter your bid amount in chat, or type §ccancel§7.");
            }
            case "refresh" -> open(player);
        }
    }

    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (TITLE.equals(event.getView().getTitle())) event.setCancelled(true);
    }

    @EventHandler public void onChat(AsyncPlayerChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!awaitingCustom.remove(id)) return;
        event.setCancelled(true);
        String input = event.getMessage().trim();
        if (input.equalsIgnoreCase("cancel")) {
            Bukkit.getScheduler().runTask(plugin, () -> event.getPlayer().sendMessage("§7Custom bid cancelled."));
            return;
        }
        double amount;
        try { amount = Double.parseDouble(input.replace(",", "")); }
        catch (NumberFormatException ex) {
            Bukkit.getScheduler().runTask(plugin, () -> event.getPlayer().sendMessage("§cInvalid amount. Reopen /bid and try again."));
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> place(event.getPlayer(), amount));
    }

    private void place(Player player, double amount) {
        player.sendMessage(auctions.placeBid(player, amount));
        open(player);
    }

    @EventHandler public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        auctions.claim(event.getPlayer());
    }
}