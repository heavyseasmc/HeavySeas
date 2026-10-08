package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.model.CharacterId;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SeatRandomIsolationTest {
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
