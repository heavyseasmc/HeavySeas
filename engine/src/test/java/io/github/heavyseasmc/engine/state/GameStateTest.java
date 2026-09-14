package io.github.heavyseasmc.engine.state;

import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.thirst.ThirstSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 阶段与整局状态单测。 */
class GameStateTest {

    private static final CharacterId JEWELER = CharacterId.of("jeweler");   // 1 号位, 4/8
    private static final CharacterId MATE = CharacterId.of("mate");         // 4 号位, 8/4
    private static final CharacterId HOSTESS = CharacterId.of("hostess");   // 5 号位, 3/9
    private static final CharacterId KID = CharacterId.of("kid");           // 8 号位, 3/9

    private static Roster roster() {
        return new Roster(List.of(
                new Survivor(JEWELER, 1, 4, 8, "base", new Ability.None()),
                new Survivor(MATE, 4, 8, 4, "base", new Ability.None()),
                new Survivor(HOSTESS, 5, 3, 9, "base", new Ability.None()),
                new Survivor(KID, 8, 3, 9, "base", new Ability.None())));
    }

    private static GameState knockOut(GameState g, CharacterId id) {
        return g.withState(id, g.stateOf(id).hurt(g.roster().get(id).size()));
    }

    private static GameState kill(GameState g, CharacterId id) {
        return g.withState(id, g.stateOf(id).hurt(g.roster().get(id).size() + 1));
    }

    @Nested
    @DisplayName("阶段")
    class Phases {

        @Test
        @DisplayName("三阶段循环，航海之后回到物资")
        void cycle() {
            assertEquals(Phase.ACTION, Phase.PROVISION.next());
            assertEquals(Phase.NAVIGATION, Phase.ACTION.next());
            assertEquals(Phase.PROVISION, Phase.NAVIGATION.next());
        }

        @Test
        @DisplayName("只有航海阶段结束才算走完一个回合")
        void onlyNavigationEndsTurn() {
            assertFalse(Phase.PROVISION.endsTurn());
            assertFalse(Phase.ACTION.endsTurn());
            assertTrue(Phase.NAVIGATION.endsTurn());
        }

        @Test
        @DisplayName("只有行动阶段且不在战斗中才能自由交易")
        void tradeWindow() {
            assertTrue(Phase.ACTION.allowsFreeTrade(false));
            assertFalse(Phase.ACTION.allowsFreeTrade(true), "战斗结束前任何卡不得易手");
            assertFalse(Phase.PROVISION.allowsFreeTrade(false));
            assertFalse(Phase.NAVIGATION.allowsFreeTrade(false));
        }

        @Test
        @DisplayName("走完一整圈，回合数 +1，且标记被清掉")
        void fullTurnClearsMarkers() {
            GameState g = GameState.start(roster());
            g = g.withState(KID, g.stateOf(KID).thirstFrom(ThirstSource.ROWED).markActed());
            assertEquals(1, g.turn());

            g = g.advancePhase().advancePhase();      // 物资 → 行动 → 航海
            assertEquals(Phase.NAVIGATION, g.phase());
            assertEquals(1, g.turn(), "还没走完航海阶段，回合数不该变");
            assertEquals(1, g.stateOf(KID).thirst().count(), "标记要到航海阶段结束才清");

            g = g.advancePhase();                      // 航海 → 物资，回合 +1
            assertEquals(Phase.PROVISION, g.phase());
            assertEquals(2, g.turn());
            assertTrue(g.stateOf(KID).thirst().isEmpty());
            assertFalse(g.stateOf(KID).actedThisTurn());
        }

        @Test
        @DisplayName("伤害跨回合累积，不被回合清理抹掉")
        void damagePersistsAcrossTurns() {
            GameState g = GameState.start(roster());
            g = g.withState(KID, g.stateOf(KID).hurt(1));
            g = g.advancePhase().advancePhase().advancePhase();
            assertEquals(1, g.stateOf(KID).damage(), "口渴是跨天累积的，伤害当然也是");
        }
    }

    @Nested
    @DisplayName("座位与次序")
    class Seating {

        @Test
        @DisplayName("按座位升序，船头在前")
        void seatOrder() {
            assertEquals(List.of(JEWELER, MATE, HOSTESS, KID), GameState.start(roster()).bySeat());
        }

        @Test
        @DisplayName("下一个行动者 = 最靠船头且未行动的清醒角色")
        void nextActorSkipsActed() {
            GameState g = GameState.start(roster());
            assertEquals(Optional.of(JEWELER), g.nextActor());
            g = g.withState(JEWELER, g.stateOf(JEWELER).markActed());
            assertEquals(Optional.of(MATE), g.nextActor());
        }

        @Test
        @DisplayName("昏迷与死亡都不参与行动顺序")
        void nextActorSkipsDownedAndDead() {
            GameState g = GameState.start(roster());
            g = knockOut(g, JEWELER);
            assertEquals(Condition.UNCONSCIOUS, g.conditionOf(JEWELER));
            assertEquals(Optional.of(MATE), g.nextActor());
            g = kill(g, MATE);
            assertEquals(Optional.of(HOSTESS), g.nextActor());
        }

