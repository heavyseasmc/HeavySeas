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
 *
 * <h2>版面的数只在这里写一次</h2>
 * 2026-09-18 一次风格审查抓到：{@code TOP_BAND_Y = 12} 在 10 个界面各写一遍，卡高上限三种值、
 * 金框两种粗细、倒计时条两份实现 —— 没有一处报错，只是已经各自漂开了。
 * {@link GuiLanguage} 为动词与色做过的事，这里为带位、卡的留空与命中、按钮与倒计时再做一次：
 * <b>各面只许引用，不许再声明</b>。{@code GuiConsistencyTest} 扫源码，又写一份就红（ADR-0033）。
 */
public abstract class GameScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /**
     * 卡面画到多高（物理像素）还算清楚：烘出来的贴图高 840，允许放大一成。
     * 再大就是插值放大，又糊了。贴图不低于 600×840 由构建期的 checkCardTextures 守着。
     */
    private static final double SHARP_CARD_PX_H = 840 * 1.1;

    // ---------------------------------------------------------------- 带位（ADR-0018 §7.1：各面同一套）

    /** 上带（回合 · 阶段 · 海鸥）画在哪一行。 */
    protected static final int TOP_BAND_Y = 12;
    /** 上带占掉的高度：一行字加一排海鸥格。 */
    protected static final int TOP_BAND_H = 34;
    /** 上带下面那一行（划船堆 · 舵手，或座位轨）画在哪一行。 */
    protected static final int SEA_LINE_Y = 38;
    /** 两侧留白。 */
    protected static final int SIDE = 20;
    /** 一排卡里相邻两张之间。 */
    protected static final int CARD_GAP = 6;
    /** 一排按钮里相邻两个之间。 */
    protected static final int BTN_GAP = 6;
    /** 舞台（那一排卡）到倒计时横杠。 */
    protected static final int BELOW_CARDS = 6;
    /** 横杠到秒数。 */
    protected static final int BAR_TO_TEXT = 3;
    /** 秒数到下面第一行说明。 */
    protected static final int HINT_GAP = 5;
    /** 倒计时那条细横杠有多高。**会超时的界面都画它，不会超时的界面一律不画**（ADR-0018 §7.4）。 */
    protected static final int BAR_H = 3;
    /** 座位轨：一行字，下面一条线。行动一面与补给箱都画它。 */
    protected static final int RAIL_H = 12;

    /** 选中金框画在卡外几像素。手牌与补给箱曾一个 1、一个 2 —— 同一个标记两种粗细（审查抓到的）。 */
    protected static final int CARD_FRAME = 2;
    /** 卡顶要留的空里，金框加余量占多少。 */
    protected static final int BORDER_ROOM = CARD_FRAME + 4;
    /** 窗口小到离谱时卡也不能缩没了 —— 缩没了与「没有牌」长得一样。 */
    protected static final int MIN_CARD_H = 24;
    /** 卡最高占屏幕高的这个比例：再高就把上下带挤没了。可调（ADR-0018 §8 右列），但只此一处。 */
    protected static final float MAX_CARD_H_RATIO = 0.5f;

    /** 按钮的内边距与两侧留白。按钮长什么样也是「必须一致」的那一类，所以同样放在这里。 */
    protected static final int BTN_PAD_X = 10;
    protected static final int BTN_PAD_Y = 6;
    protected static final int BTN_SIDE = 20;

    /** 上一帧的鼠标位置。见 {@link #mouseActuallyMoved}。 */
    private boolean mouseSeen;
    private int lastMouseX;
    private int lastMouseY;

    /** 上一帧的墙钟。见 {@link #frameDelta}。 */
    private long lastFrameMs = System.currentTimeMillis();

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
        // Fabric 的 AFTER_INIT 要等整个子类 init 返回才发生。先在公共 init 里收窄，
        // 以后某一面即使在 super.init() 之后创建原生 Widget，也会直接拿到内容区宽度。
        // 取窗口宽而不是当前字段：clearAndInit 时 width 可能已经收窄，不能再减一遍。
        reserveNotificationSidebar(client == null ? width : client.getWindow().getScaledWidth());
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
     * 把 Screen 的横向布局宽度收进通知栏左边；所有子类现有的 {@code width} 计算会一起重排，
     * 鼠标命中也继续使用同一份坐标，不需要让十四个界面各自记一套侧栏规则。
     *
     * <p>调用方每帧传完整窗口宽度，不能拿已经缩过的 {@link #width} 再减一次。
     */
    final void reserveNotificationSidebar(int screenWidth) {
        width = GameHud.sidebarLayout(screenWidth, projection()).contentWidth();
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

    /**
     * 两帧之间真实过了多少毫秒，夹在 0–200。
     *
     * <p>插值按真实毫秒推，不按帧 —— 否则高刷新率屏幕上「抬」会明显更快（{@link GuiLanguage#approach}）。
     * 上限 200：掉帧时别让插值一步跳到底。每一面原先各记一份 {@code lastFrameMs}，现在只在这里记。
     */
    protected long frameDelta(long now) {
        long dt = Math.max(0L, Math.min(200L, now - lastFrameMs));
        lastFrameMs = now;
        return dt;
    }

    @Override
    public boolean shouldPause() {
        return false;                  // 多人游戏里暂停毫无意义：别人还在等你，服务端的计时也不会停
    }

    /** 模糊背景加同一层底色（ADR-0018 §7.3：GUI 与牌面必须是同一个世界）。 */
    protected void renderBackdrop(DrawContext context, int mouseX, int mouseY, float delta) {
        // Screen.renderBackground 会读取字段 width 来铺暗纹。主内容的 width 已为侧栏收窄，
        // 这里只在背景调用期间还原完整宽度，否则侧栏下半截会直接露出比左边亮的世界画面。
        int contentWidth = width;
        width = context.getScaledWindowWidth();
        try {
            renderBackground(context, mouseX, mouseY, delta);
        } finally {
            width = contentWidth;
        }
        context.fill(0, 0, context.getScaledWindowWidth(), context.getScaledWindowHeight(), GuiLanguage.BACKDROP);
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
     * N 张一排时卡能画多高：竖着剩下的、横着一行放得下 N 张的、像素上限以内还清楚的、
     * 不超过屏幕高的 {@link #MAX_CARD_H_RATIO} —— 四者取小，再不低于 {@link #MIN_CARD_H}。
     *
     * @param availH 竖着剩给这一排卡的高度（调用方已经减掉上下各行与留空）
     */
    protected int cardHeightFor(int n, int availH) {
        int count = Math.max(1, n);
        int byWidth = GuiLanguage.cardHeight((width - 2 * SIDE - (count - 1) * CARD_GAP) / count);
        int cap = Math.min(sharpCardHeight(), Math.round(height * MAX_CARD_H_RATIO));
        return Math.max(MIN_CARD_H, Math.min(Math.min(availH, byWidth), cap));
    }

    /** 一排 N 张、每张 w 宽时整排多宽。 */
    protected static int cardRowWidth(int n, int w) {
        return n * w + (n - 1) * CARD_GAP;
    }

    /** 一排卡里，指针落在第几张上；都不在时 {@code -1}。上边界把「抬」起来的那几像素算进去。 */
    protected static int cardIndexAt(int mouseX, int mouseY, int left, int top, int w, int h, int count) {
        if (mouseY < top - GuiLanguage.LIFT_PX || mouseY > top + h) {
            return -1;
        }
        for (int i = 0; i < count; i++) {
            int x = left + i * (w + CARD_GAP);
            if (mouseX >= x && mouseX < x + w) {
                return i;
            }
        }
        return -1;
    }

    /** 只会「抬」的卡，顶上要留多少空：抬起的距离加金框与余量。 */
    protected static int liftRoom() {
        return (int) Math.ceil(GuiLanguage.LIFT_PX) + BORDER_ROOM;
    }

    /** 只会「抬」的按钮，顶上要留多少空：抬起的距离加 1 像素的框与余量。抬起来的金框不能切进上面那一行字。 */
    protected static int buttonLiftRoom() {
        return (int) Math.ceil(GuiLanguage.LIFT_PX) + 1 + 2;
    }

    /**
     * 会「顿」的卡，顶上要留多少空，才让最高的那一帧碰不到上面那一行。
     *
     * <p>三件事叠起来：「抬」9 像素 · 「顿」的位移 · 「顿」放大那一截 —— 卡是绕<b>底边</b>缩放的，
     * 所以长出来的 {@code (scale-1)×h} 全在上面。只算「抬」的那一版在真实客户端上实拍到了：
     * 超时那一下弹起时，金框的上边切进了座位轨上「珠宝商」那几个字。
     */
    protected static int snapRoom(int cardHeight) {
        return (int) Math.ceil(GuiLanguage.LIFT_PX + GuiLanguage.SNAP_PEAK_RISE
                + (GuiLanguage.SNAP_PEAK_SCALE - 1f) * cardHeight) + BORDER_ROOM;
    }

    /**
     * 选中金框（金 =「你 · 你选的那张」）。在卡自己的矩阵里画：它是这张卡的一部分，得跟着卡一起升起、一起缩放
     * —— 画在矩阵外面的那一版，发牌那 300ms 里框停在落点、卡还在下面往上走（2026-09-15 真实客户端上看到的）。
     */
    protected static void drawCardFrame(DrawContext context, int w, int h) {
        context.drawBorder(-CARD_FRAME, -CARD_FRAME, w + 2 * CARD_FRAME, h + 2 * CARD_FRAME, GuiLanguage.GOLD);
    }

    /** 身份那一行画在哪：贴底，留出屏幕高的 5%（至少 8 单位）。 */
    protected int identityY() {
        return height - Math.max(8, Math.round(height * 0.05f)) - textRenderer.fontHeight;
    }

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

    /** 倒计时跟舞台一样宽：它是「这一排牌」的时间，不是屏幕上的装饰线。至少 160，绝不出屏。 */
    protected int countdownWidth(int stageW) {
        return Math.min(width - 2 * SIDE, Math.max(160, stageW));
    }

    /**
     * 一排按钮排在哪：一行放得下就并排居中，放不下就一行一个。
     *
     * <p>❗宽度按 {@code textRenderer} 量出来的字宽算，不写死 —— 译名长度各语言不同，
     * 写死的那一版只在中文下看着是居中的（英文的「Join the defending side」在窄窗口里一行放不下三个）。
     */
    protected java.util.List<Box> layoutButtonRow(java.util.List<Text> labels, int top, int gap) {
        int h = buttonHeight();
        int[] w = new int[labels.size()];
        int total = -gap;
        for (int i = 0; i < labels.size(); i++) {
            w[i] = buttonWidth(labels.get(i));
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
     * 一片等宽的按钮排成几列：阵容页那种「八个角色勾选」用它。
     *
     * <p>每个按钮取最宽的那个标签的宽度，列数放不下时减到放得下为止（最少一列）。
     * 行与行之间多留 {@link #buttonLiftRoom()}：抬起来的金框不能切进上一行。
     */
    protected java.util.List<Box> layoutButtonGrid(java.util.List<Text> labels, int top, int gap, int columns) {
        int h = buttonHeight();
        int w = 0;
        for (Text label : labels) {
            w = Math.max(w, buttonWidth(label));
        }
        int cols = Math.max(1, Math.min(columns, labels.size()));
        while (cols > 1 && cols * w + (cols - 1) * gap > width - 2 * BTN_SIDE) {
            cols--;
        }
        int rowW = cols * w + (cols - 1) * gap;
        int left = (width - rowW) / 2;
        int rowStep = h + gap + buttonLiftRoom();
        java.util.List<Box> out = new ArrayList<>(labels.size());
        for (int i = 0; i < labels.size(); i++) {
            out.add(new Box(left + (i % cols) * (w + gap), top + (i / cols) * rowStep, w, h));
        }
        return out;
    }

    /** 一个按钮多高：一行字加上下内边距。 */
    protected int buttonHeight() {
        return textRenderer.fontHeight + 2 * BTN_PAD_Y;
    }

    /** 一个按钮多宽：字宽加左右内边距。 */
    protected int buttonWidth(Text label) {
        return textRenderer.getWidth(label) + 2 * BTN_PAD_X;
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

    /** 这一排按钮从最左到最右多宽。座位轨与倒计时跟着它排，别让轨铺满全屏、按钮缩在中间一小截。 */
    protected static int rowWidth(java.util.List<Box> boxes) {
        int left = Integer.MAX_VALUE;
        int right = 0;
        for (Box b : boxes) {
            left = Math.min(left, b.x());
            right = Math.max(right, b.x() + b.w());
        }
        return boxes.isEmpty() ? 0 : right - left;
    }

    /** 这一排（或这一片）按钮总共占多高。版面按它往下排，免得下一行压上来。 */
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
        drawButton(context, b, label, focused, color, GuiLanguage.GROUND, -lift, 1f);
    }

    /**
     * 同上，但底色、位移与缩放由调用方给：行动一面用它画「顿」（绕底边放大）与按不动的淡底。
     *
     * @param fill  底色。按不动的按钮把底一起淡下去 —— 只淡字的话与本来就淡字的「什么也不做」分不开（实拍过）
     * @param rise  竖向位移，负号向上（「抬」就是 {@code -lift}）
     * @param scale 绕底边中点缩放：「顿」长出来的那一截全在上面，版面留空才算得准
     */
    protected void drawButton(DrawContext context, Box b, Text label, boolean focused, int color,
                              int fill, float rise, float scale) {
        context.getMatrices().push();
        context.getMatrices().translate(b.x() + b.w() / 2f, b.y() + b.h() + rise, 0);
        context.getMatrices().scale(scale, scale, 1f);
        context.getMatrices().translate(-b.w() / 2f, -b.h(), 0);
        context.fill(0, 0, b.w(), b.h(), fill);
        if (focused) {
            // 金 =「你 · 你选的那个」。按钮的框是 1 像素，卡的框是 2 像素（CARD_FRAME）—— 两种元素，各只此一处。
            context.drawBorder(-1, -1, b.w() + 2, b.h() + 2, GuiLanguage.GOLD);
        }
        context.drawCenteredTextWithShadow(textRenderer, label, b.w() / 2,
                (b.h() - textRenderer.fontHeight) / 2 + 1, color);
        context.getMatrices().pop();
    }

    /** 把一个 ARGB 色换成另一个不透明度。按不动的东西靠它淡下去，不另起颜色：语义色只有三个。 */
    protected static int withAlpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0xFFFFFF);
    }

    /** 按不动的按钮：底与字都只剩这么多不透明度。 */
    protected static final int DISABLED_FILL_ALPHA = 0x55;
    protected static final int DISABLED_TEXT_ALPHA = 0x66;

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
