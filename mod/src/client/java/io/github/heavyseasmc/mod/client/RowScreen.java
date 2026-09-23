package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.game.NavCardText;
import io.github.heavyseasmc.mod.net.RowDecisionC2S;
import io.github.heavyseasmc.mod.state.HudView;
import io.github.heavyseasmc.mod.state.NavCardView;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 划船：查看抽到的牌，选择一张放进划船堆，其余自动塞回牌堆底（决策 ⑭ · ADR-0018 §7.4）。
 *
 * <h2>三带</h2>
 * <ul>
 *   <li><b>上带（公开）</b>：回合 · 阶段 · 海鸥，下面一行划船堆几张、舵手是谁 —— 全船都知道的数。</li>
 *   <li><b>中带（待决）</b>：抽到的牌。高亮一进来就在第一张；「使用」按钮只跟高亮那一张走。</li>
 *   <li><b>下带（私有）</b>：高亮那张的完整说明（牌面上的字可能小到读不清），以及你是谁、还剩多少。</li>
 * </ul>
 *
 * <h2>短倒计时</h2>
 * 划船决定给 20 秒；超时会把这一组牌全部塞回牌堆底。
 * Esc 可以收起来，服务端倒计时仍继续，按行动键可再开。
 *
 * <h2>一次选择，两种去向</h2>
 * 选中的牌往上「飞」进划船堆，其余牌同时往下「飞」回牌堆底。
 *
 * <h2>按下就飞，不等服务端</h2>
 * 一次选择只发一个包（{@link RowDecisionC2S}）。服务端按同一条规则把未选中的牌全部放回。
 *
 * <h2>没有「使用」按钮</h2>
 * 第四刀之前牌底下压着一个按钮，说的正是键位那一行已经说过的话，却吃掉舞台四分之一的高度 ——
 * 带位版面里舞台先给牌（总纲 §7.8），所以它去掉了：←→ 换牌，Enter/Space 或<b>再点一次同一张</b>使用。
 */
