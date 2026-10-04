package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 灯与家具（ADR-0063）的纯规则：整件往哪边长、搭档在哪一格、镜像换哪一块。放下去、拆掉、开关在游戏里实测。 */
final class LinerPropRulesTest {

    private static final List<Direction> HORIZONTAL = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
    private static final Set<LinerProp.Part> QUAD = EnumSet.of(LinerProp.Part.NW, LinerProp.Part.NE, LinerProp.Part.SW, LinerProp.Part.SE);
    private static final Set<LinerProp.Part> PAIR = EnumSet.of(LinerProp.Part.WEST, LinerProp.Part.EAST);
    private static final Set<LinerProp.Part> TALL = EnumSet.of(LinerProp.Part.LOWER, LinerProp.Part.UPPER);
    private static final Set<LinerProp.Part> BED = EnumSet.of(LinerProp.Part.FOOT, LinerProp.Part.HEAD);
    private static final Set<LinerProp.Part> SHELF = EnumSet.of(LinerProp.Part.WEST, LinerProp.Part.EAST,
            LinerProp.Part.UPPER_WEST, LinerProp.Part.UPPER_EAST);
    // A 甲板新家具（ADR 草稿 furnish）：壁炉 · 炉上件 3 宽 × 2 高 · 吧台 4 长 × 2 高 · 大棵棕榈三格高
    private static final Set<LinerProp.Part> WIDE = LinerProp.Rules.parts(LinerProp.Kind.FIREPLACE);
    private static final Set<LinerProp.Part> BAR = LinerProp.Rules.parts(LinerProp.Kind.BAR_COUNTER);
    private static final Set<LinerProp.Part> TALL3 = LinerProp.Rules.parts(LinerProp.Kind.PALM_TALL);
    // 魔镜放大到 3 宽 × 3 高（2026-10-04，摆法 C）
    private static final Set<LinerProp.Part> WIDE3 = LinerProp.Rules.parts(LinerProp.Kind.MIRROR);

