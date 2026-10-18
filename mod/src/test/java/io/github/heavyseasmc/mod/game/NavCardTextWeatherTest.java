package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.mod.state.NavCardView;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 航海牌预告按天候改写（ADR-0095 A5）：与引擎 {@code Session#beginNavigation} / {@code prepareThirst} 一一对应。
 * 原先只照牌面写 —— 暴风雨天带桨的牌会把划过船的人送下水，界面上却写「口渴」。
 */
class NavCardTextWeatherTest {

    private static final NavCardView.Names NOBODY = new NavCardView.Names(NavCardView.Mode.NOBODY, List.of(), "");
    private static final NavCardView.Names CAPTAIN = new NavCardView.Names(NavCardView.Mode.ONLY, List.of("captain"), "");

    /** 一张有海鸥、点名船长口渴、带桨和打架两个图示、谁都不落海的牌。 */
    private static final NavCardView CARD = new NavCardView("nav_test", 1, NOBODY, CAPTAIN, true, true);

    @Test
    @DisplayName("没有天候：照牌面写 —— 海鸥 +1，划过船的人与打过架的人都在口渴那一栏，落海一栏无人")
    void noWeather() {
        Text t = NavCardText.describe(CARD, List.of("captain"), "");
        assertTrue(keys(t).contains("heavyseas.nav.gull_plus"));
        assertTrue(lineKeys(t, "heavyseas.nav.thirst_line").containsAll(Set.of("heavyseas.nav.rowers", "heavyseas.nav.fighters")));
        assertFalse(lineKeys(t, "heavyseas.nav.overboard_line").contains("heavyseas.nav.rowers"));
        // 旧签名就是「没有天候」
        assertEquals(keys(t), keys(NavCardText.describe(CARD, List.of("captain"))));
    }

    @Test
    @DisplayName("暴风雨（rowers_overboard）：划过船的人从口渴挪到落海")
    void stormSendsRowersOverboard() {
        Text t = NavCardText.describe(CARD, List.of("captain"), "rowers_overboard");
        assertTrue(lineKeys(t, "heavyseas.nav.overboard_line").contains("heavyseas.nav.rowers"));
        assertFalse(lineKeys(t, "heavyseas.nav.thirst_line").contains("heavyseas.nav.rowers"));
        assertTrue(lineKeys(t, "heavyseas.nav.thirst_line").contains("heavyseas.nav.fighters"), "打过架的人照旧口渴");
        assertFalse(lineKeys(t, "heavyseas.nav.overboard_line").contains("heavyseas.nav.nobody"),
                "落海那一栏有人了，不该再写「无人」");
    }

    @Test
    @DisplayName("巨浪（fighters_overboard）：打过架的人从口渴挪到落海")
    void bigWavesSendFightersOverboard() {
        Text t = NavCardText.describe(CARD, List.of("captain"), "fighters_overboard");
        assertTrue(lineKeys(t, "heavyseas.nav.overboard_line").contains("heavyseas.nav.fighters"));
        assertFalse(lineKeys(t, "heavyseas.nav.thirst_line").contains("heavyseas.nav.fighters"));
    }

    @Test
    @DisplayName("暴风雨，但牌上没有桨图示：什么都不挪")
    void stormWithoutRowerIconChangesNothing() {
        NavCardView plain = new NavCardView("nav_plain", 0, NOBODY, CAPTAIN, false, false);
        assertEquals(keys(NavCardText.describe(plain, List.of("captain"), "")),
                keys(NavCardText.describe(plain, List.of("captain"), "rowers_overboard")));
    }

    @Test
    @DisplayName("浓雾（ignore_gulls）：海鸥那一栏不写")
    void fogDropsGulls() {
        assertFalse(keys(NavCardText.describe(CARD, List.of("captain"), "ignore_gulls")).contains("heavyseas.nav.gull_plus"));
    }

    @Test
    @DisplayName("无渴（ignore_thirst）：口渴那一栏只写「无人」；全渴（all_thirst）：先写「全员」")
    void thirstWeathers() {
        Set<String> none = lineKeys(NavCardText.describe(CARD, List.of("captain"), "ignore_thirst"), "heavyseas.nav.thirst_line");
        assertEquals(Set.of("heavyseas.nav.nobody"), none);
        Set<String> all = lineKeys(NavCardText.describe(CARD, List.of("captain"), "all_thirst"), "heavyseas.nav.thirst_line");
        assertTrue(all.contains("heavyseas.nav.everyone"));
    }

    /** 整棵文本里出现过的翻译键。 */
    private static Set<String> keys(Text text) {
        Set<String> out = new HashSet<>();
        collect(text, out);
        return out;
    }

    /** 某一栏（overboard_line / thirst_line）里出现过的翻译键，不含那一栏自己的键。 */
    private static Set<String> lineKeys(Text text, String lineKey) {
        Text line = find(text, lineKey);
        assertTrue(line != null, "整行里没找到「" + lineKey + "」那一栏 —— 没在查，不是通过");
        Set<String> out = new HashSet<>();
        for (Object arg : ((TranslatableTextContent) line.getContent()).getArgs()) {
            if (arg instanceof Text t) {
                collect(t, out);
            }
        }
        return out;
    }

    private static Text find(Text text, String key) {
        if (text.getContent() instanceof TranslatableTextContent tr && tr.getKey().equals(key)) {
            return text;
        }
        for (Text sibling : text.getSiblings()) {
            Text hit = find(sibling, key);
            if (hit != null) {
                return hit;
            }
        }
        if (text.getContent() instanceof TranslatableTextContent tr) {
            for (Object arg : tr.getArgs()) {
                if (arg instanceof Text t) {
                    Text hit = find(t, key);
                    if (hit != null) {
                        return hit;
                    }
                }
            }
        }
        return null;
    }

    private static void collect(Text text, Set<String> out) {
        if (text.getContent() instanceof TranslatableTextContent tr) {
            out.add(tr.getKey());
            for (Object arg : tr.getArgs()) {
                if (arg instanceof Text t) {
                    collect(t, out);
                }
            }
        }
        for (Text sibling : text.getSiblings()) {
            collect(sibling, out);
        }
    }
}
