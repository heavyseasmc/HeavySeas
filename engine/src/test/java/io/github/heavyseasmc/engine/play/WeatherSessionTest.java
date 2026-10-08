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
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class WeatherSessionTest {
    private static final Path DATA = Path.of(System.getProperty("heavyseas.data.dir"));
    private static final Roster ROSTER = RosterLoader.load(DATA.resolve("roster/default.json")).preset(6);
    private static final Provisions PROVISIONS = ProvisionLoader.loadCatalog(DATA.resolve("provisions/default.json"));
    private static final NavigationCard QUIET = card("quiet", 0, new Selector.Nobody(), false, false);

    @Test
    void lateRumRespectsRainAndDoubleWater() {
        for (WeatherEffect weather : List.of(WeatherEffect.IGNORE_THIRST, WeatherEffect.DOUBLE_WATER)) {
            Session s = atAction(weather, List.of(QUIET));
            CharacterId who = s.state().bySeat().getFirst();
            deal(s, who, "rum");
            s.advancePhase();
            s.beginNavigate(QUIET);
            s.drinkRum(who, "rum");
            if (weather == WeatherEffect.IGNORE_THIRST) {
                assertTrue(s.thirstPending().isEmpty());
            } else {
                assertEquals(2, s.thirstPending().orElseThrow().waterNeeded());
            }
        }
    }

    @Test
    void lateRumAfterExtraResolutionKeepsTheStandardCardPendingAndChargesOnce() {
        Session s = atAction(WeatherEffect.EXTRA_NAVIGATION, List.of(QUIET, card("second", 0, new Selector.Nobody(), false, false)));
        CharacterId who = s.state().bySeat().getFirst();
        deal(s, who, "rum");
        s.advancePhase();
        s.beginNavigate(s.takeWeatherNavigationCard());
        assertTrue(s.finishNavigationResolution());
        s.drinkRum(who, "rum");
        assertEquals(1, drainThirst(s, who));
        assertTrue(s.finishNavigationResolution(), "补算完仍要执行标准航海牌");
        s.prepareRowStack();
        s.beginNavigate(s.takeCardForNavigation(null));
        assertEquals(0, drainThirst(s, who), "同一天第二张不再重复酒的来源");
        assertFalse(s.finishNavigationResolution());
    }

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
        session.row(rower, (cards, state, who) -> 0);
        session.advancePhase();
        NavigationReport report = session.beginNavigate(card("oar", 0, new Selector.Nobody(), true, false));
        assertTrue(report.overboardSelected().contains(rower));
        assertTrue(session.thirstPending().isEmpty(), "船桨图示已改成落海，不应再按划船标记口渴");
    }

    @Test
    void weatherHasANewWindowAndBaitDoesNotCarryOver() {
        Session session = atAction(WeatherEffect.ROWERS_OVERBOARD, List.of(QUIET, QUIET, QUIET));
        CharacterId rower = session.state().bySeat().getFirst();
        session.row(rower, (cards, state, who) -> 0);
        session.reveal(rower, deal(session, rower, "life_preserver"));
        deal(session, rower, "bait_bucket");
        session.advancePhase();
        session.beginNavigation(new NavigationCard("two-falls", 0,
                new Selector.Only(java.util.Set.of(rower)), new Selector.Nobody(), true, false));
        int first = session.overboardPending().orElseThrow().token();
        session.playOverboardCard(rower, rower, "bait_bucket", first);
        session.finishOverboard();
        assertEquals(1, session.state().stateOf(rower).damage());
        assertNotEquals(first, session.overboardPending().orElseThrow().token());
        assertThrows(IllegalStateException.class,
                () -> session.playOverboardCard(rower, rower, "bait_bucket", first));
        session.finishOverboard();
        assertEquals(1, session.state().stateOf(rower).damage());
        assertTrue(session.overboardPending().isEmpty());
        session.requireNoProvisionLost("weather window");
    }

    @Test
    void fourthGullFinishesBeforeAnyOverboardWindow() {
        NavigationCard gull = card("gull", 1, new Selector.Nobody(), false, false);
        Session session = atAction(WeatherEffect.ALL_THIRST, List.of(gull, gull, gull));
        CharacterId actor = session.state().bySeat().getFirst();
        session.fireSignal(actor, deal(session, actor, "flare_gun"));
        assertEquals(3, session.state().gulls());
        session.advancePhase();
        session.beginNavigation(new NavigationCard("land", 1,
                new Selector.Everyone(), new Selector.Everyone(), true, true));
        assertTrue(session.state().isOver());
        assertTrue(session.overboardPending().isEmpty());
        assertTrue(session.thirstPending().isEmpty());
        assertTrue(session.navigationReport().overboardSelected().isEmpty());
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

    // ---- 狂风天翻两张航海牌：同一来源一天只算一次，阳伞一天只挡一次（ADR-0087 §4 第 1 条）。
    //      规则事实表探针 A · B：两张之间口渴标记不清，于是第一张点过名的人、喝过酒的人在第二张上又渴一次。

    @Test
    void galeNamedThirstCountsOncePerDay() {
        CharacterId first = firstSeat();
        NavigationCard named = card("named", 0, new Selector.Only(Set.of(first)), false, false);
        Session session = galeAfterExtra("named", List.of(named, QUIET), s -> { });
        assertEquals(1, drainThirst(session, first), "狂风那张点了他的名");
        finishGaleAndBeginStandard(session);
        assertEquals(0, drainThirst(session, first), "标准那张没点他；今天的点名已经算过");
        assertEquals(1, session.state().stateOf(first).damage());
    }

    @Test
    void galeRumThirstCountsOncePerDay() {
        CharacterId first = firstSeat();
        Session session = galeAfterExtra(null, List.of(QUIET, card("quiet-2", 0, new Selector.Nobody(), false, false)),
                s -> s.drinkRum(first, deal(s, first, "rum")));
        assertEquals(1, drainThirst(session, first), "酒的口渴不看牌面");
        finishGaleAndBeginStandard(session);
        assertEquals(0, drainThirst(session, first), "酒今天已经渴过一次");
        assertEquals(1, session.state().stateOf(first).damage());
    }

    @Test
    void galeParasolCoversOncePerDay() {
        // 两张各带一个只在那一张上生效的来源（狂风那张有打架图示、标准那张点他的名），
        // 这样「同一来源一天一次」碰不到它们，分得出的只有阳伞挡了一次还是两次。
        CharacterId first = firstSeat();
        NavigationCard fight = card("fight", 0, new Selector.Nobody(), false, true);
        NavigationCard named = card("named", 0, new Selector.Only(Set.of(first)), false, false);
        int[] before = new int[1];
        Session session = galeAfterExtra("fight", List.of(fight, named), s -> {
            s.openParasol(first, deal(s, first, "parasol"));
            CharacterId other = s.state().bySeat().get(1);
            s.applyFight(Fight.between(first, other));
            before[0] = s.state().stateOf(first).damage();
        });
        drainThirst(session, first);
        assertEquals(before[0], session.state().stateOf(first).damage(), "打架的口渴被撑开的伞挡下");
        finishGaleAndBeginStandard(session);
        drainThirst(session, first);
        assertEquals(1, session.state().stateOf(first).damage() - before[0], "伞今天已经挡过一次，点名这一次得自己扛");
    }

    private static CharacterId firstSeat() {
        return atAction(WeatherEffect.EXTRA_NAVIGATION, List.of(QUIET)).state().bySeat().getFirst();
    }

    /**
     * 狂风天：在行动阶段做完 {@code setup}，翻出狂风那张并结算完落海、排好口渴队列。
     * 两张牌的先后由牌堆洗牌的种子决定，所以逐个种子试，直到狂风那张恰好是 {@code extraId}（{@code null} 表示哪张都行）。
     */
    private static Session galeAfterExtra(String extraId, List<NavigationCard> deck, Consumer<Session> setup) {
        for (long seed = 1; seed < 64; seed++) {
            Session session = atAction(WeatherEffect.EXTRA_NAVIGATION, deck, seed);
            setup.accept(session);
            session.advancePhase();
            NavigationCard extra = session.takeWeatherNavigationCard();
            if (extraId == null || extra.id().equals(extraId)) {
                session.beginNavigate(extra);
                return session;
            }
        }
        return fail("64 个种子里狂风那张都不是 " + extraId);
    }

    private static void finishGaleAndBeginStandard(Session session) {
        assertTrue(session.finishNavigationResolution());
        session.prepareRowStack();
        session.beginNavigate(session.takeCardForNavigation(null));
    }

    /** 这一张牌的口渴全部按「不喝」结算完；返回其中问到 {@code who} 几次。 */
    private static int drainThirst(Session session, CharacterId who) {
        int asked = 0;
        Optional<Session.ThirstPrompt> prompt;
        while ((prompt = session.thirstPending()).isPresent()) {
            if (prompt.get().who().equals(who)) {
                asked++;
            }
            session.decideThirst(List.of());
        }
        return asked;
    }

    private static Session atNavigation(WeatherEffect effect, List<NavigationCard> deck) {
        Session session = atAction(effect, deck);
        session.advancePhase();
        return session;
    }

    private static Session atAction(WeatherEffect effect, List<NavigationCard> deck) {
        return atAction(effect, deck, 1);
    }

    private static Session atAction(WeatherEffect effect, List<NavigationCard> deck, long deckSeed) {
        Table table = new Table(new NavigationDeck(deck, new Random(deckSeed)), PROVISIONS,
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
