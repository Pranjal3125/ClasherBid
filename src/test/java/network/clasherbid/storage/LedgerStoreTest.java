package network.clasherbid.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

final class LedgerStoreTest {

    @Test
    void refundAccountingIsIdempotentAndGuarded(@TempDir Path tempDir) {
        Logger logger = Logger.getLogger("LedgerStoreTest");
        LedgerStore store = new LedgerStore(tempDir.toFile(), logger);
        store.load(new YamlConfiguration());

        UUID player = UUID.randomUUID();
        assertTrue(store.queueRefund(player, 500.0, "outbid-1"));
        // Same transaction id with same player/amount is idempotent.
        assertTrue(store.queueRefund(player, 500.0, "outbid-1"));
        assertEquals(500.0, store.pendingRefund(player), 0.0001);

        // Colliding transaction id with different amount is rejected.
        assertFalse(store.queueRefund(player, 750.0, "outbid-1"));
        assertEquals(500.0, store.pendingRefund(player), 0.0001);

        // Second distinct refund accumulates cleanly.
        assertTrue(store.queueRefund(player, 250.0, "outbid-2"));
        assertEquals(750.0, store.pendingRefund(player), 0.0001);

        // Freeze for review, then mark unpaid -> unlocks retry while preserving debt.
        assertTrue(store.markRefundPending(player));
        assertEquals("REFUND_PENDING", store.refundReview(player));
        assertTrue(store.resolveRefundReview(player, false));
        assertEquals("", store.refundReview(player));
        assertEquals(750.0, store.pendingRefund(player), 0.0001);

        // Freeze again, then mark paid -> clears debt and review marker.
        assertTrue(store.markRefundPending(player));
        assertTrue(store.resolveRefundReview(player, true));
        assertEquals("", store.refundReview(player));
        assertEquals(0.0, store.pendingRefund(player), 0.0001);
    }

    @Test
    void migratesLegacyRecoveryEntriesFromConfigYaml(@TempDir Path tempDir) {
        Logger logger = Logger.getLogger("LedgerStoreMigrationTest");
        UUID player = UUID.randomUUID();

        YamlConfiguration legacyConfig = new YamlConfiguration();
        legacyConfig.set("minimum-increment", 100.0);
        legacyConfig.set("recovery.refund." + player, 1200.0);
        legacyConfig.set("recovery.refund-review." + player, "REFUND_PENDING");

        LedgerStore store = new LedgerStore(tempDir.toFile(), logger);
        store.load(legacyConfig);

        assertEquals(1200.0, store.pendingRefund(player), 0.0001);
        assertEquals("REFUND_PENDING", store.refundReview(player));
        assertFalse(legacyConfig.contains("recovery"));
        assertEquals(100.0, legacyConfig.getDouble("minimum-increment"), 0.0001);

        // Confirm migrated ledger persists across reload.
        LedgerStore reloaded = new LedgerStore(tempDir.toFile(), logger);
        reloaded.load(new YamlConfiguration());
        assertEquals(1200.0, reloaded.pendingRefund(player), 0.0001);
        assertEquals("REFUND_PENDING", reloaded.refundReview(player));
    }
}
