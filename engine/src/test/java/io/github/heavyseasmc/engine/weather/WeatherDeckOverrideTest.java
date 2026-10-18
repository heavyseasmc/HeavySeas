package io.github.heavyseasmc.engine.weather;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeatherDeckOverrideTest {

    @Test
    void temporaryOverrideDoesNotChangeTheShuffledSequenceOrDeckAccounting() {
        List<WeatherCard> cards = List.of(
                new WeatherCard("rain", WeatherEffect.IGNORE_THIRST),
                new WeatherCard("gale", WeatherEffect.EXTRA_NAVIGATION),
                new WeatherCard("storm", WeatherEffect.ROWERS_OVERBOARD));
        WeatherDeck control = new WeatherDeck(cards, new Random(37));
        WeatherDeck overridden = new WeatherDeck(cards, new Random(37));

        assertEquals(control.draw(), overridden.draw());
        int remaining = overridden.remaining();
        int discarded = overridden.discarded();

        WeatherCard fixture = new WeatherCard("sunday", WeatherEffect.EXTRA_PROVISION);
        overridden.overrideCurrentUntilNextDraw(fixture);
        assertEquals(fixture, overridden.current().orElseThrow());
        assertEquals(remaining, overridden.remaining());
        assertEquals(discarded, overridden.discarded());

        assertEquals(control.draw(), overridden.draw(), "下一次正式抽牌仍须走原随机序列");
        assertEquals(control.remaining(), overridden.remaining());
        assertEquals(control.discarded(), overridden.discarded());
    }
}
