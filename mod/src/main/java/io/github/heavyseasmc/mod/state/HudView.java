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
 * @param thirstPrompt 口渴结算正在问谁、还需化解几次、还剩多久 · <b>公开</b>（他手上有几张水不在这里）
 * @param yourTurn  行动阶段正轮到他、而且他没有划船抽到还没定完的牌 —— ❗只在行动阶段为真（见 GameComponent 的 writeView）
 * @param hand      收件人自己的手牌（物资 id，<b>可重复</b>：水有 16 张）。
 *                  旁观者与没座位的人拿到的是空表
 * @param front     收件人自己<b>亮在面前</b>的牌。规则上这一区是公开的，但别人的那份这一版还没发 ——
 *                  要等头顶信息条（决策 ⑥）才有地方显示
 */
public record HudView(boolean active, int turn, Phase phase, int gulls,
                      List<String> seats, String actor, Sea sea, Thirst thirstPrompt,
                      boolean seated, String character, int health, int maxHealth,
                      Condition condition, int thirst, boolean yourTurn, List<String> hand,
                      List<FrontCard> front) {

    public HudView {
        seats = List.copyOf(Objects.requireNonNull(seats, "seats"));
        actor = Objects.requireNonNull(actor, "actor");
        sea = Objects.requireNonNull(sea, "sea");
        thirstPrompt = Objects.requireNonNull(thirstPrompt, "thirstPrompt");
        hand = List.copyOf(Objects.requireNonNull(hand, "hand"));
        front = List.copyOf(Objects.requireNonNull(front, "front"));
    }

    /** 没有对局时的样子。**不是 null** —— 空值会一路漂到渲染里才炸。 */
    public static final HudView IDLE = new HudView(
            false, 0, Phase.PROVISION, 0, List.of(), "", Sea.NONE, Thirst.NONE,
            false, "", 0, 0, Condition.CONSCIOUS, 0, false, List.of(), List.of());

    /**
     * 亮在面前的一张。
     *
     * @param id   物资 id
     * @param open 已经「打开」并持续生效（撑开的伞）。收着的伞与撑开的伞在界面上必须看得出不同
     */
    public record FrontCard(String id, boolean open) {

        public FrontCard {
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * 口渴结算正在问谁。**公开**：全船都看得见轮到谁、还剩多久。
     *
     * @param who        正被问的角色 id；空串表示没人在被问
     * @param sources    他这一回合实际有几次口渴（划船 / 战斗 / 点名 / 喝酒）
     * @param covered    撑开的阳伞替他抵掉几次
     * @param shared     蹭别人喝的水抵掉几次（陪酒女）
     * @param remaining  还需要化解几次 —— 每一次要么喝 1 张水，要么挨 1 点
     * @param deadlineMs 超时时刻（服务端时钟）；0 = 没开窗口（他没水、或者不清醒，直接按不喝结算）
     */
    public record Thirst(String who, int sources, int covered, int shared, int remaining, long deadlineMs) {

        public Thirst {
            who = Objects.requireNonNull(who, "who");
        }

        public static final Thirst NONE = new Thirst("", 0, 0, 0, 0, 0L);

        public boolean active() {
            return !who.isEmpty();
        }
    }

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

    /** 该我决定喝不喝水了：口渴结算问到我，而且窗口开着（没水或昏迷时服务端不开窗口）。 */
    public boolean myThirstChoice() {
        return active && seated && thirstPrompt.active() && thirstPrompt.deadlineMs() > 0
                && thirstPrompt.who().equals(character);
    }

    /**
     * 我现在拿得出几张水：手上的加亮在面前的。亮出来的水照样能喝（规则 §5.2）。
     *
     * <p>❗id 取引擎那一处常量（{@link Session#WATER}），不在客户端再写一个字面量 ——
     * 两处字面量迟早会分家，而分家的表现是「界面说你有水，服务端说没有」。
     */
    public int myWaters() {
        int waters = (int) hand.stream().filter(Session.WATER::equals).count();
        return waters + (int) front.stream().filter(card -> Session.WATER.equals(card.id())).count();
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
