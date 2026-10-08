package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.world.PlayerBodies;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

/** Each fall has its own authoritative intervention deadline. */
public final class OverboardPhase {
    /** 12 → 20 秒（用户 2026-10-07：决策窗口一律至少 20 秒，「有的时候决策时间太短」）。默认值；这一局实际用的在 {@link GameTiming}（ADR-0099 D8）。 */
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
        // 动脑 / 大模型的替身手上有救生圈 · 血饵时也要想一想：窗口照局里时限开（不是「等真人」的那一种），想完由它收
        boolean standIns = StandInMinds.thinks(component) && StandInMinds.anyStandInOverboard(session, component);
        component.clearDecided();
        // 总长随投影发给客户端画满格（ADR-0099 D8）；没人要选时 1 毫秒就到点，与原先一样
        component.openOverboardWindow(choices ? component.humanWindow(component.timing().overboardMs())   // 演示局里等真人不限时（用户 2026-10-07）
                : standIns ? component.timing().overboardMs() : 1);
        var names = Text.empty();
        for (var id : pending.get().swimmers()) {
            if (!names.getSiblings().isEmpty()) {
                names.append(", ");
            }
            names.append(GameFlow.characterName(id));
        }
        GameFlow.broadcast(world, Text.translatable("heavyseas.overboard.pending", names));
        GameComponents.sync(world);
        if (standIns) {
            StandInMinds.overboard(world, component);
        }
    }

    /** 每 tick 检查超时。经 {@link GameFlow#guarded} 挂上：出错只结束这一局。 */
    public static void tick(ServerWorld world, GameComponent component, long now) {
        long deadline = component.overboardDeadline();
        if (component.session().isEmpty() || deadline <= 0 || now < deadline) {
            return;
        }
        component.setOverboardDeadline(0);
        component.setStandInsOverboard(false);   // 这一窗结了：还在想的替身那一手落地时会核对窗口、作废
        var swimmers = component.requireSession().overboardPending().orElseThrow().swimmers();
        PlayerBodies.fall(world, component, swimmers);
        component.requireSession().finishOverboard();
        GameComponents.sync(world);
        GameFlow.schedule(component, component.anyHumanSeated() ? PlayerBodies.FALL_MILLIS : 0,
                "overboard bodies return", () -> begin(world, component));
    }
}
