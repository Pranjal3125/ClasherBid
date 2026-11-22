package network.clasherbid.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class PluginConfigTest {

    @Test
    void clampsAndValidatesConfiguredValues() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("minimum-increment", -50.0);
        yaml.set("bid-cooldown-seconds", 99999);
        yaml.set("allow-self-outbid", true);
        yaml.set("custom-bid-timeout-seconds", 2);
        yaml.set("quick-bid-increments", List.of(250.0, -10.0, 1000.0, 5000.0, 10000.0));
        yaml.set("anti-sniping.enabled", true);
        yaml.set("anti-sniping.trigger-seconds", -5);
        yaml.set("anti-sniping.extend-seconds", 5000);
        yaml.set("countdown-alerts.enabled", true);
        yaml.set("countdown-alerts.seconds", List.of(30, 5, 60, -1));
        yaml.set("payout-player", "  ServerBank  ");

        PluginConfig config = new PluginConfig(yaml);
        assertEquals(100.0, config.minimumIncrement(), 0.0001);
        assertEquals(3600L, config.bidCooldownSeconds());
        assertTrue(config.allowSelfOutbid());
        assertEquals(5L, config.customBidTimeoutSeconds());
        assertEquals(List.of(250.0, 1000.0, 5000.0), config.quickBidIncrements());
        assertEquals(0L, config.antiSnipingTriggerSeconds());
        assertEquals(3600L, config.antiSnipingExtendSeconds());
        assertEquals(List.of(5L, 30L, 60L), List.copyOf(config.countdownAlertSeconds()));
        assertEquals("ServerBank", config.payoutPlayer());
    }

    @Test
    void defaultsAreSafeWhenConfigIsEmpty() {
        PluginConfig config = new PluginConfig(new YamlConfiguration());
        assertEquals(100.0, config.minimumIncrement(), 0.0001);
        assertEquals(2L, config.bidCooldownSeconds());
        assertFalse(config.allowSelfOutbid());
        assertEquals(30L, config.customBidTimeoutSeconds());
        assertEquals(List.of(500.0, 1000.0, 5000.0), config.quickBidIncrements());
        assertTrue(config.antiSnipingEnabled());
        assertEquals(15L, config.antiSnipingTriggerSeconds());
        assertEquals(30L, config.antiSnipingExtendSeconds());
        assertTrue(config.countdownAlertsEnabled());
        assertEquals(List.of(5L, 10L, 30L, 60L), List.copyOf(config.countdownAlertSeconds()));
        assertTrue(config.soundsEnabled());
        assertEquals("", config.payoutPlayer());
    }
}
