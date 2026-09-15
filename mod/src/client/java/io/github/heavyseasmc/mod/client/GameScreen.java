package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.state.HudView;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * 对局里的界面（补给箱、手牌……）共用的底子。
 *
 * <h2>为什么要有这一层</h2>
 * 2026-09-15 第一次在真实客户端上看，有几处毛病都出在「每一面各自处理」上：
 * HUD 那几行字从界面后面透出来、跟座位轨叠在一起；两面各铺一次底色；两面各算一次卡面能画多大。
 * 这些是每一面都要、而且必须一致的东西，放一处。HUD 认的也是这个类型（见 {@link GameHud}）。
 */
public abstract class GameScreen extends Screen {

    /**
     * 卡面画到多高（物理像素）还算清楚：烘出来的贴图高 840，允许放大一成。
     * 再大就是插值放大，又糊了。贴图不低于 600×840 由构建期的 checkCardTextures 守着。
     */
    private static final double SHARP_CARD_PX_H = 840 * 1.1;

    /** 上一帧的鼠标位置。见 {@link #mouseActuallyMoved}。 */
    private boolean mouseSeen;
    private int lastMouseX;
    private int lastMouseY;

    protected GameScreen(Text title) {
        super(title);
    }

    /**
     * 打开时、以及窗口或界面尺寸每改一次，Minecraft 都会调它。
     *
     * <p>坐标系一变，鼠标没动也会换一个数，所以在这里把鼠标位置忘掉、重新起算。
     * 子类覆写时要先调 {@code super.init()}。
     */
    @Override
    protected void init() {
        mouseSeen = false;
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

    /**
     * 上带：回合 · 阶段 · 海鸥。全船都知道的东西 —— 每一面都要、而且必须长得一样，所以放在这里。
     */
    protected void drawPublicBand(DrawContext context, HudView view, int y) {
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("heavyseas.status.header", view.turn(), phaseName(view.phase()),
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
            case PROVISION -> "heavyseas.phase.provision";
            case ACTION -> "heavyseas.phase.action";
            case NAVIGATION -> "heavyseas.phase.navigation";
        });
    }
}
