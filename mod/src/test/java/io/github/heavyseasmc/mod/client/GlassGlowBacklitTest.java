package io.github.heavyseasmc.mod.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 窗后藏灯（方案 A10）：朝屋里那一面什么时候亮。 */
class GlassGlowBacklitTest {

    @Test
    @DisplayName("窗后空隙里挂着灯：背后比屋里亮 → 亮")
    void lampBehindLights() {
        assertTrue(GlassGlow.backlit(true, 14, 10, true));
    }

    @Test
    @DisplayName("背后的光是屋里漏过去的（比屋里暗一级）→ 不亮")
    void leakedLightDoesNot() {
        assertFalse(GlassGlow.backlit(true, 11, 12, true));
        assertFalse(GlassGlow.backlit(true, 12, 12, true));
    }

    @Test
    @DisplayName("背后是方块（舷窗、船壳）或暗着 → 不亮")
    void solidOrDarkBehind() {
        assertFalse(GlassGlow.backlit(false, 14, 3, true));
        assertFalse(GlassGlow.backlit(true, GlassGlow.THRESHOLD - 1, 0, true));
    }
    @Test
    @DisplayName("背后是有灯的散步廊（不是通到船壳的窄空隙）→ 不亮（2026-10-19 用户实拍「有的发黄，有的不会」）")
    void litPromenadeBehindDoesNot() {
        assertFalse(GlassGlow.backlit(true, 14, 10, false));
    }
}
