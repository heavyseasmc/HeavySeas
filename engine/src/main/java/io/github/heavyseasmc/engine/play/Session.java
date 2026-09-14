package io.github.heavyseasmc.engine.play;

import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.Selector;
import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.thirst.ThirstResolver;
import io.github.heavyseasmc.engine.thirst.ThirstSource;
import io.github.heavyseasmc.engine.thirst.ThirstTally;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 一局进行中的对局：{@link GameState} 加桌面，外加**全部规则动作**。
 *
 * <h2>为什么把规则搬到这里</h2>
 * 这些动作原先是 {@code Simulator} 的私有方法。后果是：模拟器跑几千局验证的，
 * 是**模组永远不会执行的那一份实现** —— 模组要驱动真实对局，就只能照着再写一遍。
 * 而「同一份东西复制两处、各自维护」在本仓库已经被证伪过一次（两份漂移的 textguard）。
 *
 * <p>所以规则只有这一份，上面挂两个驱动者：
 *
 * <pre>
 *   Simulator（随机决策）──┐
 *                          ├──→ Session（规则）──→ GameState / Table
 *   模组（玩家决策）───────┘
 * </pre>
 *
 * <p>模拟器验证的因此就是模组运行的那份代码。
 *
 * <h2>决策由调用方给，规则不替谁做决定</h2>
 * 「划船时留哪张」「喝几张水」「舵手挑哪张」都是**决策**，不是规则。它们以参数或回调传进来：
 * 模拟器用随机流，模组问玩家。规则只负责决策之后该怎么算。
 *
 * <p>❗回调的调用**次序与次数**是规则的一部分：模拟器的可复现性依赖它。
 * 改动这里的循环结构，同一个种子就会跑出不同的局。
 */
public final class Session {

    /** 划船时抽几张看过再决定。 */
    public static final int CARDS_DRAWN_WHEN_ROWING = 2;

    private final String context;
    private final Table table;
    private GameState state;

    /**
     * @param context 出处，报错时靠它定位（模拟器给种子，模组给对局标识）
     */
    public Session(String context, Roster roster, Table table) {
        this.context = Objects.requireNonNull(context, "context");
        this.table = Objects.requireNonNull(table, "table");
        this.state = GameState.start(roster);
        Invariants.requireValid(state, context, "开局");
    }

    public GameState state() {
        return state;
    }

    public Table table() {
        return table;
    }

    public String context() {
        return context;
    }

    /** 推进到下一阶段（航海之后回到物资并推进回合数）。 */
    public void advancePhase() {
        state = state.advancePhase();
        Invariants.requireValid(state, context, "阶段推进后");
    }

    /**
     * 物资阶段该抽几张 = <b>存活且清醒者数</b>。
     *
     * @return 本阶段该抽几张
     */
    public int provisionDraws() {
        int draws = state.consciousBySeat().size();
        if (draws < 0) {
            throw new IllegalStateException("抽牌数不可能为负");
        }
        return draws;
    }

    // ---------------------------------------------------------------- 物资阶段

    /** 这一轮传递的顺序：开始时的清醒者，船头到船尾。**中途不重算**。 */
    private List<CharacterId> provisionChain = List.of();

    /** 当前持有者看得到的牌。 */
    private final List<String> provisionOffer = new ArrayList<>();

    /** 箱子传到第几个人（下标指向 {@link #provisionChain}）。 */
    private int provisionAt;

    /**
     * 开始一轮传递：最靠船头的清醒角色抽 N 张。
     *
     * <p>❗<b>顺序在开始时定死，中途不重算。</b> 传递过程中有人昏迷时，
     * 若按「当前清醒者」重算，箱子会在半路改道 —— 而前面的人已经按旧顺序看过牌了，
     * 信息梯度就对不上了。
     *
     * <p>牌堆不够 N 张时有几张抽几张；一张都没有就直接结束（规则：抽完即止，不洗回）。
     *
     * @return 第一位看得到的牌；空表示本阶段无事可做
     */
    public List<String> beginProvision() {
        provisionChain = List.copyOf(state.consciousBySeat());
        provisionOffer.clear();
        provisionAt = 0;
        if (provisionChain.isEmpty()) {
            return List.of();
        }
        provisionOffer.addAll(table.drawProvisions(provisionDraws()));
        return List.copyOf(provisionOffer);
    }

