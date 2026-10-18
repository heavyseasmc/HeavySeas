package io.github.heavyseasmc.mod.world.liner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.heavyseasmc.mod.ui.LiftPanel;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static io.github.heavyseasmc.mod.world.liner.LinerLift.DECK;
import static io.github.heavyseasmc.mod.world.liner.LinerLift.FACING;
import static io.github.heavyseasmc.mod.world.liner.LinerLift.FLOOR;
import static io.github.heavyseasmc.mod.world.liner.LinerLift.HALF;
import static io.github.heavyseasmc.mod.world.liner.LinerLift.LIT;
import static io.github.heavyseasmc.mod.world.liner.LinerLift.OPEN;
import static io.github.heavyseasmc.mod.world.liner.LinerLift.SIDE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 电梯（{@link LinerLift}）：四种方块的默认状态 · 左右与几格（轿厢范围、指针盘、召唤按钮找门、面板归属）· 扫一列得楼层表 ·
 * 叫车与乘梯的时间表 · 门自己合上的计时 · 乘梯请求的核对 · 朝向与轮廓 · 每个状态的模板都在。
 *
 * <p>摆放的例子取主会话给生成器的约定：船头四部的门在 x 226、朝东，左扇在 z 大（13 · 16 · 19 · 22），轿厢 x 224–225；
 * 召唤按钮 x 227、z 14 · 17 · 20 · 23，比门下半高一格；面板在轿厢里 x 225、左扇那一列、朝北。
 * 船尾二等电梯门在 z 16、朝南，左扇在 x 81，轿厢 z 14–15，召唤按钮 x 80 · z 17，面板 x 81 · z 15、朝东。
 */
final class LinerLiftRulesTest {

