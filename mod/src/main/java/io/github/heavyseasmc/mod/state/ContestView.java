package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.engine.play.Contest;

import java.util.List;
import java.util.Objects;

/**
 * 进行中的那一次换座位 / 抢夺，客户端看得到的那一份（ADR-0023）。
 *
 * <h2>哪些公开、哪些只进一个人的包</h2>
 * <b>公开</b>：谁对谁 · 走到哪一段 · 还剩多久 · 两边站了谁 · 两边的体型和。
 * 理由与划船堆张数、口渴轮到谁同一条（决策 ⑭）：<b>等待要看得见</b> —— 全船在等这一场收场，
 * 屏幕上却什么都没有的话，看起来就像卡住了。
 *
 * <p><b>不公开的是武器</b>：决策 ④ 把它定成全场唯一的暗牌。所以 {@link #myWeapons} 与
 * {@link #myCommitted} 只写给押牌的人自己，而且两边的战力和里 <b>不含已押的武器</b> ——
 * 含进去的话，减一减就知道对面押了几点，这一段就白做了。
 *
 * <p>同理 {@link #victimHand} 只给<b>张数</b>不给牌：手牌是暗的，抢夺方也不能看着牌挑（规则 §5）。
 *
 * @param kind        换座位还是抢夺
 * @param attacker    发起的人（角色 id）
 * @param target      被指定的人（角色 id）
 * @param stage       走到哪一段；{@code null} 表示没有进行中的这一场
 * @param deadlineMs  这一段的超时时刻（服务端时钟）；0 = 没开窗口 —— 这一段没有真人要动，由排程一步一步推
 * @param windowMs    这一段<b>本来有多长</b>。❗倒计时那条横杠要按它画：站队有人加入会把 15 秒重置成 8 秒，
 *                    照 15 秒画的杠会从一半开始走，而它看起来完全正常
 * @param attackSide  进攻方站了谁 · 公开。表态与挑牌两段没有战斗，为空
 * @param defendSide  防守方站了谁 · 公开
 * @param attackPower 进攻方的体型和 · 公开。❗<b>不含已押的武器</b>
 * @param defendPower 防守方的体型和 · 公开。同上
 * @param myWeapons   收件人此刻还押得出的武器（物资 id，<b>可重复</b>：两支船桨就是两项）· <b>只有本人</b>
 * @param myCommitted 收件人已经押下几张 · <b>只有本人</b>
 * @param victimFront 被抢方面前亮着的牌 · <b>只在挑牌那一段写给抢夺方</b>（面前那一区规则上本来就是公开的）
 * @param victimHand  被抢方手上有几张 · 同上，<b>只给张数</b>
 */
public record ContestView(Contest.Kind kind, String attacker, String target, Contest.Stage stage,
                          long deadlineMs, long windowMs,
                          List<String> attackSide, List<String> defendSide, int attackPower, int defendPower,
                          List<String> myWeapons, int myCommitted, List<String> victimFront, int victimHand) {

    public ContestView {
        Objects.requireNonNull(kind, "kind");
        attacker = Objects.requireNonNull(attacker, "attacker");
        target = Objects.requireNonNull(target, "target");
        attackSide = List.copyOf(Objects.requireNonNull(attackSide, "attackSide"));
        defendSide = List.copyOf(Objects.requireNonNull(defendSide, "defendSide"));
        myWeapons = List.copyOf(Objects.requireNonNull(myWeapons, "myWeapons"));
        victimFront = List.copyOf(Objects.requireNonNull(victimFront, "victimFront"));
    }

    /**
     * 没有进行中的这一场。<b>不是 null</b> —— 空值会一路漂到渲染里才炸（与 {@link HudView} 那几个 NONE 同一条）。
     *
     * <p>{@code kind} 随便取一个：{@link #stage} 为空时没有任何一面读得到它。
     */
    public static final ContestView NONE = new ContestView(Contest.Kind.SWAP, "", "", null, 0L, 0L,
            List.of(), List.of(), 0, 0, List.of(), 0, List.of(), 0);

    /** 有没有进行中的这一场。 */
    public boolean active() {
        return stage != null;
    }

    /**
     * 这一段正在等人吗。
     *
     * <p>❗<b>没开窗口不等于没有这一场</b>：全是替身的那几段不开窗口，由排程按默认答案一步一步推。
     * 四面 GUI 认的是这一条，不是 {@link #active()} —— 认错的话，没人要等的那一段也会弹出一个界面，
     * 而它下一 tick 就被推过去了。
     */
    public boolean waiting() {
        return active() && deadlineMs > 0L;
    }

    /** 他在不在场上（哪一边都算）。加入之后不可退出，所以这是个只会从假变真的判断。 */
    public boolean isCombatant(String characterId) {
        return attackSide.contains(characterId) || defendSide.contains(characterId);
    }
}
