package io.github.heavyseasmc.mod.client;

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
}
