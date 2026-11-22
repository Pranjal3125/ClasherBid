package network.clasherbid.command;

import net.milkbowl.vault.economy.Economy;
import network.clasherbid.auction.AuctionManager;
import network.clasherbid.config.MessageService;
import network.clasherbid.gui.AuctionGui;
import network.clasherbid.util.FormatUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * Player-facing /bid (and /auction) command handler with subcommands and direct-bid shortcut.
 */
public final class BidCommand implements CommandExecutor, TabCompleter {
    private final Economy economy;
    private final MessageService messages;
    private final AuctionManager auctions;
    private final AuctionGui gui;

    public BidCommand(Economy economy, MessageService messages, AuctionManager auctions, AuctionGui gui) {
        this.economy = economy;
        this.messages = messages;
        this.auctions = auctions;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("clasherbid.bid")) {
            messages.send(sender, "player.no-permission");
            return true;
        }
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                messages.send(sender, "player.players-only");
                return true;
            }
            gui.open(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "claim" -> {
                if (!(sender instanceof Player player)) {
                    messages.send(sender, "player.players-only");
                    return true;
                }
                auctions.claim(player, true);
                return true;
            }
            case "history" -> {
                if (sender instanceof Player player) {
                    gui.openHistory(player);
                } else {
                    List<String> entries = auctions.auctionHistory();
                    messages.sendRaw(sender, "<gold><bold>ClasherBid History</bold></gold> <gray>(Recent " + entries.size() + ")</gray>");
                    if (entries.isEmpty()) {
                        messages.sendRaw(sender, "<gray>No recorded auctions yet.</gray>");
                    } else {
                        for (String entry : entries) {
                            messages.sendRaw(sender, "<dark_gray>•</dark_gray> <white>" + entry.replace("<", "\\<") + "</white>");
                        }
                    }
                }
                return true;
            }
            case "status" -> {
                sendStatus(sender);
                return true;
            }
            case "help" -> {
                sendHelp(sender, label);
                return true;
            }
            default -> {
                if (!(sender instanceof Player player)) {
                    messages.send(sender, "player.players-only");
                    return true;
                }
                OptionalDouble parsed = FormatUtil.parseAmount(args[0]);
                if (parsed.isEmpty()) {
                    sendHelp(sender, label);
                    return true;
                }
                gui.openConfirm(player, parsed.getAsDouble());
                return true;
            }
        }
    }

    private void sendStatus(CommandSender sender) {
        if (!auctions.isActive()) {
            messages.send(sender, "player.no-active-auction");
            return;
        }
        String priceLabel = auctions.highestBidder() == null ? "Opening" : "Current Bid";
        String top = auctions.highestBidderName().isBlank() ? "None" : auctions.highestBidderName();
        messages.sendRaw(sender, "<gold><bold>CLASHERBID</bold></gold> <dark_gray>»</dark_gray> "
                + "<white>" + escape(auctions.auctionItemName()) + "</white>"
                + " <dark_gray>|</dark_gray> <gray>" + priceLabel + ":</gray> <green>" + escape(economy.format(auctions.currentBid())) + "</green>"
                + " <dark_gray>|</dark_gray> <gray>Top:</gray> <yellow>" + escape(top) + "</yellow>"
                + " <dark_gray>|</dark_gray> <gray>Time:</gray> <yellow>" + FormatUtil.formatDuration(auctions.secondsLeft()) + "</yellow>");
    }

    private void sendHelp(CommandSender sender, String label) {
        messages.sendRaw(sender, "<gold><bold>ClasherBid Commands</bold></gold>");
        messages.sendRaw(sender, "<yellow>/" + label + "</yellow> <dark_gray>-</dark_gray> <gray>Open the live auction GUI</gray>");
        messages.sendRaw(sender, "<yellow>/" + label + " <amount></yellow> <dark_gray>-</dark_gray> <gray>Bid a specific amount (supports 1.5k, 2m)</gray>");
        messages.sendRaw(sender, "<yellow>/" + label + " claim</yellow> <dark_gray>-</dark_gray> <gray>Claim pending won or returned items</gray>");
        messages.sendRaw(sender, "<yellow>/" + label + " history</yellow> <dark_gray>-</dark_gray> <gray>View recent completed auctions</gray>");
        messages.sendRaw(sender, "<yellow>/" + label + " status</yellow> <dark_gray>-</dark_gray> <gray>Show current auction summary</gray>");
    }

    private static String escape(String raw) {
        return raw == null ? "" : raw.replace("<", "\\<");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("clasherbid.bid")) return List.of();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>(List.of("claim", "history", "status", "help"));
            if (auctions.isActive() && auctions.settlementReview().isEmpty()) {
                long minRounded = (long) Math.ceil(auctions.nextMinimum());
                suggestions.add(String.valueOf(minRounded));
            }
            return suggestions.stream()
                    .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
