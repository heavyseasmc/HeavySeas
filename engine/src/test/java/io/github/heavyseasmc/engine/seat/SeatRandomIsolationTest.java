package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.model.CharacterId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SeatRandomIsolationTest {
    @Test
    @DisplayName("行动阶段也用各座的随机流：某座多消耗随机数，不推进游戏流、不改变其他座的样本")
    void actionPhaseRandomsAreIsolated() {
        Trace normal = actions(0);
        Trace noisy = actions(1000);
        assertEquals(8, normal.samples().size(), "必须确实问过八个座位的行动");
        assertEquals(8, new java.util.HashSet<>(noisy.supplied()).size(), "行动阶段也必须每座一条流");
        assertTrue(noisy.supplied().stream().noneMatch(r -> r == noisy.game()), "行动提问拿到了游戏随机流");
        assertEquals(0, noisy.extraGameCalls(), "行动提问消耗了游戏随机流");
        CharacterId first = normal.samples().keySet().iterator().next();
        assertNotEquals(normal.samples().get(first), noisy.samples().get(first), "对照：第一座真的多消耗过随机数");
        normal.samples().forEach((seat, value) -> {
            if (!seat.equals(first)) assertEquals(value, noisy.samples().get(seat), seat.value());
        });
        assertEquals(normal.samples(), actions(0).samples(), "相同种子仍然可复现");
    }

    private static Trace actions(int extraConsumption) {
        Scenario scenario = new Scenario(Map.of("water", 8), 17).toAction();
        CountingRandom game = new CountingRandom(91);
        Map<CharacterId, SeatPolicy> policies = new LinkedHashMap<>();
        Map<CharacterId, Integer> samples = new LinkedHashMap<>();
        List<Random> supplied = new ArrayList<>();
        CharacterId first = scenario.session.state().bySeat().getFirst();
        for (CharacterId who : scenario.session.state().bySeat()) {
            policies.put(who, (SeatPolicy) Proxy.newProxyInstance(SeatPolicy.class.getClassLoader(),
                    new Class<?>[]{SeatPolicy.class}, (proxy, method, args) -> {
                    if (method.getName().equals("reveal") || method.getName().equals("drink")
                            || method.getName().equals("give")) return java.util.Optional.empty();
                    assertEquals("act", method.getName(), "夹具只应走行动，不应进入争夺或航海");
                    Random random = (Random) args[2];
                    supplied.add(random);
                    if (who.equals(first)) {
                        for (int i = 0; i < extraConsumption; i++) random.nextLong();
                    }
                    samples.put(who, random.nextInt());
                    return ActionChoice.PASS;
            }));
        }
        SeatDriver driver = new SeatDriver(scenario.session, policies, game);
        int before = game.calls;
        driver.playPhase();
        return new Trace(samples, game.calls - before, supplied, game);
    }

    private static final class CountingRandom extends Random {
        int calls;
        CountingRandom(long seed) { super(seed); }
        @Override protected int next(int bits) { calls++; return super.next(bits); }
    }

    private record Trace(Map<CharacterId, Integer> samples, int extraGameCalls, List<Random> supplied, Random game) { }

    private static Trace run(int extraConsumption) {
        Scenario scenario = new Scenario(Map.of("water", 8), 17);
        CountingRandom game = new CountingRandom(91);
        Map<CharacterId, SeatPolicy> policies = new LinkedHashMap<>();
        Map<CharacterId, Integer> samples = new LinkedHashMap<>();
        List<Random> supplied = new ArrayList<>();
        CharacterId first = scenario.session.state().bySeat().getFirst();
        for (CharacterId who : scenario.session.state().bySeat()) {
            SeatPolicy policy = (SeatPolicy) Proxy.newProxyInstance(SeatPolicy.class.getClassLoader(),
                    new Class<?>[]{SeatPolicy.class}, (proxy, method, args) -> {
                        assertEquals("keepProvision", method.getName());
                        Random random = (Random) args[2];
                        supplied.add(random);
                        if (who.equals(first)) {
                            for (int i = 0; i < extraConsumption; i++) { random.nextLong(); }
                        }
                        samples.put(who, random.nextInt());
                        return ((List<?>) args[1]).getFirst();
                    });
            policies.put(who, policy);
        }
        SeatDriver driver = new SeatDriver(scenario.session, policies, game);
        int before = game.calls;
        driver.playPhase();
        return new Trace(samples, game.calls - before, supplied, game);
    }

    @Test
    void policiesNeverReceiveOrConsumeTheGamesRandomInstance() {
        Trace trace = run(1000);
        assertEquals(8, trace.supplied().size());
        assertTrue(trace.supplied().stream().noneMatch(random -> random == trace.game()));
        assertEquals(8, new java.util.HashSet<>(trace.supplied()).size(), "每座一个随机流");
        assertEquals(0, trace.extraGameCalls(), "策略多摇骰子不能推进发牌/摸暗牌用的流");
    }

    @Test
    void oneSeatsExtraRandomConsumptionDoesNotChangeOtherSeats() {
        Trace normal = run(0);
        Trace noisy = run(1000);
        CharacterId first = normal.samples().keySet().iterator().next();
        assertNotEquals(normal.samples().get(first), noisy.samples().get(first), "正向对照：这位确实多摇了骰子");
        normal.samples().forEach((seat, sample) -> {
            if (!seat.equals(first)) { assertEquals(sample, noisy.samples().get(seat), seat.value()); }
        });
        assertEquals(normal.samples(), run(0).samples(), "同一种子仍可复现");
    }
}
