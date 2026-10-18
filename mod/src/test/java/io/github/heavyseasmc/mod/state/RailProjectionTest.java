package io.github.heavyseasmc.mod.state;

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
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.play.Table;
import io.github.heavyseasmc.engine.state.Phase;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 座位轨上的公开标记进投影（ADR-0095 B4 · 样张 A，{@link HudView.Rail}）：在等哪几座、各自的钟、补给箱在谁手上。
 *
 * <p>判据都从<b>旁观者</b>（没有座位的人）那一包里读：这一段是公开的，旁观者读得到才说明它真的进了公开那一半；
 * 另拿发起者本人那一包对照，两包这一段必须相同（公开信息不按人裁剪）。
 */
final class RailProjectionTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.initialize();
    }

    private static final CharacterId A = CharacterId.of("mate");
    private static final CharacterId B = CharacterId.of("kid");
    private static final CharacterId C = CharacterId.of("captain");
    private static final CharacterId D = CharacterId.of("doctor");

    private static Session session() {
        return new Session("rail-projection", new Roster(List.of(
                new Survivor(A, 1, 8, 4, "base", new Ability.None()),
                new Survivor(B, 2, 3, 9, "base", new Ability.None()),
                new Survivor(C, 3, 7, 5, "base", new Ability.None()),
                new Survivor(D, 4, 4, 6, "base", new Ability.None()))),
                new Table(new NavigationDeck(List.of(new NavigationCard("calm", 0,
                        new Selector.Nobody(), new Selector.Nobody(), false, false)), new Random(1)),
                        new Provisions(List.of(new Provision("coin", Provision.Category.TREASURE, 1,
                                new ProvisionEffect.ScoreFlat(1, "")))), new Random(1)));
    }

    private static GameComponent component(UUID human) {
        GameComponent component = new GameComponent(null);
        Map<CharacterId, GameComponent.Occupant> seats = new LinkedHashMap<>();
        seats.put(A, new GameComponent.Occupant(human, "p"));
        seats.put(B, new GameComponent.Occupant(null, "dummy-b"));
        seats.put(C, new GameComponent.Occupant(null, "dummy-c"));
        seats.put(D, new GameComponent.Occupant(null, "dummy-d"));
        component.begin(session(), seats);
        component.clearFog();
        return component;
    }

    private static HudView project(GameComponent component, UUID recipient) {
        RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        try {
            component.writeViewFor(buf, recipient);
            HudView view = GameComponent.readViewFrom(buf);
            assertFalse(buf.isReadable(), "读完还剩字节：两端的字段表不一致");
            return view;
        } finally {
            buf.release();
        }
    }

    private static List<String> who(HudView view) {
        return view.rail().waits().stream().map(HudView.Rail.Wait::who).toList();
    }

    @Test
    @DisplayName("站队段同时等好几座：每个还能加入的人都在等，同一只钟；旁观者那一包里读得到、与本人那一包相同")
    void stancesWaitOnEveryoneWhoCanJoin() {
        UUID human = UUID.randomUUID();
        GameComponent component = component(human);
        Session s = component.requireSession();
        s.advancePhase();
        assertEquals(Phase.ACTION, s.state().phase(), "前提：行动阶段");
        s.declare(A, Contest.Kind.SWAP, B);
        component.openContestWindow(20_000);
        HudView consent = project(component, UUID.randomUUID());
        assertEquals(List.of(B.value()), who(consent), "表态段只等被指的那一位");
        assertEquals(B.value(), consent.contest().target(), "被指的人公开（座位轨上画朱砂准星）");

        s.consent(true);
        component.openContestWindow(15_000);
        HudView spectator = project(component, UUID.randomUUID());
        assertEquals(List.of(C.value(), D.value()), who(spectator), "站队段：两个旁观的都在等，按座次");
        long deadline = spectator.rail().waits().getFirst().deadlineMs();
        assertTrue(spectator.rail().waits().stream().allMatch(w -> w.deadlineMs() == deadline && w.windowMs() == 15_000),
                "同一段同一只钟");
        assertEquals(spectator.rail(), project(component, human).rail(), "公开的这一段不按收件人裁剪");
    }

    @Test
    @DisplayName("补给箱：在谁手上、他那一手的钟，全船都看得见（逐手的包本来就发给全船）")
    void provisionHolderIsPublic() {
        UUID human = UUID.randomUUID();
        GameComponent component = component(human);
        Session s = component.requireSession();
        assertEquals(Phase.PROVISION, s.state().phase(), "前提：物资阶段");
        assertFalse(s.beginProvision().isEmpty(), "前提：箱里有牌");
        component.openProvisionWindow(37_000);
        HudView spectator = project(component, UUID.randomUUID());
        String holder = s.provisionHolder().orElseThrow().value();
        assertEquals(holder, spectator.rail().boxHolder());
        assertEquals(List.of(holder), who(spectator));
        assertEquals(37_000, spectator.rail().waits().getFirst().windowMs());
    }

    @Test
    @DisplayName("行动：轮到的人那只钟公开；小孩无预告的抢夺把钟冻住，取消后重开窗口也不跳（不泄露他按过抢夺）")
    void quietDesignationDoesNotMoveTheClock() throws Exception {
        UUID human = UUID.randomUUID();
        GameComponent component = component(human);
        Session s = component.requireSession();
        s.advancePhase();
        CharacterId actor = s.nextActor().orElseThrow();
        component.openActionWindow(20_000);
        HudView before = project(component, UUID.randomUUID());
        assertEquals(List.of(actor.value()), who(before), "旁观者也看得见轮到谁、还剩多久");
        long clock = before.rail().waits().getFirst().deadlineMs();
        assertEquals(clock, component.actionDeadline());

        Thread.sleep(5);                      // 让新开的窗口一定是另一个到点时刻：钟若跟着跳，判据分得出来
        component.beginDesignation(actor, Contest.Kind.STEAL, 20_000, true);   // 与 DesignationPhase.begin 同一个次序
        component.clearActionWindow();
        HudView quiet = project(component, UUID.randomUUID());
        assertEquals(List.of(actor.value()), who(quiet), "无预告：照旧只在等他");
        assertEquals(clock, quiet.rail().waits().getFirst().deadlineMs(), "无预告：钟没动");

        component.clearDesignation();         // 取消：DesignationPhase.cancel 重开一扇整窗
        component.openActionWindow(20_000);
        assertTrue(component.actionDeadline() != clock, "前提：新窗口的到点时刻与原先不同");
        assertEquals(clock, project(component, UUID.randomUUID()).rail().waits().getFirst().deadlineMs(),
                "取消之后钟也不能跳回满格");
    }

    @Test
    @DisplayName("有预告的举拳头：钟换成指定模式那一窗（全船都看见他发光、听见播报的秒数）")
    void loudDesignationShowsItsWindow() throws Exception {
        UUID human = UUID.randomUUID();
        GameComponent component = component(human);
        Session s = component.requireSession();
        s.advancePhase();
        CharacterId actor = s.nextActor().orElseThrow();
        component.openActionWindow(20_000);
        long clock = component.actionDeadline();
        Thread.sleep(5);
        component.beginDesignation(actor, Contest.Kind.SWAP, 23_000, false);
        component.clearActionWindow();
        HudView.Rail.Wait w = project(component, UUID.randomUUID()).rail().waits().getFirst();
        assertEquals(23_000, w.windowMs());
        assertTrue(w.deadlineMs() != clock, "钟换成了指定模式那一窗");
    }

    @Test
    @DisplayName("没开窗口：不列人（客户端照旧呼吸、不画钟）")
    void nothingWhenNoWindow() {
        UUID human = UUID.randomUUID();
        GameComponent component = component(human);
        Session s = component.requireSession();
        s.advancePhase();
        assertTrue(project(component, human).rail().waits().isEmpty(), "行动窗口没开：不列人");
        assertTrue(project(component, human).rail().boxHolder().isEmpty(), "不在物资阶段：没有箱子");
    }
}
