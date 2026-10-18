package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.NavigationDeck;
import io.github.heavyseasmc.engine.navigation.Selector;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.play.Table;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.net.CardActionC2S;
import io.github.heavyseasmc.mod.net.RequestStamp;
import io.github.heavyseasmc.mod.net.UseProvisionC2S;
import io.github.heavyseasmc.mod.state.ExperienceView;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.ui.IntentNonces;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求编号走<b>真实的收包入口</b>（{@link CardActions#handle} · {@link ActionPhase#handleUse}）：
 * 只把「回给谁 / 播给谁 / 推给谁」换成记录器，认人、认编号、可用性、引擎调用都是生产那一份。
 *
 * <p>守的缺陷：收包那一侧漏了编号这一道（{@code PlayerFeedback.admit}）。原先这里自己重写了一遍「先认编号再调引擎」，
 * 生产代码里那一行删掉照样绿。
 */
class CardRequestIdempotencyTest {
    private static final CharacterId FIRST = CharacterId.of("mate");
    private static final CharacterId LAST = CharacterId.of("kid");
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    /** 发请求的那个人：记下回了他什么、给他推了几次。 */
    private static final class Requester implements PlayerFeedback.Requester {
        final UUID id;
        final List<String> told = new ArrayList<>();
        int resyncs;

        Requester(UUID id) {
            this.id = id;
        }

        @Override
        public UUID id() {
            return id;
        }

        @Override
        public void tell(Text message) {
            told.add(((TranslatableTextContent) message.getContent()).getKey());
        }

        @Override
        public void resync() {
            resyncs++;
        }
    }

    /**
     * 那一局：播报照生产那样记进组件，全员同步只计数。没有世界：走到要世界的那几条路（结束行动）会在生产代码里抛空指针，
     * 被收包那一侧的兜底接住 —— 断言照样落在局面上（治没治、送没送），不靠这里抛。
     */
    private static final class Room implements PlayerFeedback.Room {
        final GameComponent component;
        int syncs;

        Room(GameComponent component) {
            this.component = component;
        }

        @Override
        public void broadcast(Text message, ExperienceView.Source source, String actor) {
            component.notify(message, source, actor);
            syncs++;
        }

        @Override
        public void sync() {
            syncs++;
        }

        @Override
        public ServerWorld world() {
            return null;
        }
    }

    private static Session session() {
        Roster roster = new Roster(List.of(new Survivor(FIRST, 1, 8, 4, "base", new Ability.None()),
                new Survivor(LAST, 2, 3, 9, "base", new Ability.None())));
        Session s = new Session("request-gate", roster, new Table(new NavigationDeck(List.of(new NavigationCard("calm", 0,
                new Selector.Nobody(), new Selector.Nobody(), false, false)), new Random(1)),
                new Provisions(List.of(new Provision("water", Provision.Category.CONSUMABLE, 2,
                                new ProvisionEffect.PreventThirst(1, "thirst_source", ProvisionEffect.Target.ANY_CHARACTER,
                                        true, "thirst_resolution", true, false, false, false, false)),
                        new Provision("medical_kit", Provision.Category.EQUIPMENT, 1,
                                new ProvisionEffect.Heal(1, ProvisionEffect.Target.ANY_CHARACTER_INCLUDING_SELF, true, true, List.of())),
                        new Provision("rum", Provision.Category.EQUIPMENT, 1, new ProvisionEffect.BuffSize(3, "turn",
                                ProvisionEffect.BuffSize.THIRST_AT_END_OF_TURN, true, true, false)))), new Random(1)));
        s.dealFromPile(FIRST, "water");
        s.dealFromPile(FIRST, "water");
        s.dealFromPile(FIRST, "medical_kit");
        s.dealFromPile(FIRST, "rum");
        s.advancePhase();
        return s;
    }

    private static Map<CharacterId, GameComponent.Occupant> occupants() {
        return Map.of(FIRST, new GameComponent.Occupant(A, "a"), LAST, new GameComponent.Occupant(B, "b"));
    }

    private static GameComponent component() {
        GameComponent c = new GameComponent(null);
        c.begin(session(), occupants());
        return c;
    }

    private static RequestStamp stamp(GameComponent c, long nonce) {
        return new RequestStamp(c.matchId(), c.commandContext(A), nonce);
    }

    private static CardActionC2S gift(GameComponent c, long nonce) {
        return CardActionC2S.of(CardActionC2S.Kind.GIVE_HAND, "water", LAST.value(), 0).withStamp(stamp(c, nonce));
    }

    private static int waters(GameComponent c, CharacterId who) {
        return c.requireSession().state().stateOf(who).countInHand("water");
    }

    @Test
    @DisplayName("同一个意图（同一个编号）送两次只走一张水；换一个意图能送第二张；重复包只给本人补一份投影")
    void sameIntentTransfersOnlyOneOfTwoIdenticalCardsButANewIntentCanTransferTheSecond() {
        GameComponent c = component();
        c.requireSession().markActed(FIRST);
        Requester me = new Requester(A);
        Room room = new Room(c);
        CardActionC2S request = gift(c, 1);

        CardActions.handle(me, room, c, request);
        CardActions.handle(me, room, c, request);

        assertEquals(1, waters(c, LAST), "同一个编号的第二包又送出了一张：收包那一侧没认编号");
        assertEquals(1, waters(c, FIRST));
        assertEquals(1, me.resyncs, "重复包该只给本人补一份投影");
        assertTrue(me.told.isEmpty(), "重复包不是拒绝，不该回一句「被拒」：" + me.told);
        CardActions.handle(me, room, c, gift(c, 2));
        assertEquals(2, waters(c, LAST));
        assertEquals(2, c.requireSession().deeds().size(), "No duplicate transfer or deed is recorded");
    }

    @Test
    @DisplayName("客户端：同一份投影下的同一个请求复用编号（双击只走一张）；收到新投影后再点是新意图")
    void doubleClickUnderOneProjectionReusesTheNonce() {
        GameComponent c = component();
        Requester me = new Requester(A);
        Room room = new Room(c);
        IntentNonces nonces = new IntentNonces();
        Object projection = new Object();
        CardActionC2S intent = CardActionC2S.of(CardActionC2S.Kind.GIVE_HAND, "water", LAST.value(), 0);
        RequestStamp first = nonces.stamp(projection, intent, c.matchId(), c.commandContext(A));
        RequestStamp second = nonces.stamp(projection, intent, c.matchId(), c.commandContext(A));
        assertSame(first, second, "双击取到了两个编号");

        CardActions.handle(me, room, c, intent.withStamp(first));
        CardActions.handle(me, room, c, intent.withStamp(second));
        assertEquals(1, waters(c, LAST), "双击送走了两张水");

        RequestStamp afterUpdate = nonces.stamp(new Object(), intent, c.matchId(), c.commandContext(A));
        assertNotEquals(first.nonce(), afterUpdate.nonce(), "对照：收到新投影之后再点是新意图");
        RequestStamp otherIntent = nonces.stamp(projection, CardActionC2S.of(CardActionC2S.Kind.REVEAL, "water", "", 0),
                c.matchId(), c.commandContext(A));
        assertNotEquals(afterUpdate.nonce(), otherIntent.nonce(), "对照：同一份投影下的另一个请求是另一个意图");
        CardActions.handle(me, room, c, intent.withStamp(afterUpdate));
        assertEquals(2, waters(c, LAST));
    }

    @Test
    @DisplayName("医疗箱目标：取消再重开之后，旧那一扇的目标请求判过期，一点伤都不治；拒绝只回本人、不全员同步")
    void cancellingAndReopeningMedicalSelectionRejectsTheOldTargetRequest() {
        GameComponent c = component();
        Session s = c.requireSession();
        s.applyFight(Fight.between(FIRST, LAST));
        Requester me = new Requester(A);
        Room room = new Room(c);
        ActionPhase.handleUse(me, room, c, UseProvisionC2S.play("medical_kit").withStamp(stamp(c, 1)));
        assertEquals("medical_kit", c.provisionTargetCard(), "前提：经真实入口进了挑目标");
        UseProvisionC2S oldTarget = UseProvisionC2S.target("medical_kit", LAST.value()).withStamp(stamp(c, 2));
        ActionPhase.handleUse(me, room, c, UseProvisionC2S.cancel().withStamp(stamp(c, 3)));
        assertTrue(c.provisionTargeter().isEmpty(), "前提：取消生效");
        ActionPhase.handleUse(me, room, c, UseProvisionC2S.play("medical_kit").withStamp(stamp(c, 4)));
        int syncsBefore = room.syncs;
        int resyncsBefore = me.resyncs;

        ActionPhase.handleUse(me, room, c, oldTarget);

        assertEquals(1, s.state().stateOf(LAST).damage(), "旧那一扇的目标请求治了人：收包那一侧没认编号");
        assertTrue(s.state().stateOf(FIRST).hasInHand("medical_kit"));
        assertEquals(List.of("heavyseas.ux.reject.window_closed"), me.told);
        assertEquals(syncsBefore, room.syncs, "被拒的请求触发了全员同步");
        assertEquals(resyncsBefore + 1, me.resyncs, "被拒的请求该只给本人推一份投影");
        assertTrue(c.eventsFor(B).stream().noneMatch(e -> e.kind().equals("rejected")), "别人的侧栏里出现了我的拒绝");
        assertTrue(c.eventsFor(A).stream().anyMatch(e -> e.kind().equals("rejected")));
    }

    @Test
    @DisplayName("阶段切换那一瞬间发出的一口酒照喝（编号只认对局与连接）；同一时刻要认窗口的请求照样判过期")
    void aLegalDrinkSentJustBeforeAPhaseChangeIsNotStale() {
        GameComponent c = component();
        Session s = c.requireSession();
        Requester me = new Requester(A);
        Room room = new Room(c);
        UseProvisionC2S drink = UseProvisionC2S.play("rum").withStamp(stamp(c, 1));
        UseProvisionC2S cancel = UseProvisionC2S.cancel().withStamp(stamp(c, 2));
        s.markActed(FIRST);
        s.markActed(LAST);
        s.advancePhase();
        assertNotEquals(Phase.ACTION, s.state().phase(), "前提：发出之后阶段变了");

        ActionPhase.handleUse(me, room, c, drink);

        assertTrue(s.state().stateOf(FIRST).usedThisTurn("rum"), "合法的一口酒因为阶段变了被判过期：" + me.told);
        assertTrue(me.told.isEmpty(), "这一口不该被拒：" + me.told);
        ActionPhase.handleUse(me, room, c, cancel);
        assertEquals(List.of("heavyseas.ux.reject.window_closed"), me.told, "对照：要认窗口的请求换了阶段照样过期");
    }

    @Test
    @DisplayName("上一局的编号与没带编号的请求都判过期")
    void previousGameAndEmptyStampsAreRejected() {
        GameComponent c = component();
        Requester me = new Requester(A);
        Room room = new Room(c);
        CardActionC2S old = gift(c, 1);
        c.begin(session(), occupants());
        CardActions.handle(me, room, c, old);
        CardActions.handle(me, room, c, CardActionC2S.of(CardActionC2S.Kind.GIVE_HAND, "water", LAST.value(), 0));
        assertEquals(0, waters(c, LAST));
        assertEquals(List.of("heavyseas.ux.reject.window_closed", "heavyseas.ux.reject.window_closed"), me.told);
        CardActions.handle(me, room, c, gift(c, 1));
        assertEquals(1, waters(c, LAST));
    }

    @Test
    @DisplayName("重连之后：旧连接的编号作废，客户端计数从头数也照收")
    void reconnectChangesContextAndAllowsAClientCounterToRestartWithoutReplayingOldRequests() {
        GameComponent c = component();
        Requester me = new Requester(A);
        Room room = new Room(c);
        CardActionC2S old = gift(c, 1);
        CardActions.handle(me, room, c, old);
        c.newRequestConnection(A);
        CardActions.handle(me, room, c, old);
        assertEquals(1, waters(c, LAST), "旧连接的包在新连接上又生效了");
        CardActions.handle(me, room, c, gift(c, 1));
        assertEquals(2, waters(c, LAST));
    }

    @Test
    @DisplayName("送手牌：牌名只告诉给的与收的两个人；全船那一句不带牌名，那两人也不再收它。喝酒：全船只听到「喝了酒」")
    void handGiftAndDrinkBroadcastsCarryNoCardName() {
        GameComponent c = component();
        Requester me = new Requester(A);
        Room room = new Room(c);
        UUID outsider = UUID.randomUUID();

        CardActions.handle(me, room, c, gift(c, 1));
        ActionPhase.handleUse(me, room, c, UseProvisionC2S.play("rum").withStamp(stamp(c, 2)));

        List<ExperienceView.Event> others = c.eventsFor(outsider);
        assertEquals(List.of("heavyseas.ux.receipt.gift_hidden", "heavyseas.ux.receipt.drank"),
                others.stream().map(CardRequestIdempotencyTest::key).toList());
        assertTrue(others.stream().noneMatch(e -> mentions(e.message(), "heavyseas.provision.")),
                "全船播报带了牌名");
        for (UUID party : List.of(A, B)) {
            List<ExperienceView.Event> mine = c.eventsFor(party);
            List<ExperienceView.Event> named = mine.stream()
                    .filter(e -> key(e).equals("heavyseas.ux.receipt.gift")).toList();
            assertEquals(1, named.size(), "给与收的两个人该各收到一句带牌名的");
            assertTrue(mentions(named.getFirst().message(), "heavyseas.provision.water"));
            assertTrue(mine.stream().noneMatch(e -> key(e).equals("heavyseas.ux.receipt.gift_hidden")),
                    "给与收的两个人又收到了一遍不带牌名的那一句");
        }
    }

    @Test
    @DisplayName("牌能送、人不对（送给自己）：说「目标不适用」，不说「这张牌不对」；一张都不动")
    void aWrongGiftTargetIsReportedAsTheTarget() {
        GameComponent c = component();
        Requester me = new Requester(A);
        CardActions.handle(me, new Room(c), c,
                CardActionC2S.of(CardActionC2S.Kind.GIVE_HAND, "water", FIRST.value(), 0).withStamp(stamp(c, 1)));
        assertEquals(List.of("heavyseas.ux.reject.no_target"), me.told);
        assertEquals(2, waters(c, FIRST));
    }

    private static String key(ExperienceView.Event event) {
        return ((TranslatableTextContent) event.message().getContent()).getKey();
    }

    /** 这段文字（连同参数与后缀）里有没有一个以 {@code prefix} 开头的翻译键。 */
    private static boolean mentions(Text text, String prefix) {
        if (text.getContent() instanceof TranslatableTextContent t) {
            if (t.getKey().startsWith(prefix)) {
                return true;
            }
            for (Object arg : t.getArgs()) {
                if (arg instanceof Text inner && mentions(inner, prefix)) {
                    return true;
                }
            }
        }
        return text.getSiblings().stream().anyMatch(s -> mentions(s, prefix));
    }

    @Test
    void bothPayloadCodecsCarryTheSameStampWithoutConsumingFollowingFields() {
        GameComponent c = component();
        CardActionC2S card = gift(c, 7);
        UseProvisionC2S use = UseProvisionC2S.target("medical_kit", LAST.value()).withStamp(stamp(c, 8));
        RegistryByteBuf b = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        try {
            CardActionC2S.CODEC.encode(b, card);
            UseProvisionC2S.CODEC.encode(b, use);
            b.writeInt(0x52515354);
            assertEquals(card, CardActionC2S.CODEC.decode(b));
            assertEquals(use, UseProvisionC2S.CODEC.decode(b));
            assertEquals(0x52515354, b.readInt());
            assertFalse(b.isReadable());
        } finally {
            b.release();
        }
    }
}
