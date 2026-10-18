package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.GameState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 驱动者（{@link SeatDriver}）自己的几条：问谁、问到什么时候为止。 */
class SeatDriverTest {

    /** 轮到时能打信号枪就打，否则什么也不做；记下有没有人在靠岸之后还被问「行动」。 */
    static final class Signaller implements SeatPolicy {
        final List<CharacterId> askedAfterLanding = new ArrayList<>();

        @Override
        public ActionChoice act(SeatView view, List<ActionChoice> legal, Random rng) {
            if (view.gulls() >= GameState.GULLS_TO_LAND) {
                askedAfterLanding.add(view.self());
            }
            ActionChoice fire = new ActionChoice.Play("flare_gun", Optional.empty());
            return legal.contains(fire) ? fire : ActionChoice.PASS;
        }

        @Override
        public String keepProvision(SeatView view, List<String> offer, Random rng) {
            return offer.getFirst();
        }

        @Override
        public Optional<String> reveal(SeatView view, List<String> revealable, Random rng) {
            return Optional.empty();
        }

        @Override
        public Optional<String> drink(SeatView view, List<String> drinkable, Random rng) {
            return Optional.empty();
        }

        @Override
        public int keepRowCard(SeatView view, List<NavigationCard> drawn, Random rng) {
            return 0;
        }

        @Override
        public boolean refuse(SeatView view, Random rng) {
            return false;
        }

        @Override
        public Optional<Fight.Side> joinStance(SeatView view, Random rng) {
            return Optional.empty();
        }

        @Override
        public List<String> commitWeapons(SeatView view, List<String> weapons, Random rng) {
            return List.of();
        }

        @Override
        public PickChoice pick(SeatView view, List<PickChoice> legal, Random rng) {
            return legal.getFirst();
        }

        @Override
        public NavigationCard steer(SeatView view, List<NavigationCard> rowStack, Random rng) {
            return rowStack.getFirst();
        }

        @Override
        public WaterPlan drinkWater(SeatView view, int ownUnits, Random rng) {
            return new WaterPlan(0, false);
        }

        @Override
        public String label() {
            return "能打信号枪就打";
        }
    }

    @Test
    @DisplayName("❗行动阶段打信号枪凑满海鸥：这一局当场结束，后面没轮到的人不再被问行动（审查 R1，与模组 GameFlow.finishAction 同一个口径）")
    void stopsAtLanding() {
        // 一副每张都带一只海鸥的航海牌：信号枪抽 3 张，第一张就凑满。
        List<NavigationCard> gulls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            gulls.add(Scenario.card("g" + i, 1, List.of(), List.of()));
        }
        Scenario sc = new Scenario(Map.of("flare_gun", 1, "water", 10), 1, gulls).toAction();
        Session s = sc.session;
        CharacterId first = s.nextActor().orElseThrow();
        sc.deal(first, "flare_gun");
        s.debugSetGulls(GameState.GULLS_TO_LAND - 1);

        Signaller p = new Signaller();
        Map<CharacterId, SeatPolicy> seats = new LinkedHashMap<>();
        for (Survivor sv : sc.roster.survivors()) {
            seats.put(sv.id(), p);
        }
        new SeatDriver(s, seats, new Random(1)).finishPhase();

        assertEquals(GameState.Outcome.LANDED, s.state().outcome().orElseThrow(), "正向对照：信号枪真的让艇靠了岸");
        assertTrue(s.state().stateOf(first).actedThisTurn(), "打信号枪的那一下记下了");
        assertEquals(List.of(), p.askedAfterLanding, "靠岸之后还在问这几个人行动");
        CharacterId second = s.state().bySeat().get(s.state().bySeat().indexOf(first) + 1);
        assertFalse(s.state().stateOf(second).actedThisTurn(), "下一位没轮到");
    }
}
