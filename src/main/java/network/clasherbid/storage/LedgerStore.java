package network.clasherbid.storage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Dedicated, atomically persisted ledger (ledger.yml) for:
 * - pending item deliveries and idempotent delivery transaction records
 * - pending refund liabilities and idempotent refund transaction records
 * - conservative crash-recovery review markers for claims and refunds
 *
 * Automatically migrates legacy entries from config.yml on first load without data loss.
 */
public final class LedgerStore {
    private final File ledgerFile;
    private final File legacyConfigFile;
    private final Logger logger;
    private YamlConfiguration ledger = new YamlConfiguration();

    public LedgerStore(File dataFolder, Logger logger) {
        this.ledgerFile = new File(dataFolder, "ledger.yml");
        this.legacyConfigFile = new File(dataFolder, "config.yml");
        this.logger = logger;
    }

    public void load(FileConfiguration pluginConfig) {
        if (ledgerFile.exists()) {
            this.ledger = YamlConfiguration.loadConfiguration(ledgerFile);
        } else {
            this.ledger = new YamlConfiguration();
        }
        migrateLegacyEntries(pluginConfig);
    }

    /**
     * One-time crash-safe migration of legacy recovery/pending-delivery entries from config.yml into ledger.yml.
     * ledger.yml is saved atomically BEFORE removing the legacy sections from config.yml.
     */
    private void migrateLegacyEntries(FileConfiguration pluginConfig) {
        if (pluginConfig == null) return;
        boolean hasRecovery = pluginConfig.contains("recovery");
        boolean hasDelivery = pluginConfig.contains("pending-delivery");
        if (!hasRecovery && !hasDelivery) return;

        if (hasRecovery) {
            copySectionIfAbsent(pluginConfig.getConfigurationSection("recovery"), "recovery");
        }
        if (hasDelivery) {
            copySectionIfAbsent(pluginConfig.getConfigurationSection("pending-delivery"), "pending-delivery");
        }
        if (!save()) {
            logger.severe("Failed to migrate legacy recovery/delivery entries from config.yml into ledger.yml.");
            return;
        }
        pluginConfig.set("recovery", null);
        pluginConfig.set("pending-delivery", null);
        if (pluginConfig instanceof YamlConfiguration yamlConfig) {
            YamlAtomicStorage.saveAtomically(
                    yamlConfig,
                    legacyConfigFile,
                    logger,
                    "Migrated ledger.yml successfully, but could not clean legacy keys from config.yml."
            );
        }
        logger.info("Migrated legacy recovery and pending-delivery records from config.yml into ledger.yml.");
    }

    private void copySectionIfAbsent(ConfigurationSection source, String targetPrefix) {
        if (source == null) return;
        for (String key : source.getKeys(true)) {
            if (source.isConfigurationSection(key)) continue;
            String fullPath = targetPrefix + "." + key;
            if (!ledger.contains(fullPath)) {
                ledger.set(fullPath, source.get(key));
            }
        }
    }

    public boolean save() {
        return YamlAtomicStorage.saveAtomically(
                ledger,
                ledgerFile,
                logger,
                "Could not safely persist ledger.yml. Related transaction remains frozen."
        );
    }

    // -------------------------------------------------------------------------
    // Refund liabilities & review markers
    // -------------------------------------------------------------------------

    public double pendingRefund(UUID playerId) {
        if (playerId == null) return 0.0;
        return ledger.getDouble("recovery.refund." + playerId, 0.0);
    }

    public String refundReview(UUID playerId) {
        if (playerId == null) return "";
        return ledger.getString("recovery.refund-review." + playerId, "");
    }

