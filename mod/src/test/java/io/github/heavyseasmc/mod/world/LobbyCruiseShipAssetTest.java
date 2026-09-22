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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void sourceMeshIsAThreeBlockScaleMultiDeckCruiseShip() throws IOException {
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
            for (int axis = 0; axis < 3; axis++) {
                assertTrue(from.get(axis).getAsDouble() >= -16 && to.get(axis).getAsDouble() <= 32,
                        "Source mesh must stay within the JSON model coordinate limits");
                assertTrue(from.get(axis).getAsDouble() < to.get(axis).getAsDouble());
            }
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
    void everyFaceUsesExplicitSpriteLocalUvsWithoutStretching() throws IOException {
        assertFaceUvs(json("assets/heavyseas/models/block/lobby_boat.json"));
    }

    @Test
    void uvGuardRejectsMissingEscapingDegenerateAndStretchedCoordinates() throws IOException {
        JsonObject missing = json("assets/heavyseas/models/block/lobby_boat.json");
        firstFace(missing).remove("uv");
        assertThrows(AssertionError.class, () -> assertFaceUvs(missing));

        for (String invalid : List.of("[-1,0,16,16]", "[0,0,17,16]", "[8,8,8,8]", "[0,0,16,1]")) {
            JsonObject model = json("assets/heavyseas/models/block/lobby_boat.json");
            firstFace(model).add("uv", JsonParser.parseString(invalid));
            assertThrows(AssertionError.class, () -> assertFaceUvs(model), invalid);
        }
    }

    @Test
    void uvGuardRejectsUnknownTextureReferences() throws IOException {
        JsonObject model = json("assets/heavyseas/models/block/lobby_boat.json");
        firstFace(model).addProperty("texture", "#missing");
        assertThrows(AssertionError.class, () -> assertFaceUvs(model));
    }

    @Test
    void itemInheritsCorrectedMeshAndKeepsCompactDisplayTransforms() throws IOException {
        JsonObject item = json("assets/heavyseas/models/item/lobby_boat.json");
        assertEquals("heavyseas:block/lobby_boat", item.get("parent").getAsString());
        JsonObject display = json("assets/heavyseas/models/block/lobby_boat.json").getAsJsonObject("display");
        for (String mode : List.of("gui", "ground", "fixed", "thirdperson_righthand",
                "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand")) {
            JsonArray scale = display.getAsJsonObject(mode).getAsJsonArray("scale");
            assertEquals(3, scale.size(), mode);
            for (JsonElement value : scale) {
                assertTrue(value.getAsDouble() > 0 && value.getAsDouble() <= 0.3,
                        mode + " must not inherit the tenfold world-render scale");
            }
        }
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

    private static JsonObject firstFace(JsonObject model) {
        return model.getAsJsonArray("elements").get(0).getAsJsonObject()
                .getAsJsonObject("faces").getAsJsonObject("up");
    }

    private static void assertFaceUvs(JsonObject model) {
        JsonObject textures = model.getAsJsonObject("textures");
        JsonArray elements = model.getAsJsonArray("elements");
        assertTrue(elements.size() >= 24);
        for (int index = 0; index < elements.size(); index++) {
            JsonObject element = elements.get(index).getAsJsonObject();
            JsonArray from = element.getAsJsonArray("from");
            JsonArray to = element.getAsJsonArray("to");
            JsonObject faces = element.getAsJsonObject("faces");
            assertEquals(Set.of("down", "up", "north", "south", "west", "east"), faces.keySet());
            for (String direction : faces.keySet()) {
                String context = "Element " + index + " face " + direction;
                JsonObject face = faces.getAsJsonObject(direction);
                String texture = face.get("texture").getAsString();
                assertTrue(texture.startsWith("#") && textures.has(texture.substring(1)), context + " texture");
                assertTrue(textures.get(texture.substring(1)).getAsString().startsWith("minecraft:block/"),
                        context + " must keep its vanilla material");
                assertTrue(face.has("uv"), context + " must not derive UVs from out-of-block coordinates");
                JsonArray uv = face.getAsJsonArray("uv");
                assertEquals(4, uv.size(), context);
                for (JsonElement coordinate : uv) {
                    double value = coordinate.getAsDouble();
                    assertTrue(Double.isFinite(value) && value >= 0 && value <= 16,
                            context + " UV must stay inside its sprite: " + value);
                }
                double width = uv.get(2).getAsDouble() - uv.get(0).getAsDouble();
                double height = uv.get(3).getAsDouble() - uv.get(1).getAsDouble();
                assertTrue(width > 0 && height > 0, context + " UV must have area");
                int uAxis = switch (direction) {
                    case "east", "west" -> 2;
                    default -> 0;
                };
                int vAxis = switch (direction) {
                    case "up", "down" -> 2;
                    default -> 1;
                };
                // Uniform sampling keeps thin rails and large decks from squashing a full square sprite.
                double uDensity = width / (to.get(uAxis).getAsDouble() - from.get(uAxis).getAsDouble());
                double vDensity = height / (to.get(vAxis).getAsDouble() - from.get(vAxis).getAsDouble());
                assertEquals(uDensity, vDensity, 0.001, context + " UV aspect ratio");
            }
        }
    }
}
