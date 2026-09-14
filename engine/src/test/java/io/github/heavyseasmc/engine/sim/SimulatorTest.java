package io.github.heavyseasmc.engine.sim;

import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.Selector;
import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.GameState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 随机对局。
 *
 * <p>牌堆是<b>合成的</b>，不读 `data/navigation` —— 那份数据在 O1 定案前不入库，
 * CI 上根本没有它。而模拟器要找的是死锁与非法状态，那两者不依赖具体的落水名单分布。
 * 真实牌堆的统计性质是 O1 的事，不是这里的事。
 */
class SimulatorTest {

    private static final CharacterId JEWELER = CharacterId.of("jeweler");
    private static final CharacterId MATE = CharacterId.of("mate");
    private static final CharacterId HOSTESS = CharacterId.of("hostess");
    private static final CharacterId SAILOR = CharacterId.of("sailor");
    private static final CharacterId KID = CharacterId.of("kid");

    private static Roster roster() {
        return new Roster(List.of(
                new Survivor(JEWELER, 1, 4, 8, "base", new Ability.None()),
                new Survivor(MATE, 4, 8, 4, "base", new Ability.None()),
                new Survivor(HOSTESS, 5, 3, 9, "base",
                        new Ability.ShareEffect(List.of("water"), true, true, Map.of())),
                new Survivor(SAILOR, 6, 6, 6, "base",
                        new Ability.OverboardImmune(true, List.of("bait_bucket"))),
                new Survivor(KID, 8, 3, 9, "base", new Ability.None())));
    }

    /** 合成牌堆：铺开五种点名模式、海鸥的三种取值、两个图示的四种组合。 */
    private static List<NavigationCard> deck() {
        List<NavigationCard> cards = new ArrayList<>();
        List<Selector> selectors = List.of(
                new Selector.Nobody(),
                new Selector.Everyone(),
                new Selector.Only(Set.of(MATE, KID)),
                new Selector.Except(Set.of(MATE)),
                new Selector.Conditional("used_rum"));
        int n = 0;
        for (int gull : new int[]{0, 0, 0, 1, -1}) {
            for (Selector over : selectors) {
                for (Selector thirst : selectors) {
                    cards.add(new NavigationCard("syn_" + n++, gull, over, thirst,
                            (n & 1) == 0, (n & 2) == 0));
                }
            }
        }
        return cards;
    }

    // ❗整类加超时：死锁在测试里的表现是**挂死**，不是失败 —— CI 上就是超时，没有任何信号。
    //   2026-09-14 变异测试实证：把 Simulator 的回合上限改成永不触发，这些用例会一直转下去。
    //   必须写 SEPARATE_THREAD：@Timeout 默认在同一线程里跑，只在测试**返回之后**才比对耗时，
    //   对真正的死循环毫无作用 —— 那正是这里要防的东西。
    @Nested
    @DisplayName("几千局")
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    class ManyGames {

        @Test
        @DisplayName("2000 局全部跑到终局，无死锁、无非法状态")
        void thousandsOfGames() {
            Simulator sim = new Simulator(roster(), deck());
            Map<GameState.Outcome, Integer> outcomes = new HashMap<>();
            long totalTurns = 0;
            int totalFights = 0;

            for (long seed = 0; seed < 2000; seed++) {
                Simulator.Result r = sim.run(seed);      // 非法状态或死锁会在这里抛
                outcomes.merge(r.outcome(), 1, Integer::sum);
                totalTurns += r.turns();
                totalFights += r.fights();
                assertTrue(r.turns() >= 1 && r.turns() <= Simulator.TURN_LIMIT,
                        "seed=" + seed + " 回合数越界: " + r.turns());
            }

            // ❗两种终局都必须出现过。只出现一种说明状态空间没铺开 ——
            //   那时「2000 局全过」是个假绿，它只证明了走通一条路。
            assertTrue(outcomes.getOrDefault(GameState.Outcome.LANDED, 0) > 0,
                    "没有任何一局靠岸获救，牌堆或海鸥逻辑有问题: " + outcomes);
            assertTrue(outcomes.getOrDefault(GameState.Outcome.ALL_DEAD, 0) > 0,
                    "没有任何一局全员死亡，伤害链路可能根本没跑起来: " + outcomes);
            assertTrue(totalFights > 0, "一架都没打过，战斗分支没被覆盖");
            assertTrue(totalTurns > 0);
        }

        @Test
        @DisplayName("同一个种子跑两次结果完全一致 —— 失败时才复现得了")
        void seedIsReproducible() {
            Simulator sim = new Simulator(roster(), deck());
            for (long seed : new long[]{0, 1, 42, 12345, -7}) {
                assertEquals(sim.run(seed), sim.run(seed), "seed=" + seed + " 不可复现");
            }
        }

        @Test
        @DisplayName("不同种子会走出不同的局 —— 否则随机没生效")
        void seedsDiffer() {
            Simulator sim = new Simulator(roster(), deck());
            Set<Integer> turnCounts = new java.util.HashSet<>();
            for (long seed = 0; seed < 50; seed++) {
                turnCounts.add(sim.run(seed).turns());
            }
            assertTrue(turnCounts.size() > 1, "50 个种子跑出同样的回合数，随机没生效");
        }
    }

