package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.mod.net.CardActionC2S;
import io.github.heavyseasmc.mod.state.ExperienceView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 包里的动作（{@link CardActionC2S.Kind}）与手牌一面的用法（{@link ExperienceView.Action}）的对应表。
 *
 * <p>守的缺陷：两个枚举按名字互转（{@code valueOf(kind.name())}）。哪一边加个常量或改个名，编译照过，
 * 收包时才抛 —— 「不用」那一项就是这样：它没有对应的用法，按名字转会在运行时炸。
 * 期望表写在这里（与被测的 switch 不同源）；包里每加一种动作，这里不登记就红。
 */
final class CardActionsMappingTest {

    private static final Map<CardActionC2S.Kind, Optional<ExperienceView.Action>> EXPECTED = expected();

    private static Map<CardActionC2S.Kind, Optional<ExperienceView.Action>> expected() {
        Map<CardActionC2S.Kind, Optional<ExperienceView.Action>> m = new EnumMap<>(CardActionC2S.Kind.class);
        m.put(CardActionC2S.Kind.REVEAL, Optional.of(ExperienceView.Action.REVEAL));
        m.put(CardActionC2S.Kind.GIVE_HAND, Optional.of(ExperienceView.Action.GIVE_HAND));
        m.put(CardActionC2S.Kind.GIVE_FRONT, Optional.of(ExperienceView.Action.GIVE_FRONT));
        m.put(CardActionC2S.Kind.OVERBOARD, Optional.of(ExperienceView.Action.OVERBOARD));
        m.put(CardActionC2S.Kind.OVERBOARD_DONE, Optional.empty());
        return m;
    }

    @Test
    @DisplayName("包里的每一种动作都登记了对应的用法，且与收包那一侧的对应一致；不同动作不对到同一种用法")
    void everyKindHasAnExplicitAction() {
        assertEquals(EnumSet.allOf(CardActionC2S.Kind.class), EXPECTED.keySet(),
                "包里加了一种动作，这里没登记它对应哪一种用法");
        Set<ExperienceView.Action> seen = EnumSet.noneOf(ExperienceView.Action.class);
        for (CardActionC2S.Kind kind : CardActionC2S.Kind.values()) {
            assertEquals(EXPECTED.get(kind), CardActions.action(kind), "收包那一侧把 " + kind + " 对错了");
            EXPECTED.get(kind).ifPresent(action -> assertTrue(seen.add(action), action + " 被两种动作共用"));
        }
    }

    @Test
    @DisplayName("引擎的拒绝按异常类型说：状态类说「不是这个时候」，参数类说「这张牌不对」—— 不再一律说「目标不适用」")
    void engineRefusalsAreMappedByType() {
        assertEquals(CardAvailability.Block.WRONG_TIMING, CardActions.refusal(new IllegalStateException("x")));
        assertEquals(CardAvailability.Block.CARD_CHANGED, CardActions.refusal(new IllegalArgumentException("x")));
    }
}
