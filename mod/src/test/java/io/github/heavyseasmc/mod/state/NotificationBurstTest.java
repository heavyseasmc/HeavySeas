package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.Affinities;
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
import io.github.heavyseasmc.mod.ui.NotificationArrivals;
import net.minecraft.text.Text;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 两次同步之间新到了几条播报（审查 2026-10-07 U13）。
 *
 * <p>原先客户端靠比内容数：上一帧的尾巴接上这一帧的开头。两种局面它数错，而且错得不报错 ——
 * 一帧之内来的超过投影留的条数（8 人局终局同一 tick 九条），最早那几条在发出去之前就从服务端掉了，日志历史里永远缺；
 * 内容一模一样的几条接连来（几场打架各一句「败方每人受 1 点伤害」），比内容会把「来了一条」看成「什么都没来」。
 * 局面挑的是两种都撞上的：九条一模一样的接在八条一模一样的后面。
 *
 * <p>❗不经投影的编解码：播报文本序列化要 Minecraft 引导过（{@code Style$Codecs}），单测里没有。
 * 序号进出投影那两行（{@code writeView} / {@code readView}）读码核对。
 */
final class NotificationBurstTest {

    private static final Text SAME = Text.literal("败方每人受 1 点伤害。");

    private static GameComponent component() {
        CharacterId a = CharacterId.of("mate");
        CharacterId b = CharacterId.of("kid");
        Roster roster = new Roster(List.of(
                new Survivor(a, 1, 8, 4, "base", new Ability.None()),
                new Survivor(b, 2, 3, 9, "base", new Ability.None())));
        Session session = new Session("notification-burst", roster,
                new Table(new NavigationDeck(List.of(new NavigationCard("calm", 0,
                        new Selector.Nobody(), new Selector.Nobody(), false, false)), new Random(1)),
                        new Provisions(List.of(new Provision("coin", Provision.Category.TREASURE, 1,
                                new ProvisionEffect.ScoreFlat(1, "")))), new Random(1)));
        session.dealAffinities(Affinities.random(roster, new Random(3)));
        GameComponent component = new GameComponent(null);
        Map<CharacterId, GameComponent.Occupant> seats = new LinkedHashMap<>();
        seats.put(a, new GameComponent.Occupant(UUID.randomUUID(), "p"));
        seats.put(b, new GameComponent.Occupant(null, "dummy"));
        component.begin(session, seats);
        return component;
    }

    @Test
    @DisplayName("一帧之内来了九条一模一样的播报：数得出九条，九条都还在要发出去的那一份里")
    void aBurstOfIdenticalLinesIsCountedInFull() {
        GameComponent component = component();
        for (int i = 0; i < 8; i++) {
            component.notify(SAME);
        }
        long before = component.notificationSeq();
        for (int i = 0; i < 9; i++) {
            component.notify(SAME);
        }
        int arrived = NotificationArrivals.count(before, component.notificationSeq(), component.notifications().size());
        assertEquals(9, arrived, "两次同步之间新到了九条");
    }

    @Test
    @DisplayName("新一局从 0 数起")
    void aNewGameStartsAtZero() {
        GameComponent component = component();
        component.notify(SAME);
        component.notify(SAME);
        assertEquals(2, component.notificationSeq());
        assertEquals(0, component().notificationSeq());
    }
}
