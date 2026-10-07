package io.github.heavyseasmc.mod.state;

/**
 * 替身拿主意的方式（全服一份，挂在雾海的组件上；{@code /seas dummy random|smart|llm}）。
 *
 * <p>只在<b>替身自动推进开着</b>时起作用；关着时替身照旧等指令（ADR-0019）。全部是开发 / 演示用的开关，默认 {@link #IDLE}、不持久化 ——
 * 正式计分局用替身补空座要不要「动脑」还没定（用户 2026-10-07「打过再定」，决策 ②）。
 */
public enum StandInMind {

    /** 什么也不做 · 一律同意 · 不站队 · 不押 · 舵手挑第一张 · 口渴喝够（ADR-0019，原先的默认）。 */
    IDLE("什么也不做"),
    /** 照模拟器的分布随机动（{@code StandInPlay}，用户 2026-10-07）。消费随机数的次序是钉死的 —— 回归与演示局都靠它。 */
    RANDOM("随机"),
    /** 引擎的第一层「按处境打分」（{@code HeuristicSeatPolicy}），只看这一座看得见的东西。 */
    SMART("动脑"),
    /** 先按 {@link #SMART} 算一个答案，再问大模型；大模型没给出合法答案就用那个答案（ADR-0096 A）。 */
    LLM("大模型");

    private final String label;

    StandInMind(String label) {
        this.label = label;
    }

    /** 日志与指令回显用的名字。 */
    public String label() {
        return label;
    }

    /** 替身会自己动（不是一律「什么也不做」）。原先问 {@code dummyRandom()} 的地方，凡是意思是「替身会动」的都改问它。 */
    public boolean acts() {
        return this != IDLE;
    }

    /** 走「动脑」那一套（异步想、回主线程核对后再做）：{@link #SMART} 与 {@link #LLM}。 */
    public boolean thinks() {
        return this == SMART || this == LLM;
    }
}
