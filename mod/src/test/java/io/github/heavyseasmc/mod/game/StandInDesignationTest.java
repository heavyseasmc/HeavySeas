package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.*;
import io.github.heavyseasmc.engine.navigation.*;
import io.github.heavyseasmc.engine.play.*;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.state.GameComponent;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class StandInDesignationTest {
    private static final CharacterId A = CharacterId.of("captain");
    private static final CharacterId B = CharacterId.of("kid");

    private static GameComponent fixture() {
        Roster roster = new Roster(List.of(
                new Survivor(A, 1, 7, 5, "base", new Ability.None()),
                new Survivor(B, 2, 3, 9, "base", new Ability.StealUncontested("hand", false, true))));
        Session session = new Session("designation", roster, new Table(new NavigationDeck(List.of(
                new NavigationCard("calm", 0, new Selector.Nobody(), new Selector.Nobody(), false, false)), new Random(1)),
                new Provisions(List.of(new Provision("coin", Provision.Category.TREASURE, 8,
                        new ProvisionEffect.ScoreFlat(1, "")))), new Random(2)));
        while (session.state().phase() != Phase.ACTION) session.advancePhase();
        GameComponent component = new GameComponent(null);
        component.begin(session, Map.of(A, new GameComponent.Occupant(null, "dummy"),
                B, new GameComponent.Occupant(UUID.randomUUID(), "human")));
        return component;
    }

    private static StandInDesignation.Cue cue(GameComponent component) {
        var s = component.requireSession();
        var actor = s.nextActor().orElseThrow();
        var target = actor.equals(A) ? B : A;
        component.beginDesignation(actor, Contest.Kind.SWAP, 20_000);
        return new StandInDesignation.Cue(s, s.state().turn(), actor, Contest.Kind.SWAP, target,
                component.designationSerial());
    }

    @Test
    void visibleAnnouncementHasABeatEvenInFastMode() {
        assertEquals(1_500L, StandInDesignation.delay(true, false, false));
        assertEquals(300L, StandInDesignation.delay(true, false, true), "fast designation must remain visible");
        assertEquals(0L, StandInDesignation.delay(false, false, false));
        assertEquals(0L, StandInDesignation.delay(true, true, false));
    }

    @Test
    void quietAppliesOnlyToUncontestedStealingNotSeatChanges() {
        var s = fixture().requireSession();
        assertTrue(StandInDesignation.quiet(s, B, Contest.Kind.STEAL));
        assertFalse(StandInDesignation.quiet(s, B, Contest.Kind.SWAP), "child swapping seats must announce");
        assertFalse(StandInDesignation.quiet(s, A, Contest.Kind.STEAL));
    }

    @Test
    void cancellationAndSameActorReopeningInvalidateTheOldCue() {
        var c = fixture();
        var old = cue(c);
        assertTrue(old.sameWindow(c));
        assertTrue(old.actorReady());
        c.clearDesignation();
        assertFalse(old.sameWindow(c));
        var next = cue(c);
        assertFalse(old.sameWindow(c), "reopening must invalidate the old designation cue");
        assertTrue(next.sameWindow(c));
    }

    @Test
    void endingGameOrMovingToAnotherActorMakesDelayedWorkObsolete() {
        var c = fixture();
        var old = cue(c);
        c.requireSession().markActed(old.actor());
        assertFalse(old.actorReady());
        c.end();
        assertFalse(old.sameWindow(c));
        assertFalse(old.sameWindow(fixture()));
    }

    @Test
    void bothDecisionRoutesUseTheCueAndRenderingClearsThePose() throws Exception {
        Path main = Path.of("src/main/java/io/github/heavyseasmc/mod");
        for (String file : List.of("StandInPlay.java", "StandInMinds.java")) {
            String source = Files.readString(main.resolve("game").resolve(file)).replaceAll("(?s)/\\*.*?\\*/", "");
            assertTrue(source.contains("StandInDesignation.begin("), file + " must announce before declaring");
            assertFalse(source.contains("ContestPhase.declare("), file + " bypassed designation");
        }
        String renderer = Files.readString(Path.of("src/client/java/io/github/heavyseasmc/mod/client/StandInRenderer.java"));
        assertTrue(renderer.contains("entity.isDesignating() ? BipedEntityModel.ArmPose.BLOCK : BipedEntityModel.ArmPose.EMPTY"));
        String phase = Files.readString(main.resolve("game/DesignationPhase.java"));
        assertTrue(phase.contains("StandInBodies.setDesignating(world, actor, false)"));
    }
}
