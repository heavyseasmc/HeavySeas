package io.github.heavyseasmc.engine.state;

/**
 * 一个回合（一天）里的四个阶段，按固定次序推进。
 *
 * <h2>为什么阶段是枚举而不是 boolean 或整数</h2>
 * 因为每个阶段有各自的<b>禁令</b>，而禁令是这套规则里最容易漏实现的一类：
 * <ul>
 *   <li>物资阶段：禁止任何交易；</li>
 *   <li>行动阶段：可以自由交易，<b>战斗期间除外</b>；</li>
 *   <li>航海阶段：禁止交易，<b>两个例外</b> —— 给落水者亮救生圈、给口渴者打水立即喝掉。</li>
 * </ul>
 * 写成枚举之后，「这一刻能不能交易」是一个可以被单测钉死的函数，
 * 而不是散落在各处的 if。
 */
public enum Phase {

    /** 天候阶段。每日物资阶段之前翻开一张；“无风”仍以结束航海阶段的方式结束一天并清标记。 */
    WEATHER,

    /**
     * 物资阶段。最靠船头的清醒角色抽「存活且清醒角色数」张牌，留 1 张，其余传给下一位。
     *
     * <p>❗牌堆抽完即止，<b>不洗回重用</b>；之后每回合直接略过本阶段。
     * 这条是本阶段唯一会让它「无事可做」的情况，也是模拟器要能跑到终局的前提之一。
     */
    PROVISION,

    /** 行动阶段。按「最靠船头且本回合未行动」依次取人，每人一个行动。战斗是它的子状态。 */
    ACTION,

    /** 航海阶段。舵手挑牌或翻顶牌，按 海鸥 → 落海 → 口渴 结算，然后清标记。 */
    NAVIGATION;

    /** 本阶段结束后进入哪个阶段。航海阶段之后进入下一天的天候阶段，并推进回合数。 */
    public Phase next() {
        return switch (this) {
            case WEATHER -> PROVISION;
            case PROVISION -> ACTION;
            case ACTION -> NAVIGATION;
            case NAVIGATION -> WEATHER;
        };
    }

    /** 本阶段结束是否意味着一个回合走完。 */
    public boolean endsTurn() {
        return this == NAVIGATION;
    }

    /**
     * 本阶段能否自由赠送 / 交换物资。
     *
     * <p>航海阶段的两个例外（救生圈、打水）不在这里表达 —— 它们不是「自由交易」，
     * 是结算步骤里指定的动作，由落海与口渴的结算各自负责。
     * 把例外塞进这个谓词会让它变成「大致能交易」，那就没用了。
     *
     * @param inFight 是否正处于战斗中。战斗结束前任何卡不得易手
     */
    public boolean allowsFreeTrade(boolean inFight) {
        return this == ACTION && !inFight;
    }
}