    private static final Path TEMPLATES = Path.of("src", "main", "resources", "assets", "heavyseas", "models", "block",
            "liner", "template");
    private static final Path LANG = Path.of("src", "main", "resources", "assets", "heavyseas", "lang");

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
    }

    private static BlockState gate(DoubleBlockHalf half, LinerLift.Deck deck, Direction facing) {
        return LinerLift.GATE.getDefaultState().with(HALF, half).with(DECK, deck).with(FACING, facing);
    }

    private static BlockState gate(DoubleBlockHalf half, LinerLift.Side side, Direction facing) {
        return LinerLift.GATE.getDefaultState().with(HALF, half).with(SIDE, side).with(FACING, facing);
    }

    // ---------------------------------------------------------------- 默认状态（手放 · /setblock 不带属性 · 物品图标）

    @Test
    @DisplayName("手放的门（默认状态）是 deck=none：面板上照位置编号，不冒充某一层甲板")
    void defaultDeckIsNone() {
        BlockState d = LinerLift.GATE.getDefaultState();
        assertEquals(LinerLift.Deck.NONE, d.get(DECK), "默认状态的 deck 应是 none（枚举第一个常量）—— 手放 · /setblock 不带属性 · 物品图标都是它");
        assertEquals(DoubleBlockHalf.LOWER, d.get(HALF));
        assertEquals(LiftPanel.UNNAMED, LinerLift.Deck.NONE.asString(), "面板认 none 的那个名字要与 Deck.NONE 对得上");
    }

    @Test
    @DisplayName("栅门默认合着（open=false：布尔属性不写默认就是 true）、是左扇")
    void gateDefaultsClosedAndLeft() {
        BlockState d = LinerLift.GATE.getDefaultState();
        assertFalse(d.get(OPEN), "栅门的默认状态应是合着 —— 布尔属性的取值次序 true 在前，不显式写 false 默认就是开着");
        assertEquals(LinerLift.Side.LEFT, d.get(SIDE), "栅门的默认状态应是左扇（手放的那一块是左扇，另一扇往右手放）");
    }

    @Test
    @DisplayName("指针盘默认指着最低那一层（floor=0）、是左块")
    void dialDefaultsToTheLowestFloor() {
        BlockState d = LinerLift.DIAL.getDefaultState();
        assertEquals(0, d.get(FLOOR), "指针盘的默认状态应指着 0 层");
        assertEquals(LinerLift.Side.LEFT, d.get(SIDE), "指针盘的默认状态应是左块");
    }

    @Test
    @DisplayName("召唤按钮默认不亮（lit=false）")
    void callDefaultsUnlit() {
        assertFalse(LinerLift.CALL.getDefaultState().get(LIT), "召唤按钮的默认状态应是不亮 —— 不显式写 false 默认就是亮着");
    }

    @Test
    @DisplayName("每一层甲板 · 新的几句话 · 新方块在两份 lang 里都有（楼层名的键是拼出来的，checkLangKeys 扫不到）")
    void everyDeckHasANameInBothLanguages() throws IOException {
        int checked = 0;
        for (String lang : new String[]{"zh_cn", "en_us"}) {
            JsonObject table = JsonParser.parseString(Files.readString(LANG.resolve(lang + ".json"), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (LinerLift.Deck deck : LinerLift.Deck.values()) {
                if (deck == LinerLift.Deck.NONE) {
                    continue;
                }
                String key = "heavyseas.lift.deck." + deck.asString();
                assertTrue(table.has(key), lang + " 里没有 " + key);
                checked++;
            }
            for (String key : List.of("heavyseas.lift.floor", "heavyseas.keys.go", "heavyseas.lift.coming", "heavyseas.lift.no_lift",
                    "heavyseas.lift.arrived", "heavyseas.lift.blocked")) {
                assertTrue(table.has(key), lang + " 里没有 " + key);
            }
        }
        assertEquals(2 * (LinerLift.Deck.values().length - 1), checked, "正向对照：七层甲板 × 两种语言都查到了");
    }

    // ---------------------------------------------------------------- 左右与几格

    @Test
    @DisplayName("左右：门朝东时左扇在南（z 大）、门朝南时左扇在西（x 小）；模板里左扇的外侧（x 0）转过去正落在左手那一边")
    void leftAndRight() {
        assertEquals(Direction.SOUTH, LinerLift.Rules.leftOf(Direction.EAST));
        assertEquals(Direction.NORTH, LinerLift.Rules.rightOf(Direction.EAST));
        assertEquals(Direction.WEST, LinerLift.Rules.leftOf(Direction.SOUTH));
        assertEquals(Direction.EAST, LinerLift.Rules.rightOf(Direction.SOUTH));
        for (Direction f : Direction.Type.HORIZONTAL) {
            double[] outer = LinerLooks.rotateY(0, 8, LinerLift.Rules.yaw(f));
            Direction left = LinerLift.Rules.leftOf(f);
            assertEquals(8 + 8 * left.getOffsetX(), outer[0], 1e-9, f + "：模板外侧 x");
            assertEquals(8 + 8 * left.getOffsetZ(), outer[1], 1e-9, f + "：模板外侧 z");
        }
    }

    @Test
    @DisplayName("一块栅门 → 它那一部在这一层的列（左扇下半块）：上下左右四块都认到同一格")
    void columnOfEveryLeaf() {
        BlockPos col = new BlockPos(226, 50, 13);
        Direction f = Direction.EAST;
        BlockPos right = col.offset(LinerLift.Rules.rightOf(f));
        assertEquals(new BlockPos(226, 50, 12), right);
        assertEquals(col, LinerLift.Rules.columnOf(col, gate(DoubleBlockHalf.LOWER, LinerLift.Side.LEFT, f)));
        assertEquals(col, LinerLift.Rules.columnOf(col.up(), gate(DoubleBlockHalf.UPPER, LinerLift.Side.LEFT, f)));
        assertEquals(col, LinerLift.Rules.columnOf(right, gate(DoubleBlockHalf.LOWER, LinerLift.Side.RIGHT, f)));
        assertEquals(col, LinerLift.Rules.columnOf(right.up(), gate(DoubleBlockHalf.UPPER, LinerLift.Side.RIGHT, f)));
    }

    @Test
    @DisplayName("轿厢范围：门那一排 + 背后两排、两格宽、三格高（船头朝东一部 · 船尾朝南一部）")
    void carZone() {
        assertEquals(new Box(224, 50, 12, 227, 53, 14), LinerLift.Rules.carZone(new BlockPos(226, 50, 13), Direction.EAST));
        assertEquals(new Box(81, 30, 14, 83, 33, 17), LinerLift.Rules.carZone(new BlockPos(81, 30, 16), Direction.SOUTH));
        Box z = LinerLift.Rules.carZone(new BlockPos(226, 50, 13), Direction.EAST);
        assertTrue(z.contains(new Vec3d(224.3, 50.0, 12.7)), "最里面那一排、脚踩在地板上");
        assertTrue(z.contains(new Vec3d(226.9, 50.0, 13.5)), "门洞那一排");
        assertFalse(z.contains(new Vec3d(227.1, 50.0, 13.5)), "门外（电梯厅）一步");
        assertFalse(z.contains(new Vec3d(225.5, 50.0, 14.1)), "隔壁那一列（另一部的护墙）");
        assertFalse(z.contains(new Vec3d(225.5, 53.0, 13.0)), "楼上");
    }

    @Test
    @DisplayName("指针盘在左右两扇正上方两格；人带到别的层：同一个相对位置")
    void dialsAndCarry() {
        assertEquals(List.of(new BlockPos(226, 52, 13), new BlockPos(226, 52, 12)),
                LinerLift.Rules.dialPositions(new BlockPos(226, 50, 13), Direction.EAST));
        assertEquals(List.of(new BlockPos(81, 32, 16), new BlockPos(82, 32, 16)),
                LinerLift.Rules.dialPositions(new BlockPos(81, 30, 16), Direction.SOUTH));
        Vec3d moved = LinerLift.Rules.carry(new Vec3d(224.25, 50.0, 12.75), new BlockPos(226, 50, 13), Direction.EAST,
                new BlockPos(226, 71, 13), Direction.EAST);
        assertEquals(new Vec3d(224.25, 71.0, 12.75), moved);
    }

    @Test
    @DisplayName("人带到门朝向相反的那一层（艇甲板那一站开进穹顶厅）：落在那一层轿厢里同一个相对位置，视角转 180°")
    void carryAcrossFacings() {
        // E 甲板：门朝东，左扇 (226, 50, 13)、右扇 z 12，轿厢在门西边 x 224–225；人站在轿厢里靠里、靠右扇那一角
        // 艇甲板：门朝西、同一条井道（z 12–13），左右对调 —— 左扇 (226, 71, 12)、右扇 z 13，轿厢在门东边 x 227–228
        Vec3d moved = LinerLift.Rules.carry(new Vec3d(224.25, 50.0, 12.75), new BlockPos(226, 50, 13), Direction.EAST,
                new BlockPos(226, 71, 12), Direction.WEST);
        assertEquals(new Vec3d(228.75, 71.0, 13.25), moved, "绕井道中线 (226.5, 13) 转半圈");
        assertTrue(LinerLift.Rules.carZone(new BlockPos(226, 71, 12), Direction.WEST).contains(moved), "落点要在那一层的轿厢范围里");
        assertEquals(90f - 180f, LinerLift.Rules.carryYaw(90f, Direction.EAST, Direction.WEST), 1e-6);
        assertEquals(30f, LinerLift.Rules.carryYaw(30f, Direction.EAST, Direction.EAST), 1e-6);
    }

    /** 一小块假世界：只放了几块门。 */
    private static Map<BlockPos, BlockState> world() {
        Map<BlockPos, BlockState> w = new HashMap<>();
        // 船头四部（朝东），E 甲板门下半在 y 50
        for (int z : new int[]{13, 16, 19, 22}) {
            put(w, new BlockPos(226, 50, z), Direction.EAST);
        }
        // 船尾二等电梯（朝南），门下半在 y 30
        put(w, new BlockPos(81, 30, 16), Direction.SOUTH);
        return w;
    }

    private static void put(Map<BlockPos, BlockState> w, BlockPos left, Direction f) {
        BlockPos right = left.offset(LinerLift.Rules.rightOf(f));
        w.put(left, gate(DoubleBlockHalf.LOWER, LinerLift.Side.LEFT, f));
        w.put(left.up(), gate(DoubleBlockHalf.UPPER, LinerLift.Side.LEFT, f));
        w.put(right, gate(DoubleBlockHalf.LOWER, LinerLift.Side.RIGHT, f));
        w.put(right.up(), gate(DoubleBlockHalf.UPPER, LinerLift.Side.RIGHT, f));
    }

    private static BlockState at(Map<BlockPos, BlockState> w, BlockPos p) {
        return w.getOrDefault(p, Blocks.AIR.getDefaultState());
    }

    @Test
    @DisplayName("召唤按钮找门：从按钮背后的墙沿墙先往观者右手 ≤ 2 格找左扇、再往左手 ≤ 2 格找右扇，同高 ±1")
    void callButtonFindsItsGate() {
        Map<BlockPos, BlockState> w = world();
        // 船头：四个按钮各叫各的那一部（不叫左手边隔壁那一部）
        for (int z : new int[]{13, 16, 19, 22}) {
            assertEquals(new BlockPos(226, 50, z), LinerLift.Rules.callGate(new BlockPos(227, 51, z + 1), Direction.EAST, p -> at(w, p)),
                    "z " + (z + 1) + " 的按钮");
        }
        // 按钮高一格 / 低一格 / 与门下半同高都认得出；落在上半块认下半块
        assertEquals(new BlockPos(226, 50, 13), LinerLift.Rules.callGate(new BlockPos(227, 50, 14), Direction.EAST, p -> at(w, p)));
        assertEquals(new BlockPos(226, 50, 13), LinerLift.Rules.callGate(new BlockPos(227, 52, 14), Direction.EAST, p -> at(w, p)));
        assertEquals(new BlockPos(226, 50, 13), LinerLift.Rules.callGate(new BlockPos(227, 49, 14), Direction.EAST, p -> at(w, p)));
        // 隔一格墙（2 格）也认得出；3 格就不认
        assertEquals(new BlockPos(226, 50, 13), LinerLift.Rules.callGate(new BlockPos(227, 51, 15), Direction.EAST, p -> at(w, p)));
        assertNull(LinerLift.Rules.callGate(new BlockPos(227, 51, 25), Direction.EAST, p -> at(w, p)), "右手 3 格内没有门");
        // 按钮贴反了方向（朝西）：背后那一格不是这排墙
        assertNull(LinerLift.Rules.callGate(new BlockPos(227, 51, 14), Direction.WEST, p -> at(w, p)));
        // 船尾：x 80 · z 17 的按钮叫 x 81 那一扇
        assertEquals(new BlockPos(81, 30, 16), LinerLift.Rules.callGate(new BlockPos(80, 31, 17), Direction.SOUTH, p -> at(w, p)));
        // 贴在门右手边（右扇再往右 1 · 2 格）也认：左右舷两部对称，按钮都贴靠船中线那一侧（ADR-0105）
        assertEquals(new BlockPos(81, 30, 16), LinerLift.Rules.callGate(new BlockPos(83, 31, 17), Direction.SOUTH, p -> at(w, p)));
        assertEquals(new BlockPos(81, 30, 16), LinerLift.Rules.callGate(new BlockPos(84, 31, 17), Direction.SOUTH, p -> at(w, p)));
        assertNull(LinerLift.Rules.callGate(new BlockPos(85, 31, 17), Direction.SOUTH, p -> at(w, p)), "右扇往右 3 格就不认");
        // 船头最右那一部（z 22，右扇 z 21）的右手边 z 20 那一格墙：先往右找左扇（z 19 是隔壁那一部的左扇）—— 左扇优先，认隔壁
        assertEquals(new BlockPos(226, 50, 19), LinerLift.Rules.callGate(new BlockPos(227, 51, 20), Direction.EAST, p -> at(w, p)));
        // 反过来（点亮按钮用）：候选格里有那颗按钮，而且候选格按 callGate 认回来正是这扇门
        BlockPos gate = new BlockPos(226, 50, 16);
        List<BlockPos> candidates = LinerLift.Rules.callCandidates(gate, Direction.EAST);
        assertTrue(candidates.contains(new BlockPos(227, 51, 17)), candidates.toString());
        assertTrue(candidates.contains(new BlockPos(227, 51, 14)), "右手边那一格也在候选里：" + candidates);
        int back = 0;
        for (BlockPos c : candidates) {
            if (gate.equals(LinerLift.Rules.callGate(c, Direction.EAST, p -> at(w, p)))) {
                back++;
            }
        }
        assertTrue(back >= 4, "左手边那几格（隔壁没挤着时）都认得回这扇门：" + back);
        // 船尾那一部两边都没挤着：候选格放上按钮，每一格都认得回这扇门
        BlockPos aft = new BlockPos(81, 30, 16);
        List<BlockPos> aftCandidates = LinerLift.Rules.callCandidates(aft, Direction.SOUTH);
        for (BlockPos c : aftCandidates) {
            assertEquals(aft, LinerLift.Rules.callGate(c, Direction.SOUTH, p -> at(w, p)), "候选格 " + c);
        }
    }

    @Test
    @DisplayName("面板归属：面板那一格在哪一部某一层的轿厢范围里")
    void panelBelongsToTheCarItIsIn() {
        Map<BlockPos, BlockState> w = world();
        for (int z : new int[]{13, 16, 19, 22}) {
            assertEquals(new BlockPos(226, 50, z), LinerLift.Rules.panelGate(new BlockPos(225, 51, z), p -> at(w, p)), "z " + z + " 的面板");
            assertEquals(new BlockPos(226, 50, z), LinerLift.Rules.panelGate(new BlockPos(224, 52, z - 1), p -> at(w, p)),
                    "z " + (z - 1) + " 最里面、顶上那一格");
        }
        assertEquals(new BlockPos(81, 30, 16), LinerLift.Rules.panelGate(new BlockPos(81, 31, 15), p -> at(w, p)));
        assertNull(LinerLift.Rules.panelGate(new BlockPos(225, 51, 14), p -> at(w, p)), "两部之间的护墙那一列不属于谁");
        assertNull(LinerLift.Rules.panelGate(new BlockPos(225, 53, 13), p -> at(w, p)), "轿厢顶上一层");
        assertNull(LinerLift.Rules.panelGate(new BlockPos(228, 51, 13), p -> at(w, p)), "电梯厅里");
    }

    // ---------------------------------------------------------------- 扫一列

    @Test
    @DisplayName("扫一条井道：两格里的左扇下半块、且那一对门正好占这条井道，从低到高；门朝向反过来（左扇落在另一格）照样是这一部")
    void scanFindsLowerHalvesInOrder() {
        // 井道 = x 226 · z 12–13
        LinerLift.Shaft shaft = LinerLift.Rules.shaftOf(new BlockPos(226, 50, 13), Direction.EAST);
        assertEquals(new LinerLift.Shaft(226, 12, Direction.Axis.Z), shaft);
        assertEquals(shaft, LinerLift.Rules.shaftOf(new BlockPos(226, 71, 12), Direction.WEST), "门朝西、左扇在 z 12：同一条井道");
        assertNotEquals(shaft, LinerLift.Rules.shaftOf(new BlockPos(226, 50, 12), Direction.EAST), "左扇在 z 12 朝东：右扇在 z 11，另一条");
        Map<BlockPos, BlockState> w = new HashMap<>();
        put(w, new BlockPos(226, 10, 13), Direction.EAST, LinerLift.Deck.E);          // 朝东：左扇 z 13
        put(w, new BlockPos(226, 40, 12), Direction.WEST, LinerLift.Deck.BOAT);       // 朝西：左扇 z 12（艇甲板那一站）
        put(w, new BlockPos(226, -5, 13), Direction.EAST, LinerLift.Deck.NONE);
        put(w, new BlockPos(226, 30, 12), Direction.EAST, LinerLift.Deck.B);          // 左扇在井道里、右扇在 z 11：不是这一部
        put(w, new BlockPos(226, 35, 13), Direction.SOUTH, LinerLift.Deck.A);         // 朝南：右扇在 x 227，不是这一部
        w.put(new BlockPos(226, 20, 13), Blocks.STONE.getDefaultState());
        w.put(new BlockPos(226, 25, 12), LinerDoors.CABIN.getDefaultState());
        w.put(new BlockPos(226, 33, 13), gate(DoubleBlockHalf.UPPER, LinerLift.Deck.D, Direction.EAST));        // 落单的上半块
        w.put(new BlockPos(226, 37, 12), gate(DoubleBlockHalf.LOWER, LinerLift.Deck.D, Direction.EAST).with(SIDE, LinerLift.Side.RIGHT));  // 落单的右扇
        java.util.Set<Integer> asked = new java.util.TreeSet<>();
        List<LinerLift.Floor> floors = LinerLift.Rules.scan(shaft, -64, 320, p -> {
            asked.add(p.getY());
            return at(w, p);
        });
        assertEquals(List.of(new LinerLift.Floor(-5, LinerLift.Deck.NONE, Direction.EAST, 226, 13),
                new LinerLift.Floor(10, LinerLift.Deck.E, Direction.EAST, 226, 13),
                new LinerLift.Floor(40, LinerLift.Deck.BOAT, Direction.WEST, 226, 12)), floors);
        assertEquals(384, asked.size(), "从世界底扫到顶，每一高度都问");
        assertEquals(new BlockPos(226, 40, 12), floors.get(2).gate());
        assertEquals(1, LinerLift.Rules.indexOf(floors, 10));
        assertEquals(-1, LinerLift.Rules.indexOf(floors, 11), "上半块那一格不是一层");
        // 楼层名：写明的叫甲板名，none 照位置编号（最低那一扇是 1 层）
        assertEquals("heavyseas.lift.floor", key(LinerLift.Rules.name(floors, 0)));
        assertEquals(1, ((net.minecraft.text.TranslatableTextContent) LinerLift.Rules.name(floors, 0).getContent()).getArgs()[0]);
        assertEquals("heavyseas.lift.deck.e", key(LinerLift.Rules.name(floors, 1)));
        assertEquals("heavyseas.lift.deck.boat", key(LinerLift.Rules.name(floors, 2)));
    }

    private static void put(Map<BlockPos, BlockState> w, BlockPos left, Direction f, LinerLift.Deck deck) {
        put(w, left, f);
        for (BlockPos p : List.of(left, left.up(), left.offset(LinerLift.Rules.rightOf(f)), left.offset(LinerLift.Rules.rightOf(f)).up())) {
            w.put(p, w.get(p).with(DECK, deck));
        }
    }

    private static String key(net.minecraft.text.Text text) {
        return ((net.minecraft.text.TranslatableTextContent) text.getContent()).getKey();
    }

    // ---------------------------------------------------------------- 时间表

    @Test
    @DisplayName("叫车 / 乘梯的时间表：每过一层 20 tick，指针一格一格走，到了停在目标层")
    void timetable() {
        assertEquals(20, LinerLift.TICKS_PER_FLOOR);
        assertEquals(100, LinerLift.Rules.travelTicks(0, 5));
        assertEquals(60, LinerLift.Rules.travelTicks(4, 1));
        assertEquals(0, LinerLift.Rules.travelTicks(2, 2));
        // 1 层 → 4 层（往上）：每一 tick 在第几层，变化正好发生在 20 · 40 · 60
        List<int[]> changes = new ArrayList<>();
        int last = LinerLift.Rules.positionAt(1, 4, 0);
        assertEquals(1, last, "出发那一刻还在出发层");
        for (int t = 1; t <= 80; t++) {
            int p = LinerLift.Rules.positionAt(1, 4, t);
            if (p != last) {
                changes.add(new int[]{t, p});
                last = p;
            }
        }
        assertEquals(List.of("20→2", "40→3", "60→4"), changes.stream().map(c -> c[0] + "→" + c[1]).toList());
        // 往下同样一格一格；过了到站时刻停在目标、不越过
        assertEquals(List.of(4, 4, 3, 2, 1, 1), List.of(LinerLift.Rules.positionAt(4, 1, 0), LinerLift.Rules.positionAt(4, 1, 19),
                LinerLift.Rules.positionAt(4, 1, 20), LinerLift.Rules.positionAt(4, 1, 40), LinerLift.Rules.positionAt(4, 1, 60),
                LinerLift.Rules.positionAt(4, 1, 999)));
        // 指针只有 8 档：高出去的楼层都指在 7
        assertEquals(List.of(0, 3, 7, 7), List.of(LinerLift.Rules.dial(0), LinerLift.Rules.dial(3), LinerLift.Rules.dial(7),
                LinerLift.Rules.dial(9)));
    }

    @Test
    @DisplayName("门自己合上：没人 100 tick 合上；别的层在叫车时开满 40 tick 就合上去接")
    void autoCloseTiming() {
        assertEquals(100, LinerLift.DOOR_HOLD_TICKS);
        assertFalse(LinerLift.Rules.shouldClose(1000, 1099, false));
        assertTrue(LinerLift.Rules.shouldClose(1000, 1100, false));
        assertFalse(LinerLift.Rules.shouldClose(1000, 1039, true));
        assertTrue(LinerLift.Rules.shouldClose(1000, 1040, true));
    }

    @Test
    @DisplayName("下一个去接哪一层：最近的叫车层，一样近取低的；叫车的层都没了回 MIN_VALUE")
    void nextCall() {
        List<LinerLift.Floor> f = List.of(new LinerLift.Floor(10, LinerLift.Deck.E, Direction.EAST, 0, 0),
                new LinerLift.Floor(20, LinerLift.Deck.D, Direction.EAST, 0, 0), new LinerLift.Floor(30, LinerLift.Deck.B, Direction.EAST, 0, 0),
                new LinerLift.Floor(40, LinerLift.Deck.A, Direction.EAST, 0, 0), new LinerLift.Floor(50, LinerLift.Deck.BOAT, Direction.EAST, 0, 0));
        assertEquals(40, LinerLift.Rules.nextCall(f, 30, new TreeSet<>(Set.of(10, 40))));
        assertEquals(20, LinerLift.Rules.nextCall(f, 30, new TreeSet<>(Set.of(20, 40))), "一样近取低的");
        assertEquals(Integer.MIN_VALUE, LinerLift.Rules.nextCall(f, 30, new TreeSet<>(Set.of(99))));
    }

    // ---------------------------------------------------------------- 请求核对

    private static final List<LinerLift.Floor> FLOORS = List.of(
            new LinerLift.Floor(10, LinerLift.Deck.E, Direction.SOUTH, 0, 0),
            new LinerLift.Floor(20, LinerLift.Deck.D, Direction.SOUTH, 0, 0),
            new LinerLift.Floor(40, LinerLift.Deck.BOAT, Direction.SOUTH, 0, 0));

    /** 一份能过的请求：人在 E 甲板那一层的轿厢里、轿厢停在这一层，去艇甲板（第 2 层，y 40），上一次是 1 秒以前。 */
    private static LinerLift.Rules.Request req(boolean spectator, boolean voyage, long last, boolean gateHere, boolean inCar,
                                               boolean carHere, int target, int targetY) {
        return new LinerLift.Rules.Request(spectator, voyage, 1000, last, gateHere, inCar, carHere,
                gateHere ? FLOORS : List.of(), 10, target, targetY);
    }

    private static LinerLift.Rules.Verdict check(LinerLift.Rules.Request r) {
        return LinerLift.Rules.check(r);
    }

    @Test
    @DisplayName("核对：一份正常的请求放行；从没乘过的人不受频率限制")
    void okRequestPasses() {
        assertEquals(LinerLift.Rules.Verdict.OK, check(req(false, false, 1000 - LinerLift.COOLDOWN_TICKS, true, true, true, 2, 40)));
        assertEquals(LinerLift.Rules.Verdict.OK, check(req(false, false, Long.MIN_VALUE, true, true, true, 2, 40)));
        assertEquals(LinerLift.Rules.Verdict.TOO_SOON, check(req(false, false, 1000, true, true, true, 2, 40)));
    }

    @Test
    @DisplayName("核对：不在轿厢里 · 轿厢不在这一层 · 门没了 · 目标不在这一列 · 下标越界 · 就是这一层，各自拒")
    void refusals() {
        long ok = 1000 - LinerLift.COOLDOWN_TICKS;
        assertEquals(LinerLift.Rules.Verdict.NOT_IN_CAR, check(req(false, false, ok, true, false, true, 2, 40)));
        assertEquals(LinerLift.Rules.Verdict.CAR_AWAY, check(req(false, false, ok, true, true, false, 2, 40)));
        assertEquals(LinerLift.Rules.Verdict.NOT_A_GATE, check(req(false, false, ok, false, true, true, 2, 40)));
        // 目标不在同一列：客户端以为第 2 层在 y 55（面板开着时这一列变了）—— 不把人送去 y 40
        assertEquals(LinerLift.Rules.Verdict.NO_SUCH_FLOOR, check(req(false, false, ok, true, true, true, 2, 55)));
        assertEquals(LinerLift.Rules.Verdict.NO_SUCH_FLOOR, check(req(false, false, ok, true, true, true, 3, 40)));
        assertEquals(LinerLift.Rules.Verdict.NO_SUCH_FLOOR, check(req(false, false, ok, true, true, true, -1, 40)));
        assertEquals(LinerLift.Rules.Verdict.SAME_FLOOR, check(req(false, false, ok, true, true, true, 0, 10)));
        assertEquals(LinerLift.Rules.Verdict.SPECTATOR, check(req(true, false, ok, true, true, true, 2, 40)));
        assertEquals(LinerLift.Rules.Verdict.IN_VOYAGE, check(req(false, true, ok, true, true, true, 2, 40)));
        assertEquals(LinerLift.Rules.Verdict.TOO_SOON, check(req(false, false, 990, true, true, true, 2, 40)), "同一人 1 秒内只处理一次");
    }

    // ---------------------------------------------------------------- 朝向、模板与轮廓

    @Test
    @DisplayName("模板正面（南）转到各朝向之后落在 facing 那一边")
    void yawTurnsTheFrontToTheFacing() {
        Map<Direction, double[]> side = Map.of(Direction.SOUTH, new double[]{8, 16}, Direction.WEST, new double[]{0, 8},
                Direction.NORTH, new double[]{8, 0}, Direction.EAST, new double[]{16, 8});
        for (Direction f : Direction.Type.HORIZONTAL) {
            assertArrayEquals(side.get(f), LinerLooks.rotateY(8, 16, LinerLift.Rules.yaw(f)), 1e-9, f.toString());
        }
    }

    @Test
    @DisplayName("四种方块的每一个状态，样子里写的模板文件都在（指针盘：前四档指针在左块、后四档在右块）")
    void everyStateHasItsTemplate() {
        int checked = 0;
        Set<String> templates = new TreeSet<>();
        for (Block block : LinerLift.all().values()) {
            for (BlockState s : block.getStateManager().getStates()) {
                String t = ((LinerLooks.Styled) block).look(s).template();
                assertTrue(Files.isRegularFile(TEMPLATES.resolve(t + ".json")), s + " 要的模板 " + t + " 不在");
                templates.add(t);
                checked++;
            }
            LinerLooks.Look item = ((LinerLooks.Styled) block).itemLook();
            if (item != null) {
                assertTrue(Files.isRegularFile(TEMPLATES.resolve(item.template() + ".json")), item.template());
            }
        }
        assertEquals(4, LinerLift.all().size());
        assertTrue(checked > 300 && templates.size() == 8 + 10 + 2 + 1, "正向对照：状态 " + checked + " 个，模板 " + templates);
        assertEquals("lift/dial_left_3", LinerLift.Rules.dialTemplate(LinerLift.Side.LEFT, 3));
        assertEquals("lift/dial_right", LinerLift.Rules.dialTemplate(LinerLift.Side.RIGHT, 3));
        assertEquals("lift/dial_left", LinerLift.Rules.dialTemplate(LinerLift.Side.LEFT, 4));
        assertEquals("lift/dial_right_4", LinerLift.Rules.dialTemplate(LinerLift.Side.RIGHT, 4));
    }

    @Test
    @DisplayName("轮廓包得住模板里的每个元件（两扇 × 开合 × 上下；斜条按转轴所在处与前后深度查）；开着时碰撞箱只剩外侧那一叠")
    void outlineCoversTheTemplate() throws IOException {
        int checked = 0;
        for (LinerLift.Side side : LinerLift.Side.values()) {
            for (boolean open : new boolean[]{false, true}) {
                for (DoubleBlockHalf half : DoubleBlockHalf.values()) {
                    double[][] boxes = LinerLift.Rules.gateBoxes(half, open, false);
                    if (side == LinerLift.Side.RIGHT) {
                        boxes = LinerLift.Rules.mirrored(boxes);
                    }
                    checked += covered(LinerLift.Rules.gateTemplate(side, open, half), boxes);
                }
            }
        }
        checked += covered("lift/call_on", LinerLift.Rules.CALL_BOX);
        checked += covered("lift/panel", LinerLift.Rules.PANEL_BOX);
        assertTrue(checked > 60, "正向对照：模板的元件都读到了（" + checked + "）");
        // 开着时人走得过去：左扇的碰撞箱不伸过 x 4，右扇不伸过 x 12（模板坐标）；合着时整片拦着
        for (DoubleBlockHalf half : DoubleBlockHalf.values()) {
            for (double[] b : LinerLift.Rules.gateBoxes(half, true, true)) {
                assertTrue(b[3] <= 4, "开着的碰撞箱 " + java.util.Arrays.toString(b));
            }
            assertEquals(16, LinerLift.Rules.gateBoxes(half, false, true)[0][3], "合着的碰撞箱横贯整格");
        }
        assertEquals(12, LinerLift.Rules.mirrored(LinerLift.Rules.gateBoxes(DoubleBlockHalf.LOWER, true, true))[0][0]);
    }

    private static int covered(String template, double[][] boxes) throws IOException {
        JsonObject model = JsonParser.parseString(Files.readString(TEMPLATES.resolve(template + ".json"), StandardCharsets.UTF_8))
                .getAsJsonObject();
        int n = 0;
        for (JsonElement e : model.getAsJsonArray("elements")) {
            JsonObject el = e.getAsJsonObject();
            double[] from = vec(el.getAsJsonArray("from"));
            double[] to = vec(el.getAsJsonArray("to"));
            if (el.has("rotation")) {
                double[] o = vec(el.getAsJsonObject("rotation").getAsJsonArray("origin"));
                from = new double[]{o[0], o[1], from[2]};
                to = new double[]{o[0], o[1], to[2]};
            }
            boolean inside = false;
            for (double[] b : boxes) {
                inside |= from[0] >= b[0] && from[1] >= b[1] && from[2] >= b[2] && to[0] <= b[3] && to[1] <= b[4] && to[2] <= b[5];
            }
            assertTrue(inside, template + " 的元件 " + el + " 不在轮廓里");
            n++;
        }
        return n;
    }

    private static double[] vec(JsonArray a) {
        return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
    }
}
