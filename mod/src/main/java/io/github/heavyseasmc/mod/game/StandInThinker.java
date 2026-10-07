package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.seat.SeatPolicy;
import io.github.heavyseasmc.engine.seat.SeatPolicySettings;
import io.github.heavyseasmc.mod.state.StandInMind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 「动脑」与「大模型」两种替身的一次决定怎么走完：<b>在主线程上照相，在工作线程上想，回主线程核对了再做</b>。
 *
 * <h2>一次决定的一生</h2>
 * <ol>
 *   <li>主线程：调用方照好相（{@code SeatView.of} 与合法选项都是不可变的快照），交进来；</li>
 *   <li>工作线程（{@link #executor()}，两条、有界）：策略想；大模型那一种接着问模型（它自己的线程，不占这两条）；</li>
 *   <li>想完 → {@code server.execute} 回主线程；对局换了就丢（{@link Host#epoch()}）；</li>
 *   <li>排到「想完」与「拍子到」两者晚的那一刻（{@link Spec#applyNotBefore}）：替身的节奏照旧看得见；</li>
 *   <li>到点再核对一遍<b>这个决定还开着</b>（同一局 · 同一个阶段 / 回合 / 这一场 · 还是这一座在等）：
 *       不开了就作废、记一行；开着而答案已不合法（或者没想出来、脑子换了）就照「什么也不做」那一档的默认做，记一行原因。</li>
 * </ol>
 * 外加一只<b>看门狗</b>（{@link #sweep}，每 tick 在主线程上看一眼）：想的上限过了还没落地，就按默认把这个决定收掉。
 * 替身卡住比替身做错更糟 —— 卡住的局面没有下一步来推它。看门狗不进对局的排程队列：那条队列每 tick 只取一步，
 * 每个决定多排一只看门狗，就是每个决定多占一 tick。
 *
 * <h2>每个决定恰好一行</h2>
 * {@code 替身决定 座位=… 脑=… 窗=… 选=「…」 来源=smart|llm|smart-fallback:原因|idle:原因 想=…ms [经过=…]}；
 * 作废的写 {@code 替身决定作废 …}。数日志就分得出每一座走的是哪条路（证伪表「有退路的地方必须有一行说走的是哪条路」）。
 * ❗「选」那一段写的是<b>这一座之外的人本来就看得见的</b>：留了哪张补给、押了哪几张武器都是暗的，只写「一张」「几张」——
 * 开服的人往往也是玩家（与 {@code ContestPhase#commitWeapon} 那一行同一个理由）。
 *
 * <h2>可复现</h2>
 * 每局每座一个随机流（{@link #seat}），由那一局的开局种子派生、按座位分开 —— 同一个种子的一局，第一层的替身照样打出同一局。
 * 第二层（往前推演）带墙上时间的预算（默认每个决定 250 ms）：推演几局取决于机器快慢，只有把时限设成 0（只看局数）才可复现；
 * 大模型那一种不保证（模型的回答不由种子定）。
 *
 * <p>这个类不碰 Minecraft 的类：主线程那一侧经 {@link Host} 给进来，单测里换成假的。
 */
final class StandInThinker {

    private static final Logger LOGGER = LoggerFactory.getLogger("heavyseas");

    /** 看门狗在「想的上限」之后再宽限多久（回主线程那一跳、排程队列里的那一步都在里面）。 */
    static final long WATCHDOG_SLACK_MS = 3_000L;

    /** 工作线程几条：动脑那一下是几毫秒的事，两条够全船；大模型的等待不占它们。 */
    private static final int THREADS = 2;
    /** 排队上限：满了就当场按默认收，不让主线程那一侧越积越多。 */
    private static final int QUEUE = 64;

    private StandInThinker() {
    }

    // ---------------------------------------------------------------- 主线程那一侧

    /** 主线程那一侧要的几样。模组里由 {@link StandInMinds} 用服务端与组件拼出来；单测换成假的。 */
    interface Host {

        /** 此刻是哪一局（对局身份，比引用）；没有对局为 {@code null}。 */
        Object epoch();

        /** 交给主线程去做（{@code server.execute}）。可以从任何线程调。 */
        void execute(Runnable task);

        /** 排到 {@code dueMs} 那一刻、在主线程上做；那一局结束了就作废（{@code GameComponent#schedule}）。只在主线程上调。 */
        void schedule(long dueMs, String what, Runnable task);

        long now();
    }

    /**
     * 想出来的东西。
     *
     * @param trail  大模型那一层每次请求落了个什么（{@code 503→ok}）；没问为 {@code null}
     * @param detail 动脑那一层的账（第二层：这个决定推演了几局、推演出错几次）；没有为 {@code null}
     */
    record Thought<T>(T choice, String source, String trail, String detail) {

        Thought(T choice, String source, String trail) {
            this(choice, source, trail, null);
        }

        static <T> Thought<T> smart(T choice) {
            return new Thought<>(choice, "smart", null, null);
        }

        Thought<T> withDetail(String d) {
            return new Thought<>(choice, source, trail, d);
        }
    }

    /**
     * 一个决定的全部约定。
     *
     * @param epoch          照相那一刻是哪一局
     * @param key            这个决定的名字（同一局里同一个名字只开一个；{@code null} = 不去重）
     * @param seat           哪一座（日志）
     * @param mind           照相那一刻这一座的脑子（日志）
     * @param what           哪一种决定（日志里的「窗」）
     * @param openedAt       照相的时刻
     * @param applyNotBefore 最早什么时候做（拍子）：想得再快也等到这一刻
     * @param thinkCapMs     想的上限：过了还没回来，看门狗按默认收
     * @param open           主线程：这个决定还开着吗（同一个阶段 / 回合 / 这一场 · 还是这一座在等）
     * @param legal          主线程：这个答案此刻还合法吗
     * @param stillThinks    主线程：脑子还是动脑 / 大模型吗（换了就按默认做，不照旧答案做）
     * @param apply          主线程：照答案做
     * @param idle           主线程：照「什么也不做」那一档的默认做
     * @param describe       答案写成一句人读的话（日志；暗的东西别写出来）
     */
    record Spec<T>(Host host, Object epoch, String key, String seat, StandInMind mind, String what, long openedAt,
                   long applyNotBefore, long thinkCapMs, BooleanSupplier open, Predicate<T> legal,
                   BooleanSupplier stillThinks, Consumer<T> apply, Runnable idle, Function<T, String> describe) {

        Spec {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(seat, "seat");
            Objects.requireNonNull(mind, "mind");
            Objects.requireNonNull(what, "what");
            Objects.requireNonNull(open, "open");
            Objects.requireNonNull(legal, "legal");
            Objects.requireNonNull(stillThinks, "stillThinks");
            Objects.requireNonNull(apply, "apply");
            Objects.requireNonNull(idle, "idle");
            Objects.requireNonNull(describe, "describe");
        }
    }

    // ---------------------------------------------------------------- 统计（/seas dummy 用）

    private static final AtomicLong DECIDED = new AtomicLong();
    private static final AtomicLong FALLBACKS = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();

    /** 这一次起服以来：决定了几次 · 其中退回默认几次 · 作废几次。 */
    static String tally() {
        return "决定 " + DECIDED.get() + " · 退回默认 " + FALLBACKS.get() + " · 作废 " + DROPPED.get()
                + " · 在路上 " + inFlight();
    }

    // ---------------------------------------------------------------- 一次决定

    /** 一个在路上的决定：想的结果落地之前，看门狗靠它找到这个决定。 */
    private static final class Run<T> {
        final Spec<T> spec;
        final long watchdogAt;
        final AtomicBoolean settled = new AtomicBoolean();
        /** 以下三样只在主线程上写（{@link #land}）。 */
        boolean landed;
        long landedAt;
        Thought<T> thought;
        String failure;

        Run(Spec<T> spec, long watchdogAt) {
            this.spec = spec;
            this.watchdogAt = watchdogAt;
        }
    }

    /** 还没收场的决定。只在主线程上碰（开、落地、收场、看门狗都在主线程上）；锁只为单测里换线程时稳妥。 */
    private static final List<Run<?>> RUNS = new ArrayList<>();

    /**
     * 开始一个决定。当场返回；之后的每一步都在主线程上（经 {@link Host}）。
     *
     * @param think 在工作线程上调：想出答案（大模型那一种返回的 future 在模型那边完成）
     * @return 开了没有（同一个决定已经在想时不再开一个）
     */
    static <T> boolean decide(Spec<T> spec, Supplier<CompletableFuture<Thought<T>>> think) {
        if (spec.key() != null && !claim(spec.epoch(), spec.key())) {
            return false;
        }
        Run<T> run = new Run<>(spec,
                Math.max(spec.applyNotBefore(), spec.openedAt() + spec.thinkCapMs()) + WATCHDOG_SLACK_MS);
        synchronized (RUNS) {
            RUNS.add(run);
        }
        CompletableFuture<Thought<T>> future;
        try {
            future = CompletableFuture.supplyAsync(think, executor()).thenCompose(f -> f);
        } catch (RejectedExecutionException e) {
            future = CompletableFuture.failedFuture(e);
        }
        future.orTimeout(spec.thinkCapMs() + 500, TimeUnit.MILLISECONDS)
                .whenComplete((thought, error) -> spec.host().execute(
                        () -> land(run, thought, error == null ? null : reason(error))));
        return true;
    }

    /** 想完、回到主线程：对局还是这一局，就排到拍子那一刻去做。 */
    private static <T> void land(Run<T> run, Thought<T> thought, String failure) {
        if (run.settled.get()) {
            return;                               // 看门狗已经收了
        }
        Spec<T> spec = run.spec;
        run.thought = thought;
        run.failure = failure;
        run.landedAt = spec.host().now();
        run.landed = true;
        if (spec.host().epoch() != spec.epoch()) {
            if (run.settled.compareAndSet(false, true)) {
                forget(run);
                drop(spec, "这一局已经结束了");
            }
            return;
        }
        long due = Math.max(spec.host().now(), spec.applyNotBefore());
        spec.host().schedule(due, "替身：" + spec.seat() + " " + spec.what(), () -> finish(run, thought, failure));
    }

    /**
     * 看门狗：每 tick 在主线程上调。过了时限还没收场的决定，按手上有的（落了地的答案，或者「没想出来」）收掉。
     */
    static void sweep(long now) {
        List<Run<?>> due = new ArrayList<>();
        synchronized (RUNS) {
            for (Iterator<Run<?>> it = RUNS.iterator(); it.hasNext(); ) {
                Run<?> run = it.next();
                if (run.settled.get()) {
                    it.remove();
                } else if (now >= run.watchdogAt) {
                    it.remove();
                    due.add(run);
                }
            }
        }
        for (Run<?> run : due) {
            watchdog(run);
        }
    }

    private static <T> void watchdog(Run<T> run) {
        if (run.landed) {
            // 落了地、照答案做的那一步却还没轮到（排程丢了，或者被挤在后面）：现在做
            finish(run, run.thought, run.failure);
        } else {
            finish(run, null, "看门狗：想了 " + run.spec.thinkCapMs() + " ms 还没回来");
        }
    }

    /** 到点：核对这个决定还开着，再照答案（或默认）做。 */
    private static <T> void finish(Run<T> run, Thought<T> thought, String failure) {
        if (!run.settled.compareAndSet(false, true)) {
            return;
        }
        Spec<T> spec = run.spec;
        forget(run);
        // 收场了就放开名字 —— 在照答案做之前放：照答案做可能当场开下一个决定，名字还占着的话它就开不了
        releaseKey(spec);
        long thinkMs = (run.landed ? run.landedAt : spec.host().now()) - spec.openedAt();
        if (spec.host().epoch() != spec.epoch()) {
            drop(spec, "这一局已经结束了");
            return;
        }
        if (!isOpen(spec)) {
            drop(spec, "这个决定已经不在了（被超时、指令或别的路收掉了）");
            return;
        }
        String reason = failure;
        if (reason == null && thought == null) {
            reason = "没想出来";
        }
        if (reason == null && !stillThinks(spec)) {
            reason = "脑子换了";
        }
        T choice = thought == null ? null : thought.choice();
        if (reason == null && (choice == null || !isLegal(spec, choice))) {
            reason = "选项已不合法";
        }
        if (reason == null) {
            try {
                spec.apply().accept(choice);
                DECIDED.incrementAndGet();
                log(spec, spec.describe().apply(choice), thought.source(), thinkMs, thought.trail(), thought.detail());
                return;
            } catch (RuntimeException e) {
                reason = "照答案做时被拒（" + e.getMessage() + "）";
                LOGGER.warn("替身决定：座位={} 窗={} 照答案做时出错，改走默认", spec.seat(), spec.what(), e);
                if (!isOpen(spec)) {
                    drop(spec, reason + "，之后这个决定已经不在了");
                    return;
                }
            }
        }
        spec.idle().run();
        DECIDED.incrementAndGet();
        FALLBACKS.incrementAndGet();
        log(spec, "默认", "idle:" + reason, thinkMs, thought == null ? null : thought.trail(),
                thought == null ? null : thought.detail());
    }

    private static boolean isOpen(Spec<?> spec) {
        try {
            return spec.open().getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static <T> boolean isLegal(Spec<T> spec, T choice) {
        try {
            return spec.legal().test(choice);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean stillThinks(Spec<?> spec) {
        try {
            return spec.stillThinks().getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void log(Spec<?> spec, String chosen, String source, long thinkMs, String trail, String detail) {
        LOGGER.info("替身决定 座位={} 脑={} 窗={} 选=「{}」 来源={} 想={}ms{}{}", spec.seat(), spec.mind(), spec.what(),
                chosen, source, thinkMs, detail == null || detail.isEmpty() ? "" : " " + detail,
                trail == null || trail.isEmpty() ? "" : " 经过=" + trail);
    }

    private static void drop(Spec<?> spec, String why) {
        releaseKey(spec);
        DROPPED.incrementAndGet();
        LOGGER.info("替身决定作废 座位={} 脑={} 窗={} 原因={}", spec.seat(), spec.mind(), spec.what(), why);
    }

    private static void forget(Run<?> run) {
        synchronized (RUNS) {
            RUNS.remove(run);
        }
    }

    private static int inFlight() {
        synchronized (RUNS) {
            return RUNS.size();
        }
    }

    private static void releaseKey(Spec<?> spec) {
        if (spec.key() != null) {
            release(spec.epoch(), spec.key());
        }
    }

    private static String reason(Throwable error) {
        Throwable c = error;
        while ((c instanceof CompletionException || c instanceof ExecutionException) && c.getCause() != null) {
            c = c.getCause();
        }
        if (c instanceof TimeoutException) {
            return "想超时";
        }
        if (c instanceof RejectedExecutionException) {
            return "排不上（工作线程忙不过来或已关）";
        }
        return "想的时候出错（" + c + "）";
    }

    // ---------------------------------------------------------------- 每局每座

    /**
     * 一座的脑子。
     *
     * @param policy 「动脑」用的那一个（默认第二层：带着自己的随机流与统计，所以每局每座一个；同一时刻只许一个线程用它）
     * @param quick  第一层（大模型那一种先算的答案与退路用它：便宜，大头在等模型）
     * @param rng      这一座这一局的随机流（交给策略当 {@code gameRng}；窗口里陆续动手的延迟也从这里摇）
     * @param settings 这一局的那一份旋钮（动脑的上限按它算）
     */
    record Seat(SeatPolicy policy, SeatPolicy quick, Random rng, SeatPolicySettings settings) {
    }

    /**
     * 造一座的两种策略（模组里是 {@link StandInSettings#smartPolicy} 与 {@link StandInSettings#quickPolicy}；单测换成简单的）。
     */
    interface Policies {

        /** 这一局用的那一份旋钮：一局里第一次造座位时问一次，整局都用它 —— 设置菜单里改了，下一局才换。 */
        SeatPolicySettings settings();

        /** @param seed 这一座这一局自己的种子（第二层用它造自己的随机流） */
        SeatPolicy smart(SeatPolicySettings settings, long seed);

        SeatPolicy quick(SeatPolicySettings settings);
    }

    /** 每局的那一份：这一局的旋钮 · 座位的脑子 · 正在想的决定（同一个决定不开两次）。按对局身份存，局结束后随它回收。 */
    private static final class Game {
        final Map<CharacterId, Seat> seats = new HashMap<>();
        final Set<String> pending = new HashSet<>();
        /** 这一局的那一份旋钮：第一次造座位时取。 */
        SeatPolicySettings settings;
    }

    private static final Map<Object, Game> GAMES = Collections.synchronizedMap(new WeakHashMap<>());

    private static Game game(Object epoch) {
        return GAMES.computeIfAbsent(Objects.requireNonNull(epoch, "epoch"), e -> new Game());
    }

    /**
     * 这一局这一座的脑子。第一次问时造；随机流与第二层的种子由这一局的种子按座位派生（同一条
     * {@link SplittableRandom} 先后取两个数：第一个给随机流 —— 与只有第一层时取的那个一样 —— 第二个给第二层）。
     *
     * @param seed      这一局的替身种子（{@code GameComponent#standInSeed}）
     * @param seatIndex 这一座在阵容里的位次（开局时定，之后不变）
     */
    static Seat seat(Object epoch, long seed, CharacterId id, int seatIndex, Policies policies) {
        Game g = game(epoch);
        synchronized (g) {
            if (g.settings == null) {
                g.settings = Objects.requireNonNull(policies.settings(), "settings");
            }
            SeatPolicySettings settings = g.settings;
            return g.seats.computeIfAbsent(id, k -> {
                SplittableRandom stream = new SplittableRandom(seed + 0x9E3779B97F4A7C15L * (seatIndex + 1L));
                Random rng = new Random(stream.nextLong());
                return new Seat(policies.smart(settings, stream.nextLong()), policies.quick(settings), rng, settings);
            });
        }
    }

    /** 占住一个决定（同一个决定已经在想就返回 {@code false}，调用方别再开一个）。 */
    static boolean claim(Object epoch, String key) {
        Game g = game(epoch);
        synchronized (g) {
            return g.pending.add(key);
        }
    }

    /** 放开一个决定（它落地了、作废了，或者调用方决定不开）。 */
    static void release(Object epoch, String key) {
        Game g = game(epoch);
        synchronized (g) {
            g.pending.remove(key);
        }
    }

    // ---------------------------------------------------------------- 工作线程

    private static ThreadPoolExecutor executor;

    /** 两条有名字的守护线程、队列有界；起服后第一次用到时造，停服时关（{@link #shutdown()}）。 */
    static synchronized ThreadPoolExecutor executor() {
        if (executor == null || executor.isShutdown()) {
            AtomicInteger n = new AtomicInteger();
            executor = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUE),
                    r -> {
                        Thread t = new Thread(r, "heavyseas-standin-" + n.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    });
            executor.allowCoreThreadTimeOut(true);
        }
        return executor;
    }

    /** 停服时调：还在想的一律丢下（它们回主线程那一跳也会因为服务端已停而丢掉）。 */
    static synchronized void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        GAMES.clear();
        synchronized (RUNS) {
            RUNS.clear();
        }
    }
}
