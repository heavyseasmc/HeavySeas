package io.github.heavyseasmc.engine.state;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.thirst.ThirstSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 伤害与生死状态单测。 */
class ConditionTest {

    private static final CharacterId KID = CharacterId.of("kid");

    @Nested
    @DisplayName("船上的判定")
    class OnBoat {

        @Test
        @DisplayName("小于体型清醒，等于昏迷，大于死亡")
        void threeBands() {
            assertEquals(Condition.CONSCIOUS, Condition.onBoat(0, 3));
            assertEquals(Condition.CONSCIOUS, Condition.onBoat(2, 3));
            assertEquals(Condition.UNCONSCIOUS, Condition.onBoat(3, 3));
            assertEquals(Condition.DEAD, Condition.onBoat(4, 3));
        }

        @Test
        @DisplayName("大副 8 点才昏迷，小孩 3 点就昏迷 —— 壮的人难杀")
        void sizeIsHitPoints() {
            assertEquals(Condition.CONSCIOUS, Condition.onBoat(7, 8));
            assertEquals(Condition.UNCONSCIOUS, Condition.onBoat(8, 8));
            assertEquals(Condition.UNCONSCIOUS, Condition.onBoat(3, 3));
        }

        @Test
        @DisplayName("非法入参一律拒绝，不静默当成 0")
        void rejectsBadArgs() {
            assertThrows(IllegalArgumentException.class, () -> Condition.onBoat(-1, 3));
            assertThrows(IllegalArgumentException.class, () -> Condition.onBoat(0, 0));
            assertThrows(IllegalArgumentException.class, () -> Condition.inWater(0, 0, false));
        }
    }

    @Nested
    @DisplayName("水里的判定 —— 与船上不同")
    class InWater {

        @Test
        @DisplayName("「伤害 = 体型」在船上是昏迷，在水里没救生圈就是死")
        void equalMeansDeathWithoutPreserver() {
            assertEquals(Condition.UNCONSCIOUS, Condition.onBoat(3, 3));
            assertEquals(Condition.DEAD, Condition.inWater(3, 3, false));
            assertEquals(Condition.UNCONSCIOUS, Condition.inWater(3, 3, true));
        }

        @Test
        @DisplayName("清醒角色在「再受一点就满」时落水且无救生圈 —— 直接死，不经过昏迷")
        void oneShortOfFullDiesAtSea() {
            int size = 3;
            int before = size - 1;                       // 落水前：清醒
            assertEquals(Condition.CONSCIOUS, Condition.onBoat(before, size));
            int after = before + 1;                      // 落水伤害 1 点
            assertEquals(Condition.DEAD, Condition.inWater(after, size, false),
                    "这条只在水里成立，是真特例，不是从船上规则涌现的");
            assertEquals(Condition.UNCONSCIOUS, Condition.onBoat(after, size),
                    "同样的伤害数在船上只是昏迷 —— 对照组，用来证明上一条确实是特例");
        }

        @Test
        @DisplayName("救生圈只在「恰好等于」那一档起作用；超过体型照样死")
        void preserverDoesNotResurrect() {
            assertEquals(Condition.DEAD, Condition.inWater(4, 3, true));
            assertEquals(Condition.CONSCIOUS, Condition.inWater(2, 3, true));
            assertEquals(Condition.CONSCIOUS, Condition.inWater(2, 3, false),
                    "没到体型就没事，有没有救生圈都一样");
        }
    }

    @Nested
    @DisplayName("状态能做什么")
    class Capabilities {

        @Test
        @DisplayName("只有清醒能行动")
        void onlyConsciousActs() {
            assertTrue(Condition.CONSCIOUS.canAct());
            assertFalse(Condition.UNCONSCIOUS.canAct());
            assertFalse(Condition.DEAD.canAct());
        }

