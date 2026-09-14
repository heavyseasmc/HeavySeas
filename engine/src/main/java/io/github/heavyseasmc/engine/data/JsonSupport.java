package io.github.heavyseasmc.engine.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.Set;

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
 */
final class JsonSupport {

    private JsonSupport() {
    }

    static JsonObject readObject(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                throw DataFormatException.at(file, "顶层", "应当是一个 JSON 对象，实际是 " + kindOf(root));
            }
            return root.getAsJsonObject();
        } catch (IOException e) {
            throw new DataFormatException("读不了数值数据文件: " + file, e);
        } catch (JsonParseException e) {
            throw new DataFormatException("数值数据文件不是合法 JSON: " + file, e);
        }
    }

    /**
     * 校验 schema 版本。
     *
     * <p>版本不符<b>不是警告而是失败</b>：`data/README.md` 的约定是「改动字段含义必须递增」，
     * 所以对不上就意味着引擎与数据对字段含义的理解不同，继续读出来的每个数都可能是错的。
     */
    static void requireSchemaVersion(Path file, JsonObject root, int expected) {
        int actual = integer(file, "顶层", root, "schema_version");
        if (actual != expected) {
            throw DataFormatException.at(file, "schema_version",
                    "本引擎只读版本 %d，文件是版本 %d —— 要么升级引擎，要么在 %s 下补一条旧版本读取路径"
                            .formatted(expected, actual, "engine/.../engine/data/"));
        }
    }

    /** 键白名单。下划线开头的键（注释）一律放行。 */
    static void onlyKeys(Path file, String where, JsonObject object, Set<String> allowed) {
        Set<String> unknown = new LinkedHashSet<>();
        for (String key : object.keySet()) {
            if (!key.startsWith("_") && !allowed.contains(key)) {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            throw DataFormatException.at(file, where,
                    "有引擎不认识的字段 %s —— 先决定是读它还是丢它，再改白名单（允许的是 %s）"
                            .formatted(unknown, allowed.stream().sorted().toList()));
        }
    }

    static JsonElement required(Path file, String where, JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            throw DataFormatException.at(file, where, "缺字段 " + key);
        }
        return value;
    }

    static String string(Path file, String where, JsonObject object, String key) {
        JsonElement value = required(file, where, object, key);
        if (!isPrimitive(value, JsonPrimitive::isString)) {
            throw DataFormatException.at(file, where + "." + key, "应当是字符串，实际是 " + kindOf(value));
        }
        String text = value.getAsString();
        if (text.isBlank()) {
            throw DataFormatException.at(file, where + "." + key, "不能是空字符串");
        }
        return text;
    }

    static int integer(Path file, String where, JsonObject object, String key) {
        JsonElement value = required(file, where, object, key);
        if (!isPrimitive(value, JsonPrimitive::isNumber)) {
            throw DataFormatException.at(file, where + "." + key, "应当是整数，实际是 " + kindOf(value));
        }
        double raw = value.getAsDouble();
        if (raw != Math.rint(raw)) {
            throw DataFormatException.at(file, where + "." + key, "应当是整数，实际是 " + raw);
        }
        return value.getAsInt();
    }

    static boolean bool(Path file, String where, JsonObject object, String key) {
        JsonElement value = required(file, where, object, key);
        if (!isPrimitive(value, JsonPrimitive::isBoolean)) {
            throw DataFormatException.at(file, where + "." + key, "应当是 true/false，实际是 " + kindOf(value));
        }
        return value.getAsBoolean();
    }

    static JsonObject object(Path file, String where, JsonObject parent, String key) {
        JsonElement value = required(file, where, parent, key);
        if (!value.isJsonObject()) {
            throw DataFormatException.at(file, where + "." + key, "应当是对象，实际是 " + kindOf(value));
        }
        return value.getAsJsonObject();
    }

    static JsonArray array(Path file, String where, JsonObject parent, String key) {
        JsonElement value = required(file, where, parent, key);
        if (!value.isJsonArray()) {
            throw DataFormatException.at(file, where + "." + key, "应当是数组，实际是 " + kindOf(value));
        }
        return value.getAsJsonArray();
    }

    /** 字符串数组。允许为空数组 —— 「不保护任何东西」是合法的技能描述。 */
    static List<String> strings(Path file, String where, JsonObject parent, String key) {
        JsonArray array = array(file, where, parent, key);
        List<String> out = new java.util.ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            JsonElement item = array.get(i);
            if (!isPrimitive(item, JsonPrimitive::isString) || item.getAsString().isBlank()) {
                throw DataFormatException.at(file, "%s.%s[%d]".formatted(where, key, i),
                        "应当是非空字符串，实际是 " + kindOf(item));
            }
            out.add(item.getAsString());
        }
        return List.copyOf(out);
    }

    static JsonObject asObject(Path file, String where, JsonElement element) {
        if (!element.isJsonObject()) {
            throw DataFormatException.at(file, where, "应当是对象，实际是 " + kindOf(element));
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
