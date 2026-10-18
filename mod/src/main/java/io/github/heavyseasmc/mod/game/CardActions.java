package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.seat.Legal;
import io.github.heavyseasmc.mod.net.CardActionC2S;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.ExperienceView;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.Optional;

public final class CardActions {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("heavyseas");

    private CardActions() {
    }

    public static void onAction(ServerPlayerEntity player, CardActionC2S action) {
        ServerWorld world = player.getServerWorld();
        handle(PlayerFeedback.Requester.of(player), PlayerFeedback.Room.of(world), GameComponents.of(world), action);
    }

    /**
     * 收包的整条路（生产与单测走同一份；单测只换掉回执与播报的那两个口子）。
     *
     * <p>次序：认人与编号（{@link PlayerFeedback#admit}）→ 窗口 → 可用性（{@link CardAvailability}，判据是引擎的 Legal）
     * → 带目标再问一遍 → 交给引擎。每一道拒绝都只回给本人。
     */
    static void handle(PlayerFeedback.Requester who, PlayerFeedback.Room room, GameComponent component,
                       CardActionC2S action) {
        Optional<CharacterId> seat = component.seatOf(who.id());
        if (component.session().isEmpty() || seat.isEmpty() || action.kind().isEmpty()) {
            return;
        }
        CardActionC2S.Kind kind = action.kind().get();
        if (!PlayerFeedback.admit(who, component, action.stamp(), anyWindow(kind))) {
            return;
        }
        if ((kind == CardActionC2S.Kind.OVERBOARD || kind == CardActionC2S.Kind.OVERBOARD_DONE)
                && (!CardAvailability.overboardWindowOpen(component)
                || component.requireSession().overboardPending().filter(p -> p.token() == action.token()).isEmpty())) {
            PlayerFeedback.reject(who, component, CardAvailability.Block.WINDOW_CLOSED.text());
            return;
        }
        if (kind == CardActionC2S.Kind.OVERBOARD_DONE) {
            // 落海那一窗「不用」（ADR-0095 D1）：原先只是收起界面，窗口空等到时；演示局不限时之后会一直等。
            // 手上有牌可出的真人都说了「不用」，这一窗就当场收（下一 tick 照到时那样结算）。
            if (component.hasDecided(seat.get())) {
                return;                       // 已经说过「不用」了：重复的包不再算一次（审查 2026-10-07 L5 同一类）
            }
            component.markDecided(seat.get());
            PlayerFeedback.acceptedCard(room, component, seat.get(), CardActionC2S.Kind.OVERBOARD_DONE,
                    "", "", ExperienceView.Source.PLAYER);
            boolean settled = overboardSettled(component.requireSession(), component);
            LOGGER.info("落海：{} 这一窗不用牌{}", seat.get().value(), !settled ? ""
                    : "，没人要等了，收");
            if (settled) {
                component.setOverboardDeadline(System.currentTimeMillis());
            }
            room.sync();
            return;
        }
        ExperienceView.Action asked = action(kind).orElseThrow();
        CardAvailability.Block availability = CardAvailability.check(component, seat.get(), action.card(), asked);
        if (availability == CardAvailability.Block.NONE) {
            availability = targetRefusal(component.requireSession(), seat.get(), kind, action);
        }
        if (availability != CardAvailability.Block.NONE) {
            PlayerFeedback.reject(who, component, availability.text());
            return;
        }
        try {
            apply(component.requireSession(), seat.get(), action);
            PlayerFeedback.acceptedCard(room, component, seat.get(), kind, action.card(),
                    action.target(), ExperienceView.Source.PLAYER);
            LOGGER.info("Card UI: {} {} -> {}", seat.get().value(), action.kind().orElseThrow(),
                    ActionPhase.clipForLog(action.target()));
            // ❗出了牌之后也要问一遍：把手上唯一一张落海牌打出去的人再没有可选的，那一面空着，
            // 没人会再按「不用」—— 正常局等满 20 秒，演示局不限时就永远等下去（用户 2026-10-07：「无限时间后，有的阶段会卡住」）
            if (action.kind().get() == CardActionC2S.Kind.OVERBOARD
                    && overboardSettled(component.requireSession(), component)) {
                LOGGER.info("落海：{} 出过牌之后没有真人还有牌可出，收", seat.get().value());
                component.setOverboardDeadline(System.currentTimeMillis());
            }
            room.sync();
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            LOGGER.info("Card UI: {} {} 被引擎拒绝：{}", seat.get().value(), kind, ActionPhase.clipForLog(rejected.getMessage()));
            PlayerFeedback.reject(who, component, refusal(rejected).text());
        }
    }

    /**
     * 包里的动作对应手牌一面的哪一种用法。❗显式一一对应，不按名字转（两个枚举各自加常量时，按名字转会在运行时才抛）；
     * 「不用」不是一种用牌，没有对应。{@code CardActionsMappingTest} 逐个核对。
     */
    static Optional<ExperienceView.Action> action(CardActionC2S.Kind kind) {
        return switch (kind) {
            case REVEAL -> Optional.of(ExperienceView.Action.REVEAL);
            case GIVE_HAND -> Optional.of(ExperienceView.Action.GIVE_HAND);
            case GIVE_FRONT -> Optional.of(ExperienceView.Action.GIVE_FRONT);
            case OVERBOARD -> Optional.of(ExperienceView.Action.OVERBOARD);
            case OVERBOARD_DONE -> Optional.empty();
        };
    }

