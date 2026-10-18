package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.mod.ui.NotificationHistory;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        NotificationHistory<ExperienceView.Event> history = new NotificationHistory<>();
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

    /**
     * 一个只有 UUID 的服务端玩家空壳：{@code writeSyncPacket} 从收件人身上只取 {@code getUuid()}。
     * 单测里开不起服务端、造不出真的 {@code ServerPlayerEntity}，用 JDK 自带的 {@code Unsafe.allocateInstance} 跳过构造器
     * （不加依赖、不改生产代码）。别的方法一概不能调 —— 字段全是空的。
     */
    private static ServerPlayerEntity recipient(UUID uuid) throws ReflectiveOperationException {
        Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        ServerPlayerEntity player = (ServerPlayerEntity) unsafe.getClass()
                .getMethod("allocateInstance", Class.class).invoke(unsafe, ServerPlayerEntity.class);
        player.setUuid(uuid);
        return player;
    }

    /** 经真实的写同步包路径写一份，用一本空白历史读回 HUD 那一半：读到的就是这一包线上真带了的那几条。 */
    private static NotificationHistory<ExperienceView.Event> sync(GameComponent server, ServerPlayerEntity to) {
        RegistryByteBuf packet = buffer();
        try {
            server.writeSyncPacket(packet, to);
            NotificationHistory<ExperienceView.Event> wire = new NotificationHistory<>();
            GameComponent.readViewFrom(packet, wire);
            return wire;
        } finally {
            packet.release();
        }
    }

    @Test
    @DisplayName("经真实的写同步包路径：同一个人第二次只发新增的那几条；换一个人、或忘了这个人，再发整本")
    void writeSyncPacketRemembersWhatEachRecipientAlreadyHas() throws Exception {
        // 守的缺陷：writeSyncPacket 写完不记 notificationSent（删掉那一行 put）。
        // 原先的用例走 writeViewSince 这个测试钩子、自己传「上次收到第几条」，绕开了记账那一行，删了也绿。
        // 删了之后每一包都从第 1 条重发整本保留的历史（最多 400 条），带宽随对局长度线性涨，客户端靠去重才看不出来 ——
        // 所以这里不看客户端合并后的结果，用一本空白历史读回「这一包线上带了哪几条」。
        UUID me = UUID.randomUUID();
        GameComponent server = EndgameProjectionTest.component(me, EndgameProgress.Stage.ARRIVAL, 0);
        ServerPlayerEntity player = recipient(me);
        for (int i = 1; i <= 3; i++) {
            server.notify(Text.literal("old-" + i));
        }
        assertEquals(List.of("old-1", "old-2", "old-3"), texts(sync(server, player)), "前提：第一次同步带整本");

        server.notify(Text.literal("new-4"));
        server.notify(Text.literal("new-5"));
        NotificationHistory<ExperienceView.Event> second = sync(server, player);
        assertEquals(List.of("new-4", "new-5"), texts(second),
                "第二次同步又从头发了已经发过的播报：writeSyncPacket 没记下这个人收到了第几条");
        assertEquals(3, second.missing(), "第二包该从第 4 条起（前三条这个人已经有了）");

        // 对照：记账按人分 —— 另一个人第一次收，照样拿整本；同一个人被忘掉（重连）之后也重新拿整本
        assertEquals(5, texts(sync(server, recipient(UUID.randomUUID()))).size(), "对照：换一个收件人拿整本");
        server.forgetNotificationRecipient(me);
        assertEquals(5, texts(sync(server, player)).size(), "对照：忘了这个人之后重新拿整本");
    }

    @Test
    @DisplayName("播报超过保留上限（400 条）：服务端只留最新的 400 条、丢最旧的；客户端拿到 400 条，缺口如实记成「超出保留范围」的条数")
    void overflowBeyondTheRetentionLimitDropsTheOldestAndCountsTheGap() {
        // 守的缺陷：GameComponent.notify 的上限判断去掉或差一位（> 写成 >=，或整段 while 删掉）。
        // 原先最长的用例只播 80 条，离 400 很远：上限那一行怎么改都绿。这里播到上限再多 5 条，
        // 两种改法在第一条断言上就分得开 —— 去掉：服务端留 405 条（整局越播越大、每个新连上的人都拿整本）；差一位：只留 399 条。
        int limit = NotificationHistory.LIMIT;
        int over = 5;
        UUID player = UUID.randomUUID();
        GameComponent server = EndgameProjectionTest.component(player, EndgameProgress.Stage.ARRIVAL, 0);
        for (int seq = 1; seq <= limit + over; seq++) {
            server.notify(Text.literal("n-" + seq));
        }
        List<Text> kept = server.notifications();
        assertEquals(limit, kept.size(), "服务端保留的播报条数不等于上限：上限判断被去掉或差了一位");
        assertEquals("n-" + (over + 1), kept.getFirst().getString(), "丢的不是最旧的那几条");
        assertEquals(limit + over, server.notificationSeq(), "序号照数（丢的是保留，不是计数）");

        // 客户端：新连上的人拿整本 —— 400 条，最早的 5 条服务端已经不在了，记进 missing（不把截断误当完整历史）
        NotificationHistory<ExperienceView.Event> history = new NotificationHistory<>();
        RegistryByteBuf snapshot = buffer();
        RegistryByteBuf next = buffer();
        try {
            server.writeViewFor(snapshot, player);
            HudView view = GameComponent.readViewFrom(snapshot, history);
            assertEquals(limit, view.notifications().size(), "客户端拿到的条数不等于上限");
            assertEquals("n-" + (over + 1), view.notifications().getFirst().getString());
            assertEquals("n-" + (limit + over), view.notifications().getLast().getString());
            assertEquals(over, history.missing(), "超出保留范围的那几条没记成缺口");

            // 再来一条：客户端自己也只留 400 条，丢最旧的一条，缺口不变（这一条是连着收到的，不是缺的）
            server.notify(Text.literal("n-" + (limit + over + 1)));
            server.writeViewSince(next, player, limit + over);
            view = GameComponent.readViewFrom(next, history);
            assertEquals(limit, view.notifications().size(), "客户端历史超过了上限");
            assertEquals("n-" + (over + 2), view.notifications().getFirst().getString(), "客户端丢的不是最旧的那一条");
            assertEquals(over, history.missing(), "连着收到的一条被算成了缺口");
        } finally {
            snapshot.release();
            next.release();
        }
    }

    private static List<String> texts(NotificationHistory<ExperienceView.Event> history) {
        return history.entries().stream().map(e -> e.message().getString()).toList();
    }

    @Test
    @DisplayName("逐包合并四十条；同一个世界组件上换一局：客户端历史换成新一局的，上一局的不残留")
    void packetsAreMergedBeforeRenderingAndAnotherGameCannotReuseTheSequence() {
        UUID player = UUID.randomUUID();
        GameComponent server = EndgameProjectionTest.component(player, EndgameProgress.Stage.ARRIVAL, 0);
        NotificationHistory<ExperienceView.Event> history = new NotificationHistory<>();
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
        // 守的缺陷：开局（GameComponent.begin）不轮换 notificationEpoch / 不把序号清零。
        // 原先「下一局」是另造的一个组件：新对象自带新的 epoch、序号从 0 起，begin 里那几行删掉也照样绿。
        // 真实的换局发生在同一个世界组件上（整个存档只有一个），所以这里在 server 本身上再走一次开局路径。
        // 换来的局只要局面对得上就行：借一个现成夹具的 Session，座位照夹具的写法摆。
        Map<CharacterId, GameComponent.Occupant> seats = new LinkedHashMap<>();
        seats.put(CharacterId.of("mate"), new GameComponent.Occupant(player, "p"));
        seats.put(CharacterId.of("kid"), new GameComponent.Occupant(null, "dummy"));
        server.begin(EndgameProjectionTest.component(player, EndgameProgress.Stage.ARRIVAL, 0).requireSession(), seats);
        server.clearFog();                    // 雾按布局查表，单测里没有场景数据（同夹具）
        server.notify(Text.literal("new game"));
        RegistryByteBuf packet = buffer();
        try {
            server.writeViewFor(packet, player);
            view = GameComponent.readViewFrom(packet, history);
            assertEquals(List.of("new game"), view.notifications().stream().map(Text::getString).toList(),
                    "换局之后客户端历史不是只有新一局那一条：epoch 没换就被当成同一局的旧序号丢掉；序号没清零就接在上一局四十条后面");
            assertEquals(1, view.notificationSeq(), "新一局第一条的序号该是 1");
        } finally {
            packet.release();
        }
    }
}
