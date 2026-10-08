package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.UseProvisionC2S;
import io.github.heavyseasmc.mod.net.CardActionC2S;
import io.github.heavyseasmc.mod.net.CatalogS2C;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.HudView;
import io.github.heavyseasmc.mod.ui.SheetLayout;
import io.github.heavyseasmc.mod.ui.HudLayout.Rect;
import net.minecraft.client.gui.DrawContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 手牌：看自己留下来的东西。
 *
 * <h2>为什么这一面是当前最要紧的缺口</h2>
 * 补给箱那一面让玩家挑了一张牌，然后那张牌就<b>消失了</b> —— 引擎里它进了手牌，
 * 屏幕上哪儿都没有。挑完即失踪，等于告诉玩家刚才那个选择没有意义，
 * 而「留哪张」正是本作头一个真正的决策。
 *
 * <h2>版面就是那一套带位（ADR-0037 §7.10）</h2>
 * 上带 · 座位轨 · 倒计时 · 身份行四条带由 {@code GameScreen} 排定，这一面只填舞台那一格：
 * 手牌摊成一排，吃掉舞台减去底下两行（爱恨 · 键位）之后剩下的全部高度。
 *
 * <p>这一面<b>没有倒计时</b>，因为它不会超时 —— 那一条带空着。
 * 空着与挪了位置是两回事：会超时的界面必须画它，不会超时的界面必须不画。
 *
 * <h2>放大镜去掉了</h2>
 * 第四刀之前这一面是「上面一张大图 + 下面一排小图」。大图当初是为了读卡面上的规则条，
 * 而第三刀把规则条整条从牌面上拿掉了（说明改走提示签），大图于是只是同一张牌画两遍 ——
 * 带位版面里它还把那一排挤成一条。现在只有一排，牌反而比原先那张大图还高。
 *
 * <h2>屏幕上几乎没有 UI 文字</h2>
 * 卡面自己印着名称与编号，这里一律不叠（用户 2026-09-15 看过真实客户端：卡名图上有）。
 *
 * <h2>动效的数不写在这里</h2>
 * 「发」与「抬」的时长、距离、缓动全部取自 {@link GuiLanguage} —— ADR-0018 §7.2：
 * 同一动词在不同界面用不同时长，就又变回十种游戏了。
 */
