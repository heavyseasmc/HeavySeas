package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.mod.net.CardActionC2S;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.minecraft.server.network.ServerPlayerEntity;
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
            var session = component.requireSession();
            boolean waiting = component.occupants().entrySet().stream().anyMatch(e -> !e.getValue().isDummy()
                    && !component.hasDecided(e.getKey()) && !session.state().isOffline(e.getKey())
                    && !session.overboardPlays(e.getKey()).isEmpty());
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
            GameComponents.sync(world);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            player.sendMessage(Text.translatable("heavyseas.card_action.rejected"), true);
        }
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
