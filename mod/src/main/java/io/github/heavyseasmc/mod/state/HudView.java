package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.Phase;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
 * 划船抽到的牌与舵手看的划船堆（{@link Sea}）同样走这条路。
 *
 * @param active    这个世界上有没有在进行的对局
 * @param turn      第几回合
 * @param phase     当前阶段
 * @param gulls     已有几只海鸥
 * @param seats     座位顺序（角色 id，船头到船尾）· 公开
 * @param actor     行动阶段正轮到谁（角色 id）；不在行动阶段、或已经没人能动时是空串 · 公开
 * @param sea       航海这一段：划船堆 · 舵手 · 挑牌倒计时 · 执行的那张（公开），外加只进收件人那一包的两样
 * @param seated    收件人自己在不在局里（旁观者只看得到上面那几项）
 * @param character 收件人的角色 id
 * @param health    体力（体型 − 伤害）
 * @param maxHealth 体型
 * @param condition 清醒 / 昏迷 / 死亡
 * @param thirst    口渴标记数
 * @param yourTurn  行动阶段正轮到他、而且他没有划船抽到还没定完的牌 —— ❗只在行动阶段为真（见 GameComponent 的 writeView）
 * @param hand      收件人自己的手牌（物资 id，<b>可重复</b>：水有 16 张）。
 *                  旁观者与没座位的人拿到的是空表
 */
public record HudView(boolean active, int turn, Phase phase, int gulls,
                      List<String> seats, String actor, Sea sea,
                      boolean seated, String character, int health, int maxHealth,
                      Condition condition, int thirst, boolean yourTurn, List<String> hand) {

    public HudView {
        seats = List.copyOf(Objects.requireNonNull(seats, "seats"));
        actor = Objects.requireNonNull(actor, "actor");
        sea = Objects.requireNonNull(sea, "sea");
        hand = List.copyOf(Objects.requireNonNull(hand, "hand"));
    }

    /** 没有对局时的样子。**不是 null** —— 空值会一路漂到渲染里才炸。 */
    public static final HudView IDLE = new HudView(
            false, 0, Phase.PROVISION, 0, List.of(), "", Sea.NONE,
            false, "", 0, 0, Condition.CONSCIOUS, 0, false, List.of());

    /** 在局里、有座位，而且手上有牌 —— 手牌界面开不开得起来只看这一条。 */
    public boolean hasHand() {
        return active && seated && !hand.isEmpty();
    }

    /** 该我在行动一面上选一件事了：在局里、有座位、行动阶段、正轮到我。 */
    public boolean myTurnToAct() {
        return active && seated && phase == Phase.ACTION && yourTurn;
    }

    /** 我划船抽到的牌还有没定的 —— 划船一面开不开得起来只看这一条。 */
    public boolean myRowPending() {
        return active && seated && sea.rowing().stream().anyMatch(r -> r.fate() == Session.RowFate.UNDECIDED);
    }

    /** 该我挑航海牌了：航海阶段，而且划船堆的牌进了我这一包（只有舵手、只在挑牌窗口里才会有）。 */
    public boolean myHelmPick() {
        return active && seated && phase == Phase.NAVIGATION && !sea.helmOffer().isEmpty();
    }

    /**
     * 航海这一段。前四项<b>公开</b>，每人一份照发；后两项只进该收的人那一包 —— 别人的包里没有这些字节。
     *
     * @param rowStack       划船堆有几张 · 公开（决策 ⑭：实物桌上那叠牌本来就能数）
     * @param helmsman       舵手（角色 id）；没有清醒的人时是空串 · 公开
     * @param helmDeadlineMs 舵手挑牌的超时时刻（服务端时钟）；0 = 没人在挑 · 公开
     * @param revealed       这一回合执行的航海牌；还没结算时为空 · 公开（结算后只公开这一张）
     * @param rowing         收件人自己划船抽到的牌与去向；他没在划船时为空 · <b>只有划船者本人</b>
     * @param helmOffer      划船堆里的牌 · <b>只有舵手、只在挑牌窗口里</b>，其余人一律为空（决策 ⑭：界面不能揭穿舵手）
     */
    public record Sea(int rowStack, String helmsman, long helmDeadlineMs, Optional<NavCardView> revealed,
                      List<RowCard> rowing, List<NavCardView> helmOffer) {

        public Sea {
            helmsman = Objects.requireNonNull(helmsman, "helmsman");
            revealed = Objects.requireNonNull(revealed, "revealed");
            rowing = List.copyOf(Objects.requireNonNull(rowing, "rowing"));
            helmOffer = List.copyOf(Objects.requireNonNull(helmOffer, "helmOffer"));
        }

        /** 没有对局、或者这一包里没有航海这一段的样子。 */
        public static final Sea NONE = new Sea(0, "", 0L, Optional.empty(), List.of(), List.of());

        /** 划船抽到的一张和它的去向。 */
        public record RowCard(NavCardView card, Session.RowFate fate) {

            public RowCard {
                Objects.requireNonNull(card, "card");
                Objects.requireNonNull(fate, "fate");
            }
        }
    }
}
