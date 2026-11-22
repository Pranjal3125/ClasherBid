package network.clasherbid.auction;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import network.clasherbid.config.MessageService;
import network.clasherbid.config.PluginConfig;
import network.clasherbid.storage.HistoryStore;
import network.clasherbid.storage.LedgerStore;
import network.clasherbid.storage.YamlAtomicStorage;
import network.clasherbid.util.FormatUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Crash-safe single-auction state machine.
 * All public mutations are invoked on the server main thread.
 * The highest bid is escrowed immediately and refunded when outbid or cancelled.
 *
 * Every economy or inventory mutation writes a durable intent marker BEFORE invoking
 * Vault or modifying player inventories so crashes never cause silent double-charges,
 * double-payouts, or duplicated items.
 */
public final class AuctionManager implements Listener {
    public record BidResult(boolean accepted, Component message) {}

    private final JavaPlugin plugin;
    private final Economy economy;
    private final PluginConfig config;
    private final MessageService messages;
    private final LedgerStore ledger;
    private final HistoryStore history;
    private final File stateFile;

    private final ArrayDeque<String> bidHistory = new ArrayDeque<>();

    private UUID auctionId;
    private ItemStack item;
    private UUID seller;
    private String sellerName = "";
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
    private String pendingBidderName = "";
    private double pendingBidAmount;
    private UUID pendingRefundPlayer;
    private double pendingRefundAmount;

    private long lastAlertedSecond = Long.MAX_VALUE;
    private BukkitTask timer;

    public AuctionManager(
            JavaPlugin plugin,
            Economy economy,
            PluginConfig config,
            MessageService messages,
            LedgerStore ledger,
            HistoryStore history
    ) {
        this.plugin = plugin;
        this.economy = economy;
        this.config = config;
        this.messages = messages;
        this.ledger = ledger;
        this.history = history;
        this.stateFile = new File(plugin.getDataFolder(), "active-auction.yml");
    }

    // -------------------------------------------------------------------------
    // Read-only state accessors
    // -------------------------------------------------------------------------

    public boolean isActive() { return active; }
    public boolean isRecoveryBlocked() { return recoveryBlocked; }
    public String settlementReview() { return settlementReview; }
    public ItemStack auctionItem() { return item == null ? null : item.clone(); }
    public String auctionItemName() { return FormatUtil.formatItemName(item); }
    public UUID seller() { return seller; }
    public String sellerName() {
        if (sellerName != null && !sellerName.isBlank()) return sellerName;
        if (seller == null) return "Unknown";
        String offlineName = Bukkit.getOfflinePlayer(seller).getName();
        return offlineName != null ? offlineName : seller.toString().substring(0, 8);
    }
    public double currentBid() { return currentBid; }
    public double escrow() { return escrow; }
    public UUID highestBidder() { return bidder; }
    public String highestBidderName() { return bidderName; }
    public long secondsLeft() { return active ? Math.max(0L, (endsAt - System.currentTimeMillis() + 999L) / 1000L) : 0L; }

    public double nextMinimum() {
        if (!active) return 0.0;
        double increment = config.minimumIncrement();
        if (bidder == null) return currentBid;
        double minimum = currentBid + increment;
        return Double.isFinite(minimum) ? minimum : Double.MAX_VALUE;
    }

    public List<String> recentBids() { return List.copyOf(bidHistory); }
    public List<String> auctionHistory() { return history.entries(); }
    public double pendingRefund(UUID playerId) { return ledger.pendingRefund(playerId); }
    public String refundReview(UUID playerId) { return ledger.refundReview(playerId); }
    public String claimReview(UUID playerId) { return ledger.claimReview(playerId); }
    public int pendingDeliveryCount(UUID playerId) { return ledger.pendingDeliveryCount(playerId); }
    public Set<UUID> pendingRefundPlayers() { return ledger.pendingRefundPlayers(); }
    public Set<UUID> refundReviewPlayers() { return ledger.refundReviewPlayers(); }
    public Set<UUID> claimReviewPlayers() { return ledger.claimReviewPlayers(); }

    public String bidReviewInfo() {
        return settlementReview.startsWith("BID_PENDING")
                ? settlementReview + " | " + pendingBidderName + " | " + economy.format(pendingBidAmount)
                : "";
    }

    private void recordBid(String name, double amount) {
        bidHistory.addFirst(name + " — " + economy.format(amount));
        while (bidHistory.size() > 8) bidHistory.removeLast();
    }

    // -------------------------------------------------------------------------
    // Auction start lifecycle
    // -------------------------------------------------------------------------

