package io.github.heavyseasmc.engine.weather;

import io.github.heavyseasmc.engine.data.DataFormatException;
import io.github.heavyseasmc.engine.data.WeatherLoader;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class WeatherTest {
    private static final String JSON = """
            {"schema_version":1,"id":"heavyseas:default","total":2,"cards":[
              {"id":"rain","effect":"ignore_thirst"},
              {"id":"fog","effect":"ignore_gulls"}
            ]}
            """;

    @Test
    void strictLoaderReadsEveryEffect() {
        var real = WeatherLoader.load(Path.of(System.getProperty("heavyseas.data.dir"), "weather/default.json"));
        assertEquals(10, real.size());
        assertEquals(10, real.stream().map(WeatherCard::effect).distinct().count());
    }

    @Test
    void rejectsUnknownFieldsEffectsAndTotals() {
        assertThrows(DataFormatException.class, () -> load(JSON.replace("\"effect\":\"ignore_thirst\"",
                "\"effect\":\"ignore_thirst\",\"silent\":true")));
        assertThrows(DataFormatException.class, () -> load(JSON.replace("ignore_thirst", "unknown")));
        assertThrows(DataFormatException.class, () -> load(JSON.replace("\"total\":2", "\"total\":3")));
    }

    @Test
    void drawDiscardsYesterdayAndCanReshuffle() {
        WeatherDeck deck = new WeatherDeck(load(JSON), new Random(7));
        WeatherCard first = deck.draw();
        WeatherCard second = deck.draw();
        assertNotEquals(first, second);
        assertEquals(1, deck.discarded());
        deck.reshuffleDiscard();
        assertEquals(0, deck.discarded());
        assertEquals(1, deck.remaining());
    }

    private static List<WeatherCard> load(String json) {
        return WeatherLoader.load("fixture", new StringReader(json));
    }
}
