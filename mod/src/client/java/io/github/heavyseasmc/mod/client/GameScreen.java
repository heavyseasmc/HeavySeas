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

    // ---------------------------------------------------------------- 带位（ADR-0037 §7.10：一套带位版面）

    /** 上带（回合 · 阶段 · 海鸥）画在哪一行。 */
    protected static final int TOP_BAND_Y = 12;
    /**
     * 上带里那一行字<b>下面</b>还要多少：到海鸥格 3 · 海鸥格 7 · 留白 4。
     *
     * <p>原先这里是写死的 34，而实际用掉的只有「一行字 + 10」—— 界面尺寸自动时白白空掉 15 个单位，
     * 而舞台正缺这 15 个（总纲 §7.8：牌是主体）。改成按实际行高算，上带于是只占它真正用到的那么高。
     */
    protected static final int TOP_BAND_H = 14;
    /** 相邻两条带之间。 */
    protected static final int BAND_GAP = 5;
    /**
     * 座位轨最多吃掉「轨 + 舞台」那一段的几成。
     *
     * <p>❗<b>舞台先给牌，轨拿剩下的</b>（总纲 §7.8）。反过来（轨先按窗口取高、内容再挤剩下的）
     * 就是第二刀收尾那次实拍的病根：轨一变高，行动一面的按钮被顶出去盖在说明那一行上（§7.7）。
     */
    protected static final float RAIL_MAX_SHARE = 0.32f;
    /** 两侧留白。 */
    protected static final int SIDE = 20;
    /** 一排卡里相邻两张之间。 */
    protected static final int CARD_GAP = 6;
    /** 一排按钮里相邻两个之间。 */
    protected static final int BTN_GAP = 6;
    /** 横杠到秒数。 */
    protected static final int BAR_TO_TEXT = 3;
    /** 舞台里两段内容之间。 */
    protected static final int HINT_GAP = 5;
    /**
     * 倒计时有多高。**会超时的界面都画它，不会超时的界面一律不画**（ADR-0018 §7.4）。
     * 它现在是一件材质（比例尺 / 液位管，ADR-0037），高度由材质定 —— 各面的版面照旧只引用这一个数。
     */
    protected static final int BAR_H = GuiMaterial.GAUGE_H;
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
        drawLine(context, Text.translatable("heavyseas.hud.sea", rowStack, helm),
                width / 2, y, GuiLanguage.muted());
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

    /** 界面开着时按键不经按键绑定的轮询，所以换主题在这里接一次；各面的 keyPressed 最后都会落到 super。 */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (HeavySeasClient.themeKey() != null && HeavySeasClient.themeKey().matchesKey(keyCode, scanCode)) {
            ClientPrefs.toggleTheme();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;                  // 多人游戏里暂停毫无意义：别人还在等你，服务端的计时也不会停
    }

    /**
     * 铺底：压暗的世界上放一张材质板（ADR-0037）。取代原先的「模糊背景 + 半透明黑」——
     * 那一套正是界面读起来像深色模式应用的原因。板是不透明的，聊天从底下透上来的问题由它自然兜住
     * （原先靠把黑加到 0xE4）。板只铺在舞台上：{@code width} 已为侧栏收窄，侧栏自己有一块标签作底。
     */
    protected void renderBackdrop(DrawContext context, int mouseX, int mouseY, float delta) {
        GuiMaterial.dimWorld(context);
        int m = GuiMaterial.SHEET_MARGIN;
        GuiMaterial.sheet(context, m, m, width - 2 * m, height - 2 * m);
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

    /**
     * 一排高 {@code h} 的牌在 {@code [top, bottom)} 里排在哪一行。
     *
     * <p>牌顶要留一段 {@code room}（「抬」9 像素 · 「顿」的位移与放大 —— 牌绕<b>底边</b>缩放，
     * 长出来的那一截全在上面）。❗留空<b>只在真的不够时</b>才把牌往下推：
     * 先按整格居中，再保证抬起来的那一截不越过 {@code top}。
     *
     * <p>原先写的是 {@code top + room + (余下的)/2} —— 留空先扣、牌再在<b>剩下的</b>里居中，
     * 于是整排恒比这一格的正中低半个留空（补给箱实测低 7 个单位，1280×720 下约 21 物理像素）。
     * 用户 2026-09-22 看第四刀实拍时问的就是这个。与「牌是余数」是同一个毛病的小号版本。
     */
    protected static int cardsTopIn(int top, int bottom, int h, int room) {
        return Math.max(top + room, top + Math.max(0, (bottom - top - h) / 2));
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
        context.drawBorder(-CARD_FRAME, -CARD_FRAME, w + 2 * CARD_FRAME, h + 2 * CARD_FRAME, GuiLanguage.gold());
    }

    /**
     * 五条带的 y，<b>只由窗口算出来</b>：与这一面有什么内容无关，所以同一个窗口下每一面都一样。
     *
     * <p>❗这是第四刀的全部要点（ADR-0037 §7.10 第 4 条）。此前十五个 {@code Screen} 各自从零算版面 ——
     * 上带 · 座位轨 · 倒计时 · 身份行在每一面的 y 都不同，换面时整屏都在跳，用户判「每个阶段页面感觉像独立的」。
     * 现在共有的四条带一个像素都不动，<b>连续感来自它们没动</b>，不来自动效；各面只填舞台那一格。
     *
     * <p>顺带治「文字太多」：带位一定，每面能放多少字就有了上限 —— 放不下的只能删，不能再往下挤。
     *
     * @param avatar 座位轨上头像的直径；{@code 0} = 这一档窗口放不下头像（退回只有名字的轨）
     */
    protected record Bands(int topY, int railY, int railH, int avatar, int ruleY, int stageTop, int stageBottom,
                           int gaugeY) {

        /** 舞台那一格有多高。 */
        int stageH() {
            return Math.max(0, stageBottom - stageTop);
        }

        /** 倒计时那一条带多高：横杠与它旁边那行秒数，谁高算谁。 */
        int gaugeH() {
            return Math.max(BAR_H, textH());
        }
    }

    /**
     * 这一帧的带位。每帧重算，不缓存：窗口与界面尺寸随时会变（与各面的版面同一条规矩）。
     *
     * <p>排法是<b>两头往中间</b>：上带钉在顶上、身份行贴底、倒计时压在身份行上面，
     * 剩下的一段由座位轨与舞台分 —— 轨最多拿 {@link #RAIL_MAX_SHARE}，舞台拿剩下的全部。
     */
    protected Bands bands() {
        int text = textH();
        // ❗没有身份行了（用户 2026-09-22：「按稿子去掉，交给 HUD」）—— 体力与口渴主画面 HUD 上有，
        //   界面里再写一遍是重复，而那一条带是牌最缺的二十个单位。倒计时于是直接贴底。
        int gaugeY = height - Math.max(8, Math.round(height * 0.05f)) - Math.max(BAR_H, text);
        int railY = TOP_BAND_Y + topBandH();
        int stageBottom = gaugeY - BAND_GAP;
        int middle = Math.max(0, stageBottom - railY);           // 座位轨与舞台分这一段
        int bare = RAIL_H + text - BASE_TEXT_H;                  // 只有名字与那条线的轨
        int room = Math.round(middle * RAIL_MAX_SHARE);
        int avatar = bare > room ? 0 : avatarDiameter(room - bare);
        int railH = bare > room ? 0 : bare + blockOf(avatar);
        int ruleY = railY + (railH > 0 ? railH : 0);
        int stageTop = ruleY + (railH > 0 ? BAND_GAP : 0);
        return new Bands(TOP_BAND_Y, railY, railH, avatar, ruleY, stageTop, stageBottom, gaugeY);
    }

    /**
     * 舞台里贴着底边往上排 {@code lines} 行说明时，第一行在哪。
     *
     * <p>说明贴舞台底边，所以<b>最后一行永远压在倒计时上面同一个位置</b> —— 各面说明行数不同，
     * 但它们与倒计时之间的距离一样，换面时下半屏同样不跳。
     */
    protected int footerTop(Bands b, int lines) {
        return b.stageBottom() - lines * lineStep();
    }

    /** 舞台里相邻两行说明之间隔多远（一行字加 2）。 */
    protected static int lineStep() {
        return textH() + 2;
    }

    /**
     * 四条共有的带一次画完：上带 · 座位轨 · 身份行（倒计时归 {@link #drawCountdown}，
     * 只有会超时的面才画它 —— ADR-0018 §7.4），外加舞台四角的角标。
     *
     * <p>各面在 {@code render} 的开头调它一次，拿回来的 {@link Bands} 就是这一帧唯一的版面来源。
     */
    protected Bands drawChrome(DrawContext context, HudView view) {
        edgeLeftW = 0;
        edgeRightW = 0;                        // 每帧清零：这一面这一帧到底放没放边上的提示，只认它自己说的
        Bands b = bands();
        drawTopBand(context, view, b);
        drawRailBand(context, view, b);
        // 两道通栏线把舞台夹出来：座位轨与舞台之间、舞台与倒计时之间（样张里是木板之间的缝）。
        // 截图判据认的就是它们 —— 两条都在，才量得出「舞台上下沿都没动」。
        if (b.railH() > 0) {
            GuiMaterial.bandRule(context, SIDE, b.ruleY(), width - 2 * SIDE);
        }
        GuiMaterial.bandRule(context, SIDE, b.stageBottom(), width - 2 * SIDE);
        return b;
    }

    /** 上带画什么。默认是「回合 · 阶段 · 海鸥」；对局已经结束的那两面写别的（计分写结局，阵容写标题）。 */
    protected void drawTopBand(DrawContext context, HudView view, Bands b) {
        drawPublicBand(context, view, b.topY());
    }

    /**
     * 座位轨画什么。默认是这一局的座位，轮到的那个人是金的 —— 全船都看得见轮到谁。
     *
     * <p>补给箱与终局翻牌两面覆写它：那两面的轨是<b>这一条链</b>与<b>翻到谁</b>，不是座位序。
     */
    protected void drawRailBand(DrawContext context, HudView view, Bands b) {
        List<String> seats = view.seats();
        if (seats.isEmpty() || b.railH() == 0) {
            return;
        }
        int cell = railCell(seats.size());
        int left = (width - seats.size() * cell) / 2;
        for (int i = 0; i < seats.size(); i++) {
            String id = seats.get(i);
            boolean here = id.equals(view.actor());
            drawSeat(context, id, left + i * cell, b.railY(), cell, b, here ? GuiLanguage.gold() : 0, false,
                    here ? GuiLanguage.gold() : GuiLanguage.muted(), here ? GuiLanguage.gold() : GuiLanguage.ground());
        }
    }

    /** 座位轨一格多宽：整条铺到舞台两边，格子按人数均分。 */
    protected int railCell(int seats) {
        return (width - 2 * SIDE) / Math.max(1, seats);
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
    protected void drawCountdown(DrawContext context, Bands b, long now, long deadlineMs, long totalMs, int stageW) {
        long left = Math.max(0L, deadlineMs - now);
        long total = Math.max(1L, totalMs);
        float frac = MathHelper.clamp(left / (float) total, 0f, 1f);
        boolean urgent = left <= GuiLanguage.urgencyThreshold(total);
        // ❗秒数排在横杠**旁边**，不排在它下面：叠成两行时这一条带要 21 个单位，并排只要一行字那么高。
        //   省下来的十几个单位全归舞台（总纲 §7.8）。
        // 样张里这个数是墨色的、没有 s 后缀 —— 它是这一条带上唯一要读的东西，不该比刻度还淡。
        Text seconds = Text.literal(String.format("%.1f", left / 1000f));
        int textW = textW(seconds);
        int gap = 2 * BAR_TO_TEXT;
        // 两头的按键提示先占位：倒计时绝不压到它们。
        // ❗最后这一下 min 不能省：countdownWidth 里有个 MIN_COUNTDOWN_W 的下限，
        //   光把窄一点的宽度传进去是拦不住的 —— 实拍到秒数压在「U 收起」那枚键帽上（2026-09-22）。
        //   宁可横杠短一截，也不许两件东西叠在一起。
        // ❗在**两头让出的那一段里**居中，不是按全屏居中：左右两头的提示不一样宽，
        //   按全屏居中会整块偏向窄的那一头，压上另一头（实拍：秒数压在「U 收起」上）。
        int from = SIDE + edgeLeftW + (edgeLeftW > 0 ? HINT_SPACING : 0);
        int to = width - SIDE - edgeRightW - (edgeRightW > 0 ? HINT_SPACING : 0);
        int free = Math.max(0, to - from);
        int barW = Math.min(countdownWidth(Math.max(0, stageW - textW - gap)), Math.max(0, free - textW - gap));
        int x = from + Math.max(0, (free - (barW + gap + textW)) / 2);
        GuiMaterial.gauge(context, x, b.gaugeY() + (b.gaugeH() - BAR_H) / 2, barW, frac, urgent);
        drawLineLeft(context, seconds, x + barW + gap, b.gaugeY() + (b.gaugeH() - textH()) / 2,
                urgent ? GuiLanguage.cinnabar() : GuiLanguage.ink());
    }

    /**
     * 倒计时多宽。它是「这一排牌」的时间，所以跟着舞台走；但**不通栏**。
     *
     * <p>❗右栏收起之后舞台就是整屏，倒计时于是横跨一千多像素去表达「还剩几秒」这一个标量 ——
     * 全屏信息量对面积比最差的一件，而且一条通栏的横线会把版面切断（用户 2026-09-22：
     * 「横向利用得很满，纵向是否能分担一些」，指的是 GUI 元素的排布）。
     * 上限按舞台的比例写，不写死像素：界面尺寸与窗口都会变。
     */
    protected int countdownWidth(int stageW) {
        int cap = Math.max(MIN_COUNTDOWN_W, Math.round((width - 2 * SIDE) * COUNTDOWN_MAX_SHARE));
        return Math.min(Math.min(width - 2 * SIDE, cap), Math.max(MIN_COUNTDOWN_W, stageW));
    }

    /** 倒计时最多占舞台的几成宽，以及它的下限（GUI 单位）。 */
    private static final float COUNTDOWN_MAX_SHARE = 0.46f;
    private static final int MIN_COUNTDOWN_W = 160;

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
        return textH() + 2 * BTN_PAD_Y;
    }

    /** 一个按钮多宽：字宽加左右内边距。 */
    protected int buttonWidth(Text label) {
        return textW(label) + 2 * BTN_PAD_X;
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
        drawButton(context, b, label, focused, color, GuiLanguage.ground(), -lift, 1f);
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
        // 按钮是一块标签（纸签 / 搪瓷牌）；按不动的连底一起淡下去（fill 的不透明度就是那个「淡」）。
        float alpha = (fill >>> 24) / 255f;
        context.setShaderColor(1f, 1f, 1f, alpha);
        GuiMaterial.tag(context, 0, 0, b.w(), b.h());
        context.setShaderColor(1f, 1f, 1f, 1f);
        color = GuiLanguage.onTag(color);
        if (focused) {
            // 金 =「你 · 你选的那个」。按钮的框是 1 像素，卡的框是 2 像素（CARD_FRAME）—— 两种元素，各只此一处。
            context.drawBorder(-1, -1, b.w() + 2, b.h() + 2, GuiLanguage.gold());
            context.drawBorder(-2, -2, b.w() + 4, b.h() + 4, GuiLanguage.gold());
        }
        drawLine(context, label, b.w() / 2,
                (b.h() - textH()) / 2 + 1, color);
        context.getMatrices().pop();
    }

    // ------------------------------------------------------------------ 字：只经 GuiText（ADR-0037）

    /** 版面常量当初是按「一行字 9 个单位」定的；字的实际行高随界面尺寸变，凡含一行字的高度都要补上这个差。 */
    private static final int BASE_TEXT_H = 9;

    // ------------------------------------------------------------------ 座位：头像 · 名字 · 一道线（ADR-0037）

    /** 头像最小、最大画多少<b>物理像素</b>，以及取窗口高的几分之一。上限按像素写：GUI 单位随界面尺寸差出好几倍。 */
    private static final double AVATAR_MIN_PX = 36;
    private static final double AVATAR_MAX_PX = 72;
    private static final double AVATAR_WINDOW_RATIO = 1 / 12.0;
    /** 头像最小画几个 GUI 单位：再小就只剩一团色，不如不画圈。 */
    private static final int AVATAR_FLOOR = 8;
    /** 还没轮到的人，头像淡到多少。 */
    protected static final float SEAT_FADED = 0.5f;

    /**
     * 座位上的头像画多大（直径，GUI 单位）：先按窗口高取，再缩到 {@code room} 以内；一枚都放不下就是 {@code 0}。
     *
     * <p>❗<b>缩的是头像，不是舞台。</b> 第二刀收尾那次实拍：轨换成头像之后高出约 28 个单位，
     * 行动一面的按钮被顶出去盖在说明那一行上（§7.7）。带位版面里轨只拿 {@link #RAIL_MAX_SHARE}，
     * 这个方法负责把头像塞进那点高度里；缩到看不清还硬画不如不画，而把舞台挤掉是绝不允许的。
     */
    private int avatarDiameter(int room) {
        var window = net.minecraft.client.MinecraftClient.getInstance().getWindow();
        double scale = Math.max(1.0, window.getScaleFactor());
        double px = Math.max(AVATAR_MIN_PX, Math.min(AVATAR_MAX_PX, window.getHeight() * AVATAR_WINDOW_RATIO));
        int d = Math.max(AVATAR_FLOOR, (int) Math.round(px / scale));
        while (d > AVATAR_FLOOR && blockOf(d) > room) {
            d--;
        }
        return blockOf(d) <= room ? d : 0;
    }

    /** 头像连圈占多高，外加到名字的 1 个单位；这一档不画头像时是 0。 */
    private static int blockOf(int diameter) {
        return diameter <= 0 ? 0 : diameter + 2 * GuiMaterial.ringMargin(diameter) + 1;
    }

    /**
     * 座位轨上的一格：头像、名字、名字底下一道线。三面的座位轨（补给箱 · 行动 · 终局）都画它 ——
     * 各面只决定「这一格现在是什么状态」，长什么样只此一处。
     *
     * @param mark      头像圈上的语义色（金 = 你 · 铜绿 = 正轮到），{@code 0} 不着色
     * @param faded     还没轮到：头像淡下去
     * @param lineColor 名字底下那道线
     * @return 名字那一行的 y —— 终局那一面还要在线下面再写一行
     */
    protected int drawSeat(DrawContext context, String characterId, int x, int y, int cell, Bands b,
                           int mark, boolean faded, int nameColor, int lineColor) {
        int full = b.avatar();
        if (full > 0) {
            int m = GuiMaterial.ringMargin(full);
            int d = Math.max(1, Math.min(full, cell - 2 * m - 2));   // 格子比头像还窄（界面尺寸 1、八个人）就跟着格子缩，绝不压到邻座
            GuiMaterial.avatar(context, characterId, x + (cell - d) / 2, y + m + (full - d) / 2, d, mark,
                    faded ? SEAT_FADED : 1f);
        }
        int nameY = y + blockOf(full);
        drawLineIn(context, nameOf(characterId), x + 2, nameY, cell - 4, nameColor);
        context.fill(x + 2, nameY + textH() + 1, x + cell - 2, nameY + textH() + 2, lineColor);
        return nameY;
    }

    /** 上带多高：一行字加 {@link #TOP_BAND_H}（海鸥格那一排与留白）。 */
    protected static int topBandH() {
        return textH() + TOP_BAND_H;
    }

    /** 一行正文多高，GUI 单位。版面里凡是「一行字」都用它 —— 界面尺寸小的时候有字号地板，一行不止 9 个单位。 */
    protected static int textH() {
        return GuiText.lineHeight(GuiText.BODY, false);
    }

    /** 一行正文多宽，GUI 单位。 */
    protected static int textW(Text text) {
        return GuiText.width(text.getString(), GuiText.BODY, false);
    }

    protected static int textW(String text) {
        return GuiText.width(text, GuiText.BODY, false);
    }

    /** 以 {@code cx} 为中心画一行正文。框取到舞台两边里较近的那一边为止：放不下就缩，绝不出舞台。 */
    protected void drawLine(DrawContext context, Text text, int cx, int y, int color) {
        drawLine(context, text, cx, y, color, GuiText.BODY, false);
    }

    protected void drawLine(DrawContext context, String text, int cx, int y, int color) {
        drawLine(context, Text.literal(text), cx, y, color, GuiText.BODY, false);
    }

    protected void drawLine(DrawContext context, Text text, int cx, int y, int color, int size, boolean bold) {
        int half = Math.max(1, Math.min(cx, width - cx) - 2);
        GuiText.line(context, text, cx - half, y, 2 * half, size, bold, color, GuiText.Align.CENTER);
    }

    /** 在 {@code [x, x + w]} 这一格里居中画一行正文：格子窄就缩字号，绝不压到邻格。 */
    protected void drawLineIn(DrawContext context, Text text, int x, int y, int w, int color) {
        GuiText.line(context, text, x, y, Math.max(1, w), GuiText.BODY, false, color, GuiText.Align.CENTER);
    }

    /**
     * 在舞台宽里居中画一段正文，最多 {@code maxLines} 行（折行归 {@link GuiText}）。
     *
     * @return 实际画了几行
     */
    protected int drawParagraph(DrawContext context, Text text, int y, int maxLines, int color) {
        int used = GuiText.draw(context, text.getString(), SIDE, y, width - 2 * SIDE, GuiText.BODY, false, color,
                GuiText.Align.CENTER, maxLines);
        return Math.max(1, Math.round(used / (float) textH()));
    }

    /** 从 {@code x} 起左对齐画一行正文，最宽到舞台右边。 */
    protected void drawLineLeft(DrawContext context, Text text, int x, int y, int color) {
        GuiText.line(context, text, x, y, Math.max(1, width - x - 2), GuiText.BODY, false, color, GuiText.Align.LEFT);
    }

    protected void drawLineLeft(DrawContext context, String text, int x, int y, int color) {
        drawLineLeft(context, Text.literal(text), x, y, color);
    }

    // ------------------------------------------------------------------ 按键提示：键帽 + 一句话（ADR-0037）

    /** 一条按键提示：几枚键帽，后面跟一句话。{@code keys} 为空就只是一句话 —— 它与带键帽的提示排在同一行里。 */
    protected record KeyHint(List<String> keys, Text label) {
    }

    /** 同一条提示里相邻两枚键帽之间、键帽与那句话之间。 */
    private static final int KEY_GAP = 2;
    private static final int KEY_TO_LABEL = 4;
    /** 相邻两条提示之间，也是提示与倒计时之间。 */
    private static final int HINT_SPACING = 12;

    /** 一枚键帽多高：一行字加键帽自己的边与底下那道厚边。按键提示那一行就这么高。 */
    protected static int keyHintRowH() {
        return textH() + GuiMaterial.KEY_PAD_TOP + GuiMaterial.KEY_PAD_BOTTOM;
    }

    private static int keycapW(String key) {
        // 单个字符的键帽至少是方的：「←」比「Enter」窄得多，照字宽画出来是一根竖条。
        return Math.max(keyHintRowH(), textW(key) + 2 * GuiMaterial.KEY_PAD_X);
    }

    private int keyHintW(KeyHint hint) {
        int w = 0;
        for (String key : hint.keys()) {
            w += keycapW(key) + KEY_GAP;
        }
        if (!hint.keys().isEmpty()) {
            w += KEY_TO_LABEL - KEY_GAP;
        }
        return Math.min(width - 2 * SIDE, w + textW(hint.label()));
    }

    /** 这几条提示排成几行：一行放不下就在两条提示之间折行，一条提示绝不拆开。 */
    private List<List<KeyHint>> flowKeyHints(List<KeyHint> hints) {
        int avail = width - 2 * SIDE;
        List<List<KeyHint>> rows = new ArrayList<>();
        List<KeyHint> row = new ArrayList<>();
        int used = 0;
        for (KeyHint hint : hints) {
            int w = keyHintW(hint);
            if (!row.isEmpty() && used + HINT_SPACING + w > avail) {
                rows.add(row);
                row = new ArrayList<>();
                used = 0;
            }
            used += (row.isEmpty() ? 0 : HINT_SPACING) + w;
            row.add(hint);
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        return rows;
    }

    /** 这几条提示总共占多高。版面要先问它 —— 窄窗口下会折成两行。 */
    protected int keyHintsH(List<KeyHint> hints) {
        int rows = flowKeyHints(hints).size();
        return rows * keyHintRowH() + Math.max(0, rows - 1) * KEY_GAP;
    }

    /** 在舞台宽里居中画一排按键提示；放不下就折行，单条太长就缩字号（归 {@link GuiText}），绝不出舞台。 */
    protected void drawKeyHints(DrawContext context, List<KeyHint> hints, int y, int color) {
        int rowH = keyHintRowH();
        for (List<KeyHint> row : flowKeyHints(hints)) {
            int total = -HINT_SPACING;
            for (KeyHint hint : row) {
                total += keyHintW(hint) + HINT_SPACING;
            }
            drawHintRun(context, row, (width - total) / 2, y, color);
            y += rowH + KEY_GAP;
        }
    }

    /** 一串提示从 {@code x} 起往右画一行。 */
    private void drawHintRun(DrawContext context, List<KeyHint> hints, int x, int y, int color) {
        int rowH = keyHintRowH();
        for (KeyHint hint : hints) {
            int end = x + keyHintW(hint);
            for (String key : hint.keys()) {
                int w = keycapW(key);
                GuiMaterial.keycap(context, x, y, w, rowH);
                GuiText.line(context, Text.literal(key), x + 1, y + GuiMaterial.KEY_PAD_TOP, w - 2, GuiText.BODY, false,
                        GuiLanguage.onTag(GuiLanguage.ink()), GuiText.Align.CENTER);
                x += w + KEY_GAP;
            }
            if (!hint.keys().isEmpty()) {
                x += KEY_TO_LABEL - KEY_GAP;
            }
            GuiText.line(context, hint.label(), x, y + GuiMaterial.KEY_PAD_TOP, Math.max(1, end - x), GuiText.BODY, false,
                    color, GuiText.Align.LEFT);
            x = end + HINT_SPACING;
        }
    }

    /** 一串提示排成一行有多宽。 */
    private int runWidth(List<KeyHint> hints) {
        int total = -HINT_SPACING;
        for (KeyHint hint : hints) {
            total += keyHintW(hint) + HINT_SPACING;
        }
        return Math.max(0, total);
    }

    /** 这一帧按键提示在倒计时那一条带的两头各占多宽。{@link #drawChrome} 每帧清零。 */
    private int edgeLeftW;
    private int edgeRightW;

    /**
     * 按键提示排在<b>倒计时那一条带的两头</b>：左边怎么选，右边怎么定，中间是倒计时。
     *
     * <p>❗它不占舞台一行。原先这几句挤在舞台底边居中的一排里，把牌往上顶
     * （用户 2026-09-22：「文字也可以拿掉了，按钮示范拿到左下角，或者左右两边…这样空间就省出来了，
     * 牌可以大一点，底部也不拥挤了」）。而倒计时按舞台的比例只占中间不到一半，两头本来就空着 ——
     * 键位是<b>常驻的操作说明</b>，不是这一面的内容，放边上正好。
     *
     * <p>文案一律短到两个字（{@code heavyseas.keys.*} 一处定义）：动效已经把「依次传、选一张」演出来了，
     * 不必再用一句话讲一遍。
     */
    protected void drawEdgeHints(DrawContext context, Bands b, List<KeyHint> left, List<KeyHint> right) {
        edgeLeftW = runWidth(left);
        edgeRightW = runWidth(right);
        int y = b.gaugeY() + (b.gaugeH() - keyHintRowH()) / 2;
        drawHintRun(context, left, SIDE, y, GuiLanguage.muted());
        drawHintRun(context, right, width - SIDE - edgeRightW, y, GuiLanguage.muted());
    }

    /** 一条提示：几枚键帽加一个短词。 */
    protected static KeyHint keys(String label, String... caps) {
        return new KeyHint(List.of(caps), Text.translatable("heavyseas.keys." + label));
    }

    /**
     * 物资牌的牌名与那一句效果。
     *
     * <p>❗这两条与角色名一样是<b>拼出来</b>的 lang 键（物资 id 来自 {@code data/provisions}，代码里没有那张表），
     * 所以构建期的 {@code checkLangKeys} 单独按数据核对这一族 —— 加一张牌而忘了写那一句，会在构建时红。
     */
    protected static Text provisionName(String id) {
        return Text.translatable("heavyseas.provision." + id);
    }

    protected static Text provisionEffect(String id) {
        return Text.translatable("heavyseas.provision." + id + ".effect");
    }

    // ------------------------------------------------------------ 提示签：牌自己的内容（ADR-0037 §7.12）

    /** 提示签的内边距与两行之间。 */
    private static final int PLATE_PAD_X = 10;
    private static final int PLATE_PAD_Y = 4;
    /** 效果那一段固定按两行留位：签子的高度不许随牌换来换去，否则牌会跟着上下跳。 */
    private static final int PLATE_BODY_LINES = 2;
    /** 签子最宽占舞台的几成。 */
    private static final float PLATE_MAX_SHARE = 0.46f;
    private static final int PLATE_MIN_W = 120;

    /**
     * 查看态的版面：牌收成一叠在左，说明在右。
     *
     * <p>❗<b>默认态一个字都没有。</b> 牌做什么是<b>冷信息</b> —— 玩一两次就记住了，
     * 不该天天占着舞台；轮到谁、还剩几秒才是热的，那些常驻在共有的带上。
     * 用户 2026-09-22：「藏一层右键 / U 键查看，然后做个把牌堆起来的动效，堆顶为查看的这张牌，
     * 右边为描述文字，这样一下子就宽敞了」。省下的不只是一行字，是整整一块签子的高度。
     *
     * <p>说明放<b>右边</b>而不是下面：这一排牌横着已经铺满，而牌一收成叠，横向立刻空出一大片 ——
     * 纵向本来就是最紧的那一维（§7.8）。
     */
    protected record Inspect(int cardX, int cardY, int cardW, int cardH, int plateX, int plateY, int plateW) {
    }

    /** 查看态里那一叠牌与那张签子各在哪。只由带位与窗口算，与这一面有几张牌无关。 */
    protected Inspect inspect(Bands b) {
        int h = Math.min(b.stageH(), Math.min(sharpCardHeight(), Math.round(height * MAX_CARD_H_RATIO)));
        int w = GuiLanguage.cardWidth(h);
        int stage = width - 2 * SIDE;
        int plateW = Math.max(PLATE_MIN_W, Math.min(Math.round(stage * PLATE_MAX_SHARE), stage - w - PLATE_GAP));
        int total = w + PLATE_GAP + plateW;
        int left = (width - total) / 2;
        int top = b.stageTop() + Math.max(0, (b.stageH() - h) / 2);
        return new Inspect(left, top, w, h, left + w + PLATE_GAP, top, plateW);
    }

    /** 那一叠牌与签子之间。 */
    private static final int PLATE_GAP = 14;

    /**
     * 签子上写什么：<b>类别 · 牌堆里共几张 / 牌名 / 一句效果</b>。
     *
     * <p>它就是「承担讲规矩那件事的另有它物」（用户 2026-09-22：「GUI 文字不是用来教玩家怎么玩游戏的」）。
     *
     * @param pointX  尖角指着哪儿；{@code < 0} = 不要尖角（签子在牌旁边时）
     * @param caption 题头，可为 {@code null}（类别与张数还没送到客户端时就是这样）
     */
    protected void drawCardPlate(DrawContext context, int x, int top, int w, int pointX,
                                 Text caption, Text name, Text body) {
        int tw = w - 2 * PLATE_PAD_X;
        int capH = caption == null ? 0 : GuiText.lineHeight(GuiText.CAPTION, false) + 2;
        // 签子按**这一句实际排几行**画：短句的签子就矮一点。查看态里它在牌旁边，
        // 高矮不影响牌的位置，所以不必像常驻那版那样按最大值留位。
        int bodyH = Math.min(PLATE_BODY_LINES * textH(), GuiText.height(body.getString(), tw, GuiText.BODY, false, PLATE_BODY_LINES));
        int h = 2 * PLATE_PAD_Y + capH + GuiText.lineHeight(GuiText.NAME, true) + 2 + bodyH;
        GuiMaterial.plate(context, x, top, w, h, pointX);

        int tx = x + PLATE_PAD_X;
        int y = top + PLATE_PAD_Y;
        int ink = GuiLanguage.onTag(GuiLanguage.ink());
        if (caption != null) {
            GuiText.line(context, caption, tx, y, tw, GuiText.CAPTION, false,
                    GuiLanguage.onTag(GuiLanguage.muted()), GuiText.Align.LEFT);
            y += capH;
        }
        GuiText.line(context, name, tx, y, tw, GuiText.NAME, true, ink, GuiText.Align.LEFT);
        y += GuiText.lineHeight(GuiText.NAME, true) + 2;
        GuiText.draw(context, body.getString(), tx, y, tw, GuiText.BODY, false, ink,
                GuiText.Align.LEFT, PLATE_BODY_LINES);
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
        drawLine(context, Text.translatable("heavyseas.status.header", view.turn(), phaseLabel(view),
                        view.gulls(), GameState.GULLS_TO_LAND),
                width / 2, y, GuiLanguage.muted());
        // 海鸥画成格子而不是数字：够不够 4 只是一眼的事，不该让人去读。
        int pip = 7;
        int gap = 4;
        int span = GameState.GULLS_TO_LAND * pip + (GameState.GULLS_TO_LAND - 1) * gap;
        int x = (width - span) / 2;
        for (int i = 0; i < GameState.GULLS_TO_LAND; i++) {
            int left = x + i * (pip + gap);
            context.fill(left, y + textH() + 3, left + pip, y + textH() + 3 + pip,
                    i < view.gulls() ? GuiLanguage.verdigris() : GuiLanguage.ground());
        }
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