    @Nested
    @DisplayName("不变量本身")
    class InvariantChecks {

        @Test
        @DisplayName("干净的开局通过")
        void freshStateIsValid() {
            assertTrue(Invariants.check(GameState.start(roster())).isEmpty());
        }

        @Test
        @DisplayName("座位撞车会被抓到")
        void duplicateSeatCaught() {
            GameState g = GameState.start(roster());
            g = g.withState(KID, g.stateOf(KID).withSeat(1));     // 与珠宝商同为 1 号位
            List<String> bad = Invariants.check(g);
            assertFalse(bad.isEmpty());
            assertTrue(String.join(";", bad).contains("座位"), bad.toString());
        }

        @Test
        @DisplayName("❗「行动过然后死掉」是合法的，不该被判非法")
        void actedThenDiedIsLegal() {
            // 这一条是被模拟器逼出来的：原先有一条「死人不该带着行动标记」的不变量，
            // 2000 局里第 3 回合就红了 —— 而红的是那条不变量本身，不是状态机。
            // 角色在行动阶段合法行动，随后在同一回合的航海阶段死掉，标记要到回合结束才清。
            GameState fresh = GameState.start(roster());
            GameState g = fresh.withState(KID, fresh.stateOf(KID).markActed().hurt(4));
            assertEquals(Condition.DEAD, g.conditionOf(KID));
            assertTrue(g.stateOf(KID).actedThisTurn());
            assertTrue(Invariants.check(g).isEmpty(), "这是正常对局，不该报非法: " + Invariants.check(g));
        }

        @Test
        @DisplayName("死亡不可复生 —— 跨状态才看得出来")
        void resurrectionCaught() {
            GameState fresh = GameState.start(roster());
            GameState dead = fresh.withState(KID, fresh.stateOf(KID).hurt(4));
            GameState revived = dead.withState(KID, dead.stateOf(KID).withDamage(0));
            assertEquals(Condition.DEAD, dead.conditionOf(KID));
            String msg = String.join(";", Invariants.checkTransition(dead, revived));
            assertTrue(msg.contains("复活"), msg);
            assertTrue(msg.contains("伤害从"), msg);
            assertTrue(Invariants.checkTransition(fresh, dead).isEmpty(), "受伤加重是正常的");
        }

        @Test
        @DisplayName("回合数倒退会被抓到")
        void turnRegressionCaught() {
            GameState t1 = GameState.start(roster());
            GameState t2 = t1.advancePhase().advancePhase().advancePhase();
            assertTrue(Invariants.checkTransition(t1, t2).isEmpty());
            assertTrue(String.join(";", Invariants.checkTransition(t2, t1)).contains("回合数倒退"));
        }

        @Test
        @DisplayName("requireValid 报出种子与位置，否则复现不了")
        void requireValidReportsSeed() {
            GameState fresh = GameState.start(roster());
            GameState g = fresh.withState(KID, fresh.stateOf(KID).withSeat(1));
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> Invariants.requireValid(g, 4242L, "测试"));
            assertTrue(e.getMessage().contains("seed=4242"), e.getMessage());
            assertTrue(e.getMessage().contains("测试"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("模拟器自身的守卫")
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    class SimulatorGuards {

        @Test
        @DisplayName("空牌堆拒绝构造 —— 没有牌就永远结束不了")
        void emptyDeckRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Simulator(roster(), List.of()));
        }

        @Test
        @DisplayName("一张海鸥都不给的牌堆会撞上回合上限，而不是无声空转")
        void deadlockIsReported() {
            List<NavigationCard> harmless = List.of(new NavigationCard(
                    "calm", 0, new Selector.Nobody(), new Selector.Nobody(), false, false));
            Simulator sim = new Simulator(roster(), harmless);
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> sim.run(1));
            assertTrue(e.getMessage().contains("疑似死锁"), e.getMessage());
            assertTrue(e.getMessage().contains("seed=1"), e.getMessage());
        }

        @Test
        @DisplayName("单人阵容也能跑完 —— 只剩一人不是终局条件")
        void soloRosterFinishes() {
            Roster solo = new Roster(List.of(
                    new Survivor(KID, 1, 3, 9, "base", new Ability.None())));
            Simulator sim = new Simulator(solo, deck());
            for (long seed = 0; seed < 200; seed++) {
                sim.run(seed);
            }
        }
    }
}
