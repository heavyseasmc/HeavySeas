package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.*;
import io.github.heavyseasmc.engine.navigation.*;
import io.github.heavyseasmc.engine.play.*;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.state.GameComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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

    /** 到点那一下做了哪几件事（按发生的先后）。 */
    private static List<String> fire(StandInDesignation.Cue cue, GameComponent component) {
        List<String> done = new ArrayList<>();
        StandInDesignation.fire(cue, component, () -> done.add("finish"), () -> done.add("pass"), () -> done.add("declare"));
        return done;
    }

    // 直接运行到点回调，检查副作用及顺序；不以 Cue 自己返回的布尔值代替回调结果。

    @Test
    @DisplayName("对照：窗没换、行动者还该出手 —— 到点收起指定模式，然后宣布出手")
    void bothGatesOpenDeclares() {
        var c = fixture();
        var cue = cue(c);
        assertEquals(List.of("finish", "declare"), fire(cue, c), "两道闸都该放行时没有照常出手");
    }

    enum ObsoleteWindow { CLOSED, REOPENED, ENDED, ANOTHER_GAME }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ObsoleteWindow.class)
    @DisplayName("指定窗取消、同人重开、对局结束或换局：旧回调不收窗、不出手；新窗仍可正常出手")
    void obsoleteWindowHasNoSideEffects(ObsoleteWindow change) {
        var c = fixture();
        var old = cue(c);
        StandInDesignation.Cue next = null;
        switch (change) {
            case CLOSED -> c.clearDesignation();
            case REOPENED -> {
                c.clearDesignation();
                next = cue(c);
            }
            case ENDED -> c.end();
            case ANOTHER_GAME -> {
                c = fixture();
                next = cue(c); // 行动者、类型和序号相同，只有 Session 不同。
            }
        }
        assertEquals(List.of(), fire(old, c), "旧回调产生了收窗或出手副作用：" + change);
        if (next != null) {
            assertEquals(List.of("finish", "declare"), fire(next, c), "旧回调检查后，新窗仍应正常出手");
        }
    }

    @Test
    @DisplayName("到点时窗还在、但行动者已经不该出手：只收起指定模式，不宣布出手")
    void actorNoLongerReadyOnlyFinishes() {
        var c = fixture();
        var cue = cue(c);
        c.requireSession().markActed(cue.actor());
        assertTrue(cue.sameWindow(c), "前提：窗还是那一扇 —— 拦下来的只能是第二道闸");
        assertEquals(List.of("finish"), fire(cue, c),
                "行动者已经行动过了，延迟回调还宣布出手：第二道闸（Cue.actorReady）没拦住，同一个人一天里动两次手");
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
