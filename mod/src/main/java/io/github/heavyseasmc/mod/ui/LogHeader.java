package io.github.heavyseasmc.mod.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.ToIntFunction;

/**
 * 航海日志栏头右边那串小字（「未读 N · End 最新」）写什么、放得下多少。
 *
 * <p>界面里钉住时日志那一栏收窄了（{@link PaneBudget#logWidth}，1280×720 约 245 物理像素）：标题、图钉、日志键之后只剩八十来个像素，
 * 整句排不下，实拍被截成「未读 3 · End…」—— 半截按键提示比不写更糟。<b>窄栏里只写「未读 N」</b>，按键提示不放；
 * 世界视图那一列宽，照旧写整句（2026-10-13 验收）。
 *
 * <p>纯逻辑：字由调用方翻译、字宽由调用方量（客户端按字号梯子量，单测拿字体文件量），这里不碰字体。
 */
public final class LogHeader {

    private LogHeader() {
    }

    /** 标题右边空几（稿子像素，与日志栏头的画法同一套数）。 */
    public static final double TITLE_GAP = 10;
    /** 图钉左边空几。 */
    public static final double PIN_GAP = 6;
    /** 日志键与图钉之间空几。 */
    public static final double KEY_GAP = 5;
    /** 未读最多写到几（与收起时那枚小圆片同一个上限）。 */
    public static final int UNREAD_MAX = 99;

    /**
     * 栏头那串小字可用多宽（物理像素）：栏宽减去两侧内边距、日志键、图钉、标题与它们之间的空。
     *
     * @param logW   日志那一栏的宽
     * @param keyW   日志键那枚键帽的宽
     * @param titleW 「航海日志」那几个字画出来多宽
     */
    public static int statusRoom(HudLayout l, int logW, int keyW, int titleW) {
        return logW - 2 * l.len(HudLayout.LOG_PAD_X) - keyW - l.len(KEY_GAP) - l.len(HudLayout.ICON) - l.len(PIN_GAP)
                - titleW - l.len(TITLE_GAP);
    }

    /**
     * 这一帧栏头写哪一句。
     *
     * @param narrow    界面里让出来的那一栏（窄）：不带按键提示；世界视图那一列（宽）先试整句
     * @param paused    往回翻着（不在最新那一条）
     * @param latestKey 「回到最新」那个键的名字
     * @param translate 翻译：键 + 参数 → 一句
     * @param width     一句画出来多宽（物理像素）
     */
    public static String status(boolean narrow, boolean paused, int unread, String latestKey, int room,
                                BiFunction<String, Object[], String> translate, ToIntFunction<String> width) {
        int n = Math.min(Math.max(0, unread), UNREAD_MAX);
        List<String> c = new ArrayList<>();
        if (!narrow) {
            c.add(translate.apply(paused ? "heavyseas.hud.log.unread_paused" : "heavyseas.hud.log.unread",
                    new Object[]{n, latestKey}));
        }
        if (paused) {
            c.add(translate.apply("heavyseas.hud.log.unread_brief_paused", new Object[]{n}));
        }
        c.add(translate.apply("heavyseas.hud.log.unread_brief", new Object[]{n}));
        c.add(Integer.toString(n));                // 最后一招：只剩数字（4K 下英文标题占满时）
        return firstThatFits(c, room, width);
    }

    /** 从长到短挑第一句放得下的；一句都放不下就给最短的那句（画的时候再截）。 */
    static String firstThatFits(List<String> candidates, int room, ToIntFunction<String> width) {
        for (String s : candidates) {
            if (width.applyAsInt(s) <= room) {
                return s;
            }
        }
        return candidates.get(candidates.size() - 1);
    }
}
