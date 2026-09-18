package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.scoring.ScoreSheet;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class EndgamePhaseTest {

    @Test
    void revealOrderIsLowestScoreFirstAndKeepsSeatOrderForTies() {
        CharacterId captain = CharacterId.of("captain");
        CharacterId mate = CharacterId.of("mate");
        CharacterId kid = CharacterId.of("kid");
        CharacterId doctor = CharacterId.of("doctor");
        List<CharacterId> seats = List.of(captain, mate, kid, doctor);
        Map<CharacterId, ScoreSheet> scores = new LinkedHashMap<>();
        scores.put(captain, score(10));
        scores.put(mate, score(3));
        scores.put(kid, score(10));
        scores.put(doctor, score(1));

        assertEquals(List.of(doctor, mate, captain, kid), EndgamePhase.revealOrder(seats, scores));
    }

    private static ScoreSheet score(int total) {
        return new ScoreSheet(total, 0, 0, 0);
    }
}
