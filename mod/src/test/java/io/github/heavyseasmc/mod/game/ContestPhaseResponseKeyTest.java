package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.play.Contest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 表态那一句播报用哪一对 lang 键（ADR-0032 #6）。
 *
 * <p>错的键与对的键在日志、计数、包里长得一样，只有人看得出来 —— 所以断言写死键名，而且每个 Kind 各一对：
 * 只断言绝境的话，「所有 Kind 都走绝境那一对」的错误实现照样绿。
 */
class ContestPhaseResponseKeyTest {

    @Test
    @DisplayName("绝境是「不反对」，不是「同意被换座位」：单独一对键")
    void rationHasItsOwnPair() {
        assertEquals("heavyseas.contest.ration_passed", ContestPhase.responseKey(Contest.Kind.RATION, false));
        assertEquals("heavyseas.contest.ration_objected", ContestPhase.responseKey(Contest.Kind.RATION, true));
    }

    @Test
    @DisplayName("换座位与抢夺仍用「同意 / 拒绝」那一对（对照）")
    void swapAndStealKeepTheAgreePair() {
        for (Contest.Kind kind : new Contest.Kind[] {Contest.Kind.SWAP, Contest.Kind.STEAL}) {
            assertEquals("heavyseas.contest.agreed", ContestPhase.responseKey(kind, false), kind.name());
            assertEquals("heavyseas.contest.refused", ContestPhase.responseKey(kind, true), kind.name());
        }
        assertNotEquals(ContestPhase.responseKey(Contest.Kind.RATION, false),
                ContestPhase.responseKey(Contest.Kind.SWAP, false));
    }

    @Test
    @DisplayName("每个 Kind 都有明确的归属：新增一种时这里要跟着答，不能悄悄落进「同意」那一对")
    void everyKindIsAccountedFor() {
        for (Contest.Kind kind : Contest.Kind.values()) {
            String passed = ContestPhase.responseKey(kind, false);
            String objected = ContestPhase.responseKey(kind, true);
            assertNotEquals(passed, objected, kind.name());
        }
        assertEquals(3, Contest.Kind.values().length,
                "新增了一种 Kind：先决定它的表态文案该用哪一对键，再改这个数");
    }
}
