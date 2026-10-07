package io.github.heavyseasmc.mod.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * OpenAI 兼容的 {@code /chat/completions} 在线上长什么样：请求体怎么拼、回包怎么读。只认这一种格式
 * （TLM 同一个取舍：DeepSeek、Ollama、LM Studio、OpenRouter、本机代理都说这一种话）。
 *
 * <p>读回包时只要 {@code choices[0].message.content}：推理模型放在 {@code reasoning_content} / {@code reasoning} 里的思考一律不看
 * （思考写进 {@code content} 的 {@code <think>…</think>} 由 {@link ChoiceParser#visible} 去掉）。
 * 回包前面的空行照常认：有的服务端（DeepSeek 文档原话：高峰时非流式请求会「continuously return empty lines」）先吐空行占着连接。
 */
public final class ChatWire {

    private ChatWire() {
    }

    /** 回包里我们要的那几样。{@code content} 可能是空串（推理模型把 max_tokens 全花在思考上时就是这样）。 */
    public record Reply(String content, String finishReason, ChoiceOutcome.Usage usage) {
    }

    /**
     * 2xx 但回包用不了。{@link #token()} 是记进尝试轨迹的记号：{@code err200} = 200 里装着一条错误；{@code bad} = 别的认不出。
     * 两种都值得再试一次（多半是网关或服务端一时的毛病）。
     */
    public static final class BadReply extends IllegalArgumentException {
        private final String token;

        BadReply(String token, String message) {
            super(message);
            this.token = token;
        }

        public String token() {
            return token;
        }
    }

    public static String requestBody(LlmConfig config, ChoicePrompt.Messages messages) {
        JsonObject root = new JsonObject();
        root.addProperty("model", config.model());
        JsonArray list = new JsonArray();
        list.add(message("system", messages.system()));
        list.add(message("user", messages.user()));
        root.add("messages", list);
        root.addProperty("max_tokens", config.maxTokens());
        if (config.sendsTemperature()) {
            root.addProperty("temperature", config.temperature());
        }
        if (config.sendsReasoningEffort()) {
            root.addProperty("reasoning_effort", config.reasoningEffort());
        }
        root.addProperty("stream", false);
        return root.toString();
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    /**
     * @throws BadReply 回包不是认得的样子 —— 消息是一句人话，直接进说明
     */
    public static Reply parseReply(String body) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body == null ? "" : body);
        } catch (RuntimeException e) {
            throw new BadReply("bad", "回包不是 JSON（" + snippet(body) + "）");
        }
        if (!parsed.isJsonObject()) {
            throw new BadReply("bad", "回包最外层不是 JSON 对象（" + snippet(body) + "）");
        }
        JsonObject root = parsed.getAsJsonObject();
        if (root.has("error") && !root.has("choices")) {
            throw new BadReply("err200", "回包里是一条错误：" + snippet(root.get("error").toString()));
        }
        JsonElement choicesElement = root.get("choices");
        if (choicesElement == null || !choicesElement.isJsonArray() || choicesElement.getAsJsonArray().isEmpty()) {
            throw new BadReply("bad", "回包里没有 choices");
        }
        JsonElement first = choicesElement.getAsJsonArray().get(0);
        if (!first.isJsonObject() || !first.getAsJsonObject().has("message")
                || !first.getAsJsonObject().get("message").isJsonObject()) {
            throw new BadReply("bad", "choices[0] 里没有 message");
        }
        JsonObject choice = first.getAsJsonObject();
        JsonObject message = choice.getAsJsonObject("message");
        String content = text(message.get("content"));
        String finish = choice.has("finish_reason") && choice.get("finish_reason").isJsonPrimitive()
                ? choice.get("finish_reason").getAsString() : null;
        return new Reply(content, finish, usage(root.get("usage")));
    }

    /** {@code content} 有三种写法：字符串 · null（也算空回答）· 分段数组（[{type:text, text:…}]，有的服务端这样回）。 */
    private static String text(JsonElement content) {
        if (content == null || content.isJsonNull()) {
            return "";
        }
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        if (content.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement part : content.getAsJsonArray()) {
                if (part.isJsonObject() && part.getAsJsonObject().has("text")
                        && part.getAsJsonObject().get("text").isJsonPrimitive()) {
                    sb.append(part.getAsJsonObject().get("text").getAsString());
                }
            }
            return sb.toString();
        }
        throw new BadReply("bad", "message.content 的样子认不出：" + snippet(content.toString()));
    }

    private static ChoiceOutcome.Usage usage(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return ChoiceOutcome.Usage.UNKNOWN;
        }
        JsonObject u = element.getAsJsonObject();
        int reasoning = -1;
        if (u.has("completion_tokens_details") && u.get("completion_tokens_details").isJsonObject()) {
            reasoning = integer(u.getAsJsonObject("completion_tokens_details").get("reasoning_tokens"));
        }
        return new ChoiceOutcome.Usage(integer(u.get("prompt_tokens")), integer(u.get("completion_tokens")), reasoning);
    }

    private static int integer(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return -1;
        }
        return e.getAsInt();
    }

    /** 回包截一小段进说明：够认出是什么错，又不至于把整页 HTML 塞进日志。 */
    static String snippet(String s) {
        if (s == null) {
            return "（空）";
        }
        String flat = s.replaceAll("\\s+", " ").strip();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }
}
