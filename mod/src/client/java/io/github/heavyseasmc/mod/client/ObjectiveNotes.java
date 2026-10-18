package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.state.HudView;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import java.util.List;

/** The same recipient-only goal explanation is used by the hand, rules and score pages. */
final class ObjectiveNotes {
    private ObjectiveNotes() { }

    static List<Text> lines(HudView view) {
        return view.seated() ? view.experience().goals() : List.of();
    }

    static Text detail(HudView view) {
        MutableText text = Text.translatable("heavyseas.objective.title");
        for (Text line : lines(view)) text.append("\n").append(line);
        return text;
    }

    static Text scoreLabel(boolean selfHate, int item) {
        return Text.translatable(switch (item) {
            case 0 -> selfHate ? "heavyseas.score.self_excluded" : "heavyseas.score.self";
            case 1 -> "heavyseas.score.treasure";
            case 2 -> "heavyseas.score.loved";
            case 3 -> selfHate ? "heavyseas.score.other_bodies" : "heavyseas.score.hated";
            default -> throw new IllegalArgumentException("Unknown score item: " + item);
        });
    }
}
