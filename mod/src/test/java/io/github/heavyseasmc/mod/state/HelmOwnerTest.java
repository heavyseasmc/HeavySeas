package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.state.GameState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 舵手挑牌窗口开了就不换人（ADR-0051 B5，用户 2026-10-01 拍板）。
 *
 * <p>舵手按「最靠船尾的清醒在线者」现算；窗口里认的是开窗那一刻记下的那一位 ——
 * 谁能收到划船堆的牌、谁的挑牌包算数、超时替谁挑，都走 {@link GameComponent#helmSeat}。
 */
class HelmOwnerTest {

    private static final CharacterId FIRST = CharacterId.of("mate");
    private static final CharacterId SECOND = CharacterId.of("captain");
    private static final CharacterId LAST = CharacterId.of("kid");

    private static GameState start() {
        return GameState.start(new Roster(List.of(
                new Survivor(FIRST, 1, 8, 4, "base", new Ability.None()),
                new Survivor(SECOND, 2, 7, 5, "base", new Ability.None()),
                new Survivor(LAST, 3, 3, 9, "base", new Ability.None()))));
    }

    @Test
    @DisplayName("窗口开着时舵手掉线：还认开窗那一位，不换成下一个清醒的人")
    void openWindowKeepsItsHelmsman() {
        GameState g = start();
        assertEquals(Optional.of(LAST), g.helmsman(), "前提：最靠船尾的清醒在线者是小孩");
        GameComponent component = new GameComponent(null);
        component.setHelmDeadline(System.currentTimeMillis() + 12_000L);
        component.setHelmOwner(LAST);
        GameState offline = g.withOffline(LAST, true);
        assertEquals(Optional.of(SECOND), offline.helmsman(), "对照：按局面现算的话舵手已经换成船长");
        assertEquals(Optional.of(LAST), component.helmSeat(offline), "窗口开着：认开窗那一刻的舵手");
    }

    @Test
    @DisplayName("窗口关了（结算过 / 没开过）：按现在的局面算")
    void closedWindowFollowsTheState() {
        GameState offline = start().withOffline(LAST, true);
        GameComponent component = new GameComponent(null);
        component.setHelmOwner(LAST);
        assertEquals(Optional.of(SECOND), component.helmSeat(offline), "没开窗时记下的名字不算");
        component.setHelmDeadline(System.currentTimeMillis() + 12_000L);
        component.clearHelm();
        assertEquals(Optional.of(SECOND), component.helmSeat(offline), "clearHelm 连同记下的舵手一起清掉");
    }
}
