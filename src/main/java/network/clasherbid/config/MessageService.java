package network.clasherbid.config;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * MiniMessage-based localization, broadcast, and audio feedback service backed by messages.yml.
 */
public final class MessageService {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final File file;
    private YamlConfiguration messages = new YamlConfiguration();
    private YamlConfiguration defaults = new YamlConfiguration();

    public MessageService(JavaPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.file = new File(plugin.getDataFolder(), "messages.yml");
        reload();
    }

    public void reload() {
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        this.messages = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                this.defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                this.messages.setDefaults(this.defaults);
            }
        } catch (Exception ignored) {
            // Defaults remain empty if bundled resource cannot be read.
        }
    }

    public Component parse(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize(miniMessage, resolvers);
    }

    public Component render(String key, TagResolver... resolvers) {
        String raw = messages.getString(key);
        if (raw == null) raw = defaults.getString(key, "<red>Missing message: " + key + "</red>");
        String prefixRaw = messages.getString("prefix", defaults.getString("prefix", "<gold><bold>CLASHERBID</bold></gold> <dark_gray>»</dark_gray> "));
        TagResolver prefixResolver = Placeholder.parsed("prefix", prefixRaw);
        TagResolver combined = TagResolver.builder()
                .resolver(prefixResolver)
                .resolvers(resolvers)
                .build();
        return MM.deserialize(raw, combined);
    }

    public void send(Audience audience, String key, TagResolver... resolvers) {
        if (audience == null) return;
        audience.sendMessage(render(key, resolvers));
    }

    public void sendRaw(Audience audience, String miniMessage, TagResolver... resolvers) {
        if (audience == null) return;
        audience.sendMessage(parse(miniMessage, resolvers));
    }

    public void broadcast(String key, TagResolver... resolvers) {
        Bukkit.broadcast(render(key, resolvers));
    }

    public void broadcastRaw(String miniMessage, TagResolver... resolvers) {
        Bukkit.broadcast(parse(miniMessage, resolvers));
    }

    public void playSound(Player player, Sound sound, float volume, float pitch) {
        if (player == null || sound == null || !config.soundsEnabled()) return;
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    public void playSoundAll(Sound sound, float volume, float pitch) {
        if (sound == null || !config.soundsEnabled()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.playSound(player.getLocation(), sound, volume, pitch);
        }
    }
}
