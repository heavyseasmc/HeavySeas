package io.github.heavyseasmc.mod.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.heavyseasmc.engine.data.DataFormatException;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 雾表的读取与校验（ADR-0034 §5.1.4）。天候 id 集合从 {@code data/weather} 现取 —— 判据里不写十个字面量。
 * 每条坏数据都断言文案点名的是那一条。
 */
final class FogTableTest {

    private static final Identifier DEFAULT = Identifier.of("heavyseas", "default");
    private static final String SOURCE = "heavyseas:fog/default.json";

    @Test
    void shippedTableCoversEveryWeatherAndHasADefault() throws IOException {
        Set<String> weather = weatherIds();
        assertEquals(10, weather.size(), "data/weather 该有十张 —— 没读到就不是在测");
        FogTable table = FogTable.parse(SOURCE, new StringReader(shipped()), DEFAULT, weather);

        assertEquals(DEFAULT, table.id());
        assertEquals(weather.size() + 1, table.entries().size());
        FogTable.Entry dense = table.entryFor("dense_fog");
        assertEquals(12, dense.end());
        assertTrue(dense.start() < dense.end());
        assertTrue(table.entryFor("clear_skies").vanilla(), "晴空 = 不改，走游戏默认");
        assertTrue(table.entryFor("storm").rain() && table.entryFor("storm").thunder());
        assertFalse(table.entryFor("dense_fog").rain());
        assertEquals(table.entries().get(FogTable.DEFAULT_ENTRY), table.entryFor(""), "没有天候牌的旧局取 default");
        assertThrows(IllegalStateException.class, () -> table.entryFor("drizzle"));
    }

    @Test
    void resourcePathMapsToTableId() {
        assertEquals(DEFAULT, FogTable.tableIdOf(Identifier.of("heavyseas", "fog/default.json")));
        assertThrows(IllegalArgumentException.class,
                () -> FogTable.tableIdOf(Identifier.of("heavyseas", "voyage/default.json")));
    }

    @Test
    void everyWeatherMustHaveAnEntry() throws IOException {
        JsonObject root = JsonParser.parseString(shipped()).getAsJsonObject();
        root.getAsJsonObject("entries").remove("sunday");
        assertRejected(root.toString(), weatherIds(), "顶层", "天候 sunday 没有雾值");
    }

    @Test
    void unknownWeatherIsRejectedNotIgnored() throws IOException {
        JsonObject root = JsonParser.parseString(shipped()).getAsJsonObject();
        JsonObject extra = root.getAsJsonObject("entries").getAsJsonObject("rain").deepCopy();
        root.getAsJsonObject("entries").add("drizzle", extra);
        assertRejected(root.toString(), weatherIds(), "entries.drizzle", "data/weather 里没有这张天候牌");
    }

    @Test
    void startMustBeBelowEndUnlessBothZero() throws IOException {
        JsonObject root = JsonParser.parseString(shipped()).getAsJsonObject();
        root.getAsJsonObject("entries").getAsJsonObject("dense_fog").addProperty("start", 14);
        assertRejected(root.toString(), weatherIds(), "entries.dense_fog", "start 14 不小于 end 12");
    }

    @Test
    void thunderNeedsRain() throws IOException {
        JsonObject root = JsonParser.parseString(shipped()).getAsJsonObject();
        root.getAsJsonObject("entries").getAsJsonObject("gale").addProperty("thunder", true);
        assertRejected(root.toString(), weatherIds(), "entries.gale", "thunder 为真时 rain 也得为真");
    }

    @Test
    void timeIsBounded() throws IOException {
        JsonObject root = JsonParser.parseString(shipped()).getAsJsonObject();
        root.getAsJsonObject("entries").getAsJsonObject("rain").addProperty("time", 24000);
        assertRejected(root.toString(), weatherIds(), "entries.rain", "time 24000 不在 0–23999 之间");
    }

    @Test
    void defaultEntryIsRequired() throws IOException {
        JsonObject root = JsonParser.parseString(shipped()).getAsJsonObject();
        root.getAsJsonObject("entries").remove("default");
        assertRejected(root.toString(), weatherIds(), "顶层", "缺少 default 条目");
    }

    private static void assertRejected(String text, Set<String> weather, String where, String reason) {
        DataFormatException e = assertThrows(DataFormatException.class,
                () -> FogTable.parse(SOURCE, new StringReader(text), DEFAULT, weather));
        String expected = SOURCE + " 的 " + where + "：" + reason;
        assertTrue(e.getMessage().startsWith(expected),
                "该红在「" + expected + "」，实际是「" + e.getMessage() + "」");
    }

    private static Set<String> weatherIds() throws IOException {
        JsonObject root = JsonParser.parseString(resource("/data/heavyseas/weather/default.json")).getAsJsonObject();
        Set<String> ids = new LinkedHashSet<>();
        root.getAsJsonArray("cards").forEach(card -> ids.add(card.getAsJsonObject().get("id").getAsString()));
        return ids;
    }

    private static String shipped() throws IOException {
        return resource("/data/heavyseas/fog/default.json");
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = FogTableTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("模组资源里没有 " + path + " —— 没在测，不是通过");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
