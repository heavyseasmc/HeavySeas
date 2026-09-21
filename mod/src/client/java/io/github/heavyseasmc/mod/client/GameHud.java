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
import io.github.heavyseasmc.mod.ui.NotificationSidebarLayout;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

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
 *
 * <h2>色只从 {@link GuiLanguage} 取</h2>
 * 这些字与通知侧栏画在对局界面同一帧里（侧栏是每一面的最后一层）。原先用 Minecraft 自带的 {@code Formatting}
 * 与纯白，于是同一帧上两种金（{@code #FFAA00} 与 {@code #C9A227}）、两种纸色 —— 2026-09-18 审查抓到的。
 * 现在：轮到你 / 你 = 金 · 紧迫 = 朱砂 · 其余 = 纸与次要色，与各面同一张表（ADR-0018 §7.3）。
 */
public final class GameHud {

    private static final int MARGIN = NotificationSidebarLayout.MARGIN;
    /** 普通 HUD 底部留给热栏；对局 Screen 没有热栏，可以用到窗口底。 */
    private static final int HUD_BOTTOM_SAFE = 48;

    /** 一行字与它的颜色。颜色来自 {@link GuiLanguage}，不写进文本的样式里 —— 折行之后每一段照样拿得到。 */
    private record Line(Text text, int color) {
    }

    private GameHud() {
    }

    public static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null || client.options.hudHidden) {
            return;
        }
        // HudRenderCallback 发生在 Screen.renderWithTooltip 之前。对局界面自己的底色会盖住这里画的东西，
        // 所以它们的侧栏交给 GameScreenSidebar 的 afterRender；这里必须彻底跳过，不能画一份在背后。
        if (client.currentScreen instanceof GameScreen) {
            return;
        }
        HudView view = GameComponents.of(client.world).hudView();
        if (!view.active()) {
            return;                          // 没有对局就什么都不画，不留一个空框
        }
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(Text.translatable("heavyseas.status.header", view.turn(), GameScreen.phaseLabel(view),
                view.gulls(), GameState.GULLS_TO_LAND), GuiLanguage.ink()));
        if (!view.weather().isEmpty()) {
            lines.add(new Line(Text.translatable("heavyseas.hud.weather", weatherName(view.weather())),
                    GuiLanguage.muted()));
        }
        // 终局进行中不画划船堆与口渴：对局已经结束，「划船堆 0 张 · 舵手 X」只是停下那一刻的残影（2026-09-16 实拍）。
        boolean endgame = view.endgame().active();
        if (!endgame && (view.phase() == Phase.ACTION || view.phase() == Phase.NAVIGATION)) {
            lines.add(new Line(seaLine(view), GuiLanguage.ink()));
        }
        // 口渴结算轮到谁，是**公开**的：全船都该看见在等谁（决策 ⑭ 的同一条 —— 等待要看得见）。
        if (!endgame && view.thirstPrompt().active()) {
            lines.add(thirstLine(view));
        }
        // 换座位 / 抢夺那一场同理：全船都在等它收场，屏幕上却什么都没有的话，看起来就像卡住了（ADR-0023）。
        if (!endgame && view.contest().active()) {
            lines.add(contestLine(view));
        }
        view.sea().revealed().ifPresent(card -> lines.add(new Line(Text.translatable("heavyseas.hud.revealed",
                NavCardText.describe(card, view.seats())), GuiLanguage.ink())));
        if (view.seated()) {
            // 「你」是金（ADR-0018 §7.3），与各面身份那一行同一个用法。
            lines.add(new Line(Text.translatable("heavyseas.hud.you",
                    Text.translatable("heavyseas.character." + view.character()),
                    view.health(), view.maxHealth(),
                    conditionName(view.condition()), view.thirst()), GuiLanguage.gold()));
            // 终局进行中：翻牌那一面被 Esc 收起之后，得告诉他怎么再开（ADR-0022）。
            if (view.endgame().active()) {
                lines.add(new Line(Text.translatable("heavyseas.hud.endgame",
                        HeavySeasClient.actKey().getBoundKeyLocalizedText()), GuiLanguage.gold()));
            }
            // 站队与挂武器两面收起来之后不会自己弹回来（见 HeavySeasClient#pollContest）。
            // ❗不写这一行，收起来的人就再也找不回那个界面了 —— 而窗口还在走。
            if (view.myContestChoice()) {
                lines.add(new Line(Text.translatable("heavyseas.hud.contest_you",
                        HeavySeasClient.actKey().getBoundKeyLocalizedText()), GuiLanguage.gold()));
            }
            // 举着拳头找人（ADR-0025）：不写这一行，玩家按下「换座位」之后界面一关，就不知道该干什么了。
            // 有倒计时，到点就退回 —— 紧迫，朱砂。
            if (view.myDesignating()) {
                long left = Math.max(0L, view.designateUntil() - System.currentTimeMillis());
                lines.add(new Line(Text.translatable("heavyseas.hud.designating", (left + 999) / 1000,
                        HeavySeasClient.actKey().getBoundKeyLocalizedText()), GuiLanguage.cinnabar()));
            }
            if (view.myTurnToAct()) {
                // 键同样显示实际绑定的那个，理由同下面手牌那一行。
                lines.add(new Line(Text.translatable("heavyseas.hud.your_turn",
                        HeavySeasClient.actKey().getBoundKeyLocalizedText()), GuiLanguage.gold()));
            }
            if (view.myRowPending()) {
                long left = view.sea().rowing().stream()
                        .filter(r -> r.fate() == Session.RowFate.UNDECIDED).count();
                lines.add(new Line(Text.translatable("heavyseas.hud.rowing", left,
                        HeavySeasClient.actKey().getBoundKeyLocalizedText()), GuiLanguage.gold()));
            }
            // ❗手上有牌却没有任何提示，等于没有手牌 —— 玩家不会去猜某个键能开一个界面。
            //   显示的是**实际绑定的那个键**，不是写死的 R：改了键位还说 R 就是在说谎。
            // 空手也写：爱恨在手牌那一面里，不写出来就没人知道去哪看自己恨谁（ADR-0022）。
            if (!view.hand().isEmpty() || !view.love().isEmpty()) {
                lines.add(new Line(Text.translatable("heavyseas.hud.hand", view.hand().size(),
                        HeavySeasClient.handKey().getBoundKeyLocalizedText()), GuiLanguage.ink()));
            }
        } else {
            lines.add(new Line(Text.translatable("heavyseas.hud.watching"), GuiLanguage.muted()));
        }

        // 折行：结算那一行（执行的航海牌）可能比窗口还宽，画出屏幕与没画长得一样。
        NotificationSidebarLayout sidebar = sidebarLayout(context.getScaledWindowWidth(), view);
        int maxWidth = Math.max(80,
                context.getScaledWindowWidth() - sidebar.sidebarWidth() - 3 * MARGIN);
        int y = MARGIN;
        for (Line line : lines) {
            // 压在世界上的字：带阴影。最多三行 —— 结算那一行可能很长，再长就截断，绝不画出屏幕。
            y += GuiText.draw(context, line.text().getString(), MARGIN, y, maxWidth, GuiText.BODY, false,
                    GuiLanguage.onWorld(line.color()), GuiText.Align.LEFT, 3, true);
        }
        drawNotifications(context, client, view.weather(), view.notifications(), sidebar);
    }

    /**
     * 对局界面最后一层的通知侧栏。只由 {@link GameScreenSidebar} 的 afterRender 调用；
     * 放回普通 HUD 回调会被 Screen 的底色盖住。
     */
    static void renderSidebar(DrawContext context, MinecraftClient client) {
        if (client.world == null || client.player == null || client.options.hudHidden
                || !(client.currentScreen instanceof GameScreen)) {
            return;
        }
        HudView view = GameComponents.of(client.world).hudView();
        if (!view.active()) {
            return;
        }
        drawNotifications(context, client, view.weather(), view.notifications(),
                sidebarLayout(context.getScaledWindowWidth(), view));
    }

    /** GameScreen 与实际绘制共用这一份几何；两边各算一遍仍会得到完全相同的边界。 */
    static NotificationSidebarLayout sidebarLayout(int screenWidth, HudView view) {
        boolean visible = view.active() && (!view.notifications().isEmpty() || !view.weather().isEmpty());
        return NotificationSidebarLayout.of(screenWidth, visible);
    }

    /**
     * 系统播报的固定侧栏：最新事件在最上面，保留最多八条，不污染聊天历史。
     *
     * <p>底色就是各面那层 {@link GuiLanguage#BACKDROP}：它画在对局界面之上，另起一个色就是同一帧两种暗色。
     * 播报文本自带的样式（服务端给战斗结算标的红）照原样显示 —— 那是服务端的事。
     */
    private static void drawNotifications(DrawContext context, MinecraftClient client,
                                          String weather, List<Text> notifications,
                                          NotificationSidebarLayout layout) {
        int width = layout.sidebarWidth();
        if ((notifications.isEmpty() && weather.isEmpty()) || width <= 0) {
            return;
        }
        int x = layout.sidebarX();
        int y = MARGIN;
        if (!weather.isEmpty()) {
            int cardW = Math.min(width, 168);
            int cardH = cardW * 5 / 7;
            CardTexture.drawWeather(context, weather, x + (width - cardW) / 2, y, cardW, cardH);
            y += cardH + MARGIN;
        }
        if (notifications.isEmpty()) {
            return;
        }
        int inner = width - 2 * MARGIN;
        // 第一条是栏名（次要色），下面才是播报，最新的在最上。
        List<Text> entries = new ArrayList<>();
        entries.add(Text.translatable("heavyseas.hud.notifications"));
        for (int i = notifications.size() - 1; i >= 0; i--) {
            entries.add(notifications.get(i));
        }
        // 天候卡已经占掉上半截；按剩余高度裁，而不是按整屏高度裁。否则低分辨率下
        // 八条长通知会把侧栏画出屏幕底边。先量后画：放不下的那一条整条不要，不画半条。
        int bottomSafe = client.currentScreen == null ? HUD_BOTTOM_SAFE : 2 * MARGIN;
        int room = context.getScaledWindowHeight() - y - bottomSafe - 2 * MARGIN;
        int used = 0;
        int shown = 0;
        for (Text entry : entries) {
            int h = GuiText.height(entry.getString(), inner, GuiText.BODY, false, MAX_LINES_PER_NOTE);
            if (used + h > room) {
                break;
            }
            used += h;
            shown++;
        }
        if (shown == 0) {
            return;
        }
        GuiMaterial.tag(context, x, y, width, used + 2 * MARGIN);
        int textY = y + MARGIN;
        for (int i = 0; i < shown; i++) {
            Text entry = entries.get(i);
            textY += GuiText.draw(context, entry.getString(), x + MARGIN, textY, inner, GuiText.BODY, false,
                    GuiLanguage.onTag(i == 0 ? GuiLanguage.muted() : noteColor(entry)), GuiText.Align.LEFT, MAX_LINES_PER_NOTE);
        }
    }

    /** 一条播报最多折几行；再长就截断 —— 侧栏是提要，全文该去的地方不是这里。 */
    private static final int MAX_LINES_PER_NOTE = 4;

    /** 播报自带的颜色（服务端给战斗结算标的红）照原样用；没标色的是纸色。 */
    private static int noteColor(Text entry) {
        net.minecraft.text.TextColor c = entry.getStyle().getColor();
        if (c == null && !entry.getSiblings().isEmpty()) {
            c = entry.getSiblings().get(0).getStyle().getColor();
        }
        return c == null ? GuiLanguage.ink() : GuiLanguage.semantic(c.getRgb());
    }

    /**
     * 天候名。拼键：天候 id 来自数据（数据包可以覆盖），代码里没有那张表 ——
     * 构建期的 {@code checkWeatherNames} 按 {@code data/weather} 逐个 id 核对 lang，静态扫描扫不到不要紧。
     * 原先按十个 id 写死的 {@code switch} 在数据包换 id 时会在渲染回调里抛异常（ADR-0032 #2）。
     */
    private static Text weatherName(String id) {
        return Text.translatable("heavyseas.weather." + id);
    }

    /** 轮到我就说「按哪个键」（金），轮到别人就说在等谁、还剩几秒（次要色）。 */
    private static Line thirstLine(HudView view) {
        HudView.Thirst prompt = view.thirstPrompt();
        if (view.myThirstChoice()) {
            return new Line(Text.translatable("heavyseas.hud.thirst_choose",
                    HeavySeasClient.actKey().getBoundKeyLocalizedText()), GuiLanguage.gold());
        }
        if (view.myWaterDonation()) {
            return new Line(Text.translatable("heavyseas.hud.thirst_donate",
                    Text.translatable("heavyseas.character." + prompt.who()),
                    HeavySeasClient.actKey().getBoundKeyLocalizedText()), GuiLanguage.gold());
        }
        long left = Math.max(0L, prompt.deadlineMs() - System.currentTimeMillis());
        return new Line(Text.translatable("heavyseas.hud.thirst_waiting",
                Text.translatable("heavyseas.character." + prompt.who()),
                (left + 999) / 1000), GuiLanguage.muted());
    }

    /**
     * 「换座位：X 对 Y · 站队 · N 秒」。
     *
     * <p>❗写的全是<b>公开</b>的那几项：谁对谁 · 哪一段 · 还剩几秒。两边站了谁、体型和多少留给站队那一面 ——
     * HUD 铺不下，而且别人凑过来看屏幕就全知道了（与手牌那一行同一条）。押下的武器一个字都不提：暗牌。
     * 窗口在走时是朱砂（紧迫）；没窗口的那几段（全是替身，由排程推）只是陈述，纸色。
     */
    private static Line contestLine(HudView view) {
        ContestView contest = view.contest();
        Text kind = Text.translatable(switch (contest.kind()) {
            case SWAP -> "heavyseas.contest.kind_swap";
            case STEAL -> "heavyseas.contest.kind_steal";
            case RATION -> "heavyseas.contest.kind_ration";
        });
        Text attacker = Text.translatable("heavyseas.character." + contest.attacker());
        Text target = Text.translatable("heavyseas.character." + contest.target());
        Text stage = stageName(contest.stage());
        long left = contest.deadlineMs() - System.currentTimeMillis();
        // 窗口没开的那几段（全是替身，由排程一步一步推）不写秒数：写个 0 秒会让人以为卡住了。
        if (contest.waiting() && left > 0) {
            return new Line(Text.translatable("heavyseas.hud.contest_countdown", kind, attacker, target, stage,
                    (left + 999) / 1000), GuiLanguage.cinnabar());
        }
        return new Line(Text.translatable("heavyseas.hud.contest", kind, attacker, target, stage), GuiLanguage.ink());
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
