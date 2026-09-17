package io.github.heavyseasmc.engine.state;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 一局的完整状态。不可变；推进靠返回新值。
 *
 * <h2>两半配对只在这里发生</h2>
 * 不变的一半是 {@link Roster}（体型、生存分、技能），会变的一半是 {@link SurvivorState}
 * （伤害、座位、标记）。生死状态由两半算出来，<b>全项目只有这一处配对</b> ——
 * 多一处就多一个会漂移的真相源。
 *
 * <h2>座位是查询出来的，不是存起来的</h2>
 * 「最靠船头」「最靠船尾」到处都要用，但本类不缓存排好序的列表：
 * 换座位每回合都可能发生，缓存就要记得失效，而「忘了失效」是一类查起来极慢的 bug。
 * 8 个人的排序在这个规模下不值得为它引入一致性风险。
 */
public final class GameState {

    private final Roster roster;
    private final Map<CharacterId, SurvivorState> states;
    private final Phase phase;
    private final int turn;
    private final int gulls;

    /** 被移出游戏的人（死在水里，连人带牌离场，ADR-0022）。只增不减。 */
    private final Set<CharacterId> removed;

    /** 第几只海鸥到达时靠岸获救。 */
    public static final int GULLS_TO_LAND = 4;

    private GameState(Roster roster, Map<CharacterId, SurvivorState> states,
                      Phase phase, int turn, int gulls, Set<CharacterId> removed) {
        this.roster = roster;
        this.states = Map.copyOf(states);
        this.phase = phase;
        this.turn = turn;
        this.gulls = gulls;
        this.removed = Collections.unmodifiableSet(new LinkedHashSet<>(removed));
    }

    /** 开局：全员未受伤，回合 1，物资阶段，0 只海鸥。 */
    public static GameState start(Roster roster) {
        Objects.requireNonNull(roster, "roster");
        if (roster.survivors().isEmpty()) {
            throw new IllegalArgumentException("阵容不能为空");
        }
        Map<CharacterId, SurvivorState> initial = new LinkedHashMap<>();
        for (Survivor s : roster.survivors()) {
            initial.put(s.id(), SurvivorState.fresh(s.id(), s.seat()));
        }
        return new GameState(roster, initial, Phase.PROVISION, 1, 0, Set.of());
    }

    /** 带天候扩充的开局：第 1 天从翻天候开始。 */
    public static GameState startWithWeather(Roster roster) {
        GameState base = start(roster);
        return new GameState(base.roster, base.states, Phase.WEATHER, base.turn, base.gulls, base.removed);
    }

    public Roster roster() {
        return roster;
    }

    public Phase phase() {
        return phase;
    }

    public int turn() {
        return turn;
    }

    public int gulls() {
        return gulls;
    }

    public SurvivorState stateOf(CharacterId id) {
        SurvivorState s = states.get(id);
        if (s == null) {
            throw new IllegalArgumentException("阵容中没有这个角色: " + id);
        }
        return s;
    }

    /**
     * 生死状态。全项目唯一的「两半配对」处。
     *
     * <p>❗<b>被移出游戏的人一律是 {@link Condition#DEAD}</b> —— 移出只发生在他死在水里的时候（ADR-0022），
     * 而「水里恰好等于体型、没有救生圈」在伤害数上与船上的昏迷一模一样。
     * 这是设计决策 §3.3 的写法：置一个独立标志纳入判定，而不是把伤害凑成「超过体型」让伤害数说谎。
     */
    public Condition conditionOf(CharacterId id) {
        SurvivorState s = stateOf(id);
        if (removed.contains(id)) {
            return Condition.DEAD;
        }
        return Condition.onBoat(s.damage(), roster.get(id).size());
    }

    /**
     * 按座位升序（船头 → 船尾）的全体角色 id —— <b>含被移出游戏的人</b>。
     *
     * <p>终局要翻他们的爱恨、算他们的分，座位条也要显示他们没了；所以「这一局的每个人」仍是这一份。
     * 只指「船上」的地方用 {@link #onBoatBySeat()}（ADR-0022 §5）。
     */
    public List<CharacterId> bySeat() {
        List<SurvivorState> all = new ArrayList<>(states.values());
        all.sort(Comparator.comparingInt(SurvivorState::seat));
        return all.stream().map(SurvivorState::id).toList();
    }

    /** 还在船上的人（按座位）。落海的候选、换座位的对象、绝境要的尸体都只看这一份。 */
    public List<CharacterId> onBoatBySeat() {
        return bySeat().stream().filter(id -> !removed.contains(id)).toList();
    }

    /** 他是不是已经被移出游戏（死在水里，连人带牌离场）。 */
    public boolean isRemoved(CharacterId id) {
        stateOf(id);            // 存在性校验：问一个阵容里没有的人，多半是调用方写错了
        return removed.contains(id);
    }

    /** 按座位升序的清醒角色。物资阶段的抽牌数与传牌次序都用它。 */
    public List<CharacterId> consciousBySeat() {
        return bySeat().stream().filter(id -> conditionOf(id).canAct()).toList();
    }

