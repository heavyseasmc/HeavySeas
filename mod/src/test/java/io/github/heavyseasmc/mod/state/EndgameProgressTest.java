package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.scoring.ScoreSheet;
import io.github.heavyseasmc.engine.state.GameState;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EndgameProgressTest {

    private static final List<CharacterId> ORDER = List.of(
            CharacterId.of("captain"), CharacterId.of("mate"));

    @Test
    void arrivalAcceptsEightMotionStepsThenEntersHateReveal() {
        EndgameProgress progress = progress(EndgameProgress.Stage.ARRIVAL, EndgameProgress.ARRIVAL_STEPS);

        EndgameProgress reveal = progress.nextStage();

        assertEquals(EndgameProgress.Stage.HATE, reveal.stage());
        assertEquals(0, reveal.flipped());
    }

    @Test
    void revealAcceptsEveryCardAndRejectsOnlyOverflow() {
        progress(EndgameProgress.Stage.HATE, ORDER.size());

        assertThrows(IllegalArgumentException.class,
                () -> progress(EndgameProgress.Stage.HATE, ORDER.size() + 1));
    }

    @Test
    void everyTiedTopScorerIsAWinner() {
        CharacterId doctor = CharacterId.of("doctor");
        List<CharacterId> order = List.of(ORDER.get(0), ORDER.get(1), doctor);
        Map<CharacterId, ScoreSheet> scores = new LinkedHashMap<>();
        scores.put(order.get(0), new ScoreSheet(1, 0, 0, 0));
        scores.put(order.get(1), new ScoreSheet(5, 0, 0, 0));
        scores.put(order.get(2), new ScoreSheet(5, 0, 0, 0));
        EndgameProgress progress = new EndgameProgress(GameState.Outcome.LANDED, 3, 3, order,
                EndgameProgress.Stage.HATE, 0, scores);

        assertFalse(progress.isWinner(order.get(0)));
        assertTrue(progress.isWinner(order.get(1)));
        assertTrue(progress.isWinner(order.get(2)));
    }

    private static EndgameProgress progress(EndgameProgress.Stage stage, int step) {
        Map<CharacterId, ScoreSheet> scores = new LinkedHashMap<>();
        ORDER.forEach(id -> scores.put(id, new ScoreSheet(0, 0, 0, 0)));
        return new EndgameProgress(GameState.Outcome.LANDED, 3, 2, ORDER,
                stage, step, scores);
    }
}
