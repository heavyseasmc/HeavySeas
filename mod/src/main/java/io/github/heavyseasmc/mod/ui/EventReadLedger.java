package io.github.heavyseasmc.mod.ui;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

/**
 * Read state uses received event identities, never sequence subtraction: private events leave gaps.
 *
 * <p>一条算读过：它整条露过面（长的一条要每一行都露过），<b>或者</b>比它新的某一条已经整条露过面 —— 看到了最新的就是追上了。
 * 2026-10-13 总截图：开局那一批是在补给箱开着、日志收着时到的，钉住时已被新播报压到栏底以下，栏头「未读 8」从开局挂到终局，
 * 新播报一来就在栏顶被看见、数字一次都不动。只按「每条各自露没露过面」数，旧的那几条永远挂着，数字就不再说「有新的」。
 * 往回翻着（不在最新）时新来的照样算未读：那时露面的都比它们旧。
 */
public final class EventReadLedger {
    private final Set<Long> received = new HashSet<>();
    private final Set<Long> read = new HashSet<>();
    private final Map<Long, Set<Integer>> parts = new HashMap<>();
    private final Map<Long, Integer> partCounts = new HashMap<>();
    private final Map<Long, String> layouts = new HashMap<>();
    private String match = "";
    /** 整条露过面的最新那一条的序号；不大于它的都算追上了。 */
    private long caughtUp = Long.MIN_VALUE;

    public void retain(String matchId, Collection<Long> identities) {
        if (!match.equals(matchId)) {
            clear();
            match = matchId;
        }
        received.clear();
        received.addAll(identities);
        read.retainAll(received);
        parts.keySet().retainAll(received);
        partCounts.keySet().retainAll(received);
        layouts.keySet().retainAll(received);
    }

    public void visible(long id) {
        if (received.contains(id)) {
            read.add(id);
            caughtUp = Math.max(caughtUp, id);
        }
    }

    /** Long events can be read across successive scroll positions without marking hidden lines. */
    public void visiblePart(long id, int part, int total) {
        visiblePart(id, Integer.toString(total), part, total);
    }

    public void visiblePart(long id, String layout, int part, int total) {
        if (!received.contains(id) || total <= 0 || part < 0 || part >= total) return;
        if (partCounts.getOrDefault(id, -1) != total || !layout.equals(layouts.get(id))) {
            parts.remove(id);
            partCounts.put(id, total);
            layouts.put(id, layout);
        }
        Set<Integer> visible = parts.computeIfAbsent(id, ignored -> new HashSet<>());
        visible.add(part);
        if (visible.size() == total) visible(id);
    }

    public boolean unread(long id) {
        return received.contains(id) && !read.contains(id) && id > caughtUp;
    }

    public int unread() {
        int n = 0;
        for (long id : received) {
            if (unread(id)) n++;
        }
        return n;
    }

    public void clear() {
        received.clear();
        read.clear();
        parts.clear();
        partCounts.clear();
        layouts.clear();
        match = "";
        caughtUp = Long.MIN_VALUE;
    }
}
