package io.github.heavyseasmc.mod.world;

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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Eight-seat, sit-to-register lobby with a right-click bell start. No role exists before start. */
public final class LobbyBoat {

    private LobbyBoat() {
    }

    public static ActionResult use(World rawWorld, BlockPos pos, BlockState state, PlayerEntity rawPlayer) {
        if (!(rawWorld instanceof ServerWorld world) || !(rawPlayer instanceof ServerPlayerEntity player)) {
            return ActionResult.PASS;
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
            world.playSound(null, pos, SoundEvents.BLOCK_BELL_USE, SoundCategory.BLOCKS, 1f, 1f);
            try {
                // Registered players stay first: GameFlow assigns only the first N humans. Nearby lobby
                // bystanders cross after them as spectators and therefore see the same finale without a role.
                List<ServerPlayerEntity> audience = new ArrayList<>(registered);
                Vec3d bell = Vec3d.ofCenter(pos);
                for (ServerPlayerEntity nearby : world.getPlayers(
                        candidate -> candidate.squaredDistanceTo(bell) <= 16.0 * 16.0)) {
                    if (!audience.contains(nearby)) {
                        audience.add(nearby);
                    }
                }
                MistSea.startVoyage(world.getServer(), registered.size(), audience, Set.of());
                return ActionResult.SUCCESS;
            } catch (RuntimeException failure) {
                player.sendMessage(Text.literal(String.valueOf(failure.getMessage())), false);
                return ActionResult.FAIL;
            }
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

    public static void clear(World rawWorld, BlockPos pos) {
        if (!(rawWorld instanceof ServerWorld world)) {
            return;
        }
        for (SeatEntity seat : seatsAt(world, pos)) {
            seat.removeAllPassengers();
            seat.discard();
        }
    }

    private static List<SeatEntity> ensureSeats(ServerWorld world, BlockPos anchor, Direction facing) {
        List<SeatEntity> found = seatsAt(world, anchor);
        boolean[] occupied = new boolean[8];
        for (SeatEntity seat : found) {
            if (seat.index() >= 0 && seat.index() < occupied.length) {
                occupied[seat.index()] = true;
            }
        }
        Vec3d forward = Vec3d.of(facing.getVector());
        Vec3d right = new Vec3d(-forward.z, 0, forward.x);
        Vec3d center = Vec3d.ofBottomCenter(anchor).add(0, 0.35, 0);
        for (int index = 0; index < 8; index++) {
            if (occupied[index]) {
                continue;
            }
            int row = index / 2;
            double side = index % 2 == 0 ? -0.62 : 0.62;
            Vec3d at = center.add(forward.multiply(row - 1.5)).add(right.multiply(side));
            SeatEntity seat = new SeatEntity(SeatEntity.TYPE, world);
            seat.setIndex(index);
            seat.markLobby(anchor);
            seat.refreshPositionAndAngles(at.x, at.y, at.z, facing.asRotation() + 180f, 0f);
            if (world.spawnEntity(seat)) {
                found.add(seat);
            }
        }
        found.sort(Comparator.comparingInt(SeatEntity::index));
        return found;
    }

    private static List<SeatEntity> seatsAt(ServerWorld world, BlockPos anchor) {
        Box area = new Box(anchor).expand(6.0, 3.0, 6.0);
        return new ArrayList<>(world.getEntitiesByType(SeatEntity.TYPE, area,
                seat -> seat.lobby() && seat.lobbyAnchor().equals(anchor)));
    }

    private static List<ServerPlayerEntity> registered(List<SeatEntity> seats) {
        List<ServerPlayerEntity> players = new ArrayList<>();
        for (SeatEntity seat : seats) {
            for (Entity passenger : seat.getPassengerList()) {
                if (passenger instanceof ServerPlayerEntity player) {
                    players.add(player);
                }
            }
        }
        return players;
    }
}
