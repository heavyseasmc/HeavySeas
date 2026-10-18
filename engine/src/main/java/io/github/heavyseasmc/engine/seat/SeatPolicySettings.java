package io.github.heavyseasmc.engine.seat;

/**
 * 会动脑的替身那几个可以调的旋钮 —— <b>全部</b>在这一个记录里。
 *
 * <h2>为什么只有这一处</h2>
 * 模组要给服主一个配置界面管所有设置（用户 2026-10-07），它会把配置文件里的值映射进这个记录。
 * 所以：策略只从构造参数拿它；没有静态全局量，不读系统属性 —— 两处来源就有两个真相。
 *
 * <h2>不在这里的</h2>
 * 打分里那些<b>设计常数</b>（危险度曲线、各种牌值多少、打架胜率的坡度……）不给服主调：
 * 它们一起定了「这个替身怎么想」，单拧一个只会把它拧傻。它们写在 {@link HeuristicSeatPolicy} 里，各自注明来历。
 *
 * <h2>每个字段都有默认值与合法范围</h2>
 * 构造时逐项核对，越界就抛 —— 配置文件写错了要当场说，而不是悄悄夹到边上（夹回去与「配对了」长得一样）。
 *
 * @param temperature     选择的随机程度，按「分」计。0 = 永远挑打分最高的那一项（完全可预测）；
 *                        越大越常挑次好的。默认 {@value #DEFAULT_TEMPERATURE}，范围 [0, 5]
 * @param gratitude       别人帮过我，我记多重（递水 · 送牌 · 站我这边 · 扔救生圈 · 治我）。
 *                        0 = 不记恩。默认 {@value #DEFAULT_GRATITUDE}，范围 [0, 3]
 * @param resentment      别人害过我，我记多重（抢我 · 打我 · 站对面 · 血饵 · 舵手挑牌送我下水）。
 *                        0 = 不记仇。默认 {@value #DEFAULT_RESENTMENT}，范围 [0, 3]
 * @param memoryDays      恩怨的半衰期，按天计：过这么多天，一件事的分量减半。
 *                        默认 {@value #DEFAULT_MEMORY_DAYS}，范围 [0.5, 50]
 * @param search          要不要往前推演（第二层）。关着时只按处境打分（第一层）。默认开
 * @param rollouts        每个决定最多推演几局。默认 {@value #DEFAULT_ROLLOUTS}，范围 [8, 20000]
 * @param millisPerDecision 每个决定最多想多久（毫秒）；0 = 不限时，只看局数。
 *                        ❗限时会让结果取决于机器快慢：要可复现就设 0，让局数先用完。
 *                        默认 {@value #DEFAULT_MILLIS}，范围 [0, 10000]
 * @param width           剪枝之后留几个候选进推演。默认 {@value #DEFAULT_WIDTH}，范围 [1, 16]
 * @param horizonDays     每局推演往前看几天，之后用第一层的估值收尾；0 = 一直推到终局。
 *                        默认 {@value #DEFAULT_HORIZON}，范围 [0, 40]
 */
public record SeatPolicySettings(double temperature, double gratitude, double resentment, double memoryDays,
                                 boolean search, int rollouts, int millisPerDecision, int width,
                                 int horizonDays) {

    public static final double DEFAULT_TEMPERATURE = 0.25;
    public static final double DEFAULT_GRATITUDE = 1.0;
    public static final double DEFAULT_RESENTMENT = 1.0;
    public static final double DEFAULT_MEMORY_DAYS = 4.0;
    public static final int DEFAULT_ROLLOUTS = 128;
    public static final int DEFAULT_MILLIS = 250;
    public static final int DEFAULT_WIDTH = 4;
    public static final int DEFAULT_HORIZON = 2;

    /** 全部取默认值。 */
    public static final SeatPolicySettings DEFAULTS = new SeatPolicySettings(DEFAULT_TEMPERATURE, DEFAULT_GRATITUDE,
            DEFAULT_RESENTMENT, DEFAULT_MEMORY_DAYS, true, DEFAULT_ROLLOUTS, DEFAULT_MILLIS, DEFAULT_WIDTH,
            DEFAULT_HORIZON);

    public SeatPolicySettings {
        range("temperature", temperature, 0, 5);
        range("gratitude", gratitude, 0, 3);
        range("resentment", resentment, 0, 3);
        range("memoryDays", memoryDays, 0.5, 50);
        range("rollouts", rollouts, 8, 20_000);
        range("millisPerDecision", millisPerDecision, 0, 10_000);
        range("width", width, 1, 16);
        range("horizonDays", horizonDays, 0, 40);
    }

    /** 只按处境打分（第一层），不推演。 */
    public SeatPolicySettings withoutSearch() {
        return new SeatPolicySettings(temperature, gratitude, resentment, memoryDays, false, rollouts,
                millisPerDecision, width, horizonDays);
    }

    /** 换一个随机程度。 */
    public SeatPolicySettings withTemperature(double t) {
        return new SeatPolicySettings(t, gratitude, resentment, memoryDays, search, rollouts, millisPerDecision,
                width, horizonDays);
    }

    /** 换一份推演预算（局数 · 时限 · 宽度 · 往前看几天）。 */
    public SeatPolicySettings withBudget(int rollouts, int millisPerDecision, int width, int horizonDays) {
        return new SeatPolicySettings(temperature, gratitude, resentment, memoryDays, search, rollouts,
                millisPerDecision, width, horizonDays);
    }

    /** 换一份恩怨的分量。 */
    public SeatPolicySettings withMemory(double gratitude, double resentment, double memoryDays) {
        return new SeatPolicySettings(temperature, gratitude, resentment, memoryDays, search, rollouts,
                millisPerDecision, width, horizonDays);
    }

    private static void range(String name, double value, double min, double max) {
        // ❗NaN 与任何数比较都是 false：写成「value < min || value > max」会把 NaN 放过去。
        if (!(value >= min && value <= max)) {
            throw new IllegalArgumentException("替身设置 %s 必须在 [%s, %s] 之内，实际: %s"
                    .formatted(name, fmt(min), fmt(max), value));
        }
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }
}
