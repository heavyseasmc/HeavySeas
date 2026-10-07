package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.model.CharacterId;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一个策略有没有改写 {@link SeatPolicy} 里那几个「默认不做」的方法。
 *
 * <p>只为省时间：默认实现交回「不做」且<b>不碰随机数</b>，所以没改写的策略不问也一样 ——
 * 驱动者因此可以连合法清单都不算（送牌那一张清单每个人每天要列几十项）。
 * 结果按类记一次；同一个类的每个实例答案相同。
 */
final class Overrides {

    private record Answer(boolean gives, boolean overboards, boolean drinksForFight, boolean donates) {
    }

    private static final ConcurrentHashMap<Class<?>, Answer> CACHE = new ConcurrentHashMap<>();

    private Overrides() {
    }

    static boolean gives(SeatPolicy p) {
        return of(p).gives();
    }

    static boolean overboards(SeatPolicy p) {
        return of(p).overboards();
    }

    static boolean drinksForFight(SeatPolicy p) {
        return of(p).drinksForFight();
    }

    static boolean donates(SeatPolicy p) {
        return of(p).donates();
    }

    private static Answer of(SeatPolicy p) {
        return CACHE.computeIfAbsent(p.getClass(), c -> new Answer(
                overridden(c, "give", SeatView.class, List.class, Random.class),
                overridden(c, "overboard", SeatView.class, List.class, Random.class),
                overridden(c, "drinkForFight", SeatView.class, List.class, Random.class),
                overridden(c, "donateWater", SeatView.class, CharacterId.class, int.class, int.class,
                        boolean.class, int.class, Random.class)));
    }

    private static boolean overridden(Class<?> c, String name, Class<?>... params) {
        try {
            return c.getMethod(name, params).getDeclaringClass() != SeatPolicy.class;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("SeatPolicy." + name + " 的签名变了，这里没跟上", e);
        }
    }
}
