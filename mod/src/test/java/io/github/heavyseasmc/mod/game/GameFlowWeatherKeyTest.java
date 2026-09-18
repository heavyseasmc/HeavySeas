package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.weather.WeatherCard;
import io.github.heavyseasmc.engine.weather.WeatherEffect;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameFlowWeatherKeyTest {

    @Test
    void dataPackWeatherIdsUseDynamicTranslationKeys() throws Exception {
        WeatherCard card = new WeatherCard("drizzle", WeatherEffect.IGNORE_THIRST);

        assertEquals("heavyseas.weather.drizzle", invokeKey("weatherNameKey", card));
        assertEquals("heavyseas.weather.effect.drizzle", invokeKey("weatherEffectKey", card));
    }

    private static String invokeKey(String name, WeatherCard card) throws Exception {
        Method method = GameFlow.class.getDeclaredMethod(name, WeatherCard.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(null, card);
        } catch (InvocationTargetException failure) {
            throw new AssertionError(failure.getCause());
        }
    }
}