    /** 亮牌、送牌的意思不随窗口变：编号只核对局与连接（{@link io.github.heavyseasmc.mod.state.RequestGate}）。落海认这一窗。 */
    static boolean anyWindow(CardActionC2S.Kind kind) {
        return switch (kind) {
            case REVEAL, GIVE_HAND, GIVE_FRONT -> true;
            case OVERBOARD, OVERBOARD_DONE -> false;
        };
    }

    /** 带上包里的目标再问一遍 Legal：这张牌能用，但不能给这个人（他被移出了、是自己、不在这一批落海名单里）。 */
    static CardAvailability.Block targetRefusal(Session session, CharacterId actor, CardActionC2S.Kind kind,
                                                CardActionC2S action) {
        CharacterId to;
        try {
            to = CharacterId.of(action.target());
        } catch (IllegalArgumentException | NullPointerException malformed) {
            return kind == CardActionC2S.Kind.REVEAL ? CardAvailability.Block.NONE : CardAvailability.Block.NO_TARGET;
        }
        boolean legal = switch (kind) {
            case REVEAL, OVERBOARD_DONE -> true;
            case GIVE_HAND, GIVE_FRONT -> CardAvailability.giftLegal(session, actor, action.card(),
                    kind == CardActionC2S.Kind.GIVE_FRONT, to);
            case OVERBOARD -> Legal.overboard(session, actor).contains(new Session.OverboardPlay(action.card(), to));
        };
        return legal ? CardAvailability.Block.NONE : CardAvailability.Block.NO_TARGET;
    }

    /**
     * 可用性与目标都过了、引擎仍然拒绝时说哪一句：按异常类型分 ——
     * {@link IllegalStateException} 是「不是这个时候」（阶段、这一场、窗口变了），
     * {@link IllegalArgumentException} 是「这张牌不对」（目标已在上一道单独问过）。
     * 原先一律说「目标不适用」，连阶段变了也这么说。
     */
    static CardAvailability.Block refusal(RuntimeException rejected) {
        return rejected instanceof IllegalStateException
                ? CardAvailability.Block.WRONG_TIMING : CardAvailability.Block.CARD_CHANGED;
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

    /**
     * 落海这一窗可以提前收了吗：没有真人还在选，<b>也没有替身还在想</b>（{@link GameComponent#standInsOverboard}）。
     *
     * <p>❗审查 2026-10-07 R3（用户报过）：原先只问真人 —— 真人先说「不用」，下一 tick 就结算，动脑 / 大模型的替身
     * 那一手落地时窗口已经没了，记成「作废」。真人那一侧收窗都走这里；替身的接力走完时由它自己问真人那一半（{@link StandInMinds}）。
     */
    static boolean overboardSettled(Session session, GameComponent component) {
        return !humanStillChoosing(session, component) && !component.standInsOverboard();
    }

    // ---- 动脑 / 大模型的替身（StandInMinds）：与界面那几下同一个引擎调用，之后同样推一次投影 ----

    /** 替身亮一张手牌（亮出来就是公开的）。 */
    static void revealForStandIn(ServerWorld world, GameComponent component, CharacterId who, String card) {
        component.requireSession().reveal(who, card);
        PlayerFeedback.acceptedCard(world, component, who, CardActionC2S.Kind.REVEAL, card, "", ExperienceView.Source.STAND_IN);
        LOGGER.info("亮牌（替身）：{} 亮出 {}", who.value(), card);
        GameComponents.sync(world);
    }

    /** 替身送一张牌。手里的那一张是暗的：日志不写是哪一张。 */
    static void giveForStandIn(ServerWorld world, GameComponent component, CharacterId from, CharacterId to,
                               String card, boolean fromFront) {
        component.requireSession().giveCard(from, to, card, fromFront);
        PlayerFeedback.acceptedCard(world, component, from, fromFront ? CardActionC2S.Kind.GIVE_FRONT : CardActionC2S.Kind.GIVE_HAND,
                card, to.value(), ExperienceView.Source.STAND_IN);
        LOGGER.info("送牌（替身）：{} 送 {} {}", from.value(), to.value(), fromFront ? "面前的 " + card : "一张手牌");
        GameComponents.sync(world);
    }

    /** 替身在落海那一窗打出救生圈 / 血饵（打出去就是公开的）。 */
    static void overboardForStandIn(ServerWorld world, GameComponent component, CharacterId who, CharacterId target,
                                    String card, int token) {
        component.requireSession().playOverboardCard(who, target, card, token);
        PlayerFeedback.acceptedCard(world, component, who, CardActionC2S.Kind.OVERBOARD, card, target.value(), ExperienceView.Source.STAND_IN);
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
