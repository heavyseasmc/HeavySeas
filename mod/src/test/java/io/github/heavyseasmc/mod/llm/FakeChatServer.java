package io.github.heavyseasmc.mod.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单测用的假 {@code /v1/chat/completions}：只绑回环、端口随机，照剧本回话。
 *
 * <p>它自己数「收到几次」「同时最多几个在处理」—— 判据不靠被测的服务报数（判据要与结论正交）。
 * 处理线程用缓存线程池：默认的单线程执行器会把并发请求排成一列，并发上限那一条就永远测不出东西。
 */
final class FakeChatServer implements AutoCloseable {

    /** 第 n 次请求（从 1 数）该怎么回。 */
    @FunctionalInterface
    interface Script {
        Reply reply(int n, String body);
    }

    record Reply(int status, String body, long delayMs, Map<String, String> headers, boolean hangUp) {

        /** 一条正常的回答：content 是模型说的话。 */
        static Reply answer(String content) {
            return chat(content, "stop", 812, 3);
        }

        static Reply chat(String content, String finishReason, int promptTokens, int completionTokens) {
            JsonObject message = new JsonObject();
            message.addProperty("role", "assistant");
            message.addProperty("content", content);
            JsonObject choice = new JsonObject();
            choice.addProperty("index", 0);
            choice.add("message", message);
            choice.addProperty("finish_reason", finishReason);
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject usage = new JsonObject();
            usage.addProperty("prompt_tokens", promptTokens);
            usage.addProperty("completion_tokens", completionTokens);
            usage.addProperty("total_tokens", promptTokens + completionTokens);
            JsonObject root = new JsonObject();
            root.addProperty("id", "fake");
            root.addProperty("object", "chat.completion");
            root.add("choices", choices);
            root.add("usage", usage);
            return raw(200, root.toString());
        }

        static Reply raw(int status, String body) {
            return new Reply(status, body, 0, Map.of(), false);
        }

        /** 不回任何东西就把连接关掉。 */
        static Reply drop() {
            return new Reply(0, "", 0, Map.of(), true);
        }

        Reply after(long ms) {
            return new Reply(status, body, ms, headers, hangUp);
        }

        Reply header(String name, String value) {
            Map<String, String> h = new LinkedHashMap<>(headers);
            h.put(name, value);
            return new Reply(status, body, delayMs, Map.copyOf(h), hangUp);
        }
    }

    record Received(String body, String authorization) {
    }

    private final HttpServer server;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "fake-chat-server");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
    private volatile Script script;

    FakeChatServer(Script script) throws IOException {
        this.script = script;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.setExecutor(pool);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    int requests() {
        return requests.get();
    }

    int maxInFlight() {
        return maxInFlight.get();
    }

    List<Received> received() {
        synchronized (received) {
            return List.copyOf(received);
        }
    }

    private void handle(HttpExchange exchange) {
        int n = requests.incrementAndGet();
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(body, exchange.getRequestHeaders().getFirst("Authorization")));
            Reply reply = script.reply(n, body);
            if (reply.delayMs() > 0) {
                Thread.sleep(reply.delayMs());
            }
            if (reply.hangUp()) {
                return;                                   // finally 里 close：没回响应头就关，客户端看到的是连接断了
            }
            byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
            reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // 客户端已经走了（超时取消）：照常收尾
        } finally {
            inFlight.decrementAndGet();
            exchange.close();
        }
    }

    @Override
    public void close() throws InterruptedException {
        server.stop(0);
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }
}
