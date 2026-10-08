package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.ui.NotificationArrivals;
import net.minecraft.text.Text;

import java.util.List;

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

    /**
     * 上一帧见到的播报序号（{@link io.github.heavyseasmc.mod.state.HudView#notificationSeq}）。
     * {@code -1} = 还没见过，第一帧不算「新到了几条」；{@link #forget} 之后是 0 —— 新一局开场那一批要算新到的
     * （「没有值」与「值是 0」是两种零，证伪表 2026-09-24）。
     */
    private static long lastSeq = -1;
    private static java.util.UUID epoch;
    /** 这一局一共新到过几条 —— 只增不减；日志页签上的未读数拿它减去「展开到底时看到第几条」。 */
    private static int arrived = 0;
    /** 最近一次到了几条（主画面日志里「刚刚」那一批，样张 b-2）。 */
    private static int lastBatch = 0;
    private static long shownAt = 0L;
    private static boolean pinned;

    /**
     * 这一局客户端自己记下的全部播报（旧的在前）。服务端的投影只带最新十六条，往回翻要靠这一本
     * （用户 2026-10-07：「航海日志不能滚动」）。每次新到几条就把投影末尾那几条接上来（{@link NotificationArrivals}）。
     */
    private static final java.util.ArrayList<Text> history = new java.util.ArrayList<>();
    /** 一局最多记多少条：够翻一整局，又不至于让一局打很久时无限长。 */
    private static final int HISTORY_MAX = 400;
    /** 往回翻了几条（0 = 看的是最新的）。只在钉住时作数，取消钉住就回到最新。 */
    private static int scroll;
    /** 滚轮的零头：触控板一次只给零点几格，攒满一格再翻。 */
    private static double scrollRest;

    private SidebarReveal() {
    }

    /**
     * 每帧报一次现在的播报。有新到的那一刻起算。
     *
     * <p>❗数的是**新到了几条**，不是「侧栏该不该显示」：后者每帧都成立，那样它就永远不收回去了。
     * ❗也不是**条数变多**、也不比内容：按服务端给的序号数（{@link NotificationArrivals}，审查 U13）。
     */
    static boolean observe(java.util.UUID incomingEpoch, List<Text> notes, long seq, long now) {
        boolean newGame = !java.util.Objects.equals(epoch, incomingEpoch);
        if (newGame) {
            forget();
            epoch = incomingEpoch;
        }
        int n = 0;
        if (lastSeq >= 0) {
            n = NotificationArrivals.count(lastSeq, seq, notes.size());
            if (n > 0) {
                // 已经露出来（滑入中 · 停着 · 收回途中）时不从零再滑一遍，只把停留重新算起（用户 2026-10-07：
                // 「如果关闭倒计时没结束之前有新的日志他又会弹一遍」）；收着的时候照旧滑出来
                boolean showing = !pinned && openness(now) > 0f;
                shownAt = showing ? now - GuiLanguage.SLIDE_MS : now;
                arrived += n;
                lastBatch = n;
            }
        }
        // 记账与「新到了几条」分开：❗上面那一段的行为一个字不改（forget 之后开局那一批照样算新到的，见 forget 的注释）
        if (history.isEmpty()) {
            history.addAll(notes);            // 第一次见到 / 新一局：投影里有的整批记下
        } else if (n > 0) {
            history.addAll(notes.subList(Math.max(0, notes.size() - n), notes.size()));
            while (history.size() > HISTORY_MAX) {
                history.remove(0);
            }
            if (scroll > 0) {
                scroll += n;                  // 正在往回翻：新来的接在上面，翻到的那几条别跟着动
            }
        }
        lastSeq = seq;
        return newGame;
    }

    /** 这一局记下的全部播报（旧的在前）。还没见过任何播报时是空的 —— 调用方退回投影里的那几条。 */
    static List<Text> history() {
        return history;
    }

    /** 往回翻了几条。 */
    static int scroll() {
        return pinned ? scroll : 0;
    }

    /**
     * 滚轮翻日志：往上滚（正数）翻到更早的，往下滚回到新的。只在钉住时调：界面里的滚轮（{@code GameScreen#mouseScrolled}）· 世界里的 ↑ ↓（用户 2026-10-07：世界里滚轮留给指人换人）。
     * 最多翻到只剩最早那一条在最上面；具体一屏放几条由画的那一侧决定，这里只管不越过两头。
     */
    static void scroll(double amount) {
        scrollRest += amount;
        int steps = (int) scrollRest;
        if (steps == 0) {
            return;
        }
        scrollRest -= steps;
        scroll = Math.max(0, Math.min(Math.max(0, history.size() - 1), scroll + steps));
    }

    /** 这一局一共新到过几条。 */
    static int arrived() {
        return arrived;
    }

    /** 最近一批到了几条：日志最上面这几条标「刚刚」、不压淡（样张 b-2）。 */
    static int lastBatch() {
        return lastBatch;
    }

    /**
     * 自己露出来那一段还剩多少（0–1）：样张 b-2 日志底下那道「自动收回」的短横按它缩。
     * 钉住时没有这一说，返回 1。
     */
    static float remaining(long now) {
        if (pinned) {
            return 1f;
        }
        if (shownAt <= 0L) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, 1f - (now - shownAt) / (float) REVEAL_MS));
    }

    /**
     * 换了一局（或这一局结束）就把序号清成 <b>0</b>（新一局从 0 数起），不是清回「还没见过」（-1）。
     *
     * <p>❗清回「还没见过」的话，新一局那一批开场播报会被当成「第一次见到」而不是「新到了几条」，
     * 于是**整局第一次什么都不滑出来**（2026-09-24 实测：右侧亮块 0.013，与收着时一模一样）。
     * 清成 0 才对：开局本来就是「有事发生」。
     */
    static void forget() {
        epoch = null;
        lastSeq = 0;
        arrived = 0;
        lastBatch = 0;
        shownAt = 0L;
        history.clear();
        scroll = 0;
        scrollRest = 0;
    }

    static boolean pinned() {
        return pinned;
    }

    /**
     * 钉住 / 取消钉住。钉住时它一直在，不再自己收回去。
     *
     * <p>收起时<b>记着</b>拉伸没有：再按 L 钉上还是拉伸的样子（用户 2026-10-07：「日志放大后 L 收起再展开，就取消放大了」）。
     * 拉伸只在钉住时作数（{@link #expanded()}），所以收着的时候不会有一条拉伸的空栏挂在那儿；要回到原宽按 Shift + L。
     */
    static void togglePin() {
        pinned = !pinned;
        scroll = 0;                           // 再钉上时从最新的看起
        scrollRest = 0;
    }

    /** 往回翻过（暂停在某一处）：新到的播报不挪动这一屏，直到按键回到最新（用户 2026-10-07）。 */
    static boolean paused() {
        return pinned && scroll > 0;
    }

    /** 回到最新的那一条（世界里与界面里都是 End）。 */
    static void toLatest() {
        scroll = 0;
        scrollRest = 0;
    }

    /**
     * 拉伸：日志占满整条右栏，天候卡与说明签收起（用户 2026-10-07：「放大不是全屏放大，而是拉升到整个右侧，其他元素压缩」）。
     * Shift + L 切换；拉伸时一定钉着。
     */
    private static boolean expanded;

    static boolean expanded() {
        return pinned && expanded;
    }

    static void toggleExpanded() {
        expanded = !expanded();
        if (expanded) {
            pinned = true;
        }
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
