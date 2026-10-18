package io.github.heavyseasmc.engine.data;

import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.model.TreasureKind;
import io.github.heavyseasmc.engine.scoring.TreasureScoring;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * <b>跨文件</b>的一致性核对：角色表与物资表互相点名的那些地方，两边说的必须是同一件事。
 *
 * <h2>为什么这类检查只能在这里做</h2>
 * 每份文件单看都合法 —— 与 {@link DataDocument} 那条「三份必须同变体」是同一种性质。
 * 举个真实的例子：诱饵的 {@code bypasses: ["sailor_swim_immunity", …]} 与水手的
 * {@code not_protected_from: ["bait_bucket"]} <b>说的是同一件事的两个方向</b>。
 * 只改一边，两份文件各自照样通过全部校验，而规则会静默地变成另一种
 * —— 鲨鱼突然咬不动水手了，没有任何一行报错。
 *
 * <h2>判据与结论正交</h2>
 * 这里的每一条都拿<b>另一份文件</b>去证，不拿被检查对象自己证自己。
 */
public final class DataConsistency {

    private DataConsistency() {
    }

    /**
     * 核对一遍。
     *
     * @return 问题描述；空列表表示通过
     */
    public static List<String> check(RosterData roster, Provisions provisions) {
        Objects.requireNonNull(roster, "roster");
        Objects.requireNonNull(provisions, "provisions");
        List<Survivor> characters = roster.characters();
        List<String> bad = new ArrayList<>();
        checkAbilityTargets(characters, provisions, bad);
        checkSharkBypass(characters, provisions, bad);
        checkDoubling(characters, provisions, bad);
        checkFaceValues(roster.treasureScoring(), provisions, bad);
        return bad;
    }

    /** 核对并在有问题时抛。 */
    public static void require(String source, RosterData roster, Provisions provisions) {
        List<String> bad = check(roster, provisions);
        if (!bad.isEmpty()) {
            throw DataFormatException.at(source, "角色表 × 物资表",
                    "两份数据互相点名的地方对不上：" + String.join("；", bad));
        }
    }

    /** 角色技能点到的物资 id 必须真的存在。写错一个字母的表现是那个技能永远不触发。 */
    private static void checkAbilityTargets(List<Survivor> characters, Provisions provisions,
                                            List<String> bad) {
        for (Survivor s : characters) {
            if (s.ability() instanceof Ability.NoDiscard noDiscard
                    && !provisions.has(noDiscard.target())) {
                bad.add("%s 的「用后不弃」指着不存在的物资 %s".formatted(s.id().value(), noDiscard.target()));
            }
            if (s.ability() instanceof Ability.ShareEffect share) {
                for (String source : share.sources()) {
                    if (!provisions.has(source)) {
                        bad.add("%s 的「蹭效果」指着不存在的物资 %s".formatted(s.id().value(), source));
                    }
                }
                for (String key : share.stacking().keySet()) {
                    if (!share.sources().contains(key)) {
                        bad.add("%s 的「蹭效果」给 %s 写了叠加规则，却没把它列进来源"
                                .formatted(s.id().value(), key));
                    }
                }
            }
        }
    }

    /**
     * 鲨鱼穿透：诱饵与水手两份数据说的必须是同一件事。
     *
     * <ul>
     *   <li>诱饵的 {@code bypasses} 里每一项，要么是真的物资 id（救生圈），
     *       要么是那个技能标记 {@code sailor_swim_immunity}；</li>
     *   <li>它若写着穿透落水免伤，就必须有一个带该免伤技能的角色把这张牌列进 {@code not_protected_from}；</li>
     *   <li>反过来，角色 {@code not_protected_from} 里的每一项都必须是真的物资，
     *       而且那张牌自己也得承认它穿透落水免伤。</li>
     * </ul>
     */
    private static void checkSharkBypass(List<Survivor> characters, Provisions provisions,
                                         List<String> bad) {
        for (Provision card : provisions.all()) {
            if (!(card.effect() instanceof ProvisionEffect.DamageInWater bait)) {
                continue;
            }
            for (String bypass : bait.bypasses()) {
                if (bypass.equals(ProvisionEffect.DamageInWater.SWIM_IMMUNITY) || provisions.has(bypass)) {
                    continue;
                }
                bad.add("%s 写着穿透 %s，而那既不是物资 id，也不是已知的技能标记 %s"
                        .formatted(card.id(), bypass, ProvisionEffect.DamageInWater.SWIM_IMMUNITY));
            }
            if (!bait.bypassesSwimImmunity()) {
                continue;
            }
            boolean acknowledged = false;
            for (Survivor s : characters) {
                if (s.ability() instanceof Ability.OverboardImmune immune
                        && immune.notProtectedFrom().contains(card.id())) {
                    acknowledged = true;
                }
            }
            if (!acknowledged) {
                bad.add("%s 写着穿透落水免伤，却没有任何带落水免伤技能的角色把它列进 not_protected_from"
                        .formatted(card.id()));
            }
        }
        for (Survivor s : characters) {
            if (!(s.ability() instanceof Ability.OverboardImmune immune)) {
                continue;
            }
            for (String cardId : immune.notProtectedFrom()) {
                if (!provisions.has(cardId)) {
                    bad.add("%s 的落水免伤写着挡不住 %s，而没有这张物资".formatted(s.id().value(), cardId));
                    continue;
                }
                ProvisionEffect effect = provisions.get(cardId).effect();
                boolean pierces = effect instanceof ProvisionEffect.DamageInWater water
                        && water.bypassesSwimImmunity();
                if (!pierces) {
                    bad.add("%s 的落水免伤写着挡不住 %s，而那张牌自己没写穿透落水免伤"
                            .formatted(s.id().value(), cardId));
                }
            }
        }
    }

