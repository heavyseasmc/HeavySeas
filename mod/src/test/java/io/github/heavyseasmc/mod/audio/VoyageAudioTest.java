package io.github.heavyseasmc.mod.audio;

import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.state.GameState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoyageAudioTest {
    @Test
    void inactiveAudienceNeverReceivesEvenItsOwnPrivateCue() {
        for (AudioCue cue : AudioCue.values()) {
            assertFalse(VoyageAudio.eligibleRecipient(cue, false, false), cue.name());
            assertFalse(VoyageAudio.eligibleRecipient(cue, false, true), cue.name());
        }
    }

    @Test
    void publicCuesReachVoyageSpectatorsAsWellAsTheSpeaker() {
        for (AudioCue cue : List.of(AudioCue.FIGHT_START, AudioCue.FIGHT_CLASH, AudioCue.DRINK,
                AudioCue.OVERBOARD, AudioCue.GULL_CALL, AudioCue.GULL_DEPART)) {
            assertTrue(VoyageAudio.eligibleRecipient(cue, true, false), cue.name());
            assertTrue(VoyageAudio.eligibleRecipient(cue, true, true), cue.name());
        }
    }

    @Test
    void thirstIsPrivateEvenWithinTheVoyage() {
        assertTrue(VoyageAudio.eligibleRecipient(AudioCue.THIRST, true, true));
        assertFalse(VoyageAudio.eligibleRecipient(AudioCue.THIRST, true, false));
    }

    @Test
    void speakerUsesCurrentEngineConditionAndConnectionState() {
        CharacterId who = CharacterId.of("speaker");
        GameState healthy = GameState.start(new Roster(List.of(
                new Survivor(who, 1, 4, 5, "base", new Ability.None()))));
        assertEquals("speaker", VoyageAudio.eligibleSpeaker(healthy, who));
        GameState wounded = healthy.withState(who, healthy.stateOf(who).withDamage(3));
        assertEquals("speaker", VoyageAudio.eligibleSpeaker(wounded, who));
        GameState unconscious = healthy.withState(who, healthy.stateOf(who).withDamage(4));
        assertEquals("", VoyageAudio.eligibleSpeaker(unconscious, who));
        GameState dead = healthy.withState(who, healthy.stateOf(who).withDamage(5));
        assertEquals("", VoyageAudio.eligibleSpeaker(dead, who));
        assertEquals("", VoyageAudio.eligibleSpeaker(healthy.withRemoved(who), who));
        assertEquals("", VoyageAudio.eligibleSpeaker(healthy.withOffline(who, true), who));
    }
}