    /**
     * 行动阶段的下一个行动者：<b>最靠船头且本回合尚未行动</b>的清醒角色。
     *
     * <p>这是设计师修正后的新版顺序 —— 回合内换座位会改变后续顺序。
     * 2001 版是回合开始时固定序。规则基线 §8.2 建议做成可配置开关、默认新版，
     * <b>那个开关属于 M1 的配置层，不在引擎里预留</b>：引擎先把默认行为做对。
     *
     * <p>返回空 = 本阶段已经没人能行动。❗<b>「没人能行动」不是死锁</b>，
     * 是合法状态（全员昏迷时照样要继续抽航海牌，见 §9.6），所以这里返回 Optional 而不是抛。
     */
    public Optional<CharacterId> nextActor() {
        return bySeat().stream()
                .filter(id -> conditionOf(id).canAct())
                .filter(id -> !stateOf(id).actedThisTurn())
                .findFirst();
    }

    /**
     * 舵手：<b>最靠船尾的清醒角色</b>。本作最强的权力位 —— 他决定谁落水、谁口渴、何时靠岸。
     *
     * <p>返回空 = 无人清醒，此时航海阶段直接翻牌堆顶，不经过挑选。
     */
    public Optional<CharacterId> helmsman() {
        List<CharacterId> conscious = consciousBySeat();
        return conscious.isEmpty()
                ? Optional.empty()
                : Optional.of(conscious.get(conscious.size() - 1));
    }

    /** 游戏是否已经结束。 */
    public boolean isOver() {
        return outcome().isPresent();
    }

    /**
     * 终局原因。两个：海鸥凑够 4 只（靠岸获救），或全员死亡。
     *
     * <p>❗<b>只剩一人存活时游戏照常进行</b>，不是终局条件 —— 这条写错会让模拟器
     * 提前收束，掩盖掉后半局的状态机问题。
     */
    public Optional<Outcome> outcome() {
        if (gulls >= GULLS_TO_LAND) {
            return Optional.of(Outcome.LANDED);
        }
        boolean anyAlive = states.keySet().stream().anyMatch(id -> conditionOf(id) != Condition.DEAD);
        return anyAlive ? Optional.empty() : Optional.of(Outcome.ALL_DEAD);
    }

    /** 终局原因。 */
    public enum Outcome {
        /** 第 4 只海鸥出现，陆地在望，当场结束并计分。 */
        LANDED,
        /** 全员死亡。 */
        ALL_DEAD
    }

    // ------------------------------------------------------------------ 推进

    public GameState withState(CharacterId id, SurvivorState next) {
        Objects.requireNonNull(next, "next");
        if (!next.id().equals(id)) {
            throw new IllegalArgumentException("状态的 id 与键不一致: " + id + " vs " + next.id());
        }
        stateOf(id);            // 存在性校验
        Map<CharacterId, SurvivorState> copy = new LinkedHashMap<>(states);
        copy.put(id, next);
        return new GameState(roster, copy, phase, turn, gulls, removed);
    }

    /**
     * 海鸥增减。
     *
     * <p>可以为负 —— 信号枪当信号用时可能翻出「去掉一只海鸥」，让全船倒退一格。
     * 下限钳到 0；上限<b>不钳</b>：凑够 4 只就是终局，多出来的没有意义但也不该报错。
     */
    public GameState withGulls(int delta) {
        return new GameState(roster, states, phase, turn, Math.max(0, gulls + delta), removed);
    }

    /**
     * 把一个人移出游戏。<b>只由落海结算调用</b>（{@code Session}）—— 它负责先把他的牌交给 Table。
     *
     * <p>重复移出同一个人是原样返回，不是错误：同一张牌上他只会被判一次，但判据不该依赖这一点。
     */
    public GameState withRemoved(CharacterId id) {
        stateOf(id);
        if (removed.contains(id)) {
            return this;
        }
        Set<CharacterId> next = new LinkedHashSet<>(removed);
        next.add(id);
        return new GameState(roster, states, phase, turn, gulls, next);
    }

    /**
     * 推进到下一阶段。航海阶段结束时回合数 +1，并清掉全体的划船 / 战斗 / 行动标记。
     *
     * <p>❗<b>终局之后不得再推进。</b> 第 4 只海鸥出现时该张航海牌的落海与口渴一律不再结算，
     * 直接计分；继续推进就等于把已经结束的一局又跑了半个回合。
     */
    public GameState advancePhase() {
        if (isOver()) {
            throw new IllegalStateException(
                    "游戏已结束（%s），不得再推进阶段".formatted(outcome().orElseThrow()));
        }
        Phase next = phase.next();
        if (!phase.endsTurn()) {
            return new GameState(roster, states, next, turn, gulls, removed);
        }
        Map<CharacterId, SurvivorState> cleared = new LinkedHashMap<>();
        states.forEach((id, s) -> cleared.put(id, s.endOfTurn()));
        return new GameState(roster, cleared, next, turn + 1, gulls, removed);
    }
}
