package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.block.enums.DoorHinge;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 门（ADR-0069 §2 ⑤）的纯规则：模板转到各朝向之后与游戏自带门的轮廓（= 碰撞箱）对得上；双开门两扇互相指着对方。 */
final class LinerDoorRulesTest {

    // 模板（朝东）里门扇的水平占地 {x0, z0, x1, z1}：与 liner_hull.py 的 DOOR_SHAPES 同一份
    private static final double[] CLOSED = {0, 0, 3, 16};
    private static final double[] OPEN_LEFT = {0, 0, 16, 3};
    private static final double[] OPEN_RIGHT = {0, 13, 16, 16};

    // 游戏自带门的轮廓（DoorBlock.getOutlineShape，1.21.1）：名字 → {x0, z0, x1, z1}
    private static final Map<String, double[]> SHAPE = Map.of(
            "north", new double[]{0, 0, 16, 3}, "south", new double[]{0, 13, 16, 16},
            "east", new double[]{13, 0, 16, 16}, "west", new double[]{0, 0, 3, 16});

    @Test
    void templatesTurnedToEachFacingMatchTheDoorOutline() {
        // 游戏自带门：关着 南→NORTH_SHAPE · 西→EAST · 北→SOUTH · 东→WEST；开着左合页 南→EAST · 西→SOUTH · 北→WEST · 东→NORTH；右合页反过来
        Object[][] cases = {
                {Direction.SOUTH, CLOSED, "north"}, {Direction.WEST, CLOSED, "east"}, {Direction.NORTH, CLOSED, "south"}, {Direction.EAST, CLOSED, "west"},
                {Direction.SOUTH, OPEN_LEFT, "east"}, {Direction.WEST, OPEN_LEFT, "south"}, {Direction.NORTH, OPEN_LEFT, "west"}, {Direction.EAST, OPEN_LEFT, "north"},
                {Direction.SOUTH, OPEN_RIGHT, "west"}, {Direction.WEST, OPEN_RIGHT, "north"}, {Direction.NORTH, OPEN_RIGHT, "east"}, {Direction.EAST, OPEN_RIGHT, "south"}};
        for (Object[] c : cases) {
            Direction f = (Direction) c[0];
            double[] t = (double[]) c[1];
            double[] a = LinerLooks.rotateY(t[0], t[1], LinerDoors.Rules.yaw(f));
            double[] b = LinerLooks.rotateY(t[2], t[3], LinerDoors.Rules.yaw(f));
            double[] got = {Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1])};
            assertArrayEquals(SHAPE.get((String) c[2]), got, 1e-9, f + " 的模板转过去应是 " + c[2] + " 那一条");
        }
    }

    @Test
    void doubleDoorLeavesPointAtEachOther() {
        for (Direction f : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            Direction toRight = LinerDoors.Rules.partnerSide(f, DoorHinge.LEFT);
            Direction toLeft = LinerDoors.Rules.partnerSide(f, DoorHinge.RIGHT);
            assertEquals(f.rotateYClockwise(), toRight, "合页在左手（朝 " + f + " 看），另一扇在右手");
            assertEquals(toRight.getOpposite(), toLeft, "两扇互相指着对方");
            // 游戏自带门摆放时：左手边（facing 逆时针一格）已有一扇门，新的这一扇合页放右手 —— 它的另一扇就在左手
            assertEquals(f.rotateYCounterclockwise(), toLeft);
        }
    }
}
