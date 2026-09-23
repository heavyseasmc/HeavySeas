package io.github.heavyseasmc.mod.client;

/**
 * 对局界面里的通知侧栏：默认收起，**有事自己滑出来几秒再收回**，也可以按键钉住（ADR-0037 §7.1 第 4 条）。
 *
 * <p>第一刀只做了「不再常驻」—— 那把屏幕让给了牌（牌宽从约 95 像素长到约 190），
 * 但也让「刚刚发生了什么」在对局界面里彻底看不见了。这一半补上的是后者。
 *
 * <p>❗它是**盖在舞台上的一层**，不占版面：展开时舞台一个像素都不动。
 * 若让它像主画面那样把舞台挤窄，每来一条播报整屏就要重排一次 —— 那正是 §7.13
 * 要避免的割裂感（「割裂感来自东西挪了位置，不来自东西少了」）。
 *
 * <p>❗钉住那一下不给屏幕上的提示。按键在 Minecraft 的控制设置里能看见（与换主题那个键同一条路数），
 * 而 GUI 上的字不是用来教玩家怎么玩的（用户 2026-09-22）。
 */
final class SidebarReveal {

    /** 自己滑出来之后停多久（毫秒）。够看清一条播报，又不至于赖在牌上面。 */
    static final long REVEAL_MS = 4000L;

    /** 上一帧见到几条播报。-1 = 还没见过，第一帧不算「多了一条」。 */
    private static int lastCount = -1;
    private static long shownAt = 0L;
    private static boolean pinned;

    private SidebarReveal() {
    }

    /**
     * 每帧报一次现在有几条播报。多出来的那一刻起算。
     *
     * <p>❗数的是**条数变多**，不是「侧栏该不该显示」：后者每帧都成立，那样它就永远不收回去了。
     */
    static void observe(int count, long now) {
        if (lastCount >= 0 && count > lastCount) {
            shownAt = now;
        }
        lastCount = count;
    }

    /**
     * 换了一局（或这一局结束）就把条数清回 **0**，不是清回「还没见过」。
     *
     * <p>❗清回 -1 的话，新一局那一批开场播报会被当成「第一次见到」而不是「多了几条」，
     * 于是**整局第一次什么都不滑出来**（2026-09-24 实测：右侧亮块 0.013，与收着时一模一样）。
     * 清回 0 才对：开局本来就是「有事发生」。
     */
    static void forget() {
        lastCount = 0;
        shownAt = 0L;
    }

    static boolean pinned() {
        return pinned;
    }

    /** 钉住 / 取消钉住。钉住时它一直在，不再自己收回去。 */
    static void togglePin() {
        pinned = !pinned;
    }

    /**
     * 现在展开到几分？0 = 完全收起，1 = 完全展开。
     *
     * <p>进场与退场都走「滑」这个动词（550ms，带轻微过冲）—— 与别处的滑是同一件事，
     * 不另起一套曲线。
     */
    static float openness(long now) {
        if (pinned) {
            return 1f;
        }
        if (shownAt <= 0L) {
            return 0f;
        }
        long since = now - shownAt;
        if (since <= GuiLanguage.SLIDE_MS) {
            return Math.min(1f, GuiLanguage.slide(now, shownAt));
        }
        if (since <= REVEAL_MS) {
            return 1f;
        }
        long out = since - REVEAL_MS;
        if (out >= GuiLanguage.SLIDE_MS) {
            return 0f;
        }
        return Math.max(0f, 1f - GuiLanguage.slide(now, shownAt + REVEAL_MS));
    }
}
