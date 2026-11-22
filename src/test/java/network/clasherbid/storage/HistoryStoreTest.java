package network.clasherbid.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

final class HistoryStoreTest {

    @Test
    void recordsIdempotentlyCapsAtMaxEntriesAndPersists(@TempDir Path tempDir) {
        Logger logger = Logger.getLogger("HistoryStoreTest");
        HistoryStore store = new HistoryStore(tempDir.toFile(), logger);
        store.load();

        assertTrue(store.record("Netherite Sword", "Alex", "$1,000", "tx-1"));
        assertTrue(store.record("Netherite Sword", "Alex", "$1,000", "tx-1"));
        assertEquals(1, store.entries().size());

        for (int i = 2; i <= 30; i++) {
            assertTrue(store.record("Item " + i, "Player" + i, "$" + (i * 100), "tx-" + i));
        }
        assertEquals(HistoryStore.MAX_ENTRIES, store.entries().size());
        assertTrue(store.entries().get(0).startsWith("Item 30 | Player30 | $3000"));

        HistoryStore reloaded = new HistoryStore(tempDir.toFile(), logger);
        reloaded.load();
        assertEquals(HistoryStore.MAX_ENTRIES, reloaded.entries().size());
        // Duplicate transaction id stays idempotent across restarts.
        assertTrue(reloaded.record("Item 30", "Player30", "$3000", "tx-30"));
        assertEquals(HistoryStore.MAX_ENTRIES, reloaded.entries().size());
    }
}
