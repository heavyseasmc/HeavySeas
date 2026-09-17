package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.game.GameFlow;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** M4's isolated all-ocean play space and the crash-safe boundary around player inventories. */
public final class MistSea {

    public static final RegistryKey<World> KEY = RegistryKey.of(
            RegistryKeys.WORLD, Identifier.of(HeavySeasMod.MOD_ID, "mist_sea"));
    public static final Vec3d BOAT_ORIGIN = new Vec3d(0.5, 65.15, 0.5);
    // Seats extend from the bow along yaw, while riders face yaw + 180. A 180° layout therefore
    // puts the bow and every rider toward +Z, the same direction as the fixed shore approach.
    public static final float BOAT_YAW = 180f;
    public static final Vec3d SHORE_DIRECTION = new Vec3d(0, 0, 1);
    public static final BlockPos SHORE_CENTER = new BlockPos(0, 63, 72);

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private MistSea() {
    }

    public static ServerWorld world(MinecraftServer server) {
        return server.getWorld(KEY);
    }

    /**
     * Crosses the only authoritative boundary into a match: escrow, empty inventory, teleport, then begin rules.
     * If any later step fails, every successfully escrowed player is restored before the exception escapes.
     */
    public static void startVoyage(MinecraftServer server, int players, List<ServerPlayerEntity> humans,
                                   Set<CharacterId> reservedForDummies) {
        ServerWorld sea = world(server);
        if (sea == null) {
            throw new IllegalStateException("heavyseas:mist_sea 维度未加载");
        }
        GameComponent component = GameComponents.of(sea);
        if (component.session().isPresent()) {
            throw new IllegalStateException("雾海中已有一局进行中");
        }
        if (!component.voyageEscrowPlayers().isEmpty()) {
            throw new IllegalStateException("仍有 %d 份上局的雾海托管未恢复，暂不能开新局"
                    .formatted(component.voyageEscrowPlayers().size()));
        }
        forceArena(sea, true);
        prepareShore(sea);
        List<ServerPlayerEntity> crossed = new ArrayList<>();
        try {
            for (ServerPlayerEntity player : humans) {
                escrow(component, player);
                crossed.add(player);
            }
            if (!crossed.isEmpty()) {
                checkpoint(server, "进入雾海前的物品托管");
            }
            for (ServerPlayerEntity player : crossed) {
                player.stopRiding();
                player.teleport(sea, BOAT_ORIGIN.x, BOAT_ORIGIN.y + 1.0, BOAT_ORIGIN.z,
                        BOAT_YAW + 180f, 0f);
            }
            GameFlow.start(sea, players, humans, reservedForDummies, BOAT_ORIGIN, BOAT_YAW);
            LOGGER.info("雾海：{} 名玩家已托管物品并进入独立维度", crossed.size());
        } catch (RuntimeException failure) {
            Gulls.clear(sea, component);
            Seats.clear(sea, component);
            if (component.session().isPresent()) {
                component.end();
                GameComponents.sync(sea);
            }
            for (ServerPlayerEntity player : crossed) {
                restore(component, player);
            }
            forceArena(sea, false);
            throw failure;
        }
    }

    private static void escrow(GameComponent component, ServerPlayerEntity player) {
        if (component.hasVoyageEscrow(player.getUuid())) {
            throw new IllegalStateException(player.getGameProfile().getName() + " 已有一份未恢复的雾海托管");
        }
        NbtList inventory = player.getInventory().writeNbt(new NbtList());
        component.putVoyageEscrow(new GameComponent.VoyageEscrow(player.getUuid(),
                player.getWorld().getRegistryKey().getValue().toString(),
                player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch(), inventory));
        player.getInventory().clear();
        player.getInventory().markDirty();
        player.playerScreenHandler.sendContentUpdates();
    }

    /** Normal end: online players return immediately; offline records remain until their next login. */
    public static void restoreAll(ServerWorld sea, GameComponent component) {
        int restored = 0;
        for (UUID id : new ArrayList<>(component.voyageEscrowPlayers())) {
            ServerPlayerEntity player = sea.getServer().getPlayerManager().getPlayer(id);
            if (player != null && restore(component, player)) {
                restored++;
            }
        }
        forceArena(sea, false);
        if (restored > 0) {
            checkpoint(sea.getServer(), "雾海结束后的物品恢复");
        }
    }

    /** Clears only the arena tickets owned by this mod after an interrupted server session. */
    public static void resetForceloads(MinecraftServer server) {
        ServerWorld sea = world(server);
        if (sea != null && GameComponents.of(sea).session().isEmpty()) {
            forceArena(sea, false);
        }
    }

