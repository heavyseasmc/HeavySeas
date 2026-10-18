package io.github.heavyseasmc.mod.state;

import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 局号（通知纪元）在客户端上永远有值：没有对局的那一包也一样。
 *
 * <p>2026-10-13 真机两次：{@code seas land} 靠岸 → 计分面板收起 → 回到主世界，客户端崩在
 * {@code OverboardCue.tick}（{@code notificationEpoch()} 为 null）；服务端不重启、重连也立刻崩。
 * 靠岸的人在主世界留着「上一局成绩」（{@code shouldSyncWith} 因此仍发给他），发去的是没有对局的那一包；
 * 读那一包时通知历史被清空，局号随之成了 null。{@code seas end} 不留成绩，主世界那一份从没收过空包，所以不崩。
 *
 * <p>全部走真实的写 / 读同步包路径（{@code writeSyncPacket} → {@code applySyncPacket}）。
 */
final class NotificationEpochAfterEndTest {
    private static final UUID A = UUID.randomUUID();

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    @DisplayName("对局中 → 结束后没有对局的那一包：客户端的局号仍有值，且与那一局的不同（下一局重新起算）")
    void epochStaysDefinedAfterTheIdlePacket() throws Exception {
        GameComponent server = EndgameProjectionTest.component(A, EndgameProgress.Stage.ARRIVAL, 0);
        ServerPlayerEntity a = recipient(A);
        GameComponent client = new GameComponent(null);

        relay(server, a, client);
        assertTrue(client.hudView().active(), "对照：第一包该是对局中的那一包");
        UUID inGame = client.notificationEpoch();
        assertEquals(server.notificationEpoch(), inGame, "对照：对局中客户端的局号就是服务端的那一个");

        server.end();
        assertTrue(server.shouldSyncWith(a), "对照：结束那一帧要发给这一局的人");
        relay(server, a, client);
        assertTrue(!client.hudView().active(), "对照：第二包该是没有对局的那一包");
        assertNotNull(client.notificationEpoch(), "没有对局的那一包把局号清成了 null（OverboardCue.tick 当场崩）");
        assertNotEquals(inGame, client.notificationEpoch(), "收场之后局号还是那一局的：下一局的第一批会被当成旧的");
    }

    @Test
    @DisplayName("重连：新的客户端组件收到的第一包就是没有对局的那一包，局号照样有值")
    void freshClientReceivingIdlePacketHasEpoch() throws Exception {
        GameComponent server = EndgameProjectionTest.component(A, EndgameProgress.Stage.ARRIVAL, 0);
        server.end();
        GameComponent client = new GameComponent(null);
        relay(server, recipient(A), client);
        assertTrue(!client.hudView().active(), "对照：这一包该是没有对局的那一包");
        assertNotNull(client.notificationEpoch(), "重连后的第一包把局号清成了 null");
    }

    private static void relay(GameComponent server, ServerPlayerEntity to, GameComponent client) {
        RegistryByteBuf packet = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        try {
            server.writeSyncPacket(packet, to);
            client.applySyncPacket(packet);
        } finally {
            packet.release();
        }
    }

    /** 只有 UUID 的服务端玩家空壳（同 {@code EventChannelTest#recipient}）。 */
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