    public boolean start(Player admin, ItemStack stack, double openingBid, long durationSeconds) {
        if (active || recoveryBlocked || stack == null || stack.getType().isAir() || stack.getAmount() <= 0
                || durationSeconds < 30 || durationSeconds > 7200
                || !Double.isFinite(openingBid) || openingBid <= 0.0) {
            return false;
        }
        auctionId = UUID.randomUUID();
        item = stack.clone();
        seller = admin.getUniqueId();
        sellerName = admin.getName();
        bidder = null;
        bidderName = "";
        pendingBidder = null;
        pendingBidTransactionId = null;
        pendingBidderName = "";
        pendingBidAmount = 0.0;
        pendingRefundPlayer = null;
        pendingRefundAmount = 0.0;
        currentBid = openingBid;
        escrow = 0.0;
        lastBidAt = 0L;
        lastAlertedSecond = Long.MAX_VALUE;
        bidHistory.clear();
        endsAt = System.currentTimeMillis() + durationSeconds * 1000L;
        settlementReview = "START_PENDING";
        active = true;
        if (!persist()) {
            resetInMemory();
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
        messages.broadcast("broadcast.auction-started",
                Placeholder.unparsed("item", FormatUtil.formatItemName(item)),
                Placeholder.unparsed("price", economy.format(currentBid)),
                Placeholder.unparsed("time", FormatUtil.formatDuration(secondsLeft()))
        );
        messages.playSoundAll(Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.2f);
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
            messages.broadcastRaw("<gold><bold>CLASHERBID</bold></gold> <dark_gray>»</dark_gray> <yellow>Auction start reconciled; auction is now active.</yellow>");
            return true;
        }
        ItemStack oldItem = item;
        UUID oldSeller = seller, oldBidder = bidder, oldAuctionId = auctionId;
        String oldSellerName = sellerName, oldBidderName = bidderName;
        double oldCurrentBid = currentBid, oldEscrow = escrow;
        long oldEndsAt = endsAt;
        resetInMemory();
        if (!persist()) {
            active = true;
            auctionId = oldAuctionId;
            item = oldItem;
            seller = oldSeller;
            sellerName = oldSellerName;
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

    // -------------------------------------------------------------------------
    // Bidding & escrow
    // -------------------------------------------------------------------------

    public BidResult placeBid(Player player, double amount) {
        if (!active) return new BidResult(false, messages.render("player.no-active-auction"));
        if (System.currentTimeMillis() >= endsAt) return new BidResult(false, messages.render("player.auction-ended"));
        if (!settlementReview.isEmpty()) return new BidResult(false, messages.render("player.auction-frozen"));
        if (player.getUniqueId().equals(seller)) return new BidResult(false, messages.render("player.cannot-bid-own"));
        if (!config.allowSelfOutbid() && player.getUniqueId().equals(bidder)) {
            return new BidResult(false, messages.render("player.already-highest"));
        }

        long cooldownMs = config.bidCooldownSeconds() * 1000L;
        long elapsed = System.currentTimeMillis() - lastBidAt;
        if (cooldownMs > 0 && elapsed < cooldownMs) {
            long waitSec = Math.max(1L, (cooldownMs - elapsed + 999L) / 1000L);
            return new BidResult(false, messages.render("player.bid-cooldown",
                    Placeholder.unparsed("seconds", String.valueOf(waitSec))));
        }

        double minRequired = nextMinimum();
        if (!Double.isFinite(amount) || amount < minRequired) {
            return new BidResult(false, messages.render("player.minimum-bid",
                    Placeholder.unparsed("amount", economy.format(minRequired))));
        }

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
            pendingBidAmount = 0.0;
            pendingBidTransactionId = null;
            persist();
            return new BidResult(false, messages.parse("<red>Bid paused: auction state could not be saved.</red>"));
        }

        EconomyResponse withdrawal;
        try {
            withdrawal = economy.withdrawPlayer(player, amount);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "Vault threw during bid withdrawal; bid remains frozen for manual review.", ex);
            return new BidResult(false, messages.parse("<yellow>Bid payment result is uncertain. Auction frozen for admin review; do not retry.</yellow>"));
        }
        if (withdrawal == null) {
            plugin.getLogger().severe("Vault returned no bid withdrawal response; bid remains frozen for manual review.");
            return new BidResult(false, messages.parse("<yellow>Bid payment result is uncertain. Auction frozen for admin review; do not retry.</yellow>"));
        }
        if (!withdrawal.transactionSuccess()) {
            UUID failedTransactionId = pendingBidTransactionId;
            settlementReview = "";
            pendingBidder = null;
            pendingBidderName = "";
            pendingBidAmount = 0.0;
            pendingBidTransactionId = null;
            if (!persist()) {
                settlementReview = "BID_PENDING";
                pendingBidder = player.getUniqueId();
                pendingBidderName = player.getName();
                pendingBidAmount = amount;
                pendingBidTransactionId = failedTransactionId;
                messages.broadcastRaw("<red>ClasherBid: failed withdrawal could not be cleared durably; auction frozen for admin review.</red>");
                return new BidResult(false, messages.parse("<yellow>Bid was not accepted, but auction is frozen for admin review.</yellow>"));
            }
            String errMsg = withdrawal.errorMessage == null || withdrawal.errorMessage.isBlank()
                    ? "Insufficient funds"
                    : withdrawal.errorMessage;
            return new BidResult(false, messages.render("player.bid-failed", Placeholder.unparsed("error", errMsg)));
        }

        UUID previousBidder = bidder;
        double previousEscrow = escrow;
        bidder = pendingBidder;
        bidderName = pendingBidderName;
        currentBid = pendingBidAmount;
        escrow = pendingBidAmount;
        pendingBidder = null;
        pendingBidderName = "";
        pendingBidAmount = 0.0;
        settlementReview = "BID_PENDING_ACCEPTED";
        lastBidAt = System.currentTimeMillis();
        recordBid(bidderName, amount);

        boolean refundedPrevious = false;
        if (previousBidder != null && previousEscrow > 0.0) {
            // Keep the refund source in the auction journal until its idempotent ledger entry is durable.
            pendingRefundPlayer = previousBidder;
            pendingRefundAmount = previousEscrow;
            if (!persist()) {
                messages.broadcastRaw("<red>ClasherBid: accepted bid journal could not be saved; auction frozen for admin review.</red>");
                return new BidResult(false, messages.parse("<yellow>Bid payment accepted, but recovery state could not be saved. Auction frozen for admin review.</yellow>"));
            }
            if (!ledger.queueRefund(pendingRefundPlayer, pendingRefundAmount, "outbid-" + pendingBidTransactionId)) {
                messages.broadcastRaw("<red>ClasherBid: previous bidder refund could not be saved; auction frozen for admin review.</red>");
                return new BidResult(false, messages.parse("<yellow>Bid received, but refund accounting could not be saved. Auction frozen for admin review.</yellow>"));
            }
            if (ledger.pendingRefund(pendingRefundPlayer) > 0.0 && ledger.refundReview(pendingRefundPlayer).isEmpty()) {
                refundedPrevious = recoverRefundInternal(pendingRefundPlayer, false);
                if (!refundedPrevious) {
                    messages.broadcastRaw("<yellow>ClasherBid: previous bidder refund is queued and needs admin recovery review.</yellow>");
                }
            }
            pendingRefundPlayer = null;
            pendingRefundAmount = 0.0;
        }

        boolean antiSnipeTriggered = false;
        long antiSnipeExtension = 0L;
        if (config.antiSnipingEnabled()) {
            long trigger = config.antiSnipingTriggerSeconds();
            long extension = config.antiSnipingExtendSeconds();
            if (extension > 0 && secondsLeft() <= trigger) {
                endsAt += extension * 1000L;
                lastAlertedSecond = Long.MAX_VALUE;
                antiSnipeTriggered = true;
                antiSnipeExtension = extension;
            }
        }

        if (!persist()) {
            messages.broadcastRaw("<red>ClasherBid: bid accepted but persistence is uncertain; auction frozen for admin review.</red>");
            return new BidResult(false, messages.parse("<yellow>Bid payment accepted, but auction is frozen for admin review.</yellow>"));
        }

        UUID acceptedTransactionId = pendingBidTransactionId;
        settlementReview = "";
        pendingBidder = null;
        pendingBidderName = "";
        pendingBidAmount = 0.0;
        pendingBidTransactionId = null;
        if (!persist()) {
            settlementReview = "BID_PENDING_ACCEPTED";
            pendingBidTransactionId = acceptedTransactionId;
            messages.broadcastRaw("<red>ClasherBid: accepted bid marker could not be cleared; auction frozen for admin review.</red>");
            return new BidResult(false, messages.parse("<yellow>Bid payment accepted, but auction is frozen for admin review.</yellow>"));
        }

        // Notify previous bidder if online and different from current bidder.
        if (previousBidder != null && !previousBidder.equals(player.getUniqueId())) {
            Player outbidPlayer = Bukkit.getPlayer(previousBidder);
            if (outbidPlayer != null) {
                String key = refundedPrevious ? "player.outbid" : "player.outbid-refund-queued";
                messages.send(outbidPlayer, key,
                        Placeholder.unparsed("bidder", player.getName()),
                        Placeholder.unparsed("new_bid", economy.format(amount)),
                        Placeholder.unparsed("refund", economy.format(previousEscrow))
                );
                messages.playSound(outbidPlayer, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.85f);
            }
        }

        messages.broadcast("broadcast.bid-placed",
                Placeholder.unparsed("player", player.getName()),
                Placeholder.unparsed("amount", economy.format(amount)),
                Placeholder.unparsed("item", FormatUtil.formatItemName(item)),
                Placeholder.unparsed("time", FormatUtil.formatDuration(secondsLeft()))
        );
        if (antiSnipeTriggered && config.antiSnipingBroadcast()) {
            messages.broadcast("broadcast.anti-snipe",
                    Placeholder.unparsed("seconds", String.valueOf(antiSnipeExtension)),
                    Placeholder.unparsed("time", FormatUtil.formatDuration(secondsLeft()))
            );
        }

        return new BidResult(true, messages.render("player.bid-accepted",
                Placeholder.unparsed("amount", economy.format(amount))));
    }