    /** Login recovery path for a server which stopped during a match. Safe and idempotent. */
    public static void recover(ServerPlayerEntity player) {
        ServerWorld sea = world(player.server);
        if (sea == null) {
            return;
        }
        GameComponent component = GameComponents.of(sea);
        if (!component.hasVoyageEscrow(player.getUuid())) {
            return;
        }
        if (component.session().isPresent()) {
            // This is a reconnect, not crash recovery. The escrow must remain sealed until the match ends.
            if (!player.getWorld().getRegistryKey().equals(KEY)) {
                player.teleport(sea, BOAT_ORIGIN.x, BOAT_ORIGIN.y + 1.0, BOAT_ORIGIN.z,
                        BOAT_YAW + 180f, 0f);
            }
            GameComponents.sync(sea);
            return;
        }
        if (restore(component, player)) {
            checkpoint(player.server, "雾海异常中断恢复");
            player.sendMessage(Text.translatable("heavyseas.mist_sea.recovered"), false);
            LOGGER.info("雾海恢复：{} 的物品与返回位置已恢复", player.getGameProfile().getName());
        }
    }

    private static boolean restore(GameComponent component, ServerPlayerEntity player) {
        GameComponent.VoyageEscrow escrow = component.removeVoyageEscrow(player.getUuid()).orElse(null);
        if (escrow == null) {
            return false;
        }
        Identifier id = Identifier.tryParse(escrow.dimension());
        ServerWorld destination = id == null ? null : player.server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id));
        if (destination == null) {
            destination = player.server.getOverworld();
        }
        try {
            player.stopRiding();
            player.getInventory().clear();
            player.getInventory().readNbt(escrow.inventory().copy());
            player.getInventory().markDirty();
            player.playerScreenHandler.sendContentUpdates();
            player.removeStatusEffect(StatusEffects.BLINDNESS);
            player.teleport(destination, escrow.x(), escrow.y(), escrow.z(), escrow.yaw(), escrow.pitch());
            return true;
        } catch (RuntimeException failure) {
            // Never consume the only recovery copy on a partial restore.
            component.putVoyageEscrow(escrow);
            LOGGER.error("雾海恢复失败：{}", player.getGameProfile().getName(), failure);
            return false;
        }
    }

    /** Dense, particle-free fog for everyone inside the dimension; spectators see it too. */
    public static void tick(MinecraftServer server) {
        ServerWorld sea = world(server);
        if (sea == null) {
            return;
        }
        GameComponent component = GameComponents.of(sea);
        boolean fog = component.session().isPresent() && !component.fogCleared();
        for (ServerPlayerEntity player : sea.getPlayers()) {
            if (fog) {
                player.addStatusEffect(new StatusEffectInstance(
                        StatusEffects.BLINDNESS, 40, 0, true, false, false));
            } else {
                player.removeStatusEffect(StatusEffects.BLINDNESS);
            }
        }
    }

    /** The generated dimension is all ocean; this fixed terminal shore is the one deliberate exception. */
    private static void prepareShore(ServerWorld world) {
        for (int x = -15; x <= 15; x++) {
            for (int z = -12; z <= 12; z++) {
                double oval = x * x / 225.0 + z * z / 144.0;
                if (oval > 1.0) {
                    continue;
                }
                int height = oval < 0.35 ? 2 : oval < 0.72 ? 1 : 0;
                for (int y = -2; y <= height; y++) {
                    BlockPos at = SHORE_CENTER.add(x, y, z);
                    world.setBlockState(at, y == height ? Blocks.SAND.getDefaultState()
                            : Blocks.SANDSTONE.getDefaultState(), Block.NOTIFY_LISTENERS);
                }
            }
        }
    }

    private static void forceArena(ServerWorld world, boolean forced) {
        // Boat starts in chunk 0,0 and reaches z=32; the fixed shore occupies chunks z=3..5.
        // Keeping this narrow corridor loaded also makes headless/dummy verification deterministic.
        for (int chunkX = -1; chunkX <= 1; chunkX++) {
            for (int chunkZ = -1; chunkZ <= 5; chunkZ++) {
                world.setChunkForced(chunkX, chunkZ, forced);
            }
        }
    }

    private static void checkpoint(MinecraftServer server, String reason) {
        if (!server.save(false, true, false)) {
            LOGGER.warn("雾海存档检查点未报告成功：{}", reason);
        } else {
            LOGGER.info("雾海存档检查点：{}", reason);
        }
    }
}
