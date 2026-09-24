package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.game.ProvisionPhase;
import io.github.heavyseasmc.mod.net.ProvisionActionC2S;
import io.github.heavyseasmc.mod.net.ProvisionUpdateS2C;
import io.github.heavyseasmc.mod.state.HudView;
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
 * 现在文字与横杠按行高排定，卡的高度由 {@link GameScreen#cardHeightFor} 取几者最小。
 *
 * <h2>动效的数不写在这里</h2>
 * 「发」「抬」的时长、距离、缓动与三个语义色全部取自 {@link GuiLanguage} ——
 * ADR-0018 §7.2：同一动词在不同界面用不同时长，就又变回十种游戏了。
 */
public final class ProvisionScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private ProvisionUpdateS2C data;
    private int highlight;
    private long dealAt;
    /** 每张牌当前的抬起量，向目标插值 —— 直接跳变会让悬停显得很硬。 */
    private float[] lift = new float[0];

    /** 服务端替你选的那张；{@code -1} 表示这一轮没被替选（你自己点的，或还没到点）。 */
    private int snapIndex = -1;
    /** 「顿」的起点，0 表示没在播。 */
    private long snapAt;
    /** 播完这一下就把界面收掉 —— 留牌那一包已经到了，只是被这一下拦着。 */
    private boolean closeWhenSnapDone;
    /** 你自己按下去留的是第几张；{@code -1} = 还没按（或是替你选的，那一张在 {@link #snapIndex}）。 */
    private int committedIndex = -1;
    /**
     * 「传」的起点（O21）：留下的那张「飞」进你自己的座位，其余「滑」到下一位。0 = 没在播。
     * 替你选的那一路要等「顿」播完才开始，所以它可能是一个将来的时刻。
     */
    private long passAt;
    /** 这一次「传」里留下的是第几张。 */
    private int keptIndex = -1;

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
            this.committedIndex = -1;
            this.passAt = 0L;
            this.keptIndex = -1;
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

    /**
     * 箱子从你手上传走了（服务端认下了你留的那张）：开始「传」—— 留下的那张「飞」进你自己的座位，
     * 其余几张「滑」到下一位（O21 · ADR-0018 §7.2：飞 = 牌换了主人，滑 = 轮到下一个人）。
     *
     * <p>❗落点是<b>座位轨上你自己那一格</b>：这一面没有手牌区，而轨上那一格就是「你」（金）。
     * 替你选的那一路先播完「顿」再传 —— 两件事各说各的，不叠在一起。
     *
     * @return 这一下要不要播（有牌可传才播）；重复调用不会重来
     */
    public boolean beginPass() {
        if (passAt > 0L) {
            return true;
        }
        if (data == null || data.offer().isEmpty()) {
            return false;
        }
        keptIndex = snapIndex >= 0 ? snapIndex : committedIndex >= 0 ? committedIndex : highlight;
        long now = System.currentTimeMillis();
        passAt = snapAt > 0L ? Math.max(now, snapAt + GuiLanguage.SNAP_MS) : now;
        LOGGER.info("补给箱：第 {} 张飞进你的座位，其余 {} 张滑到下一位", keptIndex + 1, data.offer().size() - 1);
        return true;
    }

    /** 「顿」或「传」还在播吗 —— 播完之前界面不收（牌离开你手里的那一下不能被截断）。 */
    public boolean busy() {
        long now = System.currentTimeMillis();
        return snapping() || (passAt > 0L && (GuiLanguage.flying(now, passAt) || GuiLanguage.sliding(now, passAt)));
    }

    /** 播完就关。 */
    public void closeAfterSnap() {
        closeWhenSnapDone = true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;                 // 选牌不能逃 —— 逃了也只是超时替你选
    }

    /**
     * 一帧的版面，全部以 GUI 单位计。带位归 {@link Bands}，这里只排舞台那一格里的东西。
     *
     * @param perRow  一排几张（一排就是全部；两排时上排多一张）
     * @param rowStep 上下两排之间隔多远（牌高 + 「顿」要的留空）
     * @param screenW 窗口宽：每一排各自居中
     */
    private record Layout(int w, int h, int cardsTop, int rowW, int n, int perRow, int rowStep, int screenW) {

        int cardX(int i) {
            int row = i / perRow;
            int inRow = Math.min(perRow, n - row * perRow);
            int rowLeft = (screenW - cardRowWidth(inRow, w)) / 2;
            return rowLeft + (i - row * perRow) * (w + CARD_GAP);
        }

        int cardTop(int i) {
            return cardsTop + (i / perRow) * rowStep;
        }

        int left() {
            return (screenW - rowW) / 2;
        }
    }

    /** 一排牌（连头上的留空）占不到舞台的这个比例时，才考虑排成两排。 */
    private static final float TWO_ROWS_EMPTY = 0.5f;

    /**
     * 这一帧舞台里的版面。
     *
     * <p>每帧、每次点击都按当前的 {@code width}/{@code height} 重算，不缓存：
     * 窗口随时会被拖大拖小，界面尺寸也随时会在设置里改，缓存下来的版面会跟画面对不上。
     */
    private Layout layout(Bands b) {
        int n = Math.max(1, data.offer().size());
        // ❗牌名不再单占一行：第三刀之后它印在牌上（CardTexture 实时排字）。
        // 同一个名字在屏幕上出现两次是这一刀带出来的重复，省下的高度全还给牌 —— 用户 2026-09-22：「卡牌太小」。
        // 舞台整格都给牌：规矩那一句拿掉了（箱子怎么传，动效已经演出来了），
        // 牌自己的内容藏在查看态里（按 U / 右键）。
        int bottom = b.stageBottom();
        int avail = bottom - b.stageTop();
        // 卡顶要留多少空，取决于卡有多高（「顿」放大 7%，绕底边，长出来的那一截全在上面）；
        // 而卡有多高又取决于留了多少空。先按一个偏大的 h 算出空，再据此定 h ——
        // 空只会偏大一点点，卡因此略小一点点，绝不会反过来压上座位轨。
        int room = snapRoom(cardHeightFor(n, avail - snapRoom(0)));
        int h = cardHeightFor(n, avail - room);
        int perRow = n;
        // 一排放不下、卡被宽度卡小时，试两排（总纲 §7.8：舞台先给牌）。
        // ❗界面尺寸 1 时一排八张被宽度卡在舞台高的四成，上下各空一大截（O32，2026-09-24 实拍）；
        //   界面尺寸自动（1280×720 下是 3）时两排反而更小，照旧一排 —— 按算出来的大小选，不按界面尺寸写死。
        // ❗判的是「一排之后舞台还空着一大半」，不是「两排大多少」：两排时每排头上都要留「顿」的空，
        //   界面尺寸 1 的八张牌换成两排只大 6%（209 → 222 个单位，实测）—— 牌几乎没长，舞台却从空一大半变成铺满。
        //   用户抱怨的是后者（O32：「舞台空了一大半」）。两排不许比一排小。
        if (n >= 4 && h + room < avail * TWO_ROWS_EMPTY) {
            int per2 = (n + 1) / 2;
            int room2 = snapRoom(cardHeightFor(per2, (avail - 2 * snapRoom(0)) / 2));
            int h2 = cardHeightFor(per2, (avail - 2 * room2) / 2);
            if (h2 >= h) {
                perRow = per2;
                room = room2;
                h = h2;
            }
        }
        int rows = (n + perRow - 1) / perRow;
        int w = GuiLanguage.cardWidth(h);
        int rowStep = h + room;
        int cardsTop = cardsTopIn(b.stageTop(), bottom, h + (rows - 1) * rowStep, room);
        int rowW = cardRowWidth(perRow, w);
        return new Layout(w, h, cardsTop, rowW, n, perRow, rowStep, width);
    }

    /** 座位轨是<b>这一条链</b>（箱子传到哪了），不是座位序 —— 所以覆写它。**公开信息**，等待要看得见（决策 ⑨）。 */
    @Override
    protected void drawRailBand(DrawContext context, HudView view, Bands b) {
        List<String> chain = data == null ? List.of() : data.chain();
        if (chain.isEmpty() || b.railH() == 0) {
            return;
        }
        int cell = railCell(chain.size());
        int left = (width - chain.size() * cell) / 2;
        for (int i = 0; i < chain.size(); i++) {
            boolean done = i < data.at();
            boolean here = i == data.at();
            // 每个名字只许占自己那一格：格子窄（界面尺寸 1、八个人）时缩字号，绝不压到邻座。
            drawSeat(context, chain.get(i), left + i * cell, b.railY(), cell, b, here ? GuiLanguage.gold() : 0,
                    !done && !here, here ? GuiLanguage.gold() : (done ? GuiLanguage.muted() : GuiLanguage.dim()),
                    done ? GuiLanguage.verdigris() : GuiLanguage.ground());
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        if (data == null || data.offer().isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long dt = frameDelta(now);
        Bands b = drawChrome(context, projection());
        Layout l = layout(b);
        Inspect in = inspect(b);
        float gathered = gathered(now);
        float snapP = GuiLanguage.snap(now, snapAt);

        // 鼠标真的动了才把高亮带过去（停着的指针不算指向，见 GameScreen#mouseActuallyMoved）。
        // ❗即使这一轮已经定了也要每帧调一次：它记的是上一帧指针在哪，停一帧就会漏掉一次移动。
        // 「顿」一开始决定就定了，之后鼠标与键盘都不该再改高亮 —— 所以判的是 decided()，
        // 不是「还在播」：播完到界面收掉之间那几帧，同样不许再改。
        // ❗查看态里不认悬停：牌都叠在一起了，命中框却还在摊开时那一排的位置上 ——
        //   指针没动、看的牌却被「指」到了别处，与「停着的指针不算指向」同一类。
        //   查看态里换牌只走 ←→（与摊开时同一个键，同一件事）。
        boolean moved = mouseActuallyMoved(mouseX, mouseY);
        if (moved && !decided() && !inspecting()) {
            int hovered = indexAt(mouseX, mouseY, l);
            if (hovered >= 0 && hovered != highlight) {
                setHighlight(hovered);    // 超时认高亮，鼠标与键盘两套指示不能各说各话
            }
        }

        List<String> offer = data.offer();
        // 高亮那一张最后画：收成一叠时它在堆顶，摊开时它抬起来 —— 两种状态下它都必须压在别人上面。
        for (int i = 0; i < offer.size(); i++) {
            if (i != highlight) {
                drawOne(context, now, dt, b, l, in, gathered, snapP, offer, i);
            }
        }
        if (highlight >= 0 && highlight < offer.size()) {
            drawOne(context, now, dt, b, l, in, gathered, snapP, offer, highlight);
        }

        if (gathered > 0f && passAt == 0L && highlight >= 0 && highlight < offer.size()) {
            String card = offer.get(highlight);
            drawCardPlate(context, in.plateX(), in.plateY(), in.plateW(), -1,
                    provisionCaption(card), provisionName(card), provisionEffect(card));
        }
        drawFootBand(context, b, List.of(keys("select", "←", "→")), inspectHints("confirm"), now,
                new Countdown(data.deadlineMs(),
                        Math.max(1, data.offer().size()) * ProvisionPhase.MILLIS_PER_CARD, l.rowW()));
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
        if (closeWhenSnapDone && !busy() && client != null) {
            client.setScreen(null);
        }
    }

    /**
     * 画一张牌：位置与大小在「摊成一排」与「收成一叠」之间按 {@code gathered} 插值。
     *
     * <p>❗版面本身不动 —— 命中判定照旧按摊开那一排算。收起来时点牌没有意义（都叠在一起了），
     * 所以查看态里鼠标点击不再当作留牌。
     */
    private void drawOne(DrawContext context, long now, long dt, Bands b, Layout l, Inspect in, float gathered,
                         float snapP, List<String> offer, int i) {
        float entered = GuiLanguage.deal(now, dealAt, i);
        if (entered <= 0f) {
            return;                       // 还没轮到它入场
        }
        boolean hi = i == highlight;
        boolean passing = passAt > 0L && now >= passAt;
        // ❗「抬」要一直保持到「传」真的开始：替你选的那一路，「传」的起点是「顿」播完的那一刻（一个将来的时刻）。
        //   第一版写成 passAt == 0 —— 服务端那一包一到 passAt 就有值了，抬起的 9 个单位在「顿」的同时落回去，
        //   正好把「顿」往上那一下抵掉（snap_test 量到超时那一路抬起 0.0 px，2026-09-25）。
        lift[i] = GuiLanguage.approach(lift[i], hi && !passing ? GuiLanguage.LIFT_PX : 0f, dt);

        int depth = hi ? 0 : 1 + Math.abs(i - Math.max(0, highlight));
        CardPose pose = cardPose(in, gathered, l.cardX(i) + l.w() / 2f, l.cardTop(i) + l.h(), l.w(), l.h(), depth);

        // 入场：从下方抬起 + 轻微放大。全部走矩阵，不碰布局。
        float rise = (1f - entered) * GuiLanguage.DEAL_RISE;
        float scale = GuiLanguage.dealScale(entered);
        if (i == snapIndex) {
            // 「顿」：带过冲地弹一下再回原位。叠在「抬」之上 —— 服务端挑的要是另一张，
            // 这一下正好连「高亮挪过去了」一起说清楚。
            rise += GuiLanguage.snapRise(snapP);
            scale *= GuiLanguage.snapScale(snapP);
        }
        float cx = pose.cx();
        float bottom = pose.bottom() - lift[i] * (1f - gathered) + rise;
        if (passing) {
            // 「传」（O21）：留下的那张沿弧线「飞」进你的座位，其余「滑」到下一位 —— 都缩到头像那么大。
            boolean kept = i == keptIndex;
            if (kept ? !GuiLanguage.flying(now, passAt) : !GuiLanguage.sliding(now, passAt)) {
                return;                   // 已经落进座位：这一面上不再有它
            }
            float p = kept ? GuiLanguage.fly(now, passAt) : GuiLanguage.slide(now, passAt);
            float[] spot = seatSpot(b, kept ? data.at() : data.at() + 1, pose.w());
            float targetScale = spot[2] / Math.max(1f, pose.w());
            float targetBottom = spot[1] + spot[2] * GuiLanguage.CARD_H / GuiLanguage.CARD_W / 2f;
            cx += (spot[0] - cx) * p;
            bottom += (targetBottom - bottom) * p - (kept ? GuiLanguage.flyArc(p) : 0f);
            scale *= 1f + (targetScale - 1f) * p;
        }

        context.getMatrices().push();
        context.getMatrices().translate(cx, bottom, 0);
        context.getMatrices().scale(scale, scale, 1f);
        context.getMatrices().translate(-pose.w() / 2f, -pose.h(), 0);
        CardTexture.drawProvision(context, offer.get(i), 0, 0, pose.w(), pose.h());
        if (hi && !passing) {
            // 金 = 「你 · 你选的那张」，与手牌那一面同一个用法；朱砂留给倒计时见底那一段。
            // 传走的那一下不带框：它已经不是「你正在选的」，是「你的了」。
            drawCardFrame(context, pose.w(), pose.h());
        }
        context.getMatrices().pop();
    }

    /**
     * 座位轨上第 {@code index} 格：头像中心的 x、y 与直径（GUI 单位）—— 「传」的落点。
     *
     * <p>与 {@link #drawRailBand} 同一套格子算法（{@code railCell} · 居中）。轨被这一档窗口拿掉时
     * 落到上带正中；没有下一位（你是这条链的最后一个）时滑出舞台右边。
     */
    private float[] seatSpot(Bands b, int index, int cardW) {
        List<String> chain = data.chain();
        if (index >= chain.size()) {
            return new float[]{width + cardW, b.stageTop() + b.stageH() / 2f, cardW};
        }
        if (chain.isEmpty() || b.railH() == 0) {
            return new float[]{width / 2f, b.topY(), Math.max(8, cardW / 4f)};
        }
        int cell = railCell(chain.size());
        int left = (width - chain.size() * cell) / 2;
        float cx = left + index * cell + cell / 2f;
        int full = b.avatar();
        if (full <= 0) {
            return new float[]{cx, b.railY() + textH() / 2f, Math.max(8, cell / 3f)};
        }
        int m = GuiMaterial.ringMargin(full);
        int d = Math.max(1, Math.min(full, cell - 2 * m - 2));
        return new float[]{cx, b.railY() + m + full / 2f, d};
    }

    /** 指针落在第几张上（一排或两排）。上边界把「抬」起来的那几像素算进去，与单排那一版同一条。 */
    private int indexAt(int mouseX, int mouseY, Layout l) {
        for (int i = 0; i < data.offer().size(); i++) {
            int x = l.cardX(i);
            int top = l.cardTop(i);
            if (mouseX >= x && mouseX < x + l.w() && mouseY >= top - GuiLanguage.LIFT_PX && mouseY <= top + l.h()) {
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

    /** 「顿」开始、或你自己按下去之后，这一轮就定了 —— 再点再按都不作数，否则会往服务端发一个已经没意义的留牌。 */
    private boolean decided() {
        return snapAt > 0L || committedIndex >= 0 || passAt > 0L;
    }

    /** 你自己留这一张。记下是哪一张：服务端认下之后「飞」走的就是它。 */
    private void commit(int index) {
        committedIndex = index;
        send(index, true);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (inspectClick(button)) {
            return true;
        }
        if (data != null && !data.offer().isEmpty() && !decided() && !inspecting()) {
            int i = indexAt((int) mouseX, (int) mouseY, layout(bands()));
            if (i >= 0) {
                commit(i);
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
        if (inspectKey(keyCode)) {
            return true;
        }
        switch (keyCode) {
            // ←→ 在两个状态里是同一件事：换哪一张。查看态里换的是堆顶那张与右边那段字，
            // 而它照旧上报服务端 —— 你正在看的，就是超时会替你留下的（决策 ⑨）。
            case GLFW.GLFW_KEY_LEFT -> {
                setHighlight(Math.max(0, highlight - 1));
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                setHighlight(Math.min(data.offer().size() - 1, highlight + 1));
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                commit(highlight);
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }
}
