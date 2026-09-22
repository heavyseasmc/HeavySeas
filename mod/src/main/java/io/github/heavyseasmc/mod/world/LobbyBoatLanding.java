package io.github.heavyseasmc.mod.world;

import net.minecraft.entity.Dismounting;
import net.minecraft.entity.EntityType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.Optional;
import java.util.function.Function;

/** Reads existing solid ground only, shared by dismounting and the persisted voyage return point. */
final class LobbyBoatLanding {

    private LobbyBoatLanding() {
    }

    static Optional<Vec3d> find(World world, EntityType<?> passenger, BlockPos anchor, Direction facing) {
        return firstSafe(anchor, facing, column -> {
            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
            return Dismounting.findRespawnPos(passenger, world,
                    new BlockPos(column.getX(), y, column.getZ()), true);
        });
    }

    static Optional<Vec3d> firstSafe(BlockPos anchor, Direction facing, Function<BlockPos, Vec3d> ground) {
        for (BlockPos column : LobbyBoatGeometry.landingColumns(anchor, facing)) {
            Vec3d safe = ground.apply(column);
            if (safe != null) {
                return Optional.of(safe);
            }
        }
        return Optional.empty();
    }
}
