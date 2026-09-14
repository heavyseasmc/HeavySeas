package io.github.heavyseasmc.engine.state;

import io.github.heavyseasmc.engine.model.CharacterId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 战斗子状态单测。 */
class FightTest {

    private static final CharacterId JEWELER = CharacterId.of("jeweler");   // 体型 4
    private static final CharacterId MATE = CharacterId.of("mate");         // 体型 8
    private static final CharacterId HOSTESS = CharacterId.of("hostess");   // 体型 3
    private static final CharacterId KID = CharacterId.of("kid");           // 体型 3
    private static final CharacterId CAPTAIN = CharacterId.of("captain");   // 体型 7

    private static final Map<CharacterId, Integer> SIZES = Map.of(
            JEWELER, 4, MATE, 8, HOSTESS, 3, KID, 3, CAPTAIN, 7);

    private static final ToIntFunction<CharacterId> SIZE = id -> SIZES.get(id);

    @Nested
    @DisplayName("判定")
    class Resolution {

        @Test
        @DisplayName("体型总和高者胜")
        void biggerSideWins() {
            Fight.Outcome o = Fight.between(KID, MATE).resolve(SIZE);   // 3 vs 8
            assertEquals(Fight.Side.DEFEND, o.winner());
            assertEquals(3, o.attackPower());
            assertEquals(8, o.defendPower());
        }

        @Test
        @DisplayName("❗平手防守方胜 —— 不是重打，也不是双方都输")
        void tieGoesToDefender() {
            Fight.Outcome o = Fight.between(KID, HOSTESS).resolve(SIZE);  // 3 vs 3
            assertTrue(o.wasTie());
            assertEquals(Fight.Side.DEFEND, o.winner());
            assertFalse(o.attackerGetsWhatTheyWanted());
        }

        @Test
        @DisplayName("助拳计入总和")
        void helpersCount() {
            Fight f = Fight.between(KID, MATE).join(CAPTAIN, Fight.Side.ATTACK);  // 3+7 vs 8
            Fight.Outcome o = f.resolve(SIZE);
            assertEquals(10, o.attackPower());
            assertEquals(Fight.Side.ATTACK, o.winner());
        }

        @Test
        @DisplayName("武器可叠加，同一人多次打出是累加不是取最大")
        void weaponsStack() {
            Fight f = Fight.between(KID, HOSTESS).arm(KID, 1).arm(KID, 3);
            assertEquals(4, f.weaponPowerOf(KID));
            assertEquals(7, f.resolve(SIZE).attackPower());
        }

        @Test
        @DisplayName("❗战斗力按满体型算 —— 受伤不降低战斗力")
        void damageDoesNotReduceStrength() {
            // 本类根本收不到伤害：resolve 只拿 sizeOf。这里用「传剩余血量会得出不同结果」
            // 来把这条钉死 —— 如果哪天有人把 sizeOf 换成剩余血量，胜负会翻转。
            Fight f = Fight.between(MATE, CAPTAIN);                 // 满体型 8 vs 7 → 进攻胜
            assertEquals(Fight.Side.ATTACK, f.resolve(SIZE).winner());

            ToIntFunction<CharacterId> remainingHp = id -> SIZES.get(id) - (id.equals(MATE) ? 5 : 0);
            assertEquals(Fight.Side.DEFEND, f.resolve(remainingHp).winner(),
                    "换成剩余血量胜负就翻转 —— 所以 sizeOf 必须传满体型，村规不是规则");
        }
    }

    @Nested
    @DisplayName("结果")
    class Results {

        @Test
        @DisplayName("败方每一个参战者各受 1 点伤害 —— 不是只伤主将，也不是平摊")
        void everyLoserTakesOne() {
            Fight f = Fight.between(MATE, KID)
                    .join(HOSTESS, Fight.Side.DEFEND)
                    .join(JEWELER, Fight.Side.DEFEND);      // 8 vs 3+3+4=10
            Fight.Outcome o = f.resolve(SIZE);
            assertEquals(Fight.Side.DEFEND, o.winner());
            assertEquals(Set.of(MATE), o.losers());
            assertEquals(1, o.damagePerLoser());

            Fight g = Fight.between(MATE, KID).join(CAPTAIN, Fight.Side.ATTACK);  // 15 vs 3
            Fight.Outcome og = g.resolve(SIZE);
            assertEquals(Set.of(KID), og.losers());
        }

        @Test
        @DisplayName("进攻方胜才拿到最初索求的东西")
        void attackerGetsOnlyOnWin() {
            assertTrue(Fight.between(MATE, KID).resolve(SIZE).attackerGetsWhatTheyWanted());
            assertFalse(Fight.between(KID, MATE).resolve(SIZE).attackerGetsWhatTheyWanted());
        }

        @Test
        @DisplayName("败方是整个阵营，助拳者一起受伤")
        void losingSideIsWholeSide() {
            Fight f = Fight.between(KID, MATE)
                    .join(HOSTESS, Fight.Side.ATTACK);       // 3+3=6 vs 8
            assertEquals(Set.of(KID, HOSTESS), f.resolve(SIZE).losers());
        }
    }

    @Nested
    @DisplayName("参战者")
    class Combatants {

        @Test
        @DisplayName("战斗标记发给全部参战者，含助拳者")
        void markersGoToEveryone() {
            Fight f = Fight.between(KID, MATE)
                    .join(CAPTAIN, Fight.Side.ATTACK)
                    .join(HOSTESS, Fight.Side.DEFEND);
            assertEquals(Set.of(KID, CAPTAIN, MATE, HOSTESS), f.combatants());
        }

        @Test
        @DisplayName("❗加入后不得反悔 —— 重复加入要拒绝，且不能换边")
        void noRejoinNoSwitchSide() {
            Fight f = Fight.between(KID, MATE).join(CAPTAIN, Fight.Side.ATTACK);
            assertThrows(IllegalArgumentException.class,
                    () -> f.join(CAPTAIN, Fight.Side.DEFEND), "不能换边");
            assertThrows(IllegalArgumentException.class,
                    () -> f.join(CAPTAIN, Fight.Side.ATTACK), "同一边也不能重复加入");
            assertThrows(IllegalArgumentException.class,
                    () -> f.join(KID, Fight.Side.DEFEND), "主将也不行");
        }

        @Test
        @DisplayName("给没参战的人打武器要拒绝")
        void cannotArmOutsider() {
            Fight f = Fight.between(KID, MATE);
            assertThrows(IllegalArgumentException.class, () -> f.arm(CAPTAIN, 3));
            assertThrows(IllegalArgumentException.class, () -> f.arm(KID, 0));
        }

        @Test
        @DisplayName("不能和自己打")
        void cannotFightSelf() {
            assertThrows(IllegalArgumentException.class, () -> Fight.between(KID, KID));
        }

        @Test
        @DisplayName("查询未参战者的阵营要报错，不能静默返回一边")
        void sideOfOutsiderThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> Fight.between(KID, MATE).sideOf(CAPTAIN));
        }

        @Test
        @DisplayName("不可变：助拳与武器都返回新值，原值不动")
        void immutability() {
            Fight base = Fight.between(KID, MATE);
            Fight joined = base.join(CAPTAIN, Fight.Side.ATTACK);
            assertEquals(Set.of(KID, MATE), base.combatants());
            assertEquals(3, joined.combatants().size());
            assertEquals(0, base.weaponPowerOf(KID));
            assertEquals(2, base.arm(KID, 2).weaponPowerOf(KID));
            assertEquals(0, base.weaponPowerOf(KID), "原值不该被 arm 改动");
        }
    }
}
