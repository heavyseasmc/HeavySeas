package io.github.heavyseasmc.mod.world.liner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.heavyseasmc.mod.world.SeatEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 露天甲板那几件（C3 第二轮 · c3-deck，ADR-0086 §2 第 11–15 条）的纯规则：小警钟不开局 · 躺椅的坐与对局座位 / 演习艇报名不相干 ·
 * 座位落在坐垫上 · 空心桅杆的竖井与门各是各的件、门开着进得去关着进不去 · 瞭望台一圈连成一件、口沿翻不出去。坐、爬、开门在游戏里实测。
 */
final class DeckPiecesTest {

    private static final List<Direction> HORIZONTAL = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);

    @Test
    void onlyTheMusterBellStartsAVoyage() {
        // 右键走哪条路：只有开航钟交给 DrillSkiff.ringBell（演习艇旁那一口 · 坐在艇里的人敲 = 开阵容面板）。小警钟只响
        int voyage = 0;
        for (LinerProp.Kind k : LinerProp.Kind.values()) {
            if (LinerProp.Rules.use(k) == LinerProp.Use.VOYAGE_BELL) {
                voyage++;
                assertEquals(LinerProp.Kind.DRILL_BELL, k, k + " 走了开局那条路");
            }
        }
        assertEquals(1, voyage, "正向对照：开航钟自己得走那条路");
        assertEquals(LinerProp.Use.ALARM_BELL, LinerProp.Rules.use(LinerProp.Kind.ALARM_BELL));
        assertEquals(LinerProp.Use.SIT, LinerProp.Rules.use(LinerProp.Kind.DECK_CHAIR));
        assertEquals(LinerProp.Use.DOOR, LinerProp.Rules.use(LinerProp.Kind.HOLLOW_MAST));
        // 警钟没有碰撞（只有拉绳垂在人站的那一格里），轮廓在格里、点得中
        assertEquals(List.of(), LinerPropShapes.collision(LinerProp.Kind.ALARM_BELL, null));
        assertFalse(LinerPropShapes.boxes(LinerProp.Kind.ALARM_BELL, null).isEmpty());
    }

    @Test
    void theAlarmBellOutlineCoversTheWholeBell() throws Exception {
        // C3 第三轮打磨（ADR-0086 §5.3 实测「只有钟口那一小截点得中」）：钟身挂在上面那一格（瞭望台口沿那一圈），准星一格一格找方块、
        //   每一格只问那一格自己的轮廓 —— 所以这一格的轮廓（boxes：拉绳 · 绳结 · 钟舌 · 唇的下沿）与伸上去的那一截（overhang，由口沿那一格
        //   并进它自己的轮廓）拼起来，要罩住模板 alarm_bell.json（生成器写的、游戏画的同一份）里每一个元件；伸上去的那一截只许在那一格里
        List<double[]> own = LinerPropShapes.boxes(LinerProp.Kind.ALARM_BELL, null);
        List<double[]> above = LinerPropShapes.overhang(LinerProp.Kind.ALARM_BELL);
        assertFalse(above.isEmpty(), "警钟没有伸上去的那一截：钟身点不中");
        List<double[]> both = new java.util.ArrayList<>(own);
        for (double[] b : above) {
            for (int i = 0; i < 3; i++) {
                assertTrue(0 <= b[i] && b[i] < b[i + 3] && b[i + 3] <= 16, "伸上去的那一截越出了上面那一格：" + java.util.Arrays.toString(b));
            }
            both.add(new double[]{b[0], b[1] + 16, b[2], b[3], b[4] + 16, b[5]});
        }
        Map<String, List<double[]>> pts = elementPoints("/assets/heavyseas/models/block/liner/template/prop/alarm_bell.json");
        assertTrue(pts.size() >= 20, "正向对照：模板里的元件只读到 " + pts.size() + " 块");
        assertEquals(List.of(), uncovered(both, pts), "警钟的轮廓罩不住这几块元件");
        // 正向对照：只有这一格的轮廓（第二轮就是这样）必须罩不住钟身 —— 判据分得开「点得中整口钟」与「只点得中钟口」
        assertFalse(uncovered(own, pts).isEmpty(), "只用这一格的轮廓也罩得住：判据没在量");
        for (LinerProp.Kind k : LinerProp.Kind.values()) {
            if (k != LinerProp.Kind.ALARM_BELL) {
                assertEquals(List.of(), LinerPropShapes.overhang(k), k + " 也伸进了上面那一格");
            }
        }
    }

    /** 模板里每个元件取 27 个点（三个方向各取近两端与正中，离边 1 %），照元件的转动转过去：{元件序号 · 起点: 点}。 */
    private static Map<String, List<double[]>> elementPoints(String resource) throws Exception {
        JsonObject m;
        try (InputStream in = DeckPiecesTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " 不在");
            m = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        Map<String, List<double[]>> out = new java.util.LinkedHashMap<>();
        int i = 0;
        for (JsonElement e : m.getAsJsonArray("elements")) {
            JsonObject el = e.getAsJsonObject();
            double[] f = vec(el.getAsJsonArray("from"));
            double[] t = vec(el.getAsJsonArray("to"));
            JsonObject rot = el.has("rotation") ? el.getAsJsonObject("rotation") : null;
            List<double[]> ps = new java.util.ArrayList<>();
            for (double a : new double[]{0.01, 0.5, 0.99}) {
                for (double b : new double[]{0.01, 0.5, 0.99}) {
                    for (double c : new double[]{0.01, 0.5, 0.99}) {
                        double[] q = {f[0] + (t[0] - f[0]) * a, f[1] + (t[1] - f[1]) * b, f[2] + (t[2] - f[2]) * c};
                        ps.add(rot == null ? q : rotate(q, rot));
                    }
                }
            }
            out.put(i++ + " · " + java.util.Arrays.toString(f), ps);
        }
        return out;
    }

    private static double[] vec(JsonArray a) {
        return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
    }

    /** 元件的转动（与 docs 的 liner_decor._rot_el 同一个公式：绕 origin、按 axis 转 angle 度）。 */
    private static double[] rotate(double[] p, JsonObject rot) {
        double[] o = vec(rot.getAsJsonArray("origin"));
        double r = Math.toRadians(rot.get("angle").getAsDouble());
        double c = Math.cos(r);
        double s = Math.sin(r);
        double x = p[0] - o[0];
        double y = p[1] - o[1];
        double z = p[2] - o[2];
        double[] q = switch (rot.get("axis").getAsString()) {
            case "x" -> new double[]{x, y * c - z * s, y * s + z * c};
            case "y" -> new double[]{x * c + z * s, y, -x * s + z * c};
            default -> new double[]{x * c - y * s, x * s + y * c, z};
        };
        return new double[]{q[0] + o[0], q[1] + o[1], q[2] + o[2]};
    }

    /** 有点落在所有盒子外面的那几块元件。 */
    private static List<String> uncovered(List<double[]> boxes, Map<String, List<double[]>> pts) {
        List<String> out = new java.util.ArrayList<>();
        for (Map.Entry<String, List<double[]>> e : pts.entrySet()) {
            for (double[] q : e.getValue()) {
                boolean in = false;
                for (double[] b : boxes) {
                    in |= b[0] - 1e-6 <= q[0] && q[0] <= b[3] + 1e-6 && b[1] - 1e-6 <= q[1] && q[1] <= b[4] + 1e-6
                            && b[2] - 1e-6 <= q[2] && q[2] <= b[5] + 1e-6;
                }
                if (!in) {
                    out.add(e.getKey());
                    break;
                }
            }
        }
        return out;
    }

    @Test
    void deckChairSeatsNeverMixWithGameSeatsOrTheDrillSkiff() {
        // 对局的位次（Seats）与演习艇的报名（DrillSkiff：坐在 SeatEntity 上、锚点是演习艇）都按 SeatEntity 认座位 —— 躺椅的座位不能是那一种
        assertFalse(SeatEntity.class.isAssignableFrom(DeckChairSeat.class), "躺椅的座位是 SeatEntity 的一种：DrillSkiff 会把坐躺椅的人认成报名");
        assertFalse(DeckChairSeat.class.isAssignableFrom(SeatEntity.class));
        // 正骑着任何东西（对局座位 · 演习艇的座位 · 船）的人右键躺椅不坐：躺椅从不替人起身（起身 = 悄悄退了报名、离了位次）
        assertFalse(LinerProp.Rules.maySit(true, false));
        assertFalse(LinerProp.Rules.maySit(false, true), "旁观者不坐");
        assertTrue(LinerProp.Rules.maySit(false, false), "正向对照：空着手站着的人坐得上去");
        // 座位实体与对局座位同一个尺寸：docs 的骑乘姿势按 0.3 格量（check_java_seat 读同一个数）
        assertEquals(0.3f, DeckChairSeat.SIZE);
    }

    @Test
    void theSeatSitsOnTheCushionInTheHeadCell() {
        // 躺椅 1 × 2：点中的是脚那一格（同床），头往远处长；座位在头那一格、离脚那一格中心 13.5 像素（往头那边）、左右正中、离甲板 3.05 像素
        for (Direction f : HORIZONTAL) {
            double[] s = LinerProp.Rules.chairSeat(LinerProp.Part.FOOT, f);
            BlockPos head = LinerProp.Rules.offset(BlockPos.ORIGIN, LinerProp.Part.FOOT, LinerProp.Part.HEAD, f);
            assertEquals(BlockPos.ORIGIN.offset(f.getOpposite()), head, "头那一格在脚那一格的远处（朝向的反方向）");
            assertTrue(Math.floor(s[0]) == head.getX() && Math.floor(s[2]) == head.getZ(), f + " 座位不在头那一格：" + s[0] + " " + s[2]);
            double back = 13.5 / 16;
            assertEquals(0.5 - f.getOffsetX() * back, s[0], 1e-9, f + " x");
            assertEquals(0.5 - f.getOffsetZ() * back, s[2], 1e-9, f + " z");
            assertEquals(3.05 / 16, s[1], 1e-9);
            // 从头那一格算也是同一个点
            double[] t = LinerProp.Rules.chairSeat(LinerProp.Part.HEAD, f);
            assertEquals(s[0] - head.getX(), t[0], 1e-9);
            assertEquals(s[2] - head.getZ(), t[2], 1e-9);
        }
    }

    @Test
    void hollowMastShaftsStandAloneAndTheDoorIsOnePiece() {
        assertEquals(EnumSet.of(LinerProp.Part.SHAFT, LinerProp.Part.DOOR_LOWER, LinerProp.Part.DOOR_UPPER),
                LinerProp.Rules.parts(LinerProp.Kind.HOLLOW_MAST));
        assertEquals(LinerProp.Part.DOOR_LOWER, LinerProp.Rules.anchor(LinerProp.Kind.HOLLOW_MAST));
        assertEquals(EnumSet.of(LinerProp.Part.SHAFT), LinerProp.Rules.piece(LinerProp.Kind.HOLLOW_MAST, LinerProp.Part.SHAFT));
        Set<LinerProp.Part> door = EnumSet.of(LinerProp.Part.DOOR_LOWER, LinerProp.Part.DOOR_UPPER);
        assertEquals(door, LinerProp.Rules.piece(LinerProp.Kind.HOLLOW_MAST, LinerProp.Part.DOOR_UPPER));
        for (Direction f : HORIZONTAL) {
            // 竖井一格谁也不挨：上下叠着的竖井、门，拆了哪一格都不连累它
            for (Direction d : Direction.values()) {
                assertNull(LinerProp.Rules.partnerAt(LinerProp.Part.SHAFT, d, f,
                        LinerProp.Rules.piece(LinerProp.Kind.HOLLOW_MAST, LinerProp.Part.SHAFT)), "竖井 " + d);
            }
            // 门：上半在下半正上方，互为搭档
            assertEquals(LinerProp.Part.DOOR_UPPER, LinerProp.Rules.partnerAt(LinerProp.Part.DOOR_LOWER, Direction.UP, f, door));
            assertEquals(LinerProp.Part.DOOR_LOWER, LinerProp.Rules.partnerAt(LinerProp.Part.DOOR_UPPER, Direction.DOWN, f, door));
            assertNull(LinerProp.Rules.partnerAt(LinerProp.Part.DOOR_LOWER, Direction.DOWN, f, door));
        }
        // 正向对照：别的种类一件就是全部的块（piece 只对空心桅杆分家）
        assertEquals(LinerProp.Rules.parts(LinerProp.Kind.BED), LinerProp.Rules.piece(LinerProp.Kind.BED, LinerProp.Part.FOOT));
    }

    /** 人（0.6 格见方 = 9.6 像素）站在 (cx, cz) 那一点、y0..y1 高：碰不碰得到这些盒子。 */
    private static boolean blocked(List<double[]> boxes, double cx, double cz, double y0, double y1) {
        double h = 4.8;
        for (double[] b : boxes) {
            if (cx - h < b[3] && cx + h > b[0] && cz - h < b[5] && cz + h > b[2] && y0 < b[4] && y1 > b[1]) {
                return true;
            }
        }
        return false;
    }

    @Test
    void anOpenDoorLetsYouInAndTheShaftIsWideEnoughToClimb() {
        // 碰撞箱就是轮廓（空心桅杆没有另写）：竖井里那一列放得下人；门开着，人从门洞走得进来（门槛 2 像素一步迈得上），关着进不来
        LinerProp.Part lo = LinerProp.Part.DOOR_LOWER;
        LinerProp.Part up = LinerProp.Part.DOOR_UPPER;
        for (LinerProp.Part p : LinerProp.Rules.parts(LinerProp.Kind.HOLLOW_MAST)) {
            for (boolean open : new boolean[]{false, true}) {
                assertFalse(blocked(LinerPropShapes.mast(p, open), 8, 7.25, 0, 16), p + " 开着 " + open + "：竖井里那一列放不下人");
            }
        }
        // 走进门洞（门在模型北面 z 0）：人站在门洞正中、z 从门外一路挪到竖井里，下半格从门槛顶（2）起、上半格到门楣（15）为止
        for (double z = -6; z <= 7.25; z += 0.25) {
            assertFalse(blocked(LinerPropShapes.mast(lo, true), 8, z, 2, 16), "门开着，下半格在 z " + z + " 被挡");
            assertFalse(blocked(LinerPropShapes.mast(up, true), 8, z, 0, 14.8), "门开着，上半格在 z " + z + " 被挡（人高 1.8 格 = 门槛上 28.8 像素）");
        }
        assertTrue(blocked(LinerPropShapes.mast(lo, false), 8, 2, 2, 16), "门关着，下半格进得去");
        assertTrue(blocked(LinerPropShapes.mast(up, false), 8, 2, 0, 14.8), "门关着，上半格进得去");
        // 门洞高：门槛顶 2 → 门楣底 16 + 15 = 1.81 格，人 1.8 格
        assertTrue(16 + 15 - 2 >= 1.8 * 16);
        // 门开着与关着轮廓真的不一样（门扇换了地方）—— 正向对照：判据分得开两个状态
        assertFalse(LinerPropShapes.mast(lo, true).equals(LinerPropShapes.mast(lo, false)));
    }

    @Test
    void theHollowMastIsClimbable() throws Exception {
        // 能爬靠游戏自带的方块标签 climbable（LivingEntity.isClimbing 只认这个标签）：标签文件里漏了它，竖井就只是一根空管子，游戏里不报错
        try (InputStream in = DeckPiecesTest.class.getResourceAsStream("/data/minecraft/tags/block/climbable.json")) {
            assertNotNull(in, "climbable 标签文件不在");
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"heavyseas:liner_hollow_mast\""), "climbable 标签里没有空心桅杆：" + json);
            assertFalse(json.contains("\"replace\": true"), "标签不许盖掉游戏自带的爬梯、藤蔓");
        }
    }

    private static Set<LinerProp.Part> reachable(LinerProp.Part from, Direction facing, Set<LinerProp.Part> parts) {
        Set<LinerProp.Part> seen = EnumSet.of(from);
        ArrayDeque<LinerProp.Part> todo = new ArrayDeque<>(seen);
        while (!todo.isEmpty()) {
            LinerProp.Part here = todo.pop();
            for (Direction d : Direction.values()) {
                LinerProp.Part p = LinerProp.Rules.partnerAt(here, d, facing, parts);
                if (p != null && seen.add(p)) {
                    todo.push(p);
                }
            }
        }
        return seen;
    }

    @Test
    void theLookoutIsARingAroundTheMast() {
        for (LinerProp.Kind k : List.of(LinerProp.Kind.LOOKOUT_FLOOR, LinerProp.Kind.LOOKOUT_RIM)) {
            Set<LinerProp.Part> ring = LinerProp.Rules.parts(k);
            assertEquals(8, ring.size());
            assertFalse(ring.contains(LinerProp.Part.RING_C), "正中那一格是桅杆，不是这一件的");
            assertEquals(LinerProp.Part.RING_N, LinerProp.Rules.anchor(k));
            for (Direction f : HORIZONTAL) {
                // 一圈八格连成一条（拆一格整件没，一格传一格），每一格正好挨着两格
                assertEquals(ring, reachable(LinerProp.Part.RING_N, f, ring), k + " 朝 " + f + " 断成了几截");
                for (LinerProp.Part here : ring) {
                    int n = 0;
                    for (Direction d : Direction.values()) {
                        n += LinerProp.Rules.partnerAt(here, d, f, ring) != null ? 1 : 0;
                    }
                    assertEquals(2, n, k + " " + here + " 朝 " + f);
                }
                // 点中的是人与桅杆之间那一格：桅杆（正中那一格）在点中那一格的前面一格（人看过去的方向 = 朝向的反方向）
                assertEquals(BlockPos.ORIGIN.offset(f.getOpposite()),
                        LinerProp.Rules.offset(BlockPos.ORIGIN, LinerProp.Part.RING_N, LinerProp.Part.RING_C, f));
            }
        }
    }

    @Test
    void theRimKeepsYouOnTheLookout() {
        // 口沿一圈的碰撞（整件坐标：桅杆中线在 24, 24；口沿那一层的格底 = 台面上 16 像素）：
        //   高 —— 从台面（y −16）到台面上 1.5 格（y 8）：原地起跳 1.25 格够不着；
        //   一圈不漏 —— 每 1° 一个方向，外壁里面那一圈与外面那一圈之间的三个点都在某一块碰撞盒子里：外壁里面在每一面正中离中线 21.5、
        //     在角上 21.92（十六边形），外面 23 / 23.45；取 21.95（贴着角上的里面）· 22.5 · 22.9。❗只取 22.5 的话红测漏得过：
        //     半宽不查「超出边心距」那一版在轴线上 20–22 像素留了一道 6 像素宽的缝，22.5 恰好在缝外面；
        //   不挤人 —— 离中线 19 像素那一点都在盒子外（站人的那一圈留得够宽）
        double top = Double.NEGATIVE_INFINITY;
        double bottom = Double.POSITIVE_INFINITY;
        int boxes = 0;
        java.util.List<double[]> whole = new java.util.ArrayList<>();
        for (LinerProp.Part p : LinerProp.Rules.parts(LinerProp.Kind.LOOKOUT_RIM)) {
            for (double[] b : LinerPropShapes.collision(LinerProp.Kind.LOOKOUT_RIM, p)) {
                whole.add(new double[]{b[0] + p.x * 16, b[1], b[2] + p.z * 16, b[3] + p.x * 16, b[4], b[5] + p.z * 16});
                top = Math.max(top, b[4]);
                bottom = Math.min(bottom, b[1]);
                boxes++;
            }
        }
        assertTrue(boxes > 8, "正向对照：口沿的碰撞盒子一块都没量到（" + boxes + "）");
        assertEquals(24 - 16, top, 1e-9, "碰撞箱顶应在台面上 1.5 格");
        assertTrue(top + 16 >= 1.5 * 16, "翻得出去：口沿碰撞箱只到台面上 " + (top + 16) / 16 + " 格");
        assertEquals(-16, bottom, 1e-9, "碰撞箱要往下伸到台面");
        for (int a = 0; a < 360; a++) {
            double c = Math.cos(Math.toRadians(a));
            double s = Math.sin(Math.toRadians(a));
            for (double r : new double[]{21.95, 22.5, 22.9}) {
                assertTrue(inside(whole, 24 + r * c, 24 + r * s), a + "° 那一边、离中线 " + r + " 像素，口沿漏了一个口");
            }
            assertFalse(inside(whole, 24 + 19 * c, 24 + 19 * s), a + "° 那一边口沿的碰撞往里挤到了离桅杆 19 像素");
        }
    }

    private static boolean inside(List<double[]> boxes, double x, double z) {
        for (double[] b : boxes) {
            if (b[0] <= x && x <= b[3] && b[2] <= z && z <= b[5] && b[1] <= -15 && b[4] >= 7) {
                return true;
            }
        }
        return false;
    }
}
