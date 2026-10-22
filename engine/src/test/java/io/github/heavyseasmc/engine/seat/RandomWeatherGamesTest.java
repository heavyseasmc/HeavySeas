package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.data.LocalData;
import io.github.heavyseasmc.engine.sim.Simulator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 随机席位 + 天候的整局（2026-09-25 QA 表 SB-02：「随机模拟未覆盖启用天候的整局」）。
 *
 * <p>原先随机席位只走不翻天候的那几个构造（{@code Session} 跳过天候阶段）；带天候的整局只有动脑替身那一路跑过
 * （{@link SeatSimulationTest}）。这里让随机席位在真实牌堆、真实天候下连跑几百局：每一局都要走完（模拟器自带回合上限，
 * 超了就抛「疑似死锁」），每一步都过模拟器里的跨状态不变量。
 *
 * <p>正向对照：天候确实翻过 —— 不然「全跑完」与「天候阶段一次都没进」输出一样。拿「回合数分布与不翻天候的同种子局不同」作判据。
 */
class RandomWeatherGamesTest {

    @Test
    @DisplayName("随机席位 · 真实 8 人 · 带天候：500 局都走得完，且与不翻天候的同一批种子打出不同的局")
    @Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void randomSeatsWithWeatherFinish() {
        var roster = LocalData.roster().preset(8);
        var seats = SeatPolicies.all(RandomSeatPolicy.indifferent());
        Simulator withWeather = new Simulator(roster, LocalData.navigationDeck(), LocalData.provisions(),
                LocalData.roster().treasureScoring(), seats, LegalTest.weather());
        Simulator noWeather = new Simulator(roster, LocalData.navigationDeck(), LocalData.provisions(),
                LocalData.roster().treasureScoring(), seats, List.of());
        assertTrue(!LegalTest.weather().isEmpty(), "天候牌一张都没读到 —— 判据没在测天候");
        List<Simulator.Result> a = LongStream.range(0, 500).parallel().mapToObj(withWeather::run).toList();
        List<Simulator.Result> b = LongStream.range(0, 500).parallel().mapToObj(noWeather::run).toList();
        assertEquals(500, a.size());
        long differ = LongStream.range(0, 500).filter(i -> a.get((int) i).turns() != b.get((int) i).turns()).count();
        System.out.println("随机席位带天候 500 局：与不翻天候的同种子局回合数不同的有 " + differ + " 局");
        assertTrue(differ > 50, "带天候与不带天候几乎打出同样的局（" + differ + " / 500）—— 天候阶段像是没进去");
    }
}
