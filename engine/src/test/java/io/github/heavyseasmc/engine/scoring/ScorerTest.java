package io.github.heavyseasmc.engine.scoring;

import io.github.heavyseasmc.engine.data.RosterLoader;
import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.model.TreasureKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计分单测。
 *
 * <p>夹具的覆盖面刻意做满：
 * 四个特殊身份、珠宝三档、遗产，以及「憎恨看事件、厌世看状态」那对关键用例；
 * 另有一组证明计分表来自数据、引擎里没有第二份。
 */
class ScorerTest {

    private static final CharacterId JEWELER = CharacterId.of("jeweler");
    private static final CharacterId COLLECTOR = CharacterId.of("collector");
    private static final CharacterId CAPTAIN = CharacterId.of("captain");
    private static final CharacterId MATE = CharacterId.of("mate");
    private static final CharacterId SAILOR = CharacterId.of("sailor");
    private static final CharacterId KID = CharacterId.of("kid");

    /**
     * 标准计分表：现金每张 1 分、美术品 2 / 3 / 3、珠宝套组 1 / 4 / 8。
     *
     * <p>❗这是<b>测试夹具</b>，不是第二个真相源：引擎运行时只读 {@code data/roster} 里那一份，
     * 写在这里是为了让下面每个期望值都能对着规则算清楚。数据那份与规则是否一致，由 {@code RealDataTest} 核对。
     */
    private static final TreasureScoring STANDARD =
            new TreasureScoring(1, List.of(2, 3, 3), List.of(1, 4, 8));

    /** 6 人阵容。座位取 1·2·3·4·6·8——摘掉陪酒女(5)与医生(7)后的嵌套序列。 */
    private static Roster sixPersonRoster() {
        return new Roster(List.of(
                new Survivor(JEWELER, 1, 4, 8, "base",
                        new Ability.ScoreMultiplier(TreasureKind.JEWELRY, 2,
                                Ability.ScoreMultiplier.Scope.SET_TOTAL)),
                new Survivor(COLLECTOR, 2, 5, 7, "base",
                        new Ability.ScoreMultiplier(TreasureKind.FINE_ART, 2,
                                Ability.ScoreMultiplier.Scope.FACE_VALUE)),
                new Survivor(CAPTAIN, 3, 7, 5, "base",
                        new Ability.ScoreMultiplier(TreasureKind.CASH, 2,
                                Ability.ScoreMultiplier.Scope.FACE_VALUE)),
                new Survivor(MATE, 4, 8, 4, "base", new Ability.None()),
                new Survivor(SAILOR, 6, 6, 6, "base",
                        new Ability.OverboardImmune(true, List.of("bait_bucket"))),
                new Survivor(KID, 8, 3, 9, "base",
                        new Ability.StealUncontested("hand", false, true))
        ));
    }

    /**
     * 一局终局快照。爱与恨各自是一个<b>置换</b>（这是硬性正确性约束：
     * 每个角色恰好被一个人爱、恰好被一个人恨）。
     *
     * <pre>
     * 角色       爱          恨          身份            终局
     * jeweler  captain     sailor      普通            活
     * collector  collector   jeweler     自恋者          活
     * captain    jeweler     captain     厌世者          活
     * mate       mate        mate        自恋+厌世       活
     * sailor     kid         kid         矛盾者          落水死亡，已移出
     * kid        sailor      collector   普通            死在艇上
     * </pre>
     */
    private static Map<CharacterId, FinalState> fixture() {
        Map<CharacterId, FinalState> m = new LinkedHashMap<>();
        m.put(JEWELER, new FinalState(true, true, Treasures.jewelry(3), CAPTAIN, SAILOR));
        m.put(COLLECTOR, new FinalState(true, true, Treasures.fineArt(5), COLLECTOR, JEWELER));
        m.put(CAPTAIN, new FinalState(true, true, Treasures.cash(2), JEWELER, CAPTAIN));
        m.put(MATE, new FinalState(true, true, Treasures.NONE, MATE, MATE));
        // 落水死亡被移出：身上 5 张现金随他一起退出游戏
        m.put(SAILOR, new FinalState(false, false, Treasures.cash(5), KID, KID));
        // 死在艇上：1 张现金作为遗产照算
        m.put(KID, new FinalState(false, true, Treasures.cash(1), SAILOR, COLLECTOR));
        return m;
    }

    private static ScoreSheet scoreOf(CharacterId who) {
        return Scorer.score(sixPersonRoster(), fixture(), who, STANDARD);
    }

    @Nested
    @DisplayName("四项独立")
    class FourComponents {

        @Test
        @DisplayName("普通角色：四项各自成立，互不影响")
        void ordinary() {
            ScoreSheet s = scoreOf(JEWELER);
            assertEquals(8, s.selfSurvival(), "活着 → 自己的生存分 8");
            assertEquals(16, s.treasure(), "珠宝 3 张 = 套组 8 分，珠宝商加倍 → 16");
            assertEquals(5, s.loved(), "所爱的船长活着 → 船长的生存分 5");
            assertEquals(6, s.hated(), "所恨的水手死了 → 水手的体型分 6");
            assertEquals(35, s.total());
        }

