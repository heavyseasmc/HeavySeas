package io.github.heavyseasmc.engine.thirst;

import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 口渴模型单测。
 *
 * <p>覆盖面按「这条错了会怎样」选，不按行数选：
 * 幂等（战斗一回合只算一次）、三源叠加上限、遮蔽优先于喝水、
 * 少喝水会转成伤害、以及陪酒女最后结算的次序。
 */
class ThirstTest {

    private static final CharacterId JEWELER = CharacterId.of("jeweler");
    private static final CharacterId MATE = CharacterId.of("mate");
    private static final CharacterId HOSTESS = CharacterId.of("hostess");
    private static final CharacterId KID = CharacterId.of("kid");

    private static Ability shareLast() {
        return new Ability.ShareEffect(List.of("water", "rum"), true, true,
                Map.of("water", true, "rum", false));
    }

    /** 四人阵容，陪酒女刻意<b>不</b>排在末位，否则「最后结算」测了等于没测。 */
    private static Roster rosterWithHostessInMiddle() {
        return new Roster(List.of(
                new Survivor(JEWELER, 1, 4, 8, "base", new Ability.None()),
                new Survivor(HOSTESS, 5, 3, 9, "base", shareLast()),
                new Survivor(MATE, 4, 8, 4, "base", new Ability.None()),
                new Survivor(KID, 8, 3, 9, "base", new Ability.None())));
    }

    @Nested
    @DisplayName("来源集合")
    class Tally {

        @Test
        @DisplayName("战斗一回合最多算一次 —— 靠集合的幂等，不靠调用方自觉")
        void combatIsIdempotent() {
            ThirstTally t = ThirstTally.none()
                    .with(ThirstSource.FOUGHT)
                    .with(ThirstSource.FOUGHT)
                    .with(ThirstSource.FOUGHT);
            assertEquals(1, t.count());
        }

        @Test
        @DisplayName("重复加入返回同一个实例，不产生垃圾")
        void repeatedAddReturnsSelf() {
            ThirstTally once = ThirstTally.of(ThirstSource.ROWED);
            assertSame(once, once.with(ThirstSource.ROWED));
        }

        @Test
        @DisplayName("来源可以叠加，且上限就是来源种类数")
        void allSourcesStack() {
            ThirstTally three = ThirstTally.of(
                    ThirstSource.ROWED, ThirstSource.FOUGHT, ThirstSource.NAMED);
            assertEquals(3, three.count(), "规则基线 §9.5 写着的那三个来源");

            // ❗上限跟着枚举走，不写死数字。2026-09-16 物资建模加了第四个来源（喝酒）时，
            //   这条断言正是按预期自动跟着变的那一条。
            ThirstTally all = ThirstTally.none();
            for (ThirstSource source : ThirstSource.values()) {
                all = all.with(source);
            }
            assertEquals(ThirstSource.values().length, all.count(),
                    "单回合上限应当恒等于来源种类数");
        }

        @Test
        @DisplayName("不可变：拿到的集合改不动，也不会被后续 with 影响")
        void isImmutable() {
            ThirstTally base = ThirstTally.of(ThirstSource.ROWED);
            assertThrows(UnsupportedOperationException.class,
                    () -> base.sources().add(ThirstSource.NAMED));
            ThirstTally grown = base.with(ThirstSource.NAMED);
            assertEquals(1, base.count(), "原值不应被 with 改动");
            assertEquals(2, grown.count());
        }

        @Test
        @DisplayName("空集合就是没渴")
        void emptyIsNoThirst() {
            assertTrue(ThirstTally.none().isEmpty());
            assertEquals(0, ThirstTally.none().count());
            assertFalse(ThirstTally.none().has(ThirstSource.ROWED));
        }
    }

    @Nested
    @DisplayName("结算")
    class Resolve {

        @Test
        @DisplayName("没渴就什么也不发生")
        void noThirst() {
            ThirstOutcome o = ThirstResolver.resolve(ThirstTally.none(), 0, 0);
            assertEquals(new ThirstOutcome(0, 0, 0, 0), o);
            assertTrue(o.unharmed());
        }

        @Test
        @DisplayName("每一次口渴弃 1 张水就不受伤")
        void waterCancelsOneEach() {
            ThirstTally t = ThirstTally.of(ThirstSource.ROWED, ThirstSource.NAMED);
            ThirstOutcome o = ThirstResolver.resolve(t, 0, 2);
            assertEquals(0, o.damage());
            assertEquals(2, o.watersSpent());
        }

        @Test
        @DisplayName("没水就每次 1 点伤害 —— 三源齐发是 3 点")
        void noWaterMeansDamage() {
            ThirstTally t = ThirstTally.of(
                    ThirstSource.ROWED, ThirstSource.FOUGHT, ThirstSource.NAMED);
            assertEquals(3, ThirstResolver.resolve(t, 0, 0).damage());
        }

        @Test
        @DisplayName("水不够时，差额转成伤害而不是报错")
        void partialWater() {
            ThirstTally t = ThirstTally.of(
                    ThirstSource.ROWED, ThirstSource.FOUGHT, ThirstSource.NAMED);
            ThirstOutcome o = ThirstResolver.resolve(t, 0, 1);
            assertEquals(1, o.watersSpent());
            assertEquals(2, o.damage());
        }

