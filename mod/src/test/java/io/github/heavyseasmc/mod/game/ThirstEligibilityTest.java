package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.state.Condition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThirstEligibilityTest {

    @Test
    void onlyAConsciousRecipientWithWaterMayCommit() {
        assertTrue(ThirstEligibility.canChoose(Condition.CONSCIOUS, 1));
        assertFalse(ThirstEligibility.canChoose(Condition.CONSCIOUS, 0));
        assertFalse(ThirstEligibility.canChoose(Condition.UNCONSCIOUS, 2));
        assertFalse(ThirstEligibility.canChoose(Condition.DEAD, 2));
    }
}
