package io.github.heavyseasmc.mod.ui;

import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.Phase;
import net.minecraft.text.Text;

import java.util.List;

/** 服务端播报、主画面和各界面共用的阶段/状态显示映射。 */
public final class GameLabels {
    private static final List<HudPart> PHASE_ICONS = List.of(
            HudPart.IC_SUN, HudPart.IC_CRATE, HudPart.IC_FIST, HudPart.IC_BOAT);

    private GameLabels() { }

    public static HudPart phaseIcon(int index) {
        return PHASE_ICONS.get(index);
    }

    public static Text phaseName(Phase phase) {
        return Text.translatable(switch (phase) {
            case WEATHER -> "heavyseas.phase.weather";
            case PROVISION -> "heavyseas.phase.provision";
            case ACTION -> "heavyseas.phase.action";
            case NAVIGATION -> "heavyseas.phase.navigation";
        });
    }

    public static Text conditionName(Condition condition) {
        return Text.translatable(switch (condition) {
            case CONSCIOUS -> "heavyseas.condition.conscious";
            case UNCONSCIOUS -> "heavyseas.condition.unconscious";
            case DEAD -> "heavyseas.condition.dead";
        });
    }
}