    public boolean queueRefund(UUID playerId, double amount, String transactionId) {
        if (playerId == null || !Double.isFinite(amount) || amount <= 0.0 || transactionId == null || transactionId.isBlank()) {
            return false;
        }
        String transactionPath = "recovery.refund-transactions." + transactionId;
        if (ledger.contains(transactionPath)) {
            String recordedPlayer = ledger.getString(transactionPath + ".player", "");
            double recordedAmount = ledger.getDouble(transactionPath + ".amount", Double.NaN);
            if (playerId.toString().equals(recordedPlayer)
                    && Double.isFinite(recordedAmount)
                    && Math.abs(recordedAmount - amount) < 0.000001) {
                return true;
            }
            logger.severe("Refund transaction key collision or corrupt ledger entry: " + transactionId);
            return false;
        }
        String refundPath = "recovery.refund." + playerId;
        double previous = ledger.getDouble(refundPath, 0.0);
        if (!Double.isFinite(previous) || previous < 0.0 || !Double.isFinite(previous + amount)) {
            logger.severe("Invalid refund balance or overflow for " + playerId);
            return false;
        }
        ledger.set(transactionPath + ".player", playerId.toString());
        ledger.set(transactionPath + ".amount", amount);
        ledger.set(refundPath, previous + amount);
        if (save()) return true;
        ledger.set(transactionPath, null);
        ledger.set(refundPath, previous > 0.0 ? previous : null);
        return false;
    }

    public boolean markRefundPending(UUID playerId) {
        String reviewPath = "recovery.refund-review." + playerId;
        String previous = ledger.getString(reviewPath, "");
        ledger.set(reviewPath, "REFUND_PENDING");
        if (save()) return true;
        ledger.set(reviewPath, previous.isEmpty() ? null : previous);
        return false;
    }

    public boolean clearRefundAfterSuccess(UUID playerId, double previousAmount) {
        ledger.set("recovery.refund." + playerId, null);
        ledger.set("recovery.refund-review." + playerId, null);
        if (save()) return true;
        ledger.set("recovery.refund." + playerId, previousAmount);
        ledger.set("recovery.refund-review." + playerId, "REFUND_PENDING");
        return false;
    }

    public boolean resolveRefundReview(UUID playerId, boolean paid) {
        String reviewPath = "recovery.refund-review." + playerId;
        String refundPath = "recovery.refund." + playerId;
        String previousReview = ledger.getString(reviewPath, "");
        double previousAmount = ledger.getDouble(refundPath, 0.0);
        if (previousReview.isEmpty()) return false;
        if (paid) ledger.set(refundPath, null);
        ledger.set(reviewPath, null);
        if (save()) return true;
        ledger.set(reviewPath, previousReview);
        if (previousAmount > 0.0) ledger.set(refundPath, previousAmount);
        return false;
    }

    // -------------------------------------------------------------------------
    // Pending item deliveries & claim review markers
    // -------------------------------------------------------------------------

    public String claimReview(UUID playerId) {
        if (playerId == null) return "";
        return ledger.getString("recovery.claim-review." + playerId, "");
    }

    public boolean setClaimReview(UUID playerId, String marker) {
        String reviewPath = "recovery.claim-review." + playerId;
        String previous = ledger.getString(reviewPath, "");
        ledger.set(reviewPath, marker);
        if (save()) return true;
        ledger.set(reviewPath, previous.isEmpty() ? null : previous);
        return false;
    }

    public boolean queueDelivery(UUID playerId, ItemStack stack, String transactionId) {
        if (playerId == null || stack == null || stack.getType().isAir() || stack.getAmount() <= 0
                || transactionId == null || transactionId.isBlank()) {
            return false;
        }
        String tx = "recovery.delivery-transactions." + transactionId;
        if (ledger.contains(tx)) {
            String recordedPlayer = ledger.getString(tx + ".player", "");
            ItemStack recordedItem = ledger.getItemStack(tx + ".item");
            if (playerId.toString().equals(recordedPlayer) && recordedItem != null
                    && recordedItem.isSimilar(stack) && recordedItem.getAmount() == stack.getAmount()) {
                return true;
            }
            logger.severe("Delivery transaction key collision or corrupt ledger entry: " + transactionId);
            return false;
        }
        String path = "pending-delivery." + playerId;
        Object previousValue = ledger.get(path);
        List<?> raw = ledger.getList(path);
        List<ItemStack> pending = new ArrayList<>();
        if (raw != null) {
            for (Object value : raw) {
                if (value instanceof ItemStack old && !old.getType().isAir() && old.getAmount() > 0) {
                    pending.add(old.clone());
                } else {
                    logger.severe("Refusing to append delivery: existing pending-delivery data is corrupt for " + playerId);
                    return false;
                }
            }
        } else if (previousValue instanceof ItemStack legacy && !legacy.getType().isAir() && legacy.getAmount() > 0) {
            pending.add(legacy.clone());
        } else if (previousValue != null) {
            logger.severe("Refusing to overwrite unsupported pending-delivery data for " + playerId);
            return false;
        }
        pending.add(stack.clone());
        ledger.set(path, pending);
        ledger.set(tx + ".player", playerId.toString());
        ledger.set(tx + ".item", stack.clone());
        if (save()) return true;
        ledger.set(path, previousValue);
        ledger.set(tx, null);
        return false;
    }

