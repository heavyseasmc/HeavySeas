package io.github.heavyseasmc.mod.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lobby block stays save-compatible, while its shipped asset is a seven-item cruise ship. */
final class LobbyCruiseShipAssetTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");

    @Test
    void recipeWrapsAnyWoodenBoatWithFiveIronBlocksAndABell() throws IOException {
        JsonObject recipe = json("data/heavyseas/recipe/lobby_boat.json");

        assertEquals("minecraft:crafting_shaped", recipe.get("type").getAsString());
        assertEquals(List.of(" B ", "I#I", "III"), strings(recipe.getAsJsonArray("pattern")));

        JsonObject key = recipe.getAsJsonObject("key");
        assertEquals("minecraft:bell", key.getAsJsonObject("B").get("item").getAsString());
        assertEquals("minecraft:iron_block", key.getAsJsonObject("I").get("item").getAsString());
        assertEquals("minecraft:boats", key.getAsJsonObject("#").get("tag").getAsString());
        assertEquals("heavyseas:lobby_boat", recipe.getAsJsonObject("result").get("id").getAsString());

        String slots = String.join("", strings(recipe.getAsJsonArray("pattern"))).replace(" ", "");
        assertEquals(7, slots.length(), "合成表必须正好使用七个物品");
        assertEquals(5, slots.chars().filter(c -> c == 'I').count());
        assertEquals(1, slots.chars().filter(c -> c == '#').count());
        assertEquals(1, slots.chars().filter(c -> c == 'B').count());
    }

    @Test
    void modelIsAThreeBlockScaleMultiDeckCruiseShip() throws IOException {
        JsonObject model = json("assets/heavyseas/models/block/lobby_boat.json");
        JsonArray elements = model.getAsJsonArray("elements");
        assertTrue(elements.size() >= 24, "游轮至少需要 24 个独立构件，实际 " + elements.size());

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (JsonElement value : elements) {
            JsonArray from = value.getAsJsonObject().getAsJsonArray("from");
            JsonArray to = value.getAsJsonObject().getAsJsonArray("to");
            minX = Math.min(minX, from.get(0).getAsDouble());
            minY = Math.min(minY, from.get(1).getAsDouble());
            minZ = Math.min(minZ, from.get(2).getAsDouble());
            maxX = Math.max(maxX, to.get(0).getAsDouble());
            maxY = Math.max(maxY, to.get(1).getAsDouble());
            maxZ = Math.max(maxZ, to.get(2).getAsDouble());
        }

        assertTrue(minX <= -8 && maxX >= 24, "船宽必须横跨至少两格");
        assertTrue(minZ <= -16 && maxZ >= 32, "船长必须横跨至少三格并覆盖八个大厅座位");
        assertTrue(minY <= -6 && maxY >= 28, "游轮需要深船体和多层上层建筑");
        assertTrue(model.getAsJsonObject("textures").entrySet().size() >= 8,
                "游轮至少要有八种材质层次");
    }

    @Test
    void playerFacingNamesCallItACruiseShip() throws IOException {
        assertEquals("大厅游轮", json("assets/heavyseas/lang/zh_cn.json")
                .get("block.heavyseas.lobby_boat").getAsString());
        assertEquals("Lobby Cruise Ship", json("assets/heavyseas/lang/en_us.json")
                .get("block.heavyseas.lobby_boat").getAsString());
    }

    private static JsonObject json(String relative) throws IOException {
        Path path = RESOURCES.resolve(relative);
        assertTrue(Files.isRegularFile(path), "资源不存在：" + path.toAbsolutePath());
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static List<String> strings(JsonArray array) {
        return array.asList().stream().map(JsonElement::getAsString).toList();
    }
}
