package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.mod.data.GameDataLoader;
import io.github.heavyseasmc.mod.net.RosterConfigS2C;
import io.github.heavyseasmc.mod.net.StartVoyageC2S;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Eight-seat cruise-ship lobby with a right-click bell start. No role exists before start. */
public final class LobbyBoat {

    private LobbyBoat() {
    }

    public static ActionResult use(World rawWorld, BlockPos pos, BlockState state, PlayerEntity rawPlayer) {
        if (!(rawWorld instanceof ServerWorld world) || !(rawPlayer instanceof ServerPlayerEntity player)) {
            return ActionResult.PASS;
        }
        if (player.isSpectator() || !player.canModifyAt(world, pos)) {
            return ActionResult.FAIL;
        }
        if (!world.getRegistryKey().equals(World.OVERWORLD)) {
            player.sendMessage(Text.translatable("heavyseas.lobby.overworld_only"), true);
            return ActionResult.FAIL;
        }
        ServerWorld sea = MistSea.world(world.getServer());
        if (sea == null) {
            player.sendMessage(Text.literal("heavyseas:mist_sea 维度未加载"), true);
            return ActionResult.FAIL;
        }
        if (GameComponents.of(sea).session().isPresent()) {
            player.sendMessage(Text.translatable("heavyseas.command.already_running"), true);
            return ActionResult.FAIL;
        }
        List<SeatEntity> seats = ensureSeats(world, pos, state.get(LobbyBoatBlock.FACING));
        boolean aboardHere = player.getVehicle() instanceof SeatEntity seat
                && seat.lobby() && seat.lobbyAnchor().equals(pos);
        if (aboardHere) {
            List<ServerPlayerEntity> registered = registered(seats);
            if (registered.size() < 6 || registered.size() > 8) {
                player.sendMessage(Text.translatable("heavyseas.lobby.need_players", registered.size()), true);
                return ActionResult.FAIL;
            }
            ServerPlayNetworking.send(player, RosterConfigS2C.from(pos.asLong(), registered.size(),
                    GameDataLoader.require().roster()));
            return ActionResult.SUCCESS;
        }

        SeatEntity open = seats.stream().filter(seat -> !seat.hasPassengers()).findFirst().orElse(null);
        if (open == null) {
            player.sendMessage(Text.translatable("heavyseas.lobby.full"), true);
            return ActionResult.FAIL;
        }
        player.stopRiding();
        player.teleport(world, open.getX(), open.getY(), open.getZ(), open.getYaw(), 0f);
        if (!player.startRiding(open, true)) {
            return ActionResult.FAIL;
        }
        int count = registered(seats).size();
        player.sendMessage(Text.translatable("heavyseas.lobby.registered", count), true);
        return ActionResult.SUCCESS;
    }

    /** 收到面板确认包后重新核对船、座位、人数与阵容，再真正敲铃开局。 */
    public static void launch(ServerPlayerEntity player, StartVoyageC2S request) {
        if (player.isSpectator()) {
            return;
        }
        if (!(player.getWorld() instanceof ServerWorld world) || !world.getRegistryKey().equals(World.OVERWORLD)) {
            return;
        }
        BlockPos pos = BlockPos.fromLong(request.anchor());
        if (!player.canModifyAt(world, pos)) {
            return;
        }
        BlockState state = world.getBlockState(pos);
        if (!state.isOf(LobbyBoatBlock.BLOCK)) {
            return;
        }
        List<SeatEntity> seats = ensureSeats(world, pos, state.get(LobbyBoatBlock.FACING));
        boolean aboardHere = player.getVehicle() instanceof SeatEntity seat
                && seat.lobby() && seat.lobbyAnchor().equals(pos);
        List<ServerPlayerEntity> registered = registered(seats);
        if (!aboardHere || registered.size() < 6 || registered.size() > 8) {
            player.sendMessage(Text.translatable("heavyseas.lobby.need_players", registered.size()), true);
            return;
        }
        List<CharacterId> selected;
        try {
            selected = request.characters().stream().map(CharacterId::of).toList();
            GameDataLoader.require().roster().select(selected);
            if (selected.size() != registered.size()) {
                throw new IllegalArgumentException("阵容人数必须与已报名人数一致");
            }
            ServerWorld sea = MistSea.world(world.getServer());
            if (sea == null || GameComponents.of(sea).session().isPresent()) {
                throw new IllegalStateException("雾海不可用或已有一局进行中");
            }
            List<ServerPlayerEntity> audience = new ArrayList<>(registered);
            Vec3d bell = Vec3d.ofCenter(pos);
            for (ServerPlayerEntity nearby : world.getPlayers(
                    candidate -> candidate.squaredDistanceTo(bell) <= 16.0 * 16.0)) {
                if (!audience.contains(nearby)) {
                    audience.add(nearby);
                }
            }
            world.playSound(null, pos, SoundEvents.BLOCK_BELL_USE, SoundCategory.BLOCKS, 1f, 1f);
            MistSea.startVoyage(world.getServer(), registered.size(), audience, Set.of(), selected);
        } catch (RuntimeException failure) {
            player.sendMessage(Text.literal(String.valueOf(failure.getMessage())), true);
        }
    }

