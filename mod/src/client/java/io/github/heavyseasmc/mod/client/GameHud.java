package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.HudView;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯文字 HUD（方案 §13 的 M1 行）。
 *
 * <h2>只有文字，这是决定不是省事</h2>
 * 本作谈判的核心信息是「<b>谁快死了</b>」，而那一条已经由血条承担（决策 ③：体型映射到生命值，
 * 全船一眼可见，零自定义渲染）。HUD 要补的只是血条说不出的三件事：
 * 第几回合、什么阶段、几只海鸥，外加「我是谁」。
 *
 * <h2>识别词只有一套：职业</h2>
 * 方案 §4.1：HUD 与卡面用同一个词，称呼层已取消。所以这里显示的是「珠宝商」，
 * 不是名字，也不是数值 —— 数值分不开人（陪酒女与小孩两项全同）。
 */
public final class GameHud {

    private static final int MARGIN = 6;
    private static final int LINE_HEIGHT = 10;

    private GameHud() {
    }

    public static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null || client.options.hudHidden) {
            return;
        }
        HudView view = GameComponents.of(client.world).hudView();
        if (!view.active()) {
            return;                          // 没有对局就什么都不画，不留一个空框
        }

        List<Text> lines = new ArrayList<>();
        lines.add(Text.translatable("heavyseas.status.header", view.turn(), phaseName(view.phase()),
                view.gulls(), GameState.GULLS_TO_LAND).formatted(Formatting.GOLD));
        if (view.seated()) {
            lines.add(Text.translatable("heavyseas.hud.you",
                    Text.translatable("heavyseas.character." + view.character()),
                    view.health(), view.maxHealth(),
                    conditionName(view.condition()), view.thirst()));
            if (view.yourTurn()) {
                lines.add(Text.translatable("heavyseas.hud.your_turn").formatted(Formatting.YELLOW));
            }
        } else {
            lines.add(Text.translatable("heavyseas.hud.watching").formatted(Formatting.GRAY));
        }

        int y = MARGIN;
        for (Text line : lines) {
            context.drawTextWithShadow(client.textRenderer, line, MARGIN, y, 0xFFFFFF);
            y += LINE_HEIGHT;
        }
    }

    // 与服务端那三个 switch 同源同理由：拼出来的 lang 键静态扫不到，漏了也不报错。
    private static Text conditionName(Condition condition) {
        return Text.translatable(switch (condition) {
            case CONSCIOUS -> "heavyseas.condition.conscious";
            case UNCONSCIOUS -> "heavyseas.condition.unconscious";
            case DEAD -> "heavyseas.condition.dead";
        });
    }

    private static Text phaseName(Phase phase) {
        return Text.translatable(switch (phase) {
            case PROVISION -> "heavyseas.phase.provision";
            case ACTION -> "heavyseas.phase.action";
            case NAVIGATION -> "heavyseas.phase.navigation";
        });
    }
}
