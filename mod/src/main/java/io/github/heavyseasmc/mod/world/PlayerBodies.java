package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.mod.data.SceneDataLoader;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.world.GameMode;
import net.minecraft.world.Heightmap;

import java.util.List;

/** Minecraft bodies are a projection; only the engine can inflict game wounds. */
public final class PlayerBodies {
    public static final long FALL_MILLIS = 3_000L;

    private PlayerBodies() {
    }

    public static void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof ServerPlayerEntity player) || !protectedBody(player));
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity player && protectedBody(player)) {
                player.setHealth(1);
                return false;
            }
            return true;
        });
    }

    /** 对局里的人，与过了魔镜在北辰号上的人（ADR-0083，用户 2026-10-03 定：船上冒险模式、不受伤）。 */
    private static boolean protectedBody(ServerPlayerEntity player) {
        for (ServerWorld world : player.server.getWorlds()) {
            GameComponent component = GameComponents.of(world);
            if (component.belongsToActiveVoyage(player.getUuid())
                    || component.voyageEscrow(player.getUuid()).map(GameComponent.VoyageEscrow::viaMirror).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 上了北辰号（过魔镜，或散局回船）：冒险模式（船是大家的大厅，挖不得）、满血满饱；血量上限回到自己的（对局里按角色体型改过）。
     */
    public static void aboard(ServerPlayerEntity player, java.util.Optional<GameComponent.BodySnapshot> own) {
        double max = own.map(GameComponent.BodySnapshot::maxHealth).orElse(20d);
        player.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(max);
        player.setHealth(player.getMaxHealth());
        player.setAbsorptionAmount(0);
        player.getHungerManager().setFoodLevel(20);
        player.getHungerManager().setSaturationLevel(5);
        player.setAir(player.getMaxAir());
        player.setFireTicks(0);
        player.fallDistance = 0;
        player.removeStatusEffect(net.minecraft.entity.effect.StatusEffects.BLINDNESS);
        if (player.interactionManager.getGameMode() != GameMode.ADVENTURE) {
            player.changeGameMode(GameMode.ADVENTURE);
        }
    }

    public static GameComponent.BodySnapshot capture(ServerPlayerEntity player) {
        NbtCompound hunger = new NbtCompound();
        player.getHungerManager().writeNbt(hunger);
        return new GameComponent.BodySnapshot(
                player.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).getBaseValue(),
                player.getHealth(), player.getAbsorptionAmount(),
                player.interactionManager.getGameMode().getName(), hunger);
    }

    public static void restore(ServerPlayerEntity player, GameComponent.BodySnapshot snapshot) {
        player.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(snapshot.maxHealth());
        player.changeGameMode(GameMode.byName(snapshot.gameMode(), GameMode.SURVIVAL));
        player.setHealth(Math.max(1, Math.min(snapshot.health(), player.getMaxHealth())));
        player.setAbsorptionAmount(snapshot.absorption());
        player.getHungerManager().readNbt(snapshot.hunger().copy());
        player.setAir(player.getMaxAir());
        player.setFireTicks(0);
        player.fallDistance = 0;
    }

    public static float mirrorHealth(int size, int wounds) {
        return Math.max(1, (size - wounds) * 2f);
    }

    public static void tick(MinecraftServer server) {
        if (server.getTicks() % 20 == 0) {
            keepAboard(server);
        }
        for (ServerWorld world : server.getWorlds()) {
            GameComponent component = GameComponents.of(world);
            if (component.session().isEmpty()) {
                continue;
            }
            var state = component.requireSession().state();
            for (var entry : component.occupants().entrySet()) {
                if (entry.getValue().isDummy() || state.isOffline(entry.getKey())) {
                    continue;
                }
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getValue().player());
                if (player == null) {
                    continue;
                }
                CharacterId id = entry.getKey();
                player.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH)
                        .setBaseValue(state.roster().get(id).size() * 2d);
                player.setHealth(mirrorHealth(state.roster().get(id).size(), state.stateOf(id).damage()));
                player.setAbsorptionAmount(0);
                player.getHungerManager().setFoodLevel(20);
                player.getHungerManager().setSaturationLevel(5);
                player.setAir(player.getMaxAir());
                player.setFireTicks(0);
                player.fallDistance = 0;
                if (state.isRemoved(id)) {
                    player.stopRiding();
                    player.changeGameMode(GameMode.SPECTATOR);
                    component.layoutId().ifPresent(layoutId -> {
                        var layout = SceneDataLoader.require(layoutId);
                        if (player.getServerWorld() != world || player.getPos().distanceTo(layout.boat().bow()) < 32) {
                            Vec3d point = layout.boat().bow().subtract(layout.boatForward().multiply(48)).add(0, 8, 0);
                            player.teleport(world, point.x, point.y, point.z, layout.ridersFacing(), 0);
                        }
                    });
                } else if (player.interactionManager.getGameMode() != GameMode.ADVENTURE) {
                    player.changeGameMode(GameMode.ADVENTURE);
                }
            }
            Seats.refresh(world, component);
        }
    }

    /** 船上的人（过了魔镜、不在对局里）一秒核一次：冒险模式、不饿（用户定：船上冒险模式）。 */
    private static void keepAboard(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            GameComponent component = GameComponents.of(world);
            for (java.util.UUID id : component.voyageEscrowPlayers()) {
                if (component.belongsToActiveVoyage(id) || !component.voyageEscrow(id).map(GameComponent.VoyageEscrow::viaMirror).orElse(false)) {
                    continue;
                }
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
                // 有 OP 权限、自己切到创造模式的人不改回冒险（2026-10-07 用户在船上用 Axiom 搭船，每秒被改回冒险）：普通玩家切不了创造，规矩照旧
                if (player == null || player.isSpectator() || (player.isCreative() && player.hasPermissionLevel(2))) {
                    continue;
                }
                if (player.interactionManager.getGameMode() != GameMode.ADVENTURE) {
                    player.changeGameMode(GameMode.ADVENTURE);
                }
                player.getHungerManager().setFoodLevel(20);
            }
        }
    }

    public static void fall(ServerWorld world, GameComponent component, List<CharacterId> swimmers) {
        component.setWaterBodies(swimmers, System.currentTimeMillis() + FALL_MILLIS);
        if (component.layoutId().isEmpty()) {
            return;
        }
        var layout = SceneDataLoader.require(component.layoutId().get());
        var state = component.requireSession().state();
        Vec3d forward = layout.boatForward();
        Vec3d side = new Vec3d(forward.z, 0, -forward.x);
        for (CharacterId id : swimmers) {
            var occupant = component.occupantOf(id);
            if (occupant.isEmpty() || occupant.get().isDummy() || state.isOffline(id)) {
                continue;
            }
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(occupant.get().player());
            if (player != null) {
                int index = state.bySeat().indexOf(id);
                for (int distance = 7; distance <= 15; distance += 2) {
                    Vec3d point = layout.seatAt(index).add(side.multiply(index % 2 == 0 ? distance : -distance));
                    BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                            BlockPos.ofFloored(point));
                    if (world.getFluidState(top.down()).isIn(FluidTags.WATER)) {
                        player.stopRiding();
                        player.teleport(world, point.x, top.getY() + 0.5, point.z, layout.ridersFacing(), 0);
                        break;
                    }
                }
            }
        }
    }
}
