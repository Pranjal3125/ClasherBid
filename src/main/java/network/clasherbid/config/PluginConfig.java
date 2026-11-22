package network.clasherbid.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * Typed, validated view over config.yml with live reload support.
 */
public final class PluginConfig {
    private static final List<Double> DEFAULT_QUICK_INCREMENTS = List.of(500.0, 1000.0, 5000.0);

    private final JavaPlugin plugin;
    private double minimumIncrement;
    private long bidCooldownSeconds;
    private boolean allowSelfOutbid;
    private long customBidTimeoutSeconds;
    private List<Double> quickBidIncrements = DEFAULT_QUICK_INCREMENTS;
    private boolean antiSnipingEnabled;
    private long antiSnipingTriggerSeconds;
    private long antiSnipingExtendSeconds;
    private boolean antiSnipingBroadcast;
    private boolean countdownAlertsEnabled;
    private NavigableSet<Long> countdownAlertSeconds = Collections.emptyNavigableSet();
    private boolean soundsEnabled;
    private String payoutPlayer = "";

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        loadFrom(plugin.getConfig());
    }

    PluginConfig(FileConfiguration cfg) {
        this.plugin = null;
        loadFrom(cfg);
    }

    public void reload() {
        if (plugin == null) return;
        plugin.reloadConfig();
        loadFrom(plugin.getConfig());
    }

    void loadFrom(FileConfiguration cfg) {
        double minInc = cfg.getDouble("minimum-increment", 100.0);
        this.minimumIncrement = (Double.isFinite(minInc) && minInc > 0.0) ? minInc : 100.0;

        this.bidCooldownSeconds = clamp(cfg.getLong("bid-cooldown-seconds", 2L), 0L, 3600L);
        this.allowSelfOutbid = cfg.getBoolean("allow-self-outbid", false);
        this.customBidTimeoutSeconds = clamp(cfg.getLong("custom-bid-timeout-seconds", 30L), 5L, 120L);

        List<Double> parsedIncrements = new ArrayList<>();
        for (double val : cfg.getDoubleList("quick-bid-increments")) {
            if (Double.isFinite(val) && val > 0.0 && parsedIncrements.size() < 3) {
                parsedIncrements.add(val);
            }
        }
        this.quickBidIncrements = parsedIncrements.isEmpty()
                ? DEFAULT_QUICK_INCREMENTS
                : List.copyOf(parsedIncrements);

        this.antiSnipingEnabled = cfg.getBoolean("anti-sniping.enabled", true);
        this.antiSnipingTriggerSeconds = clamp(cfg.getLong("anti-sniping.trigger-seconds", 15L), 0L, 3600L);
        this.antiSnipingExtendSeconds = clamp(cfg.getLong("anti-sniping.extend-seconds", 30L), 0L, 3600L);
        this.antiSnipingBroadcast = cfg.getBoolean("anti-sniping.broadcast", true);

        this.countdownAlertsEnabled = cfg.getBoolean("countdown-alerts.enabled", true);
        TreeSet<Long> alerts = new TreeSet<>();
        for (long sec : cfg.getLongList("countdown-alerts.seconds")) {
            if (sec > 0L && sec <= 3600L) alerts.add(sec);
        }
        if (alerts.isEmpty() && !cfg.contains("countdown-alerts.seconds")) {
            alerts.addAll(List.of(60L, 30L, 10L, 5L));
        }
        this.countdownAlertSeconds = Collections.unmodifiableNavigableSet(alerts);

        this.soundsEnabled = cfg.getBoolean("sounds.enabled", true);

        String rawPayout = cfg.getString("payout-player", "");
        this.payoutPlayer = rawPayout == null ? "" : rawPayout.trim();
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    public double minimumIncrement() { return minimumIncrement; }
    public long bidCooldownSeconds() { return bidCooldownSeconds; }
    public boolean allowSelfOutbid() { return allowSelfOutbid; }
    public long customBidTimeoutSeconds() { return customBidTimeoutSeconds; }
    public List<Double> quickBidIncrements() { return quickBidIncrements; }
    public boolean antiSnipingEnabled() { return antiSnipingEnabled; }
    public long antiSnipingTriggerSeconds() { return antiSnipingTriggerSeconds; }
    public long antiSnipingExtendSeconds() { return antiSnipingExtendSeconds; }
    public boolean antiSnipingBroadcast() { return antiSnipingBroadcast; }
    public boolean countdownAlertsEnabled() { return countdownAlertsEnabled; }
    public NavigableSet<Long> countdownAlertSeconds() { return countdownAlertSeconds; }
    public boolean soundsEnabled() { return soundsEnabled; }
    public String payoutPlayer() { return payoutPlayer; }
}