    public static void clear(World rawWorld, BlockPos pos) {
        if (!(rawWorld instanceof ServerWorld world)) {
            return;
        }
        for (SeatEntity seat : seatsAt(world, pos)) {
            seat.removeAllPassengers();
            seat.discard();
        }
        for (LobbyBoatEntity hull : hullsAt(world, pos)) {
            hull.discard();
        }
    }

    /** Rebuild only disposable interaction state; merely loading a boat must not create registrations. */
    public static void maintain(ServerWorld world, BlockPos anchor, Direction facing) {
        List<LobbyBoatEntity> hulls = hullsAt(world, anchor);
        LobbyBoatEntity hull;
        if (hulls.isEmpty()) {
            hull = new LobbyBoatEntity(LobbyBoatEntity.TYPE, world);
            hull.place(anchor, facing);
            world.spawnEntity(hull);
        } else {
            hull = hulls.getFirst();
            hull.place(anchor, facing);
            hulls.stream().skip(1).forEach(Entity::discard);
        }
        reconcileSeats(world, anchor, facing);
    }

    private static List<SeatEntity> ensureSeats(ServerWorld world, BlockPos anchor, Direction facing) {
        List<SeatEntity> found = reconcileSeats(world, anchor, facing);
        boolean[] occupied = new boolean[LobbyBoatGeometry.SEAT_COUNT];
        for (SeatEntity seat : found) {
            occupied[seat.index()] = true;
        }
        for (int index = 0; index < occupied.length; index++) {
            if (occupied[index]) {
                continue;
            }
            Vec3d at = LobbyBoatGeometry.seatAt(anchor, facing, index);
            SeatEntity seat = new SeatEntity(SeatEntity.TYPE, world);
            seat.setIndex(index);
            seat.markLobby(anchor);
            seat.refreshPositionAndAngles(at.x, at.y, at.z, facing.asRotation(), 0f);
            if (world.spawnEntity(seat)) {
                found.add(seat);
            }
        }
        found.sort(Comparator.comparingInt(SeatEntity::index));
        return found;
    }

    private static List<SeatEntity> reconcileSeats(ServerWorld world, BlockPos anchor, Direction facing) {
        List<SeatEntity> found = seatsAt(world, anchor);
        found.sort(Comparator.comparing((SeatEntity seat) -> !seat.hasPassengers()));
        boolean[] occupied = new boolean[LobbyBoatGeometry.SEAT_COUNT];
        List<SeatEntity> retained = new ArrayList<>();
        for (SeatEntity seat : found) {
            int index = seat.index();
            if (index < 0 || index >= occupied.length || occupied[index]) {
                seat.removeAllPassengers();
                seat.discard();
                continue;
            }
            occupied[index] = true;
            Vec3d expected = LobbyBoatGeometry.seatAt(anchor, facing, index);
            if (seat.squaredDistanceTo(expected) > 0.0001 || seat.getYaw() != facing.asRotation()) {
                seat.refreshPositionAndAngles(expected.x, expected.y, expected.z, facing.asRotation(), 0f);
            }
            retained.add(seat);
        }
        retained.sort(Comparator.comparingInt(SeatEntity::index));
        return retained;
    }

    private static List<LobbyBoatEntity> hullsAt(ServerWorld world, BlockPos anchor) {
        return new ArrayList<>(world.getEntitiesByType(LobbyBoatEntity.TYPE,
                LobbyBoatGeometry.searchBounds(anchor), hull -> hull.anchor().equals(anchor)));
    }

    private static List<SeatEntity> seatsAt(ServerWorld world, BlockPos anchor) {
        Box area = LobbyBoatGeometry.searchBounds(anchor);
        return new ArrayList<>(world.getEntitiesByType(SeatEntity.TYPE, area,
                seat -> seat.lobby() && seat.lobbyAnchor().equals(anchor)));
    }

    private static List<ServerPlayerEntity> registered(List<SeatEntity> seats) {
        List<ServerPlayerEntity> players = new ArrayList<>();
        for (SeatEntity seat : seats) {
            for (Entity passenger : seat.getPassengerList()) {
                if (passenger instanceof ServerPlayerEntity player && !player.isSpectator()) {
                    players.add(player);
                }
            }
        }
        return players;
    }
}