    // -------------------------------------------------------------------------
    // Cancellation & timer tick
    // -------------------------------------------------------------------------

    public boolean cancel() {
        if (!active || !settlementReview.isEmpty()) return false;
        settlementReview = "CANCEL_PENDING";
        if (!persist()) {
            settlementReview = "";
            return false;
        }
        messages.broadcastRaw("<red>ClasherBid cancellation frozen for admin reconciliation. Verify balances and item delivery, then use /clasherbid settlement <paid|unpaid>.</red>");
        return true;
    }

    private void schedule() {
        if (timer != null) timer.cancel();
        timer = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void tick() {
        if (!active) return;
        if (System.currentTimeMillis() >= endsAt) {
            finish();
            return;
        }
        if (!settlementReview.isEmpty() || !config.countdownAlertsEnabled()) return;
        long remaining = secondsLeft();
        for (long threshold : config.countdownAlertSeconds().descendingSet()) {
            if (remaining <= threshold && threshold < lastAlertedSecond) {
                lastAlertedSecond = threshold;
                messages.broadcast("broadcast.countdown",
                        Placeholder.unparsed("item", FormatUtil.formatItemName(item)),
                        Placeholder.unparsed("time", FormatUtil.formatDuration(remaining)),
                        Placeholder.unparsed("amount", economy.format(currentBid))
                );
                break;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Settlement
    // -------------------------------------------------------------------------

    public void finish() {
        if (!active) return;
        if (!settlementReview.isEmpty() && !settlementReview.equals("PAYOUT_RETRY")) {
            if (timer != null) timer.cancel();
            timer = null;
            return;
        }
        if (settlementReview.equals("PAYOUT_RETRY")) {
            settlementReview = "";
            if (!persist()) {
                settlementReview = "PAYOUT_RETRY";
                return;
            }
        }
        if (bidder == null) {
            settlementReview = "DELIVERY_PENDING";
            if (!persist()) {
                settlementReview = "";
                return;
            }
            if (!ledger.queueDelivery(seller, item, "auction-" + auctionTransactionId() + "-seller-return")) return;
            claimIfOnline(seller);
            if (!history.record(FormatUtil.formatItemName(item), "No bids", economy.format(0.0), auctionTransactionId())) return;
            messages.broadcast("broadcast.auction-no-bids",
                    Placeholder.unparsed("item", FormatUtil.formatItemName(item)));
        } else {
            UUID payee = payoutAccount();
            if (payee == null) {
                messages.broadcastRaw("<red>ClasherBid payout account unavailable; auction frozen. Fix payout-player in config.yml, run /clasherbid reload, and then /clasherbid retry.</red>");
                settlementReview = "PAYOUT_ACCOUNT_INVALID";
                endsAt = System.currentTimeMillis() + 60_000L;
                if (!persist()) {
                    plugin.getLogger().severe("Could not persist payout-account review marker; auction remains frozen in memory.");
                }
                return;
            }
            // Persist transaction intent before touching Vault.
            settlementReview = "PAYOUT_PENDING";
            if (!persist()) {
                messages.broadcastRaw("<red>ClasherBid payout paused: could not persist transaction intent.</red>");
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
                    messages.broadcastRaw("<red>ClasherBid payout failed and retry state could not be saved; auction remains frozen for admin review.</red>");
                    return;
                }
                messages.broadcastRaw("<red>ClasherBid payout failed; bidding is paused and settlement will retry in 60 seconds.</red>");
                schedule();
                return;
            }
            settlementReview = "PAYOUT_COMPLETED";
            if (!persist()) return;
            Player payoutRecipient = Bukkit.getPlayer(payee);
            if (payoutRecipient != null) {
                messages.send(payoutRecipient, "player.payout-credited",
                        Placeholder.unparsed("amount", economy.format(escrow)));
            }
            if (!ledger.queueDelivery(bidder, item, "auction-" + auctionTransactionId() + "-winner")) return;
            claimIfOnline(bidder);
            if (!history.record(FormatUtil.formatItemName(item), bidderName, economy.format(currentBid), auctionTransactionId())) return;
            messages.broadcast("broadcast.auction-won",
                    Placeholder.unparsed("winner", bidderName),
                    Placeholder.unparsed("item", FormatUtil.formatItemName(item)),
                    Placeholder.unparsed("amount", economy.format(currentBid))
            );
            messages.playSoundAll(Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.1f);
        }
        clear();
    }

    // -------------------------------------------------------------------------
    // Admin recovery reviews
    // -------------------------------------------------------------------------

    /** Resolve an interrupted bid withdrawal; paid accepts it, unpaid clears the intent. */
    public boolean resolveBidReview(boolean paid) {
        if (!active || !settlementReview.startsWith("BID_PENDING")) return false;
        if (settlementReview.equals("BID_PENDING_ACCEPTED")) {
            if (!paid) return false;
            UUID previousRefundPlayer = pendingRefundPlayer;
            double previousRefundAmount = pendingRefundAmount;
            if (pendingRefundPlayer != null && pendingRefundAmount > 0.0) {
                if (!ledger.queueRefund(pendingRefundPlayer, pendingRefundAmount, "outbid-" + pendingBidTransactionId)) {
                    return false;
                }
                pendingRefundPlayer = null;
                pendingRefundAmount = 0.0;
            }
            settlementReview = "";
            if (persist()) {
                schedule();
                return true;
            }
            settlementReview = "BID_PENDING_ACCEPTED";
            pendingRefundPlayer = previousRefundPlayer;
            pendingRefundAmount = previousRefundAmount;
            return false;
        }
        if (pendingBidder == null || pendingBidAmount <= 0.0) return false;

        UUID previousBidder = bidder;
        String previousBidderName = bidderName;
        double previousBid = currentBid;
        double previousEscrow = escrow;
        String previousReview = settlementReview;
        UUID intentBidder = pendingBidder;
        UUID intentTransactionId = pendingBidTransactionId;
        UUID previousRefundPlayer = pendingRefundPlayer;
        double previousRefundAmount = pendingRefundAmount;
        List<String> previousBidHistory = new ArrayList<>(bidHistory);
        String intentName = pendingBidderName;
        double intentAmount = pendingBidAmount;
        boolean acceptedJournalPersisted = false;

        if (paid) {
            bidder = intentBidder;
            bidderName = intentName;
            currentBid = intentAmount;
            escrow = intentAmount;
            recordBid(bidderName, currentBid);
            if (previousBidder != null && previousEscrow > 0.0) {
                pendingRefundPlayer = previousBidder;
                pendingRefundAmount = previousEscrow;
                settlementReview = "BID_PENDING_ACCEPTED";
                pendingBidder = null;
                pendingBidderName = "";
                pendingBidAmount = 0.0;
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
                    bidHistory.clear();
                    bidHistory.addAll(previousBidHistory);
                    return false;
                }
                acceptedJournalPersisted = true;
                if (!ledger.queueRefund(pendingRefundPlayer, pendingRefundAmount, "outbid-" + intentTransactionId)) {
                    return false;
                }
                if (ledger.pendingRefund(pendingRefundPlayer) > 0.0 && ledger.refundReview(pendingRefundPlayer).isEmpty()
                        && !recoverRefund(pendingRefundPlayer)) {
                    plugin.getLogger().warning("Previous bidder refund remains queued for guarded admin recovery: " + pendingRefundPlayer);
                }
                pendingRefundPlayer = null;
                pendingRefundAmount = 0.0;
            }
        }
        settlementReview = "";
        pendingBidder = null;
        pendingBidderName = "";
        pendingBidAmount = 0.0;
        pendingBidTransactionId = null;
        if (persist()) {
            schedule();
            return true;
        }
        if (acceptedJournalPersisted) {
            bidder = intentBidder;
            bidderName = intentName;
            currentBid = intentAmount;
            escrow = intentAmount;
            settlementReview = "BID_PENDING_ACCEPTED";
            pendingBidder = null;
            pendingBidderName = "";
            pendingBidAmount = 0.0;
            pendingBidTransactionId = intentTransactionId;
            pendingRefundPlayer = null;
            pendingRefundAmount = 0.0;
            return false;
        }
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
        bidHistory.clear();
        bidHistory.addAll(previousBidHistory);
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
        if (!active || recoveryBlocked || settlementReview.isEmpty() || settlementReview.startsWith("BID_PENDING")
                || settlementReview.equals("CANCEL_PENDING") || settlementReview.equals("AUCTION_DATA_CORRUPT")
                || settlementReview.equals("PAYOUT_ACCOUNT_INVALID") || settlementReview.equals("PAYOUT_RETRY")
                || settlementReview.equals("SETTLEMENT_UNPAID_PENDING")
                || bidder == null || item == null) {
            return false;
        }
        if (!alreadyChosen) {
            String previousReview = settlementReview;
            settlementReview = "SETTLEMENT_PAID_PENDING";
            if (!persist()) {
                settlementReview = previousReview;
                return false;
            }
        }
        if (!ledger.queueDelivery(bidder, item, "auction-" + auctionTransactionId() + "-winner")) return false;
        claimIfOnline(bidder);
        if (!history.record(FormatUtil.formatItemName(item), bidderName, economy.format(currentBid), auctionTransactionId())) return false;
        messages.broadcastRaw("<gold><bold>CLASHERBID</bold></gold> <dark_gray>»</dark_gray> <yellow>Settlement manually confirmed paid by admin. Winner item queued for claim.</yellow>");
        return clear();
    }

    public boolean resolveSettlementUnpaid() {
        boolean alreadyChosen = settlementReview.equals("SETTLEMENT_UNPAID_PENDING");
        if (!active || recoveryBlocked || settlementReview.isEmpty() || settlementReview.startsWith("BID_PENDING")
                || settlementReview.equals("START_PENDING") || settlementReview.equals("PAYOUT_COMPLETED")
                || settlementReview.equals("SETTLEMENT_PAID_PENDING")
                || settlementReview.equals("AUCTION_DATA_CORRUPT") || item == null || seller == null) {
            return false;
        }
        if (!alreadyChosen) {
            String previousReview = settlementReview;
            settlementReview = "SETTLEMENT_UNPAID_PENDING";
            if (!persist()) {
                settlementReview = previousReview;
                return false;
            }
        }
        if (bidder != null && escrow > 0.0) {
            if (!ledger.queueRefund(bidder, escrow, "settlement-" + auctionTransactionId())) {
                plugin.getLogger().severe("Could not persist bidder refund liability; settlement remains frozen and item was not returned.");
                return false;
            }
        }
        if (!ledger.queueDelivery(seller, item, "auction-" + auctionTransactionId() + "-seller-return")) return false;
        claimIfOnline(seller);
        if (bidder == null && !history.record(FormatUtil.formatItemName(item), "No bids", economy.format(0.0), auctionTransactionId())) {
            return false;
        }
        messages.broadcastRaw("<gold><bold>CLASHERBID</bold></gold> <dark_gray>»</dark_gray> <yellow>Settlement manually confirmed unpaid by admin. Auction item queued for claim.</yellow>");
        return clear();
    }

    public boolean recoverRefund(UUID playerId) {
        return recoverRefundInternal(playerId, true);
    }

    private boolean recoverRefundInternal(UUID playerId, boolean notifyRecipient) {
        double amount = ledger.pendingRefund(playerId);
        if (!Double.isFinite(amount) || amount <= 0.0 || !ledger.refundReview(playerId).isEmpty()) return false;
        if (!ledger.markRefundPending(playerId)) return false;

        EconomyResponse response;
        try {
            response = economy.depositPlayer(Bukkit.getOfflinePlayer(playerId), amount);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "Vault threw during refund recovery; refund remains frozen for manual review.", ex);
            return false;
        }
        if (response == null || !response.transactionSuccess()) {
            return false; // keep marker for manual review
        }
        if (!ledger.clearRefundAfterSuccess(playerId, amount)) {
            return false;
        }
        if (notifyRecipient) {
            Player recipient = Bukkit.getPlayer(playerId);
            if (recipient != null) {
                messages.send(recipient, "player.refund-credited",
                        Placeholder.unparsed("amount", economy.format(amount)));
            }
        }
        return true;
    }

    public boolean resolveRefundReview(UUID playerId, boolean paid) {
        return ledger.resolveRefundReview(playerId, paid);
    }

    public boolean resolveClaimReview(UUID playerId, boolean delivered) {
        if (!ledger.resolveClaimReview(playerId, delivered)) return false;
        if (!delivered) {
            Player target = Bukkit.getPlayer(playerId);
            if (target != null) {
                Bukkit.getScheduler().runTask(plugin, () -> claim(target));
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Item delivery / claim
    // -------------------------------------------------------------------------

    private void claimIfOnline(UUID playerId) {
        Player recipient = playerId == null ? null : Bukkit.getPlayer(playerId);
        if (recipient != null) claim(recipient);
    }

    public void claim(Player player) {
        claim(player, false);
    }

    public void claim(Player player, boolean notifyWhenEmpty) {
        UUID playerId = player.getUniqueId();
        if (!ledger.claimReview(playerId).isEmpty()) {
            messages.send(player, "player.claim-frozen");
            return;
        }
        LedgerStore.DeliveryReadResult read = ledger.readPendingDeliveries(playerId);
        if (read.corrupt()) {
            if (!ledger.setClaimReview(playerId, "CLAIM_DATA_CORRUPT")) {
                plugin.getLogger().severe("Corrupt pending delivery data could not be marked for review: " + playerId);
            }
            messages.sendRaw(player, "<red>Pending delivery data is invalid and has been frozen. Contact an admin; no items were delivered.</red>");
            return;
        }
        List<ItemStack> pending = read.items();
        if (pending.isEmpty()) {
            if (notifyWhenEmpty) messages.send(player, "player.claim-empty");
            return;
        }

        // Conservative preflight: require enough empty storage slots for every stack
        // so a full inventory cannot cause a partial claim during normal operation.
        int emptySlots = 0;
        for (ItemStack content : player.getInventory().getStorageContents()) {
            if (content == null || content.getType().isAir()) emptySlots++;
        }
        long requiredSlots = 0;
        for (ItemStack stack : pending) {
            int maxStack = Math.max(1, Math.min(stack.getMaxStackSize(), player.getInventory().getMaxStackSize()));
            requiredSlots += (stack.getAmount() + (long) maxStack - 1L) / maxStack;
        }
        if (emptySlots < requiredSlots) {
            messages.send(player, "player.claim-inventory-full",
                    Placeholder.unparsed("slots", String.valueOf(requiredSlots - emptySlots)));
            messages.playSound(player, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f);
            return;
        }

        // Persist a claim intent before inventory changes.
        if (!ledger.setClaimReview(playerId, "CLAIM_PENDING")) {
            messages.sendRaw(player, "<red>Could not save claim review marker. No items were delivered; contact an admin.</red>");
            return;
        }

        List<ItemStack> remaining = new ArrayList<>();
        long deliveredAmount = 0;
        for (ItemStack stack : pending) {
            if (stack == null || stack.getType().isAir()) continue;
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack.clone());
            long overflowAmount = overflow.values().stream().mapToLong(ItemStack::getAmount).sum();
            deliveredAmount += stack.getAmount() - overflowAmount;
            if (!overflow.isEmpty()) remaining.addAll(overflow.values());
        }
        if (!ledger.finalizeClaim(playerId, remaining, read.rawStored())) {
            messages.sendRaw(player, "<yellow>Delivery was attempted but its final state could not be saved. Claim is frozen for admin review; do not retry.</yellow>");
            return;
        }
        if (deliveredAmount > 0) {
            messages.send(player, "player.claim-delivered",
                    Placeholder.unparsed("count", String.valueOf(deliveredAmount)));
            messages.playSound(player, Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.1f);
        }
        if (!remaining.isEmpty()) {
            messages.send(player, "player.claim-partial-remaining");
        }
    }

    // -------------------------------------------------------------------------
    // Persistence & startup recovery
    // -------------------------------------------------------------------------

    private String auctionTransactionId() {
        if (auctionId == null) auctionId = UUID.randomUUID();
        return auctionId.toString();
    }

    private UUID payoutAccount() {
        String configured = config.payoutPlayer();
        if (configured.isBlank()) return seller;
        OfflinePlayer account = Bukkit.getOfflinePlayer(configured);
        if (!account.isOnline() && !account.hasPlayedBefore()) {
            plugin.getLogger().severe("Configured payout-player has never joined this server: " + configured);
            return null;
        }
        return account.getUniqueId();
    }

    private void resetInMemory() {
        active = false;
        auctionId = null;
        item = null;
        seller = null;
        sellerName = "";
        bidder = null;
        bidderName = "";
        currentBid = 0.0;
        escrow = 0.0;
        endsAt = 0L;
        lastBidAt = 0L;
        lastAlertedSecond = Long.MAX_VALUE;
        settlementReview = "";
        pendingBidder = null;
        pendingBidTransactionId = null;
        pendingBidderName = "";
        pendingBidAmount = 0.0;
        pendingRefundPlayer = null;
        pendingRefundAmount = 0.0;
        bidHistory.clear();
    }

    private boolean clear() {
        ItemStack oldItem = item;
        UUID oldSeller = seller, oldBidder = bidder, oldAuctionId = auctionId;
        String oldSellerName = sellerName, oldName = bidderName, oldReview = settlementReview;
        double oldBid = currentBid, oldEscrow = escrow;
        long oldEndsAt = endsAt;
        List<String> oldHistory = new ArrayList<>(bidHistory);

        resetInMemory();
        if (!persist()) {
            active = true;
            auctionId = oldAuctionId;
            item = oldItem;
            seller = oldSeller;
            sellerName = oldSellerName;
            bidder = oldBidder;
            bidderName = oldName;
            currentBid = oldBid;
            escrow = oldEscrow;
            endsAt = oldEndsAt;
            settlementReview = oldReview;
            bidHistory.addAll(oldHistory);
            plugin.getLogger().severe("Delivery queued but auction clear failed; original settlement state restored for admin review.");
            return false;
        }
        if (timer != null) timer.cancel();
        timer = null;
        return true;
    }

    public void load() {
        history.load();
        ledger.load(plugin.getConfig());
        if (!stateFile.exists()) return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(stateFile);
        Object activeValue = yaml.get("active");
        if (!(activeValue instanceof Boolean activeFlag)) {
            recoveryBlocked = true;
            plugin.getLogger().severe("Saved auction file has a missing or invalid active flag. Original file preserved; command-based auction operations are disabled.");
            return;
        }
        if (!activeFlag) return;

        item = yaml.getItemStack("item");
        seller = parseUuid(yaml.getString("seller"));
        sellerName = Objects.toString(yaml.getString("sellerName", ""), "");
        bidder = parseUuid(yaml.getString("bidder"));
        bidderName = Objects.toString(yaml.getString("bidderName", ""), "");
        currentBid = yaml.getDouble("currentBid");
        escrow = yaml.getDouble("escrow");
        endsAt = yaml.getLong("endsAt");
        bidHistory.clear();
        bidHistory.addAll(yaml.getStringList("recentBids"));
        settlementReview = Objects.toString(yaml.getString("settlementReview", ""), "");
        pendingBidder = parseUuid(yaml.getString("pendingBidder"));
        pendingBidderName = Objects.toString(yaml.getString("pendingBidderName", ""), "");
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

        active = item != null && !item.getType().isAir() && item.getAmount() > 0 && seller != null;
        if (!active) {
            recoveryBlocked = true;
            plugin.getLogger().severe("Saved auction is marked active but its item or seller data is missing/invalid. The original file was preserved; new auctions are disabled until manual file-level recovery.");
            return;
        }

        boolean invalidCoreState = !Double.isFinite(currentBid) || currentBid <= 0.0
                || !Double.isFinite(escrow) || escrow < 0.0 || endsAt <= 0L
                || (bidder == null && escrow > 0.0)
                || (bidder != null && (bidder.equals(seller) || bidderName == null || bidderName.isBlank()
                    || escrow <= 0.0 || Math.abs(escrow - currentBid) > 0.000001))
                || (pendingRefundPlayer == null && pendingRefundAmount != 0.0)
                || (pendingRefundPlayer != null && (!Double.isFinite(pendingRefundAmount) || pendingRefundAmount <= 0.0));

        boolean invalidPendingBid = settlementReview.startsWith("BID_PENDING")
                && (pendingBidTransactionId == null
                || (settlementReview.equals("BID_PENDING") && (pendingBidder == null
                    || pendingBidder.equals(seller)
                    || (!config.allowSelfOutbid() && pendingBidder.equals(bidder))
                    || !Double.isFinite(pendingBidAmount) || pendingBidAmount <= 0.0))
                || (settlementReview.equals("BID_PENDING_ACCEPTED") && pendingBidder != null));

        boolean orphanedIntent = settlementReview.isEmpty()
                && (pendingBidder != null || pendingBidAmount != 0.0 || !pendingBidderName.isEmpty()
                    || pendingRefundPlayer != null);

        if (invalidCoreState || invalidPendingBid || orphanedIntent) {
            recoveryBlocked = true;
            settlementReview = "AUCTION_DATA_CORRUPT";
            plugin.getLogger().severe("Saved auction data failed integrity checks. Auction frozen; inspect active-auction.yml and economy balances before recovery.");
        }
        if (active && migratedAuctionId && !invalidCoreState && !invalidPendingBid && !orphanedIntent && !persist()) {
            recoveryBlocked = true;
            plugin.getLogger().severe("Legacy auction ID migration could not be persisted; original file is preserved and command-based settlement is disabled.");
        }
        if (active) {
            if (settlementReview.equals("PAYOUT_RETRY")) {
                if (endsAt <= System.currentTimeMillis()) finish();
                else schedule();
            } else if (!settlementReview.isEmpty()) {
                plugin.getLogger().severe("Auction settlement frozen for admin review: " + settlementReview
                        + ". Do NOT manually retry payout until economy balances are checked.");
            } else if (endsAt <= System.currentTimeMillis()) {
                finish();
            } else {
                schedule();
            }
        }
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
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
        yaml.set("sellerName", sellerName);
        yaml.set("bidder", bidder == null ? null : bidder.toString());
        yaml.set("bidderName", bidderName);
        yaml.set("currentBid", currentBid);
        yaml.set("escrow", escrow);
        yaml.set("endsAt", endsAt);
        yaml.set("recentBids", new ArrayList<>(bidHistory));
        return YamlAtomicStorage.saveAtomically(
                yaml,
                stateFile,
                plugin.getLogger(),
                "Could not safely persist active auction. The previous file may remain intact."
        );
    }
}
