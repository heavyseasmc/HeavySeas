package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.mod.net.CardActionC2S;
import io.github.heavyseasmc.mod.net.RequestStamp;
import io.github.heavyseasmc.mod.state.ExperienceView;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.RequestGate;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 请求的回执：被拒只回给本人，接受之后的播报按实际可见范围发。
 *
 * <p>两个口子（{@link Requester} · {@link Room}）把「回给谁、播给谁、推给谁」与世界分开：
 * 生产版就是那个玩家与那个世界；单测换成记录器，收包的整条路（{@link CardActions#handle}、
 * {@link ActionPhase#handleUse}）照样走真的那一份，不必在测试里另写一遍流程。
 */
public final class PlayerFeedback {
    private PlayerFeedback() {
    }

    /** 发请求的那个人：被拒时只回他一句、只给他推一份投影（不触发全员同步）。 */
    public interface Requester {
        UUID id();

        /** 动作栏一句。 */
        void tell(Text message);

        /** 只给他一个人推一份投影。 */
        void resync();

        static Requester of(ServerPlayerEntity player) {
            return new Requester() {
                @Override
                public UUID id() {
                    return player.getUuid();
                }

                @Override
                public void tell(Text message) {
                    player.sendMessage(message, true);
                }

                @Override
                public void resync() {
                    GameComponents.syncTo(player);
                }
            };
        }
    }

    /** 请求落在的那一局：全船播报与全员同步。{@link #world()} 只给那几条非要世界不可的路（结束行动、开绝境）。 */
    public interface Room {
        void broadcast(Text message, ExperienceView.Source source, String actor);

        void sync();

        ServerWorld world();

        static Room of(ServerWorld world) {
            return new Room() {
                @Override
                public void broadcast(Text message, ExperienceView.Source source, String actor) {
                    GameFlow.broadcast(world, message, source, actor);
                }

                @Override
                public void sync() {
                    GameComponents.sync(world);
                }

                @Override
                public ServerWorld world() {
                    return world;
                }
            };
        }
    }

    /**
     * 同一个意图只认一次（{@link RequestGate}）。重复的包只给本人补一份投影；过期的回一句「这次决定已经结束」。
     *
     * @param anyWindow 这件事的意思不随阶段 / 窗口变（喝酒、亮牌、送牌）：只认对局与连接，不认窗口，
     *                  免得阶段切换那一瞬间合法的请求被判过期
     */
    public static boolean admit(Requester who, GameComponent component, RequestStamp stamp, boolean anyWindow) {
        RequestGate.Verdict verdict = component.admitRequest(who.id(), stamp, anyWindow);
        if (verdict == RequestGate.Verdict.NEW) {
            return true;
        }
        if (verdict == RequestGate.Verdict.DUPLICATE) {
            who.resync();
        } else {
            reject(who, component, CardAvailability.Block.WINDOW_CLOSED.text());
        }
        return false;
    }

    /** 被拒：侧栏记一条只给他看的，动作栏回一句，只给他推投影 —— 别人的屏幕不动。 */
    public static void reject(Requester who, GameComponent component, Text reason) {
        component.event(who.id(), "rejected", component.seatOf(who.id()).map(CharacterId::value).orElse(""),
                ExperienceView.Source.PLAYER, reason);
        who.tell(reason);
        who.resync();
    }

    public static void reject(ServerPlayerEntity player, GameComponent component, Text reason) {
        reject(Requester.of(player), component, reason);
    }

    public static Text cardName(String card) {
        return Text.translatable("heavyseas.provision." + card);
    }

    public static void acceptedCard(ServerWorld world, GameComponent component, CharacterId actor,
                                    CardActionC2S.Kind kind, String card, String target, ExperienceView.Source source) {
        acceptedCard(Room.of(world), component, actor, kind, card, target, source);
    }

    /**
     * 引擎接受之后的回执。
     *
     * <p>❗送<b>手牌</b>：全船只听到「X 给了 Y 一张手牌」，<b>不带牌名</b>；牌名只私下告诉给的与收的两个人
     * （用户定的）。这两个人不再收那句不带牌名的，同一件事在他们的侧栏里只出现一次。
     * 面前的牌本来就亮着，送面前的照旧全船带牌名。
     */
    public static void acceptedCard(Room room, GameComponent component, CharacterId actor,
                                    CardActionC2S.Kind kind, String card, String target, ExperienceView.Source source) {
        Text from = GameFlow.characterName(actor);
        switch (kind) {
            case REVEAL -> room.broadcast(Text.translatable("heavyseas.command.revealed", from, cardName(card)),
                    source, actor.value());
            case GIVE_FRONT -> {
                CharacterId to = CharacterId.of(target);
                room.broadcast(Text.translatable("heavyseas.ux.receipt.gift", from, cardName(card),
                        GameFlow.characterName(to)), source, actor.value());
            }
            case GIVE_HAND -> {
                CharacterId to = CharacterId.of(target);
                Text named = Text.translatable("heavyseas.ux.receipt.gift", from, cardName(card), GameFlow.characterName(to));
                component.privateEvent(actor, "gift", source, named);
                component.privateEvent(to, "gift", source, named);
                component.publicEventExcept("gift", actor.value(), source,
                        Text.translatable("heavyseas.ux.receipt.gift_hidden", from, GameFlow.characterName(to)),
                        Set.of(actor, to));
                room.sync();
            }
            case OVERBOARD -> {
                ProvisionEffect effect = component.requireSession().provisions().get(card).effect();
                Text message = effect instanceof ProvisionEffect.DamageInWater
                        ? Text.translatable("heavyseas.ux.receipt.water_damage", from, cardName(card))
                        : Text.translatable("heavyseas.ux.receipt.rescue", from, cardName(card),
                        GameFlow.characterName(CharacterId.of(target)));
                room.broadcast(message, source, actor.value());
            }
            case OVERBOARD_DONE -> component.privateEvent(actor, "overboard_done", source,
                    Text.translatable("heavyseas.ux.receipt.no_card"));
        }
    }

    public static void drank(ServerWorld world, GameComponent component, CharacterId who, Map<CharacterId, Integer> beforePower,
                             ExperienceView.Source source) {
        drank(Room.of(world), component, who, beforePower, source);
    }

    /** 喝酒：全船只听到「X 喝了酒」，不带牌名（用户定的）；蹭到酒的人各一句（体型加成是公开的）。 */
    public static void drank(Room room, GameComponent component, CharacterId who, Map<CharacterId, Integer> beforePower,
                             ExperienceView.Source source) {
        room.broadcast(Text.translatable("heavyseas.ux.receipt.drank", GameFlow.characterName(who)), source, who.value());
        var session = component.requireSession();
        for (var entry : beforePower.entrySet()) {
            if (entry.getKey().equals(who)) {
                continue;
            }
            int extra = session.fightingSize(entry.getKey()) - entry.getValue();
            if (extra > 0) {
                room.broadcast(Text.translatable("heavyseas.ux.receipt.shared_drink",
                        GameFlow.characterName(entry.getKey()), extra), source, who.value());
            }
        }
    }

    public static Map<CharacterId, Integer> powers(GameComponent component) {
        var session = component.requireSession();
        Map<CharacterId, Integer> powers = new LinkedHashMap<>();
        session.state().bySeat().forEach(id -> powers.put(id, session.fightingSize(id)));
        return powers;
    }
}
