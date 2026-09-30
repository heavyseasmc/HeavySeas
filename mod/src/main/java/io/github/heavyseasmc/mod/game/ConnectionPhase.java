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
            }
            // ❗舵手挑牌窗口里有人掉线 / 重连不换舵手、不重开（ADR-0051 B5，用户 2026-10-01 拍板）：
            //   开窗那一刻的舵手记在组件里（GameComponent#helmSeat），他掉线就等超时按默认挑。
            //   2adbbe4 那一版在这里换人并重开 12 秒 —— 暗牌给到两个人看，反复断连能让计时一直重开。
            GameComponents.sync(world);
        }
    }
}
