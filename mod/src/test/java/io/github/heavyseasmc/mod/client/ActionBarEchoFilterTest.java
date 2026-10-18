package io.github.heavyseasmc.mod.client;

import net.minecraft.text.Text;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动作栏搬到界面上层的那一句，哪些不搬（2026-10-13 验收）：
 * L01「Press Left Shift to Dismount」压在补给箱说明签上；C05「轮到你了 · 按 G 行动」压在已经开着的行动一面上。
 * 以及 ADR-0100 的两路：按键提示去右下角，其余（拒绝）留在正中。
 */
class ActionBarEchoFilterTest {

    @Test
    void dismountHintIsNotEchoedOverOurScreens() {
        assertTrue(ActionBarEcho.ignored(Text.translatable("mount.onboard", Text.literal("Left Shift"))));
        // 对照：服务端的拒绝理由照样搬上来
        assertFalse(ActionBarEcho.ignored(Text.translatable("heavyseas.hand.not_your_turn")));
        assertFalse(ActionBarEcho.ignored(Text.literal("mount.onboard")));
    }

    @Test
    void turnBannerIsNotDrawnOnTheActionFaceItself() {
        Text banner = Text.translatable("heavyseas.hud.turn_banner", Text.literal("G"));
        assertTrue(ActionBarEcho.hiddenOn(banner, true));
        assertFalse(ActionBarEcho.hiddenOn(banner, false), "开着手牌等别的面时这一句要看得见");
        assertFalse(ActionBarEcho.hiddenOn(Text.translatable("heavyseas.hand.not_your_turn"), true));
    }

    @Test
    @DisplayName("两路：脱离与轮到你走右下角；分原因的拒绝句（O46 ux.reject.*）与没登记的一律留在正中")
    void routesSplitByTranslationKey() {
        assertEquals(ActionBarEcho.Route.CORNER, ActionBarEcho.route(Text.translatable("mount.onboard", Text.literal("左Shift"))));
        assertEquals(ActionBarEcho.Route.CORNER, ActionBarEcho.route(Text.translatable("heavyseas.hud.turn_banner", "G")));
        for (String reason : new String[]{"ended", "cannot_act", "card_changed", "not_your_turn", "busy", "pick_locked",
                "already_used", "nobody_wounded", "no_corpse", "already_open", "wrong_timing", "no_target", "window_closed"}) {
            assertEquals(ActionBarEcho.Route.REJECT, ActionBarEcho.route(Text.translatable("heavyseas.ux.reject." + reason)),
                    reason + " 是拒绝，要留在正中");
        }
        // 没登记的新来源、字面量（北辰号房间名那种）：默认落在显眼的那一边
        assertEquals(ActionBarEcho.Route.REJECT, ActionBarEcho.route(Text.translatable("heavyseas.something.new")));
        assertEquals(ActionBarEcho.Route.REJECT, ActionBarEcho.route(Text.literal("mount.onboard")));
    }

    /** 2026-10-13 验收 05_g · L01：一面开着时，世界那一层把这两句画在压暗之下、面板外的深色底上，几乎看不见。 */
    @Test
    @DisplayName("世界那一层：对局的一面开着时右下角那两句一律不画（交给界面那一层），没开时照画")
    void worldLayerStepsAsideWhileAScreenIsOpen() {
        Text banner = Text.translatable("heavyseas.hud.turn_banner", Text.literal("G"));
        Text dismount = Text.translatable("mount.onboard", Text.literal("Left Shift"));
        assertEquals(ActionBarEcho.WorldLayer.NONE, ActionBarEcho.worldLayer(banner, true, true), "一面开着时还画在压暗底下");
        assertEquals(ActionBarEcho.WorldLayer.NONE, ActionBarEcho.worldLayer(dismount, true, true));
        assertEquals(ActionBarEcho.WorldLayer.CORNER, ActionBarEcho.worldLayer(banner, true, false));
        // 不归我们的（北辰号房间名）与不在对局里：Minecraft 自己那一行照画
        assertEquals(ActionBarEcho.WorldLayer.VANILLA, ActionBarEcho.worldLayer(Text.literal("Grand Saloon"), true, true));
        assertEquals(ActionBarEcho.WorldLayer.VANILLA, ActionBarEcho.worldLayer(banner, false, true));
    }

    @Test
    @DisplayName("键名从句子里拆出来画成键帽：只认恰好一个占位")
    void splitsTheKeyOut() {
        assertArrayEquals(new String[]{"按下", "左Shift", "来脱离"}, ActionBarEcho.splitAroundKey("按下%1$s来脱离", "左Shift"));
        assertArrayEquals(new String[]{"Press", "Left Shift", "to Dismount"},
                ActionBarEcho.splitAroundKey("Press %1$s to Dismount", "Left Shift"));
        assertArrayEquals(new String[]{"轮到你了 · 按", "G", "行动"}, ActionBarEcho.splitAroundKey("轮到你了 · 按 %s 行动", "G"));
        assertNull(ActionBarEcho.splitAroundKey("没有占位", "G"));
        assertNull(ActionBarEcho.splitAroundKey("%s 与 %s", "G"), "两个占位：不知道哪个是键名，整句照字画");
        assertNull(ActionBarEcho.splitAroundKey("按 %s", ""));
    }

    @Test
    @DisplayName("键帽亮三下后停住；少动效时不亮；到时淡出")
    void glowsThreeTimesThenHolds() {
        int peaks = 0;
        float prev = 0f;
        boolean rising = false;
        for (long t = 0; t <= ActionBarEcho.SHOW_MS; t += 10) {
            float g = ActionBarEcho.glow(t, false);
            if (g < prev && rising) {
                peaks++;
            }
            rising = g > prev;
            prev = g;
        }
        assertEquals(ActionBarEcho.GLOW_PULSES, peaks, "亮的次数");
        assertEquals(0f, ActionBarEcho.glow(ActionBarEcho.GLOW_PULSES * ActionBarEcho.GLOW_PULSE_MS + 50, false), "之后停住");
        assertEquals(0f, ActionBarEcho.glow(ActionBarEcho.GLOW_PULSE_MS / 2, true), "少动效时不亮");
        assertEquals(1f, ActionBarEcho.fade(ActionBarEcho.SHOW_MS - ActionBarEcho.FADE_MS));
        assertTrue(ActionBarEcho.fade(ActionBarEcho.SHOW_MS - ActionBarEcho.FADE_MS / 2) < 1f);
        assertEquals(0f, ActionBarEcho.fade(ActionBarEcho.SHOW_MS + 1));
        assertEquals(0.0, ActionBarEcho.shake(ActionBarEcho.SHAKE_MS, false));
        assertEquals(0.0, ActionBarEcho.shake(20, true), "少动效时不抖");
        assertTrue(Math.abs(ActionBarEcho.shake(18, false)) > 0.5, "出现那一下确实在抖");
    }
}