        @Test
        @DisplayName("❗昏迷者仍然会口渴 —— 最容易写错的一条")
        void unconsciousStillThirsts() {
            assertTrue(Condition.UNCONSCIOUS.suffersThirst(),
                    "「昏迷 = 什么都不能做」很顺口，但他会继续因口渴掉血，只是不能自己打水");
            assertTrue(Condition.CONSCIOUS.suffersThirst());
            assertFalse(Condition.DEAD.suffersThirst());
        }

        @Test
        @DisplayName("只有清醒者计入物资阶段的人数")
        void onlyConsciousCounts() {
            assertTrue(Condition.CONSCIOUS.countsForProvisioning());
            assertFalse(Condition.UNCONSCIOUS.countsForProvisioning());
            assertFalse(Condition.DEAD.countsForProvisioning());
        }
    }

    @Nested
    @DisplayName("会变的那一半")
    class State {

        @Test
        @DisplayName("受伤累加，0 点原样返回")
        void hurtAccumulates() {
            SurvivorState s = SurvivorState.fresh(KID, 8);
            assertEquals(2, s.hurt(1).hurt(1).damage());
            assertSame(s, s.hurt(0));
            assertThrows(IllegalArgumentException.class, () -> s.hurt(-1));
        }

        @Test
        @DisplayName("医疗箱减 1 点，正好把昏迷者拉回清醒")
        void healRevives() {
            SurvivorState knocked = SurvivorState.fresh(KID, 8).hurt(3);
            assertEquals(Condition.UNCONSCIOUS, Condition.onBoat(knocked.damage(), 3));
            SurvivorState revived = knocked.heal(1);
            assertEquals(Condition.CONSCIOUS, Condition.onBoat(revived.damage(), 3));
        }

        @Test
        @DisplayName("治疗未受伤的人要报错，不能静默当成 0")
        void healingUnhurtIsRejected() {
            SurvivorState s = SurvivorState.fresh(KID, 8);
            assertThrows(IllegalArgumentException.class, () -> s.heal(1));
            assertThrows(IllegalArgumentException.class, () -> s.hurt(1).heal(0));
        }

        @Test
        @DisplayName("口渴标记累积，且同一来源幂等")
        void thirstAccumulates() {
            SurvivorState s = SurvivorState.fresh(KID, 8)
                    .thirstFrom(ThirstSource.ROWED)
                    .thirstFrom(ThirstSource.ROWED)
                    .thirstFrom(ThirstSource.FOUGHT);
            assertEquals(2, s.thirst().count());
            assertSame(s, s.thirstFrom(ThirstSource.FOUGHT));
        }

        @Test
        @DisplayName("回合结束清掉标记与行动位，但不碰伤害 —— 伤害是跨回合累积的")
        void endOfTurnClearsMarkersNotDamage() {
            SurvivorState s = SurvivorState.fresh(KID, 8)
                    .hurt(2)
                    .thirstFrom(ThirstSource.ROWED)
                    .markActed();
            SurvivorState next = s.endOfTurn();
            assertTrue(next.thirst().isEmpty());
            assertFalse(next.actedThisTurn());
            assertEquals(2, next.damage(), "伤害不能被回合清理抹掉");
        }

        @Test
        @DisplayName("已经干净时回合结束返回自身")
        void endOfTurnIsIdempotent() {
            SurvivorState s = SurvivorState.fresh(KID, 8).hurt(1);
            assertSame(s, s.endOfTurn());
        }

        @Test
        @DisplayName("座位可以换，其余不变")
        void seatChanges() {
            SurvivorState s = SurvivorState.fresh(KID, 8).hurt(1);
            SurvivorState moved = s.withSeat(1);
            assertEquals(1, moved.seat());
            assertEquals(1, moved.damage());
            assertSame(s, s.withSeat(8));
        }

        @Test
        @DisplayName("构造时拒绝非法座位与负伤害")
        void rejectsBadConstruction() {
            assertThrows(IllegalArgumentException.class, () -> SurvivorState.fresh(KID, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> SurvivorState.fresh(KID, 1).withDamage(-1));
        }
    }
}
