package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.audio.AudioCue;
import io.github.heavyseasmc.mod.audio.VoyageAudio;
import io.github.heavyseasmc.mod.data.SceneDataLoader;
import io.github.heavyseasmc.mod.data.VoyageLayout;
import io.github.heavyseasmc.mod.state.EndgameProgress;
import io.github.heavyseasmc.mod.state.GameComponent;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
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
        List<AudioCue> cues = new ArrayList<>();
        while (live.size() > desired) {
            UUID id = live.remove(live.size() - 1);
            Entity entity = world.getEntity(id);
            if (entity != null) {
                entity.discard();
            }
            cues.add(AudioCue.GULL_DEPART);
            LOGGER.info("Gull departed; {} flock entities remain", live.size());
        }
        while (live.size() < desired) {
            int slot = live.size();
            GullEntity gull = new GullEntity(GullEntity.TYPE, world);
            gull.configure(slot, boatCenter(world, component));
            if (!world.spawnEntity(gull)) {
                LOGGER.warn("Could not spawn gull at flock slot {}", slot + 1);
                break;
            }
            live.add(gull.getUuid());
            cues.add(AudioCue.GULL_CALL);
            LOGGER.info("Gull arrived; {} flock entities present", live.size());
        }
        component.setGullIds(live);
        if (arrival) {
            depart(world, component);
        }
        // Publish the complete flock before transport flush can reenter refresh.
        for (AudioCue cue : cues) {
            VoyageAudio.emit(world, component, cue, "");
        }
    }

    /** 四只齐飞：往布局里岸的方向去（决策 ⑮「鸟群指路」）。 */
    public static void depart(ServerWorld world, GameComponent component) {
        Vec3d toward = layoutOf(component).bearingVector();
        for (UUID id : component.gullIds()) {
            if (world.getEntity(id) instanceof GullEntity gull) {
                gull.depart(toward);
            }
        }
    }

    /** Use the voyage layout, or the default layout when no voyage snapshot exists. */
    private static VoyageLayout layoutOf(GameComponent component) {
        return component.layout().orElseGet(SceneDataLoader::defaultLayout);   // 这一局用开局快照（审查 2026-10-07 C6）
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
            VoyageAudio.emit(world, component, AudioCue.GULL_CALL, "");
        }
    }

    private static Vec3d boatCenter(ServerWorld world, GameComponent component) {
        if (!component.seatIds().isEmpty()) {
            Entity first = world.getEntity(component.seatIds().get(0));
            if (first != null) {
                return first.getPos();
            }
        }
        return layoutOf(component).boat().bow();
    }
}
