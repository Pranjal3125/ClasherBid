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
        if (args.length == 2 && args[0].equalsIgnoreCase("bid-review")) {
            if (args[1].equalsIgnoreCase("paid")) {
                if (auctions.resolveBidReview(true))
                    sender.sendMessage("§aInterrupted bid marked paid and accepted. Previous bidder refund is queued for guarded recovery.");
                else sender.sendMessage("§cNo resolvable pending bid, or it was already accepted. Inspect /clasherbid status.");
            } else if (args[1].equalsIgnoreCase("unpaid")) {
                if (auctions.resolveBidReview(false))
                    sender.sendMessage("§eInterrupted bid marked unpaid; pending bid discarded.");
                else sender.sendMessage("§cNo resolvable pending bid, or payment may already have been accepted.");
            } else sender.sendMessage("§cUsage: /clasherbid bid-review <paid|unpaid>");
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("claim-review")) {
            org.bukkit.OfflinePlayer target = plugin.getServer().getOfflinePlayer(args[1]);
            if (!target.isOnline() && !target.hasPlayedBefore()) {
                sender.sendMessage("§cUnknown player. Use a player who has joined this server.");
                return true;
            }
            if (args[2].equalsIgnoreCase("delivered")) {
                if (auctions.resolveClaimReview(target.getUniqueId(), true))
                    sender.sendMessage("§aClaim marked delivered; pending item record cleared after inventory verification.");
                else sender.sendMessage("§cNo claim review marker exists for that player.");
            } else if (args[2].equalsIgnoreCase("undelivered")) {
                if (auctions.resolveClaimReview(target.getUniqueId(), false))
                    sender.sendMessage("§eClaim marked undelivered; claim retry is unlocked after inventory verification.");
                else sender.sendMessage("§cNo claim review marker exists for that player.");
            } else sender.sendMessage("§cUsage: /clasherbid claim-review <player> <delivered|undelivered>");
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("refund-review")) {
            org.bukkit.OfflinePlayer target = plugin.getServer().getOfflinePlayer(args[1]);
            if (!target.isOnline() && !target.hasPlayedBefore()) {
                sender.sendMessage("§cUnknown player. Use a player who has joined this server.");
                return true;
            }
            if (args[2].equalsIgnoreCase("paid")) {
                if (auctions.resolveRefundReview(target.getUniqueId(), true))
                    sender.sendMessage("§aMarked refund paid and cleared pending debt. Confirmed by your balance review.");
                else sender.sendMessage("§cNo refund review marker exists for that player.");
            } else if (args[2].equalsIgnoreCase("unpaid")) {
                if (auctions.resolveRefundReview(target.getUniqueId(), false))
                    sender.sendMessage("§eMarked refund unpaid. Retry is now authorized after balance verification.");
                else sender.sendMessage("§cNo refund review marker exists for that player.");
            } else sender.sendMessage("§cUsage: /clasherbid refund-review <player> <paid|unpaid>");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("recover")) {
            org.bukkit.OfflinePlayer target = plugin.getServer().getOfflinePlayer(args[1]);
            if (!target.isOnline() && !target.hasPlayedBefore()) {
                sender.sendMessage("§cUnknown player. Use a player who has joined this server.");
                return true;
            }
            double amount = auctions.pendingRefund(target.getUniqueId());
            String review = auctions.refundReview(target.getUniqueId());
            if (amount <= 0) sender.sendMessage("§cNo pending refund for that player.");
            else if (!review.isEmpty()) sender.sendMessage("§cRefund is frozen for review (§e" + review + "§c). Check balances, then use /clasherbid refund-review <player> <paid|unpaid>.");
            else if (auctions.recoverRefund(target.getUniqueId())) sender.sendMessage("§aRecovered " + plugin.economy().format(amount) + " for " + target.getName() + ".");
            else sender.sendMessage("§cRefund recovery failed or is now frozen for manual review. Do not retry blindly.");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("start-review")) {
            if (args[1].equalsIgnoreCase("item-removed")) {
                if (auctions.resolveStartReview(true)) sender.sendMessage("§aStart review resolved: verified item removed; auction active.");
                else sender.sendMessage("§cNo start review pending, or save failed.");
            } else if (args[1].equalsIgnoreCase("item-still-held")) {
                if (auctions.resolveStartReview(false)) sender.sendMessage("§eStart review resolved: verified item still held; auction cleared.");
                else sender.sendMessage("§cNo start review pending, or save failed.");
            } else sender.sendMessage("§cUsage: /clasherbid start-review <item-removed|item-still-held>");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("settlement")) {
            if (args[1].equalsIgnoreCase("paid")) {
                if (auctions.resolveSettlementPaid()) sender.sendMessage("§aConfirmed paid: item delivery finalized. No second payout was made.");
                else sender.sendMessage("§cNo frozen settlement could be resolved.");
            } else if (args[1].equalsIgnoreCase("unpaid")) {
                if (auctions.resolveSettlementUnpaid()) sender.sendMessage("§eConfirmed unpaid: item queued for seller; bidder refund liability recorded for guarded recovery.");
                else sender.sendMessage("§cNo frozen settlement could be resolved.");
            } else sender.sendMessage("§cUsage: /clasherbid settlement <paid|unpaid>");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("settlement")) {
            if (auctions.settlementReview().isEmpty()) sender.sendMessage("§aNo settlement is frozen for review.");
            else sender.sendMessage("§cSettlement review required: §e" + auctions.settlementReview()
                    + " §7| Auction: §f" + (auctions.auctionItem() == null ? "unknown" : auctions.auctionItem().getType())
                    + " §7| Winner: §e" + auctions.highestBidderName()
                    + " §7| Amount: §a" + plugin.economy().format(auctions.currentBid())
                    + " §7| Pending bid: §f" + (auctions.bidReviewInfo().isEmpty() ? "None" : auctions.bidReviewInfo())
                    + " §7— verify balances and item delivery before any manual action.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("cancel")) {
            if (!auctions.isActive()) sender.sendMessage("§cNo active auction.");
            else if (!auctions.settlementReview().isEmpty()) sender.sendMessage("§cSettlement is frozen for review. Use /clasherbid settlement first.");
            else if (auctions.cancel()) sender.sendMessage("§eCancellation frozen for reconciliation. Verify balances/items, then use /clasherbid settlement <paid|unpaid>.");
            else sender.sendMessage("§cCancellation could not be persisted; no cancellation actions were performed.");
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
                    + auctions.highestBidderName() + " §7| Time: §e" + auctions.secondsLeft() + "s"
                    + (auctions.settlementReview().isEmpty() ? "" : " §7| Review: §c" + auctions.settlementReview()));
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
                if (auctions.completeStart()) sender.sendMessage("§aAuction started.");
                else sender.sendMessage("§eItem removed and auction start saved, but start is frozen for admin review. Check /clasherbid settlement.");
            } else sender.sendMessage("§cCould not start auction. Is another auction active or are arguments invalid?");
            return true;
        }
        sender.sendMessage("§e/clasherbid start <minutes> <starting-price>");
        sender.sendMessage("§e/clasherbid cancel");
        sender.sendMessage("§e/clasherbid status");
        sender.sendMessage("§e/clasherbid history");
        sender.sendMessage("§e/clasherbid recover <player>");
        sender.sendMessage("§e/clasherbid claim-review <player> <delivered|undelivered>");
        sender.sendMessage("§e/clasherbid refund-review <player> <paid|unpaid>");
        sender.sendMessage("§e/clasherbid bid-review <paid|unpaid>");
        sender.sendMessage("§e/clasherbid start-review <item-removed|item-still-held>");
        sender.sendMessage("§e/clasherbid settlement");
        sender.sendMessage("§e/clasherbid settlement <paid|unpaid>");
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("clasherbid.admin")) return List.of();
        if (args.length == 1) return List.of("start", "cancel", "status", "history", "recover", "claim-review", "refund-review", "bid-review", "settlement", "start-review").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        if (args.length == 2 && args[0].equalsIgnoreCase("start")) return List.of("1", "5", "10", "15", "30", "60", "120");
        if (args.length == 2 && args[0].equalsIgnoreCase("settlement")) return List.of("paid", "unpaid");
        if (args.length == 2 && args[0].equalsIgnoreCase("start-review")) return List.of("item-removed", "item-still-held");
        if (args.length == 2 && args[0].equalsIgnoreCase("bid-review")) return List.of("paid", "unpaid");
        if (args.length == 2 && (args[0].equalsIgnoreCase("recover") || args[0].equalsIgnoreCase("refund-review") || args[0].equalsIgnoreCase("claim-review"))) return plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("refund-review")) return List.of("paid", "unpaid");
        if (args.length == 3 && args[0].equalsIgnoreCase("claim-review")) return List.of("delivered", "undelivered");
        if (args.length == 3 && args[0].equalsIgnoreCase("start")) return List.of("100", "500", "1000", "5000");
        return List.of();
    }
}