package network.clasherbid.command;

import net.milkbowl.vault.economy.Economy;
import network.clasherbid.ClasherBidPlugin;
import network.clasherbid.auction.AuctionManager;
import network.clasherbid.config.MessageService;
import network.clasherbid.util.FormatUtil;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Administrator command handler (/clasherbid) for starting, inspecting, reloading,
 * and manually reconciling crash-safe auction transactions.
 */
public final class AdminCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of(
            "start", "cancel", "status", "history", "reload",
            "recover", "claim-review", "refund-review", "bid-review",
            "settlement", "start-review", "retry", "help"
    );

    private final ClasherBidPlugin plugin;
    private final Economy economy;
    private final MessageService messages;
    private final AuctionManager auctions;

    public AdminCommand(ClasherBidPlugin plugin, Economy economy, MessageService messages, AuctionManager auctions) {
        this.plugin = plugin;
        this.economy = economy;
        this.messages = messages;
        this.auctions = auctions;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("clasherbid.admin")) {
            messages.send(sender, "player.no-permission");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                plugin.reloadConfiguration();
                messages.sendRaw(sender, "<green>ClasherBid configuration and messages reloaded.</green> <gray>(Active auction and ledger state preserved.)</gray>");
                return true;
            }
            case "start" -> {
                handleStart(sender, args);
                return true;
            }
            case "cancel" -> {
                handleCancel(sender);
                return true;
            }
            case "status" -> {
                handleStatus(sender);
                return true;
            }
            case "history" -> {
                handleHistory(sender);
                return true;
            }
            case "retry" -> {
                if (auctions.retrySettlement()) {
                    messages.sendRaw(sender, "<green>Payout retry scheduled.</green> <gray>Verify the configured payout account before using this command.</gray>");
                } else {
                    messages.sendRaw(sender, "<red>No payout-account error is waiting for retry, or state could not be saved.</red>");
                }
                return true;
            }
            case "settlement" -> {
                handleSettlement(sender, args);
                return true;
            }
            case "bid-review" -> {
                handleBidReview(sender, args);
                return true;
            }
            case "start-review" -> {
                handleStartReview(sender, args);
                return true;
            }
            case "recover" -> {
                handleRecover(sender, args);
                return true;
            }
            case "refund-review" -> {
                handleRefundReview(sender, args);
                return true;
            }
            case "claim-review" -> {
                handleClaimReview(sender, args);
                return true;
            }
            default -> {
                sendHelp(sender);
                return true;
            }
        }
    }

    private void handleStart(CommandSender sender, String[] args) {
        if (args.length != 3) {
            messages.sendRaw(sender, "<red>Usage:</red> <yellow>/clasherbid start <minutes> <starting-price></yellow>");
            return;
        }
        if (auctions.isRecoveryBlocked()) {
            messages.sendRaw(sender, "<red>Saved auction data is corrupt. Restore active-auction.yml manually before starting another auction.</red>");
            return;
        }
        if (!(sender instanceof Player player)) {
            messages.sendRaw(sender, "<red>Only an in-game admin can start an auction.</red>");
            return;
        }
        long minutes;
        try {
            minutes = Long.parseLong(args[1]);
        } catch (NumberFormatException ex) {
            messages.sendRaw(sender, "<red>Invalid duration. Must be a whole number between 1 and 120 minutes.</red>");
            return;
        }
        var parsedPrice = FormatUtil.parseAmount(args[2]);
        if (minutes < 1 || minutes > 120 || parsedPrice.isEmpty()) {
            messages.sendRaw(sender, "<red>Duration must be 1–120 minutes and starting price must be positive (e.g. 500 or 2.5k).</red>");
            return;
        }
        double price = parsedPrice.getAsDouble();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            messages.sendRaw(sender, "<red>Hold the auction item in your main hand.</red>");
            return;
        }
        if (auctions.start(player, held.clone(), price, minutes * 60L)) {
            player.getInventory().setItemInMainHand(null);
            if (auctions.completeStart()) {
                messages.sendRaw(sender, "<green>Auction started for</green> <white>" + escape(auctions.auctionItemName())
                        + "</white> <gray>(</gray><yellow>" + FormatUtil.formatDuration(minutes * 60L)
                        + "</yellow><gray>, opening</gray> <green>" + escape(economy.format(price)) + "</green><gray>).</gray>");
            } else {
                messages.sendRaw(sender, "<yellow>Item removed and auction start saved, but start is frozen for admin review. Check /clasherbid settlement.</yellow>");
            }
        } else {
            messages.sendRaw(sender, "<red>Could not start auction. Is another auction active or are arguments invalid?</red>");
        }
    }

    private void handleCancel(CommandSender sender) {
        if (!auctions.isActive()) {
            messages.sendRaw(sender, "<red>No active auction.</red>");
        } else if (!auctions.settlementReview().isEmpty()) {
            messages.sendRaw(sender, "<red>Settlement is frozen for review. Use /clasherbid settlement first.</red>");
        } else if (auctions.cancel()) {
            messages.sendRaw(sender, "<yellow>Cancellation frozen for reconciliation. Verify balances/items, then use /clasherbid settlement <paid|unpaid>.</yellow>");
        } else {
            messages.sendRaw(sender, "<red>Cancellation could not be persisted; no cancellation actions were performed.</red>");
        }
    }

    private void handleStatus(CommandSender sender) {
        messages.sendRaw(sender, "<gold><bold>ClasherBid Status</bold></gold>");
        if (!auctions.isActive()) {
            if (auctions.isRecoveryBlocked()) {
                messages.sendRaw(sender, "<red>Saved auction data is corrupt. New auctions are disabled; inspect active-auction.yml and restore manually.</red>");
            } else {
                messages.sendRaw(sender, "<gray>Active Auction:</gray> <white>None</white>");
            }
        } else {
            String priceLabel = auctions.highestBidder() == null ? "Opening Price" : "Current Bid";
            String top = auctions.highestBidderName().isBlank() ? "None" : auctions.highestBidderName();
            messages.sendRaw(sender, "<gray>Item:</gray> <white>" + escape(auctions.auctionItemName()) + "</white>"
                    + " <dark_gray>|</dark_gray> <gray>Seller:</gray> <white>" + escape(auctions.sellerName()) + "</white>");
            messages.sendRaw(sender, "<gray>" + priceLabel + ":</gray> <green>" + escape(economy.format(auctions.currentBid())) + "</green>"
                    + " <dark_gray>|</dark_gray> <gray>Top:</gray> <yellow>" + escape(top) + "</yellow>"
                    + " <dark_gray>|</dark_gray> <gray>Escrow:</gray> <green>" + escape(economy.format(auctions.highestBidder() == null ? 0.0 : auctions.currentBid())) + "</green>");
            messages.sendRaw(sender, "<gray>Time Left:</gray> <yellow>" + FormatUtil.formatDuration(auctions.secondsLeft()) + "</yellow>"
                    + (auctions.settlementReview().isEmpty() ? "" : " <dark_gray>|</dark_gray> <red>Review: " + escape(auctions.settlementReview()) + "</red>")
                    + (auctions.isRecoveryBlocked() ? " <dark_gray>|</dark_gray> <red><bold>RECOVERY BLOCKED</bold></red>" : ""));
        }

        int pendingRefunds = auctions.pendingRefundPlayers().size();
        int refundReviews = auctions.refundReviewPlayers().size();
        int claimReviews = auctions.claimReviewPlayers().size();
        if (pendingRefunds > 0 || refundReviews > 0 || claimReviews > 0) {
            messages.sendRaw(sender, "<gray>Ledger Alerts:</gray> "
                    + "<yellow>Pending Refunds: " + pendingRefunds + "</yellow>"
                    + " <dark_gray>|</dark_gray> <red>Refund Reviews: " + refundReviews + "</red>"
                    + " <dark_gray>|</dark_gray> <red>Claim Reviews: " + claimReviews + "</red>");
        }
    }

    private void handleHistory(CommandSender sender) {
        List<String> entries = auctions.auctionHistory();
        messages.sendRaw(sender, "<gold><bold>ClasherBid History</bold></gold> <gray>(Recent " + entries.size() + ")</gray>");
        if (entries.isEmpty()) {
            messages.sendRaw(sender, "<gray>No recorded auctions yet.</gray>");
        } else {
            entries.stream().limit(25).forEach(entry ->
                    messages.sendRaw(sender, "<dark_gray>•</dark_gray> <white>" + escape(entry) + "</white>"));
        }
    }

    private void handleSettlement(CommandSender sender, String[] args) {
        if (args.length == 1) {
            if (auctions.isRecoveryBlocked() && auctions.settlementReview().isEmpty()) {
                messages.sendRaw(sender, "<red>Auction recovery is blocked by invalid saved data. Inspect active-auction.yml; command-based settlement is disabled.</red>");
            } else if (auctions.settlementReview().isEmpty()) {
                messages.sendRaw(sender, "<green>No settlement is frozen for review.</green>");
            } else {
                messages.sendRaw(sender, "<red>Settlement review required:</red> <yellow>" + escape(auctions.settlementReview()) + "</yellow>"
                        + " <dark_gray>|</dark_gray> <gray>Item:</gray> <white>" + escape(auctions.auctionItemName()) + "</white>"
                        + " <dark_gray>|</dark_gray> <gray>Winner:</gray> <yellow>" + escape(auctions.highestBidderName().isBlank() ? "None" : auctions.highestBidderName()) + "</yellow>"
                        + " <dark_gray>|</dark_gray> <gray>Amount:</gray> <green>" + escape(economy.format(auctions.currentBid())) + "</green>"
                        + " <dark_gray>|</dark_gray> <gray>Pending Bid:</gray> <white>" + escape(auctions.bidReviewInfo().isEmpty() ? "None" : auctions.bidReviewInfo()) + "</white>"
                        + (auctions.isRecoveryBlocked()
                            ? " <red>| File-level recovery required; commands are disabled.</red>"
                            : " <gray>— verify balances and item delivery before any manual action.</gray>"));
            }
            return;
        }
        if (args.length == 2) {
            if (args[1].equalsIgnoreCase("paid")) {
                if (auctions.resolveSettlementPaid()) {
                    messages.sendRaw(sender, "<green>Confirmed paid: item delivery finalized. No second payout was made.</green>");
                } else {
                    messages.sendRaw(sender, "<red>No frozen settlement could be resolved.</red>");
                }
                return;
            }
            if (args[1].equalsIgnoreCase("unpaid")) {
                if (auctions.resolveSettlementUnpaid()) {
                    messages.sendRaw(sender, "<yellow>Confirmed unpaid: item queued for seller; bidder refund liability recorded for guarded recovery.</yellow>");
                } else {
                    messages.sendRaw(sender, "<red>No frozen settlement could be resolved.</red>");
                }
                return;
            }
        }
        messages.sendRaw(sender, "<red>Usage:</red> <yellow>/clasherbid settlement [paid|unpaid]</yellow>");
    }

    private void handleBidReview(CommandSender sender, String[] args) {
        if (args.length != 2) {
            messages.sendRaw(sender, "<red>Usage:</red> <yellow>/clasherbid bid-review <paid|unpaid></yellow>");
            return;
        }
        if (args[1].equalsIgnoreCase("paid")) {
            if (auctions.resolveBidReview(true)) {
                messages.sendRaw(sender, "<green>Interrupted bid marked paid and accepted. Previous bidder refund is queued for guarded recovery.</green>");
            } else {
                messages.sendRaw(sender, "<red>No resolvable pending bid, or it was already accepted. Inspect /clasherbid status.</red>");
            }
        } else if (args[1].equalsIgnoreCase("unpaid")) {
            if (auctions.resolveBidReview(false)) {
                messages.sendRaw(sender, "<yellow>Interrupted bid marked unpaid; pending bid discarded.</yellow>");
            } else {
                messages.sendRaw(sender, "<red>No resolvable pending bid, or payment may already have been accepted.</red>");
            }
        } else {
            messages.sendRaw(sender, "<red>Usage:</red> <yellow>/clasherbid bid-review <paid|unpaid></yellow>");
        }
    }

    private void handleStartReview(CommandSender sender, String[] args) {
        if (args.length != 2) {
            messages.sendRaw(sender, "<red>Usage:</red> <yellow>/clasherbid start-review <item-removed|item-still-held></yellow>");
            return;
        }
        if (args[1].equalsIgnoreCase("item-removed")) {
            if (auctions.resolveStartReview(true)) {
                messages.sendRaw(sender, "<green>Start review resolved: verified item removed; auction active.</green>");
            } else {
                messages.sendRaw(sender, "<red>No start review pending, or save failed.</red>");
            }
        } else if (args[1].equalsIgnoreCase("item-still-held")) {
            if (auctions.resolveStartReview(false)) {
                messages.sendRaw(sender, "<yellow>Start review resolved: verified item still held; auction cleared.</yellow>");
            } else {
                messages.sendRaw(sender, "<red>No start review pending, or save failed.</red>");
            }
        } else {
            messages.sendRaw(sender, "<red>Usage:</red> <yellow>/clasherbid start-review <item-removed|item-still-held></yellow>");
        }
    }

    private void handleRecover(CommandSender sender, String[] args) {
        if (args.length != 2) {
            messages.sendRaw(sender, "<red>Usage:</red> <yellow>/clasherbid recover <player></yellow>");
            return;
        }
        OfflinePlayer target = resolveKnownPlayer(sender, args[1]);
        if (target == null) return;

        double amount = auctions.pendingRefund(target.getUniqueId());
        String review = auctions.refundReview(target.getUniqueId());
        if (amount <= 0.0) {
            messages.sendRaw(sender, "<red>No pending refund for that player.</red>");
        } else if (!review.isEmpty()) {
            messages.sendRaw(sender, "<red>Refund is frozen for review (</red><yellow>" + escape(review)
                    + "</yellow><red>). Check balances, then use /clasherbid refund-review <player> <paid|unpaid>.</red>");
        } else if (auctions.recoverRefund(target.getUniqueId())) {
            String name = target.getName() != null ? target.getName() : target.getUniqueId().toString();
            messages.sendRaw(sender, "<green>Recovered</green> <yellow>" + escape(economy.format(amount))
                    + "</yellow> <green>for</green> <white>" + escape(name) + "</white><green>.</green>");
        } else {
            messages.sendRaw(sender, "<red>Refund recovery failed or is now frozen for manual review. Do not retry blindly.</red>");
        }
    }

    private void handleRefundReview(CommandSender sender, String[] args) {
        if (args.length != 3) {
            messages.sendRaw(sender, "<red>Usage:</red> <yellow>/clasherbid refund-review <player> <paid|unpaid></yellow>");
            return;
        }
        OfflinePlayer target = resolveKnownPlayer(sender, args[1]);
        if (target == null) return;

        if (args[2].equalsIgnoreCase("paid")) {
            if (auctions.resolveRefundReview(target.getUniqueId(), true)) {
                messages.sendRaw(sender, "<green>Marked refund paid and cleared pending debt. Confirmed by your balance review.</green>");
            } else {
                messages.sendRaw(sender, "<red>No refund review marker exists for that player.</red>");
            }
        } else if (args[2].equalsIgnoreCase("unpaid")) {
            if (auctions.resolveRefundReview(target.getUniqueId(), false)) {
                messages.sendRaw(sender, "<yellow>Marked refund unpaid. Retry is now authorized after balance verification.</yellow>");
            } else {
                messages.sendRaw(sender, "<red>No refund review marker exists for that player.</red>");
            }
        } else {
            messages.sendRaw(sender, "<red>Usage:</red> <yellow>/clasherbid refund-review <player> <paid|unpaid></yellow>");
        }
    }

    private void handleClaimReview(CommandSender sender, String[] args) {
        if (args.length != 3) {
            messages.sendRaw(sender, "<red>Usage:</red> <yellow>/clasherbid claim-review <player> <delivered|undelivered></yellow>");
            return;
        }
        OfflinePlayer target = resolveKnownPlayer(sender, args[1]);
        if (target == null) return;

        if (args[2].equalsIgnoreCase("delivered")) {
            if (auctions.resolveClaimReview(target.getUniqueId(), true)) {
                messages.sendRaw(sender, "<green>Claim marked delivered; pending item record cleared after inventory verification.</green>");
            } else {
                messages.sendRaw(sender, "<red>No claim review marker exists for that player.</red>");
            }
        } else if (args[2].equalsIgnoreCase("undelivered")) {
            if (auctions.resolveClaimReview(target.getUniqueId(), false)) {
                messages.sendRaw(sender, "<yellow>Claim marked undelivered; claim retry is unlocked after inventory verification.</yellow>");
            } else {
                messages.sendRaw(sender, "<red>No claim review marker exists for that player.</red>");
            }
        } else {
            messages.sendRaw(sender, "<red>Usage:</red> <yellow>/clasherbid claim-review <player> <delivered|undelivered></yellow>");
        }
    }

    private OfflinePlayer resolveKnownPlayer(CommandSender sender, String name) {
        OfflinePlayer target = plugin.getServer().getOfflinePlayer(name);
        if (!target.isOnline() && !target.hasPlayedBefore()) {
            messages.sendRaw(sender, "<red>Unknown player. Use a player who has joined this server.</red>");
            return null;
        }
        return target;
    }

    private void sendHelp(CommandSender sender) {
        messages.sendRaw(sender, "<gold><bold>ClasherBid Admin Commands</bold></gold>");
        messages.sendRaw(sender, "<yellow>/clasherbid start <minutes> <price></yellow> <dark_gray>-</dark_gray> <gray>Start auction with held item</gray>");
        messages.sendRaw(sender, "<yellow>/clasherbid cancel</yellow> <dark_gray>-</dark_gray> <gray>Freeze active auction for reconciliation</gray>");
        messages.sendRaw(sender, "<yellow>/clasherbid status</yellow> <dark_gray>-</dark_gray> <gray>Inspect active auction, escrow & ledger alerts</gray>");
        messages.sendRaw(sender, "<yellow>/clasherbid history</yellow> <dark_gray>-</dark_gray> <gray>Show recent 25 recorded auctions</gray>");
        messages.sendRaw(sender, "<yellow>/clasherbid reload</yellow> <dark_gray>-</dark_gray> <gray>Reload config.yml and messages.yml</gray>");
        messages.sendRaw(sender, "<yellow>/clasherbid recover <player></yellow> <dark_gray>-</dark_gray> <gray>Retry an eligible recorded refund</gray>");
        messages.sendRaw(sender, "<yellow>/clasherbid retry</yellow> <dark_gray>-</dark_gray> <gray>Retry settlement after fixing payout-player</gray>");
        messages.sendRaw(sender, "<yellow>/clasherbid settlement [paid|unpaid]</yellow> <dark_gray>-</dark_gray> <gray>Inspect or resolve frozen settlement</gray>");
        messages.sendRaw(sender, "<yellow>/clasherbid bid-review <paid|unpaid></yellow> <dark_gray>-</dark_gray> <gray>Resolve interrupted bid</gray>");
        messages.sendRaw(sender, "<yellow>/clasherbid refund-review <player> <paid|unpaid></yellow> <dark_gray>-</dark_gray> <gray>Resolve uncertain refund</gray>");
        messages.sendRaw(sender, "<yellow>/clasherbid claim-review <player> <delivered|undelivered></yellow> <dark_gray>-</dark_gray> <gray>Resolve interrupted claim</gray>");
        messages.sendRaw(sender, "<yellow>/clasherbid start-review <item-removed|item-still-held></yellow> <dark_gray>-</dark_gray> <gray>Resolve interrupted start</gray>");
    }

    private static String escape(String raw) {
        return raw == null ? "" : raw.replace("<", "\\<");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("clasherbid.admin")) return List.of();
        if (args.length == 1) {
            return filterPrefix(SUBCOMMANDS, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "start" -> filterPrefix(List.of("1", "5", "10", "15", "30", "60", "120"), args[1]);
                case "settlement", "bid-review" -> filterPrefix(List.of("paid", "unpaid"), args[1]);
                case "start-review" -> filterPrefix(List.of("item-removed", "item-still-held"), args[1]);
                case "recover" -> filterPrefix(playerSuggestions(auctions.pendingRefundPlayers()), args[1]);
                case "refund-review" -> filterPrefix(playerSuggestions(auctions.refundReviewPlayers()), args[1]);
                case "claim-review" -> filterPrefix(playerSuggestions(auctions.claimReviewPlayers()), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3) {
            return switch (sub) {
                case "start" -> filterPrefix(List.of("100", "500", "1000", "5000", "10000"), args[2]);
                case "refund-review" -> filterPrefix(List.of("paid", "unpaid"), args[2]);
                case "claim-review" -> filterPrefix(List.of("delivered", "undelivered"), args[2]);
                default -> List.of();
            };
        }
        return List.of();
    }

    private List<String> playerSuggestions(Set<UUID> ledgerUuids) {
        Set<String> names = new LinkedHashSet<>();
        for (UUID id : ledgerUuids) {
            String name = plugin.getServer().getOfflinePlayer(id).getName();
            if (name != null && !name.isBlank()) names.add(name);
        }
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            names.add(online.getName());
        }
        return List.copyOf(names);
    }

    private static List<String> filterPrefix(List<String> options, String rawPrefix) {
        String prefix = rawPrefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }
}