public final class HandScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private static final int HAND_GAP = 8;
    private HudView view = HudView.IDLE;

    /**
     * 当前看的是第几张。<b>一进来就有</b>，不等玩家先动一下。
     *
     * <p>手牌与面前两排共用这一个下标（{@link HandCursor}，审查 2026-10-07 Z1）：{@code [0, 手牌张数)} 是手牌，
     * 往后是面前那一排。原先面前的牌「不进高亮与命中」，亮出去的酒、伞、信号枪在这一面上再也打不出来。
     */
    private int selected;

    /** 每张的抬起量，向目标插值。下标与手牌对齐。选中那张「抽出来」的进度就是它（ADR-0049）。 */
    private float[] lift = new float[0];
    /** 面前那一排每张的抬起量（选中面前的牌时它也「抽出来」）。 */
    private float[] frontLift = new float[0];
    /** 手牌少了一张、多了一张，其余的滑到新位置（按「哪一张」认，ADR-0049）。 */
    private final CardRow.Slide slide = new CardRow.Slide();
    /** 抽出来往上提多少（GUI 单位），每帧随窗口算。 */
    private float pullLift;

    /** 这一批「发」从第几张起、什么时候开始 —— 已经在手上的牌不重发。 */
    private int dealtFrom;
    private long dealtAt;

    /** 打开过了。见 {@link #init()}。 */
    private boolean opened;

    /**
     * 焦点在不在「亮出」那颗按钮上（ADR-0043 D2）：按 ↓ 挪过去、↑ 挪回来；平时 Enter 是「打出」。
     * 只对手牌有意义 —— 面前的牌没有亮出这回事。换了选中就清掉（审查 2026-10-07 Z2 ③：原先换选中不清，亮出的是另一张）。
     */
    private boolean revealFocus;
    /** 亮出要按两下（{@link RevealConfirm}，审查 2026-10-07 Z2）：第一下按钮变色、字改成「再按一次亮出」。 */
    private final RevealConfirm revealConfirm = new RevealConfirm();
    /** 第二枚按钮这一帧画在哪（手牌是「亮在面前」，面前的牌是「赠送」）；没画就是 {@code null}。 */
    private Box secondBox;
    /** 这一帧那一排牌的落位：按位置认牌（左键选 · 右键看）要用。 */
    private int rowLeft;
    private int rowTop;
    private int rowStep;
    private int rowW;
    private int rowH;
    /** 面前那一排的左沿与间距（同一个顶、同一张牌的大小）。 */
    private int frontLeft;
    private int frontStep;

    public HandScreen() {
        super(Text.translatable("heavyseas.hand.title"));
    }

    /**
     * ❗{@code init} 不只在打开时调：窗口每改一次尺寸、界面尺寸每改一次，Minecraft 都会重新调它。
     * 发牌与选中只该在<b>打开</b>时初始化 —— 否则拖一下窗口，整手牌就重新「发」一遍，选中也跳回第一张。
     */
    @Override
    protected void init() {
        super.init();                         // 先让 GameScreen 忘掉鼠标位置：坐标系可能刚变过
        if (opened) {
            return;
        }
        opened = true;
        view = currentView();
        selected = 0;
        lift = new float[view.hand().size()];
        frontLift = new float[view.front().size()];
        dealtFrom = 0;                       // 一进来整手都是「新到你面前」，整排发一次
        dealtAt = System.currentTimeMillis();
        GuiSound.dealt(dealtAt, view.hand().size());
    }

    private HudView currentView() {
        return client == null || client.world == null
                ? HudView.IDLE
                : GameComponents.of(client.world).hudView();
    }

    /**
     * 每帧对一次投影。
     *
     * <p>❗手牌变长时<b>只发新来的那几张</b>。整排重发会让「又拿到一张」读成「重新发牌」，
     * 而「发」这个动词的含义是「新东西到你面前」，不是「这里有牌」。
     */
    private void refresh() {
        HudView next = currentView();
        int before = view.hand().size();
        int after = next.hand().size();
        view = next;
        if (after != lift.length) {
            float[] grown = new float[after];
            System.arraycopy(lift, 0, grown, 0, Math.min(lift.length, after));
            lift = grown;
        }
        if (after > before) {
            dealtFrom = before;
            dealtAt = System.currentTimeMillis();
            GuiSound.dealt(dealtAt, after - before);
        }
        int front = next.front().size();
        if (front != frontLift.length) {
            float[] grown = new float[front];
            System.arraycopy(frontLift, 0, grown, 0, Math.min(frontLift.length, front));
            frontLift = grown;
        }
        int clamped = HandCursor.clamp(selected, after, front);
        if (clamped != selected) {
            select(clamped);
        }
        if (HandCursor.inFront(selected, after)) {
            revealFocus = false;              // 亮出之后选中落到了面前那一排：面前的牌没有「亮出」，焦点没有着落了
        }
        if (after + front == 0 && inspecting()) {
            toggleInspect();                  // 最后一张打出 / 送走之后，叠里什么都不剩
            revealFocus = false;
        }
    }

    /** 选中的那一张（手里或面前）；两排都空时是 {@code null}。 */
    private String selectedCard() {
        List<String> hand = view.hand();
        if (selected >= 0 && selected < hand.size()) {
            return hand.get(selected);
        }
        int j = selected - hand.size();
        return j >= 0 && j < view.front().size() ? view.front().get(j).id() : null;
    }

    /** 选中的是面前那一排的牌吗。 */
    private boolean selectedInFront() {
        return HandCursor.inFront(selected, view.hand().size()) && selectedCard() != null;
    }

    /**
     * 对局结束或者座位没了就自己收起来。
     *
     * <p>❗关界面放在 tick 不放在 render：在 render 里换屏，这一帧余下的部分还在用一个
     * 已经 {@code removed} 的 Screen，是一类很难查的偶发崩溃。
     */
    @Override
    public void tick() {
        refresh();
        if (!view.active() || !view.seated()) {
            close();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        refresh();
        if (!view.active() || !view.seated()) {
            return;                           // 这一帧什么都不画，tick 会把它收起来
        }
        renderBackdrop(context, mouseX, mouseY, delta);

        long now = System.currentTimeMillis();
        long dt = frameDelta(now);
        List<String> hand = view.hand();
        // 照样张 b-4（用户 2026-09-30 验收清单第 4 条：「照稿子 b-4 换」）：这一面没有上带与座位轨 ——
        // 左边一张大图是选中的那张，右边一块说明签与两枚按钮，下面「手牌」「面前」两排小牌，右上「只有你看得到」的爱恨。
        Bands b = drawChrome(context, view);
        SheetLayout l = sheet();
        int s = guiScale();
        Rect sheetBox = l.sheet();

        int smallW = Math.max(8, (int) Math.round(SMALL_W * l.k() / s));
        int smallH = GuiLanguage.cardHeight(smallW);
        int gap = Math.max(1, (int) Math.round(SMALL_GAP * l.k() / s));
        // 牌底让到倒计时外圈之上（审查 2026-10-07 U3）：轮到你时这一面画行动的倒计时，照样张锚在 408 的话横杠压住牌底那一成
        int rowTop = CardRow.handRowTop(l, s, Math.round((sheetBox.y() + l.len(ROW_TOP)) / (float) s), smallH);
        int handX = Math.round((sheetBox.x() + l.len(HAND_X)) / (float) s);
        int frontMinX = Math.round((sheetBox.x() + l.len(FRONT_X)) / (float) s);
        int zoneGap = Math.max(1, (int) Math.round(ZONE_GAP * l.k() / s));
        int rightEdge = Math.round((sheetBox.right() - l.len(SECRET_RIGHT)) / (float) s);
        List<HudView.FrontCard> front = view.front();
        int frontW = front.isEmpty() ? 0 : front.size() * smallW + (front.size() - 1) * gap;

        // 手牌那一排：放得下就照样张的间距并排，放不下就叠（绝不画出板外 —— 画到外面与没画长得一样）
        int handRoom = Math.max(smallW, (front.isEmpty() ? rightEdge : Math.max(frontMinX, handX)) - handX
                - (front.isEmpty() ? 0 : zoneGap));
        int step = CardRow.step(hand.size(), smallW, gap, front.isEmpty() ? rightEdge - handX : Math.max(handRoom,
                rightEdge - handX - frontW - zoneGap));
        int handEnd = handX + (hand.isEmpty() ? 0 : (hand.size() - 1) * step + smallW);
        int frontX = Math.max(frontMinX, handEnd + zoneGap);
        int fStep = CardRow.step(front.size(), smallW, gap, Math.max(smallW, rightEdge - frontX));
        rowLeft = handX;
        this.rowTop = rowTop;
        rowStep = step;
        rowW = smallW;
        rowH = smallH;
        frontLeft = frontX;
        frontStep = fStep;
        pullLift = (float) (CardRow.PULL_LIFT * l.k() / s);
        selected = HandCursor.clamp(selected, hand.size(), front.size());

        if (mouseActuallyMoved(mouseX, mouseY)) {
            int hovered = cardAt(mouseX, mouseY);
            if (hovered >= 0) {
                select(hovered);              // 鼠标与键盘指的是同一个东西，不能各说各话
            }
        }

        // 面前那一排也进选中与命中（审查 2026-10-07 Z1）：选中的那张同样「抽出来」、带金框
        int frontSel = HandCursor.inFront(selected, hand.size()) ? selected - hand.size() : -1;
        drawFront(context, front, frontX, rowTop + smallH, smallW, smallH, fStep, frontSel, dt);
        if (!view.love().isEmpty()) {
            drawSecret(context, l);
        }

        playBox = null;
        secondBox = null;
        if (hand.isEmpty() && front.isEmpty()) {
            boolean waiting = view.phase() == Phase.PROVISION && !view.endgame().active();
            drawLine(context, Text.translatable(waiting ? "heavyseas.hand.empty" : "heavyseas.hand.empty_none"),
                    width / 2, Math.round((sheetBox.y() + l.len(BIG_Y + 200)) / (float) s), GuiLanguage.dim());
        } else {
            if (!hand.isEmpty()) {
                float[] target = new float[hand.size()];
                for (int i = 0; i < hand.size(); i++) {
                    target[i] = handX + i * step;
                }
                float[] at = slide.positions(hand, target, dt);
                boolean stacked = hand.size() > 1 && step < smallW;
                for (int i = 0; i < hand.size(); i++) {
                    lift[i] = GuiLanguage.approach(lift[i], i == selected ? pullLift : 0f, dt);
                    if (i != selected) {
                        drawHandCard(context, now, hand, i, at[i], rowTop, smallW, smallH,
                                stacked && i > 0 && i - 1 != selected);
                    }
                }
                if (selected < hand.size()) {
                    drawHandCard(context, now, hand, selected, at[selected], rowTop, smallW, smallH, false);
                }
            }
            String card = selectedCard();
            if (card != null) {
                drawBig(context, l, card);
                drawInfo(context, l, card, selectedInFront(), now);
            }
        }
        // 两排的小标题（样张 .zone：13px、字距 4、70%）—— 在牌之后画：选中的那张抬起来时金框不盖住它
        // ❗样张把它摆在 376，而样张里选中的是第二张。第一张被选中时抬 16、金框再往外 8，金框上沿落在 384，
        //   正好穿过 376 起的那一行字（用户 2026-10-01 演示时指出）。所以行框底让到金框上沿之上 ——
        //   按夹过梯子之后的真实行高算：小窗口下字会被夹大一档，写死一个 y 就又压上去了。
        //   ADR-0049 之后选中那张是「抽出来」：提得更高、还往左转，右上角升得最高 —— 按抽出来那一截算。
        pxBegin(context);
        int zonePx = l.len(ZONE_PX);
        int frameTop = (int) Math.floor(rowTop * s - CardRow.pullRoom(CardRow.PULL_LIFT * l.k(), smallW * s,
                smallH * s, l.len(SEL_OUT)));
        int zoneY = frameTop - l.len(ZONE_CLEAR) - GuiText.linePxAt(zonePx, false);
        GuiText.drawPx(context, Text.translatable("heavyseas.hand.zone").getString(), handX * s, zoneY, l.len(200),
                zonePx, false, GuiLanguage.Hud.alpha(GuiLanguage.ink(), 0.7f), GuiText.Align.LEFT, l.len(4));
        if (!front.isEmpty()) {
            GuiText.drawPx(context, Text.translatable("heavyseas.hand.front").getString(), frontX * s, zoneY, l.len(200),
                    zonePx, false, GuiLanguage.Hud.alpha(GuiLanguage.ink(), 0.7f), GuiText.Align.LEFT, l.len(4));
        }
        pxEnd(context);
        // 提示那一行：←→ 换一张 · G 行动（轮到你时）· T 赠送（送得出去时）· U 查看 · Esc 收起。打出与亮出是上面两枚按钮。
        // 轮到你时这一面也画行动的倒计时（ADR-0095 B3）：开着手牌看水的时候，60 秒原先在后台无声走完。
        List<KeyHint> right = new java.util.ArrayList<>();
        if (view.myTurnToAct()) {
            right.add(keys("act", HeavySeasClient.actKey().getBoundKeyLocalizedText().getString()));
        }
        if (canGive()) {
            right.add(keys("gift", "T"));
        }
        right.add(keys(inspecting() ? "close" : "inspect", "U"));
        right.add(keys("close", "Esc"));
        drawFootBand(context, b, List.of(keys("swap_card", "←", "→")), right, now,
                view.myTurnToAct() ? new Countdown(view.actionDeadlineMs(), view.actionWindowMs(), 0) : null);
    }

    /** 这一面没有上带（样张 b-4）。 */
    @Override
    protected void drawTopBand(DrawContext context, HudView v, Bands b) {
    }

    /** 这一面没有座位轨（样张 b-4）。 */
    @Override
    protected void drawRailBand(DrawContext context, HudView v, Bands b) {
    }

    // 样张 b-4 的几何（稿子像素，相对板的左上角）：.hand-focus { left: 120; top: 96 } 300 宽 ·
    // .hand-info { left: 470; top: 100; width: 400 } · .zone { top: 376 } · .hand-row { left: 470 / 840; top: 408 }，
    // 小牌 104 宽、间距 14 · .secret { right: 64; top: 100; width: 216 }。
    private static final double BIG_X = 120;
    private static final double BIG_Y = 96;
    private static final double BIG_W = 300;
    private static final double INFO_X = 470;
    private static final double INFO_Y = 100;
    private static final double INFO_W = 400;
    private static final double ZONE_PX = 13;
    /** 选中那一圈金框（样张 .sel：外 3 深金 · 4 金 · 1 深金）往牌外扩多少。 */
    private static final double SEL_OUT = 8;
    /** 小标题的行框底离抬起后的金框上沿至少多远。 */
    private static final double ZONE_CLEAR = 4;
    private static final double ROW_TOP = 408;
    private static final double HAND_X = 470;
    private static final double FRONT_X = 840;
    private static final double SMALL_W = 104;
    private static final double SMALL_GAP = 14;
    /** 「手牌」一排与「面前」一排之间至少隔多少（手牌多到挤过去时，面前往右让）。 */
    private static final double ZONE_GAP = 30;
    private static final double SECRET_RIGHT = 64;
    private static final double SECRET_Y = 100;
    private static final double SECRET_W = 216;

    /** 两枚按钮（样张 .acts：打出 · 亮在面前，间距 14，签子下面 20）；点击命中用，GUI 单位。 */
    private Box playBox;

    /** 左边那张大图：选中的那一张，300 稿子像素宽（样张 .hand-focus）。 */
    private void drawBig(DrawContext context, SheetLayout l, String card) {
        int s = guiScale();
        Rect sheetBox = l.sheet();
        int x = Math.round((sheetBox.x() + l.len(BIG_X)) / (float) s);
        int y = Math.round((sheetBox.y() + l.len(BIG_Y)) / (float) s);
        int w = Math.max(8, (int) Math.round(BIG_W * l.k() / s));
        int h = GuiLanguage.cardHeight(w);
        context.getMatrices().push();
        context.getMatrices().translate(x, y, 0);
        drawCardShadow(context, w, h, 0f);
        CardTexture.drawProvision(context, card, 0, 0, w, h);
        context.getMatrices().pop();
    }

    /**
     * 右边的说明签与两枚按钮（样张 .hand-info）：类别 · 手牌 i / n / 牌名 / 效果；下面 [Enter 打出] [↓ Enter 亮在面前]
     * （选中面前的牌时第二枚是 [T 赠送]，面前的牌没有亮出这回事，审查 2026-10-07 Z1）。
     * 亮出不可逆（规则 §5.2）：先按 ↓ 把焦点挪过去（ADR-0043 D2），再按两下回车（或点两下）—— 第一下那一枚变朱砂、
     * 字改成「再按一次亮出」（{@link RevealConfirm}，审查 2026-10-07 Z2）。焦点在哪一枚，哪一枚就是金圈。
     *
     * <p>效果那一段最多两行（原先三行）：手牌那一排让到倒计时之上以后（U3），三行的签子连同按钮会压到「手牌」那一行小标题
     * （1280×720 下叠 22 px）。现有的牌两种语言都在两行以内，放不下时 {@code GuiText} 先缩字。
     */
    private void drawInfo(DrawContext context, SheetLayout l, String card, boolean inFront, long now) {
        int s = guiScale();
        double k = l.k();
        Rect sheetBox = l.sheet();
        int x = sheetBox.x() + l.len(INFO_X);
        int top = sheetBox.y() + l.len(INFO_Y);
        int w = l.len(INFO_W);
        int box = w - 2 * l.len(20);
        int ink = GuiLanguage.Hud.ENAMEL_LINE;
        String effect = provisionEffect(card).getString();
        int bodyPx = l.len(17);
        int lines = GuiText.paragraphLines(effect, box, bodyPx, false, INFO_LINES);
        int h = l.len(14 + 13 * 1.4 + 26 * 1.35 + 4 + 16) + lines * l.len(17 * 1.65);
        pxBegin(context);
        GuiMaterial.hudPart(context, io.github.heavyseasmc.mod.ui.HudPart.ENAMEL, x, top, w, h, k);
        int tx = x + l.len(20);
        int y = top + l.len(14);
        io.github.heavyseasmc.mod.net.CatalogS2C.Provisions entry = Catalog.provision(card);
        int hand = view.hand().size();
        int nth = inFront ? selected - hand + 1 : selected + 1;
        int count = inFront ? view.front().size() : hand;
        // lang 键写成字面量（checkLangKeys 只认字面量，不拼）
        String caption = entry == null
                ? Text.translatable(inFront ? "heavyseas.hand.position_front" : "heavyseas.hand.position", nth, count)
                .getString()
                : Text.translatable(inFront ? "heavyseas.hand.caption_front" : "heavyseas.hand.caption",
                Text.translatable("heavyseas.category." + entry.category()), nth, count).getString();
        int capPx = l.len(13);
        GuiText.drawPx(context, caption, tx, y + (l.len(13 * 1.4) - GuiText.linePxAt(capPx, false)) / 2, box, capPx, false,
                GuiLanguage.Hud.alpha(ink, 0.7f), GuiText.Align.LEFT, l.len(4));
        y += l.len(13 * 1.4);
        int namePx = l.len(26);
        GuiText.drawPx(context, provisionName(card).getString(), tx, y + (l.len(26 * 1.35) - GuiText.linePxAt(namePx, true)) / 2,
                box, namePx, true, ink, GuiText.Align.LEFT, l.len(4));
        y += l.len(26 * 1.35 + 4);
        GuiText.paragraphPx(context, effect, tx, y, box, bodyPx, false, ink, INFO_LINES, l.len(17 * 1.65));

        // 两枚按钮：签子下面 20，间距 14（样张 .acts）。
        // 「打出」只给打得出的牌（2026-10-07 用户实拍「按 Enter 没反应」）：水、武器、财宝不是打出用的，不画这一枚
        // （点下去时说它在哪儿用，见 use()）；
        // 特殊行动没轮到你时画成按不动的样子并写明（界面给出一件必然失败的事，比不给更糟）。
        // ❗第一格不论画不画都占住位置（审查 2026-10-07 Z2 ①）：原先打不出的牌不画「打出」，「亮出」挪进第一格 ——
        //   鼠标点「原来打出的位置」就是亮出。宽度取两种字样里宽的那一个，「没轮到你」变成「打出」时第二枚也不挪。
        int by = top + h + l.len(20);
        int bh = l.len(io.github.heavyseasmc.mod.ui.SheetLayout.HINTS_H);
        CatalogS2C.Play when = playWhen(card);
        boolean live = playableNow(card);
        String play = Text.translatable(!live && when == CatalogS2C.Play.TURN ? "heavyseas.hand.play_wait"
                : "heavyseas.keys.play").getString();
        int w1 = Math.max(buttonPx(l, List.of("Enter"), Text.translatable("heavyseas.keys.play").getString()),
                buttonPx(l, List.of("Enter"), Text.translatable("heavyseas.hand.play_wait").getString()));
        playBox = null;
        if (when.playable()) {
            drawButtonPx(context, l, x, by, w1, bh, List.of("Enter"), play, live && !revealFocus, live, false);
            playBox = unitBox(new Rect(x, by, w1, bh));
        }
        int x2 = x + w1 + l.len(14);
        if (inFront) {
            // 面前的牌：第二枚是「赠送」（T，与座位面板同一件事；面前的牌没有亮出这回事）
            String give = Text.translatable("heavyseas.trade.give").getString();
            int w2 = buttonPx(l, List.of("T"), give);
            drawButtonPx(context, l, x2, by, w2, bh, List.of("T"), give, false, canGive(), false);
            secondBox = unitBox(new Rect(x2, by, w2, bh));
        } else {
            boolean armed = revealConfirm.armed(card, selected, now);
            String front = Text.translatable(armed ? "heavyseas.keys.reveal_confirm" : "heavyseas.keys.reveal_front")
                    .getString();
            int w2 = buttonPx(l, List.of("↓", "Enter"), front);
            drawButtonPx(context, l, x2, by, w2, bh, List.of("↓", "Enter"), front, (revealFocus || armed) && canReveal(),
                    canReveal(), armed);
            secondBox = unitBox(new Rect(x2, by, w2, bh));
        }
        pxEnd(context);
    }

    /** 说明签里效果那一段最多几行（见 {@link #drawInfo}）。 */
    private static final int INFO_LINES = 2;

    /** 判据与座位面板共用一份（{@link CardPlay}，ADR-0095 A3）。 */
    private static CatalogS2C.Play playWhen(String card) {
        return CardPlay.when(card);
    }

    /**
     * 此刻按「打出」行不行得通（{@link CardPlay#playableNow}，手里与面前的牌同一份判据，含「已撑开的伞」「被抢那一刻不能喝」）。
     * 服务端仍会再核一遍（这一回合喝过了、没人受伤、没有尸体 —— 投影里没有，判不了），拒绝的那句由 {@link ActionBarEcho} 显出来。
     */
    private boolean playableNow(String card) {
        return CardPlay.playableNow(view, card);
    }

    private static int buttonPx(SheetLayout l, List<String> keys, String label) {
        int w = 2 * l.len(20);
        for (String k : keys) {
            w += Math.max(l.len(26), GuiText.widthPx(k, l.len(14), true, 0) + 2 * l.len(7)) + l.len(7);
        }
        return w + l.len(10) - l.len(7) + GuiText.widthPx(label, l.len(18), false, l.len(2));
    }

    /**
     * 一枚搪瓷按钮（样张 .btn；{@code focus} 时外一圈金，.btn.focus）：键帽 + 一句话。
     * {@code enabled = false}：按不动 —— 字与键帽上的字压成三四成墨，不画金圈。
     * {@code armed}：「再按一次」那一态（亮出的第一下，Z2）—— 字与键帽上的字换成朱砂（紧迫 · 不可逆），外一圈金。
     */
    private static void drawButtonPx(DrawContext context, SheetLayout l, int x, int y, int w, int h, List<String> keys,
                                     String label, boolean focus, boolean enabled, boolean armed) {
        GuiMaterial.hudPart(context, focus ? io.github.heavyseasmc.mod.ui.HudPart.BTN_FOCUS
                : io.github.heavyseasmc.mod.ui.HudPart.BTN, x, y, w, h, l.k());
        int ink = armed && enabled ? GuiLanguage.Hud.LOG_CINNABAR
                : enabled ? GuiLanguage.Hud.ENAMEL_LINE : GuiLanguage.Hud.alpha(GuiLanguage.Hud.ENAMEL_LINE, 0.4f);
        int cx = x + l.len(20);
        int key = l.len(26);
        int cy = y + h / 2;
        int keyPx = l.len(14);
        for (String k : keys) {
            int kw = Math.max(key, GuiText.widthPx(k, keyPx, true, 0) + 2 * l.len(7));
            GuiMaterial.hudPart(context, io.github.heavyseasmc.mod.ui.HudPart.KEY, cx, cy - key / 2, kw, key, l.k());
            GuiText.drawPx(context, k, cx, cy - key / 2 + (key - GuiText.linePxAt(keyPx, true)) / 2, kw, keyPx, true,
                    ink, GuiText.Align.CENTER, 0);
            cx += kw + l.len(7);
        }
        cx += l.len(10) - l.len(7);
        int px = l.len(18);
        GuiText.drawPx(context, label, cx, cy - GuiText.linePxAt(px, false) / 2, x + w - cx, px, false,
                ink, GuiText.Align.LEFT, l.len(2));
    }

    /**
     * 右上「只有你看得到」（样张 .secret）：一枚眼睛 + 一句小标题；爱（朱砂的心）· 恨（碎心）各一行：头像 38 + 名字 17px。
     * 全程保密（规则里也不许亮出来证明自己）—— 手牌一面本来就只有你看得到。
     */
    private void drawSecret(DrawContext context, SheetLayout l) {
        double k = l.k();
        Rect sheetBox = l.sheet();
        int w = l.len(SECRET_W);
        int x = sheetBox.right() - l.len(SECRET_RIGHT) - w;
        int top = sheetBox.y() + l.len(SECRET_Y);
        int headH = l.len(13 * 1.75);
        int rowH = l.len(44);
        int h = l.len(12) + headH + l.len(8) + 2 * rowH + l.len(14);
        int ink = GuiLanguage.Hud.ENAMEL_LINE;
        pxBegin(context);
        GuiMaterial.hudPart(context, io.github.heavyseasmc.mod.ui.HudPart.ENAMEL, x, top, w, h, k);
        int tx = x + l.len(16);
        int y = top + l.len(12);
        int icon = l.len(24);
        GuiMaterial.hudIcon(context, io.github.heavyseasmc.mod.ui.HudPart.IC_EYE, tx, y + (headH - icon) / 2, icon, icon,
                GuiLanguage.Hud.alpha(ink, 0.8f), k);
        int headPx = l.len(13);
        GuiText.drawPx(context, Text.translatable("heavyseas.hand.secret").getString(), tx + icon + l.len(8),
                y + (headH - GuiText.linePxAt(headPx, false)) / 2, w - icon - l.len(40), headPx, false,
                GuiLanguage.Hud.alpha(ink, 0.8f), GuiText.Align.LEFT, l.len(3));
        y += headH + l.len(8);
        String[] who = {view.love(), view.hate()};
        io.github.heavyseasmc.mod.ui.HudPart[] mark = {io.github.heavyseasmc.mod.ui.HudPart.IC_HEART,
                io.github.heavyseasmc.mod.ui.HudPart.IC_HATE};
        int[] color = {GuiLanguage.Hud.LOG_CINNABAR, ink};
        int tok = l.len(38);
        int namePx = l.len(17);
        // 名字前写「爱」「恨」两个字（用户 2026-10-07：「爱恨的显示很反直觉」—— 只有心与碎心，看不出哪个是恨）
        String[] word = {Text.translatable("heavyseas.hand.love").getString(), Text.translatable("heavyseas.hand.hate").getString()};
        int wordPx = l.len(17);
        int wordW = Math.max(GuiText.widthPx(word[0], wordPx, true, 0), GuiText.widthPx(word[1], wordPx, true, 0));
        for (int i = 0; i < 2; i++) {
            int cy = y + rowH / 2;
            GuiText.drawPx(context, word[i], tx, cy - GuiText.linePxAt(wordPx, true) / 2, wordW + 2, wordPx, true,
                    color[i], GuiText.Align.LEFT, 0);
            int ix = tx + wordW + l.len(6);
            GuiMaterial.hudIcon(context, mark[i], ix, cy - icon / 2, icon, icon, color[i], k);
            int tokX = ix + icon + l.len(10);
            GuiMaterial.portrait(context, who[i], tokX, cy - tok / 2, tok, 1f);
            GuiMaterial.hudPart(context, io.github.heavyseasmc.mod.ui.HudPart.TOK38_PLAIN, tokX, cy - tok / 2, tok, tok, k);
            GuiText.drawPx(context, nameOf(who[i]).getString(), tokX + tok + l.len(10), cy - GuiText.linePxAt(namePx, false) / 2,
                    x + w - (tokX + tok + l.len(10)) - l.len(12), namePx, false, ink, GuiText.Align.LEFT, 0);
            y += rowH;
        }
        pxEnd(context);
    }

    /**
     * 面前那一区（样张 b-4 的「面前」一排）：你亮出来（或别的规则落到你面前）的牌。全船都看得见（公开）。
     * 撑开的伞外面一圈铜绿：它此刻正在起作用。
     *
     * <p>它也进选中与命中（审查 2026-10-07 Z1）：原先注释写着「不在手里 —— 不进高亮与命中」，而服务端认面前的牌
     * （喝面前的酒、用面前的医疗箱、撑面前的伞、打面前的信号枪）—— 这一面于是再也打不出它们。选中的那一张与手牌一样「抽出来」、带金框。
     *
     * @param sel 选中的是这一排第几张；没选中这一排是 {@code -1}
     */
    private void drawFront(DrawContext context, List<HudView.FrontCard> front, int x, int bottom, int w, int h, int step,
                           int sel, long dt) {
        boolean stacked = front.size() > 1 && step < w;
        for (int i = 0; i < front.size(); i++) {
            if (i < frontLift.length) {
                frontLift[i] = GuiLanguage.approach(frontLift[i], i == sel ? pullLift : 0f, dt);
            }
            if (i != sel) {
                drawFrontCard(context, front.get(i), x + i * step, bottom, w, h, i,
                        stacked && i > 0 && i - 1 != sel, false);
            }
        }
        if (sel >= 0 && sel < front.size()) {
            drawFrontCard(context, front.get(sel), x + sel * step, bottom, w, h, sel, false, true);
        }
    }

    private void drawFrontCard(DrawContext context, HudView.FrontCard card, int cx, int bottom, int w, int h, int i,
                               boolean edge, boolean selectedHere) {
        float up = i < frontLift.length ? frontLift[i] : 0f;
        context.getMatrices().push();
        context.getMatrices().translate(cx + w / 2f, bottom - up, 0);
        CardRow.rotate(context, pullLift > 0f ? up / pullLift : 0f);
        context.getMatrices().translate(-w / 2f, -h, 0);
        drawCardShadow(context, w, h, up);
        if (edge) {
            CardRow.edgeShadow(context, h, guiScale(), sheet().k());
        }
        CardTexture.drawProvision(context, card.id(), 0, 0, w, h);
        if (card.open()) {
            // 两像素一圈铜绿（原先两道 drawBorder）：画在牌自己的矩阵里，跟着它一起「抽出来」
            int c = GuiLanguage.verdigris();
            context.fill(-2, -2, w + 2, 0, c);
            context.fill(-2, h, w + 2, h + 2, c);
            context.fill(-2, 0, 0, h, c);
            context.fill(w, 0, w + 2, h, c);
        }
        if (selectedHere) {
            drawCardFrame(context, w, h);
        }
        context.getMatrices().pop();
    }

    /**
     * 画一张手牌。选中那张「抽出来」（ADR-0049）：以牌底中点为轴往左转、往上提；其余压暗一点。
     *
     * @param edge 叠着、而且左边那张没被抽走：左边缘给下面那张投一道细影
     */
    private void drawHandCard(DrawContext context, long now, List<String> hand, int i, float x, int top, int w, int h,
                              boolean edge) {
        float in = GuiLanguage.deal(now, dealtAt, Math.max(0, i - dealtFrom));
        if (i >= dealtFrom && in <= 0f) {
            return;                           // 还没轮到它入场
        }
        float progress = i < dealtFrom ? 1f : in;
        float rise = (1f - progress) * GuiMetrics.units(GuiLanguage.DEAL_RISE);
        float scale = GuiLanguage.dealScale(progress);
        context.getMatrices().push();
        // 全部走矩阵，布局本身不动 —— 命中判定因此可以只看落位后的矩形。
        context.getMatrices().translate(x + w / 2f, top + h - lift[i] + rise, 0);
        CardRow.rotate(context, pullLift > 0f ? lift[i] / pullLift : 0f);
        context.getMatrices().scale(scale, scale, 1f);
        context.getMatrices().translate(-w / 2f, -h, 0);
        drawCardShadow(context, w, h, Math.abs(rise) + lift[i]);
        if (edge) {
            CardRow.edgeShadow(context, h, guiScale(), sheet().k());
        }
        CardTexture.drawProvision(context, hand.get(i), 0, 0, w, h);
        if (i == selected) {
            drawCardFrame(context, w, h);
        }
        context.getMatrices().pop();
    }

    /**
     * 指针落在第几张上（两排共用的下标）；都不在、或这一帧还没画过牌时是 -1。
     * 每一排里叠起来时上面那张说了算，所以先问选中的那张，再从右往左问（{@link CardRow#indexAt}）。
     */
    private int cardAt(double mouseX, double mouseY) {
        if (rowW <= 0) {
            return -1;
        }
        int hand = view.hand().size();
        float room = Math.max(pullLift, GuiMetrics.units(GuiLanguage.LIFT_PX));
        if (hand > 0) {
            int i = CardRow.indexAt((int) mouseX, (int) mouseY, rowLeft, rowTop, rowStep, rowW, rowH, hand,
                    selected < hand ? selected : -1, room);
            if (i >= 0) {
                return i;
            }
        }
        int front = view.front().size();
        if (front > 0) {
            int j = CardRow.indexAt((int) mouseX, (int) mouseY, frontLeft, rowTop, frontStep, rowW, rowH, front,
                    selected >= hand ? selected - hand : -1, room);
            if (j >= 0) {
                return hand + j;
            }
        }
        return -1;
    }

    /**
     * 亮出正在看的那一张（手牌才有；面前的牌没有亮出这回事）。
     *
     * <h2>为什么亮出值得有个键</h2>
     * 规则里一大半效果<b>只有亮在面前才算</b>：救生圈挡落水、阳伞能撑开、船桨让划船多抽、
     * 指南针让划船堆多一张。握在手里的那几张一点用都没有 —— 亮出不是装饰动作，是真的取舍
     * （亮了就看得见、落水时会被冲走）。
     *
     * <h2>要按两下（审查 2026-10-07 Z2）</h2>
     * 第一下（回车落在「亮出」那一枚上、或点那一枚）只把它变成朱砂的「再按一次亮出」；3 秒内对同一张再按一下才发包
     * （{@link RevealConfirm}）。换选中、别的键、超时都撤销。
     *
     * <p>亮出与赠牌共用类型化载荷；服务端只认发送者实际占用的座位。
     */
    private void pressReveal() {
        List<String> hand = view.hand();
        if (selected < 0 || selected >= hand.size() || client == null || client.player == null) {
            return;
        }
        String card = hand.get(selected);
        if (!canReveal()) {
            // 服务端会拒（昏迷 · 死了 · 终局 · 正被抢到挑牌那一刻）：当场说一句，不发一个注定被丢掉的包（ADR-0095 A8）
            revealConfirm.cancel();
            ActionBarEcho.record(Text.translatable("heavyseas.hand.cannot_reveal"));
            return;
        }
        switch (revealConfirm.press(card, selected, System.currentTimeMillis())) {
            // 与语言无关的一行：GUI 回归靠它判「第一下只是待确认，没有发出去」
            case ARMED -> LOGGER.info("手牌：亮出待确认 {}", card);
            case FIRE -> {
                // 与语言无关的一行：GUI 回归靠它判「亮出这一下真的发出去了」。
                LOGGER.info("手牌：亮出 {}", card);
                revealFocus = false;
                ClientPlayNetworking.send(CardActionC2S.of(CardActionC2S.Kind.REVEAL, card, "", 0));
            }
            case IGNORED -> {
            }
        }
    }

    /**
     * 此刻亮得出吗。与引擎 {@code Session#reveal} 的门同一套：能行动（清醒、在局里），而且不是「被抢、挑牌那一刻的被抢方」
     * （规则 §5 抢夺：那一刻把手牌亮出来就躲掉了这一抢；判据与喝酒那一道同一处，{@link CardPlay#pickedFrom}）；终局翻牌时也不再动牌。
     */
    private boolean canReveal() {
        if (!view.active() || !view.seated() || !view.condition().canAct() || view.endgame().active()) {
            return false;
        }
        return !CardPlay.pickedFrom(view);
    }

    private boolean canGive() {
        return view.phase() == Phase.ACTION && view.condition().canAct()
                && !view.contest().active() && !view.endgame().active();
    }

    /** 送出选中的那一张：手里的发 GIVE_HAND，面前的发 GIVE_FRONT（{@link CardChoiceScreen}）。 */
    private void give() {
        String card = selectedCard();
        if (canGive() && card != null) {
            openChild(new CardChoiceScreen(card, selectedInFront()));   // 送完 / Esc 回手牌
        }
    }

    /**
     * 打出正在看的那一张（手里或面前）。
     *
     * <p>不再借聊天指令：服务端确认牌的效果。医疗箱会进入目标一面，其余特殊行动一键完成；酒随时能喝（面前的那瓶也是）。
     * 服务端认面前的牌（{@code ActionPhase#holds} = 手里或面前），所以面前的牌发的是同一个包。
     */
    private void use() {
        String card = selectedCard();
        if (card == null || client == null || client.player == null) {
            return;
        }
        if (revealConfirm.coolingDown(System.currentTimeMillis())) {
            return;                           // 刚亮出：紧跟的那一下（连按、按住的重复）不变成「打出」（Z2 ④）
        }
        if (!playableNow(card)) {
            // 发出去也只会被拒（或者被静默丢掉）：当场说一句，位置与服务端那几句拒绝相同（2026-10-07「按 Enter 没反应」）。
            LOGGER.info("手牌：打不出 {}（{}）", card, playWhen(card));
            ActionBarEcho.record(CardPlay.whyNot(view, card, provisionName(card)));
            return;
        }
        LOGGER.info("手牌：打出 {}{}", card, selectedInFront() ? "（面前）" : "");
        ClientPlayNetworking.send(UseProvisionC2S.play(card));
    }

    /**
     * 换选中的那张。鼠标、方向键、张数变了，几条路都走这里。换了就撤销「亮出」的焦点与「再按一次」（Z2 ③）。
     */
    private void select(int index) {
        if (index != selected) {
            revealFocus = false;
            revealConfirm.cancel();
        }
        selected = index;
    }

    @Override
    protected boolean leftClick(double mouseX, double mouseY) {
        if (playBox != null && playBox.contains((int) mouseX, (int) mouseY)) {
            revealConfirm.cancel();
            use();                            // 样张 b-4 的第一枚按钮：打出
            return true;
        }
        if (secondBox != null && secondBox.contains((int) mouseX, (int) mouseY)) {
            if (selectedInFront()) {
                give();                       // 面前的牌：第二枚是赠送
            } else {
                pressReveal();                // 手牌：第二枚是亮在面前（点两下）
            }
            return true;
        }
        if (!inspecting()) {
            int i = cardAt(mouseX, mouseY);
            if (i >= 0) {
                // 左键点牌 = 打出这一张，与补给箱、挂武器两面「左键点牌 = 就它了」同一条（用户 2026-10-07：「左键不能使用牌，
                // 而是查看介绍」）。看说明靠悬停（停上去就选中）与右键；亮出不可逆，仍只走那枚按钮或 ↓ Enter。
                // 打不出的牌点下去只说一句为什么，什么都不花掉。面前的牌也一样（Z1）。
                select(i);
                revealConfirm.cancel();
                use();
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean rightClick(double mouseX, double mouseY) {
        revealFocus = false;
        revealConfirm.cancel();
        return inspectClick(inspecting() ? -1 : cardAt(mouseX, mouseY), this::select);
    }

    @Override
    protected int inspectedIndex() {
        return selected;
    }

    @Override
    protected boolean onKey(int keyCode, int scanCode, int modifiers) {
        boolean confirmKey = keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER;
        if (!confirmKey) {
            revealConfirm.cancel();          // 「再按一次」只认紧接着的那一下回车：别的键一律撤销（Z2）
        }
        // 赠送是 T（用户 2026-10-07）：G 在哪儿都只表示「行动」，从行动一面进手牌之后按 G 回不去行动、弹出赠送，那是两件事撞了一个键
        if (keyCode == GLFW.GLFW_KEY_T) {
            give();
            return true;
        }
        if (inspectKey(keyCode)) {
            revealFocus = false;
            return true;
        }
        int hand = view.hand().size();
        int count = hand + view.front().size();
        if (count > 0 && (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT)) {
            // 手牌与面前连成一排走（Z1）
            select(HandCursor.step(selected, keyCode == GLFW.GLFW_KEY_RIGHT ? 1 : -1, hand, view.front().size()));
            return true;
        }
        // 亮出：把正在看的那张放到面前。**不可逆**（规则 §5.2），所以不再是一个随手就按到的键：
        // 先按 ↓ 把焦点挪到「亮出」按钮上（ADR-0043 D2，用户 2026-09-25 定），再按两下 Enter（Z2）。
        // 样张 b-4 两枚按钮一直摆着，所以 ↓ / ↑ 在两个状态里都挪焦点。面前的牌没有亮出，↓ 不挪。
        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            if (selected < hand) {
                revealFocus = true;
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP) {
            revealFocus = false;
            return true;
        }
        // 打出：花掉这一个行动打出去（医疗箱 · 撑伞 · 信号枪当信号 · 绝境），或者喝一口酒。Enter 在两个状态里都是它
        // （ADR-0037 §7.12 ①「Enter 在两个状态里都能确认」）—— 除非焦点在「亮出」上。
        // ❗特殊行动只有轮到你时才行得通 —— 它占行动。打不出时客户端当场说一句（use()），不往服务端发。
        if (count > 0 && confirmKey) {
            if (revealFocus && selected < hand) {
                pressReveal();
            } else {
                use();
            }
            return true;
        }
        // 用哪个键开的，就用哪个键收起来。写死 R 的话玩家改了键位就收不起来了。
        if (HeavySeasClient.handKey().matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        // G 是「行动」（赠送已改 T）：从行动一面进来的就回去，否则把行动一面当二级页面打开（用户 2026-10-07）
        if (HeavySeasClient.actKey().matchesKey(keyCode, scanCode) && view.active() && view.seated()) {
            if (parent() instanceof ActionScreen) {
                close();
            } else {
                openChild(new ActionScreen());
            }
            return true;
        }
        return super.onKey(keyCode, scanCode, modifiers);
    }
}
