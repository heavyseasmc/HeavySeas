package io.github.heavyseasmc.mod.config;

import java.util.function.DoublePredicate;
import java.util.function.IntPredicate;

/**
 * 一个记录「收不收这个值」的上下限，从它自己的核对里问出来（二分）。
 *
 * <h2>为什么要它</h2>
 * 引擎的 {@code SeatPolicySettings} 构造时逐项核范围、越界就抛，但不把范围公开成常量。菜单要上下限：
 * 抄一份数就有两个真相（引擎改了范围、菜单还拦在老的那一格），所以从它自己的核对里问 —— 从默认值（一定收）出发往一头
 * 倍增步子，找到第一个不收的，再在两者之间二分，问到它刚好还收的那一个数。
 *
 * <p>只适合「收的值是一段连续区间」的核对（{@code min ≤ v ≤ max} 那种）；问不到边（一路都收）就抛 —— 那是没有上下限，菜单画不出液位管。
 * 小数的结果按 {@value #DECIMALS} 位小数取整：区间的端点都是写在代码里的整齐数（0、0.5、50），二分只会落在它的一个 ulp 之内。
 */
final class RecordBounds {

    /** 小数的边按几位取整。 */
    static final int DECIMALS = 6;

    /** 往外找边最远找到哪：再远还收就当它没有边。 */
    private static final double FAR = 1e9;

    private RecordBounds() {
    }

    /** {@code accepts} 收的最小值（{@code from} 必须收）。 */
    static double lowest(DoublePredicate accepts, double from) {
        return edge(accepts, from, -1);
    }

    /** {@code accepts} 收的最大值（{@code from} 必须收）。 */
    static double highest(DoublePredicate accepts, double from) {
        return edge(accepts, from, +1);
    }

    /** 整数版：{@code accepts} 收的最小值（{@code from} 必须收）。 */
    static int lowest(IntPredicate accepts, int from) {
        return edge(accepts, from, -1);
    }

    /** 整数版：{@code accepts} 收的最大值。 */
    static int highest(IntPredicate accepts, int from) {
        return edge(accepts, from, +1);
    }

    private static double edge(DoublePredicate accepts, double from, int direction) {
        if (!accepts.test(from)) {
            throw new IllegalArgumentException("起点 " + from + " 本身就不收");
        }
        double inside = from;
        double step = 1;
        double outside = from + direction * step;
        while (accepts.test(outside)) {
            inside = outside;
            step *= 2;
            if (step > FAR) {
                throw new IllegalArgumentException("一路都收：这一项没有" + (direction < 0 ? "下限" : "上限"));
            }
            outside = from + direction * step;
        }
        for (int i = 0; i < 200; i++) {
            double mid = inside + (outside - inside) / 2;
            if (mid == inside || mid == outside) {
                break;
            }
            if (accepts.test(mid)) {
                inside = mid;
            } else {
                outside = mid;
            }
        }
        double scale = Math.pow(10, DECIMALS);
        double rounded = Math.round(inside * scale) / scale;
        // 取整之后的那个数也必须收（端点是整齐数时就是它本身）；不收就退回二分出来的那个
        return accepts.test(rounded) ? rounded : inside;
    }

    private static int edge(IntPredicate accepts, int from, int direction) {
        if (!accepts.test(from)) {
            throw new IllegalArgumentException("起点 " + from + " 本身就不收");
        }
        long inside = from;
        long step = 1;
        long outside = from + direction * step;
        while (outside >= Integer.MIN_VALUE && outside <= Integer.MAX_VALUE && accepts.test((int) outside)) {
            inside = outside;
            step *= 2;
            outside = from + direction * step;
        }
        if (outside < Integer.MIN_VALUE || outside > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("一路都收：这一项没有" + (direction < 0 ? "下限" : "上限"));
        }
        while (Math.abs(outside - inside) > 1) {
            long mid = inside + (outside - inside) / 2;
            if (accepts.test((int) mid)) {
                inside = mid;
            } else {
                outside = mid;
            }
        }
        return (int) inside;
    }
}
