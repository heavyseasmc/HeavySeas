package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.game.NavCardText;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.HudView;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯文字 HUD（方案 §13 的 M1 行）。
 *
 * <h2>只有文字，这是决定不是省事</h2>
 * 本作谈判的核心信息是「<b>谁快死了</b>」，而那一条已经由血条承担（决策 ③：体型映射到生命值，
 * 全船一眼可见，零自定义渲染）。HUD 要补的只是血条说不出的几件事：
 * 第几回合、什么阶段、几只海鸥，外加「我是谁」。
 *
 * <h2>它只说「有」，不说「是什么」</h2>
 * 手牌这一行只给张数与开界面的键。牌面本身归手牌那一面（{@link HandScreen}）——
 * 把牌名铺在 HUD 上，一是挤，二是<b>别人凑过来看屏幕就全知道了</b>，
 * 而本作的手牌是隐藏信息。
 *
 * <h2>航海这一段（决策 ⑭）</h2>
 * 「划船堆 N 张 · 舵手 X · 倒计时」是全船都看得见的数：张数是公开的，舵手是谁是公开的，
 * 舵手还剩几秒也是公开的 —— 划船堆里是什么不在这里，那一项只进舵手的挑牌一面。
 * 结算之后，被执行的那一张挂在这里，直到下一回合开始（结算后只公开这一张）。
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
        if (client.currentScreen instanceof GameScreen) {
            // 对局界面自带上带（回合 · 阶段 · 海鸥）。两份一起画，HUD 的字会从界面的底色后面透出来、
            // 跟座位轨叠在一起（2026-09-15 真实客户端上看到的）。聊天框等别的界面开着时照常画：
            // 谈判就在聊天里，那时正需要看「轮到谁」。
            return;
        }
        HudView view = GameComponents.of(client.world).hudView();
        if (!view.active()) {
            return;                          // 没有对局就什么都不画，不留一个空框
        }

        List<Text> lines = new ArrayList<>();
        lines.add(Text.translatable("heavyseas.status.header", view.turn(), phaseName(view.phase()),
                view.gulls(), GameState.GULLS_TO_LAND).formatted(Formatting.GOLD));
        if (view.phase() == Phase.ACTION || view.phase() == Phase.NAVIGATION) {
            lines.add(seaLine(view));
        }
        view.sea().revealed().ifPresent(card -> lines.add(Text.translatable("heavyseas.hud.revealed",
                NavCardText.describe(card, view.seats())).formatted(Formatting.AQUA)));
        if (view.seated()) {
            lines.add(Text.translatable("heavyseas.hud.you",
                    Text.translatable("heavyseas.character." + view.character()),
                    view.health(), view.maxHealth(),
                    conditionName(view.condition()), view.thirst()));
            if (view.myTurnToAct()) {
                // 键同样显示实际绑定的那个，理由同下面手牌那一行。
                lines.add(Text.translatable("heavyseas.hud.your_turn",
                        HeavySeasClient.actKey().getBoundKeyLocalizedText()).formatted(Formatting.YELLOW));
            }
            if (view.myRowPending()) {
                long left = view.sea().rowing().stream()
                        .filter(r -> r.fate() == Session.RowFate.UNDECIDED).count();
                lines.add(Text.translatable("heavyseas.hud.rowing", left,
                        HeavySeasClient.actKey().getBoundKeyLocalizedText()).formatted(Formatting.YELLOW));
            }
            // ❗手上有牌却没有任何提示，等于没有手牌 —— 玩家不会去猜某个键能开一个界面。
            //   显示的是**实际绑定的那个键**，不是写死的 H：改了键位还说 H 就是在说谎。
            if (!view.hand().isEmpty()) {
                lines.add(Text.translatable("heavyseas.hud.hand", view.hand().size(),
                        HeavySeasClient.handKey().getBoundKeyLocalizedText()));
            }
        } else {
            lines.add(Text.translatable("heavyseas.hud.watching").formatted(Formatting.GRAY));
        }

        // 折行：结算那一行（执行的航海牌）可能比窗口还宽，画出屏幕与没画长得一样。
        int maxWidth = Math.max(80, context.getScaledWindowWidth() - 2 * MARGIN);
        int y = MARGIN;
        for (Text line : lines) {
            for (OrderedText part : client.textRenderer.wrapLines(line, maxWidth)) {
                context.drawTextWithShadow(client.textRenderer, part, MARGIN, y, 0xFFFFFF);
                y += LINE_HEIGHT;
            }
        }
    }

    /** 「划船堆 N 张 · 舵手 X」，舵手在挑牌时再加「· N 秒」。 */
    private static Text seaLine(HudView view) {
        HudView.Sea sea = view.sea();
        Text helm = sea.helmsman().isEmpty()
                ? Text.literal("—")
                : Text.translatable("heavyseas.character." + sea.helmsman());
        long left = sea.helmDeadlineMs() - System.currentTimeMillis();
        if (sea.helmDeadlineMs() > 0 && left > 0) {
            return Text.translatable("heavyseas.hud.sea_countdown", sea.rowStack(), helm, (left + 999) / 1000);
        }
        return Text.translatable("heavyseas.hud.sea", sea.rowStack(), helm);
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
