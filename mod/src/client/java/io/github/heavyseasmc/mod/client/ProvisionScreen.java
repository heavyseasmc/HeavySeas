package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.game.ProvisionPhase;
import io.github.heavyseasmc.mod.net.ProvisionActionC2S;
import io.github.heavyseasmc.mod.net.ProvisionUpdateS2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 补给箱选牌界面。
 *
 * <h2>为什么这一面值得做好</h2>
 * 本作是桌游衍生，自由活动时间短 —— 玩家长时间面对的就是 GUI（ADR-0017）。
 * 而这一面是全局停留最久的：8 人局一轮传递约 70 秒，每回合都来。
 *
 * <h2>屏幕上几乎没有 UI 文字</h2>
 * 卡面自己印着名称、类别、编号、数值与规则条，所以这里<b>不再叠一遍</b>。
 * 说明只跟着高亮走一行 —— 把信息藏进互动里，而不是铺在屏幕上。
 * 那一行卡名在这一面留着：8 张一排时，卡面上印的名字小到读不出来。
 *
 * <h2>高亮从一开始就在</h2>
 * 超时要认它（决策 ⑨：自动选当前高亮那张，不是随机），所以它是<b>「你的默认答案」</b>，
 * 不能等玩家先动一下才出现。每次移动都上报服务端 —— 服务端不知道高亮的话，超时只能乱选。
 *
 * <h2>版面按行高从上往下排，卡吃掉剩下的高度</h2>
 * 窗口多大、视频设置里的界面尺寸设成几，都会改变这一面有多少 GUI 单位可用。
 * 2026-09-15 第一次在真实客户端上看（1280×720、界面尺寸自动，可用 426×240）：
 * 卡宽写死 108 的那一版，下面两行字一行只剩半截、一行整个落到屏幕外。
 * 现在文字与横杠按行高排定，卡的高度取三者最小：竖着剩下的、横着一行放得下 N 张的、
 * 像素上限以内还清楚的（{@link GameScreen#sharpCardHeight()}）。
 *
 * <h2>动效的数不写在这里</h2>
 * 「发」「抬」的时长、距离、缓动与三个语义色全部取自 {@link GuiLanguage} ——
 * ADR-0018 §7.2：同一动词在不同界面用不同时长，就又变回十种游戏了。
 */
public final class ProvisionScreen extends GameScreen {

    private static final int GAP = 6;
    private static final int SIDE = 20;
    private static final int MARGIN = 6;
    /** 座位轨：一行字，下面一条线。 */
    private static final int RAIL_H = 12;
    /** 金框画在卡外 2 像素（{@code drawBorder(-2, …)}），再留 4 像素余量。 */
    private static final int BORDER_ROOM = 2 + 4;
    private static final int BELOW_CARDS = 6;
    private static final int BAR_TO_TEXT = 3;
    private static final int HINT_GAP = 5;
    private static final int LINE_GAP = 3;
    /** 窗口小到离谱时卡也不能缩没了 —— 缩没了与「没有牌」长得一样。 */
    private static final int MIN_CARD_H = 24;

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private ProvisionUpdateS2C data;
    private int highlight;
    private long dealAt;
    /** 每张牌当前的抬起量，向目标插值 —— 直接跳变会让悬停显得很硬。 */
    private float[] lift = new float[0];
    /** 上一帧的墙钟。插值按真实毫秒推，不按帧 —— 否则高刷新率屏幕上「抬」会明显更快。 */
    private long lastFrameMs = System.currentTimeMillis();

    /** 服务端替你选的那张；{@code -1} 表示这一轮没被替选（你自己点的，或还没到点）。 */
    private int snapIndex = -1;
    /** 「顿」的起点，0 表示没在播。 */
    private long snapAt;
    /** 播完这一下就把界面收掉 —— 留牌那一包已经到了，只是被这一下拦着。 */
    private boolean closeWhenSnapDone;

    /** 见过一次「对局还在」的投影没有。见 {@link #tick()}。 */
    private boolean sawGame;

    public ProvisionScreen(ProvisionUpdateS2C data) {
        super(Text.translatable("heavyseas.provision.title"));
        apply(data);
    }

    /** 收到新的一包：换牌并重新发一次。 */
    public void apply(ProvisionUpdateS2C next) {
        boolean newOffer = this.data == null || !this.data.offer().equals(next.offer());
        this.data = next;
        if (newOffer) {
            this.dealAt = System.currentTimeMillis();
            this.highlight = 0;
            this.lift = new float[next.offer().size()];
            this.snapIndex = -1;
            this.snapAt = 0L;
            this.closeWhenSnapDone = false;
            send(0, false);
            // 与语言无关的一行：验收要判「箱子真的传到我手上了」。
            // ❗不能拿「界面：打开 ProvisionScreen」当这件事的证据 —— 界面已经开着时这里是**换牌**，不重开，
            //   那一行就不会再出现（实拍踩过：上一局的界面还开着，下一局的箱子到了，验收脚本干等了 25 秒）。
            LOGGER.info("补给箱：收到 {} 张", next.offer().size());
        }
    }

    public ProvisionUpdateS2C data() {
        return data;
    }

    /**
     * 服务端替你选了一张（ADR-0018 §6 清单第 4 条：这一下不能省）。
     *
     * <p>先把高亮挪到<b>服务端说的那一张</b>再播：服务端拿的是它最后收到的高亮，
     * 而玩家最后一次移动高亮的包可能还在路上 —— 不以服务端为准的话，
     * 屏幕上顿的那张会和真正进手里的那张不是同一张，比不做还糟。
     */
    public void autoPicked(int index) {
        if (data == null || index < 0 || index >= data.offer().size()) {
            return;
        }
        highlight = index;
        snapIndex = index;
        snapAt = System.currentTimeMillis();
        // 验收靠这一行：超时有、手动没有。抓帧看不清 396ms 时，它是第二个来源。
        LOGGER.info("补给箱：第 {} 张是替你选的（{}），播一次「顿」",
                index + 1, data.offer().get(index));
    }

    /** 「顿」还在播吗。留牌那一包到了但这一下没播完时，界面要再挂一会儿。 */
    public boolean snapping() {
        return snapAt > 0L && GuiLanguage.snap(System.currentTimeMillis(), snapAt) < 1f;
    }

    /** 播完就关。 */
    public void closeAfterSnap() {
        closeWhenSnapDone = true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;                 // 选牌不能逃 —— 逃了也只是超时替你选
    }

    /** 一帧的版面，全部以 GUI 单位计。 */
    private record Layout(int w, int h, int left, int railY, int railLeft, int railCell, int cardsTop,
                          int barY, int barX, int barW, int countdownY, int hintY, int keepY) {

        int cardX(int i) {
            return left + i * (w + GAP);
        }
    }

    /**
     * 这一帧的版面。
     *
     * <p>每帧、每次点击都按当前的 {@code width}/{@code height} 重算，不缓存：
     * 窗口随时会被拖大拖小，界面尺寸也随时会在设置里改，缓存下来的版面会跟画面对不上。
     */
    private Layout layout() {
        int n = Math.max(1, data.offer().size());
        int text = textRenderer.fontHeight;
        int below = BELOW_CARDS + BAR_H + BAR_TO_TEXT + text + HINT_GAP + text + LINE_GAP + text;
        // 卡顶要留多少空，取决于卡有多高（「顿」放大 7%，绕底边，长出来的那一截全在上面）；
        // 而卡有多高又取决于留了多少空。先按一个偏大的 h 算出空，再据此定 h ——
        // 空只会偏大一点点，卡因此略小一点点，绝不会反过来压上座位轨。
        int room = topRoom(cardHeightFor(n, RAIL_H + topRoom(0) + below));
        int fixed = RAIL_H + room + below;
        int h = cardHeightFor(n, fixed);
        int w = GuiLanguage.cardWidth(h);

        int railY = Math.max(MARGIN, (height - fixed - h) / 2);
        int cardsTop = railY + RAIL_H + room;
        int barY = cardsTop + h + BELOW_CARDS;
        int countdownY = barY + BAR_H + BAR_TO_TEXT;
        int hintY = countdownY + text + HINT_GAP;
        int rowW = n * w + (n - 1) * GAP;
        // 倒计时跟舞台一样宽：它是「这一排牌」的时间，不是屏幕上的装饰线。
        int barW = Math.min(width - 2 * SIDE, Math.max(160, rowW));
        // 座位轨按「整条链都在」时的牌距排：船头那一位看到的正是整条链的牌，每一格压在一张牌上方。
        // ❗不跟着这一次的张数走 —— 箱子往后传、牌越来越少，轨却始终是整条链，不能一格一格地缩。
        // 原先写死每格最宽 96：界面尺寸设成 1 或 2 时，轨缩在屏幕中间一截，牌却铺满全宽（真实客户端上看到的）。
        int seats = Math.max(1, data.chain().size());
        int railCell = GuiLanguage.cardWidth(cardHeightFor(seats, fixed)) + GAP;
        return new Layout(w, h, (width - rowW) / 2, railY, (width - seats * railCell) / 2, railCell,
                cardsTop, barY, (width - barW) / 2, barW, countdownY, hintY, hintY + text + LINE_GAP);
    }

    /**
     * 卡顶上要留多少空，才让最高的那一帧碰不到座位轨的线。
     *
     * <p>三件事叠起来：「抬」9 像素 · 「顿」的位移 6 像素 · 「顿」放大 7% ——
     * 卡是绕<b>底边</b>缩放的，所以长出来的那一截 {@code (scale-1)×h} 全在上面。
     *
     * <p>❗只算「抬」的那一版（本轮之前）在真实客户端上实拍到了：超时那一下弹起时，
     * 金框的上边切进了座位轨上「珠宝商」那几个字。版面留空必须把动效算进去。
     */
    private static int topRoom(int cardHeight) {
        return (int) Math.ceil(GuiLanguage.LIFT_PX + GuiLanguage.SNAP_PEAK_RISE
                + (GuiLanguage.SNAP_PEAK_SCALE - 1f) * cardHeight) + BORDER_ROOM;
    }

    /** N 张一排时卡能画多高：竖着剩下的、横着放得下的、像素上限以内还清楚的，三者取小。 */
    private int cardHeightFor(int n, int fixed) {
        int byHeight = height - 2 * MARGIN - fixed;
        int byWidth = GuiLanguage.cardHeight((width - 2 * SIDE - (n - 1) * GAP) / n);
        return Math.max(MIN_CARD_H, Math.min(Math.min(byHeight, byWidth), sharpCardHeight()));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        if (data == null || data.offer().isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long dt = Math.max(0L, Math.min(200L, now - lastFrameMs));   // 掉帧时别让插值一步跳到底
        lastFrameMs = now;
        Layout l = layout();
        float snapP = GuiLanguage.snap(now, snapAt);

        drawRail(context, l);

        // 鼠标真的动了才把高亮带过去（停着的指针不算指向，见 GameScreen#mouseActuallyMoved）。
        // ❗即使这一轮已经定了也要每帧调一次：它记的是上一帧指针在哪，停一帧就会漏掉一次移动。
        // 「顿」一开始决定就定了，之后鼠标与键盘都不该再改高亮 —— 所以判的是 decided()，
        // 不是「还在播」：播完到界面收掉之间那几帧，同样不许再改。
        boolean moved = mouseActuallyMoved(mouseX, mouseY);
        if (moved && !decided()) {
            int hovered = indexAt(mouseX, mouseY, l);
            if (hovered >= 0 && hovered != highlight) {
                setHighlight(hovered);    // 超时认高亮，鼠标与键盘两套指示不能各说各话
            }
        }

        List<String> offer = data.offer();
        for (int i = 0; i < offer.size(); i++) {
            float in = GuiLanguage.deal(now, dealAt, i);
            if (in <= 0f) {
                continue;
            }
            boolean hi = i == highlight;
            lift[i] = GuiLanguage.approach(lift[i], hi ? GuiLanguage.LIFT_PX : 0f, dt);

            context.getMatrices().push();
            // 入场：从下方抬起 + 轻微放大。全部走矩阵，不碰布局。
            float rise = (1f - in) * GuiLanguage.DEAL_RISE;
            float scale = GuiLanguage.dealScale(in);
            if (i == snapIndex) {
                // 「顿」：带过冲地弹一下再回原位。叠在「抬」之上 —— 服务端挑的要是另一张，
                // 这一下正好连「高亮挪过去了」一起说清楚。
                rise += GuiLanguage.snapRise(snapP);
                scale *= GuiLanguage.snapScale(snapP);
            }
            context.getMatrices().translate(l.cardX(i) + l.w() / 2f, l.cardsTop() + l.h() - lift[i] + rise, 0);
            context.getMatrices().scale(scale, scale, 1f);
            context.getMatrices().translate(-l.w() / 2f, -l.h(), 0);
            CardTexture.drawProvision(context, offer.get(i), 0, 0, l.w(), l.h());
            if (hi) {
                // ❗框画在同一个矩阵里：它是这张卡的一部分，得跟着卡一起升起、一起缩放。
                //   画在矩阵外面的那一版，发牌那 300ms 里框停在落点、卡还在下面往上走
                //   （2026-09-15 真实客户端上看到的）。
                // 金 = 「你 · 你选的那张」，与手牌那一面同一个用法；朱砂留给倒计时见底那一段。
                context.drawBorder(-2, -2, l.w() + 4, l.h() + 4, GuiLanguage.GOLD);
            }
            context.getMatrices().pop();
        }

        drawCountdown(context, now, data.deadlineMs(),
                Math.max(1, data.offer().size()) * ProvisionPhase.MILLIS_PER_CARD,
                l.barX(), l.barY(), l.barW(), l.countdownY());
        drawHint(context, l);
    }

    /**
     * 留牌那一包早到了，界面是被这一下「顿」拦着的 —— 播完就放它走。
     *
     * <p>❗收在 {@code tick} 不收在 {@code render}：在渲染当中把界面换掉，
     * 等于在一帧画到一半时改客户端状态。这一下结束时卡已经回到原位、缩放回 1，
     * 所以晚上至多一个 tick（50ms）看不出来。
     */
    @Override
    public void tick() {
        // ❗对局没了就收起来。这一面原先只认「传走了」那一包（{@code finished()}），而 {@code /seas end}
        //   不发那一包 —— 于是箱子开着时结束对局，界面会一直挂在屏幕上，直到下一局的箱子把它换掉
        //   （2026-09-15 实拍到的）。与行动、划船、舵手三面同一条：**收界面认投影，不认某一个包**。
        //
        // ❗「见过一次活的投影」是必要的：这一面可能比投影先到（船头那一位的箱子就在开局那一瞬间）。
        //   只判 active 的那一版实拍到的是箱子一闪即没、然后干等 16 秒超时 —— 服务端那边已经改成开局先推投影，
        //   这道闩是第二重：包的先后顺序不该让界面自己消失。
        if (projection().active()) {
            sawGame = true;
        } else if (sawGame) {
            close();
            return;
        }
        if (closeWhenSnapDone && !snapping() && client != null) {
            client.setScreen(null);
        }
    }

    /** 座位轨：箱子传到哪了。**公开信息** —— 等待要看得见（决策 ⑨）。 */
    private void drawRail(DrawContext context, Layout l) {
        List<String> chain = data.chain();
        if (chain.isEmpty()) {
            return;
        }
        int y = l.railY();
        int cell = l.railCell();
        for (int i = 0; i < chain.size(); i++) {
            int x = l.railLeft() + i * cell;
            boolean done = i < data.at();
            boolean here = i == data.at();
            context.fill(x + 2, y + 10, x + cell - 2, y + 11, done ? GuiLanguage.VERDIGRIS : GuiLanguage.GROUND);
            Text name = Text.translatable("heavyseas.character." + chain.get(i));
            context.drawCenteredTextWithShadow(textRenderer, name, x + cell / 2, y,
                    here ? GuiLanguage.GOLD : (done ? GuiLanguage.MUTED : GuiLanguage.DIM));
        }
    }

    /** 说明只跟高亮走一行 —— 每张都摊开就变成读说明书了。 */
    private void drawHint(DrawContext context, Layout l) {
        List<String> offer = data.offer();
        if (highlight < 0 || highlight >= offer.size()) {
            return;
        }
        Text name = Text.translatable("heavyseas.provision." + offer.get(highlight));
        context.drawCenteredTextWithShadow(textRenderer, name, width / 2, l.hintY(), GuiLanguage.INK);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("heavyseas.provision.keep_one", offer.size()),
                width / 2, l.keepY(), GuiLanguage.MUTED);
    }

    private int indexAt(int mouseX, int mouseY, Layout l) {
        if (mouseY < l.cardsTop() - GuiLanguage.LIFT_PX || mouseY > l.cardsTop() + l.h()) {
            return -1;
        }
        for (int i = 0; i < data.offer().size(); i++) {
            int x = l.cardX(i);
            if (mouseX >= x && mouseX < x + l.w()) {
                return i;
            }
        }
        return -1;
    }

    private void setHighlight(int index) {
        if (index == highlight || index < 0 || index >= data.offer().size()) {
            return;
        }
        highlight = index;
        send(index, false);
    }

    private void send(int index, boolean commit) {
        if (data != null && !data.offer().isEmpty()) {
            ClientPlayNetworking.send(new ProvisionActionC2S(index, commit));
        }
    }

    /** 「顿」开始之后这一轮就定了 —— 再点再按都不作数，否则会往服务端发一个已经没意义的留牌。 */
    private boolean decided() {
        return snapAt > 0L;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (data != null && !data.offer().isEmpty() && !decided()) {
            int i = indexAt((int) mouseX, (int) mouseY, layout());
            if (i >= 0) {
                send(i, true);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (data == null || data.offer().isEmpty() || decided()) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> {
                setHighlight(Math.max(0, highlight - 1));
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                setHighlight(Math.min(data.offer().size() - 1, highlight + 1));
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                send(highlight, true);
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }
}
