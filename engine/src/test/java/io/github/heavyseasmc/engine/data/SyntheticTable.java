package io.github.heavyseasmc.engine.data;

import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.Selector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模拟器测试一直用的那一套合成桌面：五人阵容、125 张铺开五种点名模式的航海牌。
 *
 * <p>原先只在 {@code SimulatorTest} 里有一份私有的；席位策略的测试也要用同一套局面（钉死的摘要就是拿它算的），
 * 所以搬到这里共用 —— 复制两份迟早分家。
 */
public final class SyntheticTable {

    public static final CharacterId JEWELER = CharacterId.of("jeweler");
    public static final CharacterId MATE = CharacterId.of("mate");
    public static final CharacterId HOSTESS = CharacterId.of("hostess");
    public static final CharacterId SAILOR = CharacterId.of("sailor");
    public static final CharacterId KID = CharacterId.of("kid");

    private SyntheticTable() {
    }

    /** 珠宝商 · 大副 · 陪酒女 · 水手 · 小孩（与 {@code SimulatorTest} 同一套）。 */
    public static Roster roster() {
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
    public static List<NavigationCard> deck() {
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
}
