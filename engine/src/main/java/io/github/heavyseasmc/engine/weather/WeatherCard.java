package io.github.heavyseasmc.engine.weather;

import java.util.Objects;

/** 一张天候牌；展示文本由客户端 lang 按 {@link #id()} 查。 */
public record WeatherCard(String id, WeatherEffect effect) {
    public WeatherCard {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("天候牌 id 不能为空");
        }
        Objects.requireNonNull(effect, "effect");
    }
}
