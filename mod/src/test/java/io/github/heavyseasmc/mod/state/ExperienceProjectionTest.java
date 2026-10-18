package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.engine.model.*;
import io.github.heavyseasmc.engine.navigation.*;
import io.github.heavyseasmc.engine.play.*;
import io.github.heavyseasmc.engine.scoring.ScoreSheet;
import io.github.heavyseasmc.engine.state.GameState;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import io.github.heavyseasmc.engine.scoring.TreasureScoring;
import io.github.heavyseasmc.mod.game.ContestPhase;
import io.github.heavyseasmc.mod.game.EndgamePhase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExperienceProjectionTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() { MinecraftTestBootstrap.initialize(); }
    private static final CharacterId FIRST = CharacterId.of("mate"), LAST = CharacterId.of("kid");
    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), WATCHER = UUID.randomUUID();
    @AfterEach void clear() { GameComponent.clearExperienceMemory(); }
    private static GameComponent component() {
        return component(new Affinities(Map.of(FIRST, LAST, LAST, FIRST), Map.of(FIRST, FIRST, LAST, LAST)));
    }
    private static GameComponent component(Affinities affinities) {
        Roster roster = new Roster(List.of(new Survivor(FIRST, 1, 8, 4, "base", new Ability.None()),
                new Survivor(LAST, 2, 6, 9, "base", new Ability.None())));
        Session session = new Session("experience", roster, new Table(new NavigationDeck(List.of(new NavigationCard("calm", 0,
                new Selector.Nobody(), new Selector.Nobody(), false, false)), new Random(1)),
                new Provisions(List.of(new Provision("private_blade", Provision.Category.WEAPON, 2, new ProvisionEffect.Weapon(3)))), new Random(1)));
        session.dealAffinities(affinities);
        session.dealFromPile(FIRST, "private_blade"); session.dealFromPile(FIRST, "private_blade");
        GameComponent c = new GameComponent(null);
        c.begin(session, Map.of(FIRST, new GameComponent.Occupant(A, "a"), LAST, new GameComponent.Occupant(B, "b")), Set.of(WATCHER));
        c.clearFog(); return c;
    }
    private static HudView project(GameComponent c, UUID viewer) {
        RegistryByteBuf b = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        try {
            c.writeViewFor(b, viewer); b.writeInt(0x55455831);
            HudView view = GameComponent.readViewFrom(b);
            assertEquals(0x55455831, b.readInt()); assertFalse(b.isReadable()); return view;
        } finally { b.release(); }
    }
    @Test void goalsWeaponsAndReceiptsAreFilteredBeforeSerialization() {
        GameComponent c = component(); Session s = c.requireSession();
        s.advancePhase(); s.declare(FIRST, Contest.Kind.SWAP, LAST); s.consent(true); s.closeStances();
        c.openContestWindow(20000); s.commitWeapon(FIRST, "private_blade"); s.commitWeapon(FIRST, "private_blade");
        c.privateEvent(FIRST, "gift", ExperienceView.Source.PLAYER, Text.literal("private receipt"));
        c.notify(Text.literal("public event"));
        ExperienceView mine = project(c, A).experience(), theirs = project(c, B).experience(), spectator = project(c, WATCHER).experience();
        assertFalse(mine.goals().isEmpty()); assertTrue(spectator.goals().isEmpty());
        assertEquals(List.of("private_blade", "private_blade"), mine.committedWeapons()); assertEquals(6, mine.committedPower());
        assertTrue(theirs.committedWeapons().isEmpty()); assertTrue(spectator.committedWeapons().isEmpty());
        assertTrue(spectator.playability().isEmpty());
        assertEquals(2, mine.events().size()); assertEquals(1, theirs.events().size());
        assertEquals("public event", theirs.events().getFirst().message().getString());
        assertTrue(spectator.scores().isEmpty());
        var outcome = s.resolveContest();
        var report = new ExperienceView.BattleReport(true, FIRST.value(), LAST.value(), outcome.attackPower(),
                outcome.defendPower(), outcome.attackerGetsWhatTheyWanted(), outcome.damagePerLoser(),
                List.of(LAST.value()), List.of("private_blade", "private_blade"), List.of());
        c.setLastBattle(report);
        assertEquals(report, project(c, WATCHER).experience().battle(), "Only after actual resolution are the revealed weapons public");
    }
    /**
     * 战报与计分说明都从生产那一份生成（{@link ContestPhase#battleReport} · {@link EndgamePhase#scoreExplanations}），
     * 不手塞：上面那条用例的战报是测试自己拼的，结算那一侧把武器分错边、说明发错人，它照样绿。
     * 期望值按局面手算：大副体型 8 + 刀 3 = 11 对小孩体型 6，进攻方赢；说明里的分值是各人的生存分（4 / 9）。
     */
    @Test void battleReportAndScoreExplanationComeFromTheRealResolution() {
        GameComponent c = component(new Affinities(Map.of(FIRST, LAST, LAST, FIRST), Map.of(FIRST, LAST, LAST, FIRST)));
        Session s = c.requireSession();
        s.advancePhase(); s.declare(FIRST, Contest.Kind.SWAP, LAST); s.consent(true); s.closeStances();
        c.openContestWindow(20000); s.commitWeapon(FIRST, "private_blade");
        Contest contest = s.contest().orElseThrow();
        assertFalse(project(c, WATCHER).experience().battle().present(), "结算之前不该有战报");
        var outcome = s.resolveContest();
        c.setLastBattle(ContestPhase.battleReport(contest, outcome, s.state().bySeat()));
        var seen = project(c, WATCHER).experience().battle();
        assertEquals(FIRST.value(), seen.attacker()); assertEquals(LAST.value(), seen.target());
        assertEquals(11, seen.attackPower()); assertEquals(6, seen.defendPower()); assertTrue(seen.attackerWon());
        assertEquals(List.of("private_blade"), seen.attackWeapons(), "进攻方押下的那一把没算在进攻方");
        assertEquals(List.of(), seen.defendWeapons(), "防守方没押，却列出了武器");
        assertEquals(List.of(LAST.value()), seen.losers());

        s.landForFixture();
        TreasureScoring scoring = new TreasureScoring(1, List.of(2), List.of(1, 4, 8));
        c.setScoreExplanations(EndgamePhase.scoreExplanations(s, scoring));
        c.setEndgame(new EndgameProgress(GameState.Outcome.LANDED, 1, 2, List.of(FIRST, LAST),
                EndgameProgress.Stage.SCORES, 0, s.scores(scoring)));
        List<String> keys = List.of("heavyseas.ux.score.self_alive", "heavyseas.ux.score.treasure_kept",
                "heavyseas.ux.score.love_alive", "heavyseas.ux.score.hate_alive");
        List<Text> mine = project(c, A).experience().scoreExplanation();
        List<Text> theirs = project(c, B).experience().scoreExplanation();
        assertEquals(keys, mine.stream().map(ExperienceProjectionTest::key).toList());
        assertEquals("4", firstArg(mine.getFirst()), "大副的说明里自己的生存分不是 4");
        assertEquals(keys, theirs.stream().map(ExperienceProjectionTest::key).toList());
        assertEquals("9", firstArg(theirs.getFirst()), "小孩拿到的不是自己那一份说明");
        assertTrue(project(c, WATCHER).experience().scoreExplanation().isEmpty());
    }

    private static String key(Text text) {
        return ((TranslatableTextContent) text.getContent()).getKey();
    }

    private static String firstArg(Text text) {
        Object arg = ((TranslatableTextContent) text.getContent()).getArgs()[0];
        return arg instanceof Text t ? t.getString() : String.valueOf(arg);
    }

    @Test void summariesAppearOnlyAtScoresAndLastResultSurvivesEndButNotServerStop() {
        GameComponent c = component();
        Map<CharacterId, ScoreSheet> scores = Map.of(FIRST, new ScoreSheet(0, 2, 9, 0), LAST, new ScoreSheet(0, 0, 4, 0));
        c.setScoreExplanations(Map.of(FIRST, List.of(Text.literal("only first explanation")), LAST, List.of(Text.literal("only last explanation"))));
        for (var stage : List.of(EndgameProgress.Stage.ARRIVAL, EndgameProgress.Stage.HATE, EndgameProgress.Stage.LOVE)) {
            c.setEndgame(new EndgameProgress(GameState.Outcome.LANDED, 3, 2, List.of(FIRST, LAST), stage, 0, scores));
            assertTrue(project(c, A).experience().scores().isEmpty());
            assertTrue(project(c, A).experience().scoreExplanation().isEmpty());
        }
        c.setEndgame(new EndgameProgress(GameState.Outcome.LANDED, 3, 2, List.of(FIRST, LAST), EndgameProgress.Stage.SCORES, 0, scores));
        c.setReturnAtMs(123456); c.rememberResult();
        assertEquals(2, project(c, WATCHER).experience().scores().size());
        assertEquals(123456, project(c, WATCHER).experience().returnAtMs());
        assertEquals(11, project(c, B).experience().scores().getFirst().total());
        assertTrue(project(c, WATCHER).experience().scoreExplanation().isEmpty());
        c.end();
        assertFalse(project(c, A).active());
        assertTrue(project(c, A).experience().lastResult().present());
        assertEquals("only first explanation", project(c, A).experience().lastResult().explanation().getFirst().getString());
        assertTrue(project(c, WATCHER).experience().lastResult().explanation().isEmpty());
        assertFalse(project(c, UUID.randomUUID()).experience().lastResult().present());
        GameComponent.clearExperienceMemory();
        assertFalse(project(c, A).experience().lastResult().present());
    }
    @Test void restartKeepsEscrowButNotMatchOrExperience() {
        GameComponent c = component(); c.notify(Text.literal("never persisted"));
        c.putVoyageEscrow(new GameComponent.VoyageEscrow(A, "minecraft:overworld", 1, 70, 2, 0, 0, new NbtList()));
        NbtCompound nbt = new NbtCompound(); c.writeToNbt(nbt, null);
        GameComponent restarted = new GameComponent(null); restarted.readFromNbt(nbt, null);
        assertTrue(restarted.session().isEmpty()); assertEquals(1, restarted.interruptedTurn());
        assertTrue(restarted.hasVoyageEscrow(A)); assertTrue(restarted.eventsFor(A).isEmpty());
        assertFalse(nbt.toString().contains("private_blade")); assertFalse(nbt.toString().contains("never persisted"));
    }
    @Test void decisionIdentityBelongsToTheRecipientAndIgnoresDeadlineExtensions() {
        GameComponent c = component(); Session s = c.requireSession(); s.advancePhase();
        assertFalse(project(c, A).experience().decisionId().isEmpty()); assertEquals("", project(c, B).experience().decisionId());
        s.declare(FIRST, Contest.Kind.SWAP, LAST); c.openContestWindow(20000);
        String first = project(c, B).experience().decisionId();
        assertFalse(first.isEmpty()); assertEquals("", project(c, A).experience().decisionId());
        c.openContestWindow(30000); assertEquals(first, project(c, B).experience().decisionId());
        s.consent(true); s.closeStances(); c.openContestWindow(20000);
        assertNotEquals(first, project(c, A).experience().decisionId());
    }
    @Test void changingADeadlineKeepsTheWindowButOpeningAnotherWindowDoesNot() {
        GameComponent c = component(); Session s = c.requireSession();
        s.advancePhase(); s.markActed(FIRST); s.markActed(LAST); s.advancePhase();
        c.openHelmWindow(20000); c.setHelmOwner(FIRST);
        String first = project(c, A).experience().decisionId();
        assertFalse(first.isEmpty());
        c.setHelmDeadline(System.currentTimeMillis() + 40000);
        assertEquals(first, project(c, A).experience().decisionId());
        c.openHelmWindow(20000);
        assertNotEquals(first, project(c, A).experience().decisionId());
        assertEquals("", project(c, B).experience().decisionId());
    }
    @Test void eventIdentityAndPhaseDescribeWhenItHappened() {
        GameComponent c = component();
        c.notify(Text.literal("first"), ExperienceView.Source.TIMEOUT, FIRST.value());
        c.requireSession().advancePhase();
        c.notify(Text.literal("second"), ExperienceView.Source.PLAYER, LAST.value());
        var events = project(c, A).experience().events();
        assertEquals(1, events.getFirst().seq()); assertEquals(2, events.getLast().seq());
        assertEquals(io.github.heavyseasmc.engine.state.Phase.PROVISION, events.getFirst().phase());
        assertEquals(io.github.heavyseasmc.engine.state.Phase.ACTION, events.getLast().phase());
        assertEquals(ExperienceView.Source.TIMEOUT, events.getFirst().source());
        assertEquals(ExperienceView.Source.PLAYER, events.getLast().source());
    }
    @Test void completedGameCannotKeepAnActionTurnBeforeTheArrivalProjectionStarts() {
        GameComponent c = component(); c.requireSession().advancePhase();
        HudView initial = project(c, A);
        assertEquals(0L, initial.actionDeadlineMs());
        assertTrue(initial.myTurnToAct(), "An ordinary action with no timed-window fixture is still the player's turn");
        c.openActionWindow(20000);
        assertTrue(project(c, A).myTurnToAct());
        c.requireSession().landForFixture();
        HudView ended = project(c, A);
        assertFalse(ended.endgame().active(), "Exercise the gap before the finale is attached");
        assertFalse(ended.yourTurn()); assertFalse(ended.myTurnToAct());
        assertEquals(0L, ended.actionDeadlineMs()); assertEquals(0L, ended.actionWindowMs());
    }
    @Test void arrivalDoesNotAdvertiseAnActionEvenIfTheStoredPhaseIsStillAction() {
        GameComponent c = component(); c.requireSession().advancePhase();
        c.setEndgame(new EndgameProgress(GameState.Outcome.LANDED, 1, 2, List.of(FIRST, LAST),
                EndgameProgress.Stage.ARRIVAL, 0, Map.of(FIRST, new ScoreSheet(0, 0, 0, 0), LAST, new ScoreSheet(0, 0, 0, 0))));
        HudView arrival = project(c, A);
        assertTrue(arrival.endgame().active());
        assertFalse(arrival.yourTurn()); assertFalse(arrival.myTurnToAct());
        assertEquals(0L, arrival.actionDeadlineMs());
    }
}
