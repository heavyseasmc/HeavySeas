package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.data.NavigationLoader;
import io.github.heavyseasmc.engine.data.ProvisionLoader;
import io.github.heavyseasmc.engine.data.RosterData;
import io.github.heavyseasmc.engine.data.RosterLoader;
import io.github.heavyseasmc.engine.data.WeatherLoader;
import io.github.heavyseasmc.engine.model.Affinities;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.NavigationDeck;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.play.Table;
import io.github.heavyseasmc.engine.seat.ActionChoice;
import io.github.heavyseasmc.engine.seat.Gift;
import io.github.heavyseasmc.engine.seat.Legal;
import io.github.heavyseasmc.engine.seat.RandomSeatPolicy;
import io.github.heavyseasmc.engine.seat.SeatDriver;
import io.github.heavyseasmc.engine.seat.SeatPolicy;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.engine.weather.WeatherDeck;
import io.github.heavyseasmc.mod.state.ExperienceView;
import io.github.heavyseasmc.mod.state.GameComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 手牌一面的可用性（{@link CardAvailability}）与引擎的合法清单（{@link Legal}）逐一相同。
 *
 * <p>局面来自几十局随机对局（真实 8 人 · 带天候）：席位每被问一次决定，就把<b>每个座位 × 手里与面前的每张牌 × 每种用法</b>
 * 问一遍可用性，与照 {@code Legal} 推出的答案比；能用的那几项再在这一局的副本上真的做一遍，引擎不许拒。
 * 期望值从 {@code Legal} 取，不从 {@code CardAvailability} 自己取 —— 判据与被测对象不同源。
 *
 * <p>守的缺陷：可用性另写一份守卫、与引擎分家（此前两处：撑伞不看「要不要撑开」、送牌不排除「划船中」）。
 * 红测：把 {@code CardAvailability} 换回手抄守卫那一版，这里红在「划船中送牌」那一格。
 */
final class CardAvailabilityLegalTest {

    private static final int GAMES = 24;

    @Test
    @DisplayName("几十局随机局面里，每个座位每张牌每种用法：可用性与 Legal 逐一相同，能用的引擎都接受")
    @Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void availabilityMatchesLegalEverywhere() {
        Path data = dataDir();
        RosterData rd = RosterLoader.load(data.resolve("roster").resolve("default.json"));
        Provisions provisions = ProvisionLoader.loadCatalog(data.resolve("provisions").resolve("default.json"));
        List<NavigationCard> deck = NavigationLoader.load(data.resolve("navigation").resolve("default.json"),
                rd.ids(), provisions.ids());
        var weather = WeatherLoader.load(data.resolve("weather").resolve("default.json"));
        Roster roster = rd.preset(8);
        Map<String, Integer> seen = new TreeMap<>();
        for (long seed = 0; seed < GAMES; seed++) {
            Random rng = new Random(seed);
            Session session = new Session("availability seed=" + seed, roster,
                    new Table(new NavigationDeck(deck, rng), provisions, new WeatherDeck(weather, rng), rng));
            session.dealAffinities(Affinities.random(roster, rng));
            GameComponent component = new GameComponent(null);
            Map<CharacterId, GameComponent.Occupant> seats = new LinkedHashMap<>();
            for (CharacterId id : session.state().bySeat()) {
                seats.put(id, new GameComponent.Occupant(UUID.randomUUID(), id.value()));
            }
            component.begin(session, seats);
            SeatPolicy checking = checking(component, seen);
            Map<CharacterId, SeatPolicy> policies = new LinkedHashMap<>();
            session.state().bySeat().forEach(id -> policies.put(id, checking));
            SeatDriver driver = new SeatDriver(session, policies, new Random(seed));
            while (!session.state().isOver() && session.state().turn() <= 60) {
                driver.playPhase();
                if (session.state().isOver()) {
                    break;
                }
                session.advancePhase();
            }
        }
        System.out.println("可用性逐项核对（能用 / 不能用 各几项）：" + seen);
        // 正向对照：「0 项」与「根本没查」输出一样 —— 每种用法能用与不能用都必须真的出现过
        for (ExperienceView.Action action : ExperienceView.Action.values()) {
            assertTrue(seen.getOrDefault(action + " 能用", 0) > 0, action + " 一次「能用」都没查到");
            assertTrue(seen.getOrDefault(action + " 不能用", 0) > 0, action + " 一次「不能用」都没查到");
        }
        assertTrue(seen.getOrDefault("划船中有牌在手", 0) > 0, "正向对照：划船中送牌那一格一次都没走到");
    }

