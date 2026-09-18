package io.github.heavyseasmc.mod.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.heavyseasmc.engine.data.DataFormatException;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 航程布局的读取与校验（ADR-0034 §5.5.1）。
 *
 * <p>每条坏数据都断言<b>文案点名的是那一条</b>，不只看抛没抛：红在别处与判据生效退出码相同（证伪表「假红」）。
 * 坏文件由官方那份改一处造出来 —— 改坏的只碰被测那一条。
 */
final class VoyageLayoutLoaderTest {

    private static final Identifier DEFAULT = Identifier.of("heavyseas", "default");
    private static final String SOURCE = "heavyseas:voyage/default.json";

    /** 模组自带的那份必须能读，而且值与 M4 写死的逐项相同 —— 这是「布局数据化没改观感」的正向对照。 */
    @Test
    void shippedDefaultLayoutMatchesTheFormerHardCodedScene() throws IOException {
        VoyageLayout layout = VoyageLayoutLoader.parse(SOURCE, new StringReader(shipped()), DEFAULT);

        assertEquals(DEFAULT, layout.id());
        assertEquals(Identifier.of("heavyseas", "mist_sea"), layout.dimension());
        assertEquals(new Vec3d(0.5, 65.15, 0.5), layout.boat().bow());
        assertEquals(180f, layout.boat().yaw());
        assertEquals(2.0, layout.boat().seatSpacing());
        assertEquals(Identifier.of("heavyseas", "boat_hull"), layout.hull().orElseThrow().structure());
        assertEquals(new Vec3i(2, 2, 15), layout.hull().orElseThrow().anchor());
        assertEquals(VoyageLayout.Restore.WATER, layout.hull().orElseThrow().restore());
        assertEquals(0f, layout.arrival().bearing());
        assertEquals(88, layout.arrival().slideFrom());
        assertEquals(40, layout.arrival().slideTo());
        assertTrue(layout.arrival().forceload());
        assertEquals(DEFAULT, layout.fog());

        // 几何：船头在 +Z 那一端、座位往 −Z 排、人面朝 +Z、岸在 +Z（与 MistSea 原来的注释一致）。
        // sin(180°) 不是精确的 0，逐分量带容差比。
        Vec3d stern = layout.seatAt(7);
        assertEquals(0.5, stern.x, 1e-9);
        assertEquals(65.15, stern.y, 1e-9);
        assertEquals(-13.5, stern.z, 1e-9);
        assertEquals(0f, layout.ridersFacing(), 1e-6f);
        assertEquals(1.0, layout.bearingVector().z, 1e-9);
        Set<ChunkPos> corridor = layout.corridor();
        assertTrue(corridor.contains(new ChunkPos(0, 0)), "船头所在的 chunk 必须在走廊里");
        assertTrue(corridor.contains(new ChunkPos(0, -1)), "船尾在 z=-13.5，chunk -1 必须在走廊里");
        assertTrue(corridor.contains(new ChunkPos(0, 6)), "岸的起滑点 88+16=104 落在 chunk 6");
        assertTrue(corridor.contains(new ChunkPos(1, 7)), "外扩一圈之后 chunk 7 也在");
        assertFalse(corridor.contains(new ChunkPos(0, 9)), "走廊不该无限长");
    }

    @Test
    void resourcePathMapsToLayoutId() {
        assertEquals(DEFAULT, VoyageLayoutLoader.layoutIdOf(Identifier.of("heavyseas", "voyage/default.json")));
        assertEquals(Identifier.of("heavyseas_drill", "open_sea"),
                VoyageLayoutLoader.layoutIdOf(Identifier.of("heavyseas_drill", "voyage/open_sea.json")));
        assertThrows(IllegalArgumentException.class,
                () -> VoyageLayoutLoader.layoutIdOf(Identifier.of("heavyseas", "fog/default.json")));
    }

    @Test
    void wrongSelfClaimedIdIsRejected() throws IOException {
        String text = shipped().replace("\"id\": \"heavyseas:default\"", "\"id\": \"heavyseas:other\"");
        assertRejected(text, "id", "自称 heavyseas:other");
    }

    @Test
    void missingSectionNamesTheField() throws IOException {
        assertRejected(without("arrival"), "顶层", "缺少字段 arrival");
        assertRejected(without("boat"), "顶层", "缺少字段 boat");
        assertRejected(without("dimension"), "顶层", "缺少字段 dimension");
        assertRejected(without("fog"), "顶层", "缺少字段 fog");
    }

    @Test
    void reservedFieldsAreRejectedNotIgnored() throws IOException {
        String text = shipped().replace("\"arrival\":", "\"structures\": [],\n  \"arrival\":");
        assertRejected(text, "顶层", "字段 structures 这一版还不读");
    }

