package io.github.heavyseasmc.mod.world;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Shared geometry for the model, its selectable hull, and the eight foredeck seats. */
public final class LobbyBoatGeometry {

    public static final float WORLD_SCALE = 10f;
    public static final double PIXEL_SCALE = WORLD_SCALE / 16.0;
    public static final double MODEL_ORIGIN_XZ = 8.0;
    public static final double MODEL_BOTTOM = -8.0;
    public static final double MODEL_TOP = 32.0;
    public static final double MODEL_MIN_X = -10.0;
    public static final double MODEL_MAX_X = 26.0;
    public static final double MODEL_MIN_Z = -16.0;
    public static final double MODEL_MAX_Z = 32.0;
    public static final double MODEL_DECK_Y = 5.0;
    public static final double WIDTH = (MODEL_MAX_X - MODEL_MIN_X) * PIXEL_SCALE;
    public static final double LENGTH = (MODEL_MAX_Z - MODEL_MIN_Z) * PIXEL_SCALE;
    public static final double HEIGHT = (MODEL_TOP - MODEL_BOTTOM) * PIXEL_SCALE;
    public static final double MODEL_LIFT = -MODEL_BOTTOM * PIXEL_SCALE;
    public static final double DECK_HEIGHT = (MODEL_DECK_Y - MODEL_BOTTOM) * PIXEL_SCALE;
    public static final double SEAT_LIFT = 0.35;
    public static final int SEAT_COUNT = 8;
    public static final int RENDER_DISTANCE = 160;

    private LobbyBoatGeometry() {
    }

    public static Vec3d modelPoint(BlockPos anchor, Direction facing, double x, double y, double z) {
        Vec3d forward = Vec3d.of(facing.getVector());
        Vec3d right = new Vec3d(-forward.z, 0, forward.x);
        return Vec3d.ofBottomCenter(anchor)
                .add(right.multiply((x - MODEL_ORIGIN_XZ) * PIXEL_SCALE))
                .add(0, (y - MODEL_BOTTOM) * PIXEL_SCALE, 0)
                .add(forward.multiply((MODEL_ORIGIN_XZ - z) * PIXEL_SCALE));
    }

    public static Vec3d seatAt(BlockPos anchor, Direction facing, int index) {
        if (index < 0 || index >= SEAT_COUNT) {
            throw new IllegalArgumentException("Lobby seat index must be between 0 and 7");
        }
        double x = index % 2 == 0 ? 3.0 : 13.0;
        double z = -12.0 + (index / 2) * 4.0;
        return modelPoint(anchor, facing, x, MODEL_DECK_Y, z).add(0, SEAT_LIFT, 0);
    }

    public static Box bounds(BlockPos anchor, Direction facing) {
        Vec3d min = modelPoint(anchor, facing, MODEL_MIN_X, MODEL_BOTTOM, MODEL_MIN_Z);
        Vec3d max = modelPoint(anchor, facing, MODEL_MAX_X, MODEL_TOP, MODEL_MAX_Z);
        return new Box(min, max);
    }

    public static Box searchBounds(BlockPos anchor) {
        double radius = LENGTH / 2.0 + 2.0;
        return new Box(anchor).expand(radius, HEIGHT + 1.0, radius);
    }

    public static Vec3d shoreAt(BlockPos anchor, Direction facing, int side) {
        Vec3d forward = Vec3d.of(facing.getVector());
        Vec3d right = new Vec3d(-forward.z, 0, forward.x);
        return Vec3d.ofBottomCenter(anchor).add(right.multiply(side * (WIDTH / 2.0 + 2.0)));
    }

    public static List<BlockPos> landingColumns(BlockPos anchor, Direction facing) {
        Vec3d center = Vec3d.ofBottomCenter(anchor);
        Vec3d end = Vec3d.of(facing.getVector()).multiply(LENGTH / 2.0 + 2.0);
        List<BlockPos> columns = new ArrayList<>();
        columns.add(BlockPos.ofFloored(shoreAt(anchor, facing, -1)));
        columns.add(BlockPos.ofFloored(shoreAt(anchor, facing, 1)));
        columns.add(BlockPos.ofFloored(center.add(end)));
        columns.add(BlockPos.ofFloored(center.subtract(end)));
        for (int side : new int[] {-1, 1}) {
            Vec3d shore = shoreAt(anchor, facing, side);
            columns.add(BlockPos.ofFloored(shore.add(end)));
            columns.add(BlockPos.ofFloored(shore.subtract(end)));
        }
        // The anchor itself remains a real solid block even when every nearby shore is unsuitable.
        columns.add(anchor.toImmutable());
        return List.copyOf(columns);
    }
}
