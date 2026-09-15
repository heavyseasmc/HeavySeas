package io.github.heavyseasmc.engine.data;

import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.engine.model.Provisions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 测试用的物资目录。
 *
 * <h2>两种，用途不同</h2>
 * <ul>
 *   <li>{@link #counting} 拿<b>真实数据里的效果定义</b>，只换张数 —— 给「想控制牌堆构成」的测试用。
 *       效果仍然只有一份真相源，所以数据改了这些测试会跟着动。</li>
 *   <li>{@link #synthetic} 是<b>手写</b>的一副，12 种 kind 一种不少 ——
 *       给模拟器用。它保证随机对局<b>每一种效果都走得到</b>，
 *       哪怕将来真实数据里某一族被拿掉。</li>
 * </ul>
 */
public final class TestProvisions {

    private TestProvisions() {
    }

    /** 真实的整副（18 种 47 张）。 */
    public static Provisions standard() {
        return LocalData.provisions();
    }

    /** 真实的效果定义 + 自己指定的张数。 */
    public static Provisions counting(Map<String, Integer> counts) {
        Provisions real = LocalData.provisions();
        List<Provision> cards = new ArrayList<>();
        counts.forEach((id, count) -> {
            Provision card = real.get(id);
            cards.add(new Provision(card.id(), card.category(), count, card.effect()));
        });
        return new Provisions(cards);
    }

    /** 一张就够的目录：只想要「牌有效果可查」而不想发牌时用（{@code Table} 的两参构造）。 */
    public static Provisions minimal() {
        return counting(Map.of("water", 1));
    }

    /**
     * 只有财宝的一副：发得出牌，但<b>没有一张会改变生死</b>。
     *
     * <p>给「数落海/口渴的分母」那类统计测试用 —— 它们要测的是统计口径，
     * 而水与医疗箱会让人活得更久，把被测的那件事淹掉。
     */
    public static Provisions inert() {
        return counting(Map.of("cash", 6, "jewelry", 3));
    }

    /**
     * 手写的一副，<b>12 种 kind 全覆盖</b>。数值照真实数据，但不读文件。
     *
     * <p>❗它存在的理由不是「省得读文件」，是<b>保证每种效果都被随机对局走到</b>。
     * 用真实牌堆的话，某一族哪天从数据里消失，模拟器会安安静静地不再测它。
     */
    public static Provisions synthetic() {
        List<Provision> cards = List.of(
                new Provision("water", Provision.Category.CONSUMABLE, 16,
                        new ProvisionEffect.PreventThirst(1, "thirst_source",
                                ProvisionEffect.Target.ANY_CHARACTER, true, "thirst_resolution",
                                true, false, false, false, false)),
                new Provision("medical_kit", Provision.Category.CONSUMABLE, 4,
                        new ProvisionEffect.Heal(1, ProvisionEffect.Target.ANY_CHARACTER_INCLUDING_SELF,
                                true, true, List.of("doctor"))),
                new Provision("bait_bucket", Provision.Category.CONSUMABLE, 2,
                        new ProvisionEffect.DamageInWater(1, false, true,
                                List.of(ProvisionEffect.DamageInWater.SWIM_IMMUNITY, "life_preserver"))),
                new Provision("ration", Provision.Category.CONSUMABLE, 1,
                        new ProvisionEffect.HealAll(1, "conscious_only", true, true, true, true)),
                new Provision("rum", Provision.Category.EQUIPMENT, 3,
                        new ProvisionEffect.BuffSize(3, "turn",
                                ProvisionEffect.BuffSize.THIRST_AT_END_OF_TURN, true, true, false)),
                new Provision("compass", Provision.Category.EQUIPMENT, 1,
                        new ProvisionEffect.NavigatorExtraDraw(1, "row_stack", "before_navigator_chooses")),
                new Provision("life_preserver", Provision.Category.EQUIPMENT, 1,
                        new ProvisionEffect.PreventOverboardDamage(true, true, true, true, true)),
                new Provision("parasol", Provision.Category.EQUIPMENT, 1,
                        new ProvisionEffect.PreventThirst(1, "thirst_source",
                                ProvisionEffect.Target.SELF, false, "", false, true, true, true, true)),
                new Provision("flare_gun", Provision.Category.WEAPON, 1,
                        new ProvisionEffect.WeaponOrSpecial(8, true,
                                new ProvisionEffect.WeaponOrSpecial.Signal("draw_and_resolve_gulls", 3,
                                        "gulls", true, "deck_bottom"), true)),
                new Provision("fish_spear", Provision.Category.WEAPON, 1, new ProvisionEffect.Weapon(4)),
                new Provision("knife", Provision.Category.WEAPON, 1, new ProvisionEffect.Weapon(3)),
                new Provision("flail", Provision.Category.WEAPON, 1, new ProvisionEffect.Weapon(2)),
                new Provision("oar", Provision.Category.WEAPON, 2,
                        new ProvisionEffect.WeaponAndRowBonus(1, 1, false, true)),
                new Provision("cash", Provision.Category.TREASURE, 6,
                        new ProvisionEffect.ScoreFlat(1, "captain")),
                new Provision("jewelry", Provision.Category.TREASURE, 3,
                        new ProvisionEffect.ScoreSet(List.of(1, 4, 8), "jeweler", "set_total")),
                new Provision("fine_art_2", Provision.Category.TREASURE, 1,
                        new ProvisionEffect.ScoreFlat(2, "collector")),
                new Provision("fine_art_3a", Provision.Category.TREASURE, 1,
                        new ProvisionEffect.ScoreFlat(3, "collector")),
                new Provision("fine_art_3b", Provision.Category.TREASURE, 1,
                        new ProvisionEffect.ScoreFlat(3, "collector")));
        return new Provisions(cards);
    }
}