    @Test
    void backdropsAreParsedWithDefaultCullingBoxes() throws IOException {
        VoyageLayout layout = VoyageLayoutLoader.parse(SOURCE, new StringReader(shipped()), DEFAULT);
        assertEquals(3, layout.backdrops().size());
        VoyageLayout.Backdrop coast = layout.backdrops().get(0);
        assertEquals(Identifier.of("heavyseas", "coast_backdrop"), coast.item());
        assertEquals(10f, coast.scale());
        assertEquals(4f, coast.viewRange(), "缺省 view_range 4：起滑点 88 格，1.0 在实体距离 50% 的客户端上会消失");
        // 缺省剔除盒：2 × (88 − 40) + 3 × 10 = 126 宽、30 高。
        assertEquals(126f, coast.boxWidth(), 1e-5f);
        assertEquals(30f, coast.boxHeight(), 1e-5f);

        assertRejected(shipped().replace("\"scale\": 10", "\"scale\": 100"), "backdrops[0].scale", "100.0 不在");
        assertRejected(shipped().replace("\"item\": \"heavyseas:coast_backdrop\"", "\"item\": \"heavyseas:coast_backdrop\", \"tint\": 1"),
                "backdrops[0]", "有不认识的字段 [tint]");
    }

    @Test
    void unknownKeyIsRejected() throws IOException {
        String text = shipped().replace("\"seat_spacing\": 2.0", "\"seat_spacing\": 2.0, \"colour\": \"red\"");
        assertRejected(text, "boat", "有不认识的字段 [colour]");
    }

    @Test
    void slideDistancesAreOrderedAndBounded() throws IOException {
        assertRejected(shipped().replace("\"slide_from\": 88", "\"slide_from\": 40"),
                "arrival.slide_from", "40 不大于 slide_to 40");
        assertRejected(shipped().replace("\"slide_to\": 40", "\"slide_to\": 8").replace("\"slide_from\": 88", "\"slide_from\": 9"),
                "arrival.slide_to", "8 小于 12 格");
        assertRejected(shipped().replace("\"slide_from\": 88", "\"slide_from\": 200"),
                "arrival.slide_from", "200 超过 144 格");
    }

    @Test
    void seatSpacingIsBounded() throws IOException {
        assertRejected(shipped().replace("\"seat_spacing\": 2.0", "\"seat_spacing\": 0.2"),
                "boat.seat_spacing", "0.2 不在");
    }

    @Test
    void hullRequiresAxisAlignedYaw() throws IOException {
        assertRejected(shipped().replace("\"yaw\": 180", "\"yaw\": 135"),
                "boat.yaw", "135.0 不是 90 的倍数");
        // 没有船体的布局（地图自带船）可以朝任何方向。
        JsonObject root = JsonParser.parseString(shipped()).getAsJsonObject();
        root.remove("hull");
        root.getAsJsonObject("boat").addProperty("yaw", 135);
        VoyageLayout free = VoyageLayoutLoader.parse(SOURCE, new StringReader(root.toString()), DEFAULT);
        assertTrue(free.hull().isEmpty());
        assertEquals(135f, free.boat().yaw());
    }

    @Test
    void hullRestoreMustBeKnown() throws IOException {
        assertRejected(shipped().replace("\"restore\": \"water\"", "\"restore\": \"lava\""),
                "hull.restore", "值 lava 不认识");
    }

    @Test
    void schemaVersionMustMatch() throws IOException {
        assertRejected(shipped().replace("\"schema_version\": 1", "\"schema_version\": 2"),
                "schema_version", "本版本只读 1，文件是 2");
    }

    private static void assertRejected(String text, String where, String reason) {
        DataFormatException e = assertThrows(DataFormatException.class,
                () -> VoyageLayoutLoader.parse(SOURCE, new StringReader(text), DEFAULT));
        String expected = SOURCE + " 的 " + where + "：" + reason;
        assertTrue(e.getMessage().startsWith(expected),
                "该红在「" + expected + "」，实际是「" + e.getMessage() + "」");
    }

    /**
     * 把顶层某个字段删掉。经 Gson 解析再序列化，而不是按行删 —— 按行删会留下悬空的逗号，
     * 红在「不是合法 JSON」而不是「缺少字段」（第一版就是这么假红的）。
     */
    private static String without(String key) throws IOException {
        JsonObject root = JsonParser.parseString(shipped()).getAsJsonObject();
        if (root.remove(key) == null) {
            throw new IllegalStateException("官方布局里本来就没有 " + key + " —— 这条红测什么也没删");
        }
        return root.toString();
    }

    private static String shipped() throws IOException {
        try (InputStream in = VoyageLayoutLoaderTest.class.getResourceAsStream("/data/heavyseas/voyage/default.json")) {
            if (in == null) {
                throw new IllegalStateException("模组资源里没有 data/heavyseas/voyage/default.json —— 没在测，不是通过");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
