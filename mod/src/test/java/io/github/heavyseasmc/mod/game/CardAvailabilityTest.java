package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.*;
import io.github.heavyseasmc.engine.navigation.*;
import io.github.heavyseasmc.engine.play.*;
import io.github.heavyseasmc.mod.state.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CardAvailabilityTest {
    private static final CharacterId FIRST = CharacterId.of("mate"), LAST = CharacterId.of("kid");
    private static GameComponent component() {
        Roster roster = new Roster(List.of(new Survivor(FIRST, 1, 8, 4, "base", new Ability.None()),
                new Survivor(LAST, 2, 3, 9, "base", new Ability.None())));
        List<Provision> cards = List.of(
                new Provision("rum", Provision.Category.EQUIPMENT, 2, new ProvisionEffect.BuffSize(3, "turn",
                        ProvisionEffect.BuffSize.THIRST_AT_END_OF_TURN, true, true, false)),
                new Provision("medical_kit", Provision.Category.EQUIPMENT, 1,
                        new ProvisionEffect.Heal(1, ProvisionEffect.Target.ANY_CHARACTER_INCLUDING_SELF, true, true, List.of())),
                new Provision("parasol", Provision.Category.EQUIPMENT, 1, new ProvisionEffect.PreventThirst(1,
                        "thirst_source", ProvisionEffect.Target.SELF, false, "", false, true, true, true, true)),
                new Provision("ration", Provision.Category.CONSUMABLE, 1, new ProvisionEffect.HealAll(1, "conscious", true, true, true, true)));
        Session s = new Session("availability", roster, new Table(new NavigationDeck(List.of(new NavigationCard("calm", 0,
                new Selector.Nobody(), new Selector.Nobody(), false, false)), new Random(1)), new Provisions(cards), new Random(1)));
        for (Provision card : cards) s.dealFromPile(FIRST, card.id());
        s.dealFromPile(LAST, "rum"); s.advancePhase();
        GameComponent c = new GameComponent(null); c.begin(s, Map.of(FIRST, new GameComponent.Occupant(UUID.randomUUID(), "a"),
                LAST, new GameComponent.Occupant(UUID.randomUUID(), "b"))); return c;
    }
    private static CardAvailability.Block play(GameComponent c, CharacterId who, String card) {
        return CardAvailability.check(c, who, card, ExperienceView.Action.PLAY);
    }
    @Test void queryAndActualDrinkingAgreeAndQueriesDoNotConsumeAnything() {
        GameComponent c = component(); Session s = c.requireSession(); var before = s.state();
        assertEquals(CardAvailability.Block.NONE, play(c, FIRST, "rum"));
        assertSame(before, s.state()); s.drinkRum(FIRST, "rum");
        assertEquals(CardAvailability.Block.ALREADY_USED, play(c, FIRST, "rum"));
        assertThrows(IllegalStateException.class, () -> s.drinkRum(FIRST, "rum"));
        assertEquals(CardAvailability.Block.NONE, play(c, LAST, "rum"), "An anytime drink does not require the current turn");
    }
    @Test void specialConditionsHaveSpecificReasonsAndFollowActualState() {
        GameComponent c = component(); Session s = c.requireSession();
        assertEquals(CardAvailability.Block.NOBODY_WOUNDED, play(c, FIRST, "medical_kit"));
        assertEquals(CardAvailability.Block.NO_CORPSE, play(c, FIRST, "ration"));
        assertEquals(CardAvailability.Block.NONE, play(c, FIRST, "parasol"));
        s.openParasol(FIRST, "parasol");
        assertEquals(CardAvailability.Block.ALREADY_OPEN, play(c, FIRST, "parasol"));
        assertThrows(IllegalStateException.class, () -> s.openParasol(FIRST, "parasol"));
        s.markActed(FIRST);
        assertEquals(CardAvailability.Block.NOT_YOUR_TURN, play(c, FIRST, "medical_kit"));
    }
    @Test void pickingLocksAllDrinkingAndRevealingWithoutRevealingOtherHands() {
        GameComponent c = component(); Session s = c.requireSession();
        s.declare(FIRST, Contest.Kind.STEAL, LAST); s.consent(false);
        assertEquals(Contest.Stage.PICK, s.contest().orElseThrow().stage());
        assertEquals(CardAvailability.Block.PICK_LOCKED, play(c, LAST, "rum"));
        assertEquals(CardAvailability.Block.PICK_LOCKED, CardAvailability.check(c, LAST, "rum", ExperienceView.Action.REVEAL));
        assertThrows(IllegalStateException.class, () -> s.drinkRum(LAST, "rum"));
        assertEquals(CardAvailability.Block.CARD_CHANGED, play(c, LAST, "medical_kit"));
    }
    @Test void consentProvenanceChangesWordingNotTheDefaultAnswer() {
        assertEquals("heavyseas.ux.consent.timeout", ContestPhase.responseKey(Contest.Kind.STEAL, false, ExperienceView.Source.TIMEOUT));
        assertEquals("heavyseas.ux.consent.offline", ContestPhase.responseKey(Contest.Kind.STEAL, false, ExperienceView.Source.DISCONNECT));
        assertEquals(ContestPhase.responseKey(Contest.Kind.STEAL, false),
                ContestPhase.responseKey(Contest.Kind.STEAL, false, ExperienceView.Source.PLAYER));
        assertEquals(ContestPhase.responseKey(Contest.Kind.STEAL, true),
                ContestPhase.responseKey(Contest.Kind.STEAL, true, ExperienceView.Source.STAND_IN));
    }
}