        @Test
        @DisplayName("死在艇上：财宝作为遗产照算")
        void corpseKeepsTreasure() {
            ScoreSheet s = scoreOf(KID);
            assertEquals(0, s.selfSurvival(), "死了，没有存活分");
            assertEquals(1, s.treasure(), "尸体还在艇上，1 张现金照算");
            assertEquals(1, s.total());
        }

        @Test
        @DisplayName("落水死亡被移出：财宝随人退出游戏")
        void drownedLosesTreasure() {
            ScoreSheet s = scoreOf(SAILOR);
            assertEquals(0, s.treasure(), "不在艇上，身上 5 张现金不计分");
        }
    }

    @Nested
    @DisplayName("特殊身份：只有厌世者需要特例，其余从模型涌现")
    class SpecialRoles {

        @Test
        @DisplayName("自恋者：生存分算两次，且不需要特例代码")
        void narcissist() {
            ScoreSheet s = scoreOf(COLLECTOR);
            assertEquals(7, s.selfSurvival(), "第一项：自己活着");
            assertEquals(7, s.loved(), "第三项：所爱者（就是自己）活着");
            assertEquals(14, s.selfSurvival() + s.loved(), "两项相加自然 ×2");
            assertEquals(10, s.treasure(), "美术品面值 5，收藏家加倍 → 10");
            assertEquals(24, s.total());
        }

        @Test
        @DisplayName("厌世者：自身生死不计分，改为清点艇上死者")
        void misanthrope() {
            ScoreSheet s = scoreOf(CAPTAIN);
            assertEquals(0, s.selfSurvival(), "厌世者自身生死不计分，哪怕他活着");
            assertEquals(4, s.treasure(), "现金 2 张，船长加倍 → 4");
            assertEquals(8, s.loved(), "所爱的珠宝商活着 → 珠宝商的生存分 8");
            assertEquals(3, s.hated(), "艇上的死者只有小孩 → 体型 3；水手已被移出，不算");
            assertEquals(15, s.total());
        }

        @Test
        @DisplayName("自恋兼厌世：生存分只算一次——这是涌现，不是特例")
        void narcissistAndMisanthrope() {
            ScoreSheet s = scoreOf(MATE);
            assertEquals(0, s.selfSurvival(), "厌世把第一项清零");
            assertEquals(4, s.loved(), "第三项照付：所爱者（自己）活着");
            assertEquals(4, s.selfSurvival() + s.loved(), "净结果是生存分一次，而非两次");
            assertEquals(3, s.hated(), "艇上死者：小孩");
            assertEquals(7, s.total());
        }

        @Test
        @DisplayName("矛盾者：目标死亡取体型分——同样是涌现")
        void ambivalent() {
            ScoreSheet s = scoreOf(SAILOR);
            assertEquals(0, s.loved(), "所爱的小孩死了 → 第三项 0");
            assertEquals(3, s.hated(), "所恨的小孩死了 → 第四项给体型分 3");
            assertEquals(3, s.total(), "「二者择一」是两项自动互斥的结果，没有写特例");
        }
    }

    @Nested
    @DisplayName("憎恨看事件，厌世看状态，两者不共用谓词")
    class EventVersusState {

        /**
         * 本测试是这组规则的要害。水手<b>落水死亡且已被移出游戏</b>：
         * 恨他的珠宝商照拿体型分（事件已发生），而厌世的船长不把他计入（他不在艇上）。
         *
         * <p>若两处共用同一个谓词，这两条断言必有一条会挂——而且只在有人落水死亡的
         * 对局里才挂，平时完全看不出来。
         */
        @Test
        @DisplayName("同一个落水死者：恨他的人照拿分，厌世者不计入")
        void drownedCountsForHateButNotForMisanthrope() {
            Map<CharacterId, FinalState> states = fixture();
            assertEquals(false, states.get(SAILOR).onBoat(), "前提：水手已被移出");
            assertEquals(true, states.get(SAILOR).died(), "前提：水手确实死了");

            assertEquals(6, scoreOf(JEWELER).hated(),
                    "憎恨卡是事件触发：他死了就给分，不问尸体在不在艇上");
            assertEquals(3, scoreOf(CAPTAIN).hated(),
                    "厌世者是状态计数：只有小孩(3)在艇上；水手已移出，不计入");
        }
    }

    @Nested
    @DisplayName("珠宝套组")
    class JewelrySet {

        private int treasureFor(int jewelry, CharacterId who) {
            Map<CharacterId, FinalState> m = fixture();
            FinalState old = m.get(who);
            m.put(who, new FinalState(old.alive(), old.onBoat(),
                    Treasures.jewelry(jewelry), old.love(), old.hate()));
            return Scorer.score(sixPersonRoster(), m, who, STANDARD).treasure();
        }

        @Test
        @DisplayName("常人：1 / 4 / 8")
        void plain() {
            assertEquals(0, treasureFor(0, CAPTAIN));
            assertEquals(1, treasureFor(1, CAPTAIN));
            assertEquals(4, treasureFor(2, CAPTAIN));
            assertEquals(8, treasureFor(3, CAPTAIN));
        }

