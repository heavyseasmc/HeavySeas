package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.seat.ActionChoice;
import io.github.heavyseasmc.engine.seat.Gift;
import io.github.heavyseasmc.engine.seat.Legal;
import io.github.heavyseasmc.engine.seat.PickChoice;
import io.github.heavyseasmc.engine.seat.SearchSeatPolicy;
import io.github.heavyseasmc.engine.seat.SeatPolicy;
import io.github.heavyseasmc.engine.seat.SeatPolicySettings;
import io.github.heavyseasmc.engine.seat.SeatView;
import io.github.heavyseasmc.engine.seat.WaterPlan;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.llm.ChoiceOutcome;
import io.github.heavyseasmc.mod.llm.ChoiceRequest;
import io.github.heavyseasmc.mod.llm.DecisionKind;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.StandInMind;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 「动脑」与「大模型」两种替身（{@code /seas dummy smart|llm}）：每一个要替身拿主意的地方，都从这里问它的脑子。
 *
 * <h2>每一处都照同一个样子走（{@link StandInThinker}）</h2>
 * 主线程照相（{@link SeatView} 与 {@link Legal} 给的合法选项）→ 工作线程想（动脑：引擎的第二层 {@link SearchSeatPolicy}，
 * 设置里关了推演就是第一层 {@code HeuristicSeatPolicy}；大模型那一种先按第一层算出一个答案、再问模型，
 * 模型没给出能用的编号就用它）→ 回主线程，排到「想完」与「拍子到」晚的那一刻 →
 * 核对这个决定还开着、答案还合法 → 走<b>真人与随机替身走的同一条路</b>（{@link ActionPhase} · {@link ContestPhase} ·
 * {@link ProvisionPhase} · {@link NavigationPhase} · {@link ThirstPhase} · {@link CardActions} 里那几个方法）：
 * 播报、窗口、HUD 同步一样不少。
 *
 * <h2>节奏</h2>
 * 轮到替身的那一刻开始想，做出来的时刻照随机替身的拍子（{@link StandInPlay#beat} · {@link StandInPlay#step}）：
 * 想得快的照旧等拍子，想得慢的（大模型）想完再做。窗口里陆续站队 / 押武器的延迟也照随机替身那一套分布，只是从这一座自己的随机流里摇。
 *
 * <h2>两种「这一段」</h2>
 * 有真人要等的那一段开着窗口：替身在窗口里各想各的、陆续站出来（{@link #contestWindow}）。没有真人要等的那一段不开窗口，
 * 替身<b>依座位一个一个</b>答（后答的看得见先站出来的），答完由这里往下推（{@link #contestStage}）——
 * 与随机替身「排一拍、然后当场全做完」是同一个次序，只是每一个都真的想过。
 *
 * <h2>决策 ②</h2>
 * 这是开发 / 演示用的开关，默认关、不持久化；正式计分局里用不用替身补空座还没定（用户 2026-10-07「打过再定」）。
 */
public final class StandInMinds {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 送牌最多连着问几次（策略写坏了也不会在这里转圈；与引擎驱动者同一个数）。 */
    private static final int MAX_GIFTS = 12;

    /** 落海那一窗最多问几轮（有人打了牌就再问一轮：救生圈扔出去之后，别人的选项就变了。与引擎驱动者同一个数）。 */
    private static final int MAX_OVERBOARD_ROUNDS = 8;

    /** 问大模型时离窗口到点留多少：落地、回主线程、排到下一 tick 都在里面（大模型那一层自己还会再留一截）。 */
    static final long WINDOW_MARGIN_MS = 1_000L;

    /** 「想的上限」在给模型的时限之外再宽限多久：模型那一层到点会自己收，这一截只防它不收。 */
    private static final long THINK_SLACK_MS = 1_000L;

    private StandInMinds() {
    }

    /** 停服时收掉工作线程；每 tick 让看门狗看一眼。模组入口调一次。 */
    public static void register() {
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> StandInThinker.shutdown());
        ServerTickEvents.END_SERVER_TICK.register(server -> StandInThinker.sweep(System.currentTimeMillis()));
    }

    /** 动脑 / 大模型开着（而且自动推进开着：关着时替身等指令）。 */
    public static boolean thinks(GameComponent component) {
        return component.dummyAutoplay() && component.dummyMind().thinks();
    }

    /** 这一座用哪种脑子：大模型那一种可以只给指定的几座（{@code /seas dummy llm seats}），其余的动脑。 */
    public static StandInMind mindOf(GameComponent component, CharacterId seat) {
        StandInMind mind = component.dummyMind();
        if (mind == StandInMind.LLM && !component.llmSeats().isEmpty() && !component.llmSeats().contains(seat)) {
            return StandInMind.SMART;
        }
        return mind;
    }

    /**
     * {@code /seas dummy} 那一行：脑子 · 自动推进 · 快慢档；大模型那一种再加「给哪几座」与大模型那一层开没开、密钥取自哪里；
     * 动脑 / 大模型再加这次起服以来的决定数。❗不写密钥（只写模型名、密钥的来源与关着的原因）。
     */
    public static String status(GameComponent component) {
        StandInMind mind = component.dummyMind();
        StringBuilder sb = new StringBuilder("替身脑子：").append(mind.label())
                .append("（").append(mind.name().toLowerCase(java.util.Locale.ROOT)).append("）")
                .append(" · 自动推进").append(component.dummyAutoplay() ? "开" : "关");
        if (mind.acts()) {
            sb.append(component.dummyFast() ? " · 快档" : " · 慢档");
        }
        if (mind.thinks()) {
            var s = StandInSettings.smart();
            sb.append(" · 动脑那一层（下一局）：").append(s.search()
                    ? "第二层（每步推演 " + s.rollouts() + " 局 · 留 " + s.width() + " 个候选 · 看 " + s.horizonDays()
                    + " 天 · 限时 " + s.millisPerDecision() + " ms）" : "第一层");
        }
        if (mind == StandInMind.LLM) {
            sb.append(" · 问大模型的座位：").append(component.llmSeats().isEmpty() ? "每一座"
                    : String.join("、", component.llmSeats().stream().map(CharacterId::value).sorted().toList()));
            var llm = StandInSettings.llm();
            sb.append(" · 大模型那一层：").append(llm.enabled() ? "开（" + llm.config().model() + "）"
                    : "关（" + llm.disabledWhy() + "；替身照动脑那一层走）");
            sb.append(" · 密钥：").append(io.github.heavyseasmc.mod.llm.LlmHooks.keySource());
        }
        if (mind.thinks()) {
            sb.append(" · ").append(StandInThinker.tally());
        }
        return sb.toString();
    }

    /** 有真人在等的这一段收尾时要不要多留一截：大模型那一种在窗口里想，一次最多 {@link StandInSettings#llmDecisionCapMs()}。 */
    static long windowTailMs(GameComponent component, long tail) {
        if (component.dummyAutoplay() && component.dummyMind() == StandInMind.LLM && StandInSettings.llm().enabled()) {
            return Math.max(tail, StandInSettings.llmDecisionCapMs() + WINDOW_MARGIN_MS);
        }
        return tail;
    }

    /** 这一局这一座的随机流（窗口里陆续动手的延迟从这里摇，与它想的时候用的是同一条）。 */
    static Random seatRng(GameComponent component, Session session, CharacterId who) {
        return seatOf(component, session, who).rng();
    }

    /**
     * 每座的两种策略从设置的接缝里造（动脑：旋钮里开着推演就是第二层；大模型先算的那个答案：第一层）。
     * 旋钮一局取一次（一局里第一次造座位时）：设置菜单里改了，下一局才换。
     */
    private static final StandInThinker.Policies POLICIES = new StandInThinker.Policies() {
        @Override
        public SeatPolicySettings settings() {
            return StandInSettings.smart();
        }

        @Override
        public SeatPolicy smart(SeatPolicySettings settings, long seed) {
            return StandInSettings.smartPolicy(settings, seed);
        }

        @Override
        public SeatPolicy quick(SeatPolicySettings settings) {
            return StandInSettings.quickPolicy(settings);
        }
    };

    private static StandInThinker.Seat seatOf(GameComponent component, Session session, CharacterId who) {
        return StandInThinker.seat(session, component.standInSeed(), who, seatIndex(session, who), POLICIES);
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    // ================================================================ 一次决定的公共部分

    /** 在工作线程上调策略。 */
    @FunctionalInterface
    interface Smart<L, T> {
        T choose(SeatPolicy policy, SeatView view, L legal, Random rng);
    }

    /** 在工作线程上拼给大模型的问题；不问大模型的那几种给 {@code null}。 */
    @FunctionalInterface
    interface Ask<L, T> {
        SeatQuestions.Question<T> ask(SeatView view, L legal, SeatQuestions.Names names);
    }

    /**
     * 一个决定要的东西（调用方填）。
     *
     * @param windowDeadline 这个决定所在的窗口几点到（0 = 没有窗口）：问大模型的时限不超过它
     * @param legal          合法选项（交给策略；也给大模型拼选项）
     * @param stillLegal     到点时这个答案还合法吗（主线程，此刻的局面）
     */
    private record Point<L, T>(ServerWorld world, GameComponent component, CharacterId seat, String what, String key,
                               DecisionKind kind, long applyNotBefore, long windowDeadline, L legal, Smart<L, T> smart,
                               Ask<L, T> ask, BooleanSupplier open, Predicate<T> stillLegal, Consumer<T> apply,
                               Runnable idle, Function<T, String> describe) {
    }

    private static StandInThinker.Host host(ServerWorld world, GameComponent component) {
        MinecraftServer server = world.getServer();
        return new StandInThinker.Host() {
            @Override
            public Object epoch() {
                return component.session().orElse(null);
            }

            @Override
            public void execute(Runnable task) {
                // 停服之后不再往主线程塞：那时 execute 会就地跑在调它的线程上
                if (server.isRunning()) {
                    server.execute(task);
                }
            }

            @Override
            public void schedule(long dueMs, String what, Runnable task) {
                if (component.session().isPresent()) {
                    component.schedule(dueMs, what, task);
                }
            }

            @Override
            public long now() {
                return System.currentTimeMillis();
            }
        };
    }

    /** 开一个决定：照相（就在这里，主线程）、交给工作线程想。 */
    private static <L, T> boolean decide(Point<L, T> p) {
        GameComponent component = p.component();
        Session session = component.requireSession();
        StandInMind mind = mindOf(component, p.seat());
        StandInThinker.Seat seat = seatOf(component, session, p.seat());
        // 大模型那一座的每个决定都用第一层（问模型的那几种是先算的答案与退路；不问的那几种第一层与第二层本来就一样）
        SeatPolicy policy = mind == StandInMind.LLM ? seat.quick() : seat.policy();
        boolean llm = mind == StandInMind.LLM && p.ask() != null && StandInSettings.llmKinds().contains(p.kind());
        SeatQuestions.Names names = llm ? SeatQuestions.Names.of(StandInSettings.llm().language()) : null;
        // 照相：视角与合法选项都是不可变的快照，交出去之后与这一局无关
        SeatQuestions.Snapshot<L> snap = llm ? SeatQuestions.Snapshot.take(session, p.seat(), p.legal(), names)
                : new SeatQuestions.Snapshot<>(SeatView.of(session, p.seat()), p.legal(), p.seat().value());
        long opened = now();
        long deadline = opened + (llm ? StandInSettings.llmDecisionCapMs()
                : StandInSettings.smartThinkCapMs(seat.settings()));
        if (p.windowDeadline() > 0) {
            deadline = Math.min(deadline, p.windowDeadline() - WINDOW_MARGIN_MS);
        }
        long budget = Math.max(0L, deadline - opened);
        Instant askBy = Instant.ofEpochMilli(opened + budget);
        StandInThinker.Spec<T> spec = new StandInThinker.Spec<>(host(p.world(), component), session, p.key(),
                p.seat().value(), mind, p.what(), opened, p.applyNotBefore(), budget + THINK_SLACK_MS, p.open(),
                p.stillLegal(), () -> thinks(component), p.apply(), p.idle(), p.describe());
        return StandInThinker.decide(spec, () -> think(p, policy, seat.rng(), snap, names, llm, askBy));
    }

    /**
     * 工作线程：先算动脑那一层的答案；大模型那一种再问模型，模型没给出能用的编号就用前者。
     *
     * <p>❗同一个策略实例同一时刻只许一个线程用（第二层带着自己的随机流与统计）：一座的上一个决定作废了、它的推演还在
     * 另一条线程上跑最后一截时，下一个决定就会撞上它 —— 所以锁着实例调。第二层这一次推演了几局、出错几次，
     * 记进这个决定那一行日志。
     */
    private static <L, T> CompletableFuture<StandInThinker.Thought<T>> think(Point<L, T> p, SeatPolicy policy,
                                                                            Random rng, SeatQuestions.Snapshot<L> snap,
                                                                            SeatQuestions.Names names, boolean llm,
                                                                            Instant askBy) {
        T smart;
        String detail = null;
        synchronized (policy) {
            if (policy instanceof SearchSeatPolicy search) {
                long rollouts = search.rollouts();
                int failures = search.failures();
                smart = p.smart().choose(policy, snap.view(), snap.legal(), rng);
                int failed = search.failures() - failures;
                detail = "推演=" + (search.rollouts() - rollouts) + "局 推演出错=" + failed;
                if (failed > 0) {
                    LOGGER.warn("替身（动脑）：{} 的「{}」推演出错，退回第一层：{}", p.seat().value(), p.what(),
                            search.lastFailure().orElse("?"));
                }
            } else {
                smart = p.smart().choose(policy, snap.view(), snap.legal(), rng);
            }
        }
        if (!llm) {
            return CompletableFuture.completedFuture(StandInThinker.Thought.smart(smart).withDetail(detail));
        }
        return consult(smart, () -> p.ask().ask(snap.view(), snap.legal(), names), StandInSettings.llm()::choose,
                snap.seatLabel(), askBy, p.seat().value() + " 的「" + p.what() + "」");
    }

    /**
     * 大模型那一种的「想」：拼问题、问模型。模型没给出能用的编号（关着 · 超时 · HTTP 错 · 认不出 · 熔断……）、
     * 问题拼不出来、接入层自己出了错，一律用 {@code smart}（动脑那一层先算好的答案）—— 「来源」那一栏写明是哪一种。
     *
     * @param smart    动脑那一层的答案
     * @param question 在这里（工作线程）拼问题
     * @param llm      问模型（{@code LlmService#choose}；单测换成假的）
     * @param what     日志里这是谁的哪一个决定
     */
    static <T> CompletableFuture<StandInThinker.Thought<T>> consult(T smart, Supplier<SeatQuestions.Question<T>> question,
                                                                   Function<ChoiceRequest, CompletableFuture<ChoiceOutcome>> llm,
                                                                   String seatLabel, Instant askBy, String what) {
        SeatQuestions.Question<T> q;
        CompletableFuture<ChoiceOutcome> outcome;
        try {
            q = question.get();
            if (q.choices().size() == 1) {
                // 只有一项可选（箱里只剩一种牌之类）：不必花一次请求，来源写明没问
                return CompletableFuture.completedFuture(
                        new StandInThinker.Thought<>(q.choices().getFirst(), "only:只有一项", null));
            }
            outcome = llm.apply(q.request(seatLabel, askBy));
        } catch (RuntimeException e) {
            LOGGER.warn("替身（大模型）：{} 问不出去（{}），用动脑那一层的答案", what, e.toString());
            return CompletableFuture.completedFuture(new StandInThinker.Thought<>(smart, "smart-fallback:问不出去", null));
        }
        SeatQuestions.Question<T> asked = q;
        return outcome.handle((o, error) -> {
            if (error != null || o == null) {
                LOGGER.warn("替身（大模型）：{} 接入层出错（{}），用动脑那一层的答案", what, String.valueOf(error));
                return new StandInThinker.Thought<>(smart, "smart-fallback:接入层出错", null);
            }
            if (o.choice().isPresent() && o.choice().getAsInt() >= 0 && o.choice().getAsInt() < asked.choices().size()) {
                return new StandInThinker.Thought<>(asked.choices().get(o.choice().getAsInt()), "llm", o.trailText());
            }
            return new StandInThinker.Thought<>(smart, "smart-fallback:" + (o.chosen() ? "编号越界" : o.fallback()),
                    o.trailText());
        });
    }

    /** 这一座在阵容里的位次（开局时定，之后不变）：派生它自己那条随机流用。 */
    private static int seatIndex(Session session, CharacterId id) {
        List<Survivor> all = session.state().roster().survivors();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(id)) {
                return i;
            }
        }
        return all.size();
    }

    private static boolean sameGame(GameComponent component, Session session) {
        return component.session().map(s -> s == session).orElse(false);
    }

    private static boolean isDummy(GameComponent component, CharacterId who) {
        return component.occupantOf(who).map(GameComponent.Occupant::isDummy).orElse(false);
    }

    // ================================================================ 行动：亮牌 → 喝酒 → 送牌 → 五选一

    /** 一个替身的一天：亮牌 → 喝酒 → 送牌 → 行动，一件接一件（后一件看得见前一件做了什么）。 */
    private record Turn(ServerWorld world, GameComponent component, Session session, CharacterId dummy, int turn,
                        long applyAt) {

        /** 还是这一局、这一天、轮到他、没有一件事做到一半。 */
        boolean open() {
            return sameGame(component, session) && session.state().phase() == Phase.ACTION
                    && session.state().turn() == turn && session.rower().isEmpty() && session.contest().isEmpty()
                    && component.designating().isEmpty() && session.nextActor().map(dummy::equals).orElse(false);
        }

        String key(String step) {
            return "turn:" + dummy.value() + ":" + turn + ":" + step;
        }
    }

    /**
     * 轮到替身行动：开始想。亮牌、喝酒、送牌这几件不占行动的事先一件件问（照引擎驱动者的次序），最后问这一天做什么。
     *
     * @param applyAt 最早什么时候做（轮到它的那一刻加拍子）
     */
    static void beginTurn(ServerWorld world, GameComponent component, CharacterId dummy, long applyAt) {
        Session session = component.requireSession();
        reveal(new Turn(world, component, session, dummy, session.state().turn(), applyAt));
    }

    private static void reveal(Turn t) {
        List<String> revealable = Legal.reveals(t.session(), t.dummy());
        if (revealable.isEmpty()) {
            drink(t);
            return;
        }
        decide(new Point<List<String>, Optional<String>>(t.world(), t.component(), t.dummy(), "亮牌", t.key("reveal"),
                DecisionKind.FREE_ACTION, t.applyAt(), 0L, revealable, (p, v, l, r) -> p.reveal(v, l, r), null,
                t::open,
                choice -> choice != null
                        && (choice.isEmpty() || Legal.reveals(t.session(), t.dummy()).contains(choice.get())),
                choice -> {
                    choice.ifPresent(card -> CardActions.revealForStandIn(t.world(), t.component(), t.dummy(), card));
                    drink(t);
                },
                () -> drink(t),
                choice -> choice.map(card -> "亮出 " + card).orElse("不亮")));     // 亮出来就是公开的
    }

    private static void drink(Turn t) {
        List<String> drinkable = Legal.drinks(t.session(), t.dummy());
        if (drinkable.isEmpty()) {
            gift(t, 0);
            return;
        }
        decide(new Point<List<String>, Optional<String>>(t.world(), t.component(), t.dummy(), "喝酒", t.key("drink"),
                DecisionKind.FREE_ACTION, t.applyAt(), 0L, drinkable, (p, v, l, r) -> p.drink(v, l, r), null,
                t::open,
                choice -> choice != null
                        && (choice.isEmpty() || Legal.drinks(t.session(), t.dummy()).contains(choice.get())),
                choice -> {
                    choice.ifPresent(card -> ActionPhase.drinkForStandIn(t.world(), t.component(), t.dummy(), card));
                    gift(t, 0);
                },
                () -> gift(t, 0),
                choice -> choice.map(card -> "喝 " + card).orElse("不喝")));
    }

    private static void gift(Turn t, int given) {
        List<Gift> gifts = given >= MAX_GIFTS ? List.of() : Legal.gifts(t.session(), t.dummy());
        if (gifts.isEmpty()) {
            act(t);
            return;
        }
        decide(new Point<List<Gift>, Optional<Gift>>(t.world(), t.component(), t.dummy(), "送牌",
                t.key("gift" + given), DecisionKind.FREE_ACTION, t.applyAt(), 0L, gifts,
                (p, v, l, r) -> p.give(v, l, r), null, t::open,
                choice -> choice != null
                        && (choice.isEmpty() || Legal.gifts(t.session(), t.dummy()).contains(choice.get())),
                choice -> {
                    if (choice.isEmpty()) {
                        act(t);
                        return;
                    }
                    Gift g = choice.get();
                    CardActions.giveForStandIn(t.world(), t.component(), t.dummy(), g.to(), g.card(), g.fromFront());
                    gift(t, given + 1);
                },
                () -> act(t),
                // 手牌是暗的：送的是哪一张不写（面前的那几张本来就亮着）
                choice -> choice.map(g -> "送 " + g.to().value() + (g.fromFront() ? " 面前的 " + g.card() : " 一张手牌"))
                        .orElse("不送")));
    }

    private static void act(Turn t) {
        List<ActionChoice> legal = Legal.actions(t.session(), t.dummy());
        decide(new Point<List<ActionChoice>, ActionChoice>(t.world(), t.component(), t.dummy(), "行动", t.key("act"),
                DecisionKind.ACTION, t.applyAt(), t.component().actionDeadline(), legal,
                (p, v, l, r) -> p.act(v, l, r), SeatQuestions::action, t::open,
                choice -> choice != null && Legal.actions(t.session(), t.dummy()).contains(choice),
                choice -> execute(t, choice),
                () -> ActionPhase.passForStandIn(t.world(), t.component(), t.dummy()),
                StandInMinds::describe));
    }

    static String describe(ActionChoice choice) {
        return switch (choice) {
            case ActionChoice.Pass pass -> "什么也不做";
            case ActionChoice.Row row -> "划船";
            case ActionChoice.Declare d -> (d.kind() == Contest.Kind.SWAP ? "换座位 " : "抢 ") + d.target().value();
            case ActionChoice.Play play -> "打出 " + play.card() + play.target().map(x -> " → " + x.value()).orElse("");
        };
    }

    /** 照选中的那一件做：都走真人与随机替身那几条路。 */
    private static void execute(Turn t, ActionChoice choice) {
        switch (choice) {
            case ActionChoice.Pass pass -> ActionPhase.passForStandIn(t.world(), t.component(), t.dummy());
            case ActionChoice.Row row -> {
                if (ActionPhase.beginRowForStandIn(t.world(), t.component(), t.dummy())) {
                    row(t);
                }
            }
            case ActionChoice.Declare d -> ContestPhase.declare(t.world(), t.component(), t.dummy(), d.kind(), d.target());
            case ActionChoice.Play play -> {
                if (!ActionPhase.playForStandIn(t.world(), t.component(), t.dummy(), play.card(), play.target())) {
                    ActionPhase.passForStandIn(t.world(), t.component(), t.dummy());
                }
            }
        }
    }

    /** 划船摸了牌：留哪一张。 */
    private static void row(Turn t) {
        Session session = t.session();
        List<NavigationCard> drawn = session.rowing().stream().map(Session.RowCard::card).toList();
        BooleanSupplier open = () -> sameGame(t.component(), session) && session.state().phase() == Phase.ACTION
                && session.state().turn() == t.turn() && session.rower().map(t.dummy()::equals).orElse(false)
                && session.rowing().stream().map(Session.RowCard::card).toList().equals(drawn);
        decide(new Point<List<NavigationCard>, Integer>(t.world(), t.component(), t.dummy(), "划船留牌", t.key("row"),
                DecisionKind.ROW, now() + StandInPlay.step(t.component()), t.component().actionDeadline(), drawn,
                (p, v, l, r) -> p.keepRowCard(v, l, r), SeatQuestions::row, open,
                index -> index != null && index >= 0 && index < drawn.size(),
                index -> ActionPhase.chooseRowForStandIn(t.world(), t.component(), t.dummy(), index),
                () -> ActionPhase.chooseRowForStandIn(t.world(), t.component(), t.dummy(), 0),
                index -> "留第 " + (index + 1) + " 张"));                         // 摸到的牌是暗的：只写第几张
    }

    // ================================================================ 补给箱

    /** 补给箱在替身手上：留哪一张。 */
    static void provision(ServerWorld world, GameComponent component) {
        Session session = component.requireSession();
        CharacterId holder = session.provisionHolder().orElseThrow();
        List<String> offer = session.provisionOffer();
        int index = session.provisionIndex();
        int round = session.provisionRoundsToday();
        int turn = session.state().turn();
        BooleanSupplier open = () -> sameGame(component, session) && session.provisionInProgress()
                && session.state().turn() == turn && session.provisionRoundsToday() == round
                && session.provisionIndex() == index && holder.equals(session.provisionHolder().orElse(null))
                && session.provisionOffer().equals(offer);
        boolean started = decide(new Point<List<String>, String>(world, component, holder, "补给箱",
                "provision:" + turn + ":" + round + ":" + index, DecisionKind.PROVISION, now(),
                component.provisionDeadline(), offer, (p, v, l, r) -> p.keepProvision(v, l, r),
                SeatQuestions::provision, open,
                card -> card != null && session.provisionOffer().contains(card),
                card -> ProvisionPhase.keepForStandIn(world, component, card),
                () -> ProvisionPhase.keepForStandIn(world, component, session.provisionOffer().getFirst()),
                card -> "留下一张"));                                           // 箱里的牌只有他看得见
        if (!started) {
            LOGGER.info("补给箱（替身）：{} 这一手已经在想了，不再开一次", holder.value());
        }
    }

    // ================================================================ 这一场：表态 · 站队 · 押武器 · 挑牌

    /** 一场里的一段（表态 / 站队 / 押武器 / 挑牌）。 */
    private record Fray(ServerWorld world, GameComponent component, Session session, Contest contest, int turn,
                        long applyAt) {

        /** 还是这一局、这一天、这一场、这一段。 */
        boolean open() {
            return sameGame(component, session) && session.state().turn() == turn
                    && session.contest().map(x -> x.kind() == contest.kind() && x.attacker().equals(contest.attacker())
                    && x.target().equals(contest.target()) && x.stage() == contest.stage()).orElse(false);
        }

        String key(String part) {
            return "contest:" + turn + ":" + contest.kind() + ":" + contest.attacker().value() + ">"
                    + contest.target().value() + ":" + contest.stage() + ":" + part;
        }
    }

    /** 不用想的那一步也照拍子排，并在到点时核对这一段还在。 */
    private static void later(Fray f, String what, Runnable step) {
        GameFlow.schedule(f.component(), Math.max(0L, f.applyAt() - now()), what, () -> {
            if (f.open()) {
                step.run();
            }
        });
    }

    /**
     * 没有真人要等的那一段（{@code ContestPhase#open} 不开窗口时）：替身按自己的脑子答，答完往下推。
     *
     * @param minDelay 最早什么时候做（随机替身那一拍，{@link StandInPlay#step}）
     */
    static void contestStage(ServerWorld world, GameComponent component, long minDelay) {
        Session session = component.requireSession();
        Optional<Contest> pending = session.contest();
        if (pending.isEmpty()) {
            return;
        }
        Fray f = new Fray(world, component, session, pending.get(), session.state().turn(), now() + minDelay);
        switch (f.contest().stage()) {
            case CONSENT -> consent(f);
            case STANCES -> stanceChain(f, eligibleStandIns(component, session, f.contest()), 0);
            case WEAPONS -> {
                List<CharacterId> combatants = new ArrayList<>(f.contest().fight().orElseThrow().combatants());
                combatants.removeIf(who -> !isDummy(component, who));
                weaponChain(f, combatants, 0);
            }
            case PICK -> pick(f);
        }
    }

    private static void consent(Fray f) {
        CharacterId target = f.contest().target();
        boolean ration = f.contest().kind() == Contest.Kind.RATION;
        if (!isDummy(f.component(), target)) {
            // 掉线的真人：照「什么也不做」那一档 —— 同意（ADR-0023 §7.8）
            later(f, "这一场：表态（掉线的真人按同意）", () -> ContestPhase.consent(f.world(), f.component(), false));
            return;
        }
        decide(new Point<Void, Boolean>(f.world(), f.component(), target, ration ? "反不反对分食" : "表态",
                f.key("consent"), DecisionKind.CONTEST_ANSWER, f.applyAt(), 0L, null,
                (p, v, l, r) -> p.refuse(v, r), (v, l, n) -> SeatQuestions.consent(v, n), f::open,
                fight -> fight != null && (!fight || f.session().state().canAct(target)),
                fight -> ContestPhase.consent(f.world(), f.component(), fight),
                () -> ContestPhase.consent(f.world(), f.component(), false),
                fight -> fight ? (ration ? "反对" : "拒绝") : (ration ? "不反对" : "同意")));
    }

    /** 站队段里还能加入的替身，船头 → 船尾。 */
    private static List<CharacterId> eligibleStandIns(GameComponent component, Session session, Contest c) {
        List<CharacterId> out = new ArrayList<>();
        for (CharacterId who : session.state().consciousBySeat()) {
            if (isDummy(component, who) && ContestPhase.canJoin(session, c, who)) {
                out.add(who);
            }
        }
        return out;
    }

    /** 一个替身的站队：{@code chain} 是不开窗口那一段的接力（答完接下一个），否则是窗口里各想各的。 */
    private static Point<Void, Optional<Fight.Side>> stance(Fray f, CharacterId who, boolean chain, long applyAt,
                                                            Runnable after) {
        return new Point<>(f.world(), f.component(), who, "站队", f.key((chain ? "chain:" : "window:") + who.value()),
                DecisionKind.CONTEST_JOIN, applyAt, chain ? 0L : f.component().contestDeadline(), null,
                (p, v, l, r) -> p.joinStance(v, r), (v, l, n) -> SeatQuestions.stance(v, n), f::open,
                side -> side != null && (side.isEmpty()
                        || ContestPhase.canJoin(f.session(), f.session().contest().orElseThrow(), who)),
                side -> {
                    side.ifPresent(s -> {
                        ContestPhase.join(f.world(), f.component(), who, s);
                        if (chain) {
                            quietWindow(f);
                        }
                    });
                    after.run();
                },
                after,
                side -> side.map(s -> s == Fight.Side.ATTACK ? "加入进攻方" : "加入防守方").orElse("不加入"));
    }

    /**
     * 不开窗口的那一段里，有人站出来 / 押下之后 {@code ContestPhase} 会照「有人动了就重置倒计时」开一扇窗：
     * 那是给真人看的，这一段没有真人，收掉 —— 不然大模型想得慢时那扇窗会先到点，把还没轮到的替身跳过去。
     * 随机替身那一路碰不到它：它当场全做完，紧接着的下一段就把窗收了。
     */
    private static void quietWindow(Fray f) {
        if (f.open()) {
            f.component().clearContest();
            GameComponents.sync(f.world());
        }
    }

    /** 没有真人的站队段：依座位一个一个问（后问的看得见先站出来的），问完收队。 */
    private static void stanceChain(Fray f, List<CharacterId> helpers, int at) {
        if (!f.open()) {
            return;                                   // 这一段已经被别的路收掉了
        }
        if (at >= helpers.size()) {
            later(f, "这一场：替身站完队，收队", () -> ContestPhase.closeStances(f.world(), f.component()));
            return;
        }
        CharacterId who = helpers.get(at);
        Runnable next = () -> stanceChain(f, helpers, at + 1);
        if (!ContestPhase.canJoin(f.session(), f.session().contest().orElseThrow(), who)) {
            next.run();
            return;
        }
        decide(stance(f, who, true, f.applyAt(), next));
    }

    /** 没有真人的押武器段：参战的替身依次先问喝不喝酒、再问押哪几张，问完结算。 */
    private static void weaponChain(Fray f, List<CharacterId> combatants, int at) {
        if (!f.open()) {
            return;
        }
        if (at >= combatants.size()) {
            later(f, "这一场：替身押完武器，结算", () -> ContestPhase.resolve(f.world(), f.component()));
            return;
        }
        arm(f, combatants.get(at), true, f.applyAt(), () -> weaponChain(f, combatants, at + 1));
    }

    /** 一个参战替身：喝不喝酒（不问大模型）→ 押哪几张。 */
    private static void arm(Fray f, CharacterId who, boolean chain, long applyAt, Runnable after) {
        String mode = chain ? "chain:" : "window:";
        Runnable commit = () -> {
            List<String> weapons = Legal.weapons(f.session(), who);
            if (weapons.isEmpty() || !f.open()) {
                after.run();
                return;
            }
            decide(new Point<List<String>, List<String>>(f.world(), f.component(), who, "押武器",
                    f.key(mode + "weapons:" + who.value()), DecisionKind.CONTEST_WEAPON, applyAt,
                    chain ? 0L : f.component().contestDeadline(), weapons, (p, v, l, r) -> p.commitWeapons(v, l, r),
                    SeatQuestions::weapons, f::open,
                    chosen -> chosen != null && subMultiset(chosen, Legal.weapons(f.session(), who)),
                    chosen -> {
                        for (String card : chosen) {
                            Contest now = f.session().contest().orElseThrow();
                            if (ContestPhase.canCommitWeapon(f.session(), now, who, card)) {
                                ContestPhase.commitWeapon(f.world(), f.component(), who, card);
                            }
                        }
                        if (chain && !chosen.isEmpty()) {
                            quietWindow(f);
                        }
                        after.run();
                    },
                    after,
                    chosen -> chosen.isEmpty() ? "不押" : "押 " + chosen.size() + " 张"));   // 暗牌：不写是哪几张
        };
        List<String> drinkable = Legal.drinks(f.session(), who);
        if (drinkable.isEmpty()) {
            commit.run();
            return;
        }
        decide(new Point<List<String>, Optional<String>>(f.world(), f.component(), who, "打架前喝酒",
                f.key(mode + "drink:" + who.value()), DecisionKind.FREE_ACTION, applyAt, 0L, drinkable,
                (p, v, l, r) -> p.drinkForFight(v, l, r), null, f::open,
                choice -> choice != null
                        && (choice.isEmpty() || Legal.drinks(f.session(), who).contains(choice.get())),
                choice -> {
                    choice.ifPresent(card -> ActionPhase.drinkForStandIn(f.world(), f.component(), who, card));
                    commit.run();
                },
                commit,
                choice -> choice.map(card -> "喝 " + card).orElse("不喝")));
    }

    private static void pick(Fray f) {
        CharacterId attacker = f.contest().attacker();
        Runnable idle = () -> defaultPick(f.world(), f.component(), f.session());
        if (!isDummy(f.component(), attacker)) {
            later(f, "这一场：挑牌（掉线的真人按默认）", idle);
            return;
        }
        decide(new Point<List<PickChoice>, PickChoice>(f.world(), f.component(), attacker, "挑牌", f.key("pick"),
                DecisionKind.STEAL_PICK, f.applyAt(), 0L, Legal.picks(f.session()), (p, v, l, r) -> p.pick(v, l, r),
                SeatQuestions::pick, f::open,
                choice -> choice != null && Legal.picks(f.session()).contains(choice),
                choice -> {
                    if (choice instanceof PickChoice.FromFront front) {
                        ContestPhase.pickFromFront(f.world(), f.component(), front.card());
                    } else {
                        ContestPhase.pickFromHand(f.world(), f.component());   // 摸到哪一张是天意：这一局自己的随机流
                    }
                },
                idle,
                choice -> choice instanceof PickChoice.FromFront front ? "拿面前的 " + front.card() : "从手里摸一张"));
    }

    /** 挑牌的默认（与超时同一个）：他手里有牌就随机摸一张，没有就拿面前第一张。 */
    private static void defaultPick(ServerWorld world, GameComponent component, Session session) {
        Contest c = session.contest().orElseThrow();
        if (!session.state().stateOf(c.target()).hand().isEmpty()) {
            ContestPhase.pickFromHand(world, component);
        } else {
            ContestPhase.pickFromFront(world, component, session.state().stateOf(c.target()).front().getFirst());
        }
    }

    /**
     * 有真人在等的那一段（窗口开着）：替身各想各的，按随机替身那一套延迟陆续站出来 / 押下（从这一座自己的随机流里摇）。
     * 窗口照旧由真人答完（加尾巴）或到点收；收了之后还没落地的答案作废。
     */
    static void contestWindow(ServerWorld world, GameComponent component, Contest.Stage stage) {
        Session session = component.requireSession();
        Optional<Contest> pending = session.contest();
        if (pending.isEmpty() || pending.get().stage() != stage) {
            return;
        }
        Contest c = pending.get();
        Fray f = new Fray(world, component, session, c, session.state().turn(), now());
        boolean fast = component.dummyFast();
        switch (stage) {
            case STANCES -> {
                for (CharacterId who : eligibleStandIns(component, session, c)) {
                    Random rng = seatRng(component, session, who);
                    long delay = fast ? rng.nextInt(500) : 1_500L + rng.nextInt(5_000);
                    decide(stance(f, who, false, now() + delay, () -> { }));
                }
            }
            case WEAPONS -> {
                for (CharacterId who : c.fight().orElseThrow().combatants()) {
                    if (!isDummy(component, who)) {
                        continue;
                    }
                    Random rng = seatRng(component, session, who);
                    long delay = fast ? rng.nextInt(500) : 1_000L + rng.nextInt(4_000);
                    arm(f, who, false, now() + delay, () -> { });
                }
            }
            default -> {
            }
        }
    }

    // ================================================================ 舵手

    /** 替身当舵手（窗口已经开好）：从划船堆里挑一张执行。 */
    static void helm(ServerWorld world, GameComponent component, CharacterId helm) {
        Session session = component.requireSession();
        List<NavigationCard> stack = session.table().rowStack();
        int turn = session.state().turn();
        BooleanSupplier open = () -> sameGame(component, session) && session.state().turn() == turn
                && session.state().phase() == Phase.NAVIGATION && component.helmDeadline() > 0
                && component.helmSeat(session.state()).map(helm::equals).orElse(false)
                && session.table().rowStack().equals(stack);
        decide(new Point<List<NavigationCard>, NavigationCard>(world, component, helm, "舵手挑牌", "helm:" + turn,
                DecisionKind.HELM, now(), component.helmDeadline(), stack, (p, v, l, r) -> p.steer(v, l, r),
                SeatQuestions::helm, open,
                card -> card != null && session.table().rowStack().contains(card),
                card -> NavigationPhase.resolveForStandIn(world, component, session.table().rowStack().indexOf(card)),
                () -> NavigationPhase.resolveForStandIn(world, component, 0),
                card -> "执行 " + card.id()));                                    // 被执行的那一张随后就公开
    }

    // ================================================================ 口渴：自己喝几次 · 替身递水

    /** 这一轮口渴结算里定下的：本人自己化解几次、开没开口、哪几座递了几次（依座位）。 */
    private record ThirstRound(int ownUnits, boolean askHelp, Map<CharacterId, Integer> donated) {
        int donatedUnits() {
            return donated.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /** 这一轮口渴还是问到这个人这一步（而且没有给真人开的窗口）。 */
    private static boolean samePrompt(GameComponent component, Session session, Session.ThirstPrompt prompt, int turn) {
        return sameGame(component, session) && session.state().turn() == turn && component.thirstDeadline() <= 0
                && session.thirstPending().map(p -> p.who().equals(prompt.who()) && p.remaining() == prompt.remaining()
                && p.waterPerSource() == prompt.waterPerSource()).orElse(false);
    }

    /** 船上有没有还拿得出一次水的替身（本人除外），可以替他递。 */
    static boolean anyStandInDonor(Session session, GameComponent component, Session.ThirstPrompt prompt) {
        int per = prompt.waterPerSource();
        return session.state().bySeat().stream().anyMatch(other -> !other.equals(prompt.who())
                && isDummy(component, other) && session.state().canAct(other) && session.watersOf(other) / per > 0);
    }

    /**
     * 口渴结算轮到某人：本人是替身就先问他（喝几次、还差的开不开口）；然后依座位问还有水的替身递不递；最后一起结算。
     *
     * @param askDrinker 本人是替身：问他（自己定不了 —— 昏迷、水不够 —— 时只能喝 0 次，只问开不开口）。否则只问递水的人
     */
    static void thirst(ServerWorld world, GameComponent component, Session.ThirstPrompt prompt, boolean askDrinker) {
        Session session = component.requireSession();
        int turn = session.state().turn();
        CharacterId who = prompt.who();
        int per = prompt.waterPerSource();
        if (!askDrinker) {
            donors(world, component, session, prompt, turn, new ThirstRound(0, false, new LinkedHashMap<>()));
            return;
        }
        int ownUnits = Legal.ownWaterUnits(session, who, per);
        int maxOwn = Math.min(ownUnits, prompt.remaining());
        decide(new Point<Integer, WaterPlan>(world, component, who, "口渴", "thirst:" + turn + ":" + who.value(),
                DecisionKind.THIRST, now(), 0L, ownUnits, (p, v, l, r) -> p.drinkWater(v, l, r),
                (v, l, n) -> askThirst(v, Math.min(l, v.thirst().map(SeatView.ThirstInfo::remaining).orElse(l)), n),
                () -> samePrompt(component, session, prompt, turn),
                plan -> plan != null && plan.units() >= 0 && plan.units() <= maxOwn,
                plan -> donors(world, component, session, prompt, turn,
                        new ThirstRound(plan.units(), plan.askHelp(), new LinkedHashMap<>())),
                // 「什么也不做」那一档：喝够（替身原先就这样，界面上的高亮默认也是它）
                () -> donors(world, component, session, prompt, turn,
                        new ThirstRound(maxOwn, false, new LinkedHashMap<>())),
                plan -> "化解 " + plan.units() + " 次"
                        + (plan.units() < prompt.remaining() && plan.askHelp() ? "，开口要水" : "")));
    }

    /** 大模型那一侧只问「喝几次」；喝不够的就开口（别的替身递不递由它们自己定）。 */
    private static SeatQuestions.Question<WaterPlan> askThirst(SeatView v, int maxUnits, SeatQuestions.Names n) {
        SeatQuestions.Question<Integer> q = SeatQuestions.thirst(v, maxUnits, n);
        int remaining = v.thirst().map(SeatView.ThirstInfo::remaining).orElse(maxUnits);
        List<WaterPlan> plans = q.choices().stream().map(k -> new WaterPlan(k, k < remaining)).toList();
        return new SeatQuestions.Question<>(q.kind(), plans, q.labels(), q.situation());
    }

    /** 依座位问还有水的替身递不递（每次只问还差的那几次），问完一起结算。 */
    private static void donors(ServerWorld world, GameComponent component, Session session,
                               Session.ThirstPrompt prompt, int turn, ThirstRound round) {
        CharacterId who = prompt.who();
        int per = prompt.waterPerSource();
        int shortUnits = prompt.remaining() - round.ownUnits() - round.donatedUnits();
        CharacterId giver = null;
        if (shortUnits > 0) {
            for (CharacterId other : session.state().bySeat()) {
                if (!other.equals(who) && !round.donated().containsKey(other) && isDummy(component, other)
                        && session.state().canAct(other) && session.watersOf(other) / per > 0) {
                    giver = other;
                    break;
                }
            }
        }
        if (giver == null) {
            settleThirst(world, component, session, prompt, turn, round);
            return;
        }
        CharacterId donor = giver;
        int myUnits = session.watersOf(donor) / per;
        int max = Math.min(myUnits, shortUnits);
        int donatedSoFar = round.donatedUnits();
        decide(new Point<Integer, Integer>(world, component, donor, "递水",
                "donate:" + turn + ":" + who.value() + ":" + donor.value(), DecisionKind.GIVE_WATER, now(), 0L, max,
                (p, v, l, r) -> p.donateWater(v, who, shortUnits, myUnits, round.askHelp(), donatedSoFar, r),
                (v, l, n) -> SeatQuestions.donate(v, who, l, n),
                () -> samePrompt(component, session, prompt, turn),
                units -> units != null && units >= 0 && units <= Math.min(session.watersOf(donor) / per, shortUnits),
                units -> {
                    round.donated().put(donor, units);
                    donors(world, component, session, prompt, turn, round);
                },
                () -> {
                    round.donated().put(donor, 0);
                    donors(world, component, session, prompt, turn, round);
                },
                units -> units == 0 ? "不递" : "递 " + units * per + " 张给 " + who.value()));
    }

    private static void settleThirst(ServerWorld world, GameComponent component, Session session,
                                     Session.ThirstPrompt prompt, int turn, ThirstRound round) {
        if (!samePrompt(component, session, prompt, turn)) {
            return;
        }
        int per = prompt.waterPerSource();
        round.donated().forEach((donor, units) -> {
            for (int i = 0; i < units * per; i++) {
                ThirstPhase.donateForStandIn(world, component, donor);
            }
        });
        ThirstPhase.resolveForStandIn(world, component, round.ownUnits() * per);
    }

    // ================================================================ 落海那一刻

    /** 船上有没有手里有牌可打（救生圈 · 血饵）的替身。 */
    static boolean anyStandInOverboard(Session session, GameComponent component) {
        return session.state().bySeat().stream()
                .anyMatch(who -> isDummy(component, who) && !Legal.overboard(session, who).isEmpty());
    }

    /**
     * 落海那一刻（窗口已经开好）：手上有能打的牌的替身依座位各问一次；有人打了就再问一轮（救生圈扔出去之后，
     * 别人的选项就变了）。问完若没有真人还在选，就当场收这一窗。
     */
    static void overboard(ServerWorld world, GameComponent component) {
        Session session = component.requireSession();
        int token = session.overboardPending().orElseThrow().token();
        overboardRound(world, component, session, token, 0);
    }

    private static void overboardRound(ServerWorld world, GameComponent component, Session session, int token,
                                       int round) {
        List<CharacterId> players = new ArrayList<>();
        if (round < MAX_OVERBOARD_ROUNDS) {
            for (CharacterId who : session.state().bySeat()) {
                if (isDummy(component, who) && !Legal.overboard(session, who).isEmpty()) {
                    players.add(who);
                }
            }
        }
        overboardNext(world, component, session, token, round, players, 0, false);
    }

    private static void overboardNext(ServerWorld world, GameComponent component, Session session, int token,
                                      int round, List<CharacterId> players, int at, boolean played) {
        BooleanSupplier open = () -> sameGame(component, session) && component.overboardDeadline() > 0
                && session.overboardPending().map(p -> p.token() == token).orElse(false);
        if (!open.getAsBoolean()) {
            return;
        }
        if (at >= players.size()) {
            if (played) {
                overboardRound(world, component, session, token, round + 1);
                return;
            }
            if (!CardActions.humanStillChoosing(session, component)) {
                LOGGER.info("落海：替身都选过了、没有真人还在选，收这一窗");
                component.setOverboardDeadline(now());
            }
            return;
        }
        CharacterId who = players.get(at);
        List<Session.OverboardPlay> plays = Legal.overboard(session, who);
        if (plays.isEmpty()) {
            overboardNext(world, component, session, token, round, players, at + 1, played);
            return;
        }
        decide(new Point<List<Session.OverboardPlay>, Optional<Session.OverboardPlay>>(world, component, who, "落海出牌",
                "overboard:" + token + ":" + round + ":" + who.value(), DecisionKind.OVERBOARD, now(),
                component.overboardDeadline(), plays, (p, v, l, r) -> p.overboard(v, l, r), SeatQuestions::overboard,
                open,
                choice -> choice != null && (choice.isEmpty() || Legal.overboard(session, who).contains(choice.get())),
                choice -> {
                    choice.ifPresent(play -> CardActions.overboardForStandIn(world, component, who, play.target(),
                            play.card(), token));
                    overboardNext(world, component, session, token, round, players, at + 1,
                            played || choice.isPresent());
                },
                () -> overboardNext(world, component, session, token, round, players, at + 1, played),
                choice -> choice.map(play -> "打出 " + play.card() + " → " + play.target().value()).orElse("不出牌")));
    }

    // ================================================================ 杂项

    /** {@code chosen} 里每一种牌的张数都不超过 {@code pool} 里的（与引擎驱动者同一条）。 */
    static boolean subMultiset(List<String> chosen, List<String> pool) {
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