        @Test
        @DisplayName("遮蔽先抵，抵完剩下的才轮到水 —— 不给调用方留决策点")
        void coverAppliesBeforeWater() {
            ThirstTally t = ThirstTally.of(ThirstSource.ROWED, ThirstSource.FOUGHT);
            ThirstOutcome o = ThirstResolver.resolveSpendingUpTo(t, 1, 5);
            assertEquals(1, o.cancelledByCover());
            assertEquals(1, o.watersSpent(), "遮蔽抵掉一次后只需再喝一张，不该白喝两张");
            assertEquals(0, o.damage());
        }

        @Test
        @DisplayName("遮蔽多于口渴次数时不会倒欠")
        void coverDoesNotOvershoot() {
            ThirstOutcome o = ThirstResolver.resolve(ThirstTally.of(ThirstSource.NAMED), 3, 0);
            assertEquals(1, o.cancelledByCover());
            assertEquals(0, o.damage());
        }

        @Test
        @DisplayName("弃水多于还需化解的次数 —— 必须由结算本身拒绝，不能静默销毁牌")
        void overspendingWaterIsRejected() {
            ThirstTally t = ThirstTally.of(ThirstSource.ROWED);

            // ❗只断言「抛了 IllegalArgumentException」是不够的：把 resolve 里的守卫删掉，
            //   ThirstOutcome 的配平不变量照样会抛同一个类型，于是这条断言仍然绿 ——
            //   它证明不了「结算拒绝了超量弃水」，只证明了「某处炸了」。2026-09-14 变异测试实证。
            //   所以这里核对消息：报错必须指出是弃水超量，且带上两个数，调用方才知道怎么改。
            for (int[] call : new int[][]{{0, 2}, {1, 1}}) {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                        () -> ThirstResolver.resolve(t, call[0], call[1]));
                assertTrue(e.getMessage().contains("弃水张数多于还需化解的口渴次数"),
                        "应当由 resolve 的守卫拒绝并说明原因，实际消息：" + e.getMessage());
                assertTrue(e.getMessage().contains("spent=" + call[1]),
                        "报错要带上实际弃水张数，实际消息：" + e.getMessage());
            }
        }

        @Test
        @DisplayName("负数一律拒绝")
        void negativesRejected() {
            ThirstTally t = ThirstTally.of(ThirstSource.ROWED);
            assertThrows(IllegalArgumentException.class, () -> ThirstResolver.resolve(t, -1, 0));
            assertThrows(IllegalArgumentException.class, () -> ThirstResolver.resolve(t, 0, -1));
            assertThrows(IllegalArgumentException.class,
                    () -> ThirstResolver.resolveSpendingUpTo(t, 0, -1));
        }

        @Test
        @DisplayName("三个去向必须恰好加总成来源数 —— 构造非法结果要炸")
        void outcomeMustBalance() {
            assertThrows(IllegalArgumentException.class, () -> new ThirstOutcome(3, 1, 1, 0));
            assertThrows(IllegalArgumentException.class, () -> new ThirstOutcome(1, 0, 0, 2));
        }

        @Test
        @DisplayName("有多少水喝多少：水管够时不受伤，水不够时只差多少伤多少")
        void spendUpToPolicy() {
            ThirstTally t = ThirstTally.of(
                    ThirstSource.ROWED, ThirstSource.FOUGHT, ThirstSource.NAMED);
            assertEquals(0, ThirstResolver.resolveSpendingUpTo(t, 0, 9).damage());
            assertEquals(2, ThirstResolver.resolveSpendingUpTo(t, 0, 1).damage());
            assertEquals(0, ThirstResolver.resolveSpendingUpTo(t, 1, 2).damage());
        }
    }

    @Nested
    @DisplayName("结算次序")
    class Order {

        @Test
        @DisplayName("带「最后结算」标记的角色排到末位，其余保持阵容顺序")
        void hostessResolvesLast() {
            List<CharacterId> order = ThirstResolver.resolutionOrder(rosterWithHostessInMiddle())
                    .stream().map(Survivor::id).toList();
            assertEquals(List.of(JEWELER, MATE, KID, HOSTESS), order);
        }

        @Test
        @DisplayName("次序是全序且可复现 —— 模拟器跑几千局不能出现不可复现的差异")
        void orderIsDeterministic() {
            Roster r = rosterWithHostessInMiddle();
            assertEquals(ThirstResolver.resolutionOrder(r), ThirstResolver.resolutionOrder(r));
        }

        @Test
        @DisplayName("没有任何角色带标记时，次序原样不变")
        void withoutMarkerOrderIsUnchanged() {
            Roster plain = new Roster(List.of(
                    new Survivor(JEWELER, 1, 4, 8, "base", new Ability.None()),
                    new Survivor(MATE, 4, 8, 4, "base", new Ability.None())));
            assertEquals(List.of(JEWELER, MATE),
                    ThirstResolver.resolutionOrder(plain).stream().map(Survivor::id).toList());
        }

        @Test
        @DisplayName("标记只认 ShareEffect 上的那一位，不认技能种类")
        void markerIsTheFlagNotTheAbilityKind() {
            Survivor sharesButNotLast = new Survivor(HOSTESS, 5, 3, 9, "base",
                    new Ability.ShareEffect(List.of("water"), true, false, Map.of()));
            assertFalse(ThirstResolver.resolvesLast(sharesButNotLast));
            assertTrue(ThirstResolver.resolvesLast(
                    new Survivor(HOSTESS, 5, 3, 9, "base", shareLast())));
        }
    }
}
