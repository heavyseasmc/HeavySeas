package io.github.heavyseasmc.mod.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.heavyseasmc.mod.llm.ChoiceOutcome.Fallback;
import io.github.heavyseasmc.mod.llm.FakeChatServer.Reply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 接入层对调用方的承诺（{@link LlmService} 类注释），逐条对着一个只绑回环的假服务端核。不连外网。
 *
 * <p>DeepSeek 那一类服务商常见的毛病（503 一阵一阵、回包前面先吐空行、空回答、429 带 Retry-After、吊住不回）都在这里用假服务端造，
 * 不去连真的服务商（用户 2026-10-07：实测只调本机的接口）。
 *
 * <p>❗时间相关的判据都按「截止时间」量，不按「大概多久」：窗口最短 6 秒，结果晚到一点就等于没到。
 * 整个类挂 {@code SEPARATE_THREAD} 的超时 —— 同线程的 {@code @Timeout} 对真挂死毫无作用（证伪表）。
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class LlmServiceTest {

    /** 假密钥：只在这个类里出现，用来核对它进不了日志。 */
    static final String KEY = "sk-fake-test-key-7f3a91c2d4e5";

    /** 单测用的设置：退避与地板都调小，免得一条测试等好几秒；熔断默认关，只在熔断那两条里开。 */
    static final class Cfg {
        final String baseUrl;
        String apiKey = KEY;
        int maxTokens = 64;
        double temperature = LlmConfig.TEMPERATURE_UNSET;
        String effort = "";
        int attemptTimeoutMs = 15_000;
        double attemptShare = LlmConfig.ATTEMPT_SHARE_DEFAULT;
        int maxAttempts = 3;
        int minAttemptMs = 250;
        int backoffBaseMs = 50;
        int backoffMaxMs = 400;
        int maxConcurrent = 4;
        int maxQueued = 32;
        int marginMs = 0;
        int breakerThreshold = 0;
        int breakerCooldownMs = 30_000;
        String language = "zh_cn";
        boolean debug;

        Cfg(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        Cfg attempts(int n) {
            maxAttempts = n;
            return this;
        }

        Cfg timeout(int ms) {
            attemptTimeoutMs = ms;
            return this;
        }

        Cfg floor(int ms) {
            minAttemptMs = ms;
            return this;
        }

        Cfg margin(int ms) {
            marginMs = ms;
            return this;
        }

        Cfg concurrency(int inFlight, int queued) {
            maxConcurrent = inFlight;
            maxQueued = queued;
            return this;
        }

        Cfg breaker(int threshold, int cooldownMs) {
            breakerThreshold = threshold;
            breakerCooldownMs = cooldownMs;
            return this;
        }

        Cfg debug() {
            debug = true;
            return this;
        }

        LlmConfig build() {
            return new LlmConfig(true, baseUrl, apiKey, "fake-model", maxTokens, temperature, effort, attemptTimeoutMs,
                    attemptShare, maxAttempts, minAttemptMs, backoffBaseMs, backoffMaxMs, maxConcurrent, maxQueued, marginMs,
                    breakerThreshold, breakerCooldownMs, language, debug);
        }

        LlmService start() {
            return LlmService.start(build());
        }
    }

    static Cfg cfg(FakeChatServer server) {
        return new Cfg(server.baseUrl());
    }

    static ChoiceRequest request(int options, long deadlineMs) {
        List<String> labels = IntStream.rangeClosed(1, options).mapToObj(i -> "选项" + i).toList();
        return new ChoiceRequest("测试座", DecisionKind.ACTION, labels, "局面：测试用", Instant.now().plusMillis(deadlineMs));
    }

    static ChoiceOutcome await(CompletableFuture<ChoiceOutcome> future) throws Exception {
        return future.get(30, TimeUnit.SECONDS);
    }

    static long msSince(long t0) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
    }

    static List<String> liveThreads(String prefix) {
        return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive).map(Thread::getName)
                .filter(n -> n.startsWith(prefix)).toList();
    }

    static String userMessage(String body) {
        return JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("messages").get(1).getAsJsonObject()
                .get("content").getAsString();
    }

    // ---- 正常的一次 ----

    @Test
    @DisplayName("回答「3」→ 第 3 项（下标 2）：一次请求、token 记下；请求体不带历史，带规则摘录、局面、编号选项，密钥只在请求头")
    void success() throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, body) -> Reply.answer("3"));
             LlmService service = cfg(server).start()) {
            ChoiceOutcome o = await(service.choose(request(5, 10_000)));
            assertTrue(o.chosen(), o.toString());
            assertEquals(2, o.index());
            assertEquals(2, o.choice().orElseThrow());
            assertEquals(List.of("ok"), o.trail());
            assertEquals("3", o.answer());
            assertEquals(812, o.usage().prompt());
            assertEquals(3, o.usage().completion());
            assertEquals(1, server.requests());

            FakeChatServer.Received r = server.received().getFirst();
            assertEquals("Bearer " + KEY, r.authorization());
            assertFalse(r.body().contains(KEY), "密钥进了请求体");
            JsonObject body = JsonParser.parseString(r.body()).getAsJsonObject();
            assertEquals("fake-model", body.get("model").getAsString());
            assertEquals(64, body.get("max_tokens").getAsInt());
            assertFalse(body.has("temperature"), "temperature 是 -1（不发），却发了");
            assertFalse(body.has("reasoning_effort"), "reasoningEffort 是空串（不发），却发了");
            assertFalse(body.get("stream").getAsBoolean());
            JsonArray messages = body.getAsJsonArray("messages");
            assertEquals(2, messages.size(), "不带历史：一条系统、一条用户");
            assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
            assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
            String user = userMessage(r.body());
            for (String part : List.of("【规则摘录】", "# 第七章", "## 计分", "你是：测试座", "局面：测试用", "1. 选项1", "5. 选项5")) {
                assertTrue(user.contains(part), "用户消息里缺「" + part + "」");
            }
            assertTrue(user.endsWith("1 到 5 之间的一个选项编号。"), "输出要求要是最后一句");
            assertFalse(user.contains("先前那一次"), "第一次不该带「再问一遍」那句");
            assertEquals(0, service.pending());
            assertEquals(0, service.inFlight());
        }
    }

    @Test
    @DisplayName("配了 temperature 与 reasoningEffort 才发这两个字段；没有密钥不带 Authorization；en_us 用英文书")
    void optionalFieldsAreSentWhenSet() throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, body) -> Reply.answer("1"))) {
            Cfg c = cfg(server);
            c.apiKey = "";
            c.temperature = 0.2;
            c.effort = "low";
            c.maxTokens = 32;
            c.language = "en_us";
            try (LlmService service = c.start()) {
                assertTrue(await(service.choose(request(2, 10_000))).chosen());
            }
            FakeChatServer.Received r = server.received().getFirst();
            JsonObject body = JsonParser.parseString(r.body()).getAsJsonObject();
            assertEquals(0.2, body.get("temperature").getAsDouble(), 1e-9);
            assertEquals("low", body.get("reasoning_effort").getAsString());
            assertEquals(32, body.get("max_tokens").getAsInt());
            assertNull(r.authorization(), "没有密钥就不该带 Authorization 头");
            String user = userMessage(r.body());
            assertTrue(user.contains("[Rules excerpt]") && user.contains("# Chapter 7") && user.contains("## Scoring"));
        }
    }

    // ---- 回答认不出 / 越界：再问一遍，只一遍 ----

    @ParameterizedTest(name = "「{0}」")
    @ValueSource(strings = {"I'd pick the third one", "选项 3", "3 或 4", "三"})
    @DisplayName("认不出编号 → 再问一遍（带一句提醒、仍不带历史），还认不出 → UNPARSEABLE；只问两次")
    void garbageIsReaskedOnce(String answer) throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, body) -> Reply.answer(answer));
             LlmService service = cfg(server).attempts(5).start()) {
            ChoiceOutcome o = await(service.choose(request(5, 10_000)));
            assertEquals(Fallback.UNPARSEABLE, o.fallback(), o.toString());
            assertEquals(List.of("unparsed", "unparsed"), o.trail());
            assertEquals(2, server.requests(), "只再问一遍");
            String second = userMessage(server.received().get(1).body());
            assertTrue(second.endsWith("别的字一个也不要。）"), "再问那一遍末尾要有提醒");
            assertFalse(second.contains(answer.isBlank() ? "\u0000" : "回答「" + answer), "不带上一次的回答（不带历史）");
            assertEquals(2, JsonParser.parseString(server.received().get(1).body()).getAsJsonObject()
                    .getAsJsonArray("messages").size(), "再问那一遍也只有一条系统、一条用户");
        }
    }

    @Test
    @DisplayName("认不出 → 再问一遍答对了 → 选中，经过 unparsed→ok")
    void reaskCanSucceed() throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, body) -> Reply.answer(n == 1 ? "我选三" : "3"));
             LlmService service = cfg(server).start()) {
            ChoiceOutcome o = await(service.choose(request(5, 10_000)));
            assertEquals(2, o.choice().orElseThrow(), o.toString());
            assertEquals("unparsed→ok", o.trailText());
        }
    }

    @ParameterizedTest(name = "「{0}」")
    @ValueSource(strings = {"7", "0", "-1", "99999999999"})
    @DisplayName("是编号但不在 1–5 里 → 再问一遍，还越界 → OUT_OF_RANGE")
    void outOfRange(String answer) throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, body) -> Reply.answer(answer));
             LlmService service = cfg(server).start()) {
            ChoiceOutcome o = await(service.choose(request(5, 10_000)));
            assertEquals(Fallback.OUT_OF_RANGE, o.fallback(), o.toString());
            assertEquals("range→range", o.trailText());
            assertTrue(o.choice().isEmpty());
        }
    }

    // ---- 2xx 但用不了：重试 ----

    @Test
    @DisplayName("空回答 → 重试，下一次答对了 → 选中（经过 empty→ok）")
    void emptyContentThenAnswer() throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, body) -> n == 1 ? Reply.chat("", "stop", 900, 0) : Reply.answer("2"));
             LlmService service = cfg(server).start()) {
            ChoiceOutcome o = await(service.choose(request(3, 10_000)));
            assertEquals(1, o.choice().orElseThrow(), o.toString());
            assertEquals("empty→ok", o.trailText());
        }
    }

    @Test
    @DisplayName("一直空回答 → 试满次数，UNPARSEABLE「空回答」")
    void emptyContentEveryTime() throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, body) -> Reply.chat("", "length", 900, 64));
             LlmService service = cfg(server).attempts(3).start()) {
            ChoiceOutcome o = await(service.choose(request(3, 10_000)));
            assertEquals(Fallback.UNPARSEABLE, o.fallback(), o.toString());
            assertEquals("empty→empty→empty", o.trailText());
            assertTrue(o.detail().contains("finish_reason=length"), o.detail());
        }
    }

    @Test
    @DisplayName("200 里装着一条错误 → 重试，下一次正常 → 选中（经过 err200→ok）")
    void errorBodyWith200ThenAnswer() throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, body) -> n == 1
                ? Reply.raw(200, "{\"error\":{\"message\":\"upstream overloaded\"}}") : Reply.answer("1"));
             LlmService service = cfg(server).start()) {
            ChoiceOutcome o = await(service.choose(request(3, 10_000)));
            assertEquals(0, o.choice().orElseThrow(), o.toString());
            assertEquals("err200→ok", o.trailText());
        }
    }

    @Test
    @DisplayName("回包前面先吐了好几行空行（高峰时占着连接的写法）→ 照常认")
    void leadingBlankLinesParse() throws Exception {
        String json = Reply.answer("2").body();
        try (FakeChatServer server = new FakeChatServer((n, body) -> Reply.raw(200, "\n\n\r\n \n\n" + json));
             LlmService service = cfg(server).start()) {
            ChoiceOutcome o = await(service.choose(request(3, 10_000)));
            assertEquals(1, o.choice().orElseThrow(), o.toString());
            assertEquals("ok", o.trailText());
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"<html>502 bad gateway</html>", "{\"error\":{\"message\":\"model not found\"}}",
            "{\"choices\":[]}", "[1,2]", "{\"choices\":[{\"text\":\"1\"}]}"})
    @DisplayName("2xx 但回包一直认不出 → 重试过后 BAD_RESPONSE")
    void badResponse(String body) throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, b) -> Reply.raw(200, body));
             LlmService service = cfg(server).attempts(2).start()) {
            ChoiceOutcome o = await(service.choose(request(3, 10_000)));
            assertEquals(Fallback.BAD_RESPONSE, o.fallback(), o.toString());
            assertEquals(2, o.attempts());
            assertTrue(o.trailText().equals("bad→bad") || o.trailText().equals("err200→err200"), o.trailText());
        }
    }

    // ---- 429 · 5xx：重试 ----

    @Test
    @DisplayName("先 429 再 200 → 选中，经过 429→ok")
    void retriesAfter429() throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, body) -> n == 1
                ? Reply.raw(429, "{\"error\":{\"message\":\"rate limited\"}}") : Reply.answer("2"));
             LlmService service = cfg(server).start()) {
            ChoiceOutcome o = await(service.choose(request(3, 10_000)));
            assertEquals(1, o.choice().orElseThrow(), o.toString());
            assertEquals("429→ok", o.trailText());
            assertEquals(2, server.requests());
        }
    }

    @Test
    @DisplayName("503 · 503 · 200 → 选中，尝试 3 次，经过 503→503→ok")
    void twoOverloadsThenOk() throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, body) -> n <= 2
                ? Reply.raw(503, "{\"error\":{\"message\":\"Server Overloaded\"}}") : Reply.answer("1"));
             LlmService service = cfg(server).attempts(3).start()) {
            ChoiceOutcome o = await(service.choose(request(3, 10_000)));
            assertEquals(0, o.choice().orElseThrow(), o.toString());
            assertEquals(3, o.attempts());
            assertEquals("503→503→ok", o.trailText());
        }
    }

    @Test
    @DisplayName("429 带 Retry-After: 1 → 等满 1 秒再试（不管 backoffMaxMs）")
    void honoursRetryAfter() throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, body) -> n == 1
                ? Reply.raw(429, "{}").header("Retry-After", "1") : Reply.answer("1"));
             LlmService service = cfg(server).start()) {
            long t0 = System.nanoTime();
            ChoiceOutcome o = await(service.choose(request(3, 10_000)));
            assertTrue(o.chosen(), o.toString());
            assertTrue(msSince(t0) >= 1_000, "Retry-After 没被照着等");
        }
    }

    @Test
    @DisplayName("一直 500、次数够多 → 来不及再试时收成 HTTP_ERROR（不硬凑成超时），不晚于截止")
    void serverErrorUntilDeadlineIsHttpError() throws Exception {
        long deadlineMs = 2_500;
        try (FakeChatServer server = new FakeChatServer((n, body) -> Reply.raw(500, "{\"error\":\"boom\"}"));
             LlmService service = cfg(server).attempts(10).start()) {
            long t0 = System.nanoTime();
            ChoiceOutcome o = await(service.choose(request(3, deadlineMs)));
            assertEquals(Fallback.HTTP_ERROR, o.fallback(), o.toString());
            assertTrue(o.detail().contains("来不及再试"), o.detail());
            assertTrue(o.attempts() >= 3, "该重试过好几次：" + o.trailText());
            assertEquals(o.attempts(), server.requests());
            assertTrue(msSince(t0) < deadlineMs);
        }
    }

    @Test
    @DisplayName("一直 500、最多 3 次 → HTTP_ERROR「已试 3 次」")
    void serverErrorExhaustsAttempts() throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, body) -> Reply.raw(500, "{}"));
             LlmService service = cfg(server).attempts(3).start()) {
            ChoiceOutcome o = await(service.choose(request(3, 20_000)));
            assertEquals(Fallback.HTTP_ERROR, o.fallback(), o.toString());
            assertEquals("500→500→500", o.trailText());
            assertTrue(o.detail().contains("已试 3 次"), o.detail());
        }
    }

    // ---- 时间预算 ----

    @Test
    @DisplayName("❗第一次吊住（单次超时）、第二次很快 → 在截止之前选中：预算切给了第二次（经过 timeout→ok）")
    void hangThenFastSuccessWithinDeadline() throws Exception {
        long deadlineMs = 4_000;
        try (FakeChatServer server = new FakeChatServer((n, body) -> n == 1 ? Reply.answer("1").after(10_000) : Reply.answer("2"));
             LlmService service = cfg(server).floor(300).attempts(3).start()) {
            long t0 = System.nanoTime();
            ChoiceOutcome o = await(service.choose(request(3, deadlineMs)));
            long took = msSince(t0);
            assertTrue(o.chosen(), "第一次吊住之后该还有时间再试一次：" + o);
            assertEquals(1, o.index());
            assertEquals("timeout→ok", o.trailText());
            assertTrue(took < deadlineMs, "要在截止之前：" + took + " ms");
        }
    }

    @Test
    @DisplayName("❗服务端一直睡过截止 → TIMEOUT，在「截止 − 余量」收场、不晚于截止；收场后没有挂着的请求，关掉后线程都退出")
    void slowServerTimesOutBeforeTheDeadline() throws Exception {
        long deadlineMs = 1_500;
        int marginMs = 300;
        // 单次上限 5 秒（远大于截止）：截止那两道都拆掉时，结果在第 5 秒才回来 —— 红在下面「赶在截止之前」那一行，而不是等结果的 30 秒
        try (FakeChatServer server = new FakeChatServer((n, body) -> Reply.answer("1").after(10_000))) {
            LlmService service = cfg(server).timeout(5_000).margin(marginMs).start();
            long t0 = System.nanoTime();
            try {
                ChoiceOutcome o = await(service.choose(request(3, deadlineMs)));
                long took = msSince(t0);
                assertEquals(Fallback.TIMEOUT, o.fallback(), o.toString());
                assertTrue(took < deadlineMs, "结果要赶在截止（" + deadlineMs + " ms）之前回来，实际 " + took + " ms");
                // 不查「不早于截止 − 余量」：剩下的不到地板时不再发，提前收场是对的（预算 1200 ms：660 + 退避 + 255，剩约 200 ms < 地板）
                assertTrue(o.attempts() >= 2, "单次超时之后该再试过：" + o.trailText());
                assertTrue(o.trail().stream().allMatch(t -> t.equals("timeout") || t.equals("cut")), o.trailText());
                assertEquals(0, service.pending(), "收场之后还有没收场的决定");
                assertEquals(0, service.inFlight(), "收场之后名额没还");
            } finally {
                service.close();
            }
            assertTrue(service.awaitTermination(Duration.ofSeconds(5)), "接入层的线程与 HttpClient 没在 5 秒内退出");
            assertEquals(List.of(), liveThreads("heavyseas-llm-"), "关掉之后还有接入层的线程活着");
        }
    }

    @Test
    @DisplayName("截止还远时，单次照样受 attemptTimeoutMs 管：只许一次、800 ms 一到就收成 TIMEOUT")
    void attemptTimeoutCapsEachAttempt() throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, body) -> Reply.answer("1").after(10_000));
             LlmService service = cfg(server).timeout(800).attempts(1).start()) {
            long t0 = System.nanoTime();
            ChoiceOutcome o = await(service.choose(request(3, 5_000)));
            long took = msSince(t0);
            assertEquals(Fallback.TIMEOUT, o.fallback(), o.toString());
            assertTrue(o.detail().contains("单次 800 ms"), o.detail());
            assertTrue(took >= 700 && took < 2_000, "单次上限 800 ms，实际 " + took + " ms");
        }
    }

    @Test
    @DisplayName("预算切分的公式：不是最后一次只给 0.55·R；最后一次或那一份不到地板，给全部；都不超过单次上限")
    void attemptTimeoutFormula() throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, body) -> Reply.answer("1"))) {
            LlmConfig c = cfg(server).timeout(15_000).attempts(3).floor(800).build();
            assertEquals(3_850, LlmService.attemptTimeoutMs(c, 1, 7_000));
            assertEquals(3_000, LlmService.attemptTimeoutMs(c, 3, 3_000), "最后一次：给全部");
            assertEquals(1_400, LlmService.attemptTimeoutMs(c, 1, 1_400), "0.55·R 不到地板：给全部");
            assertEquals(15_000, LlmService.attemptTimeoutMs(c, 1, 40_000), "不超过单次上限");
            Cfg whole = cfg(server).timeout(15_000).attempts(3).floor(800);
            whole.attemptShare = 1.0;
            assertEquals(7_000, LlmService.attemptTimeoutMs(whole.build(), 1, 7_000), "attemptShare 1.0：不切分");
            Cfg b = cfg(server);
            b.backoffBaseMs = 300;
            b.backoffMaxMs = 3_000;
            LlmConfig bc = b.build();
            for (int i = 0; i < 50; i++) {
                long k1 = LlmService.backoffMs(bc, 1);
                long k2 = LlmService.backoffMs(bc, 2);
                long k5 = LlmService.backoffMs(bc, 5);
                assertTrue(k1 >= 300 && k1 <= 450, "k=1：" + k1);
                assertTrue(k2 >= 600 && k2 <= 750, "k=2：" + k2);
                assertTrue(k5 >= 3_000 && k5 <= 3_150, "k=5 封顶：" + k5);
            }
        }
    }

    // ---- 不重试的几种 ----

    @Test
    @DisplayName("❗401 → AUTH，不重试，这一次决定只记一行；之后的决定不再发请求（直到 reload），那一行只写一句短的")
    void unauthorizedIsNotRetriedAndLatched() throws Exception {
        try (LogCapture logs = new LogCapture();
             FakeChatServer server = new FakeChatServer((n, body) -> Reply.raw(401, "{\"error\":{\"message\":\"Invalid API key\"}}"));
             LlmService service = cfg(server).attempts(5).start()) {
            ChoiceOutcome first = await(service.choose(request(3, 10_000)));
            assertEquals(Fallback.AUTH, first.fallback(), first.toString());
            assertEquals("401", first.trailText());
            assertEquals(1, server.requests(), "401 不该重试");
            List<String> lines = logs.containing("大模型");
            assertEquals(1, lines.size(), "一次 401 的决定只该有一行日志：\n" + String.join("\n", lines));
            assertTrue(lines.getFirst().contains("Invalid API key"), "第一次要写全原因：" + lines.getFirst());

            ChoiceOutcome second = await(service.choose(request(3, 10_000)));
            assertEquals(Fallback.AUTH, second.fallback(), second.toString());
            assertEquals(0, second.attempts());
            assertEquals(1, server.requests(), "密钥被拒之后还在发请求");
            List<String> after = logs.containing("大模型");
            assertEquals(2, after.size());
            assertFalse(after.get(1).contains("Invalid API key"), "第二次只写一句短的：" + after.get(1));
            assertTrue(service.health().startsWith("AUTH"), service.health());
        }
    }

    @Test
    @DisplayName("402 → BALANCE，不重试，记住")
    void paymentRequiredIsLatched() throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, body) -> Reply.raw(402, "{\"error\":\"Insufficient Balance\"}"));
             LlmService service = cfg(server).start()) {
            assertEquals(Fallback.BALANCE, await(service.choose(request(3, 10_000))).fallback());
            assertEquals(Fallback.BALANCE, await(service.choose(request(3, 10_000))).fallback());
            assertEquals(1, server.requests());
        }
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {400, 404, 422})
    @DisplayName("400 · 404 · 422 → BAD_REQUEST，不重试；不记住（下一次决定照常发）；详细原因只写第一次")
    void badRequestIsNotRetried(int status) throws Exception {
        try (LogCapture logs = new LogCapture();
             FakeChatServer server = new FakeChatServer((n, body) -> Reply.raw(status, "{\"error\":\"unsupported parameter xyz\"}"));
             LlmService service = cfg(server).attempts(5).start()) {
            ChoiceOutcome a = await(service.choose(request(3, 10_000)));
            ChoiceOutcome b = await(service.choose(request(3, 10_000)));
            assertEquals(Fallback.BAD_REQUEST, a.fallback(), a.toString());
            assertEquals(Fallback.BAD_REQUEST, b.fallback(), b.toString());
            assertEquals(String.valueOf(status), a.trailText());
            assertEquals(2, server.requests(), "每次决定各发一次、都不重试");
            List<String> lines = logs.containing("大模型决定");
            assertEquals(2, lines.size());
            assertTrue(lines.get(0).contains("unsupported parameter"), lines.get(0));
            assertFalse(lines.get(1).contains("unsupported parameter"), "同一种错的详细原因只写第一次：" + lines.get(1));
        }
    }

    // ---- 熔断 ----

    @Test
    @DisplayName("❗熔断：连续 2 次 5xx → 熔断（冷却内直接 CIRCUIT_OPEN、不发请求）→ 冷却过后放行、通了就恢复；开合各记一行")
    void circuitBreakerOpensAndRecovers() throws Exception {
        try (LogCapture logs = new LogCapture();
             FakeChatServer server = new FakeChatServer((n, body) -> n <= 2 ? Reply.raw(503, "{}") : Reply.answer("1"));
             LlmService service = cfg(server).attempts(1).breaker(2, 1_000).start()) {
            assertEquals(Fallback.HTTP_ERROR, await(service.choose(request(3, 10_000))).fallback());
            assertEquals(Fallback.HTTP_ERROR, await(service.choose(request(3, 10_000))).fallback());
            ChoiceOutcome blocked = await(service.choose(request(3, 10_000)));
            assertEquals(Fallback.CIRCUIT_OPEN, blocked.fallback(), blocked.toString());
            assertEquals(0, blocked.attempts());
            assertEquals(2, server.requests(), "熔断着还在发请求");
            assertTrue(service.health().startsWith("熔断中"), service.health());
            assertEquals(1, logs.containing("大模型熔断：").size(), "熔断时记一行");

            Thread.sleep(1_100);
            ChoiceOutcome recovered = await(service.choose(request(3, 10_000)));
            assertTrue(recovered.chosen(), "冷却过后该放行：" + recovered);
            assertTrue(await(service.choose(request(3, 10_000))).chosen());
            assertEquals(1, logs.containing("大模型熔断解除").size(), "恢复时记一行");
            assertEquals(4, server.requests());
        }
    }

    @Test
    @DisplayName("熔断：冷却过后放行的那一次又失败 → 立刻重新熔断")
    void circuitBreakerReopensOnFailureAfterCooldown() throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, body) -> Reply.raw(503, "{}"));
             LlmService service = cfg(server).attempts(1).breaker(2, 1_000).start()) {
            await(service.choose(request(3, 10_000)));
            await(service.choose(request(3, 10_000)));
            assertEquals(Fallback.CIRCUIT_OPEN, await(service.choose(request(3, 10_000))).fallback());
            Thread.sleep(1_100);
            assertEquals(Fallback.HTTP_ERROR, await(service.choose(request(3, 10_000))).fallback(), "冷却过后该放行一次");
            assertEquals(Fallback.CIRCUIT_OPEN, await(service.choose(request(3, 10_000))).fallback(), "放行的那一次又失败：该立刻重新熔断");
            assertEquals(3, server.requests());
        }
    }

    // ---- 关着、排队、并发 ----

    @Test
    @DisplayName("❗配置关着 → DISABLED，假服务端一个请求都没收到（文件里写了地址也一样）")
    void disabledConfigSendsNothing() throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, body) -> Reply.answer("1"))) {
            LlmConfig.Loaded loaded = LlmConfigFile.parse(
                    "{\"enabled\": false, \"baseUrl\": \"" + server.baseUrl() + "\", \"model\": \"m\"}", k -> null);
            assertFalse(loaded.broken(), loaded.problem());
            List<ChoiceOutcome> outcomes = new ArrayList<>();
            try (LlmService fromFile = LlmService.start(loaded.config());
                 LlmService off = LlmService.start(LlmConfig.off());
                 LlmService named = LlmService.disabled("测试：关着")) {
                for (LlmService s : List.of(fromFile, off, named)) {
                    outcomes.add(await(s.choose(request(3, 10_000))));
                }
            }
            Thread.sleep(300);
            assertEquals(0, server.requests(), "关着却发了请求");
            for (ChoiceOutcome o : outcomes) {
                assertEquals(Fallback.DISABLED, o.fallback(), o.toString());
                assertEquals(0, o.attempts());
            }
        }
    }

    @Test
    @DisplayName("❗并发上限 2：6 个决定一起来，假服务端同时最多看到 2 个（也确实看到了 2 个），6 个都选中")
    void concurrencyCapHonoured() throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, body) -> Reply.answer("1").after(400));
             LlmService service = cfg(server).concurrency(2, 32).start()) {
            List<CompletableFuture<ChoiceOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                futures.add(service.choose(request(3, 15_000)));
            }
            for (CompletableFuture<ChoiceOutcome> f : futures) {
                assertTrue(await(f).chosen());
            }
            assertEquals(6, server.requests());
            assertEquals(2, server.maxInFlight(), "同时在处理的请求数（假服务端自己数的）");
            assertEquals(0, service.inFlight());
            assertEquals(0, service.queued());
        }
    }

    @Test
    @DisplayName("❗排队时就到了截止 → TIMEOUT 按时收场，而且永远不会被发出去")
    void queuedPastDeadlineIsNeverSent() throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, body) -> Reply.answer("1").after(1_500));
             LlmService service = cfg(server).concurrency(1, 32).attempts(1).start()) {
            CompletableFuture<ChoiceOutcome> first = service.choose(request(3, 10_000));
            long t0 = System.nanoTime();
            CompletableFuture<ChoiceOutcome> second = service.choose(request(3, 700));
            ChoiceOutcome late = await(second);
            long took = msSince(t0);
            assertEquals(Fallback.TIMEOUT, late.fallback(), late.toString());
            assertTrue(took < 700 + 250, "排队的那一个要在自己的截止时收场，实际 " + took + " ms（名额 1.5 秒后才空）");
            assertEquals(0, late.attempts());
            assertTrue(late.detail().contains("排队"), late.detail());
            assertTrue(await(first).chosen());
            Thread.sleep(300);
            assertEquals(1, server.requests(), "排队时已经超时的那一个，名额空出来后被发出去了");
        }
    }

    @Test
    @DisplayName("队满（maxQueued 0）→ 当场 QUEUE_FULL，不发")
    void queueFull() throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, body) -> Reply.answer("1").after(800));
             LlmService service = cfg(server).concurrency(1, 0).start()) {
            CompletableFuture<ChoiceOutcome> first = service.choose(request(3, 10_000));
            ChoiceOutcome rejected = await(service.choose(request(3, 10_000)));
            assertEquals(Fallback.QUEUE_FULL, rejected.fallback(), rejected.toString());
            assertTrue(await(first).chosen());
            assertEquals(1, server.requests());
        }
    }

    @Test
    @DisplayName("截止时间已过、或让出余量后不到地板 → 当场 TIMEOUT，不发")
    void deadlineAlreadyGone() throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, body) -> Reply.answer("1"));
             LlmService service = cfg(server).margin(1_000).floor(250).start()) {
            for (long deadline : new long[]{-100, 500, 1_100}) {
                ChoiceOutcome o = await(service.choose(request(3, deadline)));
                assertEquals(Fallback.TIMEOUT, o.fallback(), "截止 " + deadline + " ms：" + o);
                assertEquals(0, o.attempts());
                assertTrue(o.latencyMs() < 100, o.toString());
            }
            assertEquals(0, server.requests());
        }
    }

    @Test
    @DisplayName("请求本身不合规矩（null · 没有选项 · 选项里有 null 或换行 · 缺座位 / 种类 / 截止）→ INVALID_REQUEST，不发、不抛")
    void invalidRequests() throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, body) -> Reply.answer("1"));
             LlmService service = cfg(server).start()) {
            Instant deadline = Instant.now().plusSeconds(10);
            List<ChoiceRequest> bad = Arrays.asList(
                    null,
                    new ChoiceRequest("座", DecisionKind.ACTION, List.of(), "", deadline),
                    new ChoiceRequest("座", DecisionKind.ACTION, Arrays.asList("甲", null), "", deadline),
                    new ChoiceRequest("座", DecisionKind.ACTION, List.of("甲\n乙"), "", deadline),
                    new ChoiceRequest(" ", DecisionKind.ACTION, List.of("甲"), "", deadline),
                    new ChoiceRequest("座", null, List.of("甲"), "", deadline),
                    new ChoiceRequest("座", DecisionKind.ACTION, List.of("甲"), "", null));
            for (ChoiceRequest r : bad) {
                ChoiceOutcome o = await(service.choose(r));
                assertEquals(Fallback.INVALID_REQUEST, o.fallback(), String.valueOf(r));
            }
            assertEquals(0, server.requests());
        }
    }

    // ---- 网络 ----

    @Test
    @DisplayName("连不上（端口没人听）→ 重试过后 NETWORK_ERROR，经过 conn→conn")
    void connectionRefused() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        try (LlmService service = new Cfg("http://127.0.0.1:" + port + "/v1").attempts(2).start()) {
            ChoiceOutcome o = await(service.choose(request(3, 20_000)));
            assertEquals(Fallback.NETWORK_ERROR, o.fallback(), o.toString());
            assertEquals("conn→conn", o.trailText());
            assertTrue(o.detail().contains("已试 2 次"), o.detail());
        }
    }

    @Test
    @DisplayName("连上了但没回话就断 → 重试，下一次正常 → 选中（经过 conn→ok）")
    void droppedConnectionThenOk() throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, body) -> n == 1 ? Reply.drop() : Reply.answer("3"));
             LlmService service = cfg(server).start()) {
            ChoiceOutcome o = await(service.choose(request(3, 10_000)));
            assertEquals(2, o.choice().orElseThrow(), o.toString());
            assertEquals("conn→ok", o.trailText());
        }
    }

    @Test
    @DisplayName("推理模型：reasoning_content 不看、<think> 去掉；没写完的思考里的数字不算")
    void reasoningModels() throws Exception {
        Map<Integer, Reply> script = Map.of(
                1, Reply.raw(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"2\","
                        + "\"reasoning_content\":\"我想选 4\"},\"finish_reason\":\"stop\"}]}"),
                2, Reply.answer("<think>选 4 吧……不，还是</think>\n2"),
                3, Reply.answer("<think>没想完就被截断了，3"));
        try (FakeChatServer server = new FakeChatServer((n, body) -> script.get(n));
             LlmService service = cfg(server).concurrency(1, 32).attempts(1).start()) {
            ChoiceOutcome a = await(service.choose(request(5, 10_000)));
            ChoiceOutcome b = await(service.choose(request(5, 10_000)));
            ChoiceOutcome c = await(service.choose(request(5, 10_000)));
            assertEquals(1, a.index(), a.toString());
            assertEquals(1, b.index(), b.toString());
            assertEquals(Fallback.UNPARSEABLE, c.fallback(), "没写完的思考里那个 3 不能当成回答：" + c);
            assertEquals("empty", c.trailText());
        }
    }

    // ---- 日志与密钥 ----

    @Test
    @DisplayName("❗密钥不进日志：调试日志开着、服务端在错误回包里回显密钥 —— 日志与返回的说明里都只有 ***")
    void apiKeyNeverLogged() throws Exception {
        try (LogCapture logs = new LogCapture();
             FakeChatServer server = new FakeChatServer((n, body) -> n == 1
                     ? Reply.answer("1")
                     : Reply.raw(401, "{\"error\":{\"message\":\"Incorrect API key provided: " + KEY + "\"}}"));
             LlmService service = cfg(server).debug().start()) {
            ChoiceOutcome ok = await(service.choose(request(3, 10_000)));
            ChoiceOutcome rejected = await(service.choose(request(3, 10_000)));
            assertTrue(ok.chosen(), ok.toString());
            assertEquals(Fallback.AUTH, rejected.fallback(), rejected.toString());
            assertEquals("Bearer " + KEY, server.received().getFirst().authorization(), "密钥该照常进请求头");

            // 正向对照：日志确实收到了，回显密钥的那一份回包也确实进了日志 —— 否则「没有密钥」与「没在看」一样。
            // ❗这几条只认与去不去密钥无关的字：去密钥的那一行坏了，要红在下面「日志里出现了密钥」，不能先红在对照上。
            assertEquals(2, logs.containing("大模型决定 #").size(), String.join("\n", logs.lines()));
            assertFalse(logs.containing("大模型调试").isEmpty(), "调试日志开着却一行请求体都没收到");
            assertFalse(logs.containing("Incorrect API key provided: ").isEmpty(), "回显密钥的回包没进日志，这一条没在测");
            for (String line : logs.lines()) {
                assertFalse(line.contains(KEY), "日志里出现了密钥：" + line);
            }
            assertFalse(rejected.detail().contains(KEY), "返回给调用方的说明里有密钥：" + rejected.detail());
            assertFalse(logs.containing("Incorrect API key provided: ***").isEmpty(), "回显的密钥该换成 ***");
            assertFalse(service.config().toString().contains(KEY), "设置的 toString 带了密钥");
        }
    }

    @Test
    @DisplayName("每次决定恰好一行 INFO，各条路一眼认得出（路=模型 / 路=退路:原因 · 经过=…）")
    void exactlyOneLinePerDecision() throws Exception {
        try (LogCapture logs = new LogCapture();
             FakeChatServer server = new FakeChatServer((n, body) -> switch (n) {
                 case 1 -> Reply.answer("2");
                 case 2 -> Reply.answer("哪个都行");
                 default -> Reply.answer("9");
             });
             LlmService service = cfg(server).concurrency(1, 32).attempts(1).start();
             LlmService off = LlmService.disabled("测试：关着")) {
            await(service.choose(request(3, 10_000)));
            await(service.choose(request(3, 10_000)));
            await(service.choose(request(3, 10_000)));
            await(off.choose(request(3, 10_000)));
            await(service.choose(new ChoiceRequest("座", DecisionKind.ACTION, List.of(), "", Instant.now().plusSeconds(5))));
            await(service.choose(request(3, -1)));

            List<String> lines = logs.containing("大模型决定 #");
            assertEquals(6, lines.size(), String.join("\n", lines));
            for (String path : List.of("路=模型 选=2「选项2」 经过=ok", "路=退路:UNPARSEABLE 经过=unparsed",
                    "路=退路:OUT_OF_RANGE 经过=range", "路=退路:DISABLED 经过=-", "路=退路:INVALID_REQUEST 经过=-",
                    "路=退路:TIMEOUT 经过=-")) {
                assertEquals(1, lines.stream().filter(l -> l.contains(path)).count(), path + " 那一行：\n" + String.join("\n", lines));
            }
            lines.forEach(l -> assertTrue(l.startsWith("INFO "), l));
            assertEquals(6, lines.stream().map(l -> l.replaceAll(".*大模型决定 (#\\d+).*", "$1")).distinct().count(), "编号要各不相同");
        }
    }

    @Test
    @DisplayName("关掉服务：还没收场的收成 SHUTDOWN；关掉之后再问也是 SHUTDOWN")
    void closeSettlesPendingDecisions() throws Exception {
        try (FakeChatServer server = new FakeChatServer((n, body) -> Reply.answer("1").after(5_000))) {
            LlmService service = cfg(server).start();
            CompletableFuture<ChoiceOutcome> pending = service.choose(request(3, 20_000));
            Thread.sleep(200);
            service.close();
            ChoiceOutcome o = pending.get(2, TimeUnit.SECONDS);
            assertEquals(Fallback.SHUTDOWN, o.fallback(), o.toString());
            assertEquals(Fallback.SHUTDOWN, await(service.choose(request(3, 10_000))).fallback());
            assertTrue(service.awaitTermination(Duration.ofSeconds(5)));
        }
    }
}
