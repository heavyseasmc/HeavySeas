package io.github.heavyseasmc.engine.play;

import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.data.TestProvisions;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.NavigationDeck;
import io.github.heavyseasmc.engine.navigation.Selector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 物资阶段：最靠船头的清醒角色抽 N 张，留 1 张，其余传给下一位。
 *
 * <p>这一阶段的价值全在<b>信息梯度</b>上：船头看得到全部 N 张，船尾只看得到 1 张。
 * 那不是显示差异，是规则 —— 船头位的价值就建在「他知道后面的人会从哪几张里选」之上。
 * 所以下面每一条都在盯同一件事：<b>每个人看到的张数对不对</b>。
 */
class ProvisionPhaseTest {

    private static final CharacterId A = CharacterId.of("jeweler");
    private static final CharacterId B = CharacterId.of("mate");
    private static final CharacterId C = CharacterId.of("kid");

    /**
     * 三人，座位 1 / 4 / 8。
     *
     * <p>B 的体型故意设成 1：昏迷的条件是「伤害 == 体型」，而一场败仗恰好扣 1 点，
     * 所以一场架就能把他打昏 —— 下面那条「顺序不改道」需要这个。
     */
    private static Roster roster() {
        return new Roster(List.of(
                new Survivor(A, 1, 8, 8, "base", new Ability.None()),
                new Survivor(B, 4, 1, 4, "base", new Ability.None()),
                new Survivor(C, 8, 8, 9, "base", new Ability.None())));
    }

    private static NavigationDeck navDeck() {
        return new NavigationDeck(List.of(
                new NavigationCard("syn_0", 0, new Selector.Nobody(), new Selector.Nobody(), false, false)),
                new Random(1));
    }

