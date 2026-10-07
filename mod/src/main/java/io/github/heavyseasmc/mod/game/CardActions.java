package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.mod.net.CardActionC2S;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

public final class CardActions {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("heavyseas");

    private CardActions() {
    }

    public static void onAction(ServerPlayerEntity player, CardActionC2S action) {
        var world = player.getServerWorld();
        var component = GameComponents.of(world);
        var seat = component.seatOf(player.getUuid());
        if (component.session().isEmpty() || seat.isEmpty() || action.kind().isEmpty()) {
            return;
        }
        if ((action.kind().get() == CardActionC2S.Kind.OVERBOARD || action.kind().get() == CardActionC2S.Kind.OVERBOARD_DONE)
                && (component.overboardDeadline() <= 0
                || System.currentTimeMillis() >= component.overboardDeadline())) {
            return;
        }
        if (action.kind().get() == CardActionC2S.Kind.OVERBOARD_DONE) {
            // 落海那一窗「不用」（ADR-0095 D1）：原先只是收起界面，窗口空等到时；演示局不限时之后会一直等。
            // 手上有牌可出的真人都说了「不用」，这一窗就当场收（下一 tick 照到时那样结算）。
            component.markDecided(seat.get());
            boolean waiting = humanStillChoosing(component.requireSession(), component);
            LOGGER.info("落海：{} 这一窗不用牌{}", seat.get().value(), waiting ? "" : "，没人要等了，收");
            if (!waiting) {
                component.setOverboardDeadline(System.currentTimeMillis());
            }
            return;
        }
        try {
            apply(component.requireSession(), seat.get(), action);
            LOGGER.info("Card UI: {} {} -> {}", seat.get().value(), action.kind().orElseThrow(),
                    action.target());
            // ❗出了牌之后也要问一遍：把手上唯一一张落海牌打出去的人再没有可选的，那一面空着，
            // 没人会再按「不用」—— 正常局等满 20 秒，演示局不限时就永远等下去（用户 2026-10-07：「无限时间后，有的阶段会卡住」）
            if (action.kind().get() == CardActionC2S.Kind.OVERBOARD
                    && !humanStillChoosing(component.requireSession(), component)) {
                LOGGER.info("落海：{} 出过牌之后没有真人还有牌可出，收", seat.get().value());
                component.setOverboardDeadline(System.currentTimeMillis());
            }
            GameComponents.sync(world);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            player.sendMessage(Text.translatable("heavyseas.card_action.rejected"), true);
        }
    }

    /**
     * 落海这一窗还要不要等：还有真人手上有落海时能出的牌、又没说过「不用」。替身不等：随机 / 什么也不做的替身不在这一窗出牌，
     * 动脑 / 大模型的替身自己问完自己收（{@link StandInMinds#overboard}）。
     * 「不用」与「出了牌」两条路都问这一份。
     */
    static boolean humanStillChoosing(Session session, GameComponent component) {
        return component.occupants().entrySet().stream().anyMatch(e -> !e.getValue().isDummy()
                && !component.hasDecided(e.getKey()) && !session.state().isOffline(e.getKey())
                && !session.overboardPlays(e.getKey()).isEmpty());
    }

    // ---- 动脑 / 大模型的替身（StandInMinds）：与界面那几下同一个引擎调用，之后同样推一次投影 ----

    /** 替身亮一张手牌（亮出来就是公开的）。 */
    static void revealForStandIn(ServerWorld world, GameComponent component, CharacterId who, String card) {
        component.requireSession().reveal(who, card);
        LOGGER.info("亮牌（替身）：{} 亮出 {}", who.value(), card);
        GameComponents.sync(world);
    }

    /** 替身送一张牌。手里的那一张是暗的：日志不写是哪一张。 */
    static void giveForStandIn(ServerWorld world, GameComponent component, CharacterId from, CharacterId to,
                               String card, boolean fromFront) {
        component.requireSession().giveCard(from, to, card, fromFront);
        LOGGER.info("送牌（替身）：{} 送 {} {}", from.value(), to.value(), fromFront ? "面前的 " + card : "一张手牌");
        GameComponents.sync(world);
    }

    /** 替身在落海那一窗打出救生圈 / 血饵（打出去就是公开的）。 */
    static void overboardForStandIn(ServerWorld world, GameComponent component, CharacterId who, CharacterId target,
                                    String card, int token) {
        component.requireSession().playOverboardCard(who, target, card, token);
        LOGGER.info("落海（替身）：{} 打出 {} → {}", who.value(), card, target.value());
        GameComponents.sync(world);
    }

    static void apply(Session session, CharacterId actor, CardActionC2S action) {
        if (session.state().isOver() || !session.state().canAct(actor)) {
            throw new IllegalStateException("cannot play a card");
        }
        switch (action.kind().orElseThrow(() -> new IllegalArgumentException("unknown card action"))) {
            case REVEAL -> session.reveal(actor, action.card());
            case GIVE_HAND, GIVE_FRONT -> session.giveCard(actor, CharacterId.of(action.target()),
                    action.card(), action.kind().get() == CardActionC2S.Kind.GIVE_FRONT);
            case OVERBOARD -> session.playOverboardCard(actor, CharacterId.of(action.target()),
                    action.card(), action.token());
        }
    }
}
