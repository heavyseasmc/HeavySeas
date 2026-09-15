package io.github.heavyseasmc.engine.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * JSON 取值的公共部分。<b>每个取值口都要么给出值、要么抛</b>，没有第三种出口。
 *
 * <h2>为什么手写解析而不是反射绑定</h2>
 * Gson 的反射绑定对<b>缺字段与多字段一律沉默</b>：缺的填 null/0，多的丢掉。
 * 这两种沉默正是数值文件最容易出的错 —— 导出器改了字段名，绑定照样「成功」，
 * 于是落海名单变成空的、而没有任何一行报错。手写麻烦，但每一处缺失都有位置信息。
 *
 * <h2>键要逐个列出来</h2>
 * {@link #onlyKeys} 对没列出的键直接抛；只排除已知的坏键是拦不住的。它抓的是「导出器写了、引擎没读」——
 * 这种漂移在 M5 加天候字段时几乎一定会发生一次，而沉默版本的表现是数值悄悄不生效。
 * 下划线开头的键是注释（{@code _comment}），一律放行。
 *
 * <h2>来源是一个名字，不是一个文件</h2>
 * 数据包里的资源没有文件路径，只有一个标识和一条字符流。所以每个取值口收的是 {@code source}
 * —— 出错时报给人看的来源名 —— 而不是 {@link Path}。从文件读只是来源之一，见 {@link #fromFile}。
 */
final class JsonSupport {

    private JsonSupport() {
    }

    /** 打开文件交给 {@code read}，读完关闭。文件打不开也算数据错误，并报出路径。 */
    static <T> T fromFile(Path file, Function<Reader, T> read) {
        Objects.requireNonNull(file, "file");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return read.apply(reader);
        } catch (IOException e) {
            throw new DataFormatException("读不了数值数据文件: " + file, e);
        }
    }

    /**
     * 读出顶层对象。{@code reader} 由调用方关闭。
     *
     * <p>❗读流出错与 JSON 写坏了是两回事，要改的地方完全不同。Gson 把读流时的 {@link IOException}
     * 包成 {@link JsonIOException}，而它是 {@link JsonParseException} 的子类 —— 不先单独接住，
     * 「流读到一半断了」就会被报成「不是合法 JSON」。
     */
    static JsonObject readObject(String source, Reader reader) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("来源名不能为空：数据出错时，它是唯一能说明「是哪份数据」的东西");
        }
        Objects.requireNonNull(reader, "reader");
        try {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                throw DataFormatException.at(source, "顶层", "应当是一个 JSON 对象，实际是 " + kindOf(root));
            }
            return root.getAsJsonObject();
        } catch (JsonIOException e) {
            throw new DataFormatException("读不了数值数据: " + source, e.getCause() != null ? e.getCause() : e);
        } catch (JsonParseException e) {
            throw new DataFormatException("数值数据不是合法 JSON: " + source, e);
        }
    }

    /**
     * 校验 schema 版本。
     *
     * <p>版本不符<b>不是警告而是失败</b>：`data/README.md` 的约定是「改动字段含义必须递增」，
     * 所以对不上就意味着引擎与数据对字段含义的理解不同，继续读出来的每个数都可能是错的。
     */
    static void requireSchemaVersion(String source, JsonObject root, int expected) {
        int actual = integer(source, "顶层", root, "schema_version");
        if (actual != expected) {
            throw DataFormatException.at(source, "schema_version",
                    "本引擎只读版本 %d，文件是版本 %d —— 要么升级引擎，要么在 %s 下补一条旧版本读取路径"
                            .formatted(expected, actual, "engine/src/main/java/io/github/heavyseasmc/engine/data/"));
        }
    }

    /** 键白名单。下划线开头的键（注释）一律放行。 */
    static void onlyKeys(String source, String where, JsonObject object, Set<String> allowed) {
        Set<String> unknown = new LinkedHashSet<>();
        for (String key : object.keySet()) {
            if (!key.startsWith("_") && !allowed.contains(key)) {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            throw DataFormatException.at(source, where,
                    "有引擎不认识的字段 %s —— 先决定是读它还是丢它，再改白名单（允许的是 %s）"
                            .formatted(unknown, allowed.stream().sorted().toList()));
        }
    }

    static JsonElement required(String source, String where, JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            throw DataFormatException.at(source, where, "缺字段 " + key);
        }
        return value;
    }

    static String string(String source, String where, JsonObject object, String key) {
        JsonElement value = required(source, where, object, key);
        if (!isPrimitive(value, JsonPrimitive::isString)) {
            throw DataFormatException.at(source, where + "." + key, "应当是字符串，实际是 " + kindOf(value));
        }
        String text = value.getAsString();
        if (text.isBlank()) {
            throw DataFormatException.at(source, where + "." + key, "不能是空字符串");
        }
        return text;
    }

    static int integer(String source, String where, JsonObject object, String key) {
        JsonElement value = required(source, where, object, key);
        if (!isPrimitive(value, JsonPrimitive::isNumber)) {
            throw DataFormatException.at(source, where + "." + key, "应当是整数，实际是 " + kindOf(value));
        }
        double raw = value.getAsDouble();
        if (raw != Math.rint(raw)) {
            throw DataFormatException.at(source, where + "." + key, "应当是整数，实际是 " + raw);
        }
        return value.getAsInt();
    }

    static boolean bool(String source, String where, JsonObject object, String key) {
        JsonElement value = required(source, where, object, key);
        if (!isPrimitive(value, JsonPrimitive::isBoolean)) {
            throw DataFormatException.at(source, where + "." + key, "应当是 true/false，实际是 " + kindOf(value));
        }
        return value.getAsBoolean();
    }

    static JsonObject object(String source, String where, JsonObject parent, String key) {
        JsonElement value = required(source, where, parent, key);
        if (!value.isJsonObject()) {
            throw DataFormatException.at(source, where + "." + key, "应当是对象，实际是 " + kindOf(value));
        }
        return value.getAsJsonObject();
    }

    static JsonArray array(String source, String where, JsonObject parent, String key) {
        JsonElement value = required(source, where, parent, key);
        if (!value.isJsonArray()) {
            throw DataFormatException.at(source, where + "." + key, "应当是数组，实际是 " + kindOf(value));
        }
        return value.getAsJsonArray();
    }

    /** 字符串数组。允许为空数组 —— 「不保护任何东西」是合法的技能描述。 */
    static List<String> strings(String source, String where, JsonObject parent, String key) {
        JsonArray array = array(source, where, parent, key);
        List<String> out = new java.util.ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            JsonElement item = array.get(i);
            if (!isPrimitive(item, JsonPrimitive::isString) || item.getAsString().isBlank()) {
                throw DataFormatException.at(source, "%s.%s[%d]".formatted(where, key, i),
                        "应当是非空字符串，实际是 " + kindOf(item));
            }
            out.add(item.getAsString());
        }
        return List.copyOf(out);
    }

    /** 整数数组。每个元素都要是整数，小数与非数字一律抛。 */
    static List<Integer> integers(String source, String where, JsonObject parent, String key) {
        JsonArray array = array(source, where, parent, key);
        List<Integer> out = new java.util.ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            JsonElement item = array.get(i);
            if (!isPrimitive(item, JsonPrimitive::isNumber) || item.getAsDouble() != Math.rint(item.getAsDouble())) {
                throw DataFormatException.at(source, "%s.%s[%d]".formatted(where, key, i),
                        "应当是整数，实际是 " + kindOf(item));
            }
            out.add(item.getAsInt());
        }
        return List.copyOf(out);
    }

    /**
     * 可选的布尔。缺字段时给默认值。
     *
     * <p>❗<b>只有「这张牌没有这条性质」才该用它</b>：物资数据里的布尔字段大多只写 true、省略即 false
     * （诱饵没有 {@code discard_on_use}，就是用后不弃）。要是某个字段缺了就该报错，用 {@link #bool}。
     */
    static boolean optionalBool(String source, String where, JsonObject object, String key, boolean fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? bool(source, where, object, key) : fallback;
    }

    /** 可选的整数。 */
    static int optionalInt(String source, String where, JsonObject object, String key, int fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? integer(source, where, object, key) : fallback;
    }

    /** 可选的字符串。缺字段时给默认值（通常是空串，表示「数据没写」）。 */
    static String optionalString(String source, String where, JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? string(source, where, object, key) : fallback;
    }

    /** 可选的字符串数组。缺字段时给空表。 */
    static List<String> optionalStrings(String source, String where, JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? strings(source, where, object, key)
                : List.of();
    }

    /**
     * 枚举值白名单：取值必须在已知集合里。
     *
     * <p>理由与 {@link #onlyKeys} 相同、方向相反：字段名对了但<b>值</b>是引擎没实现的那一种时，
     * 「照读不误」的表现是这张牌按另一种规则生效，而没有任何一行报错。
     */
    static String oneOf(String source, String where, JsonObject object, String key, Set<String> allowed) {
        String value = string(source, where, object, key);
        if (!allowed.contains(value)) {
            throw DataFormatException.at(source, where + "." + key,
                    "值 %s 引擎不认识 —— 认识的是 %s".formatted(value, allowed.stream().sorted().toList()));
        }
        return value;
    }

    static JsonObject asObject(String source, String where, JsonElement element) {
        if (!element.isJsonObject()) {
            throw DataFormatException.at(source, where, "应当是对象，实际是 " + kindOf(element));
        }
        return element.getAsJsonObject();
    }

    private static boolean isPrimitive(JsonElement element, java.util.function.Predicate<JsonPrimitive> test) {
        return element.isJsonPrimitive() && test.test(element.getAsJsonPrimitive());
    }

    private static String kindOf(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonArray()) {
            return "数组";
        }
        if (element.isJsonObject()) {
            return "对象";
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isString()) {
            return "字符串 " + primitive.getAsString();
        }
        if (primitive.isBoolean()) {
            return "布尔 " + primitive.getAsBoolean();
        }
        return "数字 " + primitive.getAsString();
    }
}