    public record DeliveryReadResult(List<ItemStack> items, Object rawStored, boolean corrupt) {}

    public DeliveryReadResult readPendingDeliveries(UUID playerId) {
        String path = "pending-delivery." + playerId;
        Object stored = ledger.get(path);
        List<?> raw = ledger.getList(path);
        List<ItemStack> pending = new ArrayList<>();
        boolean corrupt = false;
        if (raw != null) {
            for (Object value : raw) {
                if (value instanceof ItemStack stack && !stack.getType().isAir() && stack.getAmount() > 0) {
                    pending.add(stack.clone());
                } else {
                    corrupt = true;
                }
            }
        } else if (stored instanceof ItemStack legacy && !legacy.getType().isAir() && legacy.getAmount() > 0) {
            pending.add(legacy.clone());
        } else if (stored != null) {
            corrupt = true;
        }
        if (corrupt || (stored != null && pending.isEmpty() && (raw == null || !raw.isEmpty()))) {
            return new DeliveryReadResult(List.of(), stored, true);
        }
        return new DeliveryReadResult(pending, stored, false);
    }

    public int pendingDeliveryCount(UUID playerId) {
        if (playerId == null || !claimReview(playerId).isEmpty()) return 0;
        DeliveryReadResult result = readPendingDeliveries(playerId);
        return result.corrupt() ? 0 : result.items().size();
    }

    public boolean finalizeClaim(UUID playerId, List<ItemStack> remaining, Object previousStored) {
        String path = "pending-delivery." + playerId;
        String reviewPath = "recovery.claim-review." + playerId;
        ledger.set(path, remaining.isEmpty() ? null : remaining);
        ledger.set(reviewPath, null);
        if (save()) return true;
        ledger.set(path, previousStored);
        ledger.set(reviewPath, "CLAIM_PENDING");
        return false;
    }

    public boolean resolveClaimReview(UUID playerId, boolean delivered) {
        String reviewPath = "recovery.claim-review." + playerId;
        String deliveryPath = "pending-delivery." + playerId;
        String previousReview = ledger.getString(reviewPath, "");
        Object previousDelivery = ledger.get(deliveryPath);
        if (previousReview.isEmpty()) return false;
        if (delivered) ledger.set(deliveryPath, null);
        ledger.set(reviewPath, null);
        if (save()) return true;
        ledger.set(reviewPath, previousReview);
        if (delivered) ledger.set(deliveryPath, previousDelivery);
        return false;
    }

    // -------------------------------------------------------------------------
    // Admin diagnostics helpers
    // -------------------------------------------------------------------------

    public Set<UUID> pendingRefundPlayers() {
        return uuidsInSection("recovery.refund");
    }

    public Set<UUID> refundReviewPlayers() {
        return uuidsInSection("recovery.refund-review");
    }

    public Set<UUID> claimReviewPlayers() {
        return uuidsInSection("recovery.claim-review");
    }

    public Set<UUID> pendingDeliveryPlayers() {
        return uuidsInSection("pending-delivery");
    }

    private Set<UUID> uuidsInSection(String sectionPath) {
        ConfigurationSection section = ledger.getConfigurationSection(sectionPath);
        if (section == null) return Collections.emptySet();
        Set<UUID> result = new LinkedHashSet<>();
        for (String key : section.getKeys(false)) {
            try {
                result.add(UUID.fromString(key));
            } catch (IllegalArgumentException ignored) {
                // Skip non-UUID keys.
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
