package network.clasherbid;

import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class AdminCommand implements CommandExecutor, TabCompleter {
    private final ClasherBidPlugin plugin;
    private final AuctionManager auctions;
    AdminCommand(ClasherBidPlugin plugin, AuctionManager auctions) { this.plugin = plugin; this.auctions = auctions; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("clasherbid.admin")) { sender.sendMessage("§cNo permission."); return true; }
        if (args.length == 2 && args[0].equalsIgnoreCase("recover")) {
            org.bukkit.OfflinePlayer target = plugin.getServer().getOfflinePlayer(args[1]);
            double amount = auctions.pendingRefund(target.getUniqueId());
            if (amount <= 0) sender.sendMessage("§cNo pending refund for that player.");
            else if (auctions.recoverRefund(target.getUniqueId())) sender.sendMessage("§aRecovered " + plugin.economy().format(amount) + " for " + target.getName() + ".");
            else sender.sendMessage("§cRefund recovery failed. The pending amount remains saved.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("settlement")) {
            if (auctions.settlementReview().isEmpty()) sender.sendMessage("§aNo settlement is frozen for review.");
            else sender.sendMessage("§cSettlement review required: §e" + auctions.settlementReview()
                    + " §7| Auction: §f" + (auctions.auctionItem() == null ? "unknown" : auctions.auctionItem().getType())
                    + " §7| Winner: §e" + auctions.highestBidderName()
                    + " §7| Amount: §a" + plugin.economy().format(auctions.currentBid())
                    + " §7— verify balances and item delivery before any manual action.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("cancel")) {
            if (!auctions.isActive()) sender.sendMessage("§cNo active auction.");
            else if (!auctions.settlementReview().isEmpty()) sender.sendMessage("§cSettlement is frozen for review. Use /clasherbid settlement first.");
            else { auctions.cancel(); sender.sendMessage("§aAuction cancelled."); }
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("history")) {
            List<String> entries = auctions.auctionHistory();
            sender.sendMessage("§6§lClasherBid History §7(Recent 25)");
            if (entries.isEmpty()) sender.sendMessage("§7No recorded auctions yet.");
            else entries.stream().limit(10).forEach(entry -> sender.sendMessage("§7- §f" + entry));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            if (!auctions.isActive()) sender.sendMessage("§7No active auction.");
            else sender.sendMessage("§6Auction: §f" + auctions.auctionItem().getType() + " §7| Bid: §a"
                    + plugin.economy().format(auctions.currentBid()) + " §7| Top: §e"
                    + auctions.highestBidderName() + " §7| Time: §e" + auctions.secondsLeft() + "s");
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("start")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("§cOnly an in-game admin can start an auction."); return true; }
            long minutes; double price;
            try { minutes = Long.parseLong(args[1]); price = Double.parseDouble(args[2]); }
            catch (NumberFormatException ex) { sender.sendMessage("§cUsage: /clasherbid start <minutes> <starting-price>"); return true; }
            if (minutes < 1 || minutes > 120 || !Double.isFinite(price) || price <= 0) { sender.sendMessage("§cDuration must be 1-120 minutes and starting price must be positive."); return true; }
            ItemStack held = player.getInventory().getItemInMainHand();
            if (held.getType() == Material.AIR) { sender.sendMessage("§cHold the auction item in your main hand."); return true; }
            if (auctions.start(player, held.clone(), price, minutes * 60L)) {
                player.getInventory().setItemInMainHand(null);
                sender.sendMessage("§aAuction started.");
            } else sender.sendMessage("§cCould not start auction. Is another auction active or are arguments invalid?");
            return true;
        }
        sender.sendMessage("§e/clasherbid start <minutes> <starting-price>");
        sender.sendMessage("§e/clasherbid cancel");
        sender.sendMessage("§e/clasherbid status");
        sender.sendMessage("§e/clasherbid history");
        sender.sendMessage("§e/clasherbid recover <player>");
        sender.sendMessage("§e/clasherbid settlement");
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("clasherbid.admin")) return List.of();
        if (args.length == 1) return List.of("start", "cancel", "status", "history", "recover", "settlement").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        if (args.length == 2 && args[0].equalsIgnoreCase("start")) return List.of("1", "5", "10", "15", "30", "60", "120");
        if (args.length == 2 && args[0].equalsIgnoreCase("recover")) return plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("start")) return List.of("100", "500", "1000", "5000");
        return List.of();
    }
}