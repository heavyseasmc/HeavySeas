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

    /** 见过一次「对局还在」的投影没有。见 {@link #tick()}。 */
    private boolean sawGame;

    /**
     * 查看态：牌收成一叠，说明出现在右边（用户 2026-09-22）。默认<b>关</b> ——
     * 牌做什么是冷信息，玩一两次就记住了，不该常驻占着舞台。
     */
    private boolean inspecting;
    /** 这一次进 / 出查看态的起点，喂给「堆」。 */
    private long inspectAt;

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

    /** 一帧的版面，全部以 GUI 单位计。带位归 {@link Bands}，这里只排舞台那一格里的东西。 */
    private record Layout(int w, int h, int left, int cardsTop, int rowW) {

        int cardX(int i) {
            return left + i * (w + CARD_GAP);
        }
    }

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
        int w = GuiLanguage.cardWidth(h);
        int cardsTop = cardsTopIn(b.stageTop(), bottom, h, room);
        int rowW = cardRowWidth(n, w);
        return new Layout(w, h, (width - rowW) / 2, cardsTop, rowW);
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
        // 「堆」走到哪了：0 = 还摊成一排，1 = 已经收成一叠。进与出走同一条曲线，只是方向相反。
        float gathered = inspecting ? GuiLanguage.gather(now, inspectAt) : 1f - GuiLanguage.gather(now, inspectAt);
        float snapP = GuiLanguage.snap(now, snapAt);

        // 鼠标真的动了才把高亮带过去（停着的指针不算指向，见 GameScreen#mouseActuallyMoved）。
        // ❗即使这一轮已经定了也要每帧调一次：它记的是上一帧指针在哪，停一帧就会漏掉一次移动。
        // 「顿」一开始决定就定了，之后鼠标与键盘都不该再改高亮 —— 所以判的是 decided()，
        // 不是「还在播」：播完到界面收掉之间那几帧，同样不许再改。
        // ❗查看态里不认悬停：牌都叠在一起了，命中框却还在摊开时那一排的位置上 ——
        //   指针没动、看的牌却被「指」到了别处，与「停着的指针不算指向」同一类。
        //   查看态里换牌只走 ←→（与摊开时同一个键，同一件事）。
        boolean moved = mouseActuallyMoved(mouseX, mouseY);
        if (moved && !decided() && !inspecting) {
            int hovered = indexAt(mouseX, mouseY, l);
            if (hovered >= 0 && hovered != highlight) {
                setHighlight(hovered);    // 超时认高亮，鼠标与键盘两套指示不能各说各话
            }
        }

        List<String> offer = data.offer();
        // 高亮那一张最后画：收成一叠时它在堆顶，摊开时它抬起来 —— 两种状态下它都必须压在别人上面。
        for (int i = 0; i < offer.size(); i++) {
            if (i != highlight) {
                drawOne(context, now, dt, l, in, gathered, snapP, offer, i);
            }
        }
        if (highlight >= 0 && highlight < offer.size()) {
            drawOne(context, now, dt, l, in, gathered, snapP, offer, highlight);
        }

        if (gathered > 0f && highlight >= 0 && highlight < offer.size()) {
            String card = offer.get(highlight);
            drawCardPlate(context, in.plateX(), in.plateY(), in.plateW(), -1,
                    null, provisionName(card), provisionEffect(card));
        }
        drawEdgeHints(context, b, List.of(keys("select", "←", "→")),
                List.of(keys(inspecting ? "close" : "inspect", "U"), keys("confirm", "Enter")));
        drawCountdown(context, b, now, data.deadlineMs(),
                Math.max(1, data.offer().size()) * ProvisionPhase.MILLIS_PER_CARD, l.rowW());
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

    /**
     * 画一张牌：位置与大小在「摊成一排」与「收成一叠」之间按 {@code gathered} 插值。
     *
     * <p>❗版面本身不动 —— 命中判定照旧按摊开那一排算。收起来时点牌没有意义（都叠在一起了），
     * 所以查看态里鼠标点击不再当作留牌。
     */
    private void drawOne(DrawContext context, long now, long dt, Layout l, Inspect in, float gathered,
                         float snapP, List<String> offer, int i) {
        float entered = GuiLanguage.deal(now, dealAt, i);
        if (entered <= 0f) {
            return;                       // 还没轮到它入场
        }
        boolean hi = i == highlight;
        lift[i] = GuiLanguage.approach(lift[i], hi ? GuiLanguage.LIFT_PX : 0f, dt);

        // 一叠牌不是叠得严丝合缝：每张错开一点点，才看得出是一叠而不是一张。
        int depth = hi ? 0 : 1 + Math.abs(i - Math.max(0, highlight));
        float stackX = in.cardX() + Math.min(depth, 6) * 1.6f;
        float stackBottom = in.cardY() + in.cardH() + Math.min(depth, 6) * 1.2f;
        float w = l.w() + (in.cardW() - l.w()) * gathered;
        float h = l.h() + (in.cardH() - l.h()) * gathered;
        float rowCx = l.cardX(i) + l.w() / 2f;
        float cx = rowCx + (stackX + in.cardW() / 2f - rowCx) * gathered;
        float rowBottom = l.cardsTop() + l.h();
        float bottom = rowBottom + (stackBottom - rowBottom) * gathered;

        context.getMatrices().push();
        // 入场：从下方抬起 + 轻微放大。全部走矩阵，不碰布局。
        float rise = (1f - entered) * GuiLanguage.DEAL_RISE;
        float scale = GuiLanguage.dealScale(entered);
        if (i == snapIndex) {
            // 「顿」：带过冲地弹一下再回原位。叠在「抬」之上 —— 服务端挑的要是另一张，
            // 这一下正好连「高亮挪过去了」一起说清楚。
            rise += GuiLanguage.snapRise(snapP);
            scale *= GuiLanguage.snapScale(snapP);
        }
        context.getMatrices().translate(cx, bottom - lift[i] * (1f - gathered) + rise, 0);
        context.getMatrices().scale(scale, scale, 1f);
        context.getMatrices().translate(-w / 2f, -h, 0);
        CardTexture.drawProvision(context, offer.get(i), 0, 0, Math.round(w), Math.round(h));
        if (hi) {
            // 金 = 「你 · 你选的那张」，与手牌那一面同一个用法；朱砂留给倒计时见底那一段。
            drawCardFrame(context, Math.round(w), Math.round(h));
        }
        context.getMatrices().pop();
    }

    private int indexAt(int mouseX, int mouseY, Layout l) {
        return cardIndexAt(mouseX, mouseY, l.left(), l.cardsTop(), l.w(), l.h(), data.offer().size());
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

    /** 进 / 出查看态。同一个动作两边都通，收起来时也照旧能确认。 */
    private void toggleInspect() {
        inspecting = !inspecting;
        inspectAt = System.currentTimeMillis();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1) {
            toggleInspect();              // 右键 = 查看（与 U 同一件事）
            return true;
        }
        if (data != null && !data.offer().isEmpty() && !decided() && !inspecting) {
            int i = indexAt((int) mouseX, (int) mouseY, layout(bands()));
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
            case GLFW.GLFW_KEY_U -> {
                toggleInspect();
                return true;
            }
            case GLFW.GLFW_KEY_ESCAPE -> {
                if (inspecting) {
                    toggleInspect();      // 查看态里 Esc 只收这一层，不试图关界面（选牌本来也逃不掉）
                    return true;
                }
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
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
                send(highlight, true);
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }
}
