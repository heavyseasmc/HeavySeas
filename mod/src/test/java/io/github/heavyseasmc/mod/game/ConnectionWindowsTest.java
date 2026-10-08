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
import io.github.heavyseasmc.mod.state.GameComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 演示局里真人掉线：他正在答的那几扇窗不再等他（审查 2026-10-07 K1）。
 *
 * <p>演示局里等真人的窗口开一年（{@link GameComponent#UNLIMITED_MS}）。原设计「持箱人 / 舵手掉线就等超时替他选」，
 * 超时一年就等于永远；掉线这条路原先一扇窗都没收。每一条都配一个「掉线的是别人」的对照 —— 收错了窗与没收一样糟。
 */
final class ConnectionWindowsTest {

    private static final CharacterId FIRST = CharacterId.of("mate");
    private static final CharacterId SECOND = CharacterId.of("captain");
    private static final CharacterId LAST = CharacterId.of("kid");

    /** 三人局：船头两个真人、船尾一个替身；牌堆里的物资够开一箱。 */
    private static GameComponent component() {
        Session session = new Session("connection-windows", new Roster(List.of(
                new Survivor(FIRST, 1, 8, 4, "base", new Ability.None()),
                new Survivor(SECOND, 2, 7, 5, "base", new Ability.None()),
                new Survivor(LAST, 3, 3, 9, "base", new Ability.None()))),
                new Table(new NavigationDeck(List.of(new NavigationCard("calm", 0,
                        new Selector.Nobody(), new Selector.Nobody(), false, false)), new Random(1)),
                        new Provisions(List.of(new Provision("coin", Provision.Category.TREASURE, 20,
                                new ProvisionEffect.ScoreFlat(1, "")))), new Random(1)));
        GameComponent component = new GameComponent(null);
        Map<CharacterId, GameComponent.Occupant> seats = new LinkedHashMap<>();
        seats.put(FIRST, new GameComponent.Occupant(UUID.randomUUID(), "a"));
        seats.put(SECOND, new GameComponent.Occupant(UUID.randomUUID(), "b"));
        seats.put(LAST, new GameComponent.Occupant(null, "dummy"));
        component.begin(session, seats);
        return component;
    }

    @Test
    @DisplayName("补给箱在他手上时掉线：截止改成现在（下一 tick 照到点那样替他选）；掉线的是别人就不动")
    void provisionHolderLeaves() {
        GameComponent component = component();
        Session session = component.requireSession();
        session.beginProvision();
        assertEquals(FIRST, session.provisionHolder().orElseThrow(), "前提：箱子从船头传起");
        component.openProvisionWindow(GameComponent.UNLIMITED_MS);
        long now = System.currentTimeMillis();

        session.setOffline(SECOND, true);
        ConnectionPhase.windowsAfterDisconnect(null, component, SECOND, now);
        assertTrue(component.provisionDeadline() > now + 3_600_000L, "对照：掉线的不是持箱人，箱子的窗被收了");

        session.setOffline(FIRST, true);
        List<String> closed = ConnectionPhase.windowsAfterDisconnect(null, component, FIRST, now);
        assertEquals(now, component.provisionDeadline(), "持箱人掉线了，补给箱还在等他一年");
        assertTrue(closed.contains("provision"), "收了却没报：" + closed);
    }

    @Test
    @DisplayName("开窗那一刻的舵手掉线：截止改成现在；掉线的是别人就不动")
    void helmsmanLeaves() {
        GameComponent component = component();
        Session session = component.requireSession();
        component.openHelmWindow(GameComponent.UNLIMITED_MS);
        component.setHelmOwner(SECOND);
        long now = System.currentTimeMillis();

        session.setOffline(FIRST, true);
        ConnectionPhase.windowsAfterDisconnect(null, component, FIRST, now);
        assertTrue(component.helmDeadline() > now + 3_600_000L, "对照：掉线的不是舵手，挑牌的窗被收了");

        session.setOffline(SECOND, true);
        ConnectionPhase.windowsAfterDisconnect(null, component, SECOND, now);
        assertEquals(now, component.helmDeadline(), "舵手掉线了，挑牌的窗还在等他一年");
    }

    /**
     * 限时的窗不动：原设计「掉线就等超时按默认挑」（ADR-0051 B5，用户 2026-10-01 拍板）—— 剩下那十几秒里重连还能自己答。
     * 只有不限时的窗（等不到超时）才提前到点。
     */
    @Test
    @DisplayName("正常局（限时的窗）：持箱人 · 舵手掉线照旧等超时")
    void timedWindowsWaitForTheTimeout() {
        GameComponent component = component();
        Session session = component.requireSession();
        session.beginProvision();
        component.openProvisionWindow(20_000L);
        component.openHelmWindow(20_000L);
        component.setHelmOwner(FIRST);
        long provision = component.provisionDeadline();
        long helm = component.helmDeadline();

        session.setOffline(FIRST, true);
        List<String> closed = ConnectionPhase.windowsAfterDisconnect(null, component, FIRST, System.currentTimeMillis());
        assertEquals(provision, component.provisionDeadline(), "限时的补给箱被提前收了");
        assertEquals(helm, component.helmDeadline(), "限时的挑牌窗被提前收了");
        assertTrue(closed.isEmpty(), "限时的窗不该报收了：" + closed);
    }
}
