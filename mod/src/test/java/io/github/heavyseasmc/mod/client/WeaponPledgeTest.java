package io.github.heavyseasmc.mod.client;

import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 押武器那两行（2026-10-13 验收 A08：「你已押 1 张 · 武器加值 +4」下面写着「还没押」）。 */
class WeaponPledgeTest {

    private static String key(Text t) {
        return ((TranslatableTextContent) t.getContent()).getKey();
    }

    @Test
    void countAndDetailComeFromTheSameList() {
        Text[] lines = WeaponScreen.pledgeLines(List.of("harpoon", "oar", "oar"), 7, Text::literal);
        assertEquals("heavyseas.weapon.private_total", key(lines[0]));
        assertArrayEquals(new Object[]{3, 7}, ((TranslatableTextContent) lines[0].getContent()).getArgs());
        assertNotNull(lines[1]);
        assertEquals("harpoon ×1 · oar ×2", lines[1].getString());
    }

    @Test
    void nothingCommittedSaysSoOnceAndNeverNextToACount() {
        Text[] lines = WeaponScreen.pledgeLines(List.of(), 0, Text::literal);
        assertEquals("heavyseas.weapon.none", key(lines[0]));
        assertNull(lines[1], "没押时不该另有一行张数或明细");
    }
}
