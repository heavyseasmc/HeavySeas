package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 家具能坐（ADR-0109 ①）：{@link LinerProp.Rules#seats} 那张表的形状 —— 数对不对由 docs 的生成器判（liner_props_seats.py 的
 * check_java_seats 逐个座位对、check_seats_sitting 把人放上去量），这里只守「表本身说得通」：座位落在这一件自己的某一块里、
 * 点在那一块那一格里、脸朝的方向只许正面或左右（床沿）、同一件的两个座位不重叠，右键坐的恰好是有座位的那几种。
 */
final class LinerSeatsTest {

    /** 有座位的种类（ADR-0109 ①：椅子 · 沙发 · 凳 · 长椅 · 床尾凳 · 贵妃椅 · 床沿；上下铺不给 —— 下铺坐着头顶穿进上铺）。 */
    private static final Set<LinerProp.Kind> SEATED = EnumSet.of(LinerProp.Kind.DECK_CHAIR, LinerProp.Kind.CHAIR, LinerProp.Kind.SOFA,
            LinerProp.Kind.WRITING_CHAIR, LinerProp.Kind.WICKER_CHAIR, LinerProp.Kind.WICKER_SETTEE, LinerProp.Kind.DINING_CHAIR,
            LinerProp.Kind.STOOL, LinerProp.Kind.BED_BENCH, LinerProp.Kind.CHAISE_LONGUE, LinerProp.Kind.BED, LinerProp.Kind.DOUBLE_BED,
            LinerProp.Kind.FOUR_POSTER_BED);

    @Test
    void exactlyTheSeatedKindsHaveSeatsAndSitOnRightClick() {
        for (LinerProp.Kind k : LinerProp.Kind.values()) {
            boolean seated = SEATED.contains(k);
            assertEquals(seated, !LinerProp.Rules.seats(k).isEmpty(),
                    k + (seated ? " 应当有座位（ADR-0109 ①）" : " 不该有座位（上下铺：下铺头顶不够；别的不是坐的东西）"));
            if (seated) {
                assertEquals(LinerProp.Use.SIT, LinerProp.Rules.use(k), k + " 有座位，右键应当走「坐」那条路");
            }
        }
    }

    @Test
    void everySeatSitsInOneOfItsOwnCellsFacingFrontOrSideways() {
        for (LinerProp.Kind k : SEATED) {
            Set<LinerProp.Part> parts = LinerProp.Rules.parts(k);
            Set<String> spots = new HashSet<>();
            List<LinerProp.Rules.Seat> seats = LinerProp.Rules.seats(k);
            for (int i = 0; i < seats.size(); i++) {
                LinerProp.Rules.Seat s = seats.get(i);
                String at = k + " 第 " + i + " 个座位";
                if (parts.isEmpty()) {
                    // 一格的件没有 part 属性：seatAt 不读 cell，表里一律写 CENTER，免得读的人以为它有意义
                    assertEquals(LinerProp.Part.CENTER, s.cell(), at + "：一格的件，cell 写 CENTER");
                } else {
                    assertTrue(parts.contains(s.cell()), at + "：落在 " + s.cell() + "，不是这一件的一块 " + parts);
                    assertEquals(0, s.cell().y, at + "：座位落在最下面一层（" + s.cell() + " 在第 " + s.cell().y + " 层）");
                }
                assertTrue(s.turn() >= -1 && s.turn() <= 1, at + "：脸朝的方向只许正面（0）或床沿左右（±1），是 " + s.turn());
                assertTrue(s.x() >= 0 && s.x() <= 16 && s.z() >= 0 && s.z() <= 16,
                        at + "：点 (" + s.x() + ", " + s.z() + ") 出了 " + s.cell() + " 那一格（0–16 像素）");
                assertTrue(s.y() > 0 && s.y() < 16, at + "：座位实体的高 " + s.y() + " 像素不在这一格里");
                assertTrue(spots.add(s.cell() + "@" + s.x() + "," + s.z()), at + "：与同一件的另一个座位落在同一点");
            }
        }
    }

    @Test
    void seatAtTurnsThePointWithTheFurniture() {
        // 一格的件（here = null）：朝北不转，朝东 (x, z) → (16 − z, x)（同 LinerLooks.rotateY）
        LinerProp.Rules.Seat stool = LinerProp.Rules.seats(LinerProp.Kind.STOOL).get(0);
        BlockPos pos = new BlockPos(10, 64, -3);
        double[] n = LinerProp.Rules.seatAt(pos, null, stool, Direction.NORTH);
        assertEquals(10 + stool.x() / 16, n[0], 1e-9);
        assertEquals(64 + stool.y() / 16, n[1], 1e-9);
        assertEquals(-3 + stool.z() / 16, n[2], 1e-9);
        double[] e = LinerProp.Rules.seatAt(pos, null, stool, Direction.EAST);
        assertEquals(10 + (16 - stool.z()) / 16, e[0], 1e-9);
        assertEquals(-3 + stool.x() / 16, e[2], 1e-9);
        // 床沿：朝东那一边的人脸朝床的右手（家具朝北 → 人朝东），朝西那一边朝西
        for (LinerProp.Rules.Seat s : LinerProp.Rules.seats(LinerProp.Kind.BED)) {
            Direction want = s.turn() > 0 ? Direction.EAST : Direction.WEST;
            assertEquals(want, LinerProp.Rules.seatFacing(s, Direction.NORTH), "床沿 turn " + s.turn());
            assertEquals(s.turn() > 0, s.x() > 8, "床沿朝东的座位应当在床的东半边（x " + s.x() + "）");
        }
    }
}
