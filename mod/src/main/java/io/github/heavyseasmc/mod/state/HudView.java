package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.Phase;

/**
 * 客户端 HUD 看得到的全部东西 —— 一份<b>投影</b>，不是对局状态。
 *
 * <h2>为什么只发投影</h2>
 * 决策 ⑫ 记着 CCA 的 {@code writeSyncPacket} <b>带收件人参数</b>，同一个组件可以给每个玩家
 * 写完全不同的内容。手牌与爱恨将来必须按人裁剪（否则开挂的人能读到别人的牌），
 * 所以这条通路从第一天起就按「每人一份」建，而不是先广播全量、以后再来拆。
 *
 * <p>现在只有身份与伤势，那是 M1 的 HUD 全部需要的（方案 §4.1：全场只有一套识别词，职业）。
 *
 * @param active    这个世界上有没有在进行的对局
 * @param turn      第几回合
 * @param phase     当前阶段
 * @param gulls     已有几只海鸥
 * @param seated    收件人自己在不在局里（旁观者只看得到上面那几项）
 * @param character 收件人的角色 id
 * @param health    体力（体型 − 伤害）
 * @param maxHealth 体型
 * @param condition 清醒 / 昏迷 / 死亡
 * @param thirst    口渴标记数
 * @param yourTurn  正轮到他行动
 */
public record HudView(boolean active, int turn, Phase phase, int gulls,
                      boolean seated, String character, int health, int maxHealth,
                      Condition condition, int thirst, boolean yourTurn) {

    /** 没有对局时的样子。**不是 null** —— 空值会一路漂到渲染里才炸。 */
    public static final HudView IDLE =
            new HudView(false, 0, Phase.PROVISION, 0, false, "", 0, 0, Condition.CONSCIOUS, 0, false);
}
