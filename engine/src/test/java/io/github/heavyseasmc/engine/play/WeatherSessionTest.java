package io.github.heavyseasmc.engine.play;

import io.github.heavyseasmc.engine.data.ProvisionLoader;
import io.github.heavyseasmc.engine.data.RosterLoader;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.NavigationDeck;
import io.github.heavyseasmc.engine.navigation.Selector;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.engine.thirst.ThirstSource;
import io.github.heavyseasmc.engine.weather.WeatherCard;
import io.github.heavyseasmc.engine.weather.WeatherDeck;
import io.github.heavyseasmc.engine.weather.WeatherEffect;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class WeatherSessionTest {
    private static final Path DATA = Path.of(System.getProperty("heavyseas.data.dir"));
    private static final Roster ROSTER = RosterLoader.load(DATA.resolve("roster/default.json")).preset(6);
    private static final Provisions PROVISIONS = ProvisionLoader.loadCatalog(DATA.resolve("provisions/default.json"));
    private static final NavigationCard QUIET = card("quiet", 0, new Selector.Nobody(), false, false);

    @Test
    void rainSuppressesEveryThirstSource() {
        Session session = atAction(WeatherEffect.IGNORE_THIRST, List.of(QUIET));
        CharacterId actor = session.state().bySeat().getFirst();
        session.drinkRum(actor, deal(session, actor, "rum"));
        session.advancePhase();
        session.beginNavigate(card("named", 0, new Selector.Everyone(), true, true));
        assertTrue(session.thirstPending().isEmpty());
    }

    @Test
    void scorchingHeatAddsOneSourceToEveryone() {
        Session session = atNavigation(WeatherEffect.ALL_THIRST, List.of(QUIET));
        NavigationReport report = session.beginNavigate(QUIET);
        assertTrue(report.thirstSelected().isEmpty(), "天气口渴不是航海牌点名");
        Session.ThirstPrompt prompt = session.thirstPending().orElseThrow();
        assertTrue(prompt.effective().has(ThirstSource.WEATHER));
        assertEquals(1, prompt.remaining());
    }

    @Test
    void swelteringRequiresCompletePairsOfWater() {
        Session session = atAction(WeatherEffect.DOUBLE_WATER, List.of(QUIET));
        CharacterId first = session.state().bySeat().getFirst();
        deal(session, first, "water");
        deal(session, first, "water");
        session.advancePhase();
        session.beginNavigate(card("thirst", 0, new Selector.Everyone(), false, false));
        Session.ThirstPrompt prompt = session.thirstPending().orElseThrow();
        assertEquals(2, prompt.waterPerSource());
        assertEquals(2, prompt.waterNeeded());
        assertThrows(IllegalArgumentException.class, () -> session.decideThirst(List.of(first)));
        session.decideThirst(List.of(first, first));
        assertEquals(0, session.state().stateOf(first).damage());
    }

    @Test
    void denseFogSuppressesNavigationGulls() {
        Session session = atNavigation(WeatherEffect.IGNORE_GULLS, List.of(QUIET));
        session.beginNavigate(card("gull", 1, new Selector.Nobody(), false, false));
        assertEquals(0, session.state().gulls());
    }

    @Test
    void denseFogAlsoSuppressesFlareGulls() {
        Session session = atAction(WeatherEffect.IGNORE_GULLS, List.of(
                card("gull", 1, new Selector.Nobody(), false, false),
                card("quiet-2", 0, new Selector.Nobody(), false, false),
                card("quiet-3", 0, new Selector.Nobody(), false, false)));
        CharacterId actor = session.state().bySeat().getFirst();
        session.fireSignal(actor, deal(session, actor, "flare_gun"));
        assertEquals(0, session.state().gulls());
    }

    @Test
    void hugeWaveConvertsFightIconIntoIndependentOverboardPhase() {
        Session session = atAction(WeatherEffect.FIGHTERS_OVERBOARD, List.of(QUIET));
        CharacterId attacker = session.state().bySeat().get(0);
        CharacterId target = session.state().bySeat().get(1);
        session.declare(attacker, Contest.Kind.SWAP, target);
        session.consent(true);
        session.closeStances();
        session.resolveContest();
        session.advancePhase();
        NavigationReport report = session.beginNavigate(card("fight", 0, new Selector.Nobody(), false, true));
        assertTrue(report.overboardSelected().containsAll(List.of(attacker, target)));
        assertTrue(session.thirstPending().isEmpty(), "战斗图示已改成落海，不应再按战斗标记口渴");
    }

    @Test
    void stormConvertsOarIconIntoOverboard() {
        List<NavigationCard> deck = List.of(card("a", 0, new Selector.Nobody(), false, false),
                card("b", 0, new Selector.Nobody(), false, false), QUIET);
        Session session = atAction(WeatherEffect.ROWERS_OVERBOARD, deck);
        CharacterId rower = session.state().bySeat().getFirst();
        session.row(rower, (card, state, who) -> false);
        session.advancePhase();
        NavigationReport report = session.beginNavigate(card("oar", 0, new Selector.Nobody(), true, false));
        assertTrue(report.overboardSelected().contains(rower));
        assertTrue(session.thirstPending().isEmpty(), "船桨图示已改成落海，不应再按划船标记口渴");
    }

    @Test
    void becalmedSkipsNavigationButEndsDayAndClearsMarkers() {
        Session session = atAction(WeatherEffect.SKIP_NAVIGATION, List.of(QUIET));
        CharacterId actor = session.state().bySeat().getFirst();
        session.markActed(actor);
        assertThrows(IllegalStateException.class, () -> session.beginRow(actor));
        session.advancePhase();
        session.skipNavigation();
        session.advancePhase();
        assertEquals(Phase.WEATHER, session.state().phase());
        assertEquals(2, session.state().turn());
        assertFalse(session.state().stateOf(actor).actedThisTurn());
    }

    @Test
    void galeResolvesExtraThenStillRequiresStandardNavigation() {
        Session session = atNavigation(WeatherEffect.EXTRA_NAVIGATION,
                List.of(card("a", 0, new Selector.Nobody(), false, false), QUIET));
        assertTrue(session.weatherNavigationPending());
        NavigationCard extra = session.takeWeatherNavigationCard();
        session.beginNavigate(extra);
        assertTrue(session.finishNavigationResolution());
        assertFalse(session.navigationComplete());
        session.prepareRowStack();
        NavigationCard standard = session.takeCardForNavigation(null);
        session.beginNavigate(standard);
        assertFalse(session.finishNavigationResolution());
        assertTrue(session.navigationComplete());
    }

    private static Session atNavigation(WeatherEffect effect, List<NavigationCard> deck) {
        Session session = atAction(effect, deck);
        session.advancePhase();
        return session;
    }

    private static Session atAction(WeatherEffect effect, List<NavigationCard> deck) {
        Table table = new Table(new NavigationDeck(deck, new Random(1)), PROVISIONS,
                new WeatherDeck(List.of(new WeatherCard(effect.id(), effect)), new Random(2)), new Random(3));
        Session session = new Session("weather-test", ROSTER, table);
        assertEquals(effect, session.beginWeather().effect());
        session.advancePhase();
        session.advancePhase();
        assertEquals(Phase.ACTION, session.state().phase());
        return session;
    }

    private static String deal(Session session, CharacterId who, String card) {
        session.dealFromPile(who, card);
        return card;
    }

    private static NavigationCard card(String id, int gull, Selector thirst,
                                       boolean rowers, boolean fighters) {
        return new NavigationCard(id, gull, new Selector.Nobody(), thirst, rowers, fighters);
    }
}