    /** 箱子在谁手上；空表示这一轮已经传完（或根本没开始）。 */
    public Optional<CharacterId> provisionHolder() {
        if (provisionOffer.isEmpty() || provisionAt >= provisionChain.size()) {
            return Optional.empty();
        }
        return Optional.of(provisionChain.get(provisionAt));
    }

    /** 这一轮的传递顺序（角色 id），开始时定死。**公开信息**：全船都看得见箱子怎么传。 */
    public List<String> provisionChainIds() {
        return provisionChain.stream().map(CharacterId::value).toList();
    }

    /** 箱子传到第几位。{@code >= 链长} 表示这一轮结束。 */
    public int provisionIndex() {
        return provisionAt;
    }

    /** 当前持有者看得到的牌。**只有他看得到** —— 这是规则，不是显示差异。 */
    public List<String> provisionOffer() {
        return List.copyOf(provisionOffer);
    }

    /**
     * 留下一张，其余传给下一位。
     *
     * @param cardId 必须是当前 offer 里的一张
     * @throws IllegalStateException     现在没有人持有箱子
     * @throws IllegalArgumentException  这张牌不在 offer 里 —— 静默改成别的会让作弊看不出来
     */
    public void provisionKeep(String cardId) {
        CharacterId holder = provisionHolder().orElseThrow(
                () -> new IllegalStateException("%s 现在没有人持有补给箱".formatted(context)));
        if (!provisionOffer.remove(cardId)) {
            throw new IllegalArgumentException(
                    "%s %s 留的 %s 不在他看得到的牌里".formatted(context, holder.value(), cardId));
        }
        state = state.withState(holder, state.stateOf(holder).withCard(cardId));
        provisionAt++;
        Invariants.requireValid(state, context, "物资留牌后");
    }

    /**
     * 这一轮传递是否还没结束。
     *
     * <p>结束有两种：传完最后一位，或者牌提前发光（牌堆不够时会这样）。
     */
    public boolean provisionInProgress() {
        return provisionHolder().isPresent();
    }

    /** 下一个该行动的人；空表示本阶段没人能再行动（**合法状态，不是死锁**）。 */
    public Optional<CharacterId> nextActor() {
        return state.nextActor();
    }

    /** 记下这个人本回合行动过了。行动阶段每人一次，必须写回，否则 nextActor 会一直返回同一个人。 */
    public void markActed(CharacterId actor) {
        state = state.withState(actor, state.stateOf(actor).markActed());
        Invariants.requireValid(state, context, "行动后");
    }

    /**
     * 划船：抽 2 张看过，<b>对每一张分别</b>决定放进划船堆还是塞回牌堆底部，然后领一个划船标记。
     *
     * <p>❗「看过再决定」是本作信息结构的核心：划船堆面朝下，划船者只知道自己放了什么，
     * 舵手知道全部，其他人只看得到有几个人划了船。舵手因此能从别人挑剩的里面再挑一次 ——
     * 落水/口渴的实际分布被这两道挑选<b>连挑两次</b>，与牌面上印的张数分布不是一回事。
     *
     * <p>牌堆不够 2 张时有几张抽几张。
     *
     * @return 这次实际抽到的牌，按抽出顺序
     */
    public List<NavigationCard> row(CharacterId rower, RowingChoice choice) {
        List<NavigationCard> drawn = new ArrayList<>();
        for (int i = 0; i < CARDS_DRAWN_WHEN_ROWING && !table.pile().isEmpty(); i++) {
            NavigationCard card = table.pile().draw();
            drawn.add(card);
            if (choice.keep(card, state, rower)) {
                table.addToRowStack(card);
            } else {
                table.pile().bottom(card);
            }
        }
        table.requireNoCardLost(context, "划船后");
        state = state.withState(rower, state.stateOf(rower).thirstFrom(ThirstSource.ROWED));
        return List.copyOf(drawn);
    }

