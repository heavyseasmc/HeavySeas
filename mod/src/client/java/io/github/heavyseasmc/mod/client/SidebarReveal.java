package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.state.ExperienceView;
import io.github.heavyseasmc.mod.state.HudView;
import io.github.heavyseasmc.mod.ui.EventReadLedger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 对局界面里的通知侧栏（航海日志）：默认收起，<b>有事自己滑出来几秒再收回</b>，也可以按键钉住（ADR-0037 §7.1 第 4 条）。
 *
 * <p>第一刀只做了「不再常驻」—— 那把屏幕让给了牌（牌宽从约 95 像素长到约 190），
 * 但也让「刚刚发生了什么」在对局界面里彻底看不见了。这一半补上的是后者。
 *
 * <p>❗它是<b>盖在舞台上的一层</b>，不占版面：展开时舞台一个像素都不动。
 * 若让它像主画面那样把舞台挤窄，每来一条播报整屏就要重排一次 —— 那正是 §7.13
 * 要避免的割裂感（「割裂感来自东西挪了位置，不来自东西少了」）。
 *
 * <p>❗钉住那一下不给屏幕上的提示。按键在 Minecraft 的控制设置里能看见（与换主题那个键同一条路数），
 * 而 GUI 上的字不是用来教玩家怎么玩的（用户 2026-09-22）。
 *
 * <p>播报本身是服务端按收件人裁过的事件历史（{@link ExperienceView#events}，每条带只增不减的序号）；
 * 这里只记本机的看法 —— 滑出来没有、翻到哪儿、哪几条读过了，不落盘。
 */
final class SidebarReveal {
    /** 自己滑出来之后停多久（毫秒）。够看清一条播报，又不至于赖在牌上面。 */
    static final long REVEAL_MS = 4000L;
    private static final EventReadLedger READ = new EventReadLedger();
    private static List<ExperienceView.Event> history = List.of();
    private static String match = "";
    private static long newest;
    private static long shownAt;
    private static boolean pinned;
    private static boolean expanded;
    private static int scroll;
    private static int maxScroll;
    private static double scrollRest;
    private static String anchor = "";
    private static boolean restoreAnchor;
    private static List<ExperienceView.Event> lastIncoming;

    private SidebarReveal() { }

    /**
     * 每帧报一次现在的播报。有新到的那一刻起算。
     *
     * <p>❗认的是<b>有没有比见过的更新的序号</b>，不是「侧栏该不该显示」（那每帧都成立，就永远不收回去了），
     * 也不是条数变多（有上限的列表满了之后，新来一条与什么都没来条数一样 —— 证伪表 2026-09-30）。
     */
    static void observe(HudView view, long now) {
        ExperienceView experience = view.experience();
        if (!match.equals(experience.matchId())) {
            forget();
            match = experience.matchId();
        }
        if (lastIncoming == experience.events()) return;
        lastIncoming = experience.events();
        List<ExperienceView.Event> incoming = new ArrayList<>(experience.events());
        incoming.sort(Comparator.comparingLong(ExperienceView.Event::seq));
        if (incoming.equals(history)) return;
        boolean fresh = incoming.stream().anyMatch(e -> e.seq() > newest);
        if (fresh) {
            restoreAnchor = scroll > 0 && !anchor.isEmpty();
            // 已经露出来（滑入中 · 停着 · 收回途中）时不从零再滑一遍，只把停留重新算起（用户 2026-10-07：
            // 「如果关闭倒计时没结束之前有新的日志他又会弹一遍」）；收着的时候照旧滑出来
            boolean showing = !pinned && openness(now) > 0;
            shownAt = showing ? now - GuiLanguage.SLIDE_MS : now;
            newest = incoming.getLast().seq();
        }
        history = List.copyOf(incoming);
        READ.retain(match, history.stream().map(ExperienceView.Event::seq).toList());
    }

    static List<ExperienceView.Event> history() { return history; }
    static int unread() { return READ.unread(); }
    static boolean unread(long id) { return READ.unread(id); }
    static void visible(long id) { READ.visible(id); }
    static void visiblePart(long id, String layout, int part, int total) { READ.visiblePart(id, layout, part, total); }
    static boolean pinned() { return pinned; }
    static boolean expanded() { return pinned && expanded; }
    static int scroll() { return pinned ? scroll : 0; }
    static boolean paused() { return pinned && scroll > 0; }

    /** One scroll step is a text line, so even an unusually long message can be read in full. */
    static void scroll(double amount) {
        scrollRest += amount;
        int steps = (int) scrollRest;
        if (steps == 0) return;
        scrollRest -= steps;
        scroll = Math.max(0, Math.min(maxScroll, scroll + steps));
        restoreAnchor = false;
    }

    /** Preserve the same visible line when new events arrive above a paused reader. */
    static int fitRows(List<String> keys, int visibleRows) {
        maxScroll = Math.max(0, keys.size() - Math.max(1, visibleRows));
        if (restoreAnchor) {
            int index = keys.indexOf(anchor);
            if (index >= 0) scroll = index;
            restoreAnchor = false;
        }
        scroll = Math.max(0, Math.min(maxScroll, scroll));
        int first = scroll();
        anchor = keys.isEmpty() ? "" : keys.get(Math.min(first, keys.size() - 1));
        return first;
    }

    static void togglePin() { pinned = !pinned; toLatest(); }
    static void toggleExpanded() { expanded = !expanded(); if (expanded) pinned = true; }

    static void toLatest() {
        scroll = 0;
        scrollRest = 0;
        anchor = "";
        restoreAnchor = false;
    }

    /**
     * 换了一局（或这一局结束）就把「见过的最新序号」清成 <b>0</b>，不是清成「还没见过」。
     *
     * <p>❗「没有值」与「值是 0」是两种零（证伪表 2026-09-24）：主干上那一版清回「还没见过」时，
     * 新一局那一批开场播报被当成「第一次见到」而不是「新到了几条」，<b>整局第一次什么都不滑出来</b>
     * （实测右侧亮块 0.013，与收着时一模一样）。清成 0，开局那一批的序号都大于它，照样算新到的 —— 开局本来就是「有事发生」。
     */
    static void forget() {
        match = "";
        newest = 0;
        shownAt = 0;
        history = List.of();
        lastIncoming = null;
        READ.clear();
        maxScroll = 0;
        toLatest();
    }

    static float remaining(long now) {
        if (pinned) return 1f;
        if (shownAt <= 0) return 0f;
        return Math.max(0f, Math.min(1f, 1f - (now - shownAt) / (float) REVEAL_MS));
    }

    static float openness(long now) {
        if (pinned) return 1f;
        if (shownAt <= 0) return 0f;
        long since = now - shownAt;
        if (ClientPrefs.reducedMotion()) return since <= REVEAL_MS ? 1f : 0f;
        if (since <= GuiLanguage.SLIDE_MS) return Math.min(1f, GuiLanguage.slide(now, shownAt));
        if (since <= REVEAL_MS) return 1f;
        if (since >= REVEAL_MS + GuiLanguage.SLIDE_MS) return 0f;
        return Math.max(0f, 1f - GuiLanguage.slide(now, shownAt + REVEAL_MS));
    }
}
