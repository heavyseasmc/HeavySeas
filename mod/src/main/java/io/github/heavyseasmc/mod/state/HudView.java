package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.Phase;

import java.util.List;
import java.util.Objects;

/**
 * 客户端 HUD 看得到的全部东西 —— 一份<b>投影</b>，不是对局状态。
 *
 * <h2>为什么只发投影</h2>
 * 决策 ⑫ 记着 CCA 的 {@code writeSyncPacket} <b>带收件人参数</b>，同一个组件可以给每个玩家
 * 写完全不同的内容。手牌与爱恨将来必须按人裁剪（否则开挂的人能读到别人的牌），
 * 所以这条通路从第一天起就按「每人一份」建，而不是先广播全量、以后再来拆。
 *
 * <p>手牌就走这条路：它在 {@code writeSyncPacket} 里<b>只写收件人自己那一份</b>，
 * 别人的包里根本没有这些字节 —— 改过的客户端也读不到不存在的东西。
 * 「发全量再让客户端藏起来」在这里是错的，理由与补给箱的 offer 同一条（决策 ⑨）。
 *
 * @param active    这个世界上有没有在进行的对局
 * @param turn      第几回合
 * @param phase     当前阶段
 * @param gulls     已有几只海鸥
 * @param seats     座位顺序（角色 id，船头到船尾）· 公开
 * @param actor     行动阶段正轮到谁（角色 id）；不在行动阶段、或已经没人能动时是空串 · 公开
 * @param seated    收件人自己在不在局里（旁观者只看得到上面那几项）
 * @param character 收件人的角色 id
 * @param health    体力（体型 − 伤害）
 * @param maxHealth 体型
 * @param condition 清醒 / 昏迷 / 死亡
 * @param thirst    口渴标记数
 * @param yourTurn  行动阶段正轮到他 —— ❗只在行动阶段为真（见 GameComponent 的 writeView）
 * @param hand      收件人自己的手牌（物资 id，<b>可重复</b>：水有 16 张）。
 *                  旁观者与没座位的人拿到的是空表
 */
public record HudView(boolean active, int turn, Phase phase, int gulls,
                      List<String> seats, String actor,
                      boolean seated, String character, int health, int maxHealth,
                      Condition condition, int thirst, boolean yourTurn, List<String> hand) {

    public HudView {
        seats = List.copyOf(Objects.requireNonNull(seats, "seats"));
        actor = Objects.requireNonNull(actor, "actor");
        hand = List.copyOf(Objects.requireNonNull(hand, "hand"));
    }

    /** 没有对局时的样子。**不是 null** —— 空值会一路漂到渲染里才炸。 */
    public static final HudView IDLE = new HudView(
            false, 0, Phase.PROVISION, 0, List.of(), "", false, "", 0, 0, Condition.CONSCIOUS, 0, false, List.of());

    /** 在局里、有座位，而且手上有牌 —— 手牌界面开不开得起来只看这一条。 */
    public boolean hasHand() {
        return active && seated && !hand.isEmpty();
    }

    /** 该我在行动一面上选一件事了：在局里、有座位、行动阶段、正轮到我。 */
    public boolean myTurnToAct() {
        return active && seated && phase == Phase.ACTION && yourTurn;
    }
}
