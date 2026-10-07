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
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.play.Table;
import io.github.heavyseasmc.mod.game.GameTiming;
import io.github.heavyseasmc.mod.net.ProvisionUpdateS2C;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
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
 * 每一窗本来有多长进投影（ADR-0099 D8）：时限可配之后，客户端那几面的倒计时条不能再拿编译进去的常量画满格 ——
 * 设成 37 秒的窗口照 20 秒画，条会从「超出满格」开始走，而屏幕上不报错。
 *
 * <p>挑一个<b>与任何默认值都不同</b>的长度（37 秒）：投影若偷偷写回了常量，两种实现给出的答案不同（红测要挑分叉的局面）。
 */
final class WindowTotalsProjectionTest {

    private static final long ODD_MS = 37_000L;
    private static final CharacterId FIRST = CharacterId.of("mate");
    private static final CharacterId LAST = CharacterId.of("kid");

    private static Session session() {
        return new Session("window-totals", new Roster(List.of(
                new Survivor(FIRST, 1, 8, 4, "base", new Ability.None()),
                new Survivor(LAST, 2, 3, 9, "base", new Ability.None()))),
                new Table(new NavigationDeck(List.of(new NavigationCard("calm", 0,
                        new Selector.Nobody(), new Selector.Nobody(), false, false)), new Random(1)),
                        new Provisions(List.of(new Provision("coin", Provision.Category.TREASURE, 1,
                                new ProvisionEffect.ScoreFlat(1, "")))), new Random(1)));
    }

    /** 一个真人（船头）一个替身（船尾）。 */
    private static GameComponent component(UUID human) {
        GameComponent component = new GameComponent(null);
        Map<CharacterId, GameComponent.Occupant> seats = new LinkedHashMap<>();
        seats.put(FIRST, new GameComponent.Occupant(human, "p"));
        seats.put(LAST, new GameComponent.Occupant(null, "dummy"));
        component.begin(session(), seats);
        component.clearFog();                 // 雾按布局查表；这里没有场景数据，雾散了就不查
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

    @Test
    @DisplayName("舵手挑牌：开局快照里设成 37 秒，投影里的总长就是 37 秒")
    void helmWindowTotalReachesTheView() {
        UUID human = UUID.randomUUID();
        GameComponent component = component(human);
        GameTiming timing = GameTiming.DEFAULTS;
        component.setTiming(new GameTiming(timing.actionMs(), timing.rowMs(), timing.consentMs(), timing.stanceMs(),
                timing.stanceBumpMs(), timing.weaponMs(), timing.weaponBumpMs(), timing.contestPickMs(),
                timing.designationMs(), ODD_MS, timing.overboardMs(), timing.thirstMs(), timing.provisionPerCardMs(),
                timing.provisionMinMs(), timing.revealHoldMs(), timing.scoreHoldMs(), timing.untimedDemo()));
        component.openHelmWindow(component.humanWindow(component.timing().helmPickMs()));   // 与 NavigationPhase.begin 同一句
        HudView view = project(component, human);
        assertEquals(ODD_MS, view.sea().helmWindowMs());
        assertTrue(view.sea().helmDeadlineMs() > System.currentTimeMillis(), "到点时刻照旧在");
        component.clearHelm();
        assertEquals(0L, project(component, human).sea().helmWindowMs(), "收窗之后总长清零");
    }

    @Test
    @DisplayName("落海与补给箱：总长随牌桌投影 / 补给箱的包来回一趟不变")
    void overboardAndProvisionTotalsRoundTrip() {
        TableView table = new TableView(List.of(), 3, 12_345L, ODD_MS, List.of("kid"), List.of());
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        try {
            table.write(buf);
            assertEquals(ODD_MS, TableView.read(buf).window());
        } finally {
            buf.release();
        }
        ProvisionUpdateS2C update = new ProvisionUpdateS2C(List.of("mate", "kid"), 0, 4, 12_345L, ODD_MS, List.of("water"));
        RegistryByteBuf reg = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        try {
            ProvisionUpdateS2C.CODEC.encode(reg, update);
            assertEquals(update, ProvisionUpdateS2C.CODEC.decode(reg));
        } finally {
            reg.release();
        }
        assertEquals(ODD_MS, new GameTiming(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1_000, ODD_MS, 0, 0, true)
                .provisionWindow(3), "补给箱：张数少时取下限");
        assertEquals(40_000, new GameTiming(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5_000, ODD_MS, 0, 0, true)
                .provisionWindow(8), "补给箱：每张 5 秒 × 8 张");
    }

    @Test
    @DisplayName("演示局不限时：默认与原先四条件一模一样（总长一年、客户端画 ∞）；显式关掉之后照常限时")
    void untimedDemoDefaultsToTheFourConditions() {
        UUID human = UUID.randomUUID();
        GameComponent component = component(human);
        assertFalse(component.demoNoTimeout(), "随机行动关着：不是演示局");
        component.setDummyRandom(true);
        assertTrue(component.demoNoTimeout(), "自动推进 · 随机 · 有真人 · 有替身：四条都成立");
        component.openHelmWindow(component.humanWindow(20_000L));
        long total = project(component, human).sea().helmWindowMs();
        assertEquals(GameComponent.UNLIMITED_MS, total);
        assertTrue(total >= 24L * 3600 * 1000, "客户端认「不限时」的门槛是一天（GameScreen.UNLIMITED_THRESHOLD_MS）");

        GameTiming d = GameTiming.DEFAULTS;
        component.setTiming(new GameTiming(d.actionMs(), d.rowMs(), d.consentMs(), d.stanceMs(), d.stanceBumpMs(),
                d.weaponMs(), d.weaponBumpMs(), d.contestPickMs(), d.designationMs(), d.helmPickMs(), d.overboardMs(),
                d.thirstMs(), d.provisionPerCardMs(), d.provisionMinMs(), d.revealHoldMs(), d.scoreHoldMs(), false));
        assertFalse(component.demoNoTimeout(), "demo.untimed_humans 关掉：随机替身的局也限时");
        assertEquals(20_000L, component.humanWindow(20_000L));
    }
}