        @Test
        @DisplayName("珠宝商：先查套组表再翻倍 → 2 / 8 / 16")
        void doubled() {
            assertEquals(2, treasureFor(1, JEWELER));
            assertEquals(8, treasureFor(2, JEWELER));
            assertEquals(16, treasureFor(3, JEWELER),
                    "加倍作用于套组总分而非单张面值——集齐三张是整局最大的财宝爆点");
        }

        @Test
        @DisplayName("超过珠宝的全局张数就是终局状态错了，要当场炸")
        void overflow() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> treasureFor(4, CAPTAIN));
            assertTrue(e.getMessage().contains("3 张"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("计分表来自数据，引擎里没有第二份")
    class DataDrivenTables {

        @Test
        @DisplayName("❗换一张珠宝套组表，分数跟着变")
        void jewelryTableDrivesScore() {
            TreasureScoring steeper = new TreasureScoring(1, List.of(2, 3, 3), List.of(1, 5, 9));
            assertEquals(16, Scorer.score(sixPersonRoster(), fixture(), JEWELER, STANDARD).treasure());
            assertEquals(18, Scorer.score(sixPersonRoster(), fixture(), JEWELER, steeper).treasure(),
                    "珠宝商 3 张珠宝：新表 9 分，加倍 18");
        }

        @Test
        @DisplayName("现金分值也从表里取")
        void cashValueDrivesScore() {
            TreasureScoring richer = new TreasureScoring(3, List.of(2, 3, 3), List.of(1, 4, 8));
            assertEquals(12, Scorer.score(sixPersonRoster(), fixture(), CAPTAIN, richer).treasure(),
                    "船长 2 张现金 × 每张 3 分 × 加倍");
        }

        @Test
        @DisplayName("❗端到端：数据里的珠宝表改成 1/5/9，读进来算出的分就跟着变")
        void editedDataChangesScore() {
            String roster = """
                    {
                      "schema_version": 1,
                      "characters": [
                        {"id": "jeweler", "seat": 1, "size": 4, "survival": 8, "expansion": "base",
                         "ability": {"kind": "none"}}
                      ],
                      "presets": {"1": ["jeweler"]},
                      "treasure_scoring": {
                        "cash": {"kind": "face_value", "value": 1},
                        "fine_art": {"kind": "face_value", "values": [2, 3, 3]},
                        "jewelry": {"kind": "set_total", "table": {"1": 1, "2": 5, "3": 9}}
                      }
                    }""";
            TreasureScoring edited =
                    RosterLoader.load("test:roster/edited", new StringReader(roster)).treasureScoring();
            assertEquals(18, Scorer.score(sixPersonRoster(), fixture(), JEWELER, edited).treasure());
        }

        @Test
        @DisplayName("美术品面值合计超过全部美术品之和，是终局状态错了，当场炸")
        void fineArtOverTotalRejected() {
            Map<CharacterId, FinalState> m = fixture();
            FinalState old = m.get(COLLECTOR);
            m.put(COLLECTOR, new FinalState(old.alive(), old.onBoat(),
                    Treasures.fineArt(9), old.love(), old.hate()));
            assertThrows(IllegalArgumentException.class,
                    () -> Scorer.score(sixPersonRoster(), m, COLLECTOR, STANDARD));
        }

        @Test
        @DisplayName("计分表本身：空表或负数当场拒绝")
        void malformedTableRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new TreasureScoring(1, List.of(2, 3, 3), List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> new TreasureScoring(-1, List.of(2, 3, 3), List.of(1, 4, 8)));
            assertThrows(IllegalArgumentException.class,
                    () -> new TreasureScoring(1, List.of(2, -3, 3), List.of(1, 4, 8)));
        }
    }

    @Nested
    @DisplayName("非法状态当场拒绝")
    class Invariants {

        @Test
        @DisplayName("活着却不在艇上是不可能的")
        void aliveMustBeOnBoat() {
            assertThrows(IllegalArgumentException.class,
                    () -> new FinalState(true, false, Treasures.NONE, KID, KID));
        }

        @Test
        @DisplayName("座位重复的阵容要当场炸")
        void duplicateSeat() {
            assertThrows(IllegalArgumentException.class, () -> new Roster(List.of(
                    new Survivor(JEWELER, 1, 4, 8, "base", new Ability.None()),
                    new Survivor(KID, 1, 3, 9, "base", new Ability.None())
            )));
        }
    }

    @Test
    @DisplayName("全员计分：六个人一次算完")
    void scoreAll() {
        Map<CharacterId, ScoreSheet> all = Scorer.scoreAll(sixPersonRoster(), fixture(), STANDARD);
        assertEquals(6, all.size());
        assertEquals(35, all.get(JEWELER).total());
        assertEquals(24, all.get(COLLECTOR).total());
        assertEquals(15, all.get(CAPTAIN).total());
        assertEquals(7, all.get(MATE).total());
        assertEquals(3, all.get(SAILOR).total());
        assertEquals(1, all.get(KID).total());
    }
}
