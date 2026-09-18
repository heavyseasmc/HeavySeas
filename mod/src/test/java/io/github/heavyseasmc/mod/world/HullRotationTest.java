package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.mod.data.VoyageLayout;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 船体模板的旋转要与座位的排法同源：模板里「船尾 → 船头」是 +Z，转完之后必须等于世界里的 {@code -forward(yaw)}。
 *
 * <p>四个正方向各核一次。这条不过的表现是船放歪了而座位没歪 —— 人坐在水里，不报错。
 */
final class HullRotationTest {

    private static final Vec3i STERN_TO_BOW = new Vec3i(0, 0, 1);

    @Test
    void rotatedTemplateAxisMatchesSeatDirectionForEveryCardinalYaw() {
        for (float yaw : new float[] {180f, 90f, 0f, 270f, -90f, -180f}) {
            BlockRotation rotation = Hull.rotationFor(yaw);
            BlockPos rotated = Hull.rotate(STERN_TO_BOW, rotation);
            Vec3d expected = VoyageLayout.forward(yaw).multiply(-1);
            assertEquals(Math.round(expected.x), rotated.getX(), "yaw " + yaw + " 的 x");
            assertEquals(0, rotated.getY(), "yaw " + yaw + " 的 y");
            assertEquals(Math.round(expected.z), rotated.getZ(), "yaw " + yaw + " 的 z");
        }
    }

    @Test
    void officialLayoutNeedsNoRotation() {
        assertEquals(BlockRotation.NONE, Hull.rotationFor(180f));
        assertEquals(new BlockPos(2, 2, 15), Hull.rotate(new Vec3i(2, 2, 15), BlockRotation.NONE));
    }

    @Test
    void rotationMatchesStructureTemplateFormulaAroundOrigin() {
        // 与 StructureTemplate.transformAround(pos, NONE, rotation, ORIGIN) 同一公式（1.21.1 反编译源码里读的）。
        Vec3i p = new Vec3i(2, 5, 7);
        assertEquals(new BlockPos(7, 5, -2), Hull.rotate(p, BlockRotation.COUNTERCLOCKWISE_90));
        assertEquals(new BlockPos(-7, 5, 2), Hull.rotate(p, BlockRotation.CLOCKWISE_90));
        assertEquals(new BlockPos(-2, 5, -7), Hull.rotate(p, BlockRotation.CLOCKWISE_180));
    }

    @Test
    void diagonalYawIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Hull.rotationFor(45f));
    }
}
