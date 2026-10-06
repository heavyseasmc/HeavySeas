package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.world.PlayerBodies;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

/** Each fall has its own authoritative intervention deadline. */
public final class OverboardPhase {
    /** 12 → 20 秒（用户 2026-10-07：决策窗口一律至少 20 秒，「有的时候决策时间太短」）。 */
    public static final long CHOOSE_MILLIS = 20_000L;

    private OverboardPhase() {
    }

    static void begin(ServerWorld world, GameComponent component) {
        var session = component.requireSession();
        var pending = session.overboardPending();
        if (pending.isEmpty()) {
            GameFlow.completeNavigation(world, component, session.navigationReport());
            return;
        }
        boolean choices = component.occupants().entrySet().stream().anyMatch(entry ->
                !entry.getValue().isDummy() && !session.overboardPlays(entry.getKey()).isEmpty());
        component.setOverboardDeadline(System.currentTimeMillis() + (choices ? CHOOSE_MILLIS : 1));
        var names = Text.empty();
        for (var id : pending.get().swimmers()) {
            if (!names.getSiblings().isEmpty()) {
                names.append(", ");
            }
            names.append(GameFlow.characterName(id));
        }
        GameFlow.broadcast(world, Text.translatable("heavyseas.overboard.pending", names));
        GameComponents.sync(world);
    }

    public static void tick(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            GameComponent component = GameComponents.of(world);
            long deadline = component.overboardDeadline();
            if (component.session().isEmpty() || deadline <= 0 || System.currentTimeMillis() < deadline) {
                continue;
            }
            component.setOverboardDeadline(0);
            var swimmers = component.requireSession().overboardPending().orElseThrow().swimmers();
            PlayerBodies.fall(world, component, swimmers);
            component.requireSession().finishOverboard();
            GameComponents.sync(world);
            GameFlow.schedule(component, component.anyHumanSeated() ? PlayerBodies.FALL_MILLIS : 0,
                    "overboard bodies return", () -> begin(world, component));
        }
    }
}
