package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.HudView;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 对局里的界面（补给箱、手牌……）共用的底子。
 *
 * <h2>为什么要有这一层</h2>
 * 2026-09-15 第一次在真实客户端上看，有几处毛病都出在「每一面各自处理」上：
 * HUD 那几行字从界面后面透出来、跟座位轨叠在一起；两面各铺一次底色；两面各算一次卡面能画多大。
 * 这些是每一面都要、而且必须一致的东西，放一处。HUD 认的也是这个类型（见 {@link GameHud}）。
 */
public abstract class GameScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /**
     * 卡面画到多高（物理像素）还算清楚：烘出来的贴图高 840，允许放大一成。
     * 再大就是插值放大，又糊了。贴图不低于 600×840 由构建期的 checkCardTextures 守着。
     */
    private static final double SHARP_CARD_PX_H = 840 * 1.1;

    /** 上一帧的鼠标位置。见 {@link #mouseActuallyMoved}。 */
    private boolean mouseSeen;
    private int lastMouseX;
    private int lastMouseY;

    /** 这一面打开过了没有。见 {@link #init()}。 */
    private boolean announced;

    protected GameScreen(Text title) {
        super(title);
    }

    /**
     * 打开时、以及窗口或界面尺寸每改一次，Minecraft 都会调它。
     *
     * <p>坐标系一变，鼠标没动也会换一个数，所以在这里把鼠标位置忘掉、重新起算。
     * 子类覆写时要先调 {@code super.init()}。
     *
     * <p>第一次调时打一行「界面：打开 X」—— GUI 回归靠它判「这一面到底弹没弹」：
     * 截图里看不到它，与「没弹」和「弹了但被别的顶掉」长得一样（与语言无关，用类名）。
     */
    @Override
    protected void init() {
        mouseSeen = false;
        if (!announced) {
            announced = true;
            LOGGER.info("界面：打开 {}", getClass().getSimpleName());
        }
    }

    /** 当前世界的对局投影；没有世界时是 {@link HudView#IDLE}。 */
    protected HudView projection() {
        return client == null || client.world == null ? HudView.IDLE : GameComponents.of(client.world).hudView();
    }

    /**
     * 上带下面那一行：划船堆几张 · 舵手是谁（决策 ⑭）。全船都知道的数 —— 划船与舵手两面都画，放在这里。
     */
    protected void drawSeaLine(DrawContext context, HudView view, int y) {
        drawSeaLine(context, view, y, view.sea().rowStack());
    }

    /**
     * 同上，但张数由调用方给。
     *
     * <p>舵手一面用它：挑中那一刻服务端就把划船堆整堆收回了，投影里的张数<b>当场变 0</b>，
     * 而屏幕上那几张还在（「顿」要播完 396ms）。照投影画的话，这段时间里这一行说 0 张、底下摆着 2 张
     * —— 实拍到的。这一面该说的是<b>它正摊开的那一叠</b>。
     */
    protected void drawSeaLine(DrawContext context, HudView view, int y, int rowStack) {
        Text helm = view.sea().helmsman().isEmpty()
                ? Text.literal("—")
                : Text.translatable("heavyseas.character." + view.sea().helmsman());
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("heavyseas.hud.sea", rowStack, helm),
                width / 2, y, GuiLanguage.MUTED);
    }

    /**
     * 和上一帧比，鼠标有没有真的动过。第一帧只记下位置，不算移动。
     *
     * <p>❗悬停跟随只能看<b>移动</b>，不能看<b>位置</b>。停着不动的指针不算「指向」：
     * 界面弹出来时指针恰好停在某张牌上，默认答案就被它悄悄换掉；按方向键移走的选中，
     * 下一帧又被停着的指针拽回去，键盘等于失灵。2026-09-15 真实客户端上看到的是前一种 ——
     * 窗口最大化时指针几乎总停在窗口里，补给箱一开，高亮就在第 4 张而不是第 1 张。
     */
    protected boolean mouseActuallyMoved(int x, int y) {
        boolean moved = mouseSeen && (x != lastMouseX || y != lastMouseY);
        mouseSeen = true;
        lastMouseX = x;
        lastMouseY = y;
        return moved;
    }

    @Override
    public boolean shouldPause() {
        return false;                  // 多人游戏里暂停毫无意义：别人还在等你，服务端的计时也不会停
    }

    /** 模糊背景加同一层底色（ADR-0018 §7.3：GUI 与牌面必须是同一个世界）。 */
    protected void renderBackdrop(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.fill(0, 0, width, height, GuiLanguage.BACKDROP);
    }

    /**
     * 卡面最多画多高（GUI 单位）。
     *
     * <p>❗按<b>物理像素</b>算，不按 GUI 单位：视频设置里的界面尺寸设成 1 时一个单位是一个像素，
     * 设成 4 时是四个 —— 同样 150 个单位高的卡，前者 150 像素、后者 600 像素。清不清楚只看像素。
     * 界面尺寸不一定是「自动」，所以版面里别的上限也不能写成固定的单位数。
     */
    protected int sharpCardHeight() {
        double scale = client == null ? 1.0 : client.getWindow().getScaleFactor();
        return (int) Math.floor(SHARP_CARD_PX_H / Math.max(1.0, scale));
    }

    /** 倒计时那条细横杠有多高。**会超时的界面都画它，不会超时的界面一律不画**（ADR-0018 §7.4）。 */
    protected static final int BAR_H = 3;

    /** 按钮的内边距与两侧留白。按钮长什么样也是「必须一致」的那一类，所以同样放在这里。 */
    protected static final int BTN_PAD_X = 10;
    protected static final int BTN_PAD_Y = 6;
    protected static final int BTN_SIDE = 20;

    /** 一个按钮排在哪。 */
    protected record Box(int x, int y, int w, int h) {

        public boolean contains(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /**
     * 倒计时：一条细横杠加一行秒数。
     *
     * <p>❗<b>总长由调用方给，而且要给「这一段本来有多长」</b>，不是某个常量：站队段有人加入会把
     * 15 秒重置成 8 秒，照 15 秒画的杠会从一半开始走 —— 而它看起来完全正常。
     *
     * <p>「什么时候算紧迫」取自 {@link GuiLanguage#urgencyThreshold}：朱砂只给最后一段，
     * 一直红着就喊不动了。
     */
    protected void drawCountdown(DrawContext context, long now, long deadlineMs, long totalMs,
                                 int barX, int barY, int barW, int textY) {
        long left = Math.max(0L, deadlineMs - now);
        long total = Math.max(1L, totalMs);
        float frac = MathHelper.clamp(left / (float) total, 0f, 1f);
        boolean urgent = left <= GuiLanguage.urgencyThreshold(total);
        context.fill(barX, barY, barX + barW, barY + BAR_H, GuiLanguage.GROUND);
        context.fill(barX, barY, barX + Math.round(barW * frac), barY + BAR_H,
                urgent ? GuiLanguage.CINNABAR : GuiLanguage.VERDIGRIS);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(String.format("%.1fs", left / 1000f)),
                width / 2, textY, urgent ? GuiLanguage.CINNABAR : GuiLanguage.MUTED);
    }

    /**
     * 一排按钮排在哪：一行放得下就并排居中，放不下就一行一个。
     *
     * <p>❗宽度按 {@code textRenderer} 量出来的字宽算，不写死 —— 译名长度各语言不同，
     * 写死的那一版只在中文下看着是居中的（英文的「Join the defending side」在窄窗口里一行放不下三个）。
     */
    protected java.util.List<Box> layoutButtonRow(java.util.List<Text> labels, int top, int gap) {
        int h = textRenderer.fontHeight + 2 * BTN_PAD_Y;
        int[] w = new int[labels.size()];
        int total = -gap;
        for (int i = 0; i < labels.size(); i++) {
            w[i] = textRenderer.getWidth(labels.get(i)) + 2 * BTN_PAD_X;
            total += w[i] + gap;
        }
        java.util.List<Box> out = new ArrayList<>(labels.size());
        if (total <= width - 2 * BTN_SIDE) {
            int x = (width - total) / 2;
            for (int i = 0; i < labels.size(); i++) {
                out.add(new Box(x, top, w[i], h));
                x += w[i] + gap;
            }
            return out;
        }
        int y = top;
        for (int i = 0; i < labels.size(); i++) {
            out.add(new Box((width - w[i]) / 2, y, w[i], h));
            y += h + gap;
        }
        return out;
    }

    /**
     * 指针落在第几个按钮上；都不在时 {@code -1}。
     *
     * <p>上边界把「抬」起来的那几像素算进去：抬起来的按钮，指针停在它顶上那一截时仍然算指着它。
     */
    protected static int indexAt(java.util.List<Box> boxes, int mouseX, int mouseY) {
        for (int i = 0; i < boxes.size(); i++) {
            Box b = boxes.get(i);
            if (mouseX >= b.x() && mouseX < b.x() + b.w()
                    && mouseY >= b.y() - GuiLanguage.LIFT_PX && mouseY < b.y() + b.h()) {
                return i;
            }
        }
        return -1;
    }

    /** 这一排按钮总共占多高。版面按它往下排，免得下一行压上来。 */
    protected static int rowHeight(java.util.List<Box> boxes) {
        int bottom = 0;
        int top = Integer.MAX_VALUE;
        for (Box b : boxes) {
            top = Math.min(top, b.y());
            bottom = Math.max(bottom, b.y() + b.h());
        }
        return boxes.isEmpty() ? 0 : bottom - top;
    }

    /** 一个按钮：底 · 金框（选中 =「你选的那个」）· 居中的字。与行动一面同一个样子。 */
    protected void drawButton(DrawContext context, Box b, Text label, boolean focused, int color, float lift) {
        context.getMatrices().push();
        context.getMatrices().translate(0, -lift, 0);
        context.fill(b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), GuiLanguage.GROUND);
        if (focused) {
            context.drawBorder(b.x() - 1, b.y() - 1, b.w() + 2, b.h() + 2, GuiLanguage.GOLD);
        }
        context.drawCenteredTextWithShadow(textRenderer, label, b.x() + b.w() / 2,
                b.y() + (b.h() - textRenderer.fontHeight) / 2 + 1, color);
        context.getMatrices().pop();
    }

    /**
     * 角色名。
     *
     * <p>❗这是唯一一处<b>拼出来</b>的 lang 键（角色 id 来自数据，代码里没有那张表），
     * 所以构建期的 {@code checkLangKeys} 单独按 {@code data/roster} 核对这一族。
     */
    protected static Text nameOf(String characterId) {
        return characterId.isEmpty() ? Text.literal("—") : Text.translatable("heavyseas.character." + characterId);
    }

    /**
     * 上带：回合 · 阶段 · 海鸥。全船都知道的东西 —— 每一面都要、而且必须长得一样，所以放在这里。
     */
    protected void drawPublicBand(DrawContext context, HudView view, int y) {
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("heavyseas.status.header", view.turn(), phaseLabel(view),
                        view.gulls(), GameState.GULLS_TO_LAND),
                width / 2, y, GuiLanguage.MUTED);
        // 海鸥画成格子而不是数字：够不够 4 只是一眼的事，不该让人去读。
        int pip = 7;
        int gap = 4;
        int span = GameState.GULLS_TO_LAND * pip + (GameState.GULLS_TO_LAND - 1) * gap;
        int x = (width - span) / 2;
        for (int i = 0; i < GameState.GULLS_TO_LAND; i++) {
            int left = x + i * (pip + gap);
            context.fill(left, y + 12, left + pip, y + 12 + pip,
                    i < view.gulls() ? GuiLanguage.VERDIGRIS : GuiLanguage.GROUND);
        }
    }

    /**
     * 身份行：你是谁、还剩多少。
     *
     * <p>两段颜色不同（身份是金，体力可能是朱砂），所以分两次画；
     * 位置按 {@code textRenderer} 量出来的宽度排，不写死偏移量 ——
     * 译名长度各语言不同，写死的那一版只在中文下看着是居中的。
     */
    protected void drawIdentity(DrawContext context, HudView view, int y) {
        Text who = Text.translatable("heavyseas.character." + view.character());
        Text vitals = Text.translatable("heavyseas.hand.vitals", view.health(), view.maxHealth(),
                conditionName(view.condition()), view.thirst());
        Text sep = Text.literal(" · ");
        int wWho = textRenderer.getWidth(who);
        int wSep = textRenderer.getWidth(sep);
        int x = (width - (wWho + wSep + textRenderer.getWidth(vitals))) / 2;
        context.drawTextWithShadow(textRenderer, who, x, y, GuiLanguage.GOLD);
        context.drawTextWithShadow(textRenderer, sep, x + wWho, y, GuiLanguage.DIM);
        // 体力见底或者已经昏迷才用朱砂 —— 它只给紧迫与伤害，
        // 当强调色用的话，真紧迫那一刻就喊不动了。
        context.drawTextWithShadow(textRenderer, vitals, x + wWho + wSep, y,
                view.health() <= 1 || view.condition() != Condition.CONSCIOUS
                        ? GuiLanguage.CINNABAR : GuiLanguage.MUTED);
    }

    /**
     * 上带与 HUD 里的「阶段」：终局进行中写「终局」（ADR-0022）。
     *
     * <p>对局停在哪个阶段结束的，终局一开始就没有意义了 —— 2026-09-16 实拍：翻牌那一面顶上写着「行动阶段」。
     */
    static Text phaseLabel(HudView view) {
        return view.endgame().active() ? Text.translatable("heavyseas.phase.endgame") : phaseName(view.phase());
    }

    // 下面两个用 switch 而不是拼字符串：拼出来的 lang 键静态扫不到，漏了也不报错。
    protected static Text conditionName(Condition condition) {
        return Text.translatable(switch (condition) {
            case CONSCIOUS -> "heavyseas.condition.conscious";
            case UNCONSCIOUS -> "heavyseas.condition.unconscious";
            case DEAD -> "heavyseas.condition.dead";
        });
    }

    protected static Text phaseName(Phase phase) {
        return Text.translatable(switch (phase) {
            case WEATHER -> "heavyseas.phase.weather";
            case PROVISION -> "heavyseas.phase.provision";
            case ACTION -> "heavyseas.phase.action";
            case NAVIGATION -> "heavyseas.phase.navigation";
        });
    }
}
