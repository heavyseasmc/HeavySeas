package io.github.heavyseasmc.mod.world.liner;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 北辰号的清单与位置（ADR-0080 · ADR-0081）：清单只写船自己（位置相对船体），摆在哪写在默认航程布局里。
 * 清单与结构文件对不对得上归构建期 {@code checkLinerShip}；这里管「读出来的是那些数」「版本跟着三份源走」
 * 与起服时那三条位置判据 —— 每条红测只造坏一处，并断言<b>只红在那一条</b>。
 */
class LinerShipManifestTest {

    private static final Path DATA = Path.of("src", "main", "resources", "data", "heavyseas");
    private static final RegistryKey<World> MIST = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("heavyseas", "mist_sea"));
    /** 雾海加高后 y 0–159（ADR-0080 P1）。 */
    private static final int BOTTOM = 0;
    private static final int TOP = 159;
    private static final Vec3d BOW = new Vec3d(0.5, 65.15, 0.5);

    private static byte[] read(String rel) throws IOException {
        Path p = DATA.resolve(rel);
        assertTrue(Files.isRegularFile(p), "不在：" + p.toAbsolutePath());
        return Files.readAllBytes(p);
    }

    private static LinerShip.Ship ship() throws IOException {
        return LinerShip.parse(read("liner/ship.json"), read("structure/boat_hull.nbt"));
    }

    /** 官方地图的原点：从默认航程布局读（与模组起服时同一个来源），不在测试里抄一份。 */
    private static BlockPos officialOrigin() throws IOException {
        JsonObject layout = JsonParser.parseString(new String(read("voyage/default.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        var o = layout.getAsJsonObject("liner").getAsJsonArray("origin");
        return new BlockPos(o.get(0).getAsInt(), o.get(1).getAsInt(), o.get(2).getAsInt());
    }

    /** 船占的那一块：这里不读艇的结构，按船体外扩两舷各 6 格、往上 8 格近似（艇挂在舷外，比船体高）。 */
    private static BlockBox roughFootprint(LinerShip.Manifest m) {
        BlockBox h = m.hullBox();
        return new BlockBox(h.getMinX(), h.getMinY(), h.getMinZ() - 6, h.getMaxX(), h.getMaxY() + 8, h.getMaxZ() + 6);
    }

    @Test
    @DisplayName("jar 里那份清单读得出来：九段首尾相接、八条艇都转 90°；摆到官方原点，落脚点在船上")
    void readsTheBundledManifest() throws IOException {
        LinerShip.Ship ship = ship();
        assertEquals(9, ship.segments().size());
        int x = 0;
        for (LinerShip.Segment s : ship.segments()) {
            assertEquals(x, s.x0(), s.structure() + " 没接上上一段");
            x = s.x1();
        }
        assertEquals(ship.size().getX(), x);
        assertEquals(8, ship.skiffs().size());
        ship.skiffs().forEach(k -> assertEquals(BlockRotation.CLOCKWISE_90, k.rotation()));

        BlockPos origin = officialOrigin();
        LinerShip.Manifest m = ship.at(MIST, origin);
        assertEquals(origin, m.origin());
        assertTrue(m.hullBox().contains((int) Math.floor(m.arrival().x), (int) Math.floor(m.arrival().y),
                (int) Math.floor(m.arrival().z)), "落脚点不在船体那一块里：" + m.arrival());
        assertEquals(origin.add(ship.skiffs().get(0).pos()), m.skiffs().get(0).pos(), "艇的位置要按原点换成世界坐标");
        assertEquals(m.origin().getY() + m.waterline() - 1, m.waterTop());
        assertTrue(m.version().matches("[0-9a-f]{12}"), m.version());
    }

    @Test
    @DisplayName("版本跟着三份源走：艇的结构改一个字节、原点挪一格、换一个维度，版本都变")
    void versionFollowsSkiffAndSite() throws IOException {
        byte[] manifest = read("liner/ship.json");
        byte[] skiff = read("structure/boat_hull.nbt");
        byte[] other = skiff.clone();
        other[other.length - 1] ^= 1;
        BlockPos o = officialOrigin();
        String v = LinerShip.version(manifest, skiff, MIST, o);
        assertNotEquals(v, LinerShip.version(manifest, other, MIST, o));
        assertNotEquals(v, LinerShip.version(manifest, skiff, MIST, o.east()));
        assertNotEquals(v, LinerShip.version(manifest, skiff, World.OVERWORLD, o));
        assertEquals(v, LinerShip.version(manifest.clone(), skiff.clone(), MIST, new BlockPos(o.getX(), o.getY(), o.getZ())));
    }

    @Test
    @DisplayName("缺字段就抛，并点名是哪一个 —— 不退回默认值")
    void missingFieldIsNamed() throws IOException {
        String text = new String(read("liner/ship.json"), StandardCharsets.UTF_8).replaceFirst("\"waterline\"", "\"waterline_gone\"");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LinerShip.parse(text.getBytes(StandardCharsets.UTF_8), new byte[0]));
        assertTrue(e.getMessage().contains("waterline"), e.getMessage());
    }

    @Test
    @DisplayName("清单里再写原点就拒绝：位置只有一个来源（默认航程布局的 liner）")
    void manifestMustNotCarryTheSite() throws IOException {
        for (String key : List.of("origin", "dimension")) {
            JsonObject o = JsonParser.parseString(new String(read("liner/ship.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            o.addProperty(key, "x");
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> LinerShip.parse(o.toString().getBytes(StandardCharsets.UTF_8), new byte[0]));
            assertTrue(e.getMessage().startsWith("清单里不该再有 " + key), e.getMessage());
        }
    }

    @Test
    @DisplayName("演习艇正好一条、魔镜朝水平方向 —— 写错就抛并点名（ADR-0083）")
    void drillAndMirrorAreChecked() throws IOException {
        LinerShip.Ship ship = ship();
        assertEquals(1, ship.skiffs().stream().filter(LinerShip.Skiff::drill).count());
        assertEquals(net.minecraft.util.math.Direction.WEST, ship.mirrorFacing());
        String text = new String(read("liner/ship.json"), StandardCharsets.UTF_8);
        String noDrill = text.replace("\"drill\": true", "\"drill\": false");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LinerShip.parse(noDrill.getBytes(StandardCharsets.UTF_8), new byte[0]));
        assertTrue(e.getMessage().contains("演习艇"), e.getMessage());
        String up = text.replace("\"mirror_facing\": \"west\"", "\"mirror_facing\": \"up\"");
        e = assertThrows(IllegalArgumentException.class, () -> LinerShip.parse(up.getBytes(StandardCharsets.UTF_8), new byte[0]));
        assertTrue(e.getMessage().contains("mirror_facing"), e.getMessage());
    }

    @Test
    @DisplayName("官方位置三条都过；每种坏位置只红在它自己那一条")
    void siteProblemsNameTheirOwnRule() throws IOException {
        LinerShip.Ship ship = ship();
        BlockPos o = officialOrigin();
        LinerShip.Manifest ok = ship.at(MIST, o);
        assertEquals(List.of(), LinerShip.siteProblems(ok, roughFootprint(ok), BOTTOM, TOP, List.of(BOW)));

        // 原点 y 单数：水线切在一块钢板正中（离水面近两格也不碰「装不下」与「离对局」）
        LinerShip.Manifest odd = ship.at(MIST, o.up());
        assertOnly(LinerShip.siteProblems(odd, roughFootprint(odd), BOTTOM, TOP, List.of(BOW)), "不是一列钢板的下格");
        // 抬高 40 格（双数）：顶出维度
        LinerShip.Manifest high = ship.at(MIST, o.up(40));
        assertOnly(LinerShip.siteProblems(high, roughFootprint(high), BOTTOM, TOP, List.of(BOW)), "装不下");
        // 挪到船头 200 格外
        LinerShip.Manifest near = ship.at(MIST, new BlockPos(o.getX(), o.getY(), -200 - ship.size().getZ()));
        assertOnly(LinerShip.siteProblems(near, roughFootprint(near), BOTTOM, TOP, List.of(BOW)), "离对局船头");
        // 同一个位置，换一份船头离得远的布局：不红（判据认的是每份布局各自的船头，不是写死的原点）
        assertEquals(List.of(), LinerShip.siteProblems(near, roughFootprint(near), BOTTOM, TOP, List.of(new Vec3d(0.5, 65, 3000.5))));
    }

    private static void assertOnly(List<String> problems, String fragment) {
        assertEquals(1, problems.size(), "该只红在「" + fragment + "」一条：" + problems);
        assertTrue(problems.get(0).contains(fragment), "该红在「" + fragment + "」：" + problems);
    }

    @Test
    @DisplayName("存档记的是一串范围、各带维度：写进去读回来逐项相同，坏的一条丢掉不连累别的")
    void stateKeepsSeparateAreas() {
        LinerShip.State state = new LinerShip.State();
        state.placed = "abcdef012345";
        state.areas.add(new LinerShip.Area(MIST, new BlockBox(-168, 52, -1023, 167, 136, -982), 63));
        state.areas.add(new LinerShip.Area(World.OVERWORLD, new BlockBox(5000, 40, 5000, 5335, 124, 5046), 51));
        NbtCompound nbt = state.writeNbt(new NbtCompound(), null);
        nbt.getList("areas", 10).getCompound(1).putIntArray("box", new int[]{1, 2});   // 第二条写坏
        LinerShip.State back = LinerShip.State.fromNbt(nbt, null);
        assertEquals("abcdef012345", back.placed);
        assertEquals(1, back.areas.size(), "坏的那一条该丢掉、好的那一条留着");
        assertTrue(LinerShip.sameArea(state.areas.get(0), back.areas.get(0)), back.areas.toString());
        assertEquals(63, back.areas.get(0).waterTop());
        assertEquals(MIST, back.areas.get(0).dimension());
    }
}
