package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.mod.state.StandInMind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动脑 / 大模型替身的一次决定（{@link StandInThinker}）：想完回到主线程、到拍子再做、做之前核对这个决定还开着。
 *
 * <p>主线程是假的（{@link FakeHost}）：工作线程想完交回来的那一跳排进队列，由测试线程一件件做；排程按到期时刻、时钟手拨。
 * 每一条都配一个「本该照做」的对照 —— 「被丢掉」与「从来没走到那一步」在结果上长得一样。
 */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class StandInThinkerTest {

    /** 假的主线程：execute 从哪个线程来都排进队；schedule 按到期时刻存着，{@link #advance} 拨钟时到点的才做。 */
    static final class FakeHost implements StandInThinker.Host {

        volatile Object epoch = new Object();
        long now = 1_000_000L;
        private final LinkedBlockingQueue<Runnable> main = new LinkedBlockingQueue<>();
        private final PriorityQueue<Step> steps = new PriorityQueue<>(
                Comparator.comparingLong(Step::due).thenComparingLong(Step::seq));
        private long seq;

        private record Step(long due, long seq, Runnable task) {
        }

        @Override
        public Object epoch() {
            return epoch;
        }

        @Override
        public void execute(Runnable task) {
            main.add(task);
        }

        @Override
        public void schedule(long dueMs, String what, Runnable task) {
            steps.add(new Step(dueMs, seq++, task));
        }

        @Override
        public long now() {
            return now;
        }

        /** 看门狗交回来的错（模组里是「出错就结束这一局」）。 */
        final List<String> failures = new ArrayList<>();

        @Override
        public void fail(String what, RuntimeException error) {
            failures.add(what + "：" + error.getMessage());
        }

        /** 等工作线程把结果交回主线程（5 秒内），做掉那一跳，再把已经到点的排程做掉。 */
        void land() throws InterruptedException {
            landOnly();
            runDue();
        }

        /** 只做回到主线程的那一跳，排程里的一步也不做（看它排没排）。 */
        void landOnly() throws InterruptedException {
            Runnable r = main.poll(5, TimeUnit.SECONDS);
            assertNotNull(r, "5 秒内没回到主线程");
            r.run();
        }

        void advance(long ms) {
            now += ms;
            runDue();
        }

        void runDue() {
            while (!steps.isEmpty() && steps.peek().due() <= now) {
                steps.poll().task().run();
            }
        }

        int pendingSteps() {
            return steps.size();
        }
    }

    /** 一个决定的结果：照答案做了什么、默认做了几次。 */
    private static final class Outcome {
        final List<String> applied = new ArrayList<>();
        int idled;
    }

    private static StandInThinker.Spec<String> spec(FakeHost host, String key, long applyNotBefore, long thinkCapMs,
                                                    AtomicBoolean open, Predicate<String> legal,
                                                    AtomicBoolean stillThinks, Outcome out) {
        return new StandInThinker.Spec<>(host, host.epoch, key, "captain", StandInMind.SMART, "测试", host.now,
                applyNotBefore, thinkCapMs, open::get, legal, stillThinks::get, out.applied::add, () -> out.idled++,
                c -> c);
    }

    private static Supplier<CompletableFuture<StandInThinker.Thought<String>>> answer(String choice) {
        return () -> CompletableFuture.completedFuture(StandInThinker.Thought.smart(choice));
    }

    @AfterEach
    void clear() {
        StandInThinker.shutdown();
    }

    @Test
    @DisplayName("想得快也等到拍子：拍子到之前一步都不做，到了照答案做")
    void appliesNoEarlierThanTheBeat() throws Exception {
        FakeHost host = new FakeHost();
        Outcome out = new Outcome();
        assertTrue(StandInThinker.decide(spec(host, "beat", host.now + 4_000, 2_000, new AtomicBoolean(true),
                c -> true, new AtomicBoolean(true), out), answer("row")));
        host.land();
        assertTrue(out.applied.isEmpty(), "拍子还没到就做了");
        host.advance(3_999);
        assertTrue(out.applied.isEmpty(), "拍子差 1 毫秒就做了");
        host.advance(1);
        assertEquals(List.of("row"), out.applied);
        assertEquals(0, out.idled);
    }

    @Test
    @DisplayName("想完时这个决定已经被别的路收掉了（超时 · 指令）：作废，既不照答案做也不做默认")
    void staleDecisionIsDropped() throws Exception {
        FakeHost host = new FakeHost();
        AtomicBoolean open = new AtomicBoolean(true);
        CompletableFuture<StandInThinker.Thought<String>> slow = new CompletableFuture<>();
        Outcome out = new Outcome();
        StandInThinker.decide(spec(host, "stale", host.now, 60_000, open, c -> true, new AtomicBoolean(true), out),
                () -> slow);
        open.set(false);                                   // 窗口到点、默认已经替它做了
        slow.complete(StandInThinker.Thought.smart("steal"));
        host.land();
        assertTrue(out.applied.isEmpty(), "已经收场的决定还照旧答案做了");
        assertEquals(0, out.idled, "已经收场的决定又做了一次默认");

        // 对照：同一个名字放开了，决定还开着时照答案做 —— 上面那一条不是因为根本走不到「做」
        open.set(true);
        assertTrue(StandInThinker.decide(spec(host, "stale", host.now, 60_000, open, c -> true,
                new AtomicBoolean(true), out), answer("steal")), "作废之后名字没放开");
        host.land();
        assertEquals(List.of("steal"), out.applied);
    }

    @Test
    @DisplayName("想完时那一局已经结束：丢掉，排程里也不留一步")
    void endedGameIsDropped() throws Exception {
        FakeHost host = new FakeHost();
        CompletableFuture<StandInThinker.Thought<String>> slow = new CompletableFuture<>();
        Outcome out = new Outcome();
        StandInThinker.decide(spec(host, "epoch", host.now, 60_000, new AtomicBoolean(true), c -> true,
                new AtomicBoolean(true), out), () -> slow);
        host.epoch = new Object();                         // 收场、开了下一局
        slow.complete(StandInThinker.Thought.smart("row"));
        host.landOnly();
        assertEquals(0, host.pendingSteps(), "上一局的决定在这一局的排程里留了一步");
        host.advance(1);
        assertTrue(out.applied.isEmpty());
        assertEquals(0, out.idled);
    }

    @Test
    @DisplayName("答案到点时已不合法：照「什么也不做」那一档做，不照答案做")
    void illegalAnswerFallsBackToIdle() throws Exception {
        FakeHost host = new FakeHost();
        Outcome out = new Outcome();
        StandInThinker.decide(spec(host, "illegal", host.now, 2_000, new AtomicBoolean(true), c -> !c.equals("steal"),
                new AtomicBoolean(true), out), answer("steal"));
        host.land();
        assertTrue(out.applied.isEmpty());
        assertEquals(1, out.idled);

        Outcome control = new Outcome();
        StandInThinker.decide(spec(host, "legal", host.now, 2_000, new AtomicBoolean(true), c -> !c.equals("steal"),
                new AtomicBoolean(true), control), answer("row"));
        host.land();
        assertEquals(List.of("row"), control.applied, "对照：合法的答案照做");
        assertEquals(0, control.idled);
    }

    @Test
    @DisplayName("想的时候脑子被换掉了：按默认做，不照旧脑子的答案做")
    void mindSwitchedFallsBackToIdle() throws Exception {
        FakeHost host = new FakeHost();
        Outcome out = new Outcome();
        AtomicBoolean thinks = new AtomicBoolean(true);
        CompletableFuture<StandInThinker.Thought<String>> slow = new CompletableFuture<>();
        StandInThinker.decide(spec(host, "mind", host.now, 60_000, new AtomicBoolean(true), c -> true, thinks, out),
                () -> slow);
        thinks.set(false);
        slow.complete(StandInThinker.Thought.smart("row"));
        host.land();
        assertTrue(out.applied.isEmpty());
        assertEquals(1, out.idled);
    }

    @Test
    @DisplayName("照答案做时被规则拒了：这个决定还开着就按默认做 —— 替身卡住比做错更糟")
    void rejectedApplyFallsBackToIdle() throws Exception {
        FakeHost host = new FakeHost();
        List<String> idled = new ArrayList<>();
        StandInThinker.Spec<String> spec = new StandInThinker.Spec<>(host, host.epoch, "reject", "captain",
                StandInMind.SMART, "测试", host.now, host.now, 2_000, () -> true, c -> true, () -> true,
                c -> {
                    throw new IllegalStateException("规则拒了");
                }, () -> idled.add("idle"), c -> c);
        StandInThinker.decide(spec, answer("row"));
        host.land();
        assertEquals(List.of("idle"), idled);
    }

    @Test
    @DisplayName("想的那一边永远不回：看门狗过了时限按默认收；之后再回来的答案不做")
    void watchdogClosesAHungThought() throws Exception {
        FakeHost host = new FakeHost();
        Outcome out = new Outcome();
        CompletableFuture<StandInThinker.Thought<String>> never = new CompletableFuture<>();
        long cap = 60_000;
        long opened = host.now;
        StandInThinker.decide(spec(host, "hung", opened, cap, new AtomicBoolean(true), c -> true,
                new AtomicBoolean(true), out), () -> never);
        StandInThinker.sweep(opened + cap + StandInThinker.WATCHDOG_SLACK_MS - 1);
        assertEquals(0, out.idled, "看门狗提前咬了");
        StandInThinker.sweep(opened + cap + StandInThinker.WATCHDOG_SLACK_MS);
        assertEquals(1, out.idled, "过了时限看门狗没收");
        never.complete(StandInThinker.Thought.smart("steal"));
        host.land();
        host.advance(1);
        assertTrue(out.applied.isEmpty(), "看门狗收掉之后，迟到的答案又照做了一次");
        assertEquals(1, out.idled);
    }

    @Test
    @DisplayName("看门狗那一跳按默认做时抛了：交给主线程那一侧收（出错就结束这一局），不往每 tick 的事件外面抛")
    void watchdogFailureIsHandedToTheHost() {
        FakeHost host = new FakeHost();
        CompletableFuture<StandInThinker.Thought<String>> never = new CompletableFuture<>();
        long cap = 1_000;
        long opened = host.now;
        // 审查 2026-10-07 C1 替身那一路：答案不再合法 → 默认挑牌 → 引擎抛「偷窃只能拿手牌」
        StandInThinker.Spec<String> spec = new StandInThinker.Spec<>(host, host.epoch, "boom", "kid", StandInMind.SMART,
                "挑牌", opened, opened, cap, () -> true, c -> true, () -> true, c -> { },
                () -> {
                    throw new IllegalStateException("偷窃只能拿手牌");
                }, c -> c);
        StandInThinker.decide(spec, () -> never);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> StandInThinker.sweep(opened + cap + StandInThinker.WATCHDOG_SLACK_MS),
                "看门狗在每 tick 的事件里直接抛：原先冒到服务端主循环，崩服");
        assertEquals(1, host.failures.size(), "错没有交给主线程那一侧（排程那条路会结束这一局，这一路也该一样）");
        assertTrue(host.failures.getFirst().contains("挑牌"), host.failures.toString());
    }

    @Test
    @DisplayName("同一个决定正在想时不再开一个；收了之后可以再开")
    void sameDecisionIsNotOpenedTwice() throws Exception {
        FakeHost host = new FakeHost();
        Outcome out = new Outcome();
        CompletableFuture<StandInThinker.Thought<String>> slow = new CompletableFuture<>();
        assertTrue(StandInThinker.decide(spec(host, "dup", host.now, 60_000, new AtomicBoolean(true), c -> true,
                new AtomicBoolean(true), out), () -> slow));
        assertFalse(StandInThinker.decide(spec(host, "dup", host.now, 60_000, new AtomicBoolean(true), c -> true,
                new AtomicBoolean(true), out), answer("row")), "同一个决定开了两次");
        slow.complete(StandInThinker.Thought.smart("pass"));
        host.land();
        assertEquals(List.of("pass"), out.applied, "只做了先开的那一个");
        assertTrue(StandInThinker.decide(spec(host, "dup", host.now, 60_000, new AtomicBoolean(true), c -> true,
                new AtomicBoolean(true), out), answer("row")), "收了之后名字没放开");
        host.land();
        assertEquals(List.of("pass", "row"), out.applied);
    }

    @Test
    @DisplayName("想在有名字的守护线程上，不在主线程上")
    void thinksOffTheMainThread() throws Exception {
        FakeHost host = new FakeHost();
        Outcome out = new Outcome();
        AtomicReference<Thread> thinker = new AtomicReference<>();
        StandInThinker.decide(spec(host, "thread", host.now, 2_000, new AtomicBoolean(true), c -> true,
                new AtomicBoolean(true), out), () -> {
            thinker.set(Thread.currentThread());
            return CompletableFuture.completedFuture(StandInThinker.Thought.smart("pass"));
        });
        host.land();
        assertNotEquals(Thread.currentThread(), thinker.get());
        assertTrue(thinker.get().getName().startsWith("heavyseas-standin-"), thinker.get().getName());
        assertTrue(thinker.get().isDaemon(), "工作线程不是守护线程：停服时会拖住进程");
        assertEquals(List.of("pass"), out.applied);
    }

    /**
     * 记下第二层拿到的种子与旋钮；两种策略都用第一层（单测里没有数据，造不了真的第二层）。
     * 旋钮取自 {@link #current}（单测里手拨，模拟「设置菜单里改了」）。
     */
    private static final class RecordingPolicies implements StandInThinker.Policies {
        final List<Long> seeds = new ArrayList<>();
        final List<io.github.heavyseasmc.engine.seat.SeatPolicySettings> built = new ArrayList<>();
        io.github.heavyseasmc.engine.seat.SeatPolicySettings current =
                io.github.heavyseasmc.engine.seat.SeatPolicySettings.DEFAULTS;
        int asked;

        @Override
        public io.github.heavyseasmc.engine.seat.SeatPolicySettings settings() {
            asked++;
            return current;
        }

        @Override
        public io.github.heavyseasmc.engine.seat.SeatPolicy smart(io.github.heavyseasmc.engine.seat.SeatPolicySettings s,
                                                                  long seed) {
            seeds.add(seed);
            built.add(s);
            return new io.github.heavyseasmc.engine.seat.HeuristicSeatPolicy(s);
        }

        @Override
        public io.github.heavyseasmc.engine.seat.SeatPolicy quick(io.github.heavyseasmc.engine.seat.SeatPolicySettings s) {
            return new io.github.heavyseasmc.engine.seat.HeuristicSeatPolicy(s);
        }
    }

    @Test
    @DisplayName("动脑的旋钮一局取一次：开局后在设置里改了，这一局照旧，下一局才用新的")
    void settingsAreTakenOncePerGame() {
        CharacterId captain = CharacterId.of("captain");
        CharacterId kid = CharacterId.of("kid");
        RecordingPolicies p = new RecordingPolicies();
        Object running = new Object();
        StandInThinker.Seat first = StandInThinker.seat(running, 7L, captain, 0, p);
        var changed = io.github.heavyseasmc.engine.seat.SeatPolicySettings.DEFAULTS.withBudget(64, 100, 2, 1);
        p.current = changed;                                           // 设置菜单里存了一份新的
        StandInThinker.Seat later = StandInThinker.seat(running, 7L, kid, 7, p);
        assertSame(first.settings(), later.settings(), "同一局里第二座用上了刚改的旋钮");
        assertEquals(io.github.heavyseasmc.engine.seat.SeatPolicySettings.DEFAULTS, later.settings());
        assertEquals(1, p.asked, "同一局问了不止一次旋钮");
        StandInThinker.Seat next = StandInThinker.seat(new Object(), 7L, captain, 0, p);
        assertEquals(changed, next.settings(), "下一局没用上新的旋钮");
        assertEquals(changed, p.built.getLast(), "下一局造策略时没拿新的旋钮");
    }

    @Test
    @DisplayName("每局每座一个脑子：同一个种子同一座，随机流与第二层的种子都一样；换座位、换种子就换；同一局里只造一次")
    void seatStreams() {
        CharacterId captain = CharacterId.of("captain");
        Object gameA = new Object();
        Object gameB = new Object();
        RecordingPolicies p = new RecordingPolicies();
        long a = StandInThinker.seat(gameA, 42L, captain, 0, p).rng().nextLong();
        assertEquals(a, StandInThinker.seat(gameB, 42L, captain, 0, p).rng().nextLong(), "同一个种子同一座，流不一样");
        assertEquals(p.seeds.get(0), p.seeds.get(1), "同一个种子同一座，第二层的种子不一样");
        assertNotEquals(a, StandInThinker.seat(new Object(), 42L, captain, 1, p).rng().nextLong(), "换了座位还是同一条流");
        assertNotEquals(p.seeds.get(0), p.seeds.get(2), "换了座位，第二层还是同一个种子");
        assertNotEquals(a, StandInThinker.seat(new Object(), 43L, captain, 0, p).rng().nextLong(), "换了种子还是同一条流");
        assertNotEquals(a, (long) p.seeds.get(0), "第二层的种子与随机流取的是同一个数");
        int made = p.seeds.size();
        assertSame(StandInThinker.seat(gameA, 42L, captain, 0, p), StandInThinker.seat(gameA, 42L, captain, 0, p),
                "同一局同一座造了两个脑子");
        assertEquals(made, p.seeds.size(), "同一局同一座又造了一次第二层");
    }

    @Test
    @DisplayName("动脑的上限盖得住第二层：两条线程上八座同时推演（每座 ¼ 秒），全都在上限之内落地，没有一座被看门狗收掉")
    void eightSeatsSearchingAtOnceFitTheCap() throws Exception {
        long perDecision = io.github.heavyseasmc.engine.seat.SeatPolicySettings.DEFAULT_MILLIS;
        long cap = StandInSettings.smartThinkCapMs(StandInSettings.smart());
        assertTrue(StandInSettings.smart().search(), "默认不推演了：这一条量的就不是第二层");
        assertTrue(cap >= perDecision + StandInSettings.SMART_THINK_CAP_MS, "上限比一次推演的时限加余量还短");
        FakeHost host = new FakeHost();
        Outcome out = new Outcome();
        long started = System.nanoTime();
        for (int i = 0; i < 8; i++) {
            StandInThinker.decide(spec(host, "seat" + i, host.now, cap, new AtomicBoolean(true), c -> true,
                    new AtomicBoolean(true), out), () -> {
                try {
                    Thread.sleep(perDecision);           // 一次推演用满自己的时限
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return CompletableFuture.completedFuture(StandInThinker.Thought.smart("stance"));
            });
        }
        for (int i = 0; i < 8; i++) {
            host.land();
        }
        long tookMs = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(tookMs < cap, "八座想完用了 " + tookMs + " ms，超过上限 " + cap + " ms");
        assertEquals(8, out.applied.size(), "有的决定没有照答案做");
        StandInThinker.sweep(host.now + cap + StandInThinker.WATCHDOG_SLACK_MS);
        assertEquals(0, out.idled, "有的决定被看门狗收掉了");
        System.out.println("八座同时想（每座 " + perDecision + " ms，两条线程）：" + tookMs + " ms，上限 " + cap + " ms");
    }

    @Test
    @DisplayName("动脑的上限：第一层 2 秒；第二层每步限时 250 ms 时 3 秒；不限时按每步 10 秒算")
    void smartThinkCap() {
        var s = io.github.heavyseasmc.engine.seat.SeatPolicySettings.DEFAULTS;
        assertEquals(2_000, StandInSettings.smartThinkCapMs(s.withoutSearch()));
        assertEquals(3_000, StandInSettings.smartThinkCapMs(s.withBudget(128, 250, 4, 2)));
        assertEquals(42_000, StandInSettings.smartThinkCapMs(s.withBudget(128, 0, 4, 2)));
    }
}
