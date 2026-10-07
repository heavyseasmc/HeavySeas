package io.github.heavyseasmc.mod.llm;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 旧的 {@code config/heavyseas/llm.json}：<b>只有这个类碰它的文件格式与 Gson</b>。
 *
 * <p>❗它已经不是 {@link LlmConfig} 的来源了 —— 来源是设置菜单的「大模型」一组（服务端设置，{@code ServerSettings}）。
 * 起服时若还有这个文件，它的值一次性迁进服务端设置（只填还是默认值的那几项），然后文件改名成 {@code llm.json.migrated}
 * （{@code SettingsMigration}）。这里留下的读法只给那一次迁移用（{@link #readDraft}），外加单测。
 *
 * <h2>文件的样子（标准 JSON，不认注释）</h2>
 * 键名就是 {@link LlmConfig.Field#key()}，每一项的缺省值与范围见 {@link LlmConfig} 的字段说明。没写的项取缺省值。
 * <pre>{@code
 * {
 *   "enabled": true,
 *   "baseUrl": "http://127.0.0.1:11434/v1",
 *   "model": "qwen3:8b",
 *   "maxTokens": 64,
 *   "reasoningEffort": "low",
 *   "maxAttempts": 3,
 *   "language": "zh_cn"
 * }
 * }</pre>
 * 密钥最好不写进这个文件：设环境变量 {@value LlmConfig#KEY_ENV}（有值时压过文件里的 {@code apiKey}）。
 *
 * <p>本模组<b>不替人生成这个文件</b>：开不开是服主自己的事，没人写过它就等于没有这回事。
 *
 * <p><b>认不出的键一律算写坏</b>：把 {@code enabled} 拼成 {@code enable}，结果是「一直关着」—— 与「没打算开」长得一样，
 * 所以宁可报错（证伪表：静默退回让「配错了」和「这台机器没有」结果相同）。类型不对（数字写成字符串之类）同样算写坏。
 */
public final class LlmConfigFile {

    private static final Set<String> KEYS = Arrays.stream(LlmConfig.Field.values()).map(LlmConfig.Field::key)
            .collect(Collectors.toUnmodifiableSet());

    private LlmConfigFile() {
    }

    /**
     * 读那个文件。不抛：文件不在、读不了、写坏了都折成 {@link LlmConfig.Loaded}，由调用方决定怎么报。
     *
     * @param env 查环境变量（服务端传 {@code System::getenv}，单测传一张表）
     */
    public static LlmConfig.Loaded load(Path file, Function<String, String> env) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return new LlmConfig.Loaded(LlmConfig.off(), null, "没有 " + file + "，大模型替身关着", "无");
        } catch (IOException | RuntimeException e) {
            return LlmConfig.Loaded.broken("读不了 " + file + "：" + e);
        }
        LlmConfig.Loaded parsed = parse(text, env);
        return parsed.broken()
                ? LlmConfig.Loaded.broken(file + " 写坏了，大模型替身关着：" + parsed.problem())
                : parsed;
    }

    /** 解析文件内容（单测直接调）。 */
    public static LlmConfig.Loaded parse(String text, Function<String, String> env) {
        DraftRead read = draft(text);
        return read.unreadable() != null ? LlmConfig.Loaded.broken(read.unreadable())
                : LlmConfig.from(read.draft(), read.problems(), env);
    }

    /**
     * 读出来的原始值（迁移进服务端设置时用：一项一项地挑，不合规矩的那几项不拖累别的）。
     *
     * @param draft      读到的每一项（没写的是 {@code null}）；{@code unreadable} 不为空时是 {@code null}
     * @param problems   认不出的键、类型不对的项（一项一句；密钥的值不进这里）
     * @param unreadable 整份读不了（不是 JSON、最外层不是对象、文件打不开）的原因；读得了是 {@code null}
     */
    public record DraftRead(LlmConfig.Draft draft, List<String> problems, String unreadable) {
    }

    /** 读文件里的原始值；文件不在返回 {@code null}。 */
    public static DraftRead readDraft(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException | RuntimeException e) {
            return new DraftRead(null, List.of(), "读不了 " + file + "：" + e);
        }
        return draft(text);
    }

    private static DraftRead draft(String text) {
        JsonObject root;
        try {
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setLenient(false);
            JsonElement element = new Gson().getAdapter(JsonElement.class).read(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                return new DraftRead(null, List.of(), "JSON 后面还有多余的内容");
            }
            if (element == null || !element.isJsonObject()) {
                return new DraftRead(null, List.of(), "最外层要是一个 { … } 对象");
            }
            root = element.getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return new DraftRead(null, List.of(), "不是合法的 JSON（" + e.getMessage() + "）");
        }

        List<String> problems = new ArrayList<>();
        for (String key : root.keySet()) {
            if (!KEYS.contains(key)) {
                problems.add("认不出的键「" + key + "」（认得的：" + String.join("、", KEYS.stream().sorted().toList()) + "）");
            }
        }
        Fields f = new Fields(root, problems);
        LlmConfig.Draft draft = new LlmConfig.Draft(
                f.bool("enabled"), f.string("baseUrl"), f.string("apiKey"), f.string("model"),
                f.integer("maxTokens"), f.decimal("temperature"), f.string("reasoningEffort"),
                f.integer("attemptTimeoutMs"), f.decimal("attemptShare"), f.integer("maxAttempts"), f.integer("minAttemptMs"),
                f.integer("backoffBaseMs"), f.integer("backoffMaxMs"), f.integer("maxConcurrent"),
                f.integer("maxQueued"), f.integer("deadlineMarginMs"), f.integer("breakerThreshold"),
                f.integer("breakerCooldownMs"), f.string("language"), f.bool("debugLog"));
        return new DraftRead(draft, List.copyOf(problems), null);
    }

    /** 逐个取字段：没写返回 {@code null}；类型不对记进 problems、也返回 {@code null}（一次把错都报全）。 */
    private record Fields(JsonObject root, List<String> problems) {

        Boolean bool(String key) {
            JsonPrimitive p = primitive(key);
            if (p == null) {
                return null;
            }
            if (!p.isBoolean()) {
                problems.add(key + " 要是 true 或 false，写的是 " + p);
                return null;
            }
            return p.getAsBoolean();
        }

        String string(String key) {
            JsonPrimitive p = primitive(key);
            if (p == null) {
                return null;
            }
            if (!p.isString()) {
                problems.add(key + " 要是字符串（带引号），写的是 " + (key.equals("apiKey") ? "（不显示）" : p.toString()));
                return null;
            }
            return p.getAsString();
        }

        Integer integer(String key) {
            JsonPrimitive p = primitive(key);
            if (p == null) {
                return null;
            }
            if (!p.isNumber()) {
                problems.add(key + " 要是整数，写的是 " + p);
                return null;
            }
            double v = p.getAsDouble();
            if (v != Math.rint(v) || v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                problems.add(key + " 要是整数，写的是 " + p);
                return null;
            }
            return (int) v;
        }

        Double decimal(String key) {
            JsonPrimitive p = primitive(key);
            if (p == null) {
                return null;
            }
            if (!p.isNumber()) {
                problems.add(key + " 要是数字，写的是 " + p);
                return null;
            }
            return p.getAsDouble();
        }

        /** 键不在、或者写成 null：当没写。数组 / 对象：记一条错。 */
        private JsonPrimitive primitive(String key) {
            JsonElement e = root.get(key);
            if (e == null || e.isJsonNull()) {
                return null;
            }
            if (!e.isJsonPrimitive()) {
                problems.add(key + " 不能是数组或对象");
                return null;
            }
            return e.getAsJsonPrimitive();
        }
    }
}
