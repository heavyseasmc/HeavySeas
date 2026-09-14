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

    /**
     * 点名统计 —— O1 要的那条分布曲线的原料。
     *
     * <p>这里全部用<b>单人阵容</b>做精确断言：一个人时打不起来、也换不了座位，
     * 唯一的伤害来源就是落海，于是次数是可以一个一个数出来的。
     * 多人局里随机战斗会插进来，那时只能断言不等式 —— 那种断言对「分母漏加了」不敏感。
     */
    @Nested
    @DisplayName("点名统计")
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    class ExposureStats {

        private Roster solo(CharacterId id, int size) {
            return new Roster(List.of(new Survivor(id, 1, size, 12 - size, "base", new Ability.None())));
        }

        private List<NavigationCard> oneCard(int gull, Selector overboard, Selector thirst) {
            return List.of(new NavigationCard("fixed", gull, overboard, thirst, false, false));
        }

        @Test
        @DisplayName("分母只数活着的回合：体型 3 的角色在第 4 次落海时死，分母就停在 4")
        void deadStopAccruing() {
            // 每回合必落海、不点口渴、没有海鸥。伤害 3 = 昏迷，4 = 死亡。
            Simulator sim = new Simulator(solo(KID, 3), oneCard(0, new Selector.Everyone(), new Selector.Nobody()));
            Simulator.Result r = sim.run(0);

            assertEquals(GameState.Outcome.ALL_DEAD, r.outcome());
            Exposure kid = r.exposure().get(KID);
            assertEquals(4, kid.overboardTurns(), "第 4 次落海把他打死，之后不该再有机会数");
            assertEquals(4, kid.overboards(), "每一回合都被点到，命中数应当等于机会数");

            // ❗口渴的分母比落海少一次：第 4 回合他死在落海那一步，口渴结算根本没轮到。
            //   两个分母分开数，正是为了让这种「死在半途」不被算成「口渴过但没被点」。
            assertEquals(3, kid.thirstTurns());
            assertEquals(0, kid.thirsts());
        }

        @Test
        @DisplayName("❗死了就不再计入分母，而这只有在「他死了、局还没完」时才看得出来")
        void deadStopAccruingWhileTheGameGoesOn() {
            // 单人局里死亡就是终局，分母停不停都一样 —— 那种局面证明不了任何事。
            // 两人局才分得开：小孩第 4 次落海时死，大副还要再挨 5 次。
            Roster pair = new Roster(List.of(
                    new Survivor(KID, 1, 3, 9, "base", new Ability.None()),
                    new Survivor(MATE, 2, 8, 4, "base", new Ability.None())));
            Simulator sim = new Simulator(pair, oneCard(0, new Selector.Everyone(), new Selector.Nobody()));

            int clean = 0;
            for (long seed = 0; seed < 500; seed++) {
                Simulator.Result r = sim.run(seed);
                if (r.fights() > 0) {
                    continue;               // 打过架就有额外伤害，次数数不准，换个种子
                }
                clean++;
                assertEquals(GameState.Outcome.ALL_DEAD, r.outcome());
                assertEquals(9, r.turns(), "seed=" + seed);
                assertEquals(4, r.exposure().get(KID).overboardTurns(),
                        "seed=" + seed + " 小孩死后还在涨分母");
                assertEquals(9, r.exposure().get(MATE).overboardTurns(), "seed=" + seed);
            }
            assertTrue(clean > 0, "500 局里没有一局是零战斗，这条断言其实一次都没执行");
        }

        @Test
        @DisplayName("❗水手落水不受伤，但那仍然是落水 —— 统计数的是下水，不是受伤")
        void immunityDoesNotHideTheSplash() {
            Roster solo = new Roster(List.of(new Survivor(SAILOR, 1, 6, 6, "base",
                    new Ability.OverboardImmune(true, List.of("bait_bucket")))));
            // 每张牌一只海鸥：第 4 只出现时当场结束，那一回合的落海不再结算。
            Simulator sim = new Simulator(solo, oneCard(1, new Selector.Everyone(), new Selector.Nobody()));
            Simulator.Result r = sim.run(0);

            assertEquals(GameState.Outcome.LANDED, r.outcome());
            Exposure sailor = r.exposure().get(SAILOR);
            assertEquals(3, sailor.overboardTurns(), "第 4 回合海鸥先结束了一局，那一次不该计入");
            assertEquals(3, sailor.overboards(), "免伤不等于没下水");
            assertEquals(1.0, sailor.overboardRate().orElseThrow());
        }

        @Test
        @DisplayName("没点到的人分母照样在涨 —— 否则「没被点到」与「没上过场」分不开")
        void unnamedStillAccrueChances() {
            Simulator sim = new Simulator(solo(KID, 3), oneCard(1, new Selector.Nobody(), new Selector.Everyone()));
            Simulator.Result r = sim.run(0);

            Exposure kid = r.exposure().get(KID);
            assertEquals(0, kid.overboards());
            assertTrue(kid.overboardTurns() > 0, "一次落海结算都没执行过？那这份统计什么也没测");
            assertEquals(0.0, kid.overboardRate().orElseThrow());
            assertEquals(kid.thirstTurns(), kid.thirsts(), "口渴点的是所有人，命中应当等于机会");
        }

        @Test
        @DisplayName("多人局：点名只落在名单里的人身上，别人一次都不该有")
        void onlyNamedAreHit() {
            Simulator sim = new Simulator(roster(),
                    oneCard(1, new Selector.Only(Set.of(MATE)), new Selector.Only(Set.of(KID))));
            Simulator.Result r = sim.run(7);

            for (CharacterId id : List.of(JEWELER, HOSTESS, SAILOR)) {
                Exposure e = r.exposure().get(id);
                assertEquals(0, e.overboards(), id + " 不在落海名单里却被点到了");
                assertEquals(0, e.thirsts(), id + " 不在口渴名单里却被点到了");
                assertTrue(e.overboardTurns() > 0, id + " 的机会数是 0，统计没在数他");
            }
            assertTrue(r.exposure().get(MATE).overboards() > 0, "名单上的人一次都没被点到");
            assertTrue(r.exposure().get(KID).thirsts() > 0, "名单上的人一次都没口渴");
        }

        @Test
        @DisplayName("几千局汇总：每个角色都有机会数，且命中不多于机会")
        void aggregateOverManyGames() {
            Simulator sim = new Simulator(roster(), deck());
            Map<CharacterId, Exposure> total = new HashMap<>();
            for (long seed = 0; seed < 2000; seed++) {
                sim.run(seed).exposure().forEach((id, e) ->
                        total.merge(id, e, Exposure::plus));       // plus 里的守卫会拦下「命中多于机会」
            }
            assertEquals(roster().size(), total.size(), "有角色从头到尾没进过统计: " + total.keySet());
            total.forEach((id, e) -> {
                assertTrue(e.overboardTurns() > 0, id + " 一次落海结算都没赶上");
                assertTrue(e.overboardRate().orElseThrow() > 0, id + " 两千局里一次都没落过水");
            });
        }

        @Test
        @DisplayName("没有观测时频率是空，不是 0 —— 0 会把「没上过场」说成「运气极好」")
        void noObservationIsEmptyNotZero() {
            assertTrue(Exposure.NONE.overboardRate().isEmpty());
            assertTrue(Exposure.NONE.thirstRate().isEmpty());
            assertEquals(0.0, new Exposure(0, 5, 0, 5).overboardRate().orElseThrow());
        }

        @Test
        @DisplayName("命中多于机会直接拒绝构造 —— 那是分母漏加了，算出来的频率会很像有信号")
        void hitsCannotExceedChances() {
            assertThrows(IllegalArgumentException.class, () -> new Exposure(3, 2, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new Exposure(0, 0, 1, 0));
            assertThrows(IllegalArgumentException.class, () -> new Exposure(-1, 0, 0, 0));
        }
    }
}
