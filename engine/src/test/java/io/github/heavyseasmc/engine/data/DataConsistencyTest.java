package io.github.heavyseasmc.engine.data;

import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.scoring.TreasureScoring;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 角色表 × 物资表的跨文件核对。
 *
 * <h2>每条都配一个「只改一边」的反例</h2>
 * 这类问题的全部难点在于：<b>两份文件单看都合法</b>。所以每条判据都要证明
 * 「只动一边时它真的会红」，否则它与没有判据等价 —— 本仓库已经实证过八次假绿。
 */
class DataConsistencyTest {

    private static RosterData roster() {
        return LocalData.roster();
    }

    private static Provisions provisions() {
        return LocalData.provisions();
    }

    /** 把某个角色换成改过技能的版本，其余原样。 */
    private static RosterData withCharacter(String id, UnaryOperator<Survivor> edit) {
        List<Survivor> next = new ArrayList<>();
        for (Survivor s : roster().characters()) {
            next.add(s.id().value().equals(id) ? edit.apply(s) : s);
        }
        return new RosterData(next, roster().presets(), roster().treasureScoring());
    }

    /** 把某张物资换成改过效果的版本，其余原样。 */
    private static Provisions withProvision(String id, UnaryOperator<Provision> edit) {
        List<Provision> next = new ArrayList<>();
        for (Provision p : provisions().all()) {
            next.add(p.id().equals(id) ? edit.apply(p) : p);
        }
        return new Provisions(next);
    }

    @Test
    @DisplayName("真实的两份数据本来就对得上")
    void realDataAgrees() {
        assertEquals(List.of(), DataConsistency.check(roster(), provisions()),
                "落库的数据应当一致");
    }

    @Test
    @DisplayName("❗只改诱饵那一边：它不再写穿透落水免伤，水手却还说挡不住它")
    void baitStopsClaimingToPierce() {
        Provisions tamed = withProvision("bait_bucket", p -> new Provision(p.id(), p.category(), p.count(),
                new ProvisionEffect.DamageInWater(1, false, true, List.of("life_preserver"))));
        List<String> bad = DataConsistency.check(roster(), tamed);
        assertEquals(1, bad.size(), bad.toString());
        assertTrue(bad.get(0).contains("sailor"), bad.toString());
        assertTrue(bad.get(0).contains("bait_bucket"), bad.toString());
    }

    @Test
    @DisplayName("❗只改水手那一边：他不再说挡不住诱饵，诱饵却还写着穿透")
    void sailorStopsAcknowledgingBait() {
        RosterData tough = withCharacter("sailor", s -> new Survivor(s.id(), s.seat(), s.size(),
                s.survival(), s.expansion(), new Ability.OverboardImmune(true, List.of())));
        List<String> bad = DataConsistency.check(tough, provisions());
        assertEquals(1, bad.size(), bad.toString());
        assertTrue(bad.get(0).contains("bait_bucket"), bad.toString());
        assertTrue(bad.get(0).contains("not_protected_from"), bad.toString());
    }

    @Test
    @DisplayName("技能点着一个不存在的物资 —— 写错一个字母的表现是那个技能永远不触发")
    void abilityPointsAtNothing() {
        RosterData typo = withCharacter("doctor", s -> new Survivor(s.id(), s.seat(), s.size(),
                s.survival(), s.expansion(), new Ability.NoDiscard("medic_kit", true, true, true)));
        List<String> bad = DataConsistency.check(typo, provisions());
        assertTrue(bad.stream().anyMatch(b -> b.contains("medic_kit")), bad.toString());
    }

    @Test
    @DisplayName("❗同一组数值写在两份文件里：珠宝套组表只改一边就该红")
    void jewelrySetTableMustMatchRoster() {
        Provisions richer = withProvision("jewelry", p -> new Provision(p.id(), p.category(), p.count(),
                new ProvisionEffect.ScoreSet(List.of(1, 4, 9), "jeweler", "set_total")));
        List<String> bad = DataConsistency.check(roster(), richer);
        assertTrue(bad.stream().anyMatch(b -> b.contains("套组表")), bad.toString());
    }

    @Test
    @DisplayName("美术品面值只改一边就该红")
    void fineArtFaceValuesMustMatchRoster() {
        TreasureScoring shifted = new TreasureScoring(roster().treasureScoring().cashFaceValue(),
                List.of(2, 3, 4), roster().treasureScoring().jewelrySetTotals());
        RosterData tweaked = new RosterData(roster().characters(), roster().presets(), shifted);
        List<String> bad = DataConsistency.check(tweaked, provisions());
        assertTrue(bad.stream().anyMatch(b -> b.contains("美术品面值")), bad.toString());
    }

    @Test
    @DisplayName("财宝写着由一个没有加倍技能的人加倍")
    void doubledByWrongCharacter() {
        Provisions wrong = withProvision("cash", p -> new Provision(p.id(), p.category(), p.count(),
                new ProvisionEffect.ScoreFlat(1, "mate")));
        List<String> bad = DataConsistency.check(roster(), wrong);
        assertTrue(bad.stream().anyMatch(b -> b.contains("mate")), bad.toString());
        assertTrue(bad.stream().anyMatch(b -> b.contains("CASH")),
                "反向那条也该红：船长白有一个技能。" + bad);
    }
}
