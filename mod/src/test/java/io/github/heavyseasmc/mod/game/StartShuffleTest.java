package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.data.NavigationLoader;
import io.github.heavyseasmc.engine.data.ProvisionLoader;
import io.github.heavyseasmc.engine.data.RosterData;
import io.github.heavyseasmc.engine.data.RosterLoader;
import io.github.heavyseasmc.engine.data.WeatherLoader;
import io.github.heavyseasmc.engine.model.Affinities;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.weather.WeatherCard;
import io.github.heavyseasmc.engine.weather.WeatherDeck;
import io.github.heavyseasmc.mod.data.GameData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /seas debug next seed}（ADR-0060）：同一个种子开两局，开局那一刻的一切都相同；换一个种子就不同。
 *
 * <p>用的是 {@code data/} 里的真数据，与模组开局走同一个 {@link StartShuffle}。
 * 「不同种子 → 不同」那一条是正向对照：没有它，「同一个种子 → 相同」在种子根本没被用上时也照样绿。
 */
class StartShuffleTest {

    private static final Path DATA = Path.of("..", "data");

    private static GameData data() {
        RosterData roster = RosterLoader.load(DATA.resolve("roster").resolve("default.json"));
        Path provisions = DATA.resolve("provisions").resolve("default.json");
        List<NavigationCard> navigation = NavigationLoader.load(DATA.resolve("navigation").resolve("default.json"),
                roster.ids(), ProvisionLoader.loadIds(provisions));
        List<WeatherCard> weather = WeatherLoader.load(DATA.resolve("weather").resolve("default.json"));
        return new GameData("test", roster, ProvisionLoader.loadCatalog(provisions), navigation, weather);
    }

    /** 开局那一刻能看到的全部：真人座位次序 · 第一天天候 · 之后的天候次序 · 航海与物资两副牌 · 爱恨 · 局中随机源。 */
    private record Opening(List<CharacterId> seats, String firstWeather, List<String> weatherAfter,
                           List<String> navigation, List<String> provisions, Affinities affinities, long gameSeed) {
    }

    private static Opening open(GameData data, Roster roster, long seed, String fixedFirstWeather) {
        List<CharacterId> seats = roster.survivors().stream().map(Survivor::id).toList();
        StartShuffle.Result r = StartShuffle.shuffle(data, roster, seats, seed);
        WeatherDeck deck = r.table().weather().orElseThrow();
        if (fixedFirstWeather != null) {
            assertTrue(deck.stackNext(fixedFirstWeather), "开局时整副天候都在牌堆里，哪一张都翻得出");
        }
        String first = deck.draw().id();
        return new Opening(r.humanSeats(), first, deck.upcoming().stream().map(WeatherCard::id).toList(),
                r.table().pile().order().stream().map(NavigationCard::id).toList(),
                r.table().provisionPileOrder(), r.affinities(), r.gameSeed());
    }

    @Test
    @DisplayName("同一个种子开两局：第一天天候、三副牌的顺序、爱恨、座位次序完全相同")
    void sameSeedSameOpening() {
        GameData data = data();
        Roster roster = data.roster().preset(8);
        Opening a = open(data, roster, 20261002L, null);
        Opening b = open(data, roster, 20261002L, null);
        assertEquals(a, b);
        assertEquals(data.provisions().deck().size(), a.provisions().size(), "物资牌堆是整副：没在看半副");
    }

    @Test
    @DisplayName("对照：换一个种子，开局就不同")
    void differentSeedDifferentOpening() {
        GameData data = data();
        Roster roster = data.roster().preset(8);
        Opening a = open(data, roster, 20261002L, null);
        Opening c = open(data, roster, 20261003L, null);
        assertNotEquals(a.navigation(), c.navigation());
        assertNotEquals(a.provisions(), c.provisions());
        assertNotEquals(a.gameSeed(), c.gameSeed());
    }

    @Test
    @DisplayName("指定第一天的天候：只把那一张调到顶上，别的一样也不动")
    void fixedFirstWeatherOnlyMovesThatCard() {
        GameData data = data();
        Roster roster = data.roster().preset(6);
        Opening plain = open(data, roster, 7L, null);
        String want = plain.weatherAfter().get(plain.weatherAfter().size() - 1);   // 不调就轮不到它
        assertNotEquals(want, plain.firstWeather());
        Opening fixed = open(data, roster, 7L, want);
        assertEquals(want, fixed.firstWeather());
        assertEquals(plain.navigation(), fixed.navigation());
        assertEquals(plain.provisions(), fixed.provisions());
        assertEquals(plain.affinities(), fixed.affinities());
        assertEquals(plain.weatherAfter().size(), fixed.weatherAfter().size(), "张数不变");
    }
}
