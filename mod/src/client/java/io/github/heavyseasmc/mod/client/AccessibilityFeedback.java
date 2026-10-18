package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.ui.DecisionReminder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;

/**
 * 本机的决策提示音：轮到我答一个决定时一声、快超时时一声。只在自己这一台响，不发给别人。
 *
 * <p>走 {@link VoyageSound} 的提醒通道（总音量 · 闸门），不另起音量（2026-10-13：试合入另加的「界面音量」与绕过闸门的直放已收掉）。
 * 总音量为 0 时不排、不放。轮到我行动那一声由 {@code VoyageSound} 自己响（turn_chime），这里不再响第二声。
 */
final class AccessibilityFeedback {
    private static final DecisionReminder REMINDER = new DecisionReminder();

    private AccessibilityFeedback() { }

    static void tick(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            REMINDER.update("", false, 0);
            return;
        }
        var view = GameComponents.of(client.world).hudView();
        var timer = DecisionNavigation.countdown(view);
        String decision = view.experience().decisionId();
        boolean required = view.seated() && timer != null && !decision.isEmpty();
        long remaining = timer == null ? 0 : GameClock.remaining(timer.deadlineMs());
        DecisionReminder.Signal signal = REMINDER.update(view.experience().matchId() + ":" + decision, required, remaining);
        if (signal == DecisionReminder.Signal.NONE || !VoyageSound.audible(SoundCategory.MASTER)) {
            return;
        }
        if (signal == DecisionReminder.Signal.TURN && !view.myTurnToAct()) {
            VoyageSound.alert("turn_chime", SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 0.38f);
        } else if (signal == DecisionReminder.Signal.URGENT) {
            VoyageSound.alert("decision_urgent", SoundEvents.BLOCK_WOODEN_BUTTON_CLICK_ON, 0.6f);
        }
    }
}
