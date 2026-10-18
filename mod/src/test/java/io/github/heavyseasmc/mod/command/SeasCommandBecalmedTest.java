package io.github.heavyseasmc.mod.command;

import io.github.heavyseasmc.engine.weather.WeatherCard;
import io.github.heavyseasmc.engine.weather.WeatherEffect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 无风日的 {@code /seas row}：先给一句人话，不能把引擎的异常抛给「/seas 出错」。
 *
 * <p>判据按<b>效果</b>不按 id —— 只断言 id 是 {@code becalmed} 的话，「认字面 id」的错误实现照样绿，
 * 所以另配一张 id 不同、效果相同的数据包天候（对照）。
 */
class SeasCommandBecalmedTest {

    @Test
    @DisplayName("效果是跳过航海就拦下；id 叫什么无所谓")
    void blocksByEffectNotById() {
        assertTrue(SeasCommand.skipsNavigation(Optional.of(new WeatherCard("becalmed", WeatherEffect.SKIP_NAVIGATION))));
        assertTrue(SeasCommand.skipsNavigation(Optional.of(new WeatherCard("dead_calm", WeatherEffect.SKIP_NAVIGATION))));
    }

    @Test
    @DisplayName("别的效果、或今天没有天候，都不拦（对照）")
    void otherWeatherIsNotBlocked() {
        assertFalse(SeasCommand.skipsNavigation(Optional.of(new WeatherCard("becalmed", WeatherEffect.IGNORE_THIRST))));
        assertFalse(SeasCommand.skipsNavigation(Optional.empty()));
    }
}
