package network.clasherbid.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formatting and parsing helpers for durations, item names, and currency inputs.
 */
public final class FormatUtil {
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "^((?:\\d+|\\d{1,3}(?:,\\d{3})+)(?:\\.\\d+)?)([kmbKMB])?$"
    );

    private FormatUtil() {}

    /**
     * Formats a duration in seconds into a compact human-readable string (e.g. "1h 05m 12s", "12m 34s", "45s").
     */
    public static String formatDuration(long totalSeconds) {
        long clamped = Math.max(0L, totalSeconds);
        long hours = clamped / 3600L;
        long minutes = (clamped % 3600L) / 60L;
        long seconds = clamped % 60L;
        if (hours > 0) {
            return String.format("%dh %02dm %02ds", hours, minutes, seconds);
        }
        if (minutes > 0) {
            return String.format("%dm %02ds", minutes, seconds);
        }
        return seconds + "s";
    }

    /**
     * Converts a Bukkit Material enum name (e.g. NETHERITE_SWORD) into title case (e.g. "Netherite Sword").
     */
    public static String formatMaterialName(Material material) {
        if (material == null) return "Unknown";
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    /**
     * Returns a clean display label for an ItemStack, respecting custom display names and stack counts.
     */
    public static String formatItemName(ItemStack item) {
        if (item == null || item.getType().isAir()) return "Unknown";
        String baseName = null;
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                Component displayName = meta.displayName();
                if (displayName != null) {
                    String plain = PlainTextComponentSerializer.plainText().serialize(displayName).trim();
                    if (!plain.isEmpty()) baseName = plain;
                }
            }
        }
        if (baseName == null) {
            baseName = formatMaterialName(item.getType());
        }
        return item.getAmount() > 1 ? item.getAmount() + "x " + baseName : baseName;
    }

    /**
     * Parses a player-supplied bid amount string.
     * Supports plain numbers ("1500"), thousands separators ("1,500"), decimals ("1500.50"),
     * and compact suffixes ("2.5k", "1m", "1b").
     */
    public static OptionalDouble parseAmount(String raw) {
        if (raw == null) return OptionalDouble.empty();
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return OptionalDouble.empty();
        Matcher matcher = AMOUNT_PATTERN.matcher(trimmed);
        if (!matcher.matches()) return OptionalDouble.empty();

        String numberPart = matcher.group(1).replace(",", "");
        String suffix = matcher.group(2);
        double value;
        try {
            value = Double.parseDouble(numberPart);
        } catch (NumberFormatException ex) {
            return OptionalDouble.empty();
        }
        if (suffix != null) {
            double multiplier = switch (suffix.toLowerCase(Locale.ROOT)) {
                case "k" -> 1_000.0;
                case "m" -> 1_000_000.0;
                case "b" -> 1_000_000_000.0;
                default -> 1.0;
            };
            value *= multiplier;
        }
        if (!Double.isFinite(value) || value <= 0.0) return OptionalDouble.empty();
        return OptionalDouble.of(value);
    }

    /**
     * Disables default italic decoration on GUI item names and lore lines unless explicitly styled.
     */
    public static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
