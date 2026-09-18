package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.mod.data.VoyageLayout;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 布景的几何（ADR-0034 §5.3.1）：停靠点、起滑平移、朝向，对四个岸向各核一次。
 *
 * <p>这条不过的表现是岸从别的方向滑来、或者背对着船 —— 不报错，只是第一次实拍时看着不对。
 */
final class BackdropMathTest {

    private static final Identifier DEFAULT = Identifier.of("heavyseas", "default");
    private static final VoyageLayout.Backdrop LIGHTHOUSE = new VoyageLayout.Backdrop(
            Identifier.of("heavyseas", "lighthouse_backdrop"), new Vec3d(9, 3.1, -2), 0f, 5f, 4f, 128f, 32f);

    private static VoyageLayout layout(float yaw, float bearing) {
        return new VoyageLayout(DEFAULT, Identifier.of("heavyseas", "mist_sea"),
                new VoyageLayout.Boat(new Vec3d(0.5, 65.15, 0.5), yaw, 2.0), Optional.empty(),
                new VoyageLayout.Arrival(bearing, 88, 40, true), DEFAULT, List.of(LIGHTHOUSE));
    }

    @Test
    void officialBearingPutsTheShoreSouthAndRightIsWest() {
        VoyageLayout layout = layout(180f, 0f);
        assertVec(new Vec3d(0.5, 65.15, 40.5), layout.shoreAnchor());
        assertVec(new Vec3d(-1, 0, 0), layout.bearingRight());
        // 偏移 (9 右, 3.1 上, −2 朝船)：右是 −X，朝船是 −Z，所以 −2 朝船 = +2 往岸外。
        assertVec(new Vec3d(0.5 - 9, 65.15 + 3.1, 40.5 + 2), layout.backdropRest(LIGHTHOUSE));
        Vector3f far = Backdrop.farTranslation(layout);
        assertEquals(0f, far.x, 1e-5f);
        assertEquals(48f, far.z, 1e-5f);
    }

    @Test
    void modelNorthFaceAlwaysPointsAtTheBoat() {
        for (float bearing : new float[] {0f, 90f, 180f, 270f, -90f}) {
            VoyageLayout layout = layout(180f, bearing);
            Quaternionf facing = Backdrop.facing(layout, LIGHTHOUSE);
            Vector3f north = facing.transform(new Vector3f(0, 0, -1));
            Vec3d towardBoat = layout.bearingVector().multiply(-1);
            assertEquals(towardBoat.x, north.x, 1e-5, "岸向 " + bearing + " 的 x");
            assertEquals(towardBoat.z, north.z, 1e-5, "岸向 " + bearing + " 的 z");
        }
    }

    @Test
    void drillBearingSlidesInFromTheEast() {
        // 演练布局：yaw 90，岸向 270 = +X（东）。停靠点在船头东边 40 格，起滑平移沿 +X。
        VoyageLayout layout = layout(90f, 270f);
        assertVec(new Vec3d(40.5, 65.15, 0.5), layout.shoreAnchor());
        Vector3f far = Backdrop.farTranslation(layout);
        assertEquals(48f, far.x, 1e-5f);
        assertEquals(0f, far.z, 1e-5f);
        // 面朝东时右手是南（+Z）。
        assertVec(new Vec3d(0, 0, 1), layout.bearingRight());
    }

    private static void assertVec(Vec3d expected, Vec3d actual) {
        assertEquals(expected.x, actual.x, 1e-6, "x");
        assertEquals(expected.y, actual.y, 1e-6, "y");
        assertEquals(expected.z, actual.z, 1e-6, "z");
    }
}