        @Test
        @DisplayName("换座位会改变后续行动顺序 —— 新版规则")
        void swapChangesOrder() {
            GameState g = GameState.start(roster());
            g = g.withState(KID, g.stateOf(KID).withSeat(0 + 2));
            assertEquals(List.of(JEWELER, KID, MATE, HOSTESS), g.bySeat());
            assertEquals(Optional.of(JEWELER), g.nextActor());
            g = g.withState(JEWELER, g.stateOf(JEWELER).markActed());
            assertEquals(Optional.of(KID), g.nextActor(), "换到 2 号位后小孩排在大副前面");
        }

        @Test
        @DisplayName("舵手 = 最靠船尾的清醒角色；船尾昏迷则顺次往船头找")
        void helmsmanIsAftmostConscious() {
            GameState g = GameState.start(roster());
            assertEquals(Optional.of(KID), g.helmsman());
            g = knockOut(g, KID);
            assertEquals(Optional.of(HOSTESS), g.helmsman());
            g = kill(g, HOSTESS);
            assertEquals(Optional.of(MATE), g.helmsman());
        }

        @Test
        @DisplayName("❗无人清醒不是死锁：没有行动者、没有舵手，但游戏还在继续")
        void nobodyConsciousIsLegalNotDeadlock() {
            GameState g = GameState.start(roster());
            for (CharacterId id : List.of(JEWELER, MATE, HOSTESS, KID)) {
                g = knockOut(g, id);
            }
            assertTrue(g.nextActor().isEmpty());
            assertTrue(g.helmsman().isEmpty());
            assertFalse(g.isOver(), "全员昏迷照样要继续抽航海牌，不是终局");
            assertEquals(Phase.ACTION, g.advancePhase().phase(), "阶段仍然推得动");
        }
    }

    @Nested
    @DisplayName("终局")
    class Ending {

        @Test
        @DisplayName("第 4 只海鸥靠岸获救")
        void fourGullsLand() {
            GameState g = GameState.start(roster());
            assertFalse(g.withGulls(3).isOver());
            assertEquals(Optional.of(GameState.Outcome.LANDED), g.withGulls(4).outcome());
        }

        @Test
        @DisplayName("海鸥可以被扣掉，且不会掉到负数")
        void gullsCanDecrease() {
            GameState g = GameState.start(roster()).withGulls(2).withGulls(-1);
            assertEquals(1, g.gulls());
            assertEquals(0, g.withGulls(-5).gulls(), "下限钳到 0");
        }

        @Test
        @DisplayName("全员死亡是终局；❗只剩一人存活不是")
        void allDeadEndsButOneSurvivorDoesNot() {
            GameState g = GameState.start(roster());
            g = kill(g, JEWELER);
            g = kill(g, MATE);
            g = kill(g, HOSTESS);
            assertFalse(g.isOver(), "只剩一人照样继续 —— 写错会让模拟器提前收束");
            g = kill(g, KID);
            assertEquals(Optional.of(GameState.Outcome.ALL_DEAD), g.outcome());
        }

        @Test
        @DisplayName("全员昏迷不是全员死亡")
        void unconsciousIsNotDead() {
            GameState g = GameState.start(roster());
            for (CharacterId id : List.of(JEWELER, MATE, HOSTESS, KID)) {
                g = knockOut(g, id);
            }
            assertTrue(g.outcome().isEmpty());
        }

        @Test
        @DisplayName("终局之后不得再推进阶段")
        void noAdvanceAfterEnd() {
            GameState over = GameState.start(roster()).withGulls(4);
            assertThrows(IllegalStateException.class, over::advancePhase);
        }
    }

    @Nested
    @DisplayName("完整性")
    class Integrity {

        @Test
        @DisplayName("空阵容拒绝开局")
        void emptyRosterRejected() {
            assertThrows(IllegalArgumentException.class, () -> GameState.start(new Roster(List.of())));
        }

        @Test
        @DisplayName("查询不存在的角色要报错")
        void unknownCharacter() {
            GameState g = GameState.start(roster());
            assertThrows(IllegalArgumentException.class, () -> g.stateOf(CharacterId.of("nobody")));
            assertThrows(IllegalArgumentException.class, () -> g.conditionOf(CharacterId.of("nobody")));
        }

        @Test
        @DisplayName("写入时键与状态的 id 必须一致 —— 不一致会静默换掉一个人")
        void mismatchedKeyRejected() {
            GameState g = GameState.start(roster());
            assertThrows(IllegalArgumentException.class,
                    () -> g.withState(KID, g.stateOf(MATE)));
        }

        @Test
        @DisplayName("推进不改动原值 —— 不可变")
        void immutability() {
            GameState g = GameState.start(roster());
            GameState next = g.advancePhase();
            assertEquals(Phase.PROVISION, g.phase());
            assertEquals(Phase.ACTION, next.phase());
        }
    }
}
