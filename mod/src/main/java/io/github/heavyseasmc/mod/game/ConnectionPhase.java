package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.Optional;

/** Connection changes affect rule eligibility, never the character's wounds. */
public final class ConnectionPhase {
    private ConnectionPhase() {
    }

    public static void connected(ServerPlayerEntity player, boolean connected) {
        for (ServerWorld world : player.server.getWorlds()) {
            GameComponent component = GameComponents.of(world);
            Optional<CharacterId> seat = component.seatOf(player.getUuid());
            if (component.session().isEmpty() || seat.isEmpty()) {
                continue;
            }
            Session session = component.requireSession();
            CharacterId who = seat.get();
            Optional<CharacterId> holder = session.provisionHolder();
            Optional<CharacterId> actor = session.nextActor();
            Optional<CharacterId> helm = session.state().helmsman();
            boolean rowing = session.rower().map(who::equals).orElse(false);
            session.setOffline(who, !connected);
            if (!connected) {
                component.removeThirstDonor(who);
                if (component.designating().map(who::equals).orElse(false)) {
                    DesignationPhase.finish(world, component, who);
                    player.setGlowing(false);
                }
                if (component.provisionTargeter().map(who::equals).orElse(false)) {
                    component.clearProvisionTarget();
                }
            }
            if (session.state().isOver()) {
                GameComponents.sync(world);
                continue;
            }
            if (session.state().phase() == Phase.PROVISION) {
                if (!holder.equals(session.provisionHolder())) {
                    ProvisionPhase.resume(world, component);
                } else if (connected) {
                    ProvisionPhase.resend(world, component);
                }
            } else if (session.contest().isPresent()) {
                ContestPhase.connectionChanged(world, component);
            } else if (session.state().phase() == Phase.ACTION && session.rower().isEmpty()
                    && component.designating().isEmpty()
                    && (rowing || !actor.equals(session.nextActor()))) {
                component.clearProvisionTarget();
                GameFlow.announceTurn(world, component);
            } else if (component.helmDeadline() > 0 && !helm.equals(session.state().helmsman())) {
                component.clearHelm();
                NavigationPhase.begin(world, component);
            }
            GameComponents.sync(world);
        }
    }
}
