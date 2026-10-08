package io.github.heavyseasmc.engine.play;

import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.Affinities;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.Selector;
import io.github.heavyseasmc.engine.scoring.FinalState;
import io.github.heavyseasmc.engine.scoring.ScoreSheet;
import io.github.heavyseasmc.engine.scoring.Scorer;
import io.github.heavyseasmc.engine.scoring.TreasureScoring;
import io.github.heavyseasmc.engine.scoring.Treasures;
import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.weather.WeatherCard;
import io.github.heavyseasmc.engine.weather.WeatherDeck;
import io.github.heavyseasmc.engine.weather.WeatherEffect;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.engine.state.SurvivorState;
import io.github.heavyseasmc.engine.thirst.ThirstResolver;
import io.github.heavyseasmc.engine.thirst.ThirstSource;
import io.github.heavyseasmc.engine.thirst.ThirstTally;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private boolean weatherDrawnThisTurn;

    /**
     * @param context 出处，报错时靠它定位（模拟器给种子，模组给对局标识）
     */
    public Session(String context, Roster roster, Table table) {
        this.context = Objects.requireNonNull(context, "context");
        this.table = Objects.requireNonNull(table, "table");
        this.state = table.weather().isPresent() ? GameState.startWithWeather(roster) : GameState.start(roster);
        Invariants.requireValid(state, context, "开局");
    }

    /** 副本：见 {@link #copy()}。 */
    private Session(Session o, Table table) {
        this.context = o.context;
        this.table = table;
        this.state = o.state;                                   // 不可变，共用
        this.weatherDrawnThisTurn = o.weatherDrawnThisTurn;
        this.healedSincePhaseStart = o.healedSincePhaseStart;
        this.provisionChain = o.provisionChain;                 // 不可变，共用
        this.provisionOffer.addAll(o.provisionOffer);
        this.provisionAt = o.provisionAt;
        this.provisionRoundsToday = o.provisionRoundsToday;
        this.rower = o.rower;
        this.rowing.addAll(o.rowing);                           // RowCard 不可变
        this.contest = o.contest;                               // 不可变，共用
        o.weaponsPlayed.forEach((who, cards) -> this.weaponsPlayed.put(who, new ArrayList<>(cards)));
        this.lastRationHealed = o.lastRationHealed;
        this.navigatedThisTurn = o.navigatedThisTurn;
        this.standardNavigationTaken = o.standardNavigationTaken;
        this.extraNavigationTaken = o.extraNavigationTaken;
        this.resolvingExtraNavigation = o.resolvingExtraNavigation;
        this.pendingNavigation = o.pendingNavigation == null ? null : o.pendingNavigation.copy();
        this.lastNavigationReport = o.lastNavigationReport;     // 不可变，共用
        this.overboardSequence = o.overboardSequence;
        this.thirstCard = o.thirstCard;
        this.thirstQueue.addAll(o.thirstQueue);
        this.thirstAt = o.thirstAt;
        this.thirstWatersSpent = o.thirstWatersSpent;
        this.thirstWatersByRecipient.putAll(o.thirstWatersByRecipient);
        this.sharedWaterUsed.putAll(o.sharedWaterUsed);
        o.thirstSettledToday.forEach((who, sources) -> this.thirstSettledToday.put(who, new LinkedHashSet<>(sources)));
        this.coverUsedToday.putAll(o.coverUsedToday);
        this.sharedRum.addAll(o.sharedRum);
        this.rowStackPrepared = o.rowStackPrepared;
        this.deeds.addAll(o.deeds);
        this.affinities = o.affinities;                         // 不可变，共用
    }

    /**
     * 一局一模一样、互不相干的副本：此刻的状态、桌面上每一副牌的次序、进行到一半的每一步（补给箱、划船、这一场、
     * 落海、口渴）都原样复制一份，之后在副本上做什么都不动原来那一局。
     *
     * <p>给往前推演用（{@code engine.seat}）：在副本上把一个决定做下去、推几天看结果。
     * ❗副本里<b>什么都看得见</b>（别人的手牌、牌堆次序）—— 它不能交给席位策略；
     * 席位策略往前推演时要的是「照我看得见的、把看不见的重抽一遍」的那一种，只从它自己的视角拼，不从这里复制。
     */
    public Session copy() {
        return new Session(this, table.copy());
    }

    /**
     * 他的抢夺是不是「不问、不打、只偷手牌」—— 小孩那一手（决策 ⑦）。
     *
     * <p>❗提成一个查询，是因为<b>驱动者也要问这一句</b>：指定模式里小孩没有预告（不发光、不播报，
     * ADR-0025）。两处各写一份判据的话，两份迟早分家 —— 而分家的表现是「小孩发起时全船看见了」，
     * 规则上他因此被系统性削弱，却没有任何东西会报错。
     */
    public boolean stealsUncontested(CharacterId who) {
        return state.roster().get(who).ability() instanceof Ability.StealUncontested steal
                && "hand".equals(steal.zone());
    }

    public GameState state() {
        return state;
    }

    public void setOffline(CharacterId who, boolean offline) {
        boolean interruptedAction = offline && state.phase() == Phase.ACTION && contest == null
                && (rower().map(who::equals).orElse(false) || state.nextActor().map(who::equals).orElse(false));
        state = state.withOffline(who, offline);
        if (interruptedAction) {
            if (rower().map(who::equals).orElse(false)) {
                cancelRow();
            }
            state = state.withState(who, state.stateOf(who).markActed());
        }
        // 补给箱在他手上时照旧在他手上：由模组那一侧的超时替他留一张，不跳过、不把余牌放回牌堆（ADR-0051 B1）
        Invariants.requireValid(state, context, "connection changed");
    }

    public Table table() {
        return table;
    }

    public String context() {
        return context;
    }

    /** 推进到下一阶段；未启用天候牌堆的兼容局会自动跳过 WEATHER。 */
    public void advancePhase() {
        requireNoRowInProgress("推进阶段");
        requireNoThirstInProgress("推进阶段");
        requireNoContest("推进阶段");
        int was = state.turn();
        state = state.advancePhase();
        if (state.phase() == Phase.WEATHER && table.weather().isEmpty()) {
            state = state.advancePhase();
        }
        if (state.phase() == Phase.WEATHER) {
            weatherDrawnThisTurn = false;
        }
        navigatedThisTurn = null;
        standardNavigationTaken = false;
        extraNavigationTaken = false;
        resolvingExtraNavigation = false;
        rowStackPrepared = false;
        thirstCard = null;
        thirstQueue.clear();
        thirstAt = 0;
        thirstWatersSpent = 0;
        thirstWatersByRecipient.clear();
        sharedWaterUsed.clear();
        weaponsPlayed.clear();
        healedSincePhaseStart = 0;
        if (state.turn() != was) {
            // 蹭到的酒与「本回合用过」同寿命：一个大回合。后者在 SurvivorState.endOfTurn 里清。
            sharedRum.clear();
            thirstSettledToday.clear();
            coverUsedToday.clear();
            provisionRoundsToday = 0;
        }
        Invariants.requireValid(state, context, "阶段推进后");
    }

    /** 翻开今天的天候；只允许在 WEATHER 阶段调用一次。 */
    public WeatherCard beginWeather() {
        if (state.phase() != Phase.WEATHER) {
            throw new IllegalStateException("%s 天候只在天候阶段翻开，现在是 %s".formatted(context, state.phase()));
        }
        if (weatherDrawnThisTurn) {
            throw new IllegalStateException("%s 第 %d 天的天候已经翻过".formatted(context, state.turn()));
        }
        var deck = table.weather().orElseThrow(() -> new IllegalStateException(context + " 没有天候牌堆"));
        WeatherCard card = deck.draw();
        weatherDrawnThisTurn = true;
        if (card.effect() == WeatherEffect.RESHUFFLE_DISCARD) {
            deck.reshuffleDiscard();
        }
        return card;
    }

    public Optional<WeatherCard> currentWeather() {
        return table.weather().flatMap(io.github.heavyseasmc.engine.weather.WeatherDeck::current);
    }

    /** 物资的效果目录。 */
    public Provisions provisions() {
        return table.provisions();
    }

    /**
     * 本阶段治了几点伤。
     *
     * <p>❗给 {@link Invariants#checkTransition} 用。那条不变量原本是「伤害只增不减」，
     * 而医疗箱与绝境真的会减 —— 直接删掉它的话，「复活」与「治疗」从此再也分不开。
     * 所以改成「只能由治疗减少，且减的不超过治了几点」，判据需要这个数。
     */
    public int healedSincePhaseStart() {
        return healedSincePhaseStart;
    }

    private int healedSincePhaseStart;

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
        provisionRoundsToday++;
        provisionChain = List.copyOf(state.consciousBySeat());
        provisionOffer.clear();
        provisionAt = 0;
        if (provisionChain.isEmpty()) {
            return List.of();
        }
        provisionOffer.addAll(table.drawProvisions(provisionDraws()));
        return List.copyOf(provisionOffer);
    }

    /** 今天开过几轮补给箱（礼拜天会开两轮）。换天时归零。 */
    private int provisionRoundsToday;

    /**
     * 今天已经开过几轮补给箱。<b>公开信息</b>。
     *
     * <p>礼拜天要「传完一轮再来一整轮」，而「第二轮开过没有」原先只记在驱动者那一侧（模组的组件里）。
     * 从半路接手一局的驱动者（席位策略往前推演时）就答不出这一句 —— 所以记在规则这一侧。
     */
    public int provisionRoundsToday() {
        return provisionRoundsToday;
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
        // ❗链上掉线的人不跳过（ADR-0051 B1，取代 2adbbe4 那一版）：跳过到链尾时余牌要放回牌堆底，
        //   而规则 §8.1 是「抽完即止，不洗回」—— 前面看过牌的人会知道底牌，牌堆本已空时下一回合的物资阶段还会复活。
        //   掉线的持箱人由模组那一侧的超时替他留一张（决策 ⑧「局面不能卡住」）；开局时就掉线的人本来就不进链。
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
        requireNoRowInProgress("记下行动");
        requireNoContest("记下行动");
        state = state.withState(actor, state.stateOf(actor).markActed());
        Invariants.requireValid(state, context, "行动后");
    }

    // ---------------------------------------------------------------- 划船

    /** 划船抽到的一张牌的去向。 */
    public enum RowFate {
        /** 还没定。 */
        UNDECIDED,
        /** 留进划船堆。 */
        KEPT,
        /** 塞回牌堆底部。 */
        RETURNED
    }

    /**
     * 划船抽到的一张，和它的去向。
     *
     * @param card 抽到的牌
     * @param fate 去向；还没定时为 {@link RowFate#UNDECIDED}
     */
    public record RowCard(NavigationCard card, RowFate fate) {

        public RowCard {
            Objects.requireNonNull(card, "card");
            Objects.requireNonNull(fate, "fate");
        }
    }

    /** 正在划船的人；{@code null} 表示没人在划（没开始，或者都定完了）。 */
    private CharacterId rower;

    /** 这一次抽到的牌，按抽出顺序。下标在选择过程中不变 —— 界面按下标说「第几张」。 */
    private final List<RowCard> rowing = new ArrayList<>();

    /**
     * 划船：抽 2 张看过，选 1 张放进划船堆，其余塞回牌堆底部，然后领一个划船标记。
     *
     * <p>❗「看过再决定」是本作信息结构的核心：划船堆面朝下，划船者只知道自己放了什么，
     * 舵手知道全部，其他人只看得到有几个人划了船。舵手因此能从别人挑剩的里面再挑一次 ——
     * 落水/口渴的实际分布被这两道挑选<b>连挑两次</b>，与牌面上印的张数分布不是一回事。
     *
     * <p>这是 {@link #beginRow} 加 {@link #chooseRow} 的简写。选择器看到完整的一组牌，只返回其中一张的下标。
     * 模拟器与 {@code /seas row} 走这里，界面走两步 —— 规则只有两步那一份。
     *
     * <p>牌堆不够 2 张时有几张抽几张。
     *
     * @return 这次实际抽到的牌，按抽出顺序
     */
    public List<NavigationCard> row(CharacterId rower, RowingChoice choice) {
        List<NavigationCard> drawn = beginRow(rower);
        if (!drawn.isEmpty()) {
            chooseRow(choice.choose(drawn, state, rower));
        }
        return drawn;
    }

    /**
     * 划船第一步：抽 2 张到划船者手上。
     *
     * <h2>为什么拆成两步</h2>
     * 规则写的是「抽 2 张查看，选择 1 张」—— 两张必须先摆在面前，才能进行这一组里的选择。
     * 原先一次调用里边抽边决定，第一张塞回牌堆底之后才抽第二张：牌堆只剩一张时，第二张抽到的就是刚塞回去的那张。
     * 真人对局里决定要等人想，两张必须先摆在他面前。
     *
     * <p>抽出来的牌在做出选择之前待在 {@link Table#rowerHand()}：既不在牌堆里，也不在划船堆里，对账照样算得清。
     *
     * <p>面前亮着船桨的人多抽（{@code weapon_and_row_bonus}，可叠加）—— 手上的船桨不算，
     * 被动效果一律「在面前才算」（ADR-0021 决策 4）。
     *
     * @return 抽到的牌，按抽出顺序。牌堆不够时有几张抽几张；<b>一张都没抽到时这次划船当场结束</b>（照样领划船标记）
     * @throws IllegalStateException 不在行动阶段，或者上一次划船抽到的牌还没选完
     */
    public List<NavigationCard> beginRow(CharacterId rower) {
        if (currentWeatherEffect() == WeatherEffect.SKIP_NAVIGATION) {
            throw new IllegalStateException(context + " 风平浪静时没有航海阶段，不能划船");
        }
        Objects.requireNonNull(rower, "rower");
        if (state.phase() != Phase.ACTION) {
            throw new IllegalStateException("%s 划船是行动阶段的事，现在是 %s".formatted(context, state.phase()));
        }
        requireNoRowInProgress("再划一次船");
        requireNoContest("划船");
        state.stateOf(rower);                          // 阵容里没有这个人就在这里抛，别等到领标记时才抛
        List<NavigationCard> drawn = new ArrayList<>();
        int draws = CARDS_DRAWN_WHEN_ROWING + rowExtraDraw(rower);
        for (int i = 0; i < draws && !table.pile().isEmpty(); i++) {
            NavigationCard card = table.pile().draw();
            table.takeIntoRowerHand(card);
            drawn.add(card);
        }
        table.requireNoCardLost(context, "划船抽牌后");
        this.rower = rower;
        drawn.forEach(card -> rowing.add(new RowCard(card, RowFate.UNDECIDED)));
        if (drawn.isEmpty()) {
            finishRow();
        }
        return List.copyOf(drawn);
    }

    /**
     * 划船第二步：选择第 {@code index} 张（按抽出顺序，从 0 起）放进划船堆，其余全部塞回牌堆底。
     *
     * <p>选择一落定，整组牌就同时归位，随后领划船标记并结束这次划船。划船堆有几张仍是公开信息（决策 ⑭）。
     *
     * @return 自动塞回牌堆底的张数
     * @throws IllegalStateException    没有人在划船，或者这组牌已经做过选择
     * @throws IllegalArgumentException 没有这一张
     */
    public int chooseRow(int index) {
        if (rower == null) {
            throw new IllegalStateException("%s 现在没有人在划船".formatted(context));
        }
        if (index < 0 || index >= rowing.size()) {
            throw new IllegalArgumentException("%s %s 划船抽了 %d 张，没有第 %d 张"
                    .formatted(context, rower.value(), rowing.size(), index + 1));
        }
        if (rowing.stream().anyMatch(row -> row.fate() != RowFate.UNDECIDED)) {
            throw new IllegalStateException("%s %s 划船的这组牌已经做过选择".formatted(context, rower.value()));
        }
        int returned = 0;
        for (int i = 0; i < rowing.size(); i++) {
            moveRowCard(i, i == index ? RowFate.KEPT : RowFate.RETURNED);
            if (i != index) {
                returned++;
            }
        }
        table.requireNoCardLost(context, "划船选择后");
        finishRow();
        return returned;
    }

    /** 超时或取消选择：这一组牌全部塞回牌堆底，照样结束划船并领取标记。 */
    public int cancelRow() {
        if (rower == null) {
            throw new IllegalStateException("%s 现在没有人在划船".formatted(context));
        }
        int returned = 0;
        for (int i = 0; i < rowing.size(); i++) {
            if (rowing.get(i).fate() == RowFate.UNDECIDED) {
                moveRowCard(i, RowFate.RETURNED);
                returned++;
            }
        }
        table.requireNoCardLost(context, "划船取消选择后");
        finishRow();
        return returned;
    }

    private void moveRowCard(int index, RowFate fate) {
        RowCard row = rowing.get(index);
        if (row.fate() != RowFate.UNDECIDED) {
            throw new IllegalStateException("%s %s 划船的第 %d 张已经定过了（%s）"
                    .formatted(context, rower.value(), index + 1, row.fate()));
        }
        if (!table.releaseFromRowerHand(row.card())) {
            throw new IllegalStateException("%s %s 划船的第 %d 张不在他手上：%s"
                    .formatted(context, rower.value(), index + 1, row.card().id()));
        }
        if (fate == RowFate.KEPT) {
            table.addToRowStack(row.card());
        } else {
            table.pile().bottom(row.card());
        }
        rowing.set(index, new RowCard(row.card(), fate));
    }

    /** 谁正在划船（抽到的牌还没选完）。 */
    public Optional<CharacterId> rower() {
        return Optional.ofNullable(rower);
    }

    /** 这一次划船抽到的牌与各自的去向；没有人在划船时为空。❗<b>只有划船者看得到</b> —— 这是规则，不是显示差异。 */
    public List<RowCard> rowing() {
        return List.copyOf(rowing);
    }

    /** 全部定完：领划船标记，清掉这一次。 */
    private void finishRow() {
        state = state.withState(rower, state.stateOf(rower).thirstFrom(ThirstSource.ROWED));
        rower = null;
        rowing.clear();
    }

    /**
     * 划船抽到的牌还在某人手上时，不许做别的事。
     *
     * <p>❗那几张牌既不在牌堆也不在划船堆：此时推进阶段、记下行动、换座、打架，都等于把它们带过了那一步。
     * 对账会在稍后某处报「牌对不上」，而那时已经看不出是哪一步漏了 —— 所以在这里当场点名。
     */
    private void requireNoRowInProgress(String what) {
        if (rower != null) {
            throw new IllegalStateException("%s %s 划船抽到的牌还没选完，不能%s"
                    .formatted(context, rower.value(), what));
        }
    }

    // ---------------------------------------------------------------- 换座位与抢夺：拒绝才开打（ADR-0023）

    /** 进行中的这一场；没有时为 {@code null}。 */
    private Contest contest;

    /** 进行中的换座位或抢夺；没有时为空。❗其中押下的武器是暗牌，投影只能发给押的人自己。 */
    public Optional<Contest> contest() {
        return Optional.ofNullable(contest);
    }

    /**
     * 宣告换座位或抢夺。这一下就用掉了进攻方的行动，无论后面怎么收场（规则 §9.1）。
     *
     * <p>目标能不能拒绝由规则直接决定，不问任何人：
     * <ul>
     *   <li>目标不清醒（昏迷 · 死亡）→ 视为同意：换座位当场生效，抢夺直接进挑牌；</li>
     *   <li>小孩的偷窃（{@link Ability.StealUncontested}，只偷手牌）→ 不可拒绝，直接进挑牌（决策 ⑦）；</li>
     *   <li>其余 → 等目标表态（{@link #consent}）。</li>
     * </ul>
     *
     * @throws IllegalStateException    不在行动阶段、没轮到他、上一场还没收场，或者划船抽到的牌还没选完
     * @throws IllegalArgumentException 对自己，或者目标已经被移出游戏
     */
    public void declare(CharacterId actor, Contest.Kind kind, CharacterId target) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(target, "target");
        if (kind == Contest.Kind.RATION) {
            throw new IllegalArgumentException("绝境要带着牌走 beginRation，不能当普通指定动作宣告");
        }
        if (state.phase() != Phase.ACTION) {
            throw new IllegalStateException("%s %s是行动阶段的事，现在是 %s"
                    .formatted(context, kindName(kind), state.phase()));
        }
        requireNoRowInProgress("宣告" + kindName(kind));
        requireNoContest("再宣告一次");
        if (!state.nextActor().map(actor::equals).orElse(false)) {
            throw new IllegalStateException("%s 现在轮到的不是 %s（是 %s）".formatted(context, actor.value(),
                    state.nextActor().map(CharacterId::value).orElse("没有人")));
        }
        if (actor.equals(target)) {
            throw new IllegalArgumentException("%s %s 不能对自己%s".formatted(context, actor.value(), kindName(kind)));
        }
        if (state.isRemoved(target)) {
            throw new IllegalArgumentException("%s %s 已经被移出游戏，不能对他%s"
                    .formatted(context, target.value(), kindName(kind)));
        }
        boolean handOnly = kind == Contest.Kind.STEAL && stealsUncontested(actor);
        record(kind == Contest.Kind.STEAL ? Deed.Kind.STEAL_DECLARED : Deed.Kind.SWAP_DECLARED, actor, target, 1);
        contest = new Contest(kind, actor, target, Contest.Stage.CONSENT, Optional.empty(), handOnly, Map.of(),
                "", Set.of());
        if (handOnly || !state.canAct(target)) {
            agreed();
        }
    }

    /**
     * 被指定的人表态。
     *
     * @param fight true = 喊「战斗」；false = 同意
     * @throws IllegalStateException 现在不是等表态的时候
     */
    public void consent(boolean fight) {
        Contest c = requireStage(Contest.Stage.CONSENT, "表态");
        if (fight) {
            requireCanAct(c.target());
            record(c.kind() == Contest.Kind.RATION ? Deed.Kind.OBJECTED : Deed.Kind.REFUSED, c.target(), c.attacker(), 1);
        } else if (c.kind() != Contest.Kind.RATION) {
            record(Deed.Kind.CONCEDED, c.target(), c.attacker(), 1);
        }
        if (!fight) {
            if (c.kind() == Contest.Kind.RATION) {
                Set<CharacterId> passed = new LinkedHashSet<>(c.passed());
                passed.add(c.target());
                Optional<CharacterId> next = nextRationOpponent(c.attacker(), passed);
                if (next.isPresent()) {
                    contest = c.passAndAsk(c.target(), next.get());
                } else {
                    contest = null;
                    applyRation(c.attacker(), c.provision());
                }
                return;
            }
            agreed();
            return;
        }
        contest = c.advance(Contest.Stage.STANCES, Optional.of(Fight.between(c.attacker(), c.target())));
    }

    /**
     * 站队：加入进攻方或防守方。加入后不可退出（{@link Fight#join} 没有退出这回事）。
     *
     * @throws IllegalStateException    现在不是站队段
     * @throws IllegalArgumentException 他不清醒、已经被移出游戏，或者已经在场上
     */
    public void join(CharacterId who, Fight.Side side) {
        Contest c = requireStage(Contest.Stage.STANCES, "站队");
        if (!state.canAct(who)) {
            throw new IllegalArgumentException("%s %s 不清醒，不能加入战斗（规则 §9.3）".formatted(context, who.value()));
        }
        Fight fight = c.fight().orElseThrow();
        contest = c.withFight(fight.join(who, side));
        // 站队全船看得见（规则 §8）：记下他站在谁那边、与谁为敌。主将就是发起的人与被指的人。
        CharacterId with = side == Fight.Side.ATTACK ? fight.attacker() : fight.defender();
        CharacterId against = side == Fight.Side.ATTACK ? fight.defender() : fight.attacker();
        record(Deed.Kind.SIDED_WITH, who, with, 1);
        record(Deed.Kind.SIDED_AGAINST, who, against, 1);
    }

    /** 站队段结束，进挂武器段。 */
    public void closeStances() {
        Contest c = requireStage(Contest.Stage.STANCES, "结束站队");
        contest = c.advance(Contest.Stage.WEAPONS, c.fight());
    }

    /**
     * 挂武器段里押下一张武器。❗<b>暗牌</b>：只登记，不挪牌、不亮出 —— 结算那一刻才亮（决策 ④）。
     *
     * <p>同一个 id 最多押「手上 + 面前」那么多张（两支船桨能押两次）。
     *
     * @throws IllegalStateException    现在不是挂武器段
     * @throws IllegalArgumentException 他没参战、这张不是武器，或者他没有那么多张
     */
    public void commitWeapon(CharacterId who, String cardId) {
        Contest c = requireStage(Contest.Stage.WEAPONS, "押武器");
        requireCanAct(who);
        if (!c.fight().orElseThrow().combatants().contains(who)) {
            throw new IllegalArgumentException("%s %s 没有参战，不能押武器（决策 ④：只开放给参战双方）"
                    .formatted(context, who.value()));
        }
        if (table.provisions().get(cardId).weaponPower() <= 0) {
            throw new IllegalArgumentException("%s %s 不是武器".formatted(context, cardId));
        }
        SurvivorState s = state.stateOf(who);
        long already = c.committedBy(who).stream().filter(cardId::equals).count();
        long owned = s.countInHand(cardId) + s.front().stream().filter(cardId::equals).count();
        if (already >= owned) {
            throw new IllegalArgumentException("%s %s 手上与面前一共 %d 张 %s，已经押了 %d 张"
                    .formatted(context, who.value(), owned, cardId, already));
        }
        contest = c.withCommitted(who, cardId);
    }

    /**
     * 挂武器段结束，结算这一场：押下的牌此刻才亮出、加上战力；胜负照 {@link Fight#resolve}，伤害与战斗标记照 {@link #applyFight}。
     *
     * <p>❗亮出时<b>先用面前已有的</b>，不够才从手上亮：面前本来就有一支船桨的人押一支，手里那支不该被翻出来。
     *
     * <p>进攻方胜 → 换座位当场生效；抢夺进挑牌（被抢方身上没有能挑的牌时直接收场）。防守方胜 → 这一场结束。
     *
     * @return 这一场的结果
     */
    public Fight.Outcome resolveContest() {
        Contest c = requireStage(Contest.Stage.WEAPONS, "结算");
        Fight fight = c.fight().orElseThrow();
        contest = null;                    // 下面走的是这一场之外的结算入口，它们的守卫要看到这一场已经收起
        for (Map.Entry<CharacterId, List<String>> entry : c.committed().entrySet()) {
            CharacterId who = entry.getKey();
            Map<String, Integer> wanted = new LinkedHashMap<>();
            entry.getValue().forEach(id -> wanted.merge(id, 1, Integer::sum));
            for (Map.Entry<String, Integer> w : wanted.entrySet()) {
                String cardId = w.getKey();
                long shown = state.stateOf(who).front().stream().filter(cardId::equals).count();
                for (long i = shown; i < w.getValue(); i++) {
                    state = state.withState(who, state.stateOf(who).reveal(cardId));
                }
                for (int i = 0; i < w.getValue(); i++) {
                    weaponsPlayed.computeIfAbsent(who, k -> new ArrayList<>()).add(cardId);
                    fight = fight.arm(who, table.provisions().get(cardId).weaponPower());
                }
            }
        }
        Fight.Outcome outcome = applyFight(fight);
        if (outcome.attackerGetsWhatTheyWanted()) {
            switch (c.kind()) {
                case SWAP -> swapSeats(c.attacker(), c.target());
                case STEAL -> enterPick(c);
                case RATION -> applyRation(c.attacker(), c.provision());
            }
        }
        return outcome;
    }

    /**
     * 挑牌：拿被抢方面前亮出的一张，进抢夺方<b>面前</b>（亮出的牌换了主人照样亮着，与赠送同一条）。
     *
     * @throws IllegalStateException    现在不是挑牌的时候，或者这是小孩的偷窃（只能挑手牌）
     * @throws IllegalArgumentException 被抢方面前没有这张
     */
    public void pickFromFront(String cardId) {
        Contest c = requireStage(Contest.Stage.PICK, "挑面前的牌");
        if (c.handOnly()) {
            throw new IllegalStateException("%s %s 的偷窃只能拿手牌（决策 ⑦）".formatted(context, c.attacker().value()));
        }
        SurvivorState victim = state.stateOf(c.target());
        if (!victim.hasInFront(cardId)) {
            throw new IllegalArgumentException("%s %s 面前没有 %s".formatted(context, c.target().value(), cardId));
        }
        state = state.withState(c.target(), victim.withoutCardInFront(cardId));
        state = state.withState(c.attacker(), state.stateOf(c.attacker()).withCardInFront(cardId));
        contest = null;
        record(Deed.Kind.TOOK, c.attacker(), c.target(), 1);
        requireNoProvisionLost("抢到面前的一张之后");
    }

    /**
     * 挑牌：从被抢方手牌里<b>随机</b>抽一张，进抢夺方手牌。
     *
     * <p>❗下标由调用方给，而且<b>必须是均匀随机的</b>：{@code Session} 不持有随机源（爱恨也是驱动者发的）。
     * 界面上不能让抢夺方看着牌挑 —— 手牌是暗的，这里只收一个下标。
     *
     * @param index 被抢方手牌的下标，从 0 起
     */
    public void pickFromHand(int index) {
        Contest c = requireStage(Contest.Stage.PICK, "挑手牌");
        SurvivorState victim = state.stateOf(c.target());
        if (index < 0 || index >= victim.hand().size()) {
            throw new IllegalArgumentException("%s %s 手上有 %d 张，没有第 %d 张"
                    .formatted(context, c.target().value(), victim.hand().size(), index + 1));
        }
        String cardId = victim.hand().get(index);
        state = state.withState(c.target(), victim.withoutCard(cardId));
        state = state.withState(c.attacker(), state.stateOf(c.attacker()).withCard(cardId));
        contest = null;
        record(Deed.Kind.TOOK, c.attacker(), c.target(), 1);
        requireNoProvisionLost("抢到手牌里的一张之后");
    }

    /** 同意（或规则上视为同意）：换座位当场生效，抢夺进挑牌。 */
    private void agreed() {
        Contest c = contest;
        contest = null;
        switch (c.kind()) {
            case SWAP -> swapSeats(c.attacker(), c.target());
            case STEAL -> enterPick(c);
            case RATION -> throw new IllegalStateException("绝境要逐个询问反对者，不能走普通同意分支");
        }
    }

    /** 进挑牌；被抢方身上（小孩的偷窃：手上）一张能挑的都没有时，这一场直接收场。 */
    private void enterPick(Contest c) {
        contest = somethingToPick(c) ? c.advance(Contest.Stage.PICK, Optional.empty()) : null;
    }

    /**
     * 挑牌那一刻，被抢方身上已经一张能挑的都没有了，就照 {@link #enterPick} 的「直接收场」收掉这一场。
     *
     * <p>❗<b>兜底，不是正路</b>（审查 2026-10-08 C1）：被抢方在挑牌那一刻本来什么都动不了（亮牌、喝酒都拦着，
     * 见 {@link #reveal}、{@link #drinkRum}），所以按规则走不到这里。留着它是给驱动者的超时默认挑牌先问一句：
     * 万一哪天又多出一条把牌挪走的路，这一场也收得了，而不是在 {@link #pickFromFront} 里抛、整局卡在挑牌上。
     *
     * @return 收了场为 true；现在不在挑牌、或者还有能挑的牌时什么也不做，为 false
     */
    public boolean endPickIfNothingToTake() {
        if (contest == null || contest.stage() != Contest.Stage.PICK || somethingToPick(contest)) {
            return false;
        }
        contest = null;
        return true;
    }

    /** 这一场的抢夺此刻还有没有能挑的：手牌，外加（不是小孩的偷窃时）面前的牌。 */
    private boolean somethingToPick(Contest c) {
        SurvivorState victim = state.stateOf(c.target());
        return !victim.hand().isEmpty() || (!c.handOnly() && !victim.front().isEmpty());
    }

    /** 他正在挨抢、到了挑牌那一刻：这一刻他身上的牌不许动（规则书第五章：被抢的人到了挑牌那一刻不能再亮牌）。 */
    private boolean beingPickedFrom(CharacterId who) {
        return contest != null && contest.stage() == Contest.Stage.PICK && contest.target().equals(who);
    }

    private Contest requireStage(Contest.Stage stage, String what) {
        if (contest == null) {
            throw new IllegalStateException("%s 现在没有进行中的换座位或抢夺，不能%s".formatted(context, what));
        }
        if (contest.stage() != stage) {
            throw new IllegalStateException("%s 这一场在 %s，不能%s".formatted(context, contest.stage(), what));
        }
        return contest;
    }

    /**
     * 换座位或抢夺还没收场时，不许做别的事（ADR-0023 §7.4 · §7.7）。
     *
     * <p>❗「战斗结束前任何卡不得易手」由这里守：交易、特殊行动、记下行动、推进阶段、划船都要先等这一场收场 ——
     * 漏了收尾当场就红，不会让进攻方打完之后又轮到一次。
     */
    private void requireNoContest(String what) {
        if (contest != null) {
            throw new IllegalStateException("%s %s 对 %s 的%s还没收场（%s），不能%s".formatted(context,
                    contest.attacker().value(), contest.target().value(), kindName(contest.kind()),
                    contest.stage(), what));
        }
    }

    private static String kindName(Contest.Kind kind) {
        return switch (kind) {
            case SWAP -> "换座位";
            case STEAL -> "抢夺";
            case RATION -> "绝境反对";
        };
    }

    /**
     * 直接换座位，不问对方。
     *
     * <p>❗<b>这不是玩家路径</b>：规则上换座位要走 {@link #declare}（目标清醒就得问他）。留着它是给宣告之后的生效与测试夹具用 —— 这一场进行中时它照样抛。
     */
    public void swapSeats(CharacterId actor, CharacterId target) {
        requireNoRowInProgress("换座位");
        requireNoContest("直接换座位");
        if (state.isRemoved(actor) || state.isRemoved(target)) {
            // 规则允许与船上的尸体换座位；被冲走的人连座位牌一起退出了游戏，没有座位可换。
            throw new IllegalArgumentException("%s 被移出游戏的人没有座位可换（%s ↔ %s）"
                    .formatted(context, actor.value(), target.value()));
        }
        int a = state.stateOf(actor).seat();
        int b = state.stateOf(target).seat();
        state = state.withState(actor, state.stateOf(actor).withSeat(b))
                .withState(target, state.stateOf(target).withSeat(a));
        record(Deed.Kind.SWAPPED, actor, target, 1);
    }

    /**
     * 结算一场已经组好的战斗：判胜负、扣伤、给全部参战者发战斗标记。
     *
     * <p>❗战斗标记发给<b>全部参战者含助拳者</b>；同回合多次参战也只算一个（集合幂等）。
     *
     * @return 谁输了、每人扣几点
     */
    public Fight.Outcome applyFight(Fight fight) {
        requireNoRowInProgress("结算战斗");
        requireNoContest("直接结算一场战斗");
        // 体型按满值算，并加上本回合喝过的酒（{@code buff_size}，规则 §11.2：喝下后整个大回合有效）。
        Fight.Outcome outcome = fight.resolve(this::fightingSize);
        GameState next = state;
        CharacterId winner = outcome.winner() == Fight.Side.ATTACK ? fight.attacker() : fight.defender();
        for (CharacterId loser : outcome.losers()) {
            next = next.withState(loser, next.stateOf(loser).hurt(outcome.damagePerLoser()));
            record(Deed.Kind.BEAT, winner, loser, outcome.damagePerLoser());
        }
        for (CharacterId c : fight.combatants()) {
            next = next.withState(c, next.stateOf(c).thirstFrom(ThirstSource.FOUGHT));
        }
        state = next;
        // 这一场真的打出去的武器里，用后即弃的（信号枪）到此弃掉。
        // ❗只弃「这一场打出去的」：亮在面前而本场没用的不弃（规则 §9.1：亮出后也可以选择本场不使用）。
        weaponsPlayed.forEach((who, cards) -> {
            for (String cardId : cards) {
                if (table.provisions().get(cardId).effect().discardOnUse()) {
                    state = state.withState(who, state.stateOf(who).withoutCardInFront(cardId));
                    table.discardProvision(cardId);
                }
            }
        });
        weaponsPlayed.clear();
        requireNoProvisionLost("战斗结算后");
        return outcome;
    }

    /**
     * 战斗时算几点体型：满体型 + 本回合喝过的酒。
     *
     * <p>❗<b>与伤害无关</b> —— 「攻击力 = 剩余血量」是村规，{@link Fight} 连伤害都看不到。
     *
     * <p>公开给投影用（模组站队一面的两边体型和，ADR-0095 A4）：喝酒是公开的，界面上那个数要与结算同源 ——
     * 原先投影只加体型，有人喝了酒时屏幕上的数是错的。押下的武器不在这里（暗牌），由结算另加。
     */
    public int fightingSize(CharacterId id) {
        int size = state.roster().get(id).size();
        SurvivorState s = state.stateOf(id);
        // ❗按他今天喝过什么算，不按面前有什么算（审查 2026-10-08 R2）：规则书「这一边每个人的体型 + 今天喝的朗姆酒」，
        //   喝下去就是这一天的事，瓶子送人、被抢、被浪卷走都不收回。原先只数面前还在的那几瓶，瓶子一走 +3 就没了。
        for (String cardId : s.usedThisTurn()) {
            Provision card = table.provisions().get(cardId);
            if (card.effect() instanceof ProvisionEffect.BuffSize buff) {
                // stacks=false：同一种喝两瓶也只算一次（集合已经去重）。
                // stacks=true 的数据目前没有；照旧按面前的瓶数乘，瓶子不在了至少还是喝下去的那一份。
                size += buff.stacks() ? buff.amount() * Math.max(1, countInFront(s, cardId)) : buff.amount();
            }
        }
        return size + sharedRumBonus(id);
    }

    /**
     * 战斗中打出一张武器：从手上或面前拿出来，亮在面前，并把加值挂到这一场上。
     *
     * <p>武器打出即亮出（规则 §9.1：武器保持亮出状态，落水即失），所以手上那张会挪到面前。
     * 信号枪（{@code discard_after_any_use}）在 {@link #applyFight} 之后弃掉 ——
     * <b>这就是要记住「这一场打了哪几张」的原因</b>：亮在面前而本场没用的信号枪不该被弃。
     *
     * @return 加上这张之后的战斗（{@link Fight} 不可变）
     * @throws IllegalArgumentException 这张不是武器，或者他手上与面前都没有
     */
    public Fight playWeapon(Fight fight, CharacterId who, String cardId) {
        Objects.requireNonNull(fight, "fight");
        requireNoContest("在这一场之外打武器");
        Provision card = table.provisions().get(cardId);
        if (card.weaponPower() <= 0) {
            throw new IllegalArgumentException("%s 不是武器，打不出加值".formatted(cardId));
        }
        if (!fight.combatants().contains(who)) {
            throw new IllegalArgumentException("%s 没有参战，不能打武器".formatted(who.value()));
        }
        SurvivorState s = state.stateOf(who);
        if (s.hasInHand(cardId)) {
            state = state.withState(who, s.reveal(cardId));
        } else if (!s.hasInFront(cardId)) {
            throw new IllegalArgumentException("%s 手上与面前都没有 %s".formatted(who.value(), cardId));
        }
        weaponsPlayed.computeIfAbsent(who, k -> new ArrayList<>()).add(cardId);
        return fight.arm(who, card.weaponPower());     // arm 本身就是累加，多张武器各调一次
    }

    /** 这一场战斗里，每个人已经打出去的武器。{@link #applyFight} 之后清空。 */
    private final Map<CharacterId, List<String>> weaponsPlayed = new LinkedHashMap<>();

    /** Number of survivors healed by the most recent ration resolution. */
    private int lastRationHealed;

    public int lastRationHealed() {
        return lastRationHealed;
    }

    /**
     * 他现在能凑出多少战斗加值（手上 + 面前的武器之和）。
     *
     * <p>❗<b>手上的也算</b>：武器可以在战斗中随时打出，打出即亮出。这与被动效果那条不矛盾 ——
     * 武器不是被动生效的，是打出去才生效的。
     */
    public int weaponPowerAvailable(CharacterId who) {
        SurvivorState s = state.stateOf(who);
        int power = 0;
        for (String cardId : s.hand()) {
            power += table.provisions().get(cardId).weaponPower();
        }
        for (String cardId : s.front()) {
            power += table.provisions().get(cardId).weaponPower();
        }
        return power;
    }

    /** 能被抢夺/攻击的对象：<b>清醒</b>的人才能拒绝，所以昏迷与死亡者不会触发战斗。 */
    public List<CharacterId> fightTargets(CharacterId actor) {
        return state.consciousBySeat().stream().filter(id -> !id.equals(actor)).toList();
    }

    // ---------------------------------------------------------------- 航海阶段

    /** 这一回合执行的那张航海牌；还没结算时为 {@code null}。推进阶段时清掉。 */
    private NavigationCard navigatedThisTurn;
    private boolean standardNavigationTaken;
    private boolean extraNavigationTaken;
    private boolean resolvingExtraNavigation;

    /**
     * 这一回合执行的航海牌，并把划船堆整堆收回。
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
     *
     * @throws IllegalStateException 不在航海阶段，或者这一回合已经结算过一张 ——
     *                               模组里超时与指令可能前后脚到，第二下若也照做，一回合就执行了两张牌；
     *                               或者这一回合还没 {@link #prepareRowStack}
     */
    public NavigationCard takeCardForNavigation(NavigationCard helmsmanPick) {
        requireNoThirstInProgress("take navigation card");
        if (state.phase() != Phase.NAVIGATION) {
            throw new IllegalStateException("%s 航海牌只在航海阶段结算，现在是 %s".formatted(context, state.phase()));
        }
        if (standardNavigationTaken) {
            throw new IllegalStateException("%s 第 %d 回合已经结算过航海牌 %s —— 每回合只执行一张"
                    .formatted(context, state.turn(), navigatedThisTurn == null ? "<跳过>" : navigatedThisTurn.id()));
        }
        if (!rowStackPrepared) {
            // ❗漏调 prepareRowStack 的表现不是报错，是指南针永远不生效。2026-09-16 核对时发现
            //   模组的航海阶段一次都没调过 —— 而单测、出口验收、真人实拍全都是绿的。所以在这里当场点名。
            throw new IllegalStateException(
                    "%s 第 %d 回合结算航海牌之前没有先 prepareRowStack（舵手持有指南针时要多抽的那一张）—— 驱动者漏了一步"
                            .formatted(context, state.turn()));
        }
        NavigationCard chosen;
        if (helmsmanPick == null) {
            chosen = table.pile().draw();
        } else {
            chosen = helmsmanPick;
            int stack = table.rowStack().size();
            if (!table.removeFromRowStack(chosen)) {
                throw new IllegalStateException(
                        "%s 舵手挑了一张不在划船堆里的牌: %s".formatted(context, chosen.id()));
            }
            recordSteering(chosen, stack);
        }
        table.recycleRowStack();
        table.pile().bottom(chosen);
        table.requireNoCardLost(context, "挑牌后");
        navigatedThisTurn = chosen;
        standardNavigationTaken = true;
        resolvingExtraNavigation = false;
        return chosen;
    }

    /** 狂风：标准航海之前从牌堆顶额外翻一张并结算，不动划船堆。 */
    public NavigationCard takeWeatherNavigationCard() {
        requireNoThirstInProgress("take weather navigation card");
        if (state.phase() != Phase.NAVIGATION || currentWeatherEffect() != WeatherEffect.EXTRA_NAVIGATION) {
            throw new IllegalStateException(context + " 当前没有狂风的额外航海牌可翻");
        }
        if (extraNavigationTaken) {
            throw new IllegalStateException(context + " 本回合的狂风额外航海牌已经翻过");
        }
        NavigationCard chosen = table.pile().draw();
        table.pile().bottom(chosen);
        table.requireNoCardLost(context, "狂风额外航海牌后");
        extraNavigationTaken = true;
        resolvingExtraNavigation = true;
        navigatedThisTurn = chosen;
        return chosen;
    }

    public boolean weatherNavigationPending() {
        return currentWeatherEffect() == WeatherEffect.EXTRA_NAVIGATION && !extraNavigationTaken;
    }

    public boolean navigationComplete() {
        return standardNavigationTaken;
    }

    /** 返回刚结算完的是不是狂风额外牌，并把标志清掉。 */
    public boolean finishNavigationResolution() {
        requireNoThirstInProgress("finish navigation");
        boolean extra = resolvingExtraNavigation;
        resolvingExtraNavigation = false;
        return extra;
    }

    /** 风平浪静：不翻航海牌，但回收划船堆；之后照常结束一天、清全部回合标记。 */
    public void skipNavigation() {
        if (state.phase() != Phase.NAVIGATION || currentWeatherEffect() != WeatherEffect.SKIP_NAVIGATION) {
            throw new IllegalStateException(context + " 当前不能跳过航海阶段");
        }
        table.recycleRowStack();
        table.requireNoCardLost(context, "风平浪静回收划船堆后");
        standardNavigationTaken = true;
        navigatedThisTurn = null;
    }

    private WeatherEffect currentWeatherEffect() {
        return currentWeather().map(WeatherCard::effect).orElse(null);
    }

    /** 这一回合执行的那张航海牌。**公开信息** —— 结算后只公开被执行的那一张（决策 ⑭）。 */
    public Optional<NavigationCard> navigatedThisTurn() {
        return Optional.ofNullable(navigatedThisTurn);
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
     *
     * <p>这是 {@link #beginNavigate} 加逐个 {@link #decideThirst} 的简写，<b>按结算次序</b>每人问一次
     * {@code water}。模拟器与 {@code /seas navigate} 走这里，界面走两步 —— 规则只有两步那一份。
     */
    public NavigationReport navigate(NavigationCard card, WaterChoice water) {
        NavigationReport report = beginNavigate(card);
        Optional<ThirstPrompt> prompt;
        while ((prompt = thirstPending()).isPresent()) {
            decideThirst(water.waterFrom(prompt.get(), state));
        }
        return report;
    }

    /**
     * 航海结算的前两步：海鸥与落海，然后点名口渴、排好结算队列。
     *
     * <h2>为什么口渴要拆出去</h2>
     * 与划船那次是同一个形状：<b>「喝几张水」是决策，而真人答不了同步的问题</b>。
     * 原先口渴在一个循环里同步问回调，模组只能一律返回 0（那句「这件事还没做」的老实写法）。
     *
     * <p>❗<b>次序必须串行，不能并行收集</b>：陪酒女排在最后，要看着别人喝完再决定自己喝不喝 ——
     * 这正是「最后结算」那条规则的全部意义。并行收集会把它抹掉，而抹掉之后没有任何断言会红。
     *
     * @return 这一步点到了谁。口渴的两份名单在点名时就定了，所以报告在这里就是完整的
     */
    public NavigationReport beginNavigate(NavigationCard card) {
        beginNavigation(card);
        while (overboardPending().isPresent()) {
            finishOverboard();
        }
        return navigationReport();
    }

    private PendingNavigation pendingNavigation;
    private NavigationReport lastNavigationReport;
    private int overboardSequence;

    private static final class PendingNavigation {
        final NavigationCard card;
        final List<CharacterId> candidates;
        final List<List<CharacterId>> windows = new ArrayList<>();
        final List<CharacterId> selected = new ArrayList<>();
        final List<CharacterId> removed = new ArrayList<>();
        int token;
        int bait;

        PendingNavigation(NavigationCard card, List<CharacterId> candidates) {
            this.card = card;
            this.candidates = candidates;
        }

        PendingNavigation copy() {
            PendingNavigation c = new PendingNavigation(card, candidates);
            windows.forEach(w -> c.windows.add(new ArrayList<>(w)));
            c.selected.addAll(selected);
            c.removed.addAll(removed);
            c.token = token;
            c.bait = bait;
            return c;
        }
    }

    /** Start with gulls, then pause before each independent overboard resolution. */
    public void beginNavigation(NavigationCard card) {
        Objects.requireNonNull(card, "card");
        requireNoThirstInProgress("再结算一张航海牌");
        thirstCard = null;
        thirstQueue.clear();
        thirstAt = 0;
        lastNavigationReport = null;
        // a) 海鸥。浓雾让本回合所有海鸥图示失效。
        int gull = currentWeatherEffect() == WeatherEffect.IGNORE_GULLS ? 0 : card.gull();
        state = state.withGulls(gull);
        Invariants.requireValid(state, context, "海鸥结算后");
        if (state.isOver()) {
            lastNavigationReport = new NavigationReport(card, true, List.of(), List.of(), List.of(), List.of(), List.of());
            return;
        }

        // b) 航海牌本身的落海阶段。
        Set<CharacterId> overboardPool = new LinkedHashSet<>(state.onBoatBySeat());
        List<CharacterId> overboardCandidates = new ArrayList<>();
        for (CharacterId id : overboardPool) {
            if (state.conditionOf(id) != Condition.DEAD) {
                overboardCandidates.add(id);
            }
        }
        List<CharacterId> inWater = List.copyOf(card.overboard().select(overboardPool, usedProvisionResolver()));
        PendingNavigation pending = new PendingNavigation(card, List.copyOf(overboardCandidates));
        pending.windows.add(inWater);

        // 巨浪 / 暴风雨各自开启一个独立落海阶段：诱饵也因此在每一阶段分别结算。
        WeatherEffect weather = currentWeatherEffect();
        ThirstSource converted = weather == WeatherEffect.FIGHTERS_OVERBOARD && card.thirstFighters()
                ? ThirstSource.FOUGHT
                : weather == WeatherEffect.ROWERS_OVERBOARD && card.thirstRowers()
                ? ThirstSource.ROWED : null;
        if (converted != null) {
            List<CharacterId> marked = state.onBoatBySeat().stream()
                    .filter(id -> state.stateOf(id).thirst().has(converted))
                    .toList();
            pending.windows.add(marked);
        }
        pendingNavigation = pending;
        nextOverboardWindow();
    }

    private NavigationReport prepareThirst(NavigationCard card, List<CharacterId> overboardCandidates,
                                           List<CharacterId> overboardSelected, List<CharacterId> removedNow) {
        if (state.isOver()) {
            return new NavigationReport(card, false, overboardCandidates, overboardSelected,
                    List.of(), List.of(), removedNow);
        }
        WeatherEffect weather = currentWeatherEffect();
        // c) 口渴。候选不含死者，但含昏迷者 —— 他仍会口渴，只是不能自己打水。
        Set<CharacterId> thirstPool = new LinkedHashSet<>();
        for (CharacterId id : state.bySeat()) {
            if (state.conditionOf(id).suffersThirst()) {
                thirstPool.add(id);
            }
        }
        List<CharacterId> thirstCandidates = List.copyOf(thirstPool);
        List<CharacterId> thirstSelected = new ArrayList<>();
        if (weather == WeatherEffect.IGNORE_THIRST) {
            return new NavigationReport(card, false, overboardCandidates, List.copyOf(overboardSelected),
                    thirstCandidates, List.of(), List.copyOf(removedNow));
        }
        if (weather == WeatherEffect.ALL_THIRST) {
            for (CharacterId id : thirstPool) {
                state = state.withState(id, state.stateOf(id).thirstFrom(ThirstSource.WEATHER));
            }
        }
        for (CharacterId id : card.thirst().select(thirstPool, usedProvisionResolver())) {
            thirstSelected.add(id);              // 只数牌面点名，划船与战斗的口渴不在内
            state = state.withState(id, state.stateOf(id).thirstFrom(ThirstSource.NAMED));
        }

        // 排队。带「最后结算」标记的排在最后；这一回合一点都不渴的人不进队列 ——
        // 没有可做的决定就不该弹窗，8 人局的航海阶段本来就够长了。
        thirstCard = card;
        thirstWatersSpent = 0;
        thirstWatersByRecipient.clear();
        sharedWaterUsed.clear();
        thirstQueue.clear();
        thirstAt = 0;
        for (Survivor survivor : ThirstResolver.resolutionOrder(state.roster())) {
            CharacterId id = survivor.id();
            if (state.conditionOf(id).suffersThirst() && !effectiveThirst(id, card).isEmpty()) {
                thirstQueue.add(id);
            }
        }
        // 留住这一张的上下文到换阶段/下一张牌；队列为空之后晚喝酒仍需补算。
        return new NavigationReport(card, false, overboardCandidates, overboardSelected,
                thirstCandidates, thirstSelected, removedNow);
    }

    public record OverboardPrompt(int token, List<CharacterId> swimmers) {
        public OverboardPrompt {
            swimmers = List.copyOf(swimmers);
        }
    }

    public Optional<OverboardPrompt> overboardPending() {
        return pendingNavigation == null ? Optional.empty()
                : Optional.of(new OverboardPrompt(pendingNavigation.token, pendingNavigation.windows.getFirst()));
    }

    /**
     * 这一批落海里已经打出的血饵加了几点（没有落海在等时为 0）。<b>公开信息</b>：血饵是当着全船打的。
     *
     * <p>血饵不叠加（{@code stacks: false}），所以「已经有人打过」决定了再打一张是不是白扔 —— 替身要问这一句。
     */
    public int overboardBait() {
        return pendingNavigation == null ? 0 : pendingNavigation.bait;
    }

    public void finishOverboard() {
        if (pendingNavigation == null) {
            throw new IllegalStateException("no pending overboard window");
        }
        PendingNavigation pending = pendingNavigation;
        OverboardOutcome result = resolveOverboard(pending.windows.removeFirst(), pending.bait);
        pending.selected.addAll(result.selected());
        pending.removed.addAll(result.removed());
        nextOverboardWindow();
    }

    private void nextOverboardWindow() {
        PendingNavigation pending = pendingNavigation;
        while (!pending.windows.isEmpty()) {
            List<CharacterId> swimmers = pending.windows.getFirst().stream()
                    .filter(id -> !state.isRemoved(id)).toList();
            if (!swimmers.isEmpty() && !state.isOver()) {
                pending.windows.set(0, swimmers);
                pending.token = ++overboardSequence;
                pending.bait = 0;
                return;
            }
            pending.windows.removeFirst();
        }
        pendingNavigation = null;
        lastNavigationReport = prepareThirst(pending.card, pending.candidates,
                List.copyOf(pending.selected), List.copyOf(pending.removed));
    }

    public NavigationReport navigationReport() {
        if (pendingNavigation != null || lastNavigationReport == null) {
            throw new IllegalStateException("navigation is not resolved");
        }
        return lastNavigationReport;
    }

    public void playOverboardCard(CharacterId from, CharacterId target, String cardId, int token) {
        ProvisionEffect effect = requireOverboardPlay(from, target, cardId, token);
        if (effect instanceof ProvisionEffect.DamageInWater bait) {
            consume(from, cardId, true);
            pendingNavigation.bait = bait.stacks()
                    ? pendingNavigation.bait + bait.amount() : Math.max(pendingNavigation.bait, bait.amount());
            // 血饵打的是这一批落海的每一个人，不只是点出来的那一个。
            // ❗按座位次序记，不依赖这一批名单自己的次序：名单曾经来自 Set.copyOf，遍历次序每次起 JVM 都不同
            //   （审查 Z3 之后选择器按座位给了，这里照旧自己排一遍）。
            List<CharacterId> swimmers = pendingNavigation.windows.getFirst();
            for (CharacterId id : state.bySeat()) {
                if (swimmers.contains(id)) {
                    record(Deed.Kind.BAITED, from, id, bait.amount());
                }
            }
        } else {
            SurvivorState giver = state.stateOf(from);
            state = state.withState(from, giver.hasInHand(cardId)
                    ? giver.withoutCard(cardId) : giver.withoutCardInFront(cardId));
            state = state.withState(target, state.stateOf(target).withCardInFront(cardId));
            record(Deed.Kind.RING, from, target, 1);
        }
        requireNoProvisionLost("overboard intervention");
    }

    public record OverboardPlay(String card, CharacterId target) {
    }

    /** Private choices for this recipient; the public projection never contains others' hands. */
    public List<OverboardPlay> overboardPlays(CharacterId who) {
        if (pendingNavigation == null || !state.canAct(who)) {
            return List.of();
        }
        List<String> cards = new ArrayList<>(state.stateOf(who).hand());
        cards.addAll(state.stateOf(who).front());
        List<OverboardPlay> result = new ArrayList<>();
        for (String card : new LinkedHashSet<>(cards)) {
            ProvisionEffect kind = table.provisions().get(card).effect();
            if (!(kind instanceof ProvisionEffect.DamageInWater || kind instanceof ProvisionEffect.PreventOverboardDamage)) {
                continue;               // 别的牌这一刻一律打不出；不必逐个目标抛一个异常来确认（替身每一批落海都要问每个人）
            }
            for (CharacterId target : pendingNavigation.windows.getFirst()) {
                try {
                    ProvisionEffect effect = requireOverboardPlay(who, target, card, pendingNavigation.token);
                    result.add(new OverboardPlay(card, target));
                    if (effect instanceof ProvisionEffect.DamageInWater) {
                        break;
                    }
                } catch (IllegalArgumentException ignored) {
                    // Most held cards have no intervention at this window.
                }
            }
        }
        return List.copyOf(result);
    }

    private ProvisionEffect requireOverboardPlay(CharacterId from, CharacterId target, String cardId, int token) {
        if (pendingNavigation == null || pendingNavigation.token != token) {
            throw new IllegalStateException("overboard window has changed");
        }
        if (!pendingNavigation.windows.getFirst().contains(target)) {
            throw new IllegalArgumentException("target is not going overboard");
        }
        ProvisionEffect effect = requireHeld(from, cardId).effect();
        if (effect instanceof ProvisionEffect.DamageInWater) {
            return effect;
        }
        if (effect instanceof ProvisionEffect.PreventOverboardDamage ring
                && (from.equals(target) || ring.transferable())
                && (!ring.receiverMustBeConscious() || state.canAct(target))
                && state.conditionOf(target) != Condition.DEAD && !hasLifePreserverInFront(target)) {
            return effect;
        }
        throw new IllegalArgumentException("card cannot be played on this target");
    }

    /**
     * 轮到谁决定喝不喝水。空表示这一张牌的口渴已经结算完 —— 航海阶段到此结束。
     *
     * <p>❗<b>每问一次都现算</b>：陪酒女蹭到几张水，取决于在她之前的人喝了多少，
     * 而那是在她被问到之前一刻才知道的。提前算好整张表就把「最后结算」抹掉了。
     */
    public Optional<ThirstPrompt> thirstPending() {
        int at = nextThirstIndex();
        return at < 0 ? Optional.empty() : Optional.of(promptFor(thirstQueue.get(at)));
    }

    /**
     * 队列里下一个真的要结算口渴的人的下标；没有了就是 -1。
     *
     * <p>❗<b>不改状态</b>。它看起来该顺手把 {@code thirstAt} 推过那些跳过的人，但不能 ——
     * 模组每次同步都会替<b>每一个收件人</b>问一次「现在轮到谁」，
     * 而那是个序列化路径：在那里推进规则状态，等于让「有几个人在线」影响对局。
     */
    private int nextThirstIndex() {
        for (int at = thirstAt; at < thirstQueue.size(); at++) {
            // 排队之后可能有人死了（前面的人喝不到水、掉了血）—— 死者不结算口渴。
            if (state.conditionOf(thirstQueue.get(at)).suffersThirst()) {
                return at;
            }
        }
        return -1;
    }

    /**
     * 这一次口渴喝谁的水，一张一个人（可以重复：同一个人出两张）。空表示不喝。
     *
     * <p>喝下的水立刻离开出水人的手牌进弃牌堆；没化解掉的每一次口渴各扣 1 点。
     *
     * @throws IllegalStateException    现在没有人在等这个决定
     * @throws IllegalArgumentException 张数多于还需化解的次数；出水的人手上没有水；
     *                                  或者出水的人不清醒（❗<b>昏迷者不能自己打水，别人可以替他打</b>）
     */
    public void decideThirst(List<CharacterId> donors) {
        Objects.requireNonNull(donors, "donors");
        // ❗先记下他在队列里的下标：结算完他可能就死了，那时再问「下一个是谁」会把真正的下一个跳过去。
        int at = nextThirstIndex();
        if (at < 0) {
            throw new IllegalStateException("%s 现在没有人在等口渴的决定".formatted(context));
        }
        ThirstPrompt prompt = promptFor(thirstQueue.get(at));
        if (donors.size() > prompt.waterNeeded()) {
            throw new IllegalArgumentException(
                    "%s %s 只还需化解 %d 次口渴（每次 %d 张水），却要喝 %d 张水"
                            .formatted(context, prompt.who().value(), prompt.remaining(),
                                    prompt.waterPerSource(), donors.size()));
        }
        if (donors.size() % prompt.waterPerSource() != 0) {
            throw new IllegalArgumentException(
                    "%s %s 在当前天候下每次口渴需要 %d 张水，不能只交 %d 张"
                            .formatted(context, prompt.who().value(), prompt.waterPerSource(), donors.size()));
        }
        Map<CharacterId, Integer> required = new LinkedHashMap<>();
        donors.forEach(donor -> required.merge(donor, 1, Integer::sum));
        for (Map.Entry<CharacterId, Integer> entry : required.entrySet()) {
            CharacterId donor = entry.getKey();
            if (!state.canAct(donor)) {
                throw new IllegalArgumentException(
                        "%s %s 不清醒或已离线，打不出水".formatted(context, donor.value()));
            }
            if (watersOf(donor) < entry.getValue()) {
                throw new IllegalArgumentException("%s %s 没有所需的水".formatted(context, donor.value()));
            }
        }
        for (CharacterId donor : donors) {
            SurvivorState from = state.stateOf(donor);
            // ❗手上的与<b>亮在面前的</b>都能喝。规则 §5.2：亮出是为了防偷与「随时可用」，
            //   不是把牌锁死 —— 只认手牌的话，亮过的水就永远喝不了了，而屏幕上看不出任何异常。
            if (from.hasInHand(WATER)) {
                state = state.withState(donor, from.withoutCard(WATER));
            } else if (from.hasInFront(WATER)) {
                state = state.withState(donor, from.withoutCardInFront(WATER));
            } else {
                throw new IllegalArgumentException(
                        "%s %s 手上与面前都没有水".formatted(context, donor.value()));
            }
            table.discardProvision(WATER);
        }
        for (Map.Entry<CharacterId, Integer> entry : required.entrySet()) {
            if (!entry.getKey().equals(prompt.who())) {
                record(Deed.Kind.WATERED, entry.getKey(), prompt.who(), entry.getValue());
            }
        }
        thirstWatersSpent += donors.size();
        thirstWatersByRecipient.merge(prompt.who(), donors.size(), Integer::sum);
        sharedWaterUsed.merge(prompt.who(), prompt.shared() * prompt.waterPerSource(), Integer::sum);
        // 这一次口渴的每个来源都有了去向（挡下 / 蹭到 / 喝水 / 挨打），今天不再算第二次。
        thirstSettledToday.computeIfAbsent(prompt.who(), k -> new LinkedHashSet<>())
                .addAll(prompt.effective().sources());
        coverUsedToday.merge(prompt.who(), prompt.covered(), Integer::sum);
        int damage = prompt.remaining() - donors.size() / prompt.waterPerSource();
        if (damage > 0) {
            state = state.withState(prompt.who(), state.stateOf(prompt.who()).hurt(damage));
        }
        thirstAt = at + 1;
        requireNoProvisionLost("口渴结算后");
        Invariants.requireValid(state, context, "口渴结算后");
    }

    /** 这一张航海牌的口渴还没结算完。 */
    public boolean thirstInProgress() {
        return thirstPending().isPresent();
    }

    /**
     * 该谁决定喝水，以及他面对的账。
     *
     * @param who       轮到谁
     * @param effective 这一回合他真正生效的口渴来源（牌上没有船桨图示时，划船标记不算数）
     * @param covered   常驻遮蔽抵掉几次（撑开的阳伞）
     * @param shared    蹭到别人喝的水抵掉几次（陪酒女）
     * @param remaining 还需要化解几次 —— 每一次要么喝 1 张水，要么挨 1 点
     * @param ownWaters 他自己拿得出几张水（手上的 + 亮在面前的）。
     *                  别人能不能给他打水，规则上没有限制，所以这里只报他自己的
     */
    public record ThirstPrompt(CharacterId who, ThirstTally effective, int covered, int shared,
                               int remaining, int ownWaters, int waterPerSource) {

        public ThirstPrompt {
            Objects.requireNonNull(who, "who");
            Objects.requireNonNull(effective, "effective");
            if (waterPerSource < 1) {
                throw new IllegalArgumentException("每次口渴至少需要一张水");
            }
        }

        public int waterNeeded() {
            return Math.multiplyExact(remaining, waterPerSource);
        }
    }

    /** 水的物资 id。全项目只有这一处字面量 —— 多一处就多一个会写错的地方。 */
    public static final String WATER = "water";

    private NavigationCard thirstCard;
    private final List<CharacterId> thirstQueue = new ArrayList<>();
    private int thirstAt;
    private int thirstWatersSpent;
    /** 这一张牌各人喝下的水；不能在补算时让陪酒女蹭到自己之前喝的水。 */
    private final Map<CharacterId, Integer> thirstWatersByRecipient = new LinkedHashMap<>();
    /** 这一张牌已经用于蹭水化解的水量，补算不能重复使用。 */
    private final Map<CharacterId, Integer> sharedWaterUsed = new LinkedHashMap<>();

    /** 晚喝只补尚未结算的来源。当前窗口不换人，后续按规则次序排，已结的来源不会再进队。 */
    private void queueNewThirst() {
        if (thirstCard == null || pendingNavigation != null || state.phase() != Phase.NAVIGATION || state.isOver()) {
            return;
        }
        int at = nextThirstIndex();
        CharacterId current = at < 0 ? null : thirstQueue.get(at);
        List<CharacterId> waiting = new ArrayList<>();
        if (current != null) {
            waiting.add(current);
        }
        for (Survivor survivor : ThirstResolver.resolutionOrder(state.roster())) {
            CharacterId id = survivor.id();
            if (!id.equals(current) && state.conditionOf(id).suffersThirst()
                    && !effectiveThirst(id, thirstCard).isEmpty()) {
                waiting.add(id);
            }
        }
        thirstQueue.clear();
        thirstQueue.addAll(waiting);
        thirstAt = 0;
        if (current == null && !waiting.isEmpty()) {
            // 狂风额外牌后的停顿可能已经消费过 finishNavigationResolution；补算后仍须进入标准牌。
            resolvingExtraNavigation = extraNavigationTaken && !standardNavigationTaken;
        }
    }

    /**
     * 今天已经结算过的口渴来源，每人一份；换回合时清。
     *
     * <p>❗<b>狂风天一天翻两张航海牌</b>，而口渴标记要到一天结束才清（{@code SurvivorState.endOfTurn}）——
     * 标记不能提前清，「醉者落海」这类条件还要在第二张上读它。于是第一张点过名的人、喝过酒的人，
     * 在第二张上又渴一次（规则事实表探针 A · B）。规则是「每个来源一天最多一次」（{@link ThirstSource}、
     * 卡面「当天口渴 1 次」），用户 2026-10-05 裁定照规则修（ADR-0087 §4 第 1 条）。
     */
    private final Map<CharacterId, Set<ThirstSource>> thirstSettledToday = new LinkedHashMap<>();

    /** 今天撑开的阳伞已经挡掉几次；阳伞「每天挡 1 次」，两张牌不各挡一次。换回合时清。 */
    private final Map<CharacterId, Integer> coverUsedToday = new LinkedHashMap<>();

    private ThirstPrompt promptFor(CharacterId id) {
        ThirstTally effective = effectiveThirst(id, thirstCard);
        int covered = Math.max(0, coverCharges(id) - coverUsedToday.getOrDefault(id, 0));
        int waterPerSource = currentWeatherEffect() == WeatherEffect.DOUBLE_WATER ? 2 : 1;
        int shared = sharedWaterCancels(id) / waterPerSource;
        // 遮蔽与蹭到的水都是「不付代价就抵掉」，所以一起作为 ThirstResolver 的 coverCharges。
        var outcome = ThirstResolver.resolve(effective, Math.min(effective.count(), covered + shared), 0);
        return new ThirstPrompt(id, effective, Math.min(effective.count(), covered),
                Math.min(Math.max(0, effective.count() - covered), shared),
                outcome.damage(), watersOf(id), waterPerSource);
    }

    /** 他能拿出几张水：手上的加面前的。亮出来的水照样能喝（规则 §5.2）。 */
    public int watersOf(CharacterId id) {
        SurvivorState s = state.stateOf(id);
        return s.countInHand(WATER) + (int) s.front().stream().filter(WATER::equals).count();
    }

    /** 牌上没有船桨/战斗图示时，对应的标记不产生口渴。喝酒带来的口渴不看牌面。 */
    private ThirstTally effectiveThirst(CharacterId id, NavigationCard card) {
        ThirstTally effective = state.stateOf(id).thirst();
        if (!card.thirstRowers()) {
            effective = withoutSource(effective, ThirstSource.ROWED);
        }
        if (!card.thirstFighters()) {
            effective = withoutSource(effective, ThirstSource.FOUGHT);
        }
        if (currentWeatherEffect() == WeatherEffect.IGNORE_THIRST) {
            return ThirstTally.none();
        }
        if (currentWeatherEffect() == WeatherEffect.FIGHTERS_OVERBOARD && card.thirstFighters()) {
            effective = withoutSource(effective, ThirstSource.FOUGHT);
        }
        if (currentWeatherEffect() == WeatherEffect.ROWERS_OVERBOARD && card.thirstRowers()) {
            effective = withoutSource(effective, ThirstSource.ROWED);
        }
        for (ThirstSource settled : thirstSettledToday.getOrDefault(id, Set.of())) {
            effective = withoutSource(effective, settled);   // 狂风天第二张：今天算过的不再算
        }
        return effective;
    }

    /** 一个独立落海阶段；诱饵伤害、冲牌、水中死亡都只看这一阶段的名单。 */
    private OverboardOutcome resolveOverboard(List<CharacterId> requested, int playedBait) {
        List<CharacterId> inWater = requested.stream()
                .filter(id -> !state.isRemoved(id))
                .distinct().toList();
        List<CharacterId> selected = inWater.stream()
                .filter(id -> state.conditionOf(id) != Condition.DEAD).toList();
        int shark = Math.max(playedBait, sharkDamage(inWater));
        for (CharacterId id : inWater) {
            int hurt = (isOverboardImmune(state, id) ? 0 : 1) + shark;
            if (hurt > 0) {
                state = state.withState(id, state.stateOf(id).hurt(hurt));
            }
        }
        inWater.forEach(this::washAwayFront);
        List<CharacterId> removed = new ArrayList<>();
        for (CharacterId id : inWater) {
            int size = state.roster().get(id).size();
            if (Condition.inWater(state.stateOf(id).damage(), size,
                    hasLifePreserverInFront(id)) == Condition.DEAD) {
                removeFromGame(id);
                removed.add(id);
            }
        }
        requireNoProvisionLost("落海结算后");
        Invariants.requireValid(state, context, "落海结算后");
        return new OverboardOutcome(selected, List.copyOf(removed));
    }

    private record OverboardOutcome(List<CharacterId> selected, List<CharacterId> removed) {
    }

    /** 撑开的阳伞能抵几次。❗<b>要在落海之后才取值</b> —— 伞可能刚被冲走。 */
    private int coverCharges(CharacterId id) {
        SurvivorState s = state.stateOf(id);
        int charges = 0;
        for (String cardId : s.front()) {
            if (!(table.provisions().get(cardId).effect() instanceof ProvisionEffect.PreventThirst cover)) {
                continue;
            }
            if (!cover.persistent()) {
                continue;                        // 水不是常驻的，它要打出来才算
            }
            if (cover.requiresOpen() && !s.isOpen(cardId)) {
                continue;                        // 收着的伞不挡太阳
            }
            charges += cover.amount();
        }
        return charges;
    }

    /**
     * 蹭到别人喝的水（陪酒女）。
     *
     * <p>她排在最后结算，所以「在她之前喝掉的水」就是这一轮的全部。
     * {@code stacking.water = true} —— 别人喝几张她蹭几次；若数据改成不叠加则最多蹭一次。
     */
    private int sharedWaterCancels(CharacterId id) {
        if (!(state.roster().get(id).ability() instanceof Ability.ShareEffect share)) {
            return 0;
        }
        if (!share.sources().contains(WATER)) {
            return 0;
        }
        if (share.requiresConscious() && state.conditionOf(id) != Condition.CONSCIOUS) {
            return 0;
        }
        int otherWater = Math.max(0, thirstWatersSpent - thirstWatersByRecipient.getOrDefault(id, 0));
        int available = Boolean.TRUE.equals(share.stacking().get(WATER)) ? otherWater : Math.min(1, otherWater);
        return Math.max(0, available - sharedWaterUsed.getOrDefault(id, 0));
    }

    private void requireNoThirstInProgress(String what) {
        if (pendingNavigation != null) {
            throw new IllegalStateException("overboard decision pending: " + what);
        }
        int at = nextThirstIndex();
        if (at >= 0) {
            throw new IllegalStateException("%s %s 还没决定喝不喝水，不能%s"
                    .formatted(context, thirstQueue.get(at).value(), what));
        }
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

    /**
     * 落海免伤：水手的技能，或者<b>亮在面前</b>的救生圈。
     *
     * <p>❗水手那一条<b>要求清醒</b> —— 昏迷的水手落水照样受伤（于是照样会死）。
     * ❗救生圈<b>必须亮在面前</b>：手里攥着的救生圈不挡落水（规则 §9.2「自己亮出或别人帮他亮出」）。
     * ❗两者都<b>挡不住鲨鱼</b>，那一份在 {@link #sharkDamage} 里单独加。
     */
    private boolean isOverboardImmune(GameState g, CharacterId id) {
        Ability ability = g.roster().get(id).ability();
        // ❗「清醒」按伤势判，不按 canAct：掉线不改伤势，被动技能不因掉线失效（ADR-0051 B7，用户 2026-10-01）。
        //   陪酒女蹭水 · 蹭酒与绝境回血三处同一条。
        if (ability instanceof Ability.OverboardImmune oi
                && (!oi.requiresConscious() || g.conditionOf(id) == Condition.CONSCIOUS)) {
            return true;
        }
        for (String cardId : g.stateOf(id).front()) {
            if (table.provisions().get(cardId).effect() instanceof ProvisionEffect.PreventOverboardDamage) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这一轮下水的人各额外挨几点鲨鱼伤害（诱饵）。
     *
     * <p>条件是<b>下水的人里有谁面前亮着诱饵</b>（规则：「本就亮在落水者面前」）——
     * 所以亮诱饵这件事对自己也危险。多张不叠加（{@code stacks: false}）。
     *
     * <p>❗这一份<b>穿透水手的免伤与救生圈</b>，所以它不走 {@link #isOverboardImmune}，而是直接加在后面。
     * 数据两边都写着这件事：诱饵的 {@code bypasses} 与水手的 {@code not_protected_from}，
     * 由 {@code DataConsistency} 核对两者一致。
     */
    private int sharkDamage(List<CharacterId> inWater) {
        int worst = 0;
        int sum = 0;
        for (CharacterId id : inWater) {
            for (String cardId : state.stateOf(id).front()) {
                if (table.provisions().get(cardId).effect() instanceof ProvisionEffect.DamageInWater bait) {
                    worst = Math.max(worst, bait.amount());
                    sum += bait.amount();
                    if (!bait.stacks()) {
                        return worst;
                    }
                }
            }
        }
        return sum == 0 ? 0 : Math.max(worst, sum);
    }

    /** 落水：面前的牌全部冲走，只有{@code survives_overboard}的（救生圈）留下。手牌不动。 */
    private void washAwayFront(CharacterId id) {
        SurvivorState s = state.stateOf(id);
        if (s.front().isEmpty()) {
            return;
        }
        List<String> kept = new ArrayList<>();
        for (String cardId : s.front()) {
            ProvisionEffect effect = table.provisions().get(cardId).effect();
            boolean survives = effect instanceof ProvisionEffect.PreventOverboardDamage lp
                    && lp.survivesOverboard();
            if (survives) {
                kept.add(cardId);
            } else {
                table.discardProvision(cardId);
            }
        }
        state = state.withState(id, s.withFront(kept));
    }

    /**
     * 航海牌 {@code conditional} 条件的判定：条件名形如 {@code used_<物资 id>}。
     *
     * <p>nav_04 点名「本回合喝过朗姆酒的人」落海，靠的就是这一条。
     * 此前这里传的是「一律不成立」，那张牌<b>必定点不到任何人</b>。
     *
     * @throws IllegalArgumentException 条件名不认识 —— 静默返回 false 会让写错的条件与「没人满足」长得一样
     */
    private Selector.ConditionResolver usedProvisionResolver() {
        return (condition, who) -> {
            if (!condition.startsWith("used_")) {
                throw new IllegalArgumentException(
                        "%s 航海牌上的条件 %s 引擎不认识（认识的形如 used_<物资 id>）".formatted(context, condition));
            }
            String cardId = condition.substring("used_".length());
            if (!table.provisions().has(cardId)) {
                throw new IllegalArgumentException(
                        "%s 航海牌上的条件 %s 指着一个不存在的物资 %s".formatted(context, condition, cardId));
            }
            return state.stateOf(who).usedThisTurn(cardId);
        };
    }

    /** 面前亮着的船桨让他划船多抽几张（{@code weapon_and_row_bonus}，可叠加）。 */
    private int rowExtraDraw(CharacterId who) {
        int extra = 0;
        for (String cardId : state.stateOf(who).front()) {
            if (table.provisions().get(cardId).effect() instanceof ProvisionEffect.WeaponAndRowBonus oar) {
                extra += oar.rowExtraDraw();
                if (!oar.stacks()) {
                    return oar.rowExtraDraw();
                }
            }
        }
        return extra;
    }

    private static int countInFront(SurvivorState s, String cardId) {
        return (int) s.front().stream().filter(cardId::equals).count();
    }

    // ---------------------------------------------------------------- 物资：亮出 · 赠送 · 特殊行动

    /**
     * 亮出：手牌 → 面前。任何时候都可以，不占行动，<b>不可逆</b>（规则 §5.2）。
     *
     * <p>亮出是真的取舍：亮了才有用（救生圈挡落水、指南针多抽、伞能撑开），
     * 亮了也就落水时会被冲走、并且看得见。
     */
    public void reveal(CharacterId who, String cardId) {
        requireCanAct(who);
        if (beingPickedFrom(who)) {
            // 被抢方在战斗里照常能亮牌，但不能在抢夺结算那一刻把手牌亮出来躲掉这一抢（规则 §5 抢夺）。
            throw new IllegalStateException("%s %s 正在挨抢，挑牌那一刻不能亮牌".formatted(context, who.value()));
        }
        table.provisions().get(cardId);           // 目录不认识这张就在这里抛
        state = state.withState(who, state.stateOf(who).reveal(cardId));
        requireNoProvisionLost("亮出后");
    }

    /**
     * <b>夹具</b>：从牌堆里取一张指定的牌，直接发到某人手上。
     *
     * <h2>这不是规则，而且它必须说出来</h2>
     * 规则里牌只能从补给箱传下来。本方法是给<b>验收</b>用的：口渴那一面要有人既渴着又手里有水，
     * 而发牌是随机的 —— 等它自己出现的脚本一定会时灵时不灵，而「时灵时不灵的验收」比没有验收更坏。
     *
     * <p>牌<b>真的从牌堆里少一张</b>，所以对账照样成立 —— 这是它与「直接往手牌里塞一张」的本质区别。
     *
     * @throws IllegalArgumentException 牌堆里已经没有这张了
     */
    public void dealFromPile(CharacterId who, String cardId) {
        table.provisions().get(cardId);
        if (!table.takeFromProvisionPile(cardId)) {
            throw new IllegalArgumentException(
                    "%s 物资牌堆里已经没有 %s 了（还剩 %d 张）"
                            .formatted(context, cardId, table.provisionsLeft()));
        }
        state = state.withState(who, state.stateOf(who).withCard(cardId));
        requireNoProvisionLost("发指定的一张之后");
    }

    /**
     * 送一张牌给别人。
     *
     * <p>规则 §5.2：<b>只在行动阶段</b>可以自由赠送 / 交换，数量不限、不占行动；
     * 物资阶段与航海阶段不能交易。两个例外（航海阶段给落水者亮救生圈、给口渴者打水）
     * 走各自的结算入口，不走这里 —— 打水在 {@link #decideThirst} 里。
     *
     * <p>❗<b>亮出的牌送出去之后对方也必须保持亮出</b>（规则 §5.2），所以面前的牌进对方的面前，
     * 手牌进对方的手牌。
     *
     * @throws IllegalStateException    不在行动阶段
     * @throws IllegalArgumentException 他手上与面前都没有这张
     */
    public void giveCard(CharacterId from, CharacterId to, String cardId) {
        giveCard(from, to, cardId, !state.stateOf(from).hasInHand(cardId));
    }

    public void giveCard(CharacterId from, CharacterId to, String cardId, boolean fromFront) {
        requireNoContest("交易（规则 §9.1：战斗结束前任何卡不得易手）");
        if (state.phase() != Phase.ACTION) {
            throw new IllegalStateException(
                    "%s 只有行动阶段能交易，现在是 %s".formatted(context, state.phase()));
        }
        if (from.equals(to)) {
            throw new IllegalArgumentException("%s 送给自己没有意义".formatted(context));
        }
        if (state.isRemoved(from) || state.isRemoved(to)) {
            throw new IllegalArgumentException("%s 被移出游戏的人不能交易".formatted(context));
        }
        requireCanAct(from);
        table.provisions().get(cardId);
        SurvivorState giver = state.stateOf(from);
        if (!fromFront && giver.hasInHand(cardId)) {
            state = state.withState(from, giver.withoutCard(cardId))
                    .withState(to, state.stateOf(to).withCard(cardId));
        } else if (fromFront && giver.hasInFront(cardId)) {
            state = state.withState(from, giver.withoutCardInFront(cardId))
                    .withState(to, state.stateOf(to).withCardInFront(cardId));
        } else {
            throw new IllegalArgumentException(
                    "%s %s 手上与面前都没有 %s".formatted(context, from.value(), cardId));
        }
        record(Deed.Kind.GAVE, from, to, 1);
        requireNoProvisionLost("赠送后");
    }

    /**
     * 特殊行动：医疗箱。给一个人回 1 点伤。
     *
     * <p>规则上它用来救醒昏迷者，但卡面只写「移除 1 个伤害标记」，所以引擎不限制目标是不是昏迷 ——
     * 只要求他真的受了伤（{@link SurvivorState#heal} 会替我们把「治一个没受伤的人」拦下来）。
     * 医生用后不弃（{@code no_discard_for}），留在面前。
     *
     * @throws IllegalArgumentException 手上没有医疗箱，或者目标没受伤
     */
    public void useMedicalKit(CharacterId user, CharacterId target, String cardId) {
        requireNoContest("打出医疗箱");
        Provision card = requireHeld(user, cardId);
        if (!(card.effect() instanceof ProvisionEffect.Heal heal)) {
            throw new IllegalArgumentException("%s 不是治疗用的物资".formatted(cardId));
        }
        // ❗死亡不可复生（规则 §9.4）。少了这一条，医疗箱能把尸体治回昏迷 ——
        //   2026-09-16 随机对局当场红在 Invariants 的「从死亡复活」上，而单测一条都没红：
        //   要红就得有人恰好去治一具尸体，那正是「谁来挑输入」的差别。
        if (state.conditionOf(target) == Condition.DEAD) {
            throw new IllegalArgumentException(
                    "%s %s 已经死了，治不回来 —— 死亡不可复生".formatted(context, target.value()));
        }
        state = state.withState(target, state.stateOf(target).heal(heal.amount()));
        healedSincePhaseStart += heal.amount();
        record(Deed.Kind.HEALED, user, target, heal.amount());
        consume(user, cardId, heal.discardedBy(user));
        Invariants.requireValid(state, context, "治疗后");
        requireNoProvisionLost("治疗后");
    }

    /**
     * 特殊行动：撑开阳伞。撑开后常驻，直到落水被冲走；结算口渴时抵掉 1 个来源。
     *
     * <p>亮出与撑开是两件事：亮出不占行动、只是防偷；撑开占一个行动才开始挡太阳。
     * 手上那张会先亮出来再撑开 —— 撑着的伞不可能还在手里。
     *
     * @throws IllegalStateException 这把伞已经撑开了
     */
    public void openParasol(CharacterId who, String cardId) {
        requireNoContest("撑伞");
        requireCanAct(who);
        Provision card = table.provisions().get(cardId);
        if (!(card.effect() instanceof ProvisionEffect.PreventThirst cover) || !cover.requiresOpen()) {
            throw new IllegalArgumentException("%s 不是要撑开才生效的物资".formatted(cardId));
        }
        SurvivorState s = state.stateOf(who);
        if (s.isOpen(cardId)) {
            // 审查 2026-10-08 C9：原先不拦，SurvivorState.open 遇到已撑开的直接原样返回 —— 什么也没变，行动却白花了。
            //   界面与合法清单（Legal.plays 的 !isOpen）早就不给这一项，只有 /seas use 这条指令能走到；判据与 Legal 同一条。
            throw new IllegalStateException("%s %s 的 %s 已经撑开了".formatted(context, who.value(), cardId));
        }
        if (s.hasInHand(cardId)) {
            s = s.reveal(cardId);
        } else if (!s.hasInFront(cardId)) {
            throw new IllegalArgumentException(
                    "%s %s 手上与面前都没有 %s".formatted(context, who.value(), cardId));
        }
        state = state.withState(who, s.open(cardId));
        requireNoProvisionLost("撑伞后");
    }

    /**
     * 特殊行动：绝境的真人路径。牌先弃掉，再逐个问其余清醒角色是否反对；有人反对就进入既有战斗状态机。
     *
     * <p>规则要求无论结果如何都弃牌，所以不能等到战斗胜负出来才消费。这样战斗期间也不存在把这张牌
     * 转手、亮出或重复打出的竞态。
     *
     * @return 有值表示无需等待、效果已经结算；空表示正在等反对者，调用方必须等 {@link #contest()} 收场
     */
    public Optional<List<CharacterId>> beginRation(CharacterId user, String cardId) {
        requireNoContest("打出绝境");
        ProvisionEffect.HealAll heal = requireRation(user, cardId);
        consume(user, cardId, heal.discardOnUse());
        Optional<CharacterId> first = heal.contestable() ? nextRationOpponent(user, Set.of()) : Optional.empty();
        if (first.isPresent()) {
            contest = new Contest(Contest.Kind.RATION, user, first.get(), Contest.Stage.CONSENT,
                    Optional.empty(), false, Map.of(), cardId, Set.of());
            requireNoProvisionLost("绝境等待反对时");
            return Optional.empty();
        }
        return Optional.of(applyRation(user, cardId));
    }

    /**
     * 不含玩家决策的直达入口，供模拟器与规则夹具使用；真人模组必须走 {@link #beginRation}。
     */
    public List<CharacterId> useRation(CharacterId user, String cardId) {
        requireNoContest("打出绝境");
        ProvisionEffect.HealAll heal = requireRation(user, cardId);
        consume(user, cardId, heal.discardOnUse());
        return applyRation(user, cardId);
    }

    /** 昏迷不算尸体；这一条必须在弃牌之前查，失败不能吃掉玩家的牌。 */
    private ProvisionEffect.HealAll requireRation(CharacterId user, String cardId) {
        Provision card = requireHeld(user, cardId);
        if (!(card.effect() instanceof ProvisionEffect.HealAll heal)) {
            throw new IllegalArgumentException("%s 不是全体回血的物资".formatted(cardId));
        }
        if (heal.requiresCorpse() && state.onBoatBySeat().stream()
                .noneMatch(id -> state.conditionOf(id) == Condition.DEAD)) {
            throw new IllegalStateException("%s 船上没有尸体，%s 用不了".formatted(context, cardId));
        }
        return heal;
    }

    /** 下一位仍能反对的人，按座位顺序；打牌者、已回答者与不清醒者都跳过。 */
    private Optional<CharacterId> nextRationOpponent(CharacterId user, Set<CharacterId> passed) {
        return state.onBoatBySeat().stream()
                .filter(id -> !id.equals(user) && !passed.contains(id) && state.canAct(id))
                .findFirst();
    }

    /** 牌已经消费之后让绝境生效。 */
    private List<CharacterId> applyRation(CharacterId user, String cardId) {
        ProvisionEffect effect = table.provisions().get(cardId).effect();
        if (!(effect instanceof ProvisionEffect.HealAll heal)) {
            throw new IllegalStateException("绝境待决的牌已经不是全体回血物资：" + cardId);
        }
        List<CharacterId> healed = new ArrayList<>();
        for (CharacterId id : state.bySeat()) {
            if (state.conditionOf(id) != Condition.CONSCIOUS || state.stateOf(id).damage() == 0) {
                continue;
            }
            state = state.withState(id, state.stateOf(id).healIfHurt(heal.amount()));
            healedSincePhaseStart += heal.amount();
            healed.add(id);
            record(Deed.Kind.HEALED, user, id, heal.amount());
        }
        lastRationHealed = healed.size();
        Invariants.requireValid(state, context, "绝境之后");
        requireNoProvisionLost("绝境之后");
        return List.copyOf(healed);
    }

    /**
     * 特殊行动：信号枪当信号用。抽 3 张航海牌，<b>只结算其上的海鸥</b>，三张回牌堆底部。
     *
     * <p>抽到「去掉一只海鸥」照样算 —— 会让全船倒退一格。也可能当场凑够 4 只而结束一局，
     * 那时后面几张<b>照样翻完</b>（它们已经被抽出来了，结算次序在一张之内）。
     *
     * @return 抽到的那几张，按抽出顺序
     */
    public List<NavigationCard> fireSignal(CharacterId user, String cardId) {
        requireNoContest("打出信号枪");
        Provision card = requireHeld(user, cardId);
        if (!(card.effect() instanceof ProvisionEffect.WeaponOrSpecial weapon)) {
            throw new IllegalArgumentException("%s 没有「当信号用」这种用法".formatted(cardId));
        }
        ProvisionEffect.WeaponOrSpecial.Signal signal = weapon.special();
        List<NavigationCard> drawn = new ArrayList<>();
        for (int i = 0; i < signal.draw() && !table.pile().isEmpty(); i++) {
            NavigationCard nav = table.pile().draw();
            drawn.add(nav);
            int gull = currentWeatherEffect() == WeatherEffect.IGNORE_GULLS ? 0
                    : signal.includesGullRemoval() ? nav.gull() : Math.max(0, nav.gull());
            state = state.withGulls(gull);
        }
        drawn.forEach(nav -> table.pile().bottom(nav));
        table.requireNoCardLost(context, "放信号后");
        consume(user, cardId, weapon.discardOnUse());
        Invariants.requireValid(state, context, "放信号后");
        requireNoProvisionLost("放信号后");
        return List.copyOf(drawn);
    }

    /**
     * 喝一口酒：本回合战斗时体型 +3，<b>回合结束时口渴一次</b>。每回合最多一次，不叠加。
     *
     * <p>不占行动（数据里没有 {@code costs_action}）。酒留在面前，下一回合还能再喝。
     * 手上那瓶会先亮出来 —— 喝过的酒不可能还在手里。
     *
     * <p>「这一回合喝过」记在<b>人</b>身上（{@link SurvivorState#usedThisTurn}）：规则书「每人每天一次」。
     * 瓶子送给今天还没喝过的人，他照样能喝；喝过的人拿回瓶子也不能再喝（审查 2026-10-08 R2）。
     *
     * @throws IllegalStateException 这一回合已经喝过了，或者他正在挨抢、到了挑牌那一刻
     */
    public void drinkRum(CharacterId who, String cardId) {
        requireCanAct(who);
        if (beingPickedFrom(who)) {
            // ❗与亮牌同一道门（审查 2026-10-08 C1）：喝酒会把手里那瓶亮到面前，等于在挑牌那一刻亮牌。
            //   小孩只偷手牌，手里只剩这一瓶时一喝就一张能挑的都没有了 —— 这一场再也收不了场，
            //   模组的超时默认挑牌去拿面前那瓶，在 pickFromFront 里抛（服务端 tick 里没人接住）。
            //   从面前喝不挪牌，本身无害；但到了挑牌这一刻，这一场的打架（如果有）已经结算完，酒的 +3 只对今天之后的
            //   打架有用，挑完再喝不吃亏 —— 所以整个拦下，与 Legal.drinks 同一个口径，不分手里面前。
            throw new IllegalStateException("%s %s 正在挨抢，挑牌那一刻不能喝酒".formatted(context, who.value()));
        }
        Provision card = table.provisions().get(cardId);
        if (!(card.effect() instanceof ProvisionEffect.BuffSize buff)) {
            throw new IllegalArgumentException("%s 不是能喝的加体型物资".formatted(cardId));
        }
        SurvivorState s = state.stateOf(who);
        if (buff.oncePerTurn() && s.usedThisTurn(cardId)) {
            throw new IllegalStateException(
                    "%s %s 这一回合已经喝过 %s 了".formatted(context, who.value(), cardId));
        }
        if (s.hasInHand(cardId)) {
            s = s.reveal(cardId);
        } else if (!s.hasInFront(cardId)) {
            throw new IllegalArgumentException(
                    "%s %s 手上与面前都没有 %s".formatted(context, who.value(), cardId));
        }
        s = s.markUsedThisTurn(cardId);
        if (buff.causesThirst()) {
            s = s.thirstFrom(ThirstSource.DRANK_RUM);
        }
        state = state.withState(who, s);
        // 陪酒女蹭酒：她也跟着喝到，同样带口渴。不叠加（数据里 stacking.rum = false）。
        for (CharacterId other : state.bySeat()) {
            if (other.equals(who) || !sharesRum(other, cardId)) {
                continue;
            }
            SurvivorState guest = state.stateOf(other);
            if (guest.usedThisTurn(cardId)) {
                continue;
            }
            // ❗她蹭到的是效果，不是那张牌 —— 牌仍然只有一张，所以这里<b>不</b>把牌放到她面前。
            //   「今天喝过」（usedThisTurn，审查 R2 起按人记）也<b>不</b>给她记：蹭酒算不算「喝过」
            //   （nav_04 点不点她、她当天还能不能自己喝一瓶）待用户裁定（审查 2026-10-08 R2(c)），先维持原样。
            //   所以蹭酒另记在这里，见 sharedRumBonus。
            sharedRum.add(other);
            if (buff.causesThirst()) {
                state = state.withState(other, guest.thirstFrom(ThirstSource.DRANK_RUM));
            }
        }
        queueNewThirst();
        requireNoProvisionLost("喝酒后");
    }

    /** 本回合蹭到别人喝的酒的人（陪酒女）。回合结束时清空。 */
    private final Set<CharacterId> sharedRum = new LinkedHashSet<>();

    private boolean sharesRum(CharacterId id, String cardId) {
        if (!(state.roster().get(id).ability() instanceof Ability.ShareEffect share)) {
            return false;
        }
        if (!share.sources().contains(cardId)) {
            return false;
        }
        return !share.requiresConscious() || state.conditionOf(id) == Condition.CONSCIOUS;
    }

    /**
     * 蹭到的酒给几点体型。不叠加，所以按目录里那张牌的加值算一次。
     *
     * <p>❗<b>她自己也喝了同一种酒时，蹭到的不再另加</b>（{@code stacking.rum = false}）：先蹭到别人的、
     * 再自己喝一瓶，原先蹭酒与自己那瓶分两处记、各加一次，打架体型 +6（规则事实表探针 D）。
     * 用户 2026-10-05 裁定一天最多 +3（ADR-0087 §4 第 2 条）。先自己喝、再有人喝的那一路，
     * {@link #drinkRum} 本来就跳过她。
     */
    private int sharedRumBonus(CharacterId id) {
        if (!sharedRum.contains(id)) {
            return 0;
        }
        if (state.roster().get(id).ability() instanceof Ability.ShareEffect share) {
            SurvivorState s = state.stateOf(id);
            for (String cardId : share.sources()) {
                if (s.usedThisTurn(cardId) && !Boolean.TRUE.equals(share.stacking().get(cardId))) {
                    return 0;
                }
            }
        }
        int best = 0;
        for (Provision card : table.provisions().all()) {
            if (card.effect() instanceof ProvisionEffect.BuffSize buff) {
                best = Math.max(best, buff.amount());
            }
        }
        return best;
    }

    /**
     * 航海阶段开始时把划船堆备好：<b>舵手</b>持有指南针时，挑牌之前从牌堆顶再抽 1 张进划船堆
     * （{@code before_navigator_chooses}）。
     *
     * <p>❗<b>必须在舵手看到划船堆之前</b>调用，一回合只做一次（重复调用会一直往里加牌）；
     * {@link #takeCardForNavigation} 在没调过它时直接抛 —— 漏调的驱动者不会再安安静静地让指南针失效。
     *
     * <h2>三个条件，各有出处</h2>
     * <ul>
     *   <li><b>持有者必须是舵手</b>：设计决策 §8.1「当你是舵手时」。别人手里的指南针什么也不做 ——
     *       想让它生效就得交给舵手，这正是它作为交易筹码的意义。
     *       （ADR-0021 初稿裁定成「谁面前亮着都算」，与 §8.1 相反，同日改回。）</li>
     *   <li><b>手里的也算</b>：决策 ⑥「指南针亮出的唯一理由是防小孩偷」—— 亮不亮不影响效果。
     *       这是被动效果「在面前才算」那一条的例外。</li>
     *   <li><b>划船堆是空的就不抽</b>：没人划船时规则是直接翻顶牌，没有「挑选」这一步，
     *       「挑选之前」也就无从谈起。结果上两者一样（抽进来的那张就是顶牌），
     *       但不这么写的话，没人划船时舵手一面会为一张牌弹出来（ADR-0018 §7.4 明确不要）。</li>
     * </ul>
     *
     * @return 这一回合因此多进划船堆的张数
     */
    public int prepareRowStack() {
        if (state.phase() != Phase.NAVIGATION) {
            throw new IllegalStateException(
                    "%s 备划船堆是航海阶段的事，现在是 %s".formatted(context, state.phase()));
        }
        if (rowStackPrepared) {
            return 0;
        }
        rowStackPrepared = true;
        Optional<CharacterId> helmsman = state.helmsman();
        if (helmsman.isEmpty() || table.rowStackIsEmpty()) {
            return 0;
        }
        SurvivorState helm = state.stateOf(helmsman.get());
        List<String> held = new ArrayList<>(helm.hand());
        held.addAll(helm.front());
        int added = 0;
        for (String cardId : held) {
            if (!(table.provisions().get(cardId).effect()
                    instanceof ProvisionEffect.NavigatorExtraDraw extra)) {
                continue;
            }
            for (int i = 0; i < extra.amount() && !table.pile().isEmpty(); i++) {
                table.addToRowStack(table.pile().draw());
                added++;
            }
        }
        table.requireNoCardLost(context, "指南针多抽后");
        return added;
    }

    private boolean rowStackPrepared;

    /**
     * 终局时某人名下的财宝：<b>手牌与面前都算</b>（终局时全部亮出）。
     *
     * <p>分值不在这里 —— 它在 {@code data/roster} 的 {@code treasure_scoring} 里，
     * 由 {@link io.github.heavyseasmc.engine.scoring.Scorer} 查。本方法只数张数与面值。
     */
    public Treasures treasuresOf(CharacterId id) {
        SurvivorState s = state.stateOf(id);
        int cash = 0;
        int jewelry = 0;
        int fineArt = 0;
        List<String> owned = new ArrayList<>(s.hand());
        owned.addAll(s.front());
        for (String cardId : owned) {
            Provision card = table.provisions().get(cardId);
            if (card.effect() instanceof ProvisionEffect.ScoreSet) {
                jewelry++;
            } else if (card.effect() instanceof ProvisionEffect.ScoreFlat flat) {
                // 现金与美术品都是 score_flat，靠分值分不开 —— 靠的是「一张算一张」还是「按面值累加」。
                // 数据里现金每张 1 分且三张名画面值不同，所以按 points 是不是 1 分不开；
                // 用类别分：treasure 里 points 固定为 1 的那一族是现金。
                if (isCash(card)) {
                    cash++;
                } else {
                    fineArt += flat.points();
                }
            }
        }
        return new Treasures(cash, jewelry, fineArt);
    }

    /**
     * 这张 {@code score_flat} 是现金还是美术品。
     *
     * <p>❗两者在数据里同为 {@code score_flat}，唯一的区别是<b>谁让它翻倍</b>
     * （现金 → 船长，美术品 → 收藏家），而那正是 {@code Ability.ScoreMultiplier.target} 的取值。
     * 所以判据取自角色那一份，不是在这里写死 id。
     *
     * <p>❗查的是<b>整张角色表</b>（{@link Roster#doublers()}），不是这一局上场的人（审查 2026-10-08 C2）：
     * 原先在阵容里找加倍者，而「三个加倍者在每一套预设里都在场」只对预设成立 —— 房主自选阵容剔掉船长或收藏家之后，
     * 牌堆里照样有现金与美术品，终局一计分就抛（无船长 200 局里 151 局）。
     */
    private boolean isCash(Provision card) {
        if (!(card.effect() instanceof ProvisionEffect.ScoreFlat flat)) {
            return false;
        }
        Optional<io.github.heavyseasmc.engine.model.TreasureKind> kind =
                state.roster().treasureDoubledBy(CharacterId.of(flat.doubledBy()));
        if (kind.isPresent()) {
            return kind.get() == io.github.heavyseasmc.engine.model.TreasureKind.CASH;
        }
        // ❗分不出来就抛，不猜。从数据拼出来的阵容带着整张角色表，走到这里只可能是数据自相矛盾
        //   （DataConsistency 在加载期守着同一条，这里是它的运行期对照），或者是手搭的合成阵容里没有那个加倍者。
        //   「猜一个」会让某个人的财宝分安静地少掉一半。
        throw new IllegalStateException(
                "%s 分不出 %s 属于哪一类财宝：它写着由 %s 加倍，而角色表里没有这个角色或他没有加倍技能"
                        .formatted(context, card.id(), flat.doubledBy()));
    }

    /**
     * 拿一张出来用：手上或面前都算，并核对目录认识它。
     *
     * <p>❗<b>面前也算</b>，因为医生的医疗箱用后留在面前而且下一回合还能再用
     * （{@code no_discard_for}）。只认手牌的话，那张牌用过一次就永远用不了了 ——
     * 而表现只是「医生的技能好像没什么用」。
     */
    private Provision requireHeld(CharacterId who, String cardId) {
        requireCanAct(who);
        Provision card = table.provisions().get(cardId);
        SurvivorState s = state.stateOf(who);
        if (!s.hasInHand(cardId) && !s.hasInFront(cardId)) {
            throw new IllegalArgumentException(
                    "%s %s 手上与面前都没有 %s".formatted(context, who.value(), cardId));
        }
        return card;
    }

    private void requireCanAct(CharacterId who) {
        if (!state.canAct(who)) {
            throw new IllegalArgumentException("%s %s cannot act".formatted(context, who.value()));
        }
    }

    /** 用掉一张：弃牌堆，或者留在面前（医生的医疗箱）。手上那张用过之后不回手牌。 */
    private void consume(CharacterId who, String cardId, boolean discard) {
        SurvivorState s = state.stateOf(who);
        if (discard) {
            state = state.withState(who, s.hasInHand(cardId)
                    ? s.withoutCard(cardId)
                    : s.withoutCardInFront(cardId));
            table.discardProvision(cardId);
        } else if (s.hasInHand(cardId)) {
            state = state.withState(who, s.reveal(cardId));   // 不弃的留在面前，不回手牌
        }
    }

    /**
     * 物资对账：牌堆 + 补给箱在传的 + 每人手牌 + 每人面前 + 弃牌堆 = 总张数。
     *
     * <p>理由与航海牌那条相同：少一张<b>不会报错</b>，只会让某个效果再也不出现，
     * 而那种错能安静地跑完几千局。
     *
     * <p>❗它建立在「没有牌会离开游戏」之上。规则里尸体落海会把牌一起带走，
     * 而引擎还没有「移出游戏」这回事 —— 哪天做了，这里要同时加一个「已退出」的桶。
     */
    public void requireNoProvisionLost(String where) {
        int inHands = 0;
        int inFront = 0;
        for (CharacterId id : state.bySeat()) {
            inHands += state.stateOf(id).hand().size();
            inFront += state.stateOf(id).front().size();
        }
        int accounted = table.provisionsLeft() + provisionOffer.size() + inHands + inFront
                + table.provisionDiscard().size() + table.removedProvisions().size();
        if (accounted != table.provisionTotal()) {
            throw new IllegalStateException(
                    "%s %s：物资对不上，牌堆 %d + 补给箱 %d + 手牌 %d + 面前 %d + 弃牌 %d + 随人离场 %d ≠ 共 %d 张"
                            .formatted(context, where, table.provisionsLeft(), provisionOffer.size(),
                                    inHands, inFront, table.provisionDiscard().size(),
                                    table.removedProvisions().size(), table.provisionTotal()));
        }
    }

    // ---------------------------------------------------------------- 推演用：公开的进度，与照图纸重拼一局

    /**
     * 这一局走到哪儿了：不在 {@link GameState} 里、又全船看得见的那些进度。<b>不含任何看不见的东西。</b>
     *
     * <p>给往前推演用（{@code engine.seat}）：替身只拿得到自己的视角，要「照我看得见的、把看不见的重抽一遍」拼出一局，
     * 视角里就得有这些 —— 走到半路的一局（补给箱传到一半、落海还剩一批、口渴排到谁）光靠状态与桌面拼不出来。
     *
     * <p>集合一律按座位排过，不依赖落海名单自己的次序（它曾经来自 {@code Set.copyOf}，遍历次序每次起 JVM 都不同；
     * 审查 Z3 之后选择器已按座位给）。
     *
     * @param weatherDrawn          今天的天候翻过了
     * @param provisionChain        补给箱传的次序（开箱那一刻定死）
     * @param provisionAt           传到第几位
     * @param provisionRoundsToday  今天开过几轮补给箱
     * @param navigated             这一天已经执行的那张航海牌
     * @param standardNavigation    这一天舵手那一张执行过了
     * @param extraNavigation       狂风那一张翻过了
     * @param resolvingExtra        正在结算的是狂风那一张
     * @param rowStackPrepared      舵手的指南针那一张已经抽进划船堆
     * @param overboard             正在等的那几批落海（结算完为空）
     * @param overboardSequence     落海批次的编号到几了
     * @param thirstCard            最近开始结算口渴的牌（换阶段/开始下一张时清，供晚喝补算）
     * @param thirstQueue           口渴的结算次序
     * @param thirstAt              排到第几位
     * @param thirstWatersSpent     这一张牌的口渴里一共喝下了几张水（陪酒女蹭水看它）
     * @param thirstWatersByRecipient 这一张牌各人已经喝下的水
     * @param sharedWaterUsed       这一张牌各人已经用来蹭水化解的水量
     * @param thirstSettledToday    今天每个人已经结算过的口渴来源（狂风天第二张不再算）
     * @param coverUsedToday        今天每个人撑开的伞已经挡过几次
     * @param sharedRum             今天蹭到别人的酒的人
     * @param healedSincePhaseStart 这一阶段治了几点（模拟器核对不变量用）
     */
    public record Progress(boolean weatherDrawn, List<CharacterId> provisionChain, int provisionAt,
                           int provisionRoundsToday, Optional<NavigationCard> navigated, boolean standardNavigation,
                           boolean extraNavigation, boolean resolvingExtra, boolean rowStackPrepared,
                           Optional<OverboardBatches> overboard, int overboardSequence,
                           Optional<NavigationCard> thirstCard, List<CharacterId> thirstQueue, int thirstAt,
                           int thirstWatersSpent, Map<CharacterId, Integer> thirstWatersByRecipient,
                           Map<CharacterId, Integer> sharedWaterUsed, Map<CharacterId, Set<ThirstSource>> thirstSettledToday,
                           Map<CharacterId, Integer> coverUsedToday, Set<CharacterId> sharedRum,
                           int healedSincePhaseStart) {

        public Progress {
            Objects.requireNonNull(navigated, "navigated");
            Objects.requireNonNull(overboard, "overboard");
            Objects.requireNonNull(thirstCard, "thirstCard");
            provisionChain = List.copyOf(provisionChain);
            thirstQueue = List.copyOf(thirstQueue);
            thirstWatersByRecipient = Map.copyOf(thirstWatersByRecipient);
            sharedWaterUsed = Map.copyOf(sharedWaterUsed);
            Map<CharacterId, Set<ThirstSource>> settled = new LinkedHashMap<>();
            thirstSettledToday.forEach((id, sources) -> settled.put(id, Set.copyOf(sources)));
            thirstSettledToday = Map.copyOf(settled);
            coverUsedToday = Map.copyOf(coverUsedToday);
            sharedRum = Set.copyOf(sharedRum);
        }
    }

    /**
     * 正在结算的那张航海牌的落海：还剩几批（第一批就是此刻在等的那一批）、点到了谁、谁沉了、这一批打了几点血饵。
     */
    public record OverboardBatches(NavigationCard card, List<CharacterId> candidates, List<List<CharacterId>> windows,
                                   List<CharacterId> selected, List<CharacterId> removed, int token, int bait) {

        public OverboardBatches {
            Objects.requireNonNull(card, "card");
            candidates = List.copyOf(candidates);
            windows = windows.stream().map(List::copyOf).toList();
            selected = List.copyOf(selected);
            removed = List.copyOf(removed);
        }
    }

    /** 此刻的进度（{@link Progress}）。只有公开的东西。 */
    public Progress progress() {
        Optional<OverboardBatches> batches = Optional.ofNullable(pendingNavigation).map(p -> new OverboardBatches(
                p.card, p.candidates, p.windows.stream().map(this::inSeatOrder).toList(), inSeatOrder(p.selected),
                inSeatOrder(p.removed), p.token, p.bait));
        Map<CharacterId, Set<ThirstSource>> settled = new LinkedHashMap<>();
        thirstSettledToday.forEach((id, sources) -> settled.put(id, Set.copyOf(sources)));
        return new Progress(weatherDrawnThisTurn, provisionChain, provisionAt, provisionRoundsToday,
                Optional.ofNullable(navigatedThisTurn), standardNavigationTaken, extraNavigationTaken,
                resolvingExtraNavigation, rowStackPrepared, batches, overboardSequence, Optional.ofNullable(thirstCard),
                thirstQueue, thirstAt, thirstWatersSpent, thirstWatersByRecipient, sharedWaterUsed,
                settled, coverUsedToday, sharedRum, healedSincePhaseStart);
    }

    private List<CharacterId> inSeatOrder(java.util.Collection<CharacterId> ids) {
        return state.bySeat().stream().filter(ids::contains).toList();
    }

    /**
     * 照图纸重拼一局所需的全部：状态、每一堆牌的次序、走到半路的每一步、爱恨与记事。
     *
     * <p>推演用：看不见的那几样（别人的手牌、牌堆次序、别人押下的是哪一张、别人的爱恨）由调用方重新抽好再填进来 ——
     * 见 {@code engine.seat.Worlds}。{@link #rebuild} 拼完会对账（牌一张不少、状态自洽），图纸填错了当场抛。
     *
     * @param navPile    航海牌堆，顶上的在前
     * @param rowStack   划船堆
     * @param rowerHand  划船者手上（摸了还没选的那几张）；同时也是 {@code rowing} 的那几张
     * @param weather    天候牌堆；空表示不翻天候的三阶段局
     * @param provisionOffer 补给箱里此刻的牌
     * @param rower      正在划船的人
     * @param contest    进行中的这一场（押下的暗牌已经按图纸填好）
     */
    public record Blueprint(String context, GameState state, Provisions provisions, List<NavigationCard> navPile,
                            List<NavigationCard> rowStack, List<NavigationCard> rowerHand, List<String> provisionPile,
                            List<String> provisionDiscard, List<String> removedProvisions,
                            Optional<WeatherParts> weather, List<String> provisionOffer, Optional<CharacterId> rower,
                            Optional<Contest> contest, Progress progress, Affinities affinities, List<Deed> deeds) {

        public Blueprint {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(provisions, "provisions");
            Objects.requireNonNull(weather, "weather");
            Objects.requireNonNull(rower, "rower");
            Objects.requireNonNull(contest, "contest");
            Objects.requireNonNull(progress, "progress");
            Objects.requireNonNull(affinities, "affinities");
            navPile = List.copyOf(navPile);
            rowStack = List.copyOf(rowStack);
            rowerHand = List.copyOf(rowerHand);
            provisionPile = List.copyOf(provisionPile);
            provisionDiscard = List.copyOf(provisionDiscard);
            removedProvisions = List.copyOf(removedProvisions);
            provisionOffer = List.copyOf(provisionOffer);
            deeds = List.copyOf(deeds);
        }
    }

    /**
     * 天候牌堆的几样：还没翻的（顶上的在前）、弃牌堆、今天这一张，以及以后洗牌用的随机流种子。
     */
    public record WeatherParts(List<WeatherCard> upcoming, List<WeatherCard> discard, Optional<WeatherCard> current,
                               long seed) {

        public WeatherParts {
            upcoming = List.copyOf(upcoming);
            discard = List.copyOf(discard);
            Objects.requireNonNull(current, "current");
        }
    }

    /** 拼一局用的空壳：状态照给的，其余由 {@link #rebuild} 逐项填。 */
    private Session(String context, Table table, GameState state) {
        this.context = Objects.requireNonNull(context, "context");
        this.table = Objects.requireNonNull(table, "table");
        this.state = Objects.requireNonNull(state, "state");
    }

    /**
     * 照图纸重拼一局（{@link Blueprint}）。拼出来的一局与普通的一局没有两样：接着往下走、做副本、再拼，都可以。
     *
     * <p>❗它<b>不知道</b>图纸上哪些是看得见的、哪些是抽出来的 —— 那是填图纸的人的事。它只管拼对：
     * 牌一张不少（航海牌与物资各对一次账）、状态自洽（{@link Invariants}），不对就抛。
     *
     * @throws IllegalStateException    对不上账，或者状态不自洽
     * @throws IllegalArgumentException 图纸里的东西互相矛盾（比如正在划船的人没有摸到牌）
     */
    public static Session rebuild(Blueprint b) {
        Objects.requireNonNull(b, "b");
        int navTotal = b.navPile().size() + b.rowStack().size() + b.rowerHand().size();
        WeatherDeck weather = b.weather().map(w -> WeatherDeck.of(w.upcoming(), w.discard(), w.current(),
                new java.util.Random(w.seed()))).orElse(null);
        Table table = new Table(io.github.heavyseasmc.engine.navigation.NavigationDeck.ofOrder(b.navPile(), navTotal),
                b.provisions(), weather, b.rowStack(), b.rowerHand(), b.provisionPile(), b.provisionDiscard(),
                b.removedProvisions());
        Session s = new Session(b.context(), table, b.state());
        Progress p = b.progress();
        s.weatherDrawnThisTurn = p.weatherDrawn();
        s.healedSincePhaseStart = p.healedSincePhaseStart();
        s.provisionChain = p.provisionChain();
        s.provisionOffer.addAll(b.provisionOffer());
        s.provisionAt = p.provisionAt();
        s.provisionRoundsToday = p.provisionRoundsToday();
        if (b.rower().isPresent() != !b.rowerHand().isEmpty()) {
            throw new IllegalArgumentException("图纸上正在划船的人与划船者手上的牌对不上");
        }
        s.rower = b.rower().orElse(null);
        b.rowerHand().forEach(card -> s.rowing.add(new RowCard(card, RowFate.UNDECIDED)));
        s.contest = b.contest().orElse(null);
        s.navigatedThisTurn = p.navigated().orElse(null);
        s.standardNavigationTaken = p.standardNavigation();
        s.extraNavigationTaken = p.extraNavigation();
        s.resolvingExtraNavigation = p.resolvingExtra();
        s.rowStackPrepared = p.rowStackPrepared();
        p.overboard().ifPresent(o -> {
            PendingNavigation pending = new PendingNavigation(o.card(), o.candidates());
            o.windows().forEach(w -> pending.windows.add(new ArrayList<>(w)));
            pending.selected.addAll(o.selected());
            pending.removed.addAll(o.removed());
            pending.token = o.token();
            pending.bait = o.bait();
            s.pendingNavigation = pending;
        });
        s.overboardSequence = p.overboardSequence();
        s.thirstCard = p.thirstCard().orElse(null);
        s.thirstQueue.addAll(p.thirstQueue());
        s.thirstAt = p.thirstAt();
        s.thirstWatersSpent = p.thirstWatersSpent();
        s.thirstWatersByRecipient.putAll(p.thirstWatersByRecipient());
        s.sharedWaterUsed.putAll(p.sharedWaterUsed());
        p.thirstSettledToday().forEach((id, sources) -> s.thirstSettledToday.put(id, new LinkedHashSet<>(sources)));
        s.coverUsedToday.putAll(p.coverUsedToday());
        s.sharedRum.addAll(p.sharedRum());
        s.deeds.addAll(b.deeds());
        b.affinities().requireCovers(b.state().roster());
        s.affinities = b.affinities();
        table.requireNoCardLost(s.context, "照图纸重拼之后");
        s.requireNoProvisionLost("照图纸重拼之后");
        Invariants.requireValid(s.state, s.context, "照图纸重拼之后");
        return s;
    }

    // ---------------------------------------------------------------- 公开记事

    /** 这一局全船看得见的事，按发生先后。只增不减（{@link Deed}）。 */
    private final List<Deed> deeds = new ArrayList<>();

    /** {@link #deeds()} 交出去的那一份；记一条新的就作废。读的次数远多于记的次数（每个决定都要读一次）。 */
    private List<Deed> deedsSnapshot = List.of();

    /**
     * 这一局到此为止全船看得见的事（{@link Deed}），按发生先后。不可变的一份。
     *
     * <p>❗<b>只有公开的事</b>：拿走、送出、押下的是哪一张都不在里面。席位策略靠它「记得谁帮过我、谁害过我」，
     * 而它只能用全船都知道的东西 —— 所以这份记事对每个人都一样，按人裁剪的只有爱恨与手牌。
     */
    public List<Deed> deeds() {
        if (deedsSnapshot.size() != deeds.size()) {
            deedsSnapshot = List.copyOf(deeds);
        }
        return deedsSnapshot;
    }

    private void record(Deed.Kind kind, CharacterId actor, CharacterId target, int amount) {
        deeds.add(new Deed(state.turn(), kind, actor, target, amount));
    }

    /**
     * 舵手挑的牌点了谁落海：每人记一条 {@link Deed.Kind#STEERED}。
     *
     * <p>按座位次序记，不按选择器给的集合 —— 那个集合曾经来自 {@code Set.copyOf}，遍历次序每次起 JVM 都不同
     * （审查 Z3 之后它已按候选次序给，这里照旧按座位走一遍，不依赖它）。
     * 舵手自己不记：他把自己送下水不是对谁做了什么。
     */
    private void recordSteering(NavigationCard card, int stackSize) {
        Optional<CharacterId> helmsman = state.helmsman();
        if (helmsman.isEmpty()) {
            return;
        }
        Set<CharacterId> named = card.overboard().select(new LinkedHashSet<>(state.onBoatBySeat()),
                usedProvisionResolver());
        for (CharacterId id : state.onBoatBySeat()) {
            if (named.contains(id) && !id.equals(helmsman.get())) {
                record(Deed.Kind.STEERED, helmsman.get(), id, stackSize);
            }
        }
    }

    // ---------------------------------------------------------------- 爱恨与终局（ADR-0022）

    private Affinities affinities;

    /**
     * 发爱恨牌。开局发一次（规则：设置阶段每人各抽一张喜爱、一张憎恨，全程保密）。
     *
     * @throws IllegalStateException    已经发过
     * @throws IllegalArgumentException 爱恨牌发给的人与这一局的阵容对不上
     */
    public void dealAffinities(Affinities dealt) {
        Objects.requireNonNull(dealt, "dealt");
        if (affinities != null) {
            throw new IllegalStateException("%s 爱恨牌已经发过了 —— 一局只发一次".formatted(context));
        }
        dealt.requireCovers(state.roster());
        affinities = dealt;
    }

    /** 这一局的爱恨牌；还没发时为空。❗<b>每个人只该看到自己那两张</b> —— 按人裁剪是调用方的事。 */
    public Optional<Affinities> affinities() {
        return Optional.ofNullable(affinities);
    }

    /**
     * 终局状态：计分的全部输入。
     *
     * <p>{@code alive} 看生死，{@code onBoat} 看有没有被移出游戏 —— 两者只在「死在水里」这一种情形下分叉，
     * 而那正是 {@link FinalState} 把它们分开存的原因。财宝取手牌加面前（终局时全部亮出）。
     *
     * @throws IllegalStateException 爱恨牌还没发 —— 不猜，猜出来的分是假的
     */
    public Map<CharacterId, FinalState> finalStates() {
        if (affinities == null) {
            throw new IllegalStateException("%s 爱恨牌还没发，算不出终局".formatted(context));
        }
        Map<CharacterId, FinalState> out = new LinkedHashMap<>();
        for (CharacterId id : state.bySeat()) {
            boolean onBoat = !state.isRemoved(id);
            out.put(id, new FinalState(state.conditionOf(id) != Condition.DEAD, onBoat,
                    onBoat ? treasuresOf(id) : Treasures.NONE, affinities.loveOf(id), affinities.hateOf(id)));
        }
        return out;
    }

    /**
     * 四项计分（{@link Scorer}，M0 起就在，这里只是把真实终局交给它）。
     *
     * @param scoring 分值表，来自 {@code data/roster} 的 {@code treasure_scoring} —— 引擎里不另写一份
     */
    public Map<CharacterId, ScoreSheet> scores(TreasureScoring scoring) {
        Objects.requireNonNull(scoring, "scoring");
        return Scorer.scoreAll(state.roster(), finalStates(), scoring);
    }

    /**
     * <b>夹具</b>：把海鸥直接置满，让这一局当场以靠岸结束（ADR-0022 §7.7）。
     *
     * <p>与 {@link #dealFromPile} 同一族：终局不摆出来就验不了，而等一局自然打完是时灵时不灵的验收。
     *
     * @throws IllegalStateException 这一局已经结束，或者还有划船 / 口渴没定完
     */
    public void landForFixture() {
        requireNoRowInProgress("直接靠岸");
        requireNoThirstInProgress("直接靠岸");
        if (state.isOver()) {
            throw new IllegalStateException("%s 这一局已经结束了".formatted(context));
        }
        state = state.withGulls(GameState.GULLS_TO_LAND - state.gulls());
        Invariants.requireValid(state, context, "夹具靠岸后");
    }

    /**
     * <b>调试口</b>（ADR-0060，{@code /seas debug gulls}）：把海鸥数定为 {@code target}。
     *
     * <p>与 {@link #landForFixture} 同一族，只是可以停在半路（0–3 只）。凑够 {@value GameState#GULLS_TO_LAND} 只时
     * 当场以靠岸结束 —— 那一刻与夹具靠岸是同一个局面，所以拦的也是同两条：划船 / 口渴还没定完时不许靠岸。
     * 往下调是合法的（信号枪也会翻出「去掉一只海鸥」），负数不行。
     *
     * @throws IllegalArgumentException {@code target} 为负
     * @throws IllegalStateException    这一局已经结束；或者要靠岸了，而划船 / 口渴还没定完
     */
    public void debugSetGulls(int target) {
        if (target < 0) {
            throw new IllegalArgumentException("%s 海鸥数不能为负：%d".formatted(context, target));
        }
        if (state.isOver()) {
            throw new IllegalStateException("%s 这一局已经结束了".formatted(context));
        }
        if (target >= GameState.GULLS_TO_LAND) {
            requireNoRowInProgress("调试靠岸");
            requireNoThirstInProgress("调试靠岸");
        }
        GameState before = state;
        state = state.withGulls(target - state.gulls());
        Invariants.requireValid(state, context, "调试改海鸥后");
        Invariants.requireValidTransition(before, state, context, "调试改海鸥");
    }

    /** 连人带牌移出游戏：手牌与面前的牌随他离场（不进弃牌堆），他从船上消失（ADR-0022）。 */
    private void removeFromGame(CharacterId id) {
        SurvivorState s = state.stateOf(id);
        List<String> cards = new ArrayList<>(s.hand());
        cards.addAll(s.front());
        table.removeWithCharacter(cards);
        state = state.withState(id, s.withoutAllCards()).withRemoved(id);
    }

    /** 面前亮着救生圈吗。水中判定只认这一件 —— 水手的免伤管的是扣不扣血，不管淹不淹死。 */
    private boolean hasLifePreserverInFront(CharacterId id) {
        for (String cardId : state.stateOf(id).front()) {
            if (table.provisions().get(cardId).effect() instanceof ProvisionEffect.PreventOverboardDamage) {
                return true;
            }
        }
        return false;
    }

    /** 划船时看完整组牌后，返回要放进划船堆的下标。 */
    @FunctionalInterface
    public interface RowingChoice {
        int choose(List<NavigationCard> cards, GameState state, CharacterId rower);
    }

    /**
     * 口渴结算时这一次喝谁的水 —— 一张水一个人，可以重复（同一个人出两张），空表示不喝。
     *
     * <p>❗<b>返回的是「谁出的水」，不是「喝几张」。</b> 只报张数的话，水从谁手里出去这件事
     * 根本表达不出来，于是没有任何一张牌真的被消耗 —— M1 期间正是如此。
     */
    @FunctionalInterface
    public interface WaterChoice {
        List<CharacterId> waterFrom(ThirstPrompt prompt, GameState state);
    }
}
