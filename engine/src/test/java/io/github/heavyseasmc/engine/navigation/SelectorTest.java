package io.github.heavyseasmc.engine.navigation;

import io.github.heavyseasmc.engine.model.CharacterId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 航海牌点名与牌面单测。 */
class SelectorTest {

    private static final CharacterId JEWELER = CharacterId.of("jeweler");
    private static final CharacterId MATE = CharacterId.of("mate");
    private static final CharacterId KID = CharacterId.of("kid");
    private static final CharacterId GHOST = CharacterId.of("collector");   // 已出局，不在候选里

    private static final Set<CharacterId> CANDIDATES =
            new LinkedHashSet<>(Set.of(JEWELER, MATE, KID));

    private static final Selector.ConditionResolver NO_CONDITIONS = (c, who) -> false;

    @Test
    @DisplayName("all 点全体候选")
    void everyone() {
        assertEquals(CANDIDATES, new Selector.Everyone().select(CANDIDATES, NO_CONDITIONS));
    }

    @Test
    @DisplayName("none 一个也不点 —— 与「空名单」是两回事")
    void nobody() {
        assertTrue(new Selector.Nobody().select(CANDIDATES, NO_CONDITIONS).isEmpty());
    }

    @Test
    @DisplayName("list 点名")
    void only() {
        assertEquals(Set.of(MATE), new Selector.Only(Set.of(MATE)).select(CANDIDATES, NO_CONDITIONS));
    }

    @Test
    @DisplayName("❗名单里已出局的人取交集，不报错 —— 牌是印死的，场上少人是常态")
    void onlyIntersectsWithCandidates() {
        Selector s = new Selector.Only(Set.of(MATE, GHOST));
        assertEquals(Set.of(MATE), s.select(CANDIDATES, NO_CONDITIONS));
    }

    @Test
    @DisplayName("except 点名单之外的全部")
    void except() {
        assertEquals(Set.of(JEWELER, KID),
                new Selector.Except(Set.of(MATE)).select(CANDIDATES, NO_CONDITIONS));
    }

    @Test
    @DisplayName("except 名单里有已出局的人也不影响结果")
    void exceptIgnoresAbsent() {
        assertEquals(Set.of(JEWELER, KID),
                new Selector.Except(Set.of(MATE, GHOST)).select(CANDIDATES, NO_CONDITIONS));
    }

    @Test
    @DisplayName("conditional 按条件筛，条件名是物资 id 不是角色 id")
    void conditional() {
        Selector.ConditionResolver drankRum = (c, who) -> c.equals("used_rum") && who.equals(KID);
        assertEquals(Set.of(KID),
                new Selector.Conditional("used_rum").select(CANDIDATES, drankRum));
        assertTrue(new Selector.Conditional("used_rum").select(CANDIDATES, NO_CONDITIONS).isEmpty());
    }

    @Test
    @DisplayName("conditional 缺少解析器要报错，不能当成「没人满足」")
    void conditionalNeedsResolver() {
        Selector s = new Selector.Conditional("used_rum");
        assertThrows(NullPointerException.class, () -> s.select(CANDIDATES, null));
    }

    @Test
    @DisplayName("空条件名拒绝")
    void blankConditionRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Selector.Conditional(" "));
    }

    @Test
    @DisplayName("候选集为空时，任何模式都点不到人")
    void emptyCandidates() {
        Set<CharacterId> none = Set.of();
        assertTrue(new Selector.Everyone().select(none, NO_CONDITIONS).isEmpty());
        assertTrue(new Selector.Only(Set.of(MATE)).select(none, NO_CONDITIONS).isEmpty());
        assertTrue(new Selector.Except(Set.of(MATE)).select(none, NO_CONDITIONS).isEmpty());
    }

    @Test
    @DisplayName("选择器不改动传入的候选集")
    void doesNotMutateCandidates() {
        Set<CharacterId> input = new LinkedHashSet<>(CANDIDATES);
        new Selector.Except(Set.of(MATE)).select(input, NO_CONDITIONS);
        new Selector.Only(Set.of(MATE)).select(input, NO_CONDITIONS);
        assertEquals(CANDIDATES, input, "候选集被就地改掉会让同一张牌的两栏互相污染");
    }

    @Test
    @DisplayName("海鸥增减只能是 -1 / 0 / +1")
    void gullRange() {
        Selector none = new Selector.Nobody();
        assertThrows(IllegalArgumentException.class,
                () -> new NavigationCard("x", 2, none, none, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> new NavigationCard("x", -2, none, none, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> new NavigationCard(" ", 0, none, none, false, false));
    }
}
