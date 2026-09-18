package io.github.heavyseasmc.mod.data;

import com.google.gson.JsonObject;
import io.github.heavyseasmc.engine.data.DataFormatException;
import net.minecraft.util.Identifier;

import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 雾表（ADR-0034 §5.1.4）：每种天候那一天的能见度、雨雷与时刻。
 *
 * <p>数据，不是代码：ADR-0032 #2 记过「天候 id 在三处写死、数据包换 id 即崩」，雾这一族从第一天起就按 id 查表。
 * 表要覆盖 {@code data/weather} 的<b>全部</b> id，多一个少一个都在加载期拒绝 —— id 集合从天候数据取，判据里不写十个字面量。
 *
 * @param id      自称的 id，与资源路径 {@code fog/<name>.json} 对应
 * @param entries 按天候 id 的条目；另有一条 {@code default} 给没有天候数据的旧局
 */
public record FogTable(Identifier id, Map<String, Entry> entries) {

    public static final int SCHEMA_VERSION = 1;
    public static final String RESOURCE_DIR = "fog";
    public static final String DEFAULT_ENTRY = "default";
    public static final int END_MAX = 512;

    private static final Set<String> TOP_KEYS = Set.of("schema_version", "id", "entries");
    private static final Set<String> ENTRY_KEYS = Set.of("start", "end", "rain", "thunder", "time");

    public FogTable {
        Objects.requireNonNull(id, "id");
        entries = Map.copyOf(entries);
    }

    /**
     * 一天的世界参数。
     *
     * @param start   雾从几格外开始变浓（地形雾；天空雾一律从 0 起）
     * @param end     几格外完全看不见。<b>{@code start == end == 0} 表示不改，走游戏默认的雾</b>（晴空那一天）
     * @param rain    下不下雨
     * @param thunder 打不打雷（要与 rain 一起为真才会打雷，游戏自己的规矩）
     * @param time    钉住的时刻（0–23999，6000 正午）
     */
    public record Entry(int start, int end, boolean rain, boolean thunder, int time) {

        /** 不改雾：客户端看到 0/0 就交还给游戏默认。 */
        public boolean vanilla() {
            return start == 0 && end == 0;
        }
    }

    /** 某种天候那一天的条目；天候 id 为空（旧局没有天候牌）时取 {@code default}。 */
    public Entry entryFor(String weatherId) {
        String key = weatherId == null || weatherId.isEmpty() ? DEFAULT_ENTRY : weatherId;
        Entry entry = entries.get(key);
        if (entry == null) {
            // 加载期已按天候数据逐个核过，走到这里只可能是天候数据在雾表之后换了一套。
            throw new IllegalStateException("雾表 %s 里没有天候 %s 的条目（有的是 %s）".formatted(id, key, entries.keySet()));
        }
        return entry;
    }

    /**
     * 严格读取一份雾表。
     *
     * @param weatherIds 本次加载的天候 id 全集：表里每一个都得有，也不得多出不存在的
     */
    public static FogTable parse(String source, Reader reader, Identifier expectedId, Set<String> weatherIds) {
        JsonObject root = SceneJson.readObject(source, reader);
        SceneJson.requireSchemaVersion(source, root, SCHEMA_VERSION);
        SceneJson.onlyKeys(source, "顶层", root, TOP_KEYS);
        Identifier id = SceneJson.identifier(source, "顶层", root, "id");
        if (!id.equals(expectedId)) {
            throw DataFormatException.at(source, "id", "自称 %s，但按路径它应当是 %s".formatted(id, expectedId));
        }
        JsonObject entriesJson = SceneJson.object(source, "顶层", root, "entries");
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (String key : entriesJson.keySet()) {
            if (key.startsWith("_")) {
                continue;
            }
            if (!key.equals(DEFAULT_ENTRY) && !weatherIds.contains(key)) {
                throw DataFormatException.at(source, "entries." + key,
                        "data/weather 里没有这张天候牌（有的是 %s）".formatted(weatherIds.stream().sorted().toList()));
            }
            String where = "entries." + key;
            JsonObject entryJson = SceneJson.object(source, "entries", entriesJson, key);
            SceneJson.onlyKeys(source, where, entryJson, ENTRY_KEYS);
            int start = SceneJson.integer(source, where, entryJson, "start");
            int end = SceneJson.integer(source, where, entryJson, "end");
            boolean rain = SceneJson.optionalBool(source, where, entryJson, "rain", false);
            boolean thunder = SceneJson.optionalBool(source, where, entryJson, "thunder", false);
            int time = SceneJson.integer(source, where, entryJson, "time");
            if (start < 0 || end < 0) {
                throw DataFormatException.at(source, where, "start / end 不能是负数");
            }
            if (!(start == 0 && end == 0) && start >= end) {
                throw DataFormatException.at(source, where, "start %d 不小于 end %d".formatted(start, end));
            }
            if (end > END_MAX) {
                throw DataFormatException.at(source, where, "end %d 超过 %d 格".formatted(end, END_MAX));
            }
            if (time < 0 || time > 23999) {
                throw DataFormatException.at(source, where, "time %d 不在 0–23999 之间".formatted(time));
            }
            if (thunder && !rain) {
                throw DataFormatException.at(source, where, "thunder 为真时 rain 也得为真（没有干打雷）");
            }
            entries.put(key, new Entry(start, end, rain, thunder, time));
        }
        for (String weatherId : weatherIds) {
            if (!entries.containsKey(weatherId)) {
                throw DataFormatException.at(source, "顶层", "天候 %s 没有雾值（data/weather 里有这张牌）".formatted(weatherId));
            }
        }
        if (!entries.containsKey(DEFAULT_ENTRY)) {
            throw DataFormatException.at(source, "顶层", "缺少 default 条目（给没有天候数据的旧局）");
        }
        return new FogTable(id, entries);
    }

    /** 资源 id → 雾表自称的 id：{@code heavyseas:fog/default.json} → {@code heavyseas:default}。 */
    public static Identifier tableIdOf(Identifier resource) {
        String path = resource.getPath();
        String prefix = RESOURCE_DIR + "/";
        String suffix = ".json";
        if (!path.startsWith(prefix) || !path.endsWith(suffix) || path.length() <= prefix.length() + suffix.length()) {
            throw new IllegalArgumentException("不是雾表资源的路径: " + resource);
        }
        return Identifier.of(resource.getNamespace(), path.substring(prefix.length(), path.length() - suffix.length()));
    }
}
