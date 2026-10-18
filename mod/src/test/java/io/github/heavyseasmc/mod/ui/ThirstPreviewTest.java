package io.github.heavyseasmc.mod.ui;

import io.github.heavyseasmc.engine.state.Condition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThirstPreviewTest {

    @Test
    void drinkingNothingCanLeadToUnconsciousnessOrDeath() {
        assertEquals(new ThirstPreview(2, 1, Condition.CONSCIOUS), ThirstPreview.of(3, 4, 2, 1, 0, 0));
        assertEquals(new ThirstPreview(2, 0, Condition.UNCONSCIOUS), ThirstPreview.of(2, 4, 2, 1, 0, 0));
        assertEquals(new ThirstPreview(2, 0, Condition.DEAD), ThirstPreview.of(1, 4, 2, 1, 0, 0));
    }

    @Test
    void waterGivenByOthersChangesTheOutcomeEvenWhenDrinkingNothing() {
        assertEquals(new ThirstPreview(1, 0, Condition.UNCONSCIOUS), ThirstPreview.of(1, 4, 2, 1, 1, 0));
        assertEquals(new ThirstPreview(0, 1, Condition.CONSCIOUS), ThirstPreview.of(1, 4, 2, 1, 2, 0));
    }

    @Test
    void doubleWaterCountsOnlyCompleteGroupsIncludingDonations() {
        assertEquals(new ThirstPreview(2, 0, Condition.DEAD), ThirstPreview.of(1, 4, 2, 2, 1, 0));
        assertEquals(new ThirstPreview(1, 0, Condition.UNCONSCIOUS), ThirstPreview.of(1, 4, 2, 2, 1, 1));
        assertEquals(new ThirstPreview(0, 1, Condition.CONSCIOUS), ThirstPreview.of(1, 4, 2, 2, 1, 3));
    }
}
