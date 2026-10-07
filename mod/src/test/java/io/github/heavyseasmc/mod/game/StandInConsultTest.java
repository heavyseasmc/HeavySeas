package io.github.heavyseasmc.mod.game;

import com.sun.net.httpserver.HttpServer;
import io.github.heavyseasmc.mod.llm.ChoiceOutcome;
import io.github.heavyseasmc.mod.llm.ChoiceRequest;
import io.github.heavyseasmc.mod.llm.DecisionKind;
import io.github.heavyseasmc.mod.llm.LlmConfig;
import io.github.heavyseasmc.mod.llm.LlmService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 大模型替身的「想」（{@link StandInMinds#consult}）：模型挑中了就照它；任何一种没挑成 —— 每一种退路、问题拼不出、
 * 接入层出错、编号越界 —— 都用动脑那一层先算好的答案，而且「来源」那一栏写明是哪一种。
 *
 * <p>最后一条走真的接入层（{@link LlmService}）对一个本机假服务端：一直回 503（DeepSeek 那种忙不过来），重试完照样退回动脑。
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class StandInConsultTest {

    private static final Instant DEADLINE = Instant.now().plusSeconds(30);

    private static SeatQuestions.Question<String> question() {
        return new SeatQuestions.Question<>(DecisionKind.ACTION, List.of("pass", "row", "steal"),
                List.of("什么也不做", "划船", "抢大副"), "局面：测试用");
    }

    private static ChoiceOutcome chosen(int index) {
        return new ChoiceOutcome(index, null, "", String.valueOf(index + 1), 5, ChoiceOutcome.Usage.UNKNOWN,
                List.of("ok"));
    }

    private static ChoiceOutcome fallback(ChoiceOutcome.Fallback why) {
        return new ChoiceOutcome(-1, why, "测试", null, 5, ChoiceOutcome.Usage.UNKNOWN, List.of("503", "503"));
    }

    private static StandInThinker.Thought<String> consult(Function<ChoiceRequest, CompletableFuture<ChoiceOutcome>> llm)
            throws Exception {
        return StandInMinds.consult("pass", StandInConsultTest::question, llm, "船长", DEADLINE, "测试")
                .get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("模型挑中了：照它挑的那一项，来源写 llm、经过原样带回")
    void modelPickIsUsed() throws Exception {
        AtomicReference<ChoiceRequest> asked = new AtomicReference<>();
        StandInThinker.Thought<String> t = consult(req -> {
            asked.set(req);
            return CompletableFuture.completedFuture(chosen(2));
        });
        assertEquals("steal", t.choice());
        assertEquals("llm", t.source());
        assertEquals("ok", t.trail());
        assertEquals(List.of("什么也不做", "划船", "抢大副"), asked.get().options(), "问出去的不是这一题");
        assertEquals("船长", asked.get().seat());
    }

    @Test
    @DisplayName("每一种退路都用动脑的答案，来源写明是哪一种")
    void everyFallbackUsesSmart() throws Exception {
        for (ChoiceOutcome.Fallback why : ChoiceOutcome.Fallback.values()) {
            StandInThinker.Thought<String> t = consult(req -> CompletableFuture.completedFuture(fallback(why)));
            assertEquals("pass", t.choice(), why.name());
            assertEquals("smart-fallback:" + why, t.source());
            assertEquals("503→503", t.trail(), why.name());
        }
    }

    @Test
    @DisplayName("问题拼不出来 · 接入层的 future 出了错 · 编号越界：都用动脑的答案")
    void brokenPathsUseSmart() throws Exception {
        StandInThinker.Thought<String> unbuildable = StandInMinds.<String>consult("pass", () -> {
            throw new IllegalStateException("拼不出来");
        }, req -> CompletableFuture.completedFuture(chosen(1)), "船长", DEADLINE, "测试").get(10, TimeUnit.SECONDS);
        assertEquals("pass", unbuildable.choice());
        assertEquals("smart-fallback:问不出去", unbuildable.source());
        assertNull(unbuildable.trail());

        StandInThinker.Thought<String> failed = consult(req -> CompletableFuture.failedFuture(new RuntimeException("炸了")));
        assertEquals("pass", failed.choice());
        assertEquals("smart-fallback:接入层出错", failed.source());

        StandInThinker.Thought<String> thrown = consult(req -> {
            throw new IllegalArgumentException("请求不合规矩");
        });
        assertEquals("smart-fallback:问不出去", thrown.source());

        StandInThinker.Thought<String> outOfRange = consult(req -> CompletableFuture.completedFuture(chosen(7)));
        assertEquals("pass", outOfRange.choice());
        assertEquals("smart-fallback:编号越界", outOfRange.source());
    }

    @Test
    @DisplayName("只有一项可选：不问模型，来源写 only；两项时照问（对照）")
    void singleOptionIsNotAsked() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        Function<ChoiceRequest, CompletableFuture<ChoiceOutcome>> llm = req -> {
            asked.incrementAndGet();
            return CompletableFuture.completedFuture(chosen(0));
        };
        StandInThinker.Thought<String> one = StandInMinds.consult("water",
                () -> new SeatQuestions.Question<>(DecisionKind.PROVISION, List.of("water"), List.of("水"), "局面"),
                llm, "船长", DEADLINE, "测试").get(10, TimeUnit.SECONDS);
        assertEquals("water", one.choice());
        assertEquals("only:只有一项", one.source());
        assertEquals(0, asked.get(), "只有一项还发了请求");
        StandInThinker.Thought<String> two = StandInMinds.consult("water",
                () -> new SeatQuestions.Question<>(DecisionKind.PROVISION, List.of("water", "oar"), List.of("水", "船桨"),
                        "局面"), llm, "船长", DEADLINE, "测试").get(10, TimeUnit.SECONDS);
        assertEquals("llm", two.source());
        assertEquals(1, asked.get(), "两项时没去问：上面那条「不问」证明不了什么");
    }

    @Test
    @DisplayName("真的接入层：服务端一直 503，重试完退回动脑的答案（经过 503→503，确实发了两次）")
    void realServiceThatKeepsFailingFallsBackToSmart() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"error\":{\"message\":\"busy\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        LlmConfig config = new LlmConfig(true, baseUrl, "test-only", "fake-model", 64, LlmConfig.TEMPERATURE_UNSET, "",
                5_000, LlmConfig.ATTEMPT_SHARE_DEFAULT, 2, 200, 0, 0, 2, 4, 100, 0, 1_000, "zh_cn", false);
        try (LlmService service = LlmService.start(config)) {
            assertTrue(service.enabled(), "假服务端那一份设置没开起来：下面的退路就什么也证明不了");
            StandInThinker.Thought<String> t = StandInMinds.consult("pass", StandInConsultTest::question,
                    service::choose, "船长", Instant.now().plusSeconds(10), "测试").get(20, TimeUnit.SECONDS);
            assertEquals("pass", t.choice());
            assertEquals("smart-fallback:HTTP_ERROR", t.source());
            assertEquals("503→503", t.trail());
            assertEquals(2, requests.get(), "没有真的重试（或者重试了不止一次）");
        } finally {
            server.stop(0);
        }
    }
}
