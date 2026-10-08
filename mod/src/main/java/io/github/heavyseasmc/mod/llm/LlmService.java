package io.github.heavyseasmc.mod.llm;

import io.github.heavyseasmc.mod.llm.ChoiceOutcome.Fallback;
import io.github.heavyseasmc.mod.llm.ChoiceOutcome.Usage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 大模型替身的接入层（ADR-0096 §3）：给一座的一个决定（编了号的合法选项 + 这一座看得到的局面），
 * 异步问一个 OpenAI 兼容的模型挑一个编号；<b>永远</b>给回一个合法下标，或者「没有可用的选择 + 为什么」。
 *
 * <h2>对调用方的承诺</h2>
 * <ul>
 *   <li>{@link #choose} 当场返回，不在调用线程上做任何网络的事（拿到名额也是转到自己的线程去发）。</li>
 *   <li>返回的 future <b>只会正常完成</b>，从不异常完成；最晚在「截止时间 − deadlineMarginMs」那一刻完成。</li>
 *   <li>future 在本服务自己的线程上完成（已经完成的除外：那时 {@code thenAccept} 会在调用线程上当场跑）——
 *       回到游戏里一律 {@code server.execute(...)}，并且<b>再校验一次</b>：局面可能已经变了。</li>
 *   <li>每一次决定恰好一行 INFO：{@code 大模型决定 #编号 座位=… 窗=… 选项=… 路=模型|退路:原因 经过=503→503→ok …}（{@link #logLine}）。
 *       有退路的地方必须有一行说走的是哪条路（证伪表）。熔断开合另记一行。</li>
 * </ul>
 *
 * <h2>时间预算（用户 2026-10-07：「大模型一定要有 retry，不然像 deepseek 这种就会经常失败」）</h2>
 * <pre>
 * 预算 R = 截止时间 − deadlineMarginMs − 现在            （每次尝试开始时重算；排队等名额也花它）
 * R &lt; minAttemptMs                         → 不发，收场（地板）
 * 第 k 次尝试的超时 T_k：
 *   k = maxAttempts，或 ⌊s·R⌋ &lt; minAttemptMs → T_k = min(attemptTimeoutMs, R)          （最后一次：不必再留）
 *   否则                                     → T_k = min(attemptTimeoutMs, ⌊s·R⌋)  （留出 (1−s)·R 给退避与下一次）
 *   s = attemptShare（缺省 0.55；1.0 = 不切分）
 * 第 k 次失败后的退避 B_k = Retry-After（有就照它，秒数）
 *                     否则 min(backoffMaxMs, backoffBaseMs·2^(k−1)) + 随机[0, backoffBaseMs/2]
 *   再问一遍（认不出编号 / 越界）不退避：B_k = 0
 * 还要再试，须 R − B_k ≥ minAttemptMs，且已发次数 &lt; maxAttempts；否则带着这一次的原因收场（「来不及再试」）
 * </pre>
 * 截止定时器在 R 用完那一刻无条件收成 {@code TIMEOUT}，并取消在途的那一次 —— 上面的切分管「留得出重试的时间」，定时器管「结果按时到」。
 * T_k 管到<b>回包读完</b>为止（每一次自己挂一个定时器，审查 2026-10-07 U12）：{@code HttpRequest} 自带的 timeout 收到响应头就不再计，
 * 服务端先回 200、再慢慢吐空白时，原先这一次会一直吊到截止。
 * 例：8 秒的站队窗口、余量 1 秒 → R = 7 秒，第一次最多等 3.85 秒；吊住了还剩约 3 秒再试一次。
 * ❗代价（2026-10-07 本机代理实测）：一个常要 4–6 秒的推理模型，在这样的窗口里两次都被切成超时（5 个里 3 个），
 * 不切分反而来得及 —— 防「吊住」与防「慢」是相反的两头，所以 s 是设置项，慢而稳的模型调高它或设 reasoningEffort。
 *
 * <h2>哪些重试</h2>
 * <ul>
 *   <li><b>重试</b>：408 · 429 · 500 · 502 · 503 · 504；连不上 / 连接断了；单次超时；200 但回包是一条错误（{@code err200}）、
 *       认不出（{@code bad}）、或者 {@code content} 是空的（{@code empty}：推理模型可能只写了 {@code reasoning_content}）。</li>
 *   <li><b>再问一遍，只一遍</b>：回答认不出编号（{@code unparsed}）或越界（{@code range}）。用户消息末尾加一句输出要求，仍不带历史。</li>
 *   <li><b>不重试</b>：401 · 403 → {@code AUTH}，402 → {@code BALANCE}（这两种<b>记住</b>：之后的决定不再发请求，改好配置后 reload）；
 *       400 · 404 · 422 及别的 4xx → {@code BAD_REQUEST}；别的状态码 → {@code HTTP_ERROR}。详细原因只在第一次的那一行里写全。
 *       回包超过 {@link #maxReplyBytes} → 读到一半丢掉，{@code BAD_RESPONSE}（经过 {@code big}）。</li>
 * </ul>
 *
 * <h2>熔断与并发</h2>
 * 熔断见 {@link CircuitBreaker}（全服连续 breakerThreshold 次传输失败 / 超时 / 5xx → 冷却 breakerCooldownMs 内直接 {@code CIRCUIT_OPEN}）。
 * 全服共用 {@code maxConcurrent} 个在途名额（{@link AsyncPermits}）；排不上的排队，队满（{@code maxQueued}）当场回 {@code QUEUE_FULL}。
 * 名额从拿到一直占到这一次决定收场（重试的退避期间也占着：429 正是该少发的时候）。
 *
 * <h2>密钥</h2>
 * 只进请求头。日志里的每一行（含 debugLog 打出的请求体与回包）与返回的说明，写之前都把密钥换成 {@code ***} —— 有的服务端会在错误回包里把它回显出来。
 */
public final class LlmService implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("heavyseas");

    /** 认不出编号 / 越界时最多再问几遍。 */
    static final int MAX_REASKS = 1;
    /** 这些状态码值得再试：服务端一时忙不过来。 */
    static final Set<Integer> RETRYABLE_STATUS = Set.of(408, 429, 500, 502, 503, 504);

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final LlmConfig config;
    private final String disabledWhy;
    private final RulebookExcerpts excerpts;
    private final Clock clock;
    private final URI endpoint;
    private final HttpClient http;
    private final ExecutorService worker;
    private final ScheduledThreadPoolExecutor timer;
    private final AsyncPermits permits;
    private final CircuitBreaker breaker;
    private final Map<Long, Decision> live = new ConcurrentHashMap<>();
    private final Map<Fallback, AtomicLong> fallbackCounts = new EnumMap<>(Fallback.class);
    private final AtomicLong chosenCount = new AtomicLong();
    /** 详细原因已经写过一遍的那几种（「BAD_REQUEST 400」……）：之后只写一句「同上」。 */
    private final Set<String> reportedOnce = ConcurrentHashMap.newKeySet();
    /** 密钥被拒 / 余额不足：记住，之后的决定不再发请求，直到 reload 换一个服务。 */
    private volatile Fallback rejectedAs;
    private volatile String rejectedBecause;
    private volatile boolean closed;

    private LlmService(LlmConfig config, String disabledWhy, RulebookExcerpts excerpts, Clock clock) {
        this.config = config;
        this.disabledWhy = disabledWhy;
        this.excerpts = excerpts;
        this.clock = clock;
        this.breaker = new CircuitBreaker(config.breakerThreshold(), config.breakerCooldownMs());
        for (Fallback f : Fallback.values()) {
            fallbackCounts.put(f, new AtomicLong());
        }
        if (disabledWhy != null) {
            this.endpoint = null;
            this.http = null;
            this.worker = null;
            this.timer = null;
            this.permits = null;
            return;
        }
        this.endpoint = config.endpoint();
        this.worker = Executors.newCachedThreadPool(daemon("heavyseas-llm-"));
        this.timer = new ScheduledThreadPoolExecutor(1, daemon("heavyseas-llm-timer-"));
        this.timer.setRemoveOnCancelPolicy(true);
        this.permits = new AsyncPermits(config.maxConcurrent(), config.maxQueued());
        this.http = HttpClient.newBuilder()
                .executor(worker)
                .connectTimeout(Duration.ofMillis(Math.min(5_000, config.attemptTimeoutMs())))
                .version(HttpClient.Version.HTTP_1_1)       // 明文 http 上不去试 h2c 升级：有的本机服务端处理不好那个头
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 关着的服务：{@link #choose} 一律当场回 {@code DISABLED}，不起线程、不建连接。 */
    public static LlmService disabled(String why) {
        return new LlmService(LlmConfig.off(), why, null, Clock.systemUTC());
    }

    /**
     * 按设置起一个服务。设置关着就是 {@link #disabled}；规则摘录拼不出来（jar 里少了书、节表过期）也是关着，并记一行 ERROR。
     */
    public static LlmService start(LlmConfig config) {
        return start(config, Clock.systemUTC());
    }

    static LlmService start(LlmConfig config, Clock clock) {
        if (!config.enabled()) {
            return new LlmService(config, "配置里 enabled 不是 true", null, clock);
        }
        RulebookExcerpts excerpts;
        try {
            excerpts = RulebookExcerpts.load(config.language());
        } catch (RuntimeException e) {
            String why = "规则摘录拼不出来：" + e.getMessage();
            LOGGER.error("大模型替身关着：{}", why);
            return new LlmService(config, why, null, clock);
        }
        return new LlmService(config, null, excerpts, clock);
    }

    public LlmConfig config() {
        return config;
    }

    public boolean enabled() {
        return disabledWhy == null && !closed;
    }

    /** 关着的原因；开着是 {@code null}。 */
    public String disabledWhy() {
        return closed ? "服务已关" : disabledWhy;
    }

    /** 提示词用的语言 —— 调用方拼局面时用同一种。 */
    public String language() {
        return config.language();
    }

    /** 规则摘录（开着时才有）：调用方拼局面时可以取角色本事（{@link RulebookExcerpts#characterNotes}）。 */
    public RulebookExcerpts excerpts() {
        return excerpts;
    }

    /** 这一刻占着名额的决定数（含退避等重试的）。 */
    public int inFlight() {
        return permits == null ? 0 : permits.inUse();
    }

    public int queued() {
        return permits == null ? 0 : permits.queued();
    }

    /** 还没收场的决定数（在途 + 排队）。收场之后一定回到 0 —— 单测靠它查「没有挂着的」。 */
    public int pending() {
        return live.size();
    }

    /** 服务商这一侧的状况（status 用）：正常 · 熔断中 · 密钥被拒 / 余额不足。 */
    public String health() {
        if (rejectedAs != null) {
            return rejectedAs + "（" + rejectedBecause + "）：不再发请求，改好配置后 /seasllm reload";
        }
        long now = System.nanoTime();
        long left = breaker.remainingMs(now);
        if (left > 0) {
            return "熔断中，还要 " + left + " ms（连续 " + breaker.consecutive() + " 次失败，最后一次 " + breaker.lastFailure() + "）";
        }
        return "正常（连续失败 " + breaker.consecutive() + " 次）";
    }

    /** 这个服务起来以后各条路各走了几次（status 用）。 */
    public String tally() {
        StringBuilder sb = new StringBuilder("模型 ").append(chosenCount.get());
        fallbackCounts.forEach((f, n) -> {
            if (n.get() > 0) {
                sb.append(" · ").append(f).append(' ').append(n.get());
            }
        });
        return sb.toString();
    }

    /**
     * 问模型挑一项。当场返回；future 只会正常完成（见类注释）。
     */
    public CompletableFuture<ChoiceOutcome> choose(ChoiceRequest request) {
        Decision d = new Decision(SEQUENCE.incrementAndGet(), request, System.nanoTime());
        try {
            String problem = request == null ? "请求是 null" : request.problem();
            if (problem != null) {
                d.finish(Fallback.INVALID_REQUEST, problem);
                return d.outcome;
            }
            if (disabledWhy != null) {
                d.finish(Fallback.DISABLED, disabledWhy);
                return d.outcome;
            }
            if (rejectedAs != null) {
                d.finish(rejectedAs, rejectedNote());
                return d.outcome;
            }
            long budgetNanos = Duration.between(clock.instant(), request.deadline()).toNanos()
                    - TimeUnit.MILLISECONDS.toNanos(config.deadlineMarginMs());
            d.deadlineNanos = d.startNanos + budgetNanos;
            if (budgetNanos < TimeUnit.MILLISECONDS.toNanos(config.minAttemptMs())) {
                d.finish(Fallback.TIMEOUT, "截止前只剩 " + TimeUnit.NANOSECONDS.toMillis(budgetNanos) + " ms（已让出余量 "
                        + config.deadlineMarginMs() + " ms），不到地板 " + config.minAttemptMs() + " ms，不发");
                return d.outcome;
            }
            if (breaker.blocks(System.nanoTime())) {
                d.finish(Fallback.CIRCUIT_OPEN, breakerNote());
                return d.outcome;
            }
            ChoicePrompt.Messages messages = ChoicePrompt.build(request, excerpts.excerpt(request.kind()), config.language());
            d.body = ChatWire.requestBody(config, messages);
            synchronized (this) {
                if (closed) {
                    d.finish(Fallback.SHUTDOWN, "服务已关");
                    return d.outcome;
                }
                live.put(d.id, d);
                d.deadlineTimer = timer.schedule(d::onDeadline, budgetNanos, TimeUnit.NANOSECONDS);
                if (!permits.acquire(d)) {
                    d.finish(Fallback.QUEUE_FULL, "在途 " + permits.inUse() + " 个、排队 " + permits.queued() + " 个，队已满");
                }
            }
        } catch (RejectedExecutionException e) {
            d.finish(Fallback.SHUTDOWN, "服务正在关：" + e);
        } catch (Throwable t) {
            d.finish(Fallback.INTERNAL_ERROR, t.toString());
        }
        return d.outcome;
    }

    /** 关掉：还没收场的一律收成 {@code SHUTDOWN}，线程与连接一并收掉。可以重复调。 */
    @Override
    public void close() {
        List<Decision> pending;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            pending = new ArrayList<>(live.values());
        }
        for (Decision d : pending) {
            d.finish(Fallback.SHUTDOWN, "服务关了（服务端停了或重读了配置）");
        }
        if (timer != null) {
            timer.shutdownNow();
            worker.shutdownNow();
            http.shutdownNow();
        }
    }

    /** 单测用：关掉之后等自己的线程都退出。 */
    boolean awaitTermination(Duration within) throws InterruptedException {
        if (timer == null) {
            return true;
        }
        long end = System.nanoTime() + within.toNanos();
        return timer.awaitTermination(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS)
                && worker.awaitTermination(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS)
                && http.awaitTermination(Duration.ofNanos(Math.max(0, end - System.nanoTime())));
    }

    /**
     * 第 k 次尝试（从 1 数）的超时：不是最后一次就只给剩下预算的 {@code attemptShare}，给退避与下一次留出时间；
     * 那一份不到地板、或者这就是最后一次，才把剩下的全给它。公式见类注释。
     */
    static long attemptTimeoutMs(LlmConfig config, int k, long remainingMs) {
        long share = (long) Math.floor(config.attemptShare() * remainingMs);
        boolean last = k >= config.maxAttempts() || share < config.minAttemptMs();
        return Math.min(config.attemptTimeoutMs(), last ? remainingMs : share);
    }

    /**
     * 回包最多读多少字节（审查 2026-10-07 U12：原先没有上限，能改地址的人可以拿一个超大回包把服务端撑爆内存）。
     * 64 KB 起；{@code max_tokens} 调大时按每个 token 16 字节放宽 —— 推理模型的思考（{@code reasoning_content}）也在回包里，
     * 中文按 {@code \\uXXXX} 转义一个字就占 6 字节。{@code max_tokens} 有上限，所以这里最多 512 KB。
     */
    static int maxReplyBytes(LlmConfig config) {
        return (int) Math.max(64L * 1024, config.maxTokens() * 16L);
    }

    /** 回包超过 {@link #maxReplyBytes}：读到一半就丢掉、断开。 */
    static final class ReplyTooLarge extends IOException {
        ReplyTooLarge(int maxBytes) {
            super("回包超过 " + maxBytes + " 字节");
        }
    }

    /** 读回包（UTF-8），读到超过 {@code maxBytes} 就停、取消订阅、按 {@link ReplyTooLarge} 失败 —— 不把整份读进内存。 */
    static HttpResponse.BodyHandler<String> limitedBody(int maxBytes) {
        return info -> new LimitedBody(maxBytes);
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<String> {

        private final int maxBytes;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        /** Flow 保证 onNext · onError · onComplete 不并发，但取消之后可能还会来几包：来了也不收。 */
        private boolean done;

        LimitedBody(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (done) {
                return;
            }
            for (ByteBuffer item : items) {
                if (bytes.size() + (long) item.remaining() > maxBytes) {
                    done = true;
                    subscription.cancel();
                    body.completeExceptionally(new ReplyTooLarge(maxBytes));
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable error) {
            if (!done) {
                done = true;
                body.completeExceptionally(error);
            }
        }

        @Override
        public void onComplete() {
            if (!done) {
                done = true;
                body.complete(bytes.toString(StandardCharsets.UTF_8));
            }
        }

        @Override
        public CompletionStage<String> getBody() {
            return body;
        }
    }

    /** 异常链上有没有这一种（HttpClient 会把回包那一侧的异常包上一两层）。 */
    private static boolean causedBy(Throwable t, Class<? extends Throwable> kind) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (kind.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    /** 第 k 次失败（从 1 数）后的退避：指数 + 抖动；上限不管抖动那一截。 */
    static long backoffMs(LlmConfig config, int k) {
        long exponential = config.backoffBaseMs() * (1L << Math.min(k - 1, 20));
        return Math.min(config.backoffMaxMs(), exponential)
                + ThreadLocalRandom.current().nextLong(config.backoffBaseMs() / 2 + 1);
    }

    private String rejectedNote() {
        return "服务商先前拒了（" + rejectedBecause + "），不再发请求；改好配置后 /seasllm reload";
    }

    private String breakerNote() {
        return "熔断中：连续 " + breaker.consecutive() + " 次失败（最后一次 " + breaker.lastFailure() + "），还要 "
                + breaker.remainingMs(System.nanoTime()) + " ms 才放行";
    }

    // ---- 一次决定 ----

    private final class Decision implements AsyncPermits.Waiter {

        final long id;
        final ChoiceRequest request;
        final long startNanos;
        final CompletableFuture<ChoiceOutcome> outcome = new CompletableFuture<>();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean holdsPermit = new AtomicBoolean();
        /** 已经发出去的次数（从 1 数的 k 就是它）。 */
        private final AtomicInteger sent = new AtomicInteger();
        /** 每一次尝试落了个什么（{@link ChoiceOutcome#trail}）。 */
        private final List<String> trail = Collections.synchronizedList(new ArrayList<>());
        volatile long deadlineNanos;
        volatile String body;
        volatile ScheduledFuture<?> deadlineTimer;
        private volatile String reminderBody;
        private volatile boolean remind;
        private volatile int reasks;
        private volatile ScheduledFuture<?> retryTimer;
        private volatile CompletableFuture<HttpResponse<String>> inFlight;
        private volatile Usage usage = Usage.UNKNOWN;
        private volatile String answer;
        private volatile Fallback lastReason;
        private volatile String lastDetail;

        Decision(long id, ChoiceRequest request, long startNanos) {
            this.id = id;
            this.request = request;
            this.startNanos = startNanos;
        }

        @Override
        public boolean abandoned() {
            return finished.get();
        }

        @Override
        public void granted() {
            holdsPermit.set(true);
            if (finished.get()) {
                releasePermit();
                return;
            }
            try {
                worker.execute(this::attempt);
            } catch (RejectedExecutionException e) {
                finish(Fallback.SHUTDOWN, "服务正在关");
            }
        }

        private long remainingMs() {
            return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        }

        /** 发一次请求。名额已经在手上。 */
        void attempt() {
            try {
                if (finished.get()) {
                    releasePermit();
                    return;
                }
                if (closed) {
                    finish(Fallback.SHUTDOWN, "服务关了");
                    return;
                }
                if (rejectedAs != null) {
                    finish(rejectedAs, rejectedNote());
                    return;
                }
                long remaining = remainingMs();
                if (remaining < config.minAttemptMs()) {
                    finish(lastReason == null ? Fallback.TIMEOUT : lastReason, (lastDetail == null ? "" : lastDetail + " · ")
                            + "剩下 " + remaining + " ms，不到地板 " + config.minAttemptMs() + " ms，不再发");
                    return;
                }
                if (breaker.blocks(System.nanoTime())) {
                    finish(Fallback.CIRCUIT_OPEN, breakerNote());
                    return;
                }
                int k = sent.incrementAndGet();
                long timeoutMs = attemptTimeoutMs(config, k, remaining);
                String payload = remind ? reminderBody() : body;
                HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofMillis(timeoutMs))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
                if (config.hasKey()) {
                    builder.header("Authorization", "Bearer " + config.apiKey());
                }
                if (config.debugLog()) {
                    debug("请求（第 " + k + " 次，这一次最多等 " + timeoutMs + " ms，预算还剩 " + remaining + " ms）：" + payload);
                }
                long sentAt = System.nanoTime();
                CompletableFuture<HttpResponse<String>> f =
                        http.sendAsync(builder.build(), limitedBody(maxReplyBytes(config)));
                inFlight = f;
                // 这一次的总时限（审查 2026-10-07 U12）：HttpRequest 的 timeout 只管到响应头 —— 服务端先回 200、再慢慢吐空白时，
                // 读回包没有时限，切分与重试全部失效，只能吊到截止。这个定时器管到回包读完：到点就把这一次切开、按单次超时算
                AtomicBoolean cut = new AtomicBoolean();
                ScheduledFuture<?> attemptTimer;
                try {
                    attemptTimer = timer.schedule(() -> {
                        if (!f.isDone()) {
                            cut.set(true);
                            f.cancel(true);
                        }
                    }, timeoutMs, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException e) {
                    f.cancel(true);
                    finish(Fallback.SHUTDOWN, "服务正在关");
                    return;
                }
                if (finished.get()) {
                    f.cancel(true);               // 截止的那一刻正好落在发出与登记之间
                }
                f.whenComplete((response, error) -> {
                    attemptTimer.cancel(false);
                    onResponse(timeoutMs, sentAt, response, error, error != null && cut.get());
                });
            } catch (Throwable t) {
                finish(Fallback.INTERNAL_ERROR, "发请求时出错：" + t);
            }
        }

        private String reminderBody() {
            if (reminderBody == null) {
                reminderBody = ChatWire.requestBody(config,
                        ChoicePrompt.build(request, excerpts.excerpt(request.kind()), config.language(), true));
            }
            return reminderBody;
        }

        /** @param cutByAttemptTimer 这一次是被它自己的总时限切开的（见 {@link #attempt}）：按单次超时算 */
        private void onResponse(long timeoutMs, long sentAt, HttpResponse<String> response, Throwable error,
                                boolean cutByAttemptTimer) {
            try {
                inFlight = null;
                if (finished.get()) {
                    return;                       // 已经按截止或关服收了场：这一份回包作废
                }
                if (error != null) {
                    Throwable cause = unwrap(error);
                    if (cutByAttemptTimer) {
                        unhealthy("timeout");
                        retryOrFinish(Fallback.TIMEOUT, "timeout", "单次 " + timeoutMs + " ms 没读完回答", -1, false);
                    } else if (causedBy(error, ReplyTooLarge.class)) {
                        // 不重试：同一个请求再发一次多半还是这么大；也不算服务商不健康（它回得很快）
                        trail.add("big");
                        finish(Fallback.BAD_RESPONSE, "回包超过 " + maxReplyBytes(config) / 1024 + " KB，丢掉不读");
                    } else if (cause instanceof HttpConnectTimeoutException) {
                        unhealthy("conn");
                        retryOrFinish(Fallback.NETWORK_ERROR, "conn", "连接超时", -1, false);
                    } else if (cause instanceof HttpTimeoutException) {
                        unhealthy("timeout");
                        retryOrFinish(Fallback.TIMEOUT, "timeout", "单次 " + timeoutMs + " ms 没等到回答", -1, false);
                    } else if (cause instanceof CancellationException) {
                        finish(Fallback.INTERNAL_ERROR, "请求被取消，但这一次决定还没收场");
                    } else if (cause instanceof IOException) {
                        unhealthy("conn");
                        retryOrFinish(Fallback.NETWORK_ERROR, "conn", describe(cause), -1, false);
                    } else {
                        finish(Fallback.INTERNAL_ERROR, "请求出错：" + cause);
                    }
                    return;
                }
                int status = response.statusCode();
                String text = response.body();
                if (config.debugLog()) {
                    debug("回包（HTTP " + status + "，" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sentAt)
                            + " ms）：" + (text == null ? "" : text.length() > 8_000 ? text.substring(0, 8_000) + "…（截断）" : text));
                }
                String code = String.valueOf(status);
                String what = "HTTP " + status + " " + ChatWire.snippet(text);
                if (RETRYABLE_STATUS.contains(status)) {
                    if (status >= 500) {
                        unhealthy(code);
                    }
                    retryOrFinish(Fallback.HTTP_ERROR, code, what, retryAfterMs(response), false);
                    return;
                }
                if (status == 401 || status == 403 || status == 402) {
                    Fallback reason = status == 402 ? Fallback.BALANCE : Fallback.AUTH;
                    trail.add(code);
                    reject(reason, "HTTP " + status);
                    finish(reason, what + "（" + (status == 402 ? "余额不足" : "密钥被拒")
                            + "；之后的决定不再发请求，改好配置后 /seasllm reload）");
                    return;
                }
                if (status >= 400 && status < 500) {
                    trail.add(code);
                    finish(Fallback.BAD_REQUEST, once("BAD_REQUEST " + status, what, "HTTP " + status + "（同上，详见这种错第一次的那一行）"));
                    return;
                }
                if (status < 200 || status >= 300) {
                    trail.add(code);
                    finish(Fallback.HTTP_ERROR, once("HTTP_ERROR " + status, what, "HTTP " + status + "（同上）"));
                    return;
                }
                healthy();
                ChatWire.Reply reply;
                try {
                    reply = ChatWire.parseReply(text);
                } catch (ChatWire.BadReply e) {
                    retryOrFinish(Fallback.BAD_RESPONSE, e.token(), e.getMessage(), -1, false);
                    return;
                }
                usage = usage.plus(reply.usage());
                String visible = ChoiceParser.visible(reply.content());
                answer = visible.length() <= 200 ? visible : visible.substring(0, 200) + "…";
                if (visible.isEmpty()) {
                    retryOrFinish(Fallback.UNPARSEABLE, "empty", "空回答（finish_reason=" + reply.finishReason() + "）", -1, false);
                    return;
                }
                int options = request.options().size();
                ChoiceParser.Result r = ChoiceParser.parse(reply.content(), options);
                switch (r.kind()) {
                    case OK -> {
                        trail.add("ok");
                        finishChosen(r.number() - 1);
                    }
                    case OUT_OF_RANGE -> retryOrFinish(Fallback.OUT_OF_RANGE, "range",
                            "回答「" + answer + "」不在 1–" + options + " 里", -1, true);
                    case UNPARSEABLE -> retryOrFinish(Fallback.UNPARSEABLE, "unparsed",
                            "回答「" + answer + "」认不出是一个编号", -1, true);
                }
            } catch (Throwable t) {
                finish(Fallback.INTERNAL_ERROR, "读回包时出错：" + t);
            }
        }

        /**
         * 这一次没成：记进轨迹；能再试就排一次（名额照占着），次数用完或来不及就带着这个原因收场。
         *
         * @param reask 认不出编号 / 越界：只再问一遍，不退避，下一次的用户消息末尾加一句输出要求
         */
        private void retryOrFinish(Fallback reason, String token, String detail, long retryAfterMs, boolean reask) {
            trail.add(token);
            lastReason = reason;
            lastDetail = detail;
            if (reask && reasks >= MAX_REASKS) {
                finish(reason, detail + " · 已再问过一遍");
                return;
            }
            int done = sent.get();
            if (done >= config.maxAttempts()) {
                finish(reason, detail + " · 已试 " + done + " 次");
                return;
            }
            long backoff = reask ? 0 : retryAfterMs >= 0 ? retryAfterMs : backoffMs(config, done);
            long remaining = remainingMs();
            if (remaining - backoff < config.minAttemptMs()) {
                finish(reason, detail + " · 来不及再试（还剩 " + remaining + " ms，退避 " + backoff + " ms，地板 "
                        + config.minAttemptMs() + " ms）");
                return;
            }
            if (reask) {
                reasks++;
                remind = true;
            }
            try {
                retryTimer = timer.schedule(this::attempt, backoff, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                finish(Fallback.SHUTDOWN, "服务正在关");
                return;
            }
            if (finished.get()) {
                retryTimer.cancel(false);
            }
        }

        void onDeadline() {
            String what;
            if (sent.get() == 0) {
                what = "排队等名额时到了截止时间（一次也没发出去）";
            } else if (inFlight != null) {
                trail.add("cut");
                what = "等回答时到了截止时间（已发 " + sent.get() + " 次）";
            } else {
                what = "等重试时到了截止时间（已发 " + sent.get() + " 次）";
            }
            finish(Fallback.TIMEOUT, what + (lastDetail == null ? "" : "；先前：" + lastDetail));
        }

        void finishChosen(int index) {
            settle(new ChoiceOutcome(index, null, "第 " + (index + 1) + " 项", redact(answer), elapsedMs(), usage,
                    snapshot()));
        }

        /** 说明与原话也要去掉密钥：它们回到调用方手里，会进聊天栏（probe）与调用方自己的日志。 */
        void finish(Fallback reason, String detail) {
            settle(new ChoiceOutcome(-1, reason, redact(detail), redact(answer), elapsedMs(), usage, snapshot()));
        }

        private List<String> snapshot() {
            synchronized (trail) {
                return List.copyOf(trail);
            }
        }

        private long elapsedMs() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        }

        /** 只有第一个到的能收场：记一行、收拾定时器与在途请求、还名额，最后才把结果交出去。 */
        private void settle(ChoiceOutcome o) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            try {
                if (o.chosen()) {
                    chosenCount.incrementAndGet();
                } else {
                    fallbackCounts.get(o.fallback()).incrementAndGet();
                }
                LOGGER.info(redact(logLine(id, request, o)));
                ScheduledFuture<?> t = deadlineTimer;
                if (t != null) {
                    t.cancel(false);
                }
                ScheduledFuture<?> r = retryTimer;
                if (r != null) {
                    r.cancel(false);
                }
                CompletableFuture<HttpResponse<String>> f = inFlight;
                if (f != null) {
                    f.cancel(true);
                }
                live.remove(id);
                releasePermit();
            } catch (Throwable t) {
                LOGGER.error(redact("大模型决定 #" + id + " 收场时出错：" + t));
            } finally {
                deliver(o);
            }
        }

        /**
         * 把结果交出去。调用方挂在 future 上的回调会跑在完成它的那个线程上 —— 收场常常发生在全服共用的那一个定时线程上
         * （截止、重试都排在那里），回调慢一点就会耽误别的决定的截止。所以交给工作线程去完成；服务已经在关就当场完成。
         */
        private void deliver(ChoiceOutcome o) {
            if (worker != null) {
                try {
                    worker.execute(() -> outcome.complete(o));
                    return;
                } catch (RejectedExecutionException e) {
                    // 已经在关：下面当场完成
                }
            }
            outcome.complete(o);
        }

        private void releasePermit() {
            if (holdsPermit.compareAndSet(true, false)) {
                permits.release();
            }
        }

        private void debug(String text) {
            LOGGER.info(redact("大模型调试 #" + id + " " + text));
        }
    }

    // ---- 服务商的状况：熔断 · 被拒 · 只写一次的详细原因 ----

    private void unhealthy(String what) {
        if (breaker.failure(System.nanoTime(), what) == CircuitBreaker.Change.OPENED) {
            LOGGER.warn("大模型熔断：连续 {} 次失败（最后一次 {}），之后 {} ms 内的决定直接走退路", breaker.threshold(), what,
                    breaker.cooldownMs());
        }
    }

    private void healthy() {
        if (breaker.success() == CircuitBreaker.Change.RECOVERED) {
            LOGGER.info("大模型熔断解除：冷却之后又通了");
        }
    }

    private void reject(Fallback reason, String because) {
        rejectedBecause = because;
        rejectedAs = reason;
    }

    /** 这种错第一次出现时写全，之后只写一句短的：同一条错在每一行里重复整段回包，只会把别的淹掉。 */
    private String once(String signature, String full, String compact) {
        return reportedOnce.add(signature) ? full : compact;
    }

    /**
     * 每次决定那一行。{@code 路=模型} 或 {@code 路=退路:原因}，{@code 经过=} 是每一次尝试落了个什么 —— 数日志就数得出走了哪条路（ADR-0096 §6）。
     */
    static String logLine(long id, ChoiceRequest request, ChoiceOutcome o) {
        StringBuilder sb = new StringBuilder("大模型决定 #").append(id);
        sb.append(" 座位=").append(request == null ? "?" : request.seat());
        sb.append(" 窗=").append(request == null || request.kind() == null ? "?" : request.kind());
        sb.append(" 选项=").append(request == null || request.options() == null ? 0 : request.options().size());
        if (o.chosen()) {
            String label = request.options().get(o.index());
            sb.append(" 路=模型 选=").append(o.index() + 1).append("「")
                    .append(label.length() <= 40 ? label : label.substring(0, 40) + "…").append("」");
        } else {
            sb.append(" 路=退路:").append(o.fallback());
        }
        sb.append(" 经过=").append(o.trailText());
        sb.append(" 耗时=").append(o.latencyMs()).append("ms");
        sb.append(" token=").append(o.usage());
        if (!o.chosen()) {
            sb.append(" 说明=").append(o.detail());
        }
        return sb.toString().replace('\n', ' ');
    }

    private String redact(String text) {
        if (text == null || !config.hasKey()) {
            return text;
        }
        return text.replace(config.apiKey(), "***");
    }

    private static Throwable unwrap(Throwable t) {
        Throwable c = t;
        while ((c instanceof CompletionException || c instanceof ExecutionException) && c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : "：" + message);
    }

    /** {@code Retry-After} 只认秒数（日期格式的不认，走默认退避）。 */
    private static long retryAfterMs(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").map(v -> {
            try {
                double seconds = Double.parseDouble(v.strip());
                return seconds >= 0 ? (long) (seconds * 1000) : -1L;
            } catch (NumberFormatException e) {
                return -1L;
            }
        }).orElse(-1L);
    }

    private static ThreadFactory daemon(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
