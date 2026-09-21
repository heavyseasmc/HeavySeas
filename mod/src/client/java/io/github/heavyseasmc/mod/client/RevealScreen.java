package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.state.EndgameProgress;
import io.github.heavyseasmc.mod.state.HudView;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 终局 · 逐个点名翻牌（决策 ⑪ 第二幕 · ADR-0022）。版式照交互稿「终局」那一面（用户 2026-09-15 定）。
 *
 * <h2>三带</h2>
 * <ul>
 *   <li><b>上带（公开）</b>：回合 · 海鸥；「第一轮：恨」；座位条 —— 已翻开的在名字下面写出目标，正在点名的那一个是金色。</li>
 *   <li><b>中带（舞台）</b>：被点名的人 · 「恨」（朱砂）/「爱」· 一张暗牌。暗牌「翻」开是目标的角色卡。</li>
 *   <li><b>下带（私有）</b>：你是谁；你自己恨谁、爱谁（你本来就知道，放在这里是为了推理时不用去翻手牌）。</li>
 * </ul>
 *
 * <h2>只用「滑」与「翻」</h2>
 * 翻 = 暗牌变明牌（全局唯一的「信息状态改变」）；滑 = 点名移到下一个人（轮次推进）。
 * 终局是整局的收束，不该出现玩家没见过的动作（ADR-0018 §7.4）。
 *
 * <h2>没有倒计时</h2>
 * 这是一段编排好的演出，不是决策 —— 舞台下方那条细横杠不出现。节奏由服务端排程（{@code EndgamePhase}）。
 *
 * <h2>客户端只跟着投影走</h2>
 * 服务端每翻一张就把 {@code flipped} 加一；这一面看到它变大，就「翻」开刚才那一张，看清之后再「滑」到下一个人。
 * 投影里没翻开的目标<b>根本不在包里</b>，所以这一面想提前露也露不出来。
 * 并列最高分的胜者使用 1.4 秒的蓄势翻牌；服务端显式投影胜者标记，不由客户端猜最后几位。
 */