    /** 换座位：与任意角色交换，不限相邻。昏迷与死亡者不能拒绝。 */
    public void swapSeats(CharacterId actor, CharacterId target) {
        int a = state.stateOf(actor).seat();
        int b = state.stateOf(target).seat();
        state = state.withState(actor, state.stateOf(actor).withSeat(b))
                .withState(target, state.stateOf(target).withSeat(a));
    }

    /**
     * 结算一场已经组好的战斗：判胜负、扣伤、给全部参战者发战斗标记。
     *
     * <p>❗战斗标记发给<b>全部参战者含助拳者</b>；同回合多次参战也只算一个（集合幂等）。
     *
     * @return 谁输了、每人扣几点
     */
    public Fight.Outcome applyFight(Fight fight) {
        Fight.Outcome outcome = fight.resolve(id -> state.roster().get(id).size());
        GameState next = state;
        for (CharacterId loser : outcome.losers()) {
            next = next.withState(loser, next.stateOf(loser).hurt(outcome.damagePerLoser()));
        }
        for (CharacterId c : fight.combatants()) {
            next = next.withState(c, next.stateOf(c).thirstFrom(ThirstSource.FOUGHT));
        }
        state = next;
        return outcome;
    }

    /** 能被抢夺/攻击的对象：<b>清醒</b>的人才能拒绝，所以昏迷与死亡者不会触发战斗。 */
    public List<CharacterId> fightTargets(CharacterId actor) {
        return state.consciousBySeat().stream().filter(id -> !id.equals(actor)).toList();
    }

    /**
     * 这一回合执行哪张牌，并把划船堆整堆收回。
     *
     * <p>三种情况，规则只写了前两种：
     * <ol>
     *   <li>划船堆非空且有清醒的舵手 → 他看过全部，挑 1 张（{@code helmsmanPick}）；</li>
     *   <li>划船堆为空 → 翻牌堆顶牌（{@code helmsmanPick} 传 {@code null}）；</li>
     *   <li>❗<b>划船堆非空但全员昏迷</b> → 规则没写。本作<b>翻顶牌</b>：
     *       没有清醒的人，就没有人能查看划船堆。这是我们的裁定，不是规则。</li>
     * </ol>
     *
     * <p>挑中的牌与没挑中的牌一律立刻回到牌堆底部 —— 结算过程中没有人会再抽牌，
     * 所以提前放回不改变任何结果，却让「牌不会凭空消失」在每个出口都成立。
     */
    public NavigationCard takeCardForNavigation(NavigationCard helmsmanPick) {
        NavigationCard chosen;
        if (helmsmanPick == null) {
            chosen = table.pile().draw();
        } else {
            chosen = helmsmanPick;
            if (!table.removeFromRowStack(chosen)) {
                throw new IllegalStateException(
                        "%s 舵手挑了一张不在划船堆里的牌: %s".formatted(context, chosen.id()));
            }
        }
        table.recycleRowStack();
        table.pile().bottom(chosen);
        table.requireNoCardLost(context, "挑牌后");
        return chosen;
    }

    /** 舵手是否真的能挑牌（划船堆非空 且 有清醒的舵手）。 */
    public boolean helmsmanMayPick() {
        return !table.rowStackIsEmpty() && state.helmsman().isPresent();
    }

