package io.github.heavyseasmc.engine.scoring;

import io.github.heavyseasmc.engine.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ScorerExplanationTest {
    private static final CharacterId SELF = CharacterId.of("mate"), LOVED = CharacterId.of("kid"), OTHER = CharacterId.of("captain");
    private static final Roster ROSTER = new Roster(List.of(
            new Survivor(SELF, 1, 8, 4, "base", new Ability.None()),
            new Survivor(LOVED, 2, 3, 9, "base", new Ability.None()),
            new Survivor(OTHER, 3, 7, 5, "base", new Ability.None())));
    private static final TreasureScoring SCORING = new TreasureScoring(1, List.of(2), List.of(1, 4, 8));
    private static Map<CharacterId, FinalState> states(CharacterId love, CharacterId hate, boolean lovedOnBoat) {
        return Map.of(SELF, new FinalState(true, true, Treasures.cash(2), love, hate),
                LOVED, new FinalState(false, lovedOnBoat, Treasures.NONE, SELF, OTHER),
                OTHER, new FinalState(false, true, Treasures.NONE, SELF, LOVED));
    }
    @Test void selfLoveAndSelfHateRemainIndependent() {
        var both = Scorer.explain(ROSTER, states(SELF, SELF, true), SELF, SCORING);
        assertEquals(new ScoreSheet(0, 2, 4, 10), both.score());
        assertEquals(0, both.goal().selfSurvival());
        assertEquals(4, both.goal().lovedSurvival());
        assertEquals(List.of(new Scorer.Contribution(LOVED, 3), new Scorer.Contribution(OTHER, 7)), both.corpses());
        assertEquals(new ScoreSheet(4, 2, 4, 3), Scorer.explain(ROSTER, states(SELF, LOVED, true), SELF, SCORING).score());
    }
    @Test void selfHateExcludesLovedAndRemovedBodies() {
        var lovesDead = Scorer.explain(ROSTER, states(LOVED, SELF, true), SELF, SCORING);
        assertEquals(new ScoreSheet(0, 2, 0, 7), lovesDead.score());
        assertEquals(List.of(new Scorer.Contribution(OTHER, 7)), lovesDead.corpses());
        var removed = Scorer.explain(ROSTER, states(SELF, SELF, false), SELF, SCORING);
        assertEquals(new ScoreSheet(0, 2, 4, 7), removed.score());
        assertEquals(List.of(new Scorer.Contribution(OTHER, 7)), removed.corpses());
    }
    @Test void ordinaryHateStillCountsAnOverboardDeathAndSameTargetUsesItsActualState() {
        var explanation = Scorer.explain(ROSTER, states(LOVED, LOVED, false), SELF, SCORING);
        assertTrue(explanation.hatedDead()); assertFalse(explanation.lovedAlive());
        assertEquals(new ScoreSheet(4, 2, 0, 3), explanation.score());
    }
}
