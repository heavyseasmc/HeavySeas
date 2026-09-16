package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.state.Condition;

import java.util.Objects;

/** 口渴窗口里“本人能不能作决定”的单一判据，服务端授权与客户端弹窗必须共用。 */
public final class ThirstEligibility {

    private ThirstEligibility() {
    }

    public static boolean canChoose(Condition condition, int waters) {
        return Objects.requireNonNull(condition, "condition").canAct() && waters > 0;
    }
}
