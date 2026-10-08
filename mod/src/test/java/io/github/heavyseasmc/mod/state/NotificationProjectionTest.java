package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.mod.ui.NotificationHistory;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.text.Text;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NotificationProjectionTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrapTextCodecs() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
    }

    private static RegistryByteBuf buffer() {
        return new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
    }

    @Test
    void aBurstLargerThanSixteenSurvivesProjectionAndDuplicatePackets() {
        UUID player = UUID.randomUUID();
        GameComponent server = EndgameProjectionTest.component(player, EndgameProgress.Stage.ARRIVAL, 0);
        for (int i = 0; i < 80; i++) {
            server.notify(Text.literal("same"));
        }
        NotificationHistory<Text> history = new NotificationHistory<>();
        RegistryByteBuf first = buffer();
        RegistryByteBuf duplicate = buffer();
        RegistryByteBuf emptyDelta = buffer();
        try {
            server.writeViewFor(first, player);
            int snapshotBytes = first.readableBytes();
            HudView projected = GameComponent.readViewFrom(first, history);
            assertEquals(80, projected.notifications().size());
            assertEquals(80, projected.notificationSeq());
            server.writeViewSince(duplicate, player, 64);
            assertEquals(80, GameComponent.readViewFrom(duplicate, history).notifications().size());
            server.writeViewSince(emptyDelta, player, 80);
            assertTrue(emptyDelta.readableBytes() < snapshotBytes, "没有新播报的包不重发整本历史");
            assertEquals(projected.notifications(), GameComponent.readViewFrom(emptyDelta, history).notifications());
        } finally {
            first.release();
            duplicate.release();
            emptyDelta.release();
        }
    }

    @Test
    void packetsAreMergedBeforeRenderingAndAnotherGameCannotReuseTheSequence() {
        UUID player = UUID.randomUUID();
        GameComponent server = EndgameProjectionTest.component(player, EndgameProgress.Stage.ARRIVAL, 0);
        NotificationHistory<Text> history = new NotificationHistory<>();
        HudView view = HudView.IDLE;
        for (int seq = 1; seq <= 40; seq++) {
            server.notify(Text.literal("note-" + seq));
            RegistryByteBuf packet = buffer();
            try {
                server.writeViewSince(packet, player, seq - 1);
                view = GameComponent.readViewFrom(packet, history);
            } finally {
                packet.release();
            }
        }
        assertEquals(40, view.notifications().size());
        assertEquals("note-1", view.notifications().getFirst().getString());
        GameComponent nextGame = EndgameProjectionTest.component(player, EndgameProgress.Stage.ARRIVAL, 0);
        nextGame.notify(Text.literal("new game"));
        RegistryByteBuf packet = buffer();
        try {
            nextGame.writeViewFor(packet, player);
            view = GameComponent.readViewFrom(packet, history);
            assertEquals(1, view.notifications().size());
            assertEquals("new game", view.notifications().getFirst().getString());
        } finally {
            packet.release();
        }
    }
}
