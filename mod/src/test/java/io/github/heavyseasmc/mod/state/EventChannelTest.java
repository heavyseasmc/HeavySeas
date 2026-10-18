package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.mod.ui.NotificationHistory;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 播报与私信只有一条通道：按收件人增量、每条只发一次、私信只发本人；可用性只在变了时发。
 *
 * <p>全部经真实的写同步包路径（{@code writeSyncPacket}），每一包用一本空白历史读回 —— 读到的就是这一包线上真带了的。
 */
final class EventChannelTest {
    private static final CharacterId MATE = CharacterId.of("mate");
    private static final CharacterId KID = CharacterId.of("kid");
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID WATCHER = UUID.randomUUID();

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.initialize();
    }

    private static GameComponent component() {
        GameComponent c = EndgameProjectionTest.component(A, EndgameProgress.Stage.ARRIVAL, 0);
        Session session = c.requireSession();
        Map<CharacterId, GameComponent.Occupant> seats = new LinkedHashMap<>();
        seats.put(MATE, new GameComponent.Occupant(A, "a"));
        seats.put(KID, new GameComponent.Occupant(B, "b"));
        c.begin(session, seats, Set.of(WATCHER));
        c.clearFog();
        return c;
    }

    @Test
    @DisplayName("私信只到本人；「除了这两人」的播报那两人收不到；每条只发一次，第二包空着")
    void privateEventsReachOnlyTheirRecipientAndEachEventIsSentOnce() throws Exception {
        GameComponent c = component();
        ServerPlayerEntity a = recipient(A);
        ServerPlayerEntity b = recipient(B);
        ServerPlayerEntity watcher = recipient(WATCHER);
        c.privateEvent(MATE, "gift", ExperienceView.Source.PLAYER, Text.literal("only for mate"));
        c.publicEventExcept("gift", MATE.value(), ExperienceView.Source.PLAYER, Text.literal("not for the two"),
                Set.of(MATE, KID));
        c.notify(Text.literal("for everyone"));

        assertEquals(List.of("only for mate", "for everyone"), texts(sync(c, a)));
        assertEquals(List.of("for everyone"), texts(sync(c, b)), "别人的私信，或「除了你」的那一句，发给了他");
        assertEquals(List.of("not for the two", "for everyone"), texts(sync(c, watcher)));
        assertEquals(List.of(), texts(sync(c, a)), "没有新事件时又重发了一遍：每条只该发一次");

        c.privateEvent(KID, "gift", ExperienceView.Source.PLAYER, Text.literal("only for kid"));
        assertEquals(List.of(), texts(sync(c, a)), "别人的新私信发给了他");
        assertEquals(List.of("only for kid"), texts(sync(c, b)), "第二包只该带这一条新的");
    }

    @Test
    @DisplayName("可用性只在这个人的那一份变了时才上线：没变的那一包不带，客户端沿用上一份")
    void playabilityIsSentOnlyWhenItChanged() throws Exception {
        GameComponent c = component();
        ServerPlayerEntity a = recipient(A);
        List<ExperienceView.Playability> previous = List.of(new ExperienceView.Playability("previous",
                ExperienceView.Action.PLAY, false, Text.empty()));

        assertNotEquals(previous, playability(c, a, previous), "第一包该带上可用性");
        assertSame(previous, playability(c, a, previous), "没变的可用性又发了一遍");
        c.requireSession().dealFromPile(MATE, "coin");
        List<ExperienceView.Playability> changed = playability(c, a, previous);
        assertTrue(changed.stream().anyMatch(p -> p.card().equals("coin")), "手里多了一张，可用性却没跟着发：" + changed);
        c.forgetNotificationRecipient(A);
        assertNotEquals(previous, playability(c, a, previous), "重连（忘了这个人）之后该重新带上可用性");
    }

    private static List<ExperienceView.Playability> playability(GameComponent c, ServerPlayerEntity to,
                                                                List<ExperienceView.Playability> previous) {
        RegistryByteBuf packet = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        try {
            c.writeSyncPacket(packet, to);
            return GameComponent.readViewFrom(packet, new NotificationHistory<>(), previous).experience().playability();
        } finally {
            packet.release();
        }
    }

    private static NotificationHistory<ExperienceView.Event> sync(GameComponent c, ServerPlayerEntity to) {
        RegistryByteBuf packet = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        try {
            c.writeSyncPacket(packet, to);
            NotificationHistory<ExperienceView.Event> wire = new NotificationHistory<>();
            GameComponent.readViewFrom(packet, wire);
            return wire;
        } finally {
            packet.release();
        }
    }

    private static List<String> texts(NotificationHistory<ExperienceView.Event> history) {
        return history.entries().stream().map(e -> e.message().getString()).toList();
    }

    /** 只有 UUID 的服务端玩家空壳（同 {@code NotificationProjectionTest#recipient}）：写同步包只取 {@code getUuid()}。 */
    private static ServerPlayerEntity recipient(UUID uuid) throws ReflectiveOperationException {
        Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        ServerPlayerEntity player = (ServerPlayerEntity) unsafe.getClass()
                .getMethod("allocateInstance", Class.class).invoke(unsafe, ServerPlayerEntity.class);
        player.setUuid(uuid);
        return player;
    }
}