    /** 每被问一次决定，先把全船核对一遍，再照随机席位答。 */
    private static SeatPolicy checking(GameComponent component, Map<String, Integer> seen) {
        SeatPolicy inner = RandomSeatPolicy.indifferent();
        return (SeatPolicy) Proxy.newProxyInstance(SeatPolicy.class.getClassLoader(), new Class<?>[]{SeatPolicy.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("label")) {
                        checkEverything(component, seen);
                    }
                    try {
                        return method.invoke(inner, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static void checkEverything(GameComponent component, Map<String, Integer> seen) {
        Session session = component.requireSession();
        // 模组的窗口：引擎这一刻在等落海 / 押武器，模组那一窗就是开着的
        if (session.overboardPending().isPresent()) {
            component.openOverboardWindow(60_000);
        } else {
            component.setOverboardDeadline(0);
        }
        if (session.contest().filter(c -> c.stage() == Contest.Stage.WEAPONS).isPresent()) {
            component.openContestWindow(60_000);
        } else {
            component.clearContest();
        }
        GameState g = session.state();
        for (CharacterId who : g.bySeat()) {
            Set<String> cards = new LinkedHashSet<>(g.stateOf(who).hand());
            cards.addAll(g.stateOf(who).front());
            if (session.rower().isPresent() && !cards.isEmpty()) {
                seen.merge("划船中有牌在手", 1, Integer::sum);
            }
            for (String card : cards) {
                for (ExperienceView.Action action : ExperienceView.Action.values()) {
                    boolean expected = expected(component, session, who, card, action);
                    CardAvailability.Block got = CardAvailability.check(component, who, card, action);
                    if (expected != (got == CardAvailability.Block.NONE)) {
                        fail("%s：%s 的 %s 用 %s —— Legal 说%s，可用性说 %s".formatted(session.context(), who.value(),
                                card, action, expected ? "能" : "不能", got));
                    }
                    seen.merge(action + (expected ? " 能用" : " 不能用"), 1, Integer::sum);
                    if (expected) {
                        engineAccepts(session, who, card, action);
                    }
                }
            }
        }
    }

    /** 照 {@link Legal} 推出来的答案（外加模组的窗口与轮次），与被测对象不同源。 */
    private static boolean expected(GameComponent component, Session session, CharacterId who, String card,
                                    ExperienceView.Action action) {
        GameState g = session.state();
        if (g.isOver() || !g.canAct(who)) {
            return false;
        }
        return switch (action) {
            case REVEAL -> Legal.reveals(session, who).contains(card);
            case GIVE_HAND -> Legal.gifts(session, who).stream().anyMatch(x -> x.card().equals(card) && !x.fromFront());
            case GIVE_FRONT -> Legal.gifts(session, who).stream().anyMatch(x -> x.card().equals(card) && x.fromFront());
            case OVERBOARD -> session.overboardPending().isPresent()
                    && Legal.overboard(session, who).stream().anyMatch(p -> p.card().equals(card));
            case WEAPON -> session.contest().filter(c -> c.stage() == Contest.Stage.WEAPONS)
                    .map(c -> Legal.weapons(session, who).stream().filter(card::equals).count()
                            > c.committedBy(who).stream().filter(card::equals).count())
                    .orElse(false);
            case PLAY -> {
                if (session.provisions().get(card).effect() instanceof ProvisionEffect.BuffSize) {
                    yield Legal.drinks(session, who).contains(card);
                }
                boolean myTurn = g.phase() == Phase.ACTION && session.nextActor().map(who::equals).orElse(false)
                        && session.contest().isEmpty() && session.rower().isEmpty();
                yield myTurn && Legal.actions(session, who).stream()
                        .anyMatch(c -> c instanceof ActionChoice.Play p && p.card().equals(card));
            }
        };
    }

    /** 能用的那一项在副本上真的做一遍。特殊行动由引擎的 LegalTest 逐项做过，这里不重复。 */
    private static void engineAccepts(Session live, CharacterId who, String card, ExperienceView.Action action) {
        Session copy = live.copy();
        try {
            switch (action) {
                case REVEAL -> copy.reveal(who, card);
                case GIVE_HAND, GIVE_FRONT -> {
                    boolean front = action == ExperienceView.Action.GIVE_FRONT;
                    Gift gift = Legal.gifts(copy, who).stream()
                            .filter(x -> x.card().equals(card) && x.fromFront() == front).findFirst().orElseThrow();
                    copy.giveCard(who, gift.to(), card, front);
                }
                case OVERBOARD -> {
                    Session.OverboardPlay play = Legal.overboard(copy, who).stream()
                            .filter(p -> p.card().equals(card)).findFirst().orElseThrow();
                    copy.playOverboardCard(who, play.target(), card, copy.overboardPending().orElseThrow().token());
                }
                case WEAPON -> copy.commitWeapon(who, card);
                case PLAY -> {
                    if (copy.provisions().get(card).effect() instanceof ProvisionEffect.BuffSize) {
                        copy.drinkRum(who, card);
                    }
                }
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new AssertionError("可用性说能用、引擎却拒绝：%s 的 %s 用 %s —— %s"
                    .formatted(who.value(), card, action, e.getMessage()), e);
        }
    }

    private static Path dataDir() {
        for (Path candidate : List.of(Path.of("data"), Path.of("..", "data"))) {
            if (Files.isRegularFile(candidate.resolve("roster").resolve("default.json"))) {
                return candidate;
            }
        }
        throw new AssertionError("找不到 data/：当前目录 " + Path.of("").toAbsolutePath());
    }
}
