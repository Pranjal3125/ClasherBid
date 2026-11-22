package network.clasherbid.util;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class FormatUtilTest {

    @Test
    void formatDurationHandlesSecondsMinutesAndHours() {
        assertEquals("0s", FormatUtil.formatDuration(0));
        assertEquals("0s", FormatUtil.formatDuration(-5));
        assertEquals("45s", FormatUtil.formatDuration(45));
        assertEquals("1m 05s", FormatUtil.formatDuration(65));
        assertEquals("12m 34s", FormatUtil.formatDuration(754));
        assertEquals("1h 05m 09s", FormatUtil.formatDuration(3909));
    }

    @Test
    void formatMaterialNamePrettifiesEnums() {
        assertEquals("Netherite Sword", FormatUtil.formatMaterialName(Material.NETHERITE_SWORD));
        assertEquals("Diamond", FormatUtil.formatMaterialName(Material.DIAMOND));
        assertEquals("Unknown", FormatUtil.formatMaterialName(null));
    }

    @Test
    void parseAmountAcceptsValidFormatsAndSuffixes() {
        assertEquals(1500.0, FormatUtil.parseAmount("1500").orElseThrow(), 0.0001);
        assertEquals(1500.0, FormatUtil.parseAmount("1,500").orElseThrow(), 0.0001);
        assertEquals(1500.50, FormatUtil.parseAmount("1,500.50").orElseThrow(), 0.0001);
        assertEquals(2500.0, FormatUtil.parseAmount("2.5k").orElseThrow(), 0.0001);
        assertEquals(2500.0, FormatUtil.parseAmount("2.5K").orElseThrow(), 0.0001);
        assertEquals(1_200_000.0, FormatUtil.parseAmount("1.2m").orElseThrow(), 0.0001);
        assertEquals(1_000_000_000.0, FormatUtil.parseAmount("1B").orElseThrow(), 0.0001);
    }

    @Test
    void parseAmountRejectsInvalidInputs() {
        assertTrue(FormatUtil.parseAmount(null).isEmpty());
        assertTrue(FormatUtil.parseAmount("").isEmpty());
        assertTrue(FormatUtil.parseAmount("   ").isEmpty());
        assertTrue(FormatUtil.parseAmount("0").isEmpty());
        assertTrue(FormatUtil.parseAmount("-100").isEmpty());
        assertTrue(FormatUtil.parseAmount("1,00").isEmpty());
        assertTrue(FormatUtil.parseAmount("abc").isEmpty());
        assertTrue(FormatUtil.parseAmount("100x").isEmpty());
        assertTrue(FormatUtil.parseAmount("NaN").isEmpty());
    }
}
