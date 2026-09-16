package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.game.NavCardText;
import io.github.heavyseasmc.mod.state.ContestView;
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
        lines.add(Text.translatable("heavyseas.status.header", view.turn(), GameScreen.phaseLabel(view),
                view.gulls(), GameState.GULLS_TO_LAND).formatted(Formatting.GOLD));
        // 终局进行中不画划船堆与口渴：对局已经结束，「划船堆 0 张 · 舵手 X」只是停下那一刻的残影（2026-09-16 实拍）。
        boolean endgame = view.endgame().active();
        if (!endgame && (view.phase() == Phase.ACTION || view.phase() == Phase.NAVIGATION)) {
            lines.add(seaLine(view));
        }
        // 口渴结算轮到谁，是**公开**的：全船都该看见在等谁（决策 ⑭ 的同一条 —— 等待要看得见）。
        if (!endgame && view.thirstPrompt().active()) {
            lines.add(thirstLine(view));
        }
        // 换座位 / 抢夺那一场同理：全船都在等它收场，屏幕上却什么都没有的话，看起来就像卡住了（ADR-0023）。
        if (!endgame && view.contest().active()) {
            lines.add(contestLine(view));
        }
        view.sea().revealed().ifPresent(card -> lines.add(Text.translatable("heavyseas.hud.revealed",
                NavCardText.describe(card, view.seats())).formatted(Formatting.AQUA)));
        if (view.seated()) {
            lines.add(Text.translatable("heavyseas.hud.you",
                    Text.translatable("heavyseas.character." + view.character()),
                    view.health(), view.maxHealth(),
                    conditionName(view.condition()), view.thirst()));
            // 终局进行中：翻牌那一面被 Esc 收起之后，得告诉他怎么再开（ADR-0022）。
            if (view.endgame().active()) {
                lines.add(Text.translatable("heavyseas.hud.endgame",
                        HeavySeasClient.actKey().getBoundKeyLocalizedText()).formatted(Formatting.GOLD));
            }
            // 站队与挂武器两面收起来之后不会自己弹回来（见 HeavySeasClient#pollContest）。
            // ❗不写这一行，收起来的人就再也找不回那个界面了 —— 而窗口还在走。
            if (view.myContestChoice()) {
                lines.add(Text.translatable("heavyseas.hud.contest_you",
                        HeavySeasClient.actKey().getBoundKeyLocalizedText()).formatted(Formatting.YELLOW));
            }
            // 举着拳头找人（ADR-0025）：不写这一行，玩家按下「换座位」之后界面一关，就不知道该干什么了。
            if (view.myDesignating()) {
                long left = Math.max(0L, view.designateUntil() - System.currentTimeMillis());
                lines.add(Text.translatable("heavyseas.hud.designating", (left + 999) / 1000,
                        HeavySeasClient.actKey().getBoundKeyLocalizedText()).formatted(Formatting.RED));
            }
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
            // 空手也写：爱恨在手牌那一面里，不写出来就没人知道去哪看自己恨谁（ADR-0022）。
            if (!view.hand().isEmpty() || !view.love().isEmpty()) {
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

    /** 轮到我就说「按哪个键」，轮到别人就说在等谁、还剩几秒。 */
    private static Text thirstLine(HudView view) {
        HudView.Thirst prompt = view.thirstPrompt();
        if (view.myThirstChoice()) {
            return Text.translatable("heavyseas.hud.thirst_choose",
                    HeavySeasClient.actKey().getBoundKeyLocalizedText()).formatted(Formatting.YELLOW);
        }
        long left = Math.max(0L, prompt.deadlineMs() - System.currentTimeMillis());
        return Text.translatable("heavyseas.hud.thirst_waiting",
                Text.translatable("heavyseas.character." + prompt.who()),
                (left + 999) / 1000).formatted(Formatting.AQUA);
    }

    /**
     * 「换座位：X 对 Y · 站队 · N 秒」。
     *
     * <p>❗写的全是<b>公开</b>的那几项：谁对谁 · 哪一段 · 还剩几秒。两边站了谁、体型和多少留给站队那一面 ——
     * HUD 铺不下，而且别人凑过来看屏幕就全知道了（与手牌那一行同一条）。押下的武器一个字都不提：暗牌。
     */
    private static Text contestLine(HudView view) {
        ContestView contest = view.contest();
        Text kind = Text.translatable(contest.kind() == Contest.Kind.STEAL
                ? "heavyseas.contest.kind_steal" : "heavyseas.contest.kind_swap");
        Text attacker = Text.translatable("heavyseas.character." + contest.attacker());
        Text target = Text.translatable("heavyseas.character." + contest.target());
        Text stage = stageName(contest.stage());
        long left = contest.deadlineMs() - System.currentTimeMillis();
        // 窗口没开的那几段（全是替身，由排程一步一步推）不写秒数：写个 0 秒会让人以为卡住了。
        if (contest.waiting() && left > 0) {
            return Text.translatable("heavyseas.hud.contest_countdown", kind, attacker, target, stage,
                    (left + 999) / 1000).formatted(Formatting.RED);
        }
        return Text.translatable("heavyseas.hud.contest", kind, attacker, target, stage)
                .formatted(Formatting.RED);
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

    // 同上一条：把键写全，别拼。
    private static Text stageName(Contest.Stage stage) {
        return Text.translatable(switch (stage) {
            case CONSENT -> "heavyseas.contest.stage_consent";
            case STANCES -> "heavyseas.contest.stage_stances";
            case WEAPONS -> "heavyseas.contest.stage_weapons";
            case PICK -> "heavyseas.contest.stage_pick";
        });
    }

    // 与服务端那三个 switch 同源同理由：拼出来的 lang 键静态扫不到，漏了也不报错。
    private static Text conditionName(Condition condition) {
        return Text.translatable(switch (condition) {
            case CONSCIOUS -> "heavyseas.condition.conscious";
            case UNCONSCIOUS -> "heavyseas.condition.unconscious";
            case DEAD -> "heavyseas.condition.dead";
        });
    }

}
