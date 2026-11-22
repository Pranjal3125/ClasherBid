package network.clasherbid.storage;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Persistent, idempotent store for recent auction history (auction-history.yml).
 */
public final class HistoryStore {
    public static final int MAX_ENTRIES = 25;
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final File file;
    private final Logger logger;
    private final List<String> entries = new ArrayList<>();
    private final Set<String> recordedTransactions = new HashSet<>();

    public HistoryStore(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "auction-history.yml");
        this.logger = logger;
    }

    public void load() {
        entries.clear();
        recordedTransactions.clear();
        if (!file.exists()) return;
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(file);
        entries.addAll(saved.getStringList("entries"));
        recordedTransactions.addAll(saved.getStringList("transactions"));
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
    }

    public List<String> entries() {
        return List.copyOf(entries);
    }

    /**
     * Records an auction outcome at most once per transactionId.
     * Returns true if already recorded or durably saved; false on persistence failure.
     */
    public boolean record(String itemLabel, String winner, String formattedAmount, String transactionId) {
        if (transactionId == null || transactionId.isBlank()) return false;
        if (!recordedTransactions.add(transactionId)) return true;

        List<String> previousEntries = new ArrayList<>(entries);
        String entry = itemLabel + " | " + winner + " | " + formattedAmount + " | " + TIMESTAMP_FORMAT.format(Instant.now());
        entries.add(0, entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("entries", entries);
        yaml.set("transactions", new ArrayList<>(recordedTransactions));

        if (!YamlAtomicStorage.saveAtomically(
                yaml,
                file,
                logger,
                "Could not safely save auction history; settlement remains recoverable."
        )) {
            recordedTransactions.remove(transactionId);
            entries.clear();
            entries.addAll(previousEntries);
            return false;
        }
        return true;
    }
}
