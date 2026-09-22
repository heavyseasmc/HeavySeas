package io.github.heavyseasmc.mod.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LobbyBoatGeometryTest {

    private static final BlockPos ANCHOR = new BlockPos(26, -60, 24);
    private static final List<Direction> FACINGS =
            List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);

    @Test
    void worldScaleIsTenTimesTheAssetAndTheKeelRestsOnPlacementGround() throws IOException {
        assertEquals(10f, LobbyBoatGeometry.WORLD_SCALE);
        for (Direction facing : FACINGS) {
            Box actual = null;
            for (Box part : parts(facing)) {
                actual = actual == null ? part : actual.union(part);
            }
            Box bounds = LobbyBoatGeometry.bounds(ANCHOR, facing);
            assertEquals(actual, bounds, "Selectable hull must match all rendered model parts");
            assertEquals(25.0, bounds.getLengthY());
            assertEquals(-60.0, bounds.minY);
            assertEquals(facing.getAxis() == Direction.Axis.Z ? 22.5 : 30.0, bounds.getLengthX());
            assertEquals(facing.getAxis() == Direction.Axis.Z ? 30.0 : 22.5, bounds.getLengthZ());
        }
    }

    @Test
    void allEightPassengersSitAboveVisibleDeckAndClearOfTheCabins() throws IOException {
        for (Direction facing : FACINGS) {
            List<Box> parts = parts(facing);
            HashSet<Vec3d> positions = new HashSet<>();
            for (int index = 0; index < LobbyBoatGeometry.SEAT_COUNT; index++) {
                Vec3d seat = LobbyBoatGeometry.seatAt(ANCHOR, facing, index);
                assertTrue(positions.add(seat), "Each registration needs its own position");
                double deckY = seat.y - LobbyBoatGeometry.SEAT_LIFT;
                assertEquals(ANCHOR.getY() + 8.125, deckY);
                assertTrue(parts.stream().anyMatch(part -> Math.abs(part.maxY - deckY) < 0.001
                                && part.minX < seat.x && part.maxX > seat.x
                                && part.minZ < seat.z && part.maxZ > seat.z),
                        "Every seat must have a rendered deck directly underneath");
                // Vanilla's 0.3-high seat attachment puts player feet 0.3 below the seat origin.
                Box player = new Box(seat.x - 0.3, seat.y - 0.3, seat.z - 0.3,
                        seat.x + 0.3, seat.y - 0.3 + 1.8, seat.z + 0.3);
                assertFalse(parts.stream().anyMatch(player::intersects),
                        "No cabin, rail, or hull part may intersect the seated player");
                assertTrue(LobbyBoatGeometry.searchBounds(ANCHOR).contains(seat));
            }
        }
    }

    @Test
    void hullCanBeClickedWithinVanillaReachWithoutReachingItsAnchor() {
        Vec3d eye = new Vec3d(13.5, -58.38, 24.5);
        Box hull = LobbyBoatGeometry.bounds(ANCHOR, Direction.NORTH);
        assertEquals(1.75 * 1.75, hull.squaredMagnitude(eye), 0.0001);
        assertTrue(hull.squaredMagnitude(eye) < 3.0 * 3.0);
        assertTrue(Vec3d.ofCenter(ANCHOR).squaredDistanceTo(eye) > 4.0 * 4.0);
        for (int index = 0; index < LobbyBoatGeometry.SEAT_COUNT; index++) {
            Vec3d seatedEye = LobbyBoatGeometry.seatAt(ANCHOR, Direction.NORTH, index).add(0, 1.32, 0);
            assertEquals(0.0, hull.squaredMagnitude(seatedEye));
        }
    }

    @Test
    void dismountCandidatesAreOutsideTheHullForEveryFacing() {
        for (Direction facing : FACINGS) {
            Box hull = LobbyBoatGeometry.bounds(ANCHOR, facing);
            for (int side : new int[] {-1, 1}) {
                Vec3d shore = LobbyBoatGeometry.shoreAt(ANCHOR, facing, side);
                assertFalse(hull.expand(0.3).contains(shore));
                assertEquals(4.0, hull.squaredMagnitude(shore), 0.0001);
            }
        }
    }

    @Test
    void seatIndicesCannotSilentlyPlacePassengersOutsideTheBoat() {
        assertThrows(IllegalArgumentException.class,
                () -> LobbyBoatGeometry.seatAt(ANCHOR, Direction.NORTH, -1));
        assertThrows(IllegalArgumentException.class,
                () -> LobbyBoatGeometry.seatAt(ANCHOR, Direction.NORTH, 8));
    }

    private static List<Box> parts(Direction facing) throws IOException {
        JsonObject model = JsonParser.parseString(Files.readString(Path.of("src", "main", "resources",
                "assets", "heavyseas", "models", "block", "lobby_boat.json"))).getAsJsonObject();
        List<Box> parts = new ArrayList<>();
        for (JsonElement element : model.getAsJsonArray("elements")) {
            JsonObject part = element.getAsJsonObject();
            var from = part.getAsJsonArray("from");
            var to = part.getAsJsonArray("to");
            parts.add(new Box(LobbyBoatGeometry.modelPoint(ANCHOR, facing, from.get(0).getAsDouble(),
                            from.get(1).getAsDouble(), from.get(2).getAsDouble()),
                    LobbyBoatGeometry.modelPoint(ANCHOR, facing, to.get(0).getAsDouble(),
                            to.get(1).getAsDouble(), to.get(2).getAsDouble())));
        }
        return parts;
    }
}
