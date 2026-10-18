package io.github.heavyseasmc.mod.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.heavyseasmc.engine.data.DataFormatException;
import net.minecraft.util.Identifier;

import java.io.Reader;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 场景数据（航程布局 · 雾表）的 JSON 取值口。
 *
 * <p>与引擎的 {@code JsonSupport} 同一形状：<b>每个取值口要么给出值、要么抛</b>，出错文案带来源与位置。
 * 引擎那一份是包内可见的，而且不该为模组的场景数据放宽 —— 场景不是规则，这里是模组自己的一份。
 * 理由与引擎相同：Gson 的反射绑定对缺字段与多字段一律沉默，而「坐标写错了」的表现是船摆在海底，不报错。
 */
final class SceneJson {

    private SceneJson() {
    }

    static JsonObject readObject(String source, Reader reader) {
        try {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                throw DataFormatException.at(source, "顶层", "应当是一个 JSON 对象，实际是 " + kindOf(root));
            }
            return root.getAsJsonObject();
        } catch (JsonIOException e) {
            throw new DataFormatException("读不了场景数据: " + source, e.getCause() != null ? e.getCause() : e);
        } catch (JsonParseException e) {
            throw new DataFormatException("场景数据不是合法 JSON: " + source, e);
        }
    }

    static void requireSchemaVersion(String source, JsonObject root, int expected) {
        int actual = integer(source, "顶层", root, "schema_version");
        if (actual != expected) {
            throw DataFormatException.at(source, "schema_version",
                    "本版本只读 %d，文件是 %d".formatted(expected, actual));
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
                    "有不认识的字段 %s（允许的是 %s）".formatted(unknown, allowed.stream().sorted().toList()));
        }
    }

    static JsonElement required(String source, String where, JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            throw DataFormatException.at(source, where, "缺少字段 " + key);
        }
        return value;
    }

    static String string(String source, String where, JsonObject object, String key) {
        JsonElement value = required(source, where, object, key);
        if (!isPrimitive(value, JsonPrimitive::isString) || value.getAsString().isBlank()) {
            throw DataFormatException.at(source, where + "." + key, "应当是非空字符串，实际是 " + kindOf(value));
        }
        return value.getAsString();
    }

    static Identifier identifier(String source, String where, JsonObject object, String key) {
        String raw = string(source, where, object, key);
        Identifier id = Identifier.tryParse(raw);
        if (id == null) {
            throw DataFormatException.at(source, where + "." + key, "不是合法的命名空间 id：" + raw);
        }
        return id;
    }

    static double number(String source, String where, JsonObject object, String key) {
        JsonElement value = required(source, where, object, key);
        if (!isPrimitive(value, JsonPrimitive::isNumber)) {
            throw DataFormatException.at(source, where + "." + key, "应当是数字，实际是 " + kindOf(value));
        }
        double raw = value.getAsDouble();
        if (!Double.isFinite(raw)) {
            throw DataFormatException.at(source, where + "." + key, "不是有限的数字");
        }
        return raw;
    }

    static int integer(String source, String where, JsonObject object, String key) {
        double raw = number(source, where, object, key);
        if (raw != Math.rint(raw)) {
            throw DataFormatException.at(source, where + "." + key, "应当是整数，实际是 " + raw);
        }
        return (int) raw;
    }

    static boolean optionalBool(String source, String where, JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
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

    /** 数组里的一项必须是对象。 */
    static JsonObject asObject(String source, String where, JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            throw DataFormatException.at(source, where, "应当是对象，实际是 " + kindOf(element));
        }
        return element.getAsJsonObject();
    }

    /** 可选的对象：缺字段或 null 都算「没有」。 */
    static Optional<JsonObject> optionalObject(String source, String where, JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        if (value == null || value.isJsonNull()) {
            return Optional.empty();
        }
        if (!value.isJsonObject()) {
            throw DataFormatException.at(source, where + "." + key, "应当是对象或 null，实际是 " + kindOf(value));
        }
        return Optional.of(value.getAsJsonObject());
    }

    /** 恰好三个数字的数组（坐标）。 */
    static double[] numbers3(String source, String where, JsonObject parent, String key) {
        JsonElement value = required(source, where, parent, key);
        if (!value.isJsonArray() || value.getAsJsonArray().size() != 3) {
            throw DataFormatException.at(source, where + "." + key, "应当是 [x, y, z] 三个数字，实际是 " + kindOf(value));
        }
        JsonArray array = value.getAsJsonArray();
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            JsonElement item = array.get(i);
            if (!isPrimitive(item, JsonPrimitive::isNumber) || !Double.isFinite(item.getAsDouble())) {
                throw DataFormatException.at(source, "%s.%s[%d]".formatted(where, key, i),
                        "应当是数字，实际是 " + kindOf(item));
            }
            out[i] = item.getAsDouble();
        }
        return out;
    }

    /** 恰好三个整数的数组。 */
    static int[] integers3(String source, String where, JsonObject parent, String key) {
        double[] raw = numbers3(source, where, parent, key);
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            if (raw[i] != Math.rint(raw[i])) {
                throw DataFormatException.at(source, "%s.%s[%d]".formatted(where, key, i),
                        "应当是整数，实际是 " + raw[i]);
            }
            out[i] = (int) raw[i];
        }
        return out;
    }

    static String oneOf(String source, String where, JsonObject object, String key, Set<String> allowed) {
        String value = string(source, where, object, key);
        if (!allowed.contains(value)) {
            throw DataFormatException.at(source, where + "." + key,
                    "值 %s 不认识 —— 认识的是 %s".formatted(value, allowed.stream().sorted().toList()));
        }
        return value;
    }

    private static boolean isPrimitive(JsonElement element, Predicate<JsonPrimitive> test) {
        return element.isJsonPrimitive() && test.test(element.getAsJsonPrimitive());
    }

    private static String kindOf(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonArray()) {
            return "数组（%d 项）".formatted(element.getAsJsonArray().size());
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
