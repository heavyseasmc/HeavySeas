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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 两次同步之间新到了几条播报（审查 2026-10-07 U13）。
 *
 * <p>原先客户端靠比内容数：上一帧的尾巴接上这一帧的开头。两种局面它数错，而且错得不报错 ——
 * 一帧之内来的超过投影留的条数（8 人局终局同一 tick 九条），最早那几条在发出去之前就从服务端掉了，日志历史里永远缺；
 * 内容一模一样的几条接连来（几场打架各一句「败方每人受 1 点伤害」），比内容会把「来了一条」看成「什么都没来」。
 * 局面挑的是两种都撞上的：九条一模一样的接在八条一模一样的后面。
 *
 * <p>这里核对组件计数与到达数；真实写包、读包及客户端合并由 {@link NotificationProjectionTest} 覆盖。
 */
final class NotificationBurstTest {

    private static final Text SAME = Text.literal("败方每人受 1 点伤害。");
    private static final CharacterId A = CharacterId.of("mate");
    private static final CharacterId B = CharacterId.of("kid");

    private static Session session(String context) {
        Roster roster = new Roster(List.of(
                new Survivor(A, 1, 8, 4, "base", new Ability.None()),
                new Survivor(B, 2, 3, 9, "base", new Ability.None())));
        Session session = new Session(context, roster,
                new Table(new NavigationDeck(List.of(new NavigationCard("calm", 0,
                        new Selector.Nobody(), new Selector.Nobody(), false, false)), new Random(1)),
                        new Provisions(List.of(new Provision("coin", Provision.Category.TREASURE, 1,
                                new ProvisionEffect.ScoreFlat(1, "")))), new Random(1)));
        session.dealAffinities(Affinities.random(roster, new Random(3)));
        return session;
    }

    private static Map<CharacterId, GameComponent.Occupant> seats() {
        Map<CharacterId, GameComponent.Occupant> seats = new LinkedHashMap<>();
        seats.put(A, new GameComponent.Occupant(UUID.randomUUID(), "p"));
        seats.put(B, new GameComponent.Occupant(null, "dummy"));
        return seats;
    }

    private static GameComponent component() {
        GameComponent component = new GameComponent(null);
        component.begin(session("notification-burst"), seats());
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
    @DisplayName("同一个组件上开新一局：序号从 0 数起、对局身份换一个、上一局的播报不留")
    void aNewGameStartsAtZero() {
        // 守的缺陷：开局（GameComponent.begin）漏了序号清零 / notificationEpoch 轮换。
        // 原先这里断言的是 component() 新造的那个组件 —— 新对象的序号天然是 0、epoch 天然是新的，begin 里那几行删光了也照样绿。
        // 世界组件整个存档只有一个，真实的换局是同一个实例上再 begin 一次，所以这里在同一个组件上走开局路径。
        GameComponent component = component();
        component.notify(SAME);
        component.notify(SAME);
        assertEquals(2, component.notificationSeq(), "前提：上一局播了两条");
        UUID previous = component.notificationEpoch();

        component.begin(session("notification-burst-next"), seats());

        assertEquals(0, component.notificationSeq(),
                "新一局的序号接着上一局数：投影里第一条的序号对不上，客户端会凭空记一段「超出保留范围」的缺口");
        assertNotEquals(previous, component.notificationEpoch(),
                "换了一局对局身份没换：客户端按（身份, 序号）合并，新一局序号比上一局小，开场那几条会被当成早收过的丢掉");
        assertEquals(List.of(), component.notifications(), "上一局的播报留到了新一局");
    }
}