public final class RowScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private HudView view = HudView.IDLE;
    /** 这一次抽到的牌，按抽出顺序。打开时从投影里拿一次：服务端划完之后投影里就没有了，而最后一张还在飞。 */
    private List<NavCardView> cards = List.of();
    private Session.RowFate[] fates = new Session.RowFate[0];
    /** 每张起飞的时刻；0 表示没在这一面上飞过（还没选择，或者是重开之前归位的）。 */
    private long[] flyAt = new long[0];
    private float[] lift = new float[0];
    /** 高亮。一进来就在第一张待选牌上；选择完成时为 -1。 */
    private int focus = -1;
    private long dealAt;
    private boolean opened;
    /** 上一帧排出来的版面，点击按它判。 */
    private Layout layout;

    /** 一帧的版面，全部以 GUI 单位计。带位归 {@link Bands}，这里只排舞台那一格里的东西。 */
    private record Layout(int w, int h, int left, int cardsTop, int rowW, int seaY, int hintY) {

        int cardX(int i) {
            return left + i * (w + CARD_GAP);
        }
    }

    public RowScreen() {
        super(Text.translatable("heavyseas.row.title"));
    }

    /** ❗{@code init} 在窗口改尺寸时还会再调：牌与高亮只在第一次打开时取，否则拖一下窗口就重新发一遍牌。 */
    @Override
    protected void init() {
        super.init();
        if (opened) {
            return;
        }
        opened = true;
        view = projection();
        List<HudView.Sea.RowCard> rowing = view.sea().rowing();
        cards = rowing.stream().map(HudView.Sea.RowCard::card).toList();
        fates = new Session.RowFate[cards.size()];
        for (int i = 0; i < fates.length; i++) {
            fates[i] = rowing.get(i).fate();
        }
        flyAt = new long[cards.size()];
        lift = new float[cards.size()];
        focus = nextUndecided(0, 1);
        dealAt = System.currentTimeMillis();
    }

    /** 每帧对一次投影。服务端那边已经定了、这边还没记上的（重开界面之前定过一张），以服务端为准。 */
    private void refresh() {
        view = projection();
        List<HudView.Sea.RowCard> server = view.sea().rowing();
        if (server.size() != cards.size()) {
            return;                           // 服务端已经划完（投影里空了）：以本地为准，等牌飞完再收
        }
        for (int i = 0; i < cards.size(); i++) {
            HudView.Sea.RowCard s = server.get(i);
            if (fates[i] == Session.RowFate.UNDECIDED && s.fate() != Session.RowFate.UNDECIDED
                    && s.card().id().equals(cards.get(i).id())) {
                fates[i] = s.fate();
                if (focus == i) {
                    focus = nextUndecided(0, 1);
                }
            }
        }
    }

    /** 收界面只在这里做（理由同行动一面：不在 render 里换屏）。 */
    @Override
    public void tick() {
        refresh();
        if (!view.active() || !view.seated()) {
            close();
            return;
        }
        if (flying()) {
            return;                           // 飞完再收：牌离开你手里的那一下不能被截断
        }
        if (focus < 0 || !view.myRowPending()) {
            close();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        refresh();
        if (!view.active() || !view.seated() || cards.isEmpty()) {
            return;                           // 这一帧什么都不画，tick 会把它收起来
        }
        renderBackdrop(context, mouseX, mouseY, delta);
        long now = System.currentTimeMillis();
        long dt = frameDelta(now);
        Bands b = drawChrome(context, view);
        Layout l = layout(b);
        layout = l;
        Inspect ins = inspect(b, l.seaY() + lineStep());
        float gathered = gathered(now);

        // 划船堆几张 · 舵手是谁：这一面才要，所以排在舞台里，不占共有的带。
        drawSeaLine(context, view, l.seaY());

        // 鼠标真的动了才把高亮带过去（停着的指针不算指向，见 GameScreen#mouseActuallyMoved）。
        // 查看态里不认悬停：牌叠在一起了，命中框却还在摊开那一排的位置上。
        if (mouseActuallyMoved(mouseX, mouseY) && !inspecting()) {
            int hovered = cardAt(mouseX, mouseY, l);
            if (hovered >= 0 && fates[hovered] == Session.RowFate.UNDECIDED) {
                focus = hovered;
            }
        }

        for (int i = 0; i < cards.size(); i++) {
            drawCard(context, now, dt, l, i, ins, gathered);
        }
        // 说明只写一处：查看态里它在签子上，摊开时它在舞台底下 —— 同一句写两遍是这一刀在收的那种重复。
        if (focus >= 0 && gathered <= 0f) {
            drawHint(context, l);
        }
        // 航海牌没有牌名（id 形如 nav_07），它的身份就是那一句 —— 所以签子上只有说明。
        if (gathered > 0f && focus >= 0 && focus < cards.size()) {
            drawCardPlate(context, ins.plateX(), ins.plateY(), ins.plateW(), -1,
                    null, null, NavCardText.describe(cards.get(focus), view.seats()));
        }
        drawFootBand(context, b, List.of(keys("select", "←", "→")), inspectHints("use"), now,
                new Countdown(view.actionDeadlineMs(), view.actionWindowMs(), l.rowW()));
    }

    /**
     * 这一帧舞台里的版面。每帧按当前的 {@code width}/{@code height} 重算：窗口与界面尺寸随时会变。
     *
     * <p>舞台里自上而下：划船堆那一行 · 牌 · 按钮；说明两行与超时一行贴着舞台底边。
     */
    private Layout layout(Bands b) {
        int seaY = b.stageTop();
        int hintY = footerTop(b, 2);                     // 这一张牌会做什么，最多两行
        int top = seaY + lineStep() + liftRoom();        // 抬起来的牌连同金框不碰划船堆那一行
        int bottomLimit = hintY - HINT_GAP;
        int n = Math.max(1, cards.size());
        int h = cardHeightFor(n, bottomLimit - top);
        int w = GuiLanguage.cardWidth(h);
        int cardsTop = cardsTopIn(top, bottomLimit, h, 0);
        int rowW = cardRowWidth(n, w);
        return new Layout(w, h, (width - rowW) / 2, cardsTop, rowW, seaY, hintY);
    }

    private void drawCard(DrawContext context, long now, long dt, Layout l, int i, Inspect ins, float gathered) {
        float in = GuiLanguage.deal(now, dealAt, i);
        if (in <= 0f) {
            return;                           // 还没轮到它入场
        }
        boolean undecided = fates[i] == Session.RowFate.UNDECIDED;
        lift[i] = GuiLanguage.approach(lift[i], undecided && i == focus ? GuiLanguage.LIFT_PX : 0f, dt);
        float cx = l.cardX(i) + l.w() / 2f;
        float bottom = l.cardsTop() + l.h();
        float scale = GuiLanguage.dealScale(in);
        float rise = (1f - in) * GuiLanguage.DEAL_RISE;
        if (!undecided) {
            if (flyAt[i] <= 0L) {
                return;                       // 重开这一面之前就定了：它已经不在你面前
            }
            float p = GuiLanguage.fly(now, flyAt[i]);
            if (p >= 1f) {
                return;
            }
            boolean keep = fates[i] == Session.RowFate.KEPT;
            // 留进划船堆：飞向上带那一行「划船堆 N 张」；塞回牌堆底：往下飞出屏幕。弧线两头为 0，中间抬起。
            float targetX = keep ? width / 2f : cx;
            float targetBottom = keep ? l.seaY() + textH() : height + l.h();
            cx += (targetX - cx) * p;
            bottom += (targetBottom - bottom) * p - GuiLanguage.flyArc(p);
            scale *= 1f - (keep ? 0.75f : 0.3f) * p;
        }
        CardPose pose = cardPose(ins, gathered, cx, bottom, l.w(), l.h(),
                i == focus ? 0 : 1 + Math.abs(i - Math.max(0, focus)));
        context.getMatrices().push();
        // 绕底边中点缩放，与卡的其余几面同一个做法。
        context.getMatrices().translate(pose.cx(), pose.bottom() - lift[i] * (1f - gathered) + rise, 0);
        context.getMatrices().scale(scale, scale, 1f);
        context.getMatrices().translate(-pose.w() / 2f, -pose.h(), 0);
        CardTexture.drawNav(context, cards.get(i), 0, 0, pose.w(), pose.h());
        if (undecided && i == focus) {
            drawCardFrame(context, pose.w(), pose.h());
        }
        context.getMatrices().pop();
    }

    /** 说明只跟高亮走：那张牌的完整内容；一行放得下时，第二行说键位。 */
    private void drawHint(DrawContext context, Layout l) {
        // 这一行说的是**这张牌会做什么**（牌面上的字在这个尺寸下读不出来），不是规矩 ——
        // 航海卡去字并接上提示签之后，这一行也该跟着走（ADR-0037 §7.4 第 3 条还欠）。
        drawParagraph(context, NavCardText.describe(cards.get(focus), view.seats()), l.hintY(), 2, GuiLanguage.ink());
    }

    private int cardAt(int mouseX, int mouseY, Layout l) {
        return cardIndexAt(mouseX, mouseY, l.left(), l.cardsTop(), l.w(), l.h(), cards.size());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (inspectClick(button)) {
            return true;
        }
        Layout l = layout;
        if (l != null && focus >= 0 && !inspecting()) {
            int card = cardAt((int) mouseX, (int) mouseY, l);
            if (card >= 0 && fates[card] == Session.RowFate.UNDECIDED) {
                // 第一下选中，再点同一张才使用 —— 与补给箱「点一下就定」不同：那一面点错还能等超时，
                // 这一面一点下去其余几张当场塞回牌堆底，不可逆（朱砂那一类）。按键那一行写着这条。
                if (card == focus) {
                    choose(card);
                } else {
                    focus = card;
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (inspectKey(keyCode)) {
            return true;
        }
        // 用哪个键开的，就用哪个键收起来（理由同行动一面：写死的键，玩家改了键位就收不起来）。
        if (HeavySeasClient.actKey().matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        if (focus >= 0) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT -> {
                    int next = nextUndecided(focus + (keyCode == GLFW.GLFW_KEY_RIGHT ? 1 : -1),
                            keyCode == GLFW.GLFW_KEY_RIGHT ? 1 : -1);
                    if (next >= 0) {
                        focus = next;
                    }
                    return true;
                }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                    choose(focus);
                    return true;
                }
                default -> {
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void choose(int i) {
        if (i < 0 || i >= cards.size() || fates[i] != Session.RowFate.UNDECIDED) {
            return;
        }
        long now = System.currentTimeMillis();
        for (int card = 0; card < cards.size(); card++) {
            if (fates[card] == Session.RowFate.UNDECIDED) {
                fates[card] = card == i ? Session.RowFate.KEPT : Session.RowFate.RETURNED;
                flyAt[card] = now;
            }
        }
        ClientPlayNetworking.send(new RowDecisionC2S(i));
        // 验收靠这一行与服务端那行「划船（界面）」对上：客户端按了、服务端认了，两个来源。
        LOGGER.info("划船：选择第 {} 张留进划船堆（{}），其余 {} 张塞回牌堆底",
                i + 1, cards.get(i).id(), Math.max(0, cards.size() - 1));
        focus = -1;
    }

    /** 从 {@code from} 起朝 {@code step} 方向找第一张待选牌；没有就是 -1。 */
    private int nextUndecided(int from, int step) {
        for (int i = from; i >= 0 && i < fates.length; i += step) {
            if (fates[i] == Session.RowFate.UNDECIDED) {
                return i;
            }
        }
        return -1;
    }

    /** 还有牌在飞吗。舵手一面要等它飞完再弹，否则最后一张飞到一半就被顶掉。 */
    public boolean flying() {
        long now = System.currentTimeMillis();
        for (long at : flyAt) {
            if (GuiLanguage.flying(now, at)) {
                return true;
            }
        }
        return false;
    }
}