public final class RevealScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 舞台上卡与「恨 / 爱」那个字之间。 */
    private static final int STAGE_GAP = 10;
    private static final int LABEL_SCALE = 2;

    /**
     * 翻开之后看清多久再「滑」到下一个人。服务端每一步停 2.6 秒（{@code EndgamePhase.FLIP_HOLD_MS}），
     * 其中翻 400ms、滑 550ms，剩下的就是「看」。
     */
    private static final long VIEW_MS = 1600L;

    /** 「滑」进来的位移（GUI 单位）：从右边滑到台中央。 */
    private static final float SLIDE_DX = 40f;

    private HudView view = HudView.IDLE;
    private EndgameProgress.Stage shownStage;
    private int shownFlipped = -1;
    /** 台上是第几个人（翻牌次序里的下标）。 */
    private int stageWho;
    /** 台上这个人的牌翻开了没有。 */
    private boolean stageRevealed;
    /** 翻开之后要滑到的下一个人；-1 表示没有待滑的。 */
    private int pendingNext = -1;
    private long flipAt;
    private long slideAt;
    private boolean opened;

    public RevealScreen() {
        super(Text.translatable("heavyseas.reveal.title"));
    }

    @Override
    protected void init() {
        super.init();
        if (!opened) {
            opened = true;
            follow(System.currentTimeMillis());
        }
    }

    /** 终局没了、或者已经进计分阶段，就收起来 —— 计分面板由 HeavySeasClient 接着开。 */
    @Override
    public void tick() {
        view = projection();
        if (!view.myEndgame() || view.endgame().stage() == EndgameProgress.Stage.SCORES) {
            close();
        }
    }

    /** 跟上投影：新一轮就把第一个人滑上台；翻开张数变大就翻刚才那一张；看清了就滑到下一个人。 */
    private void follow(long now) {
        view = projection();
        HudView.Endgame e = view.endgame();
        if (!e.active() || e.stage() == EndgameProgress.Stage.SCORES || e.entries().isEmpty()) {
            return;
        }
        int last = e.entries().size() - 1;
        if (e.stage() != shownStage) {
            shownStage = e.stage();
            shownFlipped = e.flipped();
            stageWho = Math.min(e.flipped(), last);
            stageRevealed = e.flipped() == e.entries().size();
            pendingNext = -1;
            flipAt = stageRevealed ? now - flipMillis(e.entries().get(stageWho)) : 0L;
            slideAt = now;                   // 这一轮的第一个人「滑」上台
            return;
        }
        if (e.flipped() > shownFlipped) {
            shownFlipped = e.flipped();
            stageWho = e.flipped() - 1;
            stageRevealed = true;
            flipAt = now;
            slideAt = 0L;
            pendingNext = e.flipped() < e.entries().size() ? e.flipped() : -1;
            // 与语言无关的一行：GUI 回归靠它数「客户端真的翻了几张」。
            LOGGER.info("终局：翻开第 {} 张（{}）", e.flipped(), e.stage());
        }
        if (stageRevealed && pendingNext >= 0
                && now - flipAt >= flipMillis(e.entries().get(stageWho)) + VIEW_MS) {
            stageWho = Math.min(pendingNext, last);
            stageRevealed = false;
            pendingNext = -1;
            flipAt = 0L;
            slideAt = now;                   // 点名「滑」到下一个人
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        long now = System.currentTimeMillis();
        follow(now);
        HudView.Endgame e = view.endgame();
        if (!e.active() || e.stage() == EndgameProgress.Stage.SCORES || e.entries().isEmpty()) {
            return;                          // tick 会把它收起来
        }
        renderBackdrop(context, mouseX, mouseY, delta);
        boolean hate = e.stage() == EndgameProgress.Stage.HATE;

        Bands b = drawChrome(context, view);
        // 舞台第一行：这一轮翻的是恨还是爱；第二行：每一格点了谁（座位轨那一条带只容得下名字与线）。
        int titleY = b.stageTop();
        int targetsY = titleY + lineStep();
        drawLine(context, Text.translatable(hate ? "heavyseas.reveal.round_hate" : "heavyseas.reveal.round_love"),
                width / 2, titleY, GuiLanguage.ink());
        drawTargets(context, now, e, b, targetsY);

        int sayY = footerTop(b, 2);
        int ownY = sayY + lineStep();
        drawStage(context, now, e, hate, targetsY + lineStep() + HINT_GAP, sayY - HINT_GAP);
        drawSay(context, now, e, hate, sayY);
        if (view.seated()) {
            drawLine(context, Text.translatable("heavyseas.reveal.yours",
                    nameOf(view.hate()), nameOf(view.love())), width / 2, ownY, GuiLanguage.muted());
        } else {
            drawLine(context, Text.translatable("heavyseas.endgame.spectating"),
                    width / 2, b.identityY(), GuiLanguage.muted());
        }
    }

    /** 旁观的人没有身份可写，那一行改写「旁观中」（在 {@link #render} 里）。 */
    @Override
    protected boolean showsIdentity() {
        return view.seated();
    }

    /**
     * 座位条：名字 · 一道横杠 · 已翻开的目标。
     *
     * <p>❗台上这一张还没翻过中点时，条上<b>不提前写出目标</b> —— 投影里目标已经到了，
     * 但「翻」是「暗变明」的唯一动作，条上先露出来，这个动作就没有意义了。
     *
     * @return 条的下沿
     */
    /** 座位轨是<b>翻到谁</b>，不是座位序 —— 所以覆写它。 */
    @Override
    protected void drawRailBand(DrawContext context, HudView unused, Bands b) {
        long now = System.currentTimeMillis();
        List<HudView.Endgame.Entry> entries = view.endgame().entries();
        if (entries.isEmpty() || b.railH() == 0) {
            return;
        }
        int cell = railCell(entries.size());
        int left = (width - entries.size() * cell) / 2;
        for (int i = 0; i < entries.size(); i++) {
            HudView.Endgame.Entry en = entries.get(i);
            boolean midFlip = i == stageWho && stageRevealed && !showsFront(now, en);
            boolean done = !en.target().isEmpty() && !midFlip;
            boolean here = i == stageWho && !done;
            int nameColor = here ? GuiLanguage.gold() : done ? GuiLanguage.muted() : GuiLanguage.dim();
            drawSeat(context, en.who(), left + i * cell, b.railY(), cell, b, here ? GuiLanguage.gold() : 0,
                    !done && !here, nameColor, done ? GuiLanguage.verdigris() : GuiLanguage.ground());
        }
    }

    /** 轨底下那一行：已经翻过的，点的是谁。它是这一面自己的内容，所以排在舞台里，不挤共有的那条带。 */
    private void drawTargets(DrawContext context, long now, HudView.Endgame e, Bands b, int y) {
        List<HudView.Endgame.Entry> entries = e.entries();
        if (entries.isEmpty()) {
            return;
        }
        int cell = railCell(entries.size());
        int left = (width - entries.size() * cell) / 2;
        for (int i = 0; i < entries.size(); i++) {
            HudView.Endgame.Entry en = entries.get(i);
            boolean midFlip = i == stageWho && stageRevealed && !showsFront(now, en);
            if (!en.target().isEmpty() && !midFlip) {
                drawLineIn(context, nameOf(en.target()), left + i * cell + 2, y, cell - 4, GuiLanguage.muted());
            }
        }
    }

    /** 舞台：被点名的人 · 「恨 / 爱」· 暗牌（翻开是目标）。整排跟着「滑」。 */
    private void drawStage(DrawContext context, long now, HudView.Endgame e, boolean hate, int top, int bottom) {
        List<HudView.Endgame.Entry> entries = e.entries();
        if (stageWho < 0 || stageWho >= entries.size() || bottom <= top) {
            return;
        }
        HudView.Endgame.Entry en = entries.get(stageWho);
        Text label = Text.translatable(hate ? "heavyseas.reveal.hates" : "heavyseas.reveal.loves");
        int labelW = textW(label) * LABEL_SCALE;
        int avail = bottom - top;
        int byWidth = GuiLanguage.cardHeight(Math.max(1, (width - 2 * SIDE - labelW - 2 * STAGE_GAP) / 2));
        int cap = Math.min(sharpCardHeight(), Math.round(height * MAX_CARD_H_RATIO));
        int h = Math.max(MIN_CARD_H, Math.min(Math.min(avail, byWidth), cap));
        int w = GuiLanguage.cardWidth(h);
        int rowW = 2 * w + labelW + 2 * STAGE_GAP;
        float dx = (1f - GuiLanguage.slide(now, slideAt)) * SLIDE_DX;
        int left = Math.round((width - rowW) / 2f + dx);
        int y = top + Math.max(0, (avail - h) / 2);

        CardTexture.drawCharacter(context, en.who(), left, y, w, h);
        if (en.winner() && stageRevealed && showsFront(now, en)) {
            context.drawBorder(left - CARD_FRAME, y - CARD_FRAME, w + 2 * CARD_FRAME, h + 2 * CARD_FRAME,
                    GuiLanguage.gold());
        }

        // 「恨」用朱砂 —— 它只给伤害与紧迫；「爱」不占语义色（ADR-0018 §7.3：多了就不成语义）。
        context.getMatrices().push();
        context.getMatrices().translate(left + w + STAGE_GAP, y + h / 2f - textH() * LABEL_SCALE / 2f, 0);
        context.getMatrices().scale(LABEL_SCALE, LABEL_SCALE, 1f);
        drawLineLeft(context, label, 0, 0, hate ? GuiLanguage.cinnabar() : GuiLanguage.ink());
        context.getMatrices().pop();

        int tx = left + w + labelW + 2 * STAGE_GAP;
        boolean front = stageRevealed && !en.target().isEmpty() && showsFront(now, en);
        float sx = stageRevealed ? flipScaleX(now, en) : 1f;
        context.getMatrices().push();
        context.getMatrices().translate(tx + w / 2f, y, 0);
        context.getMatrices().scale(sx, 1f, 1f);
        context.getMatrices().translate(-w / 2f, 0, 0);
        if (front) {
            CardTexture.drawCharacter(context, en.target(), 0, 0, w, h);
        } else {
            CardTexture.drawBack(context, "secret", 0, 0, w, h);
        }
        context.getMatrices().pop();
    }

    /** 舞台下面一行：问谁，或者揭晓了什么。 */
    private void drawSay(DrawContext context, long now, HudView.Endgame e, boolean hate, int y) {
        List<HudView.Endgame.Entry> entries = e.entries();
        if (stageWho < 0 || stageWho >= entries.size()) {
            return;
        }
        HudView.Endgame.Entry en = entries.get(stageWho);
        Text line;
        int color = GuiLanguage.muted();
        if (stageRevealed && !en.target().isEmpty() && showsFront(now, en)) {
            line = Text.translatable(hate ? "heavyseas.endgame.reveal_hate" : "heavyseas.endgame.reveal_love",
                    nameOf(en.who()), nameOf(en.target()));
            color = GuiLanguage.ink();
        } else {
            line = Text.translatable(hate ? "heavyseas.reveal.asking_hate" : "heavyseas.reveal.asking_love",
                    nameOf(en.who()));
        }
        drawLine(context, line, width / 2, y, color);
    }

    private static long flipMillis(HudView.Endgame.Entry entry) {
        return entry.winner() ? GuiLanguage.WINNER_FLIP_MS : GuiLanguage.FLIP_MS;
    }

    private boolean showsFront(long now, HudView.Endgame.Entry entry) {
        return entry.winner() ? GuiLanguage.winnerFlipShowsFront(now, flipAt)
                : GuiLanguage.flipShowsFront(now, flipAt);
    }

    private float flipScaleX(long now, HudView.Endgame.Entry entry) {
        return entry.winner() ? GuiLanguage.winnerFlipScaleX(now, flipAt)
                : GuiLanguage.flipScaleX(now, flipAt);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 用哪个键开的，就用哪个键收起来（理由同行动一面：写死的键，玩家改了键位就收不起来）。
        if (HeavySeasClient.actKey().matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 名字放不下一格时截短 —— 八人局、英文、窄窗口时相邻两格会叠成一团，截短至少还认得出是谁。 */
}