    /**
     * 财宝加倍：物资上的 {@code doubled_by} 与角色的加倍技能必须对上。
     *
     * <p>对不上的表现是「船长的现金没有翻倍」—— 而两份文件各自都合法。
     */
    private static void checkDoubling(List<Survivor> characters, Provisions provisions, List<String> bad) {
        Set<TreasureKind> claimed = EnumSet.noneOf(TreasureKind.class);
        for (Provision card : provisions.all()) {
            String owner = doubledBy(card);
            if (owner == null) {
                continue;
            }
            Survivor who = null;
            for (Survivor s : characters) {
                if (s.id().value().equals(owner)) {
                    who = s;
                }
            }
            if (who == null) {
                bad.add("%s 写着由 %s 加倍，而角色表里没有这个角色".formatted(card.id(), owner));
                continue;
            }
            if (!(who.ability() instanceof Ability.ScoreMultiplier mult)) {
                bad.add("%s 写着由 %s 加倍，而那个角色根本没有加倍技能".formatted(card.id(), owner));
                continue;
            }
            claimed.add(mult.target());
            // 套组计分的只可能是珠宝；按面值计分的只可能是现金或美术品。
            // ❗物资那一份分不开现金与美术品（同为 score_flat），分得开的是角色那一份 ——
            //   所以这里只核对「大类对不对得上」，谁是现金由 doubled_by 指的那个人的技能决定。
            boolean isSet = card.effect() instanceof ProvisionEffect.ScoreSet;
            if (isSet && mult.target() != TreasureKind.JEWELRY) {
                bad.add("%s 是套组计分（珠宝那一族），却写着由加倍 %s 的 %s 加倍"
                        .formatted(card.id(), mult.target(), owner));
            }
            if (!isSet && mult.target() == TreasureKind.JEWELRY) {
                bad.add("%s 按面值计分，却写着由加倍珠宝的 %s 加倍".formatted(card.id(), owner));
            }
        }
        // 反向：角色表里每一种加倍技能都得真的有牌可加。少一族的表现是那个角色白有一个技能。
        for (Survivor s : characters) {
            if (s.ability() instanceof Ability.ScoreMultiplier mult && !claimed.contains(mult.target())) {
                bad.add("%s 会加倍 %s，而没有任何一张物资写着由他加倍"
                        .formatted(s.id().value(), mult.target()));
            }
        }
    }

    /**
     * 面值：{@code data/roster} 的 {@code treasure_scoring} 与 {@code data/provisions} 的
     * {@code points} / {@code table} 说的是同一组数字。
     *
     * <p>❗这是本文件里最值钱的一条：<b>同一个数值写在两份文件里</b>，
     * 而计分只读角色那一份 —— 物资那一份改了不会有任何反应，也不会报错。
     * 「珠宝 3 张 8 分」改成 9 分而计分照旧给 8，正是这种形状。
     */
    private static void checkFaceValues(TreasureScoring scoring, Provisions provisions, List<String> bad) {
        List<Integer> fineArt = new ArrayList<>();
        for (Provision card : provisions.all()) {
            if (card.effect() instanceof ProvisionEffect.ScoreSet set
                    && !set.setTotals().equals(scoring.jewelrySetTotals())) {
                bad.add("%s 的套组表 %s 与角色表里的 %s 不一致"
                        .formatted(card.id(), set.setTotals(), scoring.jewelrySetTotals()));
            }
            if (!(card.effect() instanceof ProvisionEffect.ScoreFlat flat)) {
                continue;
            }
            if (flat.points() == scoring.cashFaceValue() && card.count() > 1) {
                continue;             // 现金：同一个面值多张。按面值分不开时，张数是仅有的区分
            }
            for (int i = 0; i < card.count(); i++) {
                fineArt.add(flat.points());
            }
        }
        List<Integer> expected = new ArrayList<>(scoring.fineArtFaceValues());
        List<Integer> actual = new ArrayList<>(fineArt);
        expected.sort(null);
        actual.sort(null);
        if (!expected.equals(actual)) {
            bad.add("美术品面值：角色表写着 %s，物资表数出来是 %s".formatted(expected, actual));
        }
    }

    private static String doubledBy(Provision card) {
        if (card.effect() instanceof ProvisionEffect.ScoreFlat flat) {
            return flat.doubledBy();
        }
        if (card.effect() instanceof ProvisionEffect.ScoreSet set) {
            return set.doubledBy();
        }
        return null;
    }

}
