package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.NavigationReport;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.weather.WeatherEffect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * 用席位策略把一局往前推：每到要人拿主意的地方，就去问那个座位的策略。
 *
 * <h2>谁在用它</h2>
 * <ul>
 *   <li>模拟器（{@code Simulator}）：一个阶段一个阶段地推，阶段之间核对不变量；</li>
 *   <li>推演（第二层）：在抽出来的一份「可能的局面」上，从某个决定之后接着往下推几天。</li>
 * </ul>
 * 模组不用它 —— 模组的每一步要等真人、要计时、要播报，是异步的；它问替身用的是同一套 {@link SeatPolicy}。
 *
 * <h2>❗问的次序与次数是钉死的</h2>
 * 随机席位照搬搬家前模拟器的随机分布，它消费随机数的次序就是这里提问的次序：
 * 亮牌 → 喝酒 → 行动；表态 → 依座位问站队 → 依参战次序问押武器 → 挑牌；口渴依结算次序，先问本人再依座位问递水的人。
 * 没有可选项的问题也照样问（「喝不喝」在没有酒时也问）—— 原模拟器在那里摇过一次骰子。
 *
 * <h2>交回不合法的选项</h2>
 * 退回那个决定的默认（与模组的超时默认同一个意思：什么也不做 · 同意 · 不站队 · 不押 · 挑第一张……），
 * 记一笔（{@link #fallbacks()}），<b>不抛</b>。抛了这一局就卡在半路，而卡住的局面没有下一步来推它。
 */
public final class SeatDriver {

    /** 驱动者往外报的事。 */
    public interface Listener {

        /** 一张航海牌的海鸥与落海结算完了（口渴还没开始）。 */
        default void navigated(NavigationReport report) {
        }
    }

    /** 每一种决定记前几条退回默认的详情；再多只计数（按种类各记各的：一种答错得多，别把别的挤掉）。 */
    private static final int FALLBACK_LOG_PER_DECISION = 3;

    private final Session session;
    private final Map<CharacterId, SeatPolicy> policies;
    private final Random rng;
    private final Listener listener;
    private int fights;
    private int fallbacks;
    private final List<String> fallbackLog = new ArrayList<>();
    private final Map<String, Integer> fallbackCounts = new java.util.LinkedHashMap<>();
    private final SeatView.Cache viewCache = new SeatView.Cache();

    /**
     * @param policies 每个座位的策略，必须一个不落
     * @param rng      这一局的随机流：天意（摸哪张手牌）与随机席位都从它拿
     */
    public SeatDriver(Session session, Map<CharacterId, SeatPolicy> policies, Random rng, Listener listener) {
        this.session = Objects.requireNonNull(session, "session");
        this.policies = Map.copyOf(Objects.requireNonNull(policies, "policies"));
        this.rng = Objects.requireNonNull(rng, "rng");
        this.listener = Objects.requireNonNull(listener, "listener");
        for (CharacterId id : session.state().bySeat()) {
            if (!this.policies.containsKey(id)) {
                throw new IllegalArgumentException("座位 " + id + " 没有策略");
            }
        }
    }

    public SeatDriver(Session session, Map<CharacterId, SeatPolicy> policies, Random rng) {
        this(session, policies, rng, new Listener() {
        });
    }

    public Session session() {
        return session;
    }

    /** 打了几架（走到站队那一步就算一架）。 */
    public int fights() {
        return fights;
    }

    /** 策略交回不合法的选项、被退回默认的次数。 */
    public int fallbacks() {
        return fallbacks;
    }

    /** 每一种决定前几条退回默认的详情。 */
    public List<String> fallbackLog() {
        return Collections.unmodifiableList(fallbackLog);
    }

    /** 每一种决定被退回默认了几次（按第一次出现的先后）。 */
    public Map<String, Integer> fallbackCounts() {
        return Collections.unmodifiableMap(fallbackCounts);
    }

    /** 把当前阶段<b>从头</b>走完（模拟器用：每个阶段开始时调一次）。 */
    public void playPhase() {
        switch (session.state().phase()) {
            case WEATHER -> session.beginWeather();
            case PROVISION -> provisionPhase();
            case ACTION -> actionPhase();
            case NAVIGATION -> navigationPhase();
        }
    }

    /**
     * 从此刻往后推，直到终局或者走完第 {@code lastTurn} 天（含）。
     *
     * <p>先把当前阶段<b>从半路</b>走完（补给箱传到一半、这一场打到一半、口渴结算到一半都接得上），再一个阶段一个阶段往下。
     */
    public void playUntil(int lastTurn) {
        finishPhase();
        while (!session.state().isOver()) {
            session.advancePhase();
            if (session.state().turn() > lastTurn) {
                return;
            }
            playPhase();
        }
    }

    /** 把当前阶段从半路走完。 */
    public void finishPhase() {
        if (session.state().isOver()) {
            return;
        }
        switch (session.state().phase()) {
            case WEATHER -> {
                // 天候阶段没有要人拿主意的地方，推演不会停在这里；天候已经翻过时不能再翻
                if (session.currentWeather().isEmpty()) {
                    session.beginWeather();
                }
            }
            case PROVISION -> provisionPhase();
            case ACTION -> actionPhase();
            case NAVIGATION -> navigationPhase();
        }
    }

    // ---------------------------------------------------------------- 物资

    private void provisionPhase() {
        // 礼拜天传完一轮再来一整轮（规则第十三章）；第几轮记在规则那一侧，半路接手也数得对
        int rounds = weatherIs(WeatherEffect.EXTRA_PROVISION) ? 2 : 1;
        while (true) {
            boolean dealt = false;
            if (!session.provisionInProgress()) {
                if (session.provisionRoundsToday() >= rounds) {
                    return;
                }
                dealt = !session.beginProvision().isEmpty();
            }
            while (session.provisionInProgress()) {
                keepOne();
            }
            if (dealt) {
                session.requireNoProvisionLost("物资阶段之后");
            }
        }
    }

    private void keepOne() {
        CharacterId holder = session.provisionHolder().orElseThrow();
        List<String> offer = session.provisionOffer();
        String keep = policy(holder).keepProvision(view(holder), offer, rng);
        if (keep == null || !offer.contains(keep)) {
            keep = fallback(holder, "补给箱留牌", keep, offer.getFirst());
        }
        session.provisionKeep(keep);
    }

    // ---------------------------------------------------------------- 行动

    private void actionPhase() {
        finishTurnInProgress();
        int guard = 0;
        while (true) {
            Optional<CharacterId> next = session.nextActor();
            if (next.isEmpty()) {
                return;                       // ❗没人能行动是合法状态，不是死锁
            }
            if (++guard > session.state().roster().size() * 4) {
                throw new IllegalStateException(
                        "%s 行动阶段推不动：nextActor 一直返回同一个人，标记没写回".formatted(session.context()));
            }
            playTurn(next.get());
        }
    }

    /** 半路接手时，上一个人的这一下还没做完（划船摸了牌没选、这一场没收场）：先替他做完、记下行动。 */
    private void finishTurnInProgress() {
        if (session.rower().isPresent()) {
            CharacterId rower = session.rower().get();
            keepRowCard(rower, session.rowing().stream().map(Session.RowCard::card).toList());
            session.markActed(rower);
        } else if (session.contest().isPresent()) {
            CharacterId actor = session.contest().get().attacker();
            runContest();
            session.markActed(actor);
        }
    }

    /**
     * 一个人的一天：亮牌 → 喝酒 → 送牌 → 行动 → 记下行动。
     *
     * <p>中间什么也没变时沿用同一个视角（大多数人不亮、不喝、不送）—— 一局要问上千次，每次都现造一个不值得。
     */
    private void playTurn(CharacterId actor) {
        SeatPolicy p = policy(actor);
        SeatView v = view(actor);
        List<String> revealable = Legal.reveals(session, actor);
        Optional<String> reveal = p.reveal(v, revealable, rng);
        if (reveal.isPresent()) {
            if (revealable.contains(reveal.get())) {
                session.reveal(actor, reveal.get());
                v = view(actor);
            } else {
                fallback(actor, "亮牌", reveal.get(), "不亮");
            }
        }
        List<String> drinkable = Legal.drinks(session, actor);
        Optional<String> drink = p.drink(v, drinkable, rng);
        if (drink.isPresent()) {
            if (drinkable.contains(drink.get())) {
                session.drinkRum(actor, drink.get());
                v = view(actor);
            } else {
                fallback(actor, "喝酒", drink.get(), "不喝");
            }
        }
        if (gifts(actor, v)) {
            v = view(actor);
        }
        List<ActionChoice> legal = Legal.actions(session, actor);
        ActionChoice choice = p.act(v, legal, rng);
        if (choice == null || !legal.contains(choice)) {
            choice = fallback(actor, "行动", choice, ActionChoice.PASS);
        }
        execute(actor, choice);
    }

    /**
     * 送牌：一直问到他交回空（外加一道上限，免得策略写坏了在这里转圈）。
     *
     * @return 送出过牌没有（送过的话视角要重造）
     */
    private boolean gifts(CharacterId from, SeatView current) {
        if (!Overrides.gives(policy(from))) {
            return false;                     // 没改写「送牌」的策略一律不送，连清单都不必列
        }
        SeatView v = current;
        boolean gave = false;
        for (int i = 0; i < 12; i++) {
            List<Gift> gifts = Legal.gifts(session, from);
            if (gifts.isEmpty()) {
                return gave;
            }
            Optional<Gift> gift = policy(from).give(v, gifts, rng);
            if (gift.isEmpty()) {
                return gave;
            }
            if (!gifts.contains(gift.get())) {
                fallback(from, "送牌", gift.get(), "不送");
                return gave;
            }
            Gift g = gift.get();
            session.giveCard(from, g.to(), g.card(), g.fromFront());
            gave = true;
            v = view(from);
        }
        return gave;
    }

    /**
     * 做这一天的行动，做完记下行动（{@link Session#markActed}）。
     *
     * <p>推演从「行动」这个决定接着往下走时，也从这里进来。
     */
    void execute(CharacterId actor, ActionChoice choice) {
        switch (choice) {
            case ActionChoice.Pass pass -> {
            }
            case ActionChoice.Row row -> {
                List<NavigationCard> drawn = session.beginRow(actor);
                if (!drawn.isEmpty()) {
                    keepRowCard(actor, drawn);
                }
            }
            case ActionChoice.Declare d -> {
                session.declare(actor, d.kind(), d.target());
                runContest();
            }
            case ActionChoice.Play play -> play(actor, play);
        }
        session.markActed(actor);
    }

    private void keepRowCard(CharacterId rower, List<NavigationCard> drawn) {
        int index = policy(rower).keepRowCard(view(rower), drawn, rng);
        if (index < 0 || index >= drawn.size()) {
            index = fallback(rower, "划船留牌", index, 0);
        }
        session.chooseRow(index);
    }

    private void play(CharacterId actor, ActionChoice.Play play) {
        ProvisionEffect effect = session.provisions().get(play.card()).effect();
        if (effect instanceof ProvisionEffect.Heal) {
            session.useMedicalKit(actor, play.target().orElseThrow(), play.card());
        } else if (effect instanceof ProvisionEffect.PreventThirst) {
            session.openParasol(actor, play.card());
        } else if (effect instanceof ProvisionEffect.HealAll) {
            // ❗走真人那一条路（逐个问反不反对），不走不问人的 useRation：会动脑的替身要能反对。
            //   随机席位一律不反对、也不摇骰子，所以对随机桌来说两条路的结果一模一样（RandomSeatsReproduceTest 钉着）。
            if (session.beginRation(actor, play.card()).isEmpty()) {
                runContest();
            }
        } else if (effect instanceof ProvisionEffect.WeaponOrSpecial) {
            session.fireSignal(actor, play.card());
        } else {
            throw new IllegalStateException(play.card() + " 不是能花行动打出的牌，合法清单不该列它");
        }
    }

    // ---------------------------------------------------------------- 换座位 · 抢 · 分食反对

    /** 把进行中的这一场推到收场（表态 → 站队 → 押武器 → 结算 → 挑牌）。 */
    private void runContest() {
        boolean fought = false;
        while (session.contest().isPresent()) {
            Contest c = session.contest().get();
            switch (c.stage()) {
                case CONSENT -> consent(c);
                case STANCES -> {
                    fought = true;
                    stances(c, null);
                }
                case WEAPONS -> weapons(c, null, true);
                case PICK -> pick(c);
            }
        }
        if (fought) {
            fights++;
        }
    }

    private void consent(Contest c) {
        CharacterId who = c.target();
        boolean refuse = policy(who).refuse(view(who), rng);
        if (refuse && !session.state().canAct(who)) {
            refuse = fallback(who, "表态", true, false);
        }
        session.consent(refuse);
    }

    /**
     * 站队：依座位问每个清醒的旁观者，问完收队。
     *
     * @param after 从这个人之后接着问（推演从某人的站队决定接着走时）；{@code null} = 从头问
     */
    private void stances(Contest c, CharacterId after) {
        Fight opened = c.fight().orElseThrow();
        boolean started = after == null;
        for (CharacterId helper : session.state().consciousBySeat()) {
            if (!started) {
                started = helper.equals(after);
                continue;
            }
            if (opened.combatants().contains(helper)) {
                continue;
            }
            Optional<Fight.Side> side = policy(helper).joinStance(view(helper), rng);
            side.ifPresent(s -> session.join(helper, s));
        }
        session.closeStances();
    }

    /**
     * 押武器：先问参战者要不要喝酒，再依参战次序问押不押，问完结算。
     *
     * @param after  从这个人之后接着问押武器；{@code null} = 从头问
     * @param drinks 要不要先问那一轮喝酒（从半路接着押武器时，那一轮已经问过了）
     */
    private void weapons(Contest c, CharacterId after, boolean drinks) {
        List<CharacterId> combatants = List.copyOf(c.fight().orElseThrow().combatants());
        if (drinks) {
            for (CharacterId who : combatants) {
                if (!Overrides.drinksForFight(policy(who))) {
                    continue;
                }
                List<String> drinkable = Legal.drinks(session, who);
                if (drinkable.isEmpty()) {
                    continue;
                }
                Optional<String> d = policy(who).drinkForFight(view(who), drinkable, rng);
                if (d.isPresent()) {
                    if (drinkable.contains(d.get())) {
                        session.drinkRum(who, d.get());
                    } else {
                        fallback(who, "打架前喝酒", d.get(), "不喝");
                    }
                }
            }
        }
        boolean started = after == null;
        for (CharacterId who : combatants) {
            if (!started) {
                started = who.equals(after);
                continue;
            }
            List<String> weapons = Legal.weapons(session, who);
            List<String> chosen = policy(who).commitWeapons(view(who), weapons, rng);
            if (chosen == null || !subMultiset(chosen, weapons)) {
                chosen = fallback(who, "押武器", chosen, List.of());
            }
            for (String card : chosen) {
                session.commitWeapon(who, card);
            }
        }
        session.resolveContest();
    }

    private void pick(Contest c) {
        CharacterId attacker = c.attacker();
        List<PickChoice> legal = Legal.picks(session);
        PickChoice choice = policy(attacker).pick(view(attacker), legal, rng);
        if (choice == null || !legal.contains(choice)) {
            // 规则第七章的超时默认：从他手里随机摸一张；他手里没牌，就拿他面前的第一张
            choice = fallback(attacker, "挑牌", choice,
                    legal.contains(PickChoice.FROM_HAND) ? PickChoice.FROM_HAND : legal.getFirst());
        }
        applyPick(c, choice);
    }

    private void applyPick(Contest c, PickChoice choice) {
        if (choice instanceof PickChoice.FromFront front) {
            session.pickFromFront(front.card());
        } else {
            // 摸哪一张是天意，不是挑牌的人能定的：用这一局的随机流
            session.pickFromHand(rng.nextInt(session.state().stateOf(c.target()).hand().size()));
        }
    }

    // ---------------------------------------------------------------- 航海

    private void navigationPhase() {
        // 从半路接手：手上这一张先结算完
        if (session.overboardPending().isPresent() || session.thirstInProgress()) {
            finishCard(false);
            if (session.state().isOver()) {
                return;
            }
        }
        session.finishNavigationResolution();   // 刚结算完的若是狂风那一张，把标志收掉；没有时什么也不做
        if (session.navigationComplete()) {
            return;
        }
        if (session.weatherNavigationPending()) {
            // 狂风：先从牌堆顶多翻一张，整张结算完，再照常由舵手挑牌（规则第十三章）
            session.beginNavigation(session.takeWeatherNavigationCard());
            finishCard(true);
            if (session.state().isOver()) {
                return;
            }
            session.finishNavigationResolution();
        }
        if (weatherIs(WeatherEffect.SKIP_NAVIGATION)) {
            session.skipNavigation();
            return;
        }
        session.prepareRowStack();           // 舵手握着指南针时，挑牌之前多抽一张进划船堆
        NavigationCard pick = null;
        if (session.helmsmanMayPick()) {
            CharacterId helm = session.state().helmsman().orElseThrow();
            List<NavigationCard> stack = session.table().rowStack();
            pick = policy(helm).steer(view(helm), stack, rng);
            if (pick == null || !stack.contains(pick)) {
                pick = fallback(helm, "舵手挑牌", pick, stack.getFirst());
            }
        }
        session.beginNavigation(session.takeCardForNavigation(pick));
        finishCard(true);
    }

    /**
     * 一张航海牌从落海那一刻起结算完：每一批落海问一遍扔救生圈 / 血饵，然后逐个结算口渴。
     *
     * @param report 落海结算完时往外报一次（半路接手、落海早已结算过时不报）
     */
    private void finishCard(boolean report) {
        while (session.overboardPending().isPresent()) {
            overboardWindow(null);
            session.finishOverboard();
        }
        if (report) {
            NavigationReport r = session.navigationReport();
            listener.navigated(r);
        }
        while (!session.state().isOver()) {
            Optional<Session.ThirstPrompt> prompt = session.thirstPending();
            if (prompt.isEmpty()) {
                return;
            }
            thirst(prompt.get());
        }
    }

    /**
     * 落海那一刻：依座位问每个清醒的、手里有牌可打的人；有人打了就再问一轮（救生圈扔出去之后，别人的选项就变了），
     * 直到一轮下来没人打。
     *
     * @param after 第一轮从这个人之后接着问；{@code null} = 从头问
     */
    private void overboardWindow(CharacterId after) {
        int token = session.overboardPending().orElseThrow().token();
        boolean again = true;
        boolean started = after == null;
        for (int round = 0; again && round < 8; round++) {
            again = false;
            for (CharacterId who : session.state().bySeat()) {
                if (!started) {
                    started = who.equals(after);
                    continue;
                }
                if (!Overrides.overboards(policy(who))) {
                    continue;
                }
                List<Session.OverboardPlay> plays = Legal.overboard(session, who);
                if (plays.isEmpty()) {
                    continue;
                }
                Optional<Session.OverboardPlay> play = policy(who).overboard(view(who), plays, rng);
                if (play.isEmpty()) {
                    continue;
                }
                if (!plays.contains(play.get())) {
                    fallback(who, "落海时打牌", play.get(), "不打");
                    continue;
                }
                session.playOverboardCard(who, play.get().target(), play.get().card(), token);
                again = true;
            }
            started = true;
        }
    }

    /** 一个人的口渴：先问他自己喝几次、要不要开口，再依座位问别人递不递，最后一起结算。 */
    private void thirst(Session.ThirstPrompt prompt) {
        CharacterId who = prompt.who();
        int per = prompt.waterPerSource();
        int ownUnits = Legal.ownWaterUnits(session, who, per);
        viewCache.prompt = prompt;
        WaterPlan plan;
        try {
            plan = policy(who).drinkWater(view(who), ownUnits, rng);
        } finally {
            viewCache.prompt = null;
        }
        int maxOwn = Math.min(ownUnits, prompt.remaining());
        if (plan == null || plan.units() > maxOwn) {
            plan = fallback(who, "喝水", plan, new WaterPlan(Math.min(maxOwn, plan == null ? 0 : plan.units()),
                    plan != null && plan.askHelp()));
        }
        List<CharacterId> donors = new ArrayList<>(Collections.nCopies(plan.units() * per, who));
        donate(prompt, plan, donors, null);
    }

    /**
     * 依座位问别人递不递水，问完结算这一个人的口渴。
     *
     * @param donors 已经定下的出水人（一张水一项）：本人喝的，加上在 {@code after} 之前递过的
     * @param after  从这个人之后接着问；{@code null} = 从头问
     */
    private void donate(Session.ThirstPrompt prompt, WaterPlan plan, List<CharacterId> donors, CharacterId after) {
        CharacterId who = prompt.who();
        int per = prompt.waterPerSource();
        int donated = (int) donors.stream().filter(d -> !d.equals(who)).count() / per;
        int shortUnits = prompt.remaining() - plan.units() - donated;
        boolean started = after == null;
        viewCache.prompt = prompt;            // 问递水的人时局面不变：这一份账一直有效，结算之前清掉
        try {
            for (CharacterId other : session.state().bySeat()) {
                if (!started) {
                    started = other.equals(after);
                    continue;
                }
                if (shortUnits <= 0) {
                    break;
                }
                if (other.equals(who) || !session.state().canAct(other)) {
                    continue;
                }
                int myUnits = session.watersOf(other) / per;
                if (myUnits == 0 || !Overrides.donates(policy(other))) {
                    continue;
                }
                int give = policy(other).donateWater(view(other), who, shortUnits, myUnits, plan.askHelp(),
                        donated, rng);
                if (give < 0 || give > Math.min(myUnits, shortUnits)) {
                    give = fallback(other, "递水", give, 0);
                }
                donors.addAll(Collections.nCopies(give * per, other));
                donated += give;
                shortUnits -= give;
            }
        } finally {
            viewCache.prompt = null;
        }
        session.decideThirst(donors);
    }

    // ---------------------------------------------------------------- 推演：从某个决定之后接着走
    //
    // 推演在抽出来的局面上替「我」把一个决定做下去：每一种要推演的决定各有一个入口，收下那个答案，
    // 把驱动者自己此刻会接着做的那几步做完，停在 {@link #finishPhase} / {@link #playUntil} 接得上的地方
    // （这一场收了场、发起者的行动记下了）。答案要合法 —— 这里不退回默认，那是策略那一侧的事。
    // {@code SearchSeatPolicyTest} 逐个核对：同一个答案，走入口再 finishPhase，与驱动者自己往下走，局面与随机流一模一样。

    /** 补给箱留牌：留下这一张，余下的传给下一位（之后由 {@link #finishPhase} 接着传）。 */
    void resumeProvision(String card) {
        session.provisionKeep(card);
    }

    /** 行动：做这一天的行动、记下行动（与驱动者自己走的是同一条路）。 */
    void resumeAction(CharacterId actor, ActionChoice choice) {
        execute(actor, choice);
    }

    /** 划船留牌：留第几张，记下划船的人的行动。 */
    void resumeRowCard(int index) {
        CharacterId rower = session.rower().orElseThrow();
        session.chooseRow(index);
        session.markActed(rower);
    }

    /** 表态：把这一场推到收场，记下发起者的行动。 */
    void resumeConsent(boolean refuse) {
        CharacterId attacker = session.contest().orElseThrow().attacker();
        session.consent(refuse);
        runContest();
        session.markActed(attacker);
    }

    /** 站队：我（{@code self}）站这一边（或不站），接着问我后面的人、收队、押武器、结算、挑牌，记下发起者的行动。 */
    void resumeStance(CharacterId self, Optional<Fight.Side> side) {
        Contest c = session.contest().orElseThrow();
        side.ifPresent(s -> session.join(self, s));
        stances(c, self);
        runContest();
        session.markActed(c.attacker());
    }

    /** 押武器：我（{@code self}）押这几张，接着问我后面的参战者、结算、挑牌，记下发起者的行动。 */
    void resumeWeapons(CharacterId self, List<String> cards) {
        Contest c = session.contest().orElseThrow();
        for (String card : cards) {
            session.commitWeapon(self, card);
        }
        weapons(c, self, false);
        runContest();
        session.markActed(c.attacker());
    }

    /** 挑牌：从手里摸时摸哪张照这一局的随机流（天意），收场，记下发起者的行动。 */
    void resumePick(PickChoice choice) {
        Contest c = session.contest().orElseThrow();
        applyPick(c, choice);
        runContest();
        session.markActed(c.attacker());
    }

    /** 舵手挑牌：执行这一张（落海、口渴由 {@link #finishPhase} 接着结算）。 */
    void resumeSteer(NavigationCard card) {
        session.beginNavigation(session.takeCardForNavigation(card));
    }

    /** 喝水：轮到口渴的那个人自己喝几次、开不开口；接着问递水的人、结算他这一次（之后的人由 {@link #finishPhase} 接着问）。 */
    void resumeWater(WaterPlan plan) {
        Session.ThirstPrompt prompt = session.thirstPending().orElseThrow();
        List<CharacterId> donors = new ArrayList<>(Collections.nCopies(plan.units() * prompt.waterPerSource(),
                prompt.who()));
        donate(prompt, plan, donors, null);
    }

    // ---------------------------------------------------------------- 杂项

    private boolean weatherIs(WeatherEffect effect) {
        return session.currentWeather().map(w -> w.effect() == effect).orElse(false);
    }

    private SeatPolicy policy(CharacterId id) {
        return policies.get(id);
    }

    private SeatView view(CharacterId id) {
        return SeatView.of(session, id, viewCache);
    }

    private <T> T fallback(CharacterId who, String decision, Object got, T instead) {
        fallbacks++;
        if (fallbackCounts.merge(decision, 1, Integer::sum) <= FALLBACK_LOG_PER_DECISION) {
            fallbackLog.add("%s 第 %d 天 %s 的%s交回了 %s，退回 %s（策略 %s）".formatted(session.context(),
                    session.state().turn(), who.value(), decision, got, instead, policy(who).label()));
        }
        return instead;
    }

    /** {@code chosen} 里每一种牌的张数都不超过 {@code pool} 里的。 */
    private static boolean subMultiset(List<String> chosen, List<String> pool) {
        Map<String, Integer> left = new HashMap<>();
        pool.forEach(c -> left.merge(c, 1, Integer::sum));
        for (String c : chosen) {
            Integer n = left.get(c);
            if (n == null || n == 0) {
                return false;
            }
            left.put(c, n - 1);
        }
        return true;
    }
}
