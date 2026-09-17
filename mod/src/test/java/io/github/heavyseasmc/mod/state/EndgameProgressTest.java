package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.scoring.ScoreSheet;
import io.github.heavyseasmc.engine.state.GameState;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class EndgameProgressTest {

    private static final List<CharacterId> ORDER = List.of(
            CharacterId.of("captain"), CharacterId.of("mate"));

    @Test
    void arrivalAcceptsEightMotionStepsThenEntersHateReveal() {
        EndgameProgress progress = progress(EndgameProgress.Stage.ARRIVAL, 8);

        EndgameProgress reveal = progress.nextStage();

        assertEquals(EndgameProgress.Stage.HATE, reveal.stage());
        assertEquals(0, reveal.flipped());
    }

    @Test
    void revealStillReservesItsLastCard() {
        assertThrows(IllegalArgumentException.class,
                () -> progress(EndgameProgress.Stage.HATE, ORDER.size()));
    }

    private static EndgameProgress progress(EndgameProgress.Stage stage, int step) {
        Map<CharacterId, ScoreSheet> scores = new LinkedHashMap<>();
        ORDER.forEach(id -> scores.put(id, new ScoreSheet(0, 0, 0, 0)));
        return new EndgameProgress(GameState.Outcome.LANDED, 3, 2, ORDER,
                stage, step, false, scores);
    }
}