    @Test
    void mirrorIsThreeByThreeCentredOnTheClickedCellAndEveryCellFindsTheCentre() {
        // 点中的是下面一层正中（左右对称的件，镜子立在人面前），往人的左右手各一格、往上两层。人站在西边、面朝东摆 → 镜面朝西
        BlockPos at = new BlockPos(222, 40, 17);
        Direction facing = Direction.WEST;
        assertEquals(LinerProp.Part.WIDE_MID, LinerProp.Rules.anchor(LinerProp.Kind.MIRROR));
        assertEquals(9, WIDE3.size());
        Set<BlockPos> cells = new HashSet<>();
        for (LinerProp.Part p : WIDE3) {
            BlockPos q = LinerProp.Rules.offset(at, LinerProp.Part.WIDE_MID, p, facing);
            cells.add(q);
            // 右键点中哪一格，交给 MagicMirror 的都是正中那一格（LinerProp.onUse 的换算）
            assertEquals(at, LinerProp.Rules.offset(q, p, LinerProp.Part.WIDE_MID, facing), p + " 换算回正中");
        }
        Set<BlockPos> want = new HashSet<>();
        for (int dy = 0; dy < 3; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                want.add(at.add(0, dy, dz));
            }
        }
        assertEquals(want, cells);
        // 镜面朝西时模型的西（x 0）在 z + 1 —— liner_build.mirror_cells 与 checkLinerShip ⑧ 照这一条摆、照这一条认
        assertEquals(at.south(), LinerProp.Rules.offset(at, LinerProp.Part.WIDE_MID, LinerProp.Part.WIDE_WEST, facing));
        assertEquals(at.up(2).north(), LinerProp.Rules.offset(at, LinerProp.Part.WIDE_MID, LinerProp.Part.WIDE_TOP_EAST, facing));
        for (Direction f : HORIZONTAL) {
            assertEquals(WIDE3, reachable(LinerProp.Part.WIDE_MID, f, WIDE3), "魔镜朝 " + f + " 断成了几截");
        }
        assertEquals(false, LinerProp.Rules.isLamp(LinerProp.Kind.MIRROR));
    }

    @Test
    void aDeckPiecesGrowToThePlacersRightAndUpAndOnlyTheHearthGlows() {
        // 人站在南边、面朝北（对着墙）摆：正面朝着人（朝南），背贴北边那面墙；点中的是下面一层人左手那一块，往人的右手（东）与上面长
        BlockPos at = new BlockPos(7, 40, -12);
        Direction facing = Direction.SOUTH;
        assertEquals(6, WIDE.size());
        assertEquals(8, BAR.size());
        assertEquals(EnumSet.of(LinerProp.Part.LOWER, LinerProp.Part.UPPER, LinerProp.Part.TOP), TALL3);
        for (LinerProp.Kind k : List.of(LinerProp.Kind.FIREPLACE, LinerProp.Kind.OVERMANTEL)) {
            Set<BlockPos> cells = new HashSet<>();
            for (LinerProp.Part p : LinerProp.Rules.parts(k)) {
                cells.add(LinerProp.Rules.offset(at, LinerProp.Rules.anchor(k), p, facing));
            }
            assertEquals(Set.of(at, at.east(), at.east(2), at.up(), at.east().up(), at.east(2).up()), cells, k + " 往人的右手与上面长");
        }
        Set<BlockPos> bar = new HashSet<>();
        for (LinerProp.Part p : BAR) {
            bar.add(LinerProp.Rules.offset(at, LinerProp.Rules.anchor(LinerProp.Kind.BAR_COUNTER), p, facing));
        }
        assertEquals(8, bar.size());
        assertEquals(true, bar.contains(at.east(3)) && bar.contains(at.east(3).up()) && !bar.contains(at.west()), "吧台往人的右手长四格");
        assertEquals(at.up(2), LinerProp.Rules.offset(at, LinerProp.Rules.anchor(LinerProp.Kind.PALM_TALL), LinerProp.Part.TOP, facing));
        assertEquals(TALL, LinerProp.Rules.parts(LinerProp.Kind.PALM));
        assertEquals(PAIR, LinerProp.Rules.parts(LinerProp.Kind.WICKER_SETTEE));
        // 长椅同沙发
        assertEquals(LinerProp.Rules.offset(at, LinerProp.Rules.anchor(LinerProp.Kind.SOFA), LinerProp.Part.WEST, facing),
                LinerProp.Rules.offset(at, LinerProp.Rules.anchor(LinerProp.Kind.WICKER_SETTEE), LinerProp.Part.WEST, facing));
        // 炉火是开关（默认亮），只有正中下面那一格发光；别的新件都不是灯
        assertEquals(true, LinerProp.Rules.isLamp(LinerProp.Kind.FIREPLACE));
        for (LinerProp.Part p : WIDE) {
            assertEquals(p == LinerProp.Part.WIDE_MID, LinerProp.Rules.glows(LinerProp.Kind.FIREPLACE, p), "炉火 · " + p);
        }
        for (LinerProp.Kind k : List.of(LinerProp.Kind.OVERMANTEL, LinerProp.Kind.PALM, LinerProp.Kind.PALM_TALL, LinerProp.Kind.WICKER_CHAIR,
                LinerProp.Kind.WICKER_TABLE, LinerProp.Kind.WICKER_SETTEE, LinerProp.Kind.BAR_COUNTER)) {
            assertEquals(false, LinerProp.Rules.isLamp(k), k + " 不是灯");
            assertEquals(false, LinerProp.Rules.hanging(k), k + " 不挂天花");
        }
    }

    @Test
    void palmsCollideOnlyWithThePot() {
        // 棕榈：碰撞只算盆（下面那一格的盆），上面几格一个碰撞盒子都没有；轮廓照旧有（点得中）。别的件碰撞箱就是轮廓（null）
        for (LinerProp.Kind k : List.of(LinerProp.Kind.PALM, LinerProp.Kind.PALM_TALL)) {
            for (LinerProp.Part p : LinerProp.Rules.parts(k)) {
                List<double[]> c = LinerPropShapes.collision(k, p);
                assertEquals(p == LinerProp.Part.LOWER, !c.isEmpty(), k + " · " + p);
                assertEquals(false, LinerPropShapes.boxes(k, p).isEmpty(), k + " · " + p + " 的轮廓");
                for (double[] b : c) {
                    assertEquals(true, b[4] <= 8, k + " 的碰撞高过盆沿：" + java.util.Arrays.toString(b));
                }
            }
        }
        assertNull(LinerPropShapes.collision(LinerProp.Kind.FIREPLACE, LinerProp.Part.WIDE_MID));
        assertNull(LinerPropShapes.collision(LinerProp.Kind.SOFA, LinerProp.Part.WEST));
    }

    @Test
    void furnitureGrowsTheWayTheModelsAreDrawn() {
        // ADR 草稿 furniture。人站在南边、面朝北摆（正面朝着人 = 朝南）：
        BlockPos at = new BlockPos(4, 64, -9);
        Direction facing = Direction.SOUTH;
        // 床：点中的那一格是床尾，床头往远处长（同游戏自带的床）—— 面朝北的人，远处是北
        assertEquals(LinerProp.Part.FOOT, LinerProp.Rules.anchor(LinerProp.Kind.BED));
        assertEquals(at.north(), LinerProp.Rules.offset(at, LinerProp.Part.FOOT, LinerProp.Part.HEAD, facing));
        // 衣柜、盥洗台：上面那一格
        for (LinerProp.Kind k : List.of(LinerProp.Kind.WARDROBE, LinerProp.Kind.WASHSTAND)) {
            assertEquals(LinerProp.Part.LOWER, LinerProp.Rules.anchor(k), k + " 点中的是下面那一格");
            assertEquals(TALL, LinerProp.Rules.parts(k), k + " 两格高");
        }
        // 书柜：点中的是下面一层人左手那一块，往人的右手（东）与上面长
        LinerProp.Part a = LinerProp.Rules.anchor(LinerProp.Kind.BOOKCASE);
        Set<BlockPos> shelf = new HashSet<>();
        for (LinerProp.Part p : LinerProp.Rules.parts(LinerProp.Kind.BOOKCASE)) {
            shelf.add(LinerProp.Rules.offset(at, a, p, facing));
        }
        assertEquals(Set.of(at, at.east(), at.up(), at.east().up()), shelf);
        // 写字台：同沙发
        assertEquals(LinerProp.Rules.offset(at, LinerProp.Rules.anchor(LinerProp.Kind.SOFA), LinerProp.Part.WEST, facing),
                LinerProp.Rules.offset(at, LinerProp.Rules.anchor(LinerProp.Kind.WRITING_TABLE), LinerProp.Part.WEST, facing));
        // 壁灯是灯（右键开关、发光），不挂天花；家具不是灯
        assertEquals(true, LinerProp.Rules.isLamp(LinerProp.Kind.SCONCE));
        assertEquals(true, LinerProp.Rules.glows(LinerProp.Kind.SCONCE, null));
        for (LinerProp.Kind k : List.of(LinerProp.Kind.BED, LinerProp.Kind.WARDROBE, LinerProp.Kind.WASHSTAND, LinerProp.Kind.BOOKCASE,
                LinerProp.Kind.WRITING_TABLE, LinerProp.Kind.WRITING_CHAIR)) {
            assertEquals(false, LinerProp.Rules.isLamp(k), k + " 不是灯");
        }
    }

    @Test
    void modelCellsTurnTheSameWayAsBlockStateRotation() {
        // 模型正面朝北：北 (0, −1) 转一格到东 (1, 0)、两格到南、三格到西（与方块状态 y 旋转同向）
        assertArrayEquals(new int[]{0, -1}, LinerProp.Rules.toWorld(0, -1, 0));
        assertArrayEquals(new int[]{1, 0}, LinerProp.Rules.toWorld(0, -1, 1));
        assertArrayEquals(new int[]{0, 1}, LinerProp.Rules.toWorld(0, -1, 2));
        assertArrayEquals(new int[]{-1, 0}, LinerProp.Rules.toWorld(0, -1, 3));
        assertArrayEquals(new int[]{0, 1}, LinerProp.Rules.toWorld(1, 0, 1), "模型里的东（x + 1）在朝东时落到南");
    }

    @Test
    void sofaAndTableGrowToThePlacersRightAndAwayFromThePlacer() {
        // 人站在南边、面朝北摆：沙发正面朝着人（朝南）。点中的那一格是「人左手那一块」，整件往人的右手长
        //   面朝北的人，右手是东 —— 另一块在东边一格
        BlockPos at = new BlockPos(-3, 64, -7);
        Direction facing = Direction.SOUTH;
        LinerProp.Part a = LinerProp.Rules.anchor(LinerProp.Kind.SOFA);
        assertEquals(at.east(), LinerProp.Rules.offset(at, a, LinerProp.Part.WEST, facing));
        // 大桌：另三块在人的右手与远处（面朝北的人：东、北）
        LinerProp.Part t = LinerProp.Rules.anchor(LinerProp.Kind.GRAND_TABLE);
        Set<BlockPos> table = new HashSet<>();
        for (LinerProp.Part p : QUAD) {
            table.add(LinerProp.Rules.offset(at, t, p, facing));
        }
        assertEquals(Set.of(at, at.east(), at.north(), at.east().north()), table);
        // 灯：上面那一格
        assertEquals(at.up(), LinerProp.Rules.offset(at, LinerProp.Part.LOWER, LinerProp.Part.UPPER, Direction.EAST));
    }

    @Test
    void everyPartFindsEachPartnerExactlyWhereOffsetPutsIt() {
        for (Set<LinerProp.Part> parts : List.of(QUAD, PAIR, TALL, BED, SHELF, WIDE, BAR, TALL3, WIDE3)) {
            for (Direction facing : HORIZONTAL) {
                for (LinerProp.Part here : parts) {
                    int partners = 0;
                    for (Direction d : Direction.values()) {
                        LinerProp.Part p = LinerProp.Rules.partnerAt(here, d, facing, parts);
                        if (p != null) {
                            partners++;
                            assertEquals(BlockPos.ORIGIN.offset(d), LinerProp.Rules.offset(BlockPos.ORIGIN, here, p, facing),
                                    here + " 朝 " + facing + " 时，" + d + " 那一格的搭档 " + p);
                        }
                    }
                    // 两格的件一个搭档；2 × 2 的大桌、竖着 2 × 2 的书柜每一块挨着两块（斜对角那一块不挨着，靠一格传一格）；
                    //   3 宽 × 2 高、4 长 × 2 高、三格高的件：在模型里挨着几块就是几个搭档（中间那几块三个 / 两个）
                    int touching = 0;
                    for (LinerProp.Part q : parts) {
                        touching += Math.abs(q.x - here.x) + Math.abs(q.y - here.y) + Math.abs(q.z - here.z) == 1 ? 1 : 0;
                    }
                    assertEquals(touching, partners, here + " 朝 " + facing);
                    if (parts == QUAD || parts == SHELF || parts == PAIR || parts == TALL || parts == BED) {
                        assertEquals(parts == QUAD || parts == SHELF ? 2 : 1, partners, here + " 朝 " + facing);
                    }
                }
            }
        }
        assertNull(LinerProp.Rules.partnerAt(LinerProp.Part.LOWER, Direction.DOWN, Direction.NORTH, TALL), "灯的下面一格不是搭档");
    }

    private static final Set<LinerProp.Part> GRAND = LinerProp.Rules.parts(LinerProp.Kind.GRAND_CHANDELIER);

    @Test
    void hangingLampsGrowDownFromTheCellUnderTheCeiling() {
        BlockPos at = new BlockPos(5, 70, -2);
        // 黄铜小吊灯：点中的那一格贴天花（上面那一块），灯身在它正下方
        assertEquals(LinerProp.Part.UPPER, LinerProp.Rules.anchor(LinerProp.Kind.CHANDELIER));
        assertEquals(at.down(), LinerProp.Rules.offset(at, LinerProp.Part.UPPER, LinerProp.Part.LOWER, Direction.SOUTH));
        // 水晶大吊灯：吊杆那一格贴天花，下面一层 3 × 3 以它为中心 —— 哪个朝向都一样
        LinerProp.Part crown = LinerProp.Rules.anchor(LinerProp.Kind.GRAND_CHANDELIER);
        assertEquals(LinerProp.Part.CROWN, crown);
        Set<BlockPos> ring = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ring.add(at.add(dx, -1, dz));
            }
        }
        for (Direction facing : HORIZONTAL) {
            Set<BlockPos> got = new HashSet<>();
            for (LinerProp.Part p : GRAND) {
                if (p != crown) {
                    got.add(LinerProp.Rules.offset(at, crown, p, facing));
                }
            }
            assertEquals(ring, got, "朝 " + facing);
            assertEquals(at.down(), LinerProp.Rules.offset(at, crown, LinerProp.Part.RING_C, facing));
        }
        for (LinerProp.Kind k : LinerProp.Kind.values()) {
            assertEquals(k == LinerProp.Kind.CEILING_LAMP || k == LinerProp.Kind.CHANDELIER || k == LinerProp.Kind.GRAND_CHANDELIER
                            || k == LinerProp.Kind.CEILING_PAIR || k == LinerProp.Kind.CEILING_QUAD,
                    LinerProp.Rules.hanging(k), k + " 挂不挂在天花下");
        }
    }

    @Test
    void tableLampLeansTowardTheMiddleOfTheTable() {
        // 用户 2026-10-03「桌子上台灯的脚有一部分悬空了」（ADR-0071）：台灯在大桌哪一角、桌子与台灯各朝哪边，
        //   挪的方向（台灯模型里的 table_corner，按台灯朝向转到世界）都得指向桌子正中 —— 从世界里那四块桌子的位置直接算答案，
        //   不用 tableCorner 自己的算法
        int checked = 0;
        for (Direction tableFacing : HORIZONTAL) {
            BlockPos anchor = BlockPos.ORIGIN;
            java.util.Map<LinerProp.Part, BlockPos> cells = new java.util.HashMap<>();
            for (LinerProp.Part p : LinerProp.Rules.parts(LinerProp.Kind.GRAND_TABLE)) {
                cells.put(p, LinerProp.Rules.offset(anchor, LinerProp.Rules.anchor(LinerProp.Kind.GRAND_TABLE), p, tableFacing));
            }
            double cx = cells.values().stream().mapToInt(BlockPos::getX).average().orElseThrow() + 0.5;
            double cz = cells.values().stream().mapToInt(BlockPos::getZ).average().orElseThrow() + 0.5;
            for (java.util.Map.Entry<LinerProp.Part, BlockPos> e : cells.entrySet()) {
                int wantX = (int) Math.signum(cx - (e.getValue().getX() + 0.5));
                int wantZ = (int) Math.signum(cz - (e.getValue().getZ() + 0.5));
                for (Direction lampFacing : HORIZONTAL) {
                    LinerProp.Part c = LinerProp.Rules.tableCorner(e.getKey(), tableFacing, lampFacing);
                    int[] w = LinerProp.Rules.toWorld(c.x == 0 ? -1 : 1, c.z == 0 ? -1 : 1, LinerConnect.index(lampFacing));
                    assertArrayEquals(new int[]{wantX, wantZ}, w,
                            "桌子朝 " + tableFacing + " 的 " + e.getKey() + " 那一块上、台灯朝 " + lampFacing + "：该往桌子正中挪");
                    checked++;
                }
            }
        }
        assertEquals(4 * 4 * 4, checked);
    }

    @Test
    void seamLightsSpreadFlatUnderTheCeilingAndLightEveryCell() {
        // 骑缝的吸顶灯（ADR-0068）：两格 / 2 × 2 一件，都在同一层（每一格都贴天花），整件往人的右手与远处长（同沙发、大桌），每一格都发光
        BlockPos at = new BlockPos(5, 64, -3);
        for (Direction facing : HORIZONTAL) {
            LinerProp.Part a = LinerProp.Rules.anchor(LinerProp.Kind.CEILING_PAIR);
            assertEquals(LinerProp.Rules.offset(at, LinerProp.Rules.anchor(LinerProp.Kind.SOFA), LinerProp.Part.WEST, facing),
                    LinerProp.Rules.offset(at, a, LinerProp.Part.WEST, facing), "两格的骑缝灯与沙发同一个长法，朝 " + facing);
            Set<BlockPos> quad = new java.util.HashSet<>();
            for (LinerProp.Part p : LinerProp.Rules.parts(LinerProp.Kind.CEILING_QUAD)) {
                BlockPos q = LinerProp.Rules.offset(at, LinerProp.Rules.anchor(LinerProp.Kind.CEILING_QUAD), p, facing);
                assertEquals(at.getY(), q.getY(), "2 × 2 的骑缝灯四格都在天花下那一层");
                quad.add(q);
            }
            assertEquals(4, quad.size());
        }
        for (LinerProp.Kind k : List.of(LinerProp.Kind.CEILING_PAIR, LinerProp.Kind.CEILING_QUAD)) {
            assertEquals(true, LinerProp.Rules.isLamp(k), k + " 是灯");
            for (LinerProp.Part p : LinerProp.Rules.parts(k)) {
                assertEquals(true, LinerProp.Rules.glows(k, p), k + " · " + p + " 发光");
            }
        }
    }

    @Test
    void everyCellOfTheGrandChandelierFindsItsNeighbours() {
        for (Direction facing : HORIZONTAL) {
            for (LinerProp.Part here : GRAND) {
                int partners = 0;
                for (Direction d : Direction.values()) {
                    LinerProp.Part p = LinerProp.Rules.partnerAt(here, d, facing, GRAND);
                    if (p != null) {
                        partners++;
                        assertEquals(BlockPos.ORIGIN.offset(d), LinerProp.Rules.offset(BlockPos.ORIGIN, here, p, facing), here + " " + d);
                    }
                }
                // 拆掉任何一格整件跟着没：一格传一格，所以每一格至少挨着一块 —— 正中那一格挨着十字四块 + 上面的吊杆
                int want = switch (here) {
                    case CROWN -> 1;
                    case RING_C -> 5;
                    case RING_N, RING_S, RING_W, RING_E -> 3;
                    default -> 2;
                };
                assertEquals(want, partners, here + " 朝 " + facing);
            }
        }
    }

    @Test
    void grandChandelierLightsOnlyTheCrossOfFiveAndMirrorsWhole() {
        Set<LinerProp.Part> lit = EnumSet.noneOf(LinerProp.Part.class);
        for (LinerProp.Part p : GRAND) {
            if (LinerProp.Rules.glows(LinerProp.Kind.GRAND_CHANDELIER, p)) {
                lit.add(p);
            }
        }
        assertEquals(EnumSet.of(LinerProp.Part.RING_N, LinerProp.Part.RING_W, LinerProp.Part.RING_C, LinerProp.Part.RING_E,
                LinerProp.Part.RING_S), lit);
        assertEquals(true, LinerProp.Rules.glows(LinerProp.Kind.CHANDELIER, LinerProp.Part.LOWER));
        assertEquals(false, LinerProp.Rules.glows(LinerProp.Kind.CHANDELIER, LinerProp.Part.UPPER));
        for (Direction facing : HORIZONTAL) {
            Direction mirroredFacing = facing.getAxis() == Direction.Axis.X ? facing.getOpposite() : facing;
            for (LinerProp.Part a : GRAND) {
                for (LinerProp.Part b : GRAND) {
                    BlockPos d = LinerProp.Rules.offset(BlockPos.ORIGIN, a, b, facing);
                    assertEquals(new BlockPos(-d.getX(), d.getY(), d.getZ()), LinerProp.Rules.offset(BlockPos.ORIGIN,
                            LinerProp.Rules.mirrored(a), LinerProp.Rules.mirrored(b), mirroredFacing), a + " → " + b + " 朝 " + facing);
                }
            }
        }
    }

    /** 这几个盒子把一格填满了多少（按 16³ 个小格的中心点数）。 */
    private static double filled(List<double[]> boxes) {
        int in = 0;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    double px = x + 0.5;
                    double py = y + 0.5;
                    double pz = z + 0.5;
                    for (double[] b : boxes) {
                        if (b[0] <= px && px < b[3] && b[1] <= py && py < b[4] && b[2] <= pz && pz < b[5]) {
                            in++;
                            break;
                        }
                    }
                }
            }
        }
        return in / 4096.0;
    }

    @Test
    void noPropHasAFullBlockOutlineOrCollisionBox() {
        // 用户 2026-10-02：「异形物品碰撞箱不能是完整的一个方块，需要也是异形的」。碰撞箱就是轮廓（Minecraft 默认），
        //   所以查轮廓的盒子：每一种、每一块都要有盒子、都在这一格里、而且没把这一格填到九成
        // 正向对照：一整块与「差一条边的整块」都要被认成填满 —— 判据认不出整块，下面全绿也什么都没证明
        assertEquals(1.0, filled(List.of(new double[]{0, 0, 0, 16, 16, 16})));
        assertEquals(true, filled(List.of(new double[]{0, 0, 0, 16, 16, 15})) > 0.9);
        int checked = 0;
        for (LinerProp.Kind kind : LinerProp.Kind.values()) {
            Set<LinerProp.Part> parts = LinerProp.Rules.parts(kind);
            for (LinerProp.Part part : parts.isEmpty() ? java.util.Collections.<LinerProp.Part>singletonList(null) : parts) {
                List<double[]> boxes = LinerPropShapes.boxes(kind, part);
                String who = kind + (part == null ? "" : " · " + part);
                assertEquals(true, !boxes.isEmpty(), who + " 没有轮廓（点不中、也挡不住人）");
                for (double[] b : boxes) {
                    for (int i = 0; i < 3; i++) {
                        assertEquals(true, 0 <= b[i] && b[i] < b[i + 3] && b[i + 3] <= 16, who + " 的盒子越出这一格：" + java.util.Arrays.toString(b));
                    }
                }
                double f = filled(boxes);
                assertEquals(true, f < 0.9, who + " 的轮廓把一格填到了 " + Math.round(f * 100) + "%（读出来就是一整块）");
                checked++;
            }
        }
        // 正向对照：每一种、每一块都过了一遍（落地灯 2 · 台灯 1 · 大桌 4 · 沙发 2 · 椅子 1 · 吸顶灯 1 · 小吊灯 2 · 大吊灯 10
        //   · 骑缝灯两格 2 · 2 × 2 4 · 床 2 · 衣柜 2 · 盥洗台 2 · 魔镜 9（2026-10-04 放大前 2）· 书柜 4 · 写字台 2 · 写字椅 1 · 壁灯 1
        //   · A 甲板新家具：壁炉 6 · 炉上件 6 · 棕榈 2 · 大棵棕榈 3 · 藤椅 1 · 藤编小圆桌 1 · 藤编长椅 2 · 吧台 8
        //   · 艇甲板设备：吊艇架 10 · 高通风筒 3 · 矮通风筒 2 · 开局的钟 12）
        assertEquals(2 + 1 + 4 + 2 + 1 + 1 + 2 + 10 + 2 + 4 + 2 + 2 + 2 + 9 + 4 + 2 + 1 + 1 + 6 + 6 + 2 + 3 + 1 + 1 + 2 + 8 + 10 + 3 + 2 + 12, checked);
    }

    /** 从 from 那一块起，经「搭档在哪一格」（六个面上的邻居）一格传一格走得到的块 —— 拆一格整件没，靠的就是这条链。 */
    private static Set<LinerProp.Part> reachable(LinerProp.Part from, Direction facing, Set<LinerProp.Part> parts) {
        Set<LinerProp.Part> seen = EnumSet.of(from);
        java.util.ArrayDeque<LinerProp.Part> todo = new java.util.ArrayDeque<>(seen);
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
    void deckGearIsOneChainAndTheDavitArmReachesOutboard() {
        // 艇甲板设备（ADR 草稿 deckgear）：吊艇架 10 格、高通风筒 3 格、矮通风筒 2 格。拆一格整件没是一格传一格（搭档不在了自己变空气，
        //   只看六个面）—— 所以从锚点出发、经搭档一格格走，必须每一块都走得到，哪个朝向都一样
        for (LinerProp.Kind k : List.of(LinerProp.Kind.DAVIT, LinerProp.Kind.VENTILATOR, LinerProp.Kind.VENTILATOR_SHORT,
                LinerProp.Kind.DRILL_BELL)) {
            Set<LinerProp.Part> parts = LinerProp.Rules.parts(k);
            for (Direction facing : HORIZONTAL) {
                assertEquals(parts, reachable(LinerProp.Rules.anchor(k), facing, parts), k + " 朝 " + facing + " 断成了几截");
                for (LinerProp.Part here : parts) {
                    for (Direction d : Direction.values()) {
                        LinerProp.Part p = LinerProp.Rules.partnerAt(here, d, facing, parts);
                        if (p != null) {
                            assertEquals(BlockPos.ORIGIN.offset(d), LinerProp.Rules.offset(BlockPos.ORIGIN, here, p, facing), k + " " + here + " " + d);
                        }
                    }
                }
            }
        }
        assertEquals(10, LinerProp.Rules.parts(LinerProp.Kind.DAVIT).size());
        assertEquals(EnumSet.of(LinerProp.Part.LOWER, LinerProp.Part.UPPER, LinerProp.Part.TOP), LinerProp.Rules.parts(LinerProp.Kind.VENTILATOR));
        assertEquals(TALL, LinerProp.Rules.parts(LinerProp.Kind.VENTILATOR_SHORT));
        assertEquals(12, LinerProp.Rules.parts(LinerProp.Kind.DRILL_BELL).size());
        // 开局的钟（门形钟架，2 宽 × 6 高）：点中的是下面一层人左手那一块，往人的右手与上面长（同书柜）—— 面朝北的人：东边那一格是锚点，另一列在西
        BlockPos bell = new BlockPos(7, 48, 30);
        Set<BlockPos> bellCells = new HashSet<>();
        for (LinerProp.Part q : LinerProp.Rules.parts(LinerProp.Kind.DRILL_BELL)) {
            bellCells.add(LinerProp.Rules.offset(bell, LinerProp.Rules.anchor(LinerProp.Kind.DRILL_BELL), q, Direction.SOUTH));
        }
        Set<BlockPos> wantBell = new HashSet<>();
        for (int y = 0; y < 6; y++) {
            wantBell.add(bell.up(y));
            wantBell.add(bell.east().up(y));
        }
        assertEquals(wantBell, bellCells);
        // 正向对照：第一轮样张那 6 格（臂只放在 (0,3,−1) · (0,5,−2) · (0,6,−2)，与扇板那一格只在棱上挨着）必须走不通 —— 判据认得出断处
        Set<LinerProp.Part> first = EnumSet.of(LinerProp.Part.BASE, LinerProp.Part.BASE_IN, LinerProp.Part.QUADRANT, LinerProp.Part.ARM_C,
                LinerProp.Part.ARM_F, LinerProp.Part.ARM_HEAD);
        assertEquals(EnumSet.of(LinerProp.Part.BASE, LinerProp.Part.BASE_IN, LinerProp.Part.QUADRANT),
                reachable(LinerProp.Part.BASE, Direction.NORTH, first));
        // 吊艇架往哪长：点中的是铁座靠舷外那一格；正面朝北（舷外在北）时，往舷内（南）一格是 base_in，臂头在上 6 格、往舷外（北）2 格
        BlockPos at = new BlockPos(100, 48, 2);
        assertEquals(LinerProp.Part.BASE, LinerProp.Rules.anchor(LinerProp.Kind.DAVIT));
        assertEquals(at.south(), LinerProp.Rules.offset(at, LinerProp.Part.BASE, LinerProp.Part.BASE_IN, Direction.NORTH));
        assertEquals(at.add(0, 6, -2), LinerProp.Rules.offset(at, LinerProp.Part.BASE, LinerProp.Part.ARM_HEAD, Direction.NORTH));
        // 右舷那一架正面朝南：臂头往南（舷外）2 格
        assertEquals(at.add(0, 6, 2), LinerProp.Rules.offset(at, LinerProp.Part.BASE, LinerProp.Part.ARM_HEAD, Direction.SOUTH));
        // 照镜子：各块都在 x 0 那一列，块不换、相对位置照样对得上；艇换到另一只手
        for (Direction facing : HORIZONTAL) {
            Direction mirroredFacing = facing.getAxis() == Direction.Axis.X ? facing.getOpposite() : facing;
            for (LinerProp.Kind k : List.of(LinerProp.Kind.DAVIT, LinerProp.Kind.VENTILATOR, LinerProp.Kind.DRILL_BELL)) {
                for (LinerProp.Part a : LinerProp.Rules.parts(k)) {
                    for (LinerProp.Part b : LinerProp.Rules.parts(k)) {
                        BlockPos d = LinerProp.Rules.offset(BlockPos.ORIGIN, a, b, facing);
                        assertEquals(new BlockPos(-d.getX(), d.getY(), d.getZ()), LinerProp.Rules.offset(BlockPos.ORIGIN,
                                LinerProp.Rules.mirrored(a), LinerProp.Rules.mirrored(b), mirroredFacing), k + " " + a + " → " + b + " 朝 " + facing);
                    }
                }
            }
        }
        assertEquals(LinerProp.BoatSide.LEFT, LinerProp.Rules.mirrored(LinerProp.BoatSide.RIGHT));
        assertEquals(LinerProp.BoatSide.RIGHT, LinerProp.Rules.mirrored(LinerProp.BoatSide.LEFT));
        for (LinerProp.Kind k : List.of(LinerProp.Kind.DAVIT, LinerProp.Kind.VENTILATOR, LinerProp.Kind.VENTILATOR_SHORT,
                LinerProp.Kind.DRILL_BELL)) {
            assertEquals(false, LinerProp.Rules.isLamp(k), k + " 不是灯");
            assertEquals(false, LinerProp.Rules.hanging(k), k + " 不挂天花");
        }
    }

    @Test
    void mirroringSwapsLeftAndRightAndKeepsThePieceWhole() {
        assertEquals(LinerProp.Part.EAST, LinerProp.Rules.mirrored(LinerProp.Part.WEST));
        assertEquals(LinerProp.Part.SE, LinerProp.Rules.mirrored(LinerProp.Part.SW));
        assertEquals(LinerProp.Part.UPPER, LinerProp.Rules.mirrored(LinerProp.Part.UPPER));
        // 整件照镜子（世界里 x 取反）之后，每一块换成 mirrored 的那一块、朝向照镜子转 —— 各块之间的相对位置必须还对得上
        for (Direction facing : HORIZONTAL) {
            Direction mirroredFacing = facing.getAxis() == Direction.Axis.X ? facing.getOpposite() : facing;
            for (Set<LinerProp.Part> parts : List.of(QUAD, PAIR, BED, SHELF, TALL, WIDE, BAR, TALL3, WIDE3)) {
                for (LinerProp.Part a : parts) {
                    for (LinerProp.Part b : parts) {
                        BlockPos d = LinerProp.Rules.offset(BlockPos.ORIGIN, a, b, facing);
                        BlockPos flipped = new BlockPos(-d.getX(), d.getY(), d.getZ());
                        assertEquals(flipped, LinerProp.Rules.offset(BlockPos.ORIGIN, LinerProp.Rules.mirrored(a),
                                LinerProp.Rules.mirrored(b), mirroredFacing), a + " → " + b + " 朝 " + facing);
                    }
                }
            }
        }
    }
}
