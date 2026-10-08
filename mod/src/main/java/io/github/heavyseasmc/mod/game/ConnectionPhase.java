package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Connection changes affect rule eligibility, never the character's wounds. */
public final class ConnectionPhase {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private ConnectionPhase() {
    }

    /** 只在服务端线程上调（{@code HeavySeasMod} 的 JOIN / DISCONNECT 都经 {@code server.execute} 转过来，审查 2026-10-07 C4）。 */
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
                List<String> closed = windowsAfterDisconnect(world, component, who, System.currentTimeMillis());
                if (!closed.isEmpty()) {
                    // 与语言无关的一行：掉线收了哪几扇窗
                    LOGGER.info("掉线：{} 走了，{} 这几扇窗不再等他", who.value(), closed);
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
            //   开窗那一刻的舵手记在组件里（GameComponent#helmSeat），他掉线就等超时按默认挑；
            //   不限时的窗（演示局）等不到超时，截止改成现在（windowsAfterDisconnect）。
            //   2adbbe4 那一版在这里换人并重开 12 秒 —— 暗牌给到两个人看，反复断连能让计时一直重开。
            GameComponents.sync(world);
        }
    }

    /**
     * 有人掉线（{@code setOffline} 之后）：他正在答的那几扇窗不再等他（审查 2026-10-07 K1）。
     *
     * <p>❗演示局里等真人不限时（{@link GameComponent#humanWindow} 给一年）：原设计「他掉线就等超时替他选」，超时一年就等于永远。
     * 「该答的都答了就收」原先只在答了的那几个事件点上问，掉线这条路一处都没问。这里逐扇复核：
     * <ul>
     *   <li>补给箱在他手上 · 他是开窗那一刻的舵手：截止改成现在，下一 tick 照到点那样替他选（认他最后的高亮）；</li>
     *   <li>口渴（他是口渴的人，或是递水的人）：只给旁人打水的那一窗，能打水的真人都表过态了就到点；</li>
     *   <li>落海：没有真人还在选、也没有替身还在想，就到点；</li>
     *   <li>站队 / 押武器：该答的真人都答了就收短（{@link ContestPhase#endIfAllDecided}）；表态 · 挑牌那两种由
     *       {@link ContestPhase#connectionChanged} 改成按默认往下走（原先就有）。</li>
     * </ul>
     * 只改到点时刻，不在这里替谁做决定：超时那一步仍由各阶段自己的计时照本来的规矩走。
     *
     * <p>❗<b>只收不限时的窗</b>（离到点还有一天以上 —— 与客户端画「∞」同一条线）。限时的窗照原设计等超时：
     * 他在剩下的那十几秒里重连，还能自己答（ADR-0051 B5「掉线就等超时按默认挑」，用户 2026-10-01 拍板）。
     *
     * @param world 只转给 {@link ContestPhase#endIfAllDecided}（它不用）；单测里可以是 {@code null}
     * @return 收了哪几扇（与语言无关的名字，进日志、给单测）
     */
    static List<String> windowsAfterDisconnect(ServerWorld world, GameComponent component, CharacterId who, long now) {
        List<String> closed = new ArrayList<>();
        Session session = component.requireSession();
        if (untimed(component.provisionDeadline(), now) && session.provisionHolder().map(who::equals).orElse(false)) {
            component.setProvisionDeadline(now);
            closed.add("provision");
        }
        if (untimed(component.helmDeadline(), now) && component.helmSeat(session.state()).map(who::equals).orElse(false)) {
            component.setHelmDeadline(now);
            closed.add("helm");
        }
        if (untimed(component.thirstDeadline(), now)) {
            Optional<Session.ThirstPrompt> pending = session.thirstPending();
            if (pending.isPresent() && ThirstPhase.donationsSettled(session, component, pending.get())) {
                component.setThirstDeadline(now);
                closed.add("thirst");
            }
        }
        if (untimed(component.overboardDeadline(), now) && session.overboardPending().isPresent()
                && CardActions.overboardSettled(session, component)) {
            component.setOverboardDeadline(now);
            closed.add("overboard");
        }
        if (untimed(component.contestDeadline(), now) && session.contest().isPresent()) {
            long before = component.contestDeadline();
            ContestPhase.endIfAllDecided(world, component);
            if (component.contestDeadline() != before) {
                closed.add("contest");
            }
        }
        return List.copyOf(closed);
    }

    /** 离到点还有一天以上 = 不限时的窗（{@link GameComponent#UNLIMITED_MS}；客户端同一条线画「∞」）。 */
    private static boolean untimed(long deadline, long now) {
        return deadline - now > UNTIMED_AFTER_MS;
    }

    private static final long UNTIMED_AFTER_MS = 24L * 3600 * 1000;
}
