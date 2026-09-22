package io.github.heavyseasmc.mod.world;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LobbyBoatLandingTest {

    private static final BlockPos ANCHOR = new BlockPos(26, -60, 24);

    @Test
    void blockedSidesContinueToTheBowAndStopAtTheFirstSafeGround() {
        List<BlockPos> visited = new ArrayList<>();
        List<BlockPos> candidates = LobbyBoatGeometry.landingColumns(ANCHOR, Direction.NORTH);
        Vec3d bow = Vec3d.ofBottomCenter(candidates.get(2));
        var landing = LobbyBoatLanding.firstSafe(ANCHOR, Direction.NORTH, column -> {
            visited.add(column);
            return column.equals(candidates.get(2)) ? bow : null;
        });
        assertEquals(bow, landing.orElseThrow());
        assertEquals(candidates.subList(0, 3), visited);
        assertEquals(-60.0, landing.orElseThrow().y);
    }

    @Test
    void noSafeShoreFallsBackToExistingAnchorBlockInsteadOfTheHighSeat() {
        Vec3d anchorTop = Vec3d.ofBottomCenter(ANCHOR.up());
        var landing = LobbyBoatLanding.firstSafe(ANCHOR, Direction.NORTH,
                column -> column.equals(ANCHOR) ? anchorTop : null);
        assertEquals(anchorTop, landing.orElseThrow());
        assertEquals(-59.0, landing.orElseThrow().y);
        assertTrue(landing.orElseThrow().y < LobbyBoatGeometry.seatAt(ANCHOR, Direction.NORTH, 0).y);
    }

    @Test
    void entirelyUnsafeTerrainHasNoFabricatedFloatingReturnPoint() {
        var landing = LobbyBoatLanding.firstSafe(ANCHOR, Direction.NORTH, column -> null);
        assertTrue(landing.isEmpty(), "Voyage escrow must reject this, not store a high seat fallback");
    }

    @Test
    void everyFacingChecksFourSidesFourCornersThenTheAnchor() {
        for (Direction facing : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            List<BlockPos> columns = LobbyBoatGeometry.landingColumns(ANCHOR, facing);
            assertEquals(9, columns.size());
            assertEquals(9, new HashSet<>(columns).size());
            assertEquals(ANCHOR, columns.getLast());
            for (BlockPos outside : columns.subList(0, 8)) {
                assertFalse(LobbyBoatGeometry.bounds(ANCHOR, facing).contains(Vec3d.ofBottomCenter(outside)));
            }
        }
    }
}
