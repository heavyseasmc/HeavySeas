package io.github.heavyseasmc.engine.thirst;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 某人在<b>某一回合</b>累积到的口渴来源集合。
 *
 * <p>不可变。累积靠 {@link #with(ThirstSource)} 返回新值，
 * 与本模块其余的 record 风格一致，也避免把一个可变 {@code EnumSet} 泄漏出去
 * —— 那种泄漏会让「上一回合的标记没清干净」这类 bug 变得极难查，
 * 而口渴恰恰是<b>跨天累积</b>的（见开放项 O7）。
 *
 * <p><b>生命周期</b>：航海阶段结束时清空。O7 正是在问「天候『无风』跳过整个航海阶段时，
 * 划船与战斗标记清不清」—— 那一条尚未定案，所以本类<b>不</b>自己决定何时清空，
 * 清空由状态机在阶段边界上做。
 */
public record ThirstTally(Set<ThirstSource> sources) {

    private static final ThirstTally NONE = new ThirstTally(Collections.emptySet());

    public ThirstTally {
        Objects.requireNonNull(sources, "sources");
        sources = sources.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(sources));
    }

    /** 本回合尚未渴过。 */
    public static ThirstTally none() {
        return NONE;
    }

    public static ThirstTally of(ThirstSource... sources) {
        return sources.length == 0 ? NONE : new ThirstTally(Set.of(sources));
    }

    /**
     * 加入一个来源。已存在时返回自身 —— 幂等正是「战斗一回合只算一次」的实现方式。
     */
    public ThirstTally with(ThirstSource source) {
        Objects.requireNonNull(source, "source");
        if (sources.contains(source)) {
            return this;
        }
        EnumSet<ThirstSource> next = sources.isEmpty()
                ? EnumSet.noneOf(ThirstSource.class)
                : EnumSet.copyOf(sources);
        next.add(source);
        return new ThirstTally(next);
    }

    public boolean has(ThirstSource source) {
        return sources.contains(source);
    }

    /** 本回合的口渴次数。上限恒为 {@code ThirstSource.values().length}。 */
    public int count() {
        return sources.size();
    }

    public boolean isEmpty() {
        return sources.isEmpty();
    }
}