    /**
     * 一副固定的物资牌，内容可预期 —— 洗牌用定种子，失败时复现得了。
     *
     * <p>❗效果定义取自真实数据（{@code TestProvisions.counting}），只有张数是这里定的：
     * 手里抄一份效果就会有第二个真相源。
     */
    private static Provisions deck(int waters, int extras) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (waters > 0) {
            counts.put("water", waters);
        }
        if (extras > 0) {
            counts.put("knife", extras);
        }
        return TestProvisions.counting(counts);
    }

    private static Session session(Provisions provisions) {
        return new Session("test", roster(), new Table(navDeck(), provisions, new Random(7)));
    }

    @Test
    @DisplayName("❗信息梯度：船头看 3 张、中间 2 张、船尾 1 张 —— 每传一次少一张")
    void offerShrinksByOneEachPass() {
        Session s = session(deck(3, 3));

        assertEquals(3, s.beginProvision().size(), "船头看得到全部 3 张（= 清醒者数）");
        assertEquals(A, s.provisionHolder().orElseThrow(), "箱子从最靠船头的人开始");

        s.provisionKeep(s.provisionOffer().get(0));
        assertEquals(2, s.provisionOffer().size(), "留 1 张之后传下去的是 2 张");
        assertEquals(B, s.provisionHolder().orElseThrow());

        s.provisionKeep(s.provisionOffer().get(0));
        assertEquals(1, s.provisionOffer().size(), "船尾只看得到 1 张");
        assertEquals(C, s.provisionHolder().orElseThrow());

        s.provisionKeep(s.provisionOffer().get(0));
        assertFalse(s.provisionInProgress(), "三个人都拿过，这一轮结束");
    }

    @Test
    @DisplayName("每人恰好留 1 张，且牌堆正好少 3 张 —— 牌不会凭空多出来或消失")
    void everyoneKeepsExactlyOne() {
        Session s = session(deck(3, 3));
        int before = s.table().provisionsLeft();

        s.beginProvision();
        while (s.provisionInProgress()) {
            s.provisionKeep(s.provisionOffer().get(0));
        }

        assertEquals(1, s.state().stateOf(A).hand().size());
        assertEquals(1, s.state().stateOf(B).hand().size());
        assertEquals(1, s.state().stateOf(C).hand().size());
        assertEquals(before - 3, s.table().provisionsLeft(), "抽了 3 张就该少 3 张");
    }

    @Test
    @DisplayName("❗手牌允许重复：水有 16 张，同一个 id 拿两张是常态")
    void handAllowsDuplicates() {
        Session s = session(deck(6, 0));          // 全是水
        s.beginProvision();
        s.provisionKeep("water");
        // 再开一轮，让同一个人又拿一张水
        s.beginProvision();
        s.provisionKeep("water");
        assertEquals(List.of("water", "water"), s.state().stateOf(A).hand(),
                "两张水都要在手里 —— 用 Set 存手牌的话第二张会被悄悄吞掉");
    }

    @Test
    @DisplayName("❗手牌跨回合留着：回合收尾清的是口渴与行动标记，不是手上的牌")
    void handSurvivesTheTurnBoundary() {
        // 整副都是水：牌堆是洗过的，只有内容一致才谈得上「抽到什么都可预期」。
        Session s = session(deck(6, 0));
        s.beginProvision();
        s.provisionKeep("water");
        s.provisionKeep("water");
        s.provisionKeep("water");
        assertFalse(s.provisionInProgress(), "三个人都留过了，这一轮该结束");

        // 走完一整圈回到物资阶段 —— 回合收尾（endOfTurn）就在这中间。
        s.advancePhase();                 // 物资 → 行动
        s.advancePhase();                 // 行动 → 航海
        s.advancePhase();                 // 航海 → 物资，回合 +1，这一步会清标记
        assertEquals(2, s.state().turn());
        assertEquals(List.of("water"), s.state().stateOf(A).hand(),
                "回合收尾把手牌一起清了的话，玩家每回合都会发现自己两手空空，而对局照常推进");

        s.beginProvision();
        s.provisionKeep("water");
        assertEquals(List.of("water", "water"), s.state().stateOf(A).hand(),
                "第二回合的牌要叠在第一回合那张上面，不是替换它");
    }

    @Test
    @DisplayName("❗留一张不在 offer 里的牌直接抛：静默改成别的，作弊就看不出来了")
    void keepingACardNotOnOfferThrows() {
        Session s = session(deck(3, 0));          // offer 里全是 water
        s.beginProvision();
        assertFalse(s.provisionOffer().contains("rum"), "先确认它真的不在 —— 否则这条什么也没测");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> s.provisionKeep("rum"));
        assertTrue(e.getMessage().contains("rum"), e.getMessage());
        assertEquals(3, s.provisionOffer().size(), "抛了就不该动 offer");
        assertEquals(0, s.state().stateOf(A).hand().size(), "抛了也不该有人拿到牌");
    }

    @Test
    @DisplayName("牌堆不够时有几张抽几张，发光即止 —— 规则是「抽完即止，不洗回」")
    void runsOutMidPass() {
        Session s = session(deck(2, 0));          // 只剩 2 张，却有 3 个人
        assertEquals(2, s.beginProvision().size(), "有几张抽几张，不是抽不满就不发");

        s.provisionKeep("water");
        assertEquals(B, s.provisionHolder().orElseThrow());
        s.provisionKeep("water");

        assertFalse(s.provisionInProgress(), "牌发光了，船尾这一轮什么也没有");
        assertEquals(0, s.state().stateOf(C).hand().size());
        assertEquals(0, s.table().provisionsLeft());
    }

    @Test
    @DisplayName("❗传递顺序在开始时定死：中途有人昏迷，箱子也不改道")
    void chainIsFixedAtStart() {
        Session s = session(deck(3, 3));
        s.beginProvision();
        s.provisionKeep(s.provisionOffer().get(0));      // 船头拿完，轮到 B

        // B 在轮到自己时昏迷：体型 1，输一场架正好扣满
        s.applyFight(io.github.heavyseasmc.engine.state.Fight.between(A, B));
        assertFalse(s.state().consciousBySeat().contains(B), "B 已经不清醒了");

        // 顺序是开始时定的，所以箱子仍然在 B 手上，而不是跳给 C
        assertEquals(B, s.provisionHolder().orElseThrow(),
                "按「当前清醒者」重算的话箱子会在半路改道，而前面的人已经按旧顺序看过牌了");
    }
}
