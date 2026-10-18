package io.github.heavyseasmc.engine.data;

import com.google.gson.JsonObject;
import io.github.heavyseasmc.engine.weather.WeatherCard;
import io.github.heavyseasmc.engine.weather.WeatherEffect;

import java.io.Reader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 严格读取 {@code data/weather/default.json}。 */
public final class WeatherLoader {
    public static final int SCHEMA_VERSION = 1;
    private static final Set<String> TOP_KEYS = Set.of("schema_version", "id", "total", "cards");
    private static final Set<String> CARD_KEYS = Set.of("id", "effect");
    private static final Set<String> EFFECTS = Arrays.stream(WeatherEffect.values())
            .map(WeatherEffect::id).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private WeatherLoader() {
    }

    public static List<WeatherCard> load(Path file) {
        return JsonSupport.fromFile(file, reader -> load(file.toString(), reader));
    }

    public static List<WeatherCard> load(String source, Reader reader) {
        return loadDocument(source, reader).value();
    }

    public static DataDocument<List<WeatherCard>> loadDocument(String source, Reader reader) {
        JsonObject root = JsonSupport.readObject(source, reader);
        JsonSupport.requireSchemaVersion(source, root, SCHEMA_VERSION);
        JsonSupport.onlyKeys(source, "顶层", root, TOP_KEYS);
        List<WeatherCard> cards = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        var array = JsonSupport.array(source, "顶层", root, "cards");
        for (int i = 0; i < array.size(); i++) {
            String where = "cards[%d]".formatted(i);
            JsonObject json = JsonSupport.asObject(source, where, array.get(i));
            JsonSupport.onlyKeys(source, where, json, CARD_KEYS);
            String id = JsonSupport.string(source, where, json, "id");
            if (!ids.add(id)) {
                throw DataFormatException.at(source, where, "牌 id 重复: " + id);
            }
            String effect = JsonSupport.oneOf(source, where, json, "effect", EFFECTS);
            cards.add(new WeatherCard(id, WeatherEffect.fromId(effect)));
        }
        if (cards.isEmpty()) {
            throw DataFormatException.at(source, "cards", "天候牌堆不能为空");
        }
        int declared = JsonSupport.integer(source, "顶层", root, "total");
        if (declared != cards.size()) {
            throw DataFormatException.at(source, "total",
                    "写着 %d 张，实际读到 %d 张".formatted(declared, cards.size()));
        }
        return new DataDocument<>(JsonSupport.string(source, "顶层", root, "id"), List.copyOf(cards));
    }
}
