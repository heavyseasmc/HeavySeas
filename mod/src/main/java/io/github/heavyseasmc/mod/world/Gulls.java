package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.state.EndgameProgress;
import io.github.heavyseasmc.mod.state.GameComponent;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Projects the engine gull counter into an equal-sized flock of real sky entities. */
public final class Gulls {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private Gulls() {
    }

    public static void refresh(ServerWorld world, GameComponent component) {
        if (component.session().isEmpty()) {
            clear(world, component);
            return;
        }
        boolean arrival = component.endgame()
                .map(e -> e.stage() == EndgameProgress.Stage.ARRIVAL)
                .orElse(false);
        int desired = arrival ? 4 : component.requireSession().state().gulls();
        List<UUID> live = component.gullIds().stream()
                .filter(id -> world.getEntity(id) instanceof GullEntity)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        while (live.size() > desired) {
            UUID id = live.remove(live.size() - 1);
            Entity entity = world.getEntity(id);
            if (entity != null) {
                entity.discard();
            }
            sound(world, Math.max(1, live.size()));
            LOGGER.info("海鸥：离开一只，现有 {} 只实体", live.size());
        }
        while (live.size() < desired) {
            int slot = live.size();
            GullEntity gull = new GullEntity(GullEntity.TYPE, world);
            gull.configure(slot, boatCenter(world, component));
            if (!world.spawnEntity(gull)) {
                LOGGER.warn("海鸥实体生成失败：第 {} 只", slot + 1);
                break;
            }
            live.add(gull.getUuid());
            sound(world, live.size());
            LOGGER.info("海鸥：抵达一只，现有 {} 只实体", live.size());
        }
        component.setGullIds(live);
        if (arrival) {
            depart(world, component);
        }
    }

    public static void depart(ServerWorld world, GameComponent component) {
        for (UUID id : component.gullIds()) {
            if (world.getEntity(id) instanceof GullEntity gull) {
                gull.depart();
            }
        }
    }

    public static void clear(ServerWorld world, GameComponent component) {
        for (UUID id : component.gullIds()) {
            Entity entity = world.getEntity(id);
            if (entity != null) {
                entity.discard();
            }
        }
        component.setGullIds(List.of());
    }

    /** Calls become denser as the flock grows: 0 gulls are silent, 3–4 call every two seconds. */
    public static void tick(MinecraftServer server) {
        ServerWorld world = MistSea.world(server);
        if (world == null) {
            return;
        }
        GameComponent component = io.github.heavyseasmc.mod.state.GameComponents.of(world);
        int count = component.gullIds().size();
        if (count > 0 && server.getTicks() % Math.max(40, 160 - count * 30) == 0) {
            sound(world, count);
        }
    }

    private static Vec3d boatCenter(ServerWorld world, GameComponent component) {
        if (!component.seatIds().isEmpty()) {
            Entity first = world.getEntity(component.seatIds().get(0));
            if (first != null) {
                return first.getPos();
            }
        }
        return MistSea.BOAT_ORIGIN;
    }

    private static void sound(ServerWorld world, int count) {
        Vec3d at = boatCenter(world, io.github.heavyseasmc.mod.state.GameComponents.of(world));
        world.playSound(null, at.x, at.y + 4, at.z, SoundEvents.ENTITY_PARROT_AMBIENT,
                SoundCategory.AMBIENT, Math.min(1.5f, 0.35f + count * 0.25f), 1.35f);
    }
}