    /**
     * 航海阶段的结算：<b>海鸥 → 落海 → 口渴</b>，次序固定，不可颠倒。
     *
     * <p>海鸥凑够 4 只当场结束，该牌的落海与口渴一律不再结算。
     * 落海之后若已终局，口渴同样不再结算。
     */
    public NavigationReport navigate(NavigationCard card, WaterChoice water) {
        // a) 海鸥。
        state = state.withGulls(card.gull());
        Invariants.requireValid(state, context, "海鸥结算后");
        if (state.isOver()) {
            return new NavigationReport(card, true, List.of(), List.of(), List.of(), List.of());
        }

        // b) 落海。候选含尸体 —— 死者被冲下去会彻底退出游戏。
        Set<CharacterId> overboardPool = new LinkedHashSet<>(state.bySeat());
        Selector.ConditionResolver noConditions = (c, who) -> false;
        // 分母：这一步真正执行时还活着的人。先记分母再点名，两者必须同一个时刻取。
        List<CharacterId> overboardCandidates = new ArrayList<>();
        for (CharacterId id : overboardPool) {
            if (state.conditionOf(id) != Condition.DEAD) {
                overboardCandidates.add(id);
            }
        }
        List<CharacterId> overboardSelected = new ArrayList<>();
        for (CharacterId id : card.overboard().select(overboardPool, noConditions)) {
            if (state.conditionOf(id) != Condition.DEAD) {
                overboardSelected.add(id);       // 数的是下水，不是受伤：水手落水不受伤
            }
            if (!isOverboardImmune(state, id)) {
                state = state.withState(id, state.stateOf(id).hurt(1));
            }
        }
        Invariants.requireValid(state, context, "落海结算后");
        if (state.isOver()) {
            return new NavigationReport(card, false, overboardCandidates, overboardSelected,
                    List.of(), List.of());
        }

        // c) 口渴。候选不含死者，但含昏迷者 —— 他仍会口渴，只是不能自己打水。
        Set<CharacterId> thirstPool = new LinkedHashSet<>();
        for (CharacterId id : state.bySeat()) {
            if (state.conditionOf(id).suffersThirst()) {
                thirstPool.add(id);
            }
        }
        List<CharacterId> thirstCandidates = List.copyOf(thirstPool);
        List<CharacterId> thirstSelected = new ArrayList<>();
        for (CharacterId id : card.thirst().select(thirstPool, noConditions)) {
            thirstSelected.add(id);              // 只数牌面点名，划船与战斗的口渴不在内
            state = state.withState(id, state.stateOf(id).thirstFrom(ThirstSource.NAMED));
        }

        // 按结算次序逐个算账。带「最后结算」标记的排在最后。
        for (var survivor : ThirstResolver.resolutionOrder(state.roster())) {
            CharacterId id = survivor.id();
            if (!state.conditionOf(id).suffersThirst()) {
                continue;
            }
            // 牌上没有船桨/战斗图示时，对应的标记不产生口渴。
            ThirstTally effective = state.stateOf(id).thirst();
            if (!card.thirstRowers()) {
                effective = withoutSource(effective, ThirstSource.ROWED);
            }
            if (!card.thirstFighters()) {
                effective = withoutSource(effective, ThirstSource.FOUGHT);
            }
            int spent = water.spend(id, effective, state);
            var outcome = ThirstResolver.resolveSpendingUpTo(effective, 0, spent);
            if (outcome.damage() > 0) {
                state = state.withState(id, state.stateOf(id).hurt(outcome.damage()));
            }
        }
        Invariants.requireValid(state, context, "口渴结算后");
        return new NavigationReport(card, false, overboardCandidates, overboardSelected,
                thirstCandidates, thirstSelected);
    }

    /** 还活着几个人。 */
    public int aliveCount() {
        return (int) state.bySeat().stream().filter(id -> state.conditionOf(id) != Condition.DEAD).count();
    }

    private static ThirstTally withoutSource(ThirstTally t, ThirstSource s) {
        if (!t.has(s)) {
            return t;
        }
        Set<ThirstSource> kept = new LinkedHashSet<>(t.sources());
        kept.remove(s);
        return new ThirstTally(kept);
    }

    /** 落海免伤（水手）。❗<b>要求清醒</b> —— 昏迷的水手落水照样受伤。 */
    private static boolean isOverboardImmune(GameState g, CharacterId id) {
        Ability ability = g.roster().get(id).ability();
        if (!(ability instanceof Ability.OverboardImmune oi)) {
            return false;
        }
        return !oi.requiresConscious() || g.conditionOf(id) == Condition.CONSCIOUS;
    }

    /** 划船时对每一张抽到的牌：留进划船堆（true）还是塞回牌堆底部（false）。 */
    @FunctionalInterface
    public interface RowingChoice {
        boolean keep(NavigationCard card, GameState state, CharacterId rower);
    }

    /** 口渴结算时这个人喝几张水。返回值会被 {@code ThirstResolver} 夹到合法范围内。 */
    @FunctionalInterface
    public interface WaterChoice {
        int spend(CharacterId who, ThirstTally effective, GameState state);
    }
}
