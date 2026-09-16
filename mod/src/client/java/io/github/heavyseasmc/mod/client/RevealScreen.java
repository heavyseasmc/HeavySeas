package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.state.EndgameProgress;
import io.github.heavyseasmc.mod.state.HudView;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Language;
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
 * 投影里没翻开的目标<b>根本不在包里</b>（最后那张不翻的更是谁都不发），所以这一面想提前露也露不出来。
 */
public final class RevealScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private static final int SIDE = 20;
    private static final int TOP_BAND_Y = 12;
    private static final int GAP = 10;
    private static final int MIN_CARD_H = 32;
    private static final float MAX_CARD_H_RATIO = 0.42f;
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
    private boolean shownWithheld;
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
            shownWithheld = e.withheld();
            stageWho = e.withheld() ? last : Math.min(e.flipped(), last);
            stageRevealed = false;
            pendingNext = -1;
            flipAt = 0L;
            slideAt = now;                   // 这一轮的第一个人「滑」上台
            return;
        }
        if (e.flipped() > shownFlipped) {
            shownFlipped = e.flipped();
            stageWho = e.flipped() - 1;
            stageRevealed = true;
            flipAt = now;
            slideAt = 0L;
            pendingNext = e.flipped();
            // 与语言无关的一行：GUI 回归靠它数「客户端真的翻了几张」。
            LOGGER.info("终局：翻开第 {} 张（{}）", e.flipped(), e.stage());
        }
        if (e.withheld() && !shownWithheld) {
            shownWithheld = true;
            LOGGER.info("终局：最后一张不翻（{}）", e.stage());
        }
        if (stageRevealed && pendingNext >= 0 && now - flipAt >= GuiLanguage.FLIP_MS + VIEW_MS) {
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
        int fh = textRenderer.fontHeight;

        drawPublicBand(context, view, TOP_BAND_Y);
        int titleY = TOP_BAND_Y + 26;
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable(hate ? "heavyseas.reveal.round_hate" : "heavyseas.reveal.round_love"),
                width / 2, titleY, GuiLanguage.INK);
        int railBottom = drawRail(context, now, e, titleY + fh + 6);

        int identityY = height - Math.max(8, Math.round(height * 0.05f)) - fh;
        int ownY = identityY - fh - 3;
        int sayY = ownY - fh - 8;
        drawStage(context, now, e, hate, railBottom + 8, sayY - 8);
        drawSay(context, now, e, hate, sayY);
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("heavyseas.reveal.yours",
                nameOf(view.hate()), nameOf(view.love())), width / 2, ownY, GuiLanguage.MUTED);
        drawIdentity(context, view, identityY);
    }

    /**
     * 座位条：名字 · 一道横杠 · 已翻开的目标。
     *
     * <p>❗台上这一张还没翻过中点时，条上<b>不提前写出目标</b> —— 投影里目标已经到了，
     * 但「翻」是「暗变明」的唯一动作，条上先露出来，这个动作就没有意义了。
     *
     * @return 条的下沿
     */
    private int drawRail(DrawContext context, long now, HudView.Endgame e, int y) {
        List<HudView.Endgame.Entry> entries = e.entries();
        int n = entries.size();
        int fh = textRenderer.fontHeight;
        int widest = 0;
        for (HudView.Endgame.Entry en : entries) {
            widest = Math.max(widest, textRenderer.getWidth(nameOf(en.who())));
        }
        int cell = Math.min(Math.max(widest + 8, 48), (width - 2 * SIDE) / n);
        int left = (width - n * cell) / 2;
        for (int i = 0; i < n; i++) {
            HudView.Endgame.Entry en = entries.get(i);
            int x = left + i * cell;
            boolean midFlip = i == stageWho && stageRevealed && !GuiLanguage.flipShowsFront(now, flipAt);
            boolean done = !en.target().isEmpty() && !midFlip;
            boolean here = i == stageWho && !done;
            int nameColor = here ? GuiLanguage.GOLD : done ? GuiLanguage.MUTED : GuiLanguage.DIM;
            context.drawCenteredTextWithShadow(textRenderer, fit(nameOf(en.who()), cell - 4), x + cell / 2, y, nameColor);
            context.fill(x + 3, y + fh + 1, x + cell - 3, y + fh + 3, done ? GuiLanguage.VERDIGRIS : GuiLanguage.GROUND);
            if (done) {
                context.drawCenteredTextWithShadow(textRenderer, fit(nameOf(en.target()), cell - 4), x + cell / 2,
                        y + fh + 5, GuiLanguage.MUTED);
            }
        }
        return y + 2 * fh + 6;
    }

    /** 舞台：被点名的人 · 「恨 / 爱」· 暗牌（翻开是目标）。整排跟着「滑」。 */
    private void drawStage(DrawContext context, long now, HudView.Endgame e, boolean hate, int top, int bottom) {
        List<HudView.Endgame.Entry> entries = e.entries();
        if (stageWho < 0 || stageWho >= entries.size() || bottom <= top) {
            return;
        }
        HudView.Endgame.Entry en = entries.get(stageWho);
        Text label = Text.translatable(hate ? "heavyseas.reveal.hates" : "heavyseas.reveal.loves");
        int labelW = textRenderer.getWidth(label) * LABEL_SCALE;
        int avail = bottom - top;
        int byWidth = GuiLanguage.cardHeight(Math.max(1, (width - 2 * SIDE - labelW - 2 * GAP) / 2));
        int cap = Math.min(sharpCardHeight(), Math.round(height * MAX_CARD_H_RATIO));
        int h = Math.max(MIN_CARD_H, Math.min(Math.min(avail, byWidth), cap));
        int w = GuiLanguage.cardWidth(h);
        int rowW = 2 * w + labelW + 2 * GAP;
        float dx = (1f - GuiLanguage.slide(now, slideAt)) * SLIDE_DX;
        int left = Math.round((width - rowW) / 2f + dx);
        int y = top + Math.max(0, (avail - h) / 2);

        CardTexture.drawCharacter(context, en.who(), left, y, w, h);

        // 「恨」用朱砂 —— 它只给伤害与紧迫；「爱」不占语义色（ADR-0018 §7.3：多了就不成语义）。
        context.getMatrices().push();
        context.getMatrices().translate(left + w + GAP, y + h / 2f - textRenderer.fontHeight * LABEL_SCALE / 2f, 0);
        context.getMatrices().scale(LABEL_SCALE, LABEL_SCALE, 1f);
        context.drawTextWithShadow(textRenderer, label, 0, 0, hate ? GuiLanguage.CINNABAR : GuiLanguage.INK);
        context.getMatrices().pop();

        int tx = left + w + labelW + 2 * GAP;
        boolean front = stageRevealed && !en.target().isEmpty() && GuiLanguage.flipShowsFront(now, flipAt);
        float sx = stageRevealed ? GuiLanguage.flipScaleX(now, flipAt) : 1f;
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

    /** 舞台下面一行：问谁、揭晓了什么，或者「最后一张不翻」。 */
    private void drawSay(DrawContext context, long now, HudView.Endgame e, boolean hate, int y) {
        List<HudView.Endgame.Entry> entries = e.entries();
        if (stageWho < 0 || stageWho >= entries.size()) {
            return;
        }
        HudView.Endgame.Entry en = entries.get(stageWho);
        Text line;
        int color = GuiLanguage.MUTED;
        if (stageRevealed && !en.target().isEmpty() && GuiLanguage.flipShowsFront(now, flipAt)) {
            line = Text.translatable(hate ? "heavyseas.endgame.reveal_hate" : "heavyseas.endgame.reveal_love",
                    nameOf(en.who()), nameOf(en.target()));
            color = GuiLanguage.INK;
        } else if (e.withheld() && stageWho == entries.size() - 1) {
            line = Text.translatable(hate ? "heavyseas.endgame.withheld_hate" : "heavyseas.endgame.withheld_love",
                    nameOf(en.who()));
            color = GuiLanguage.INK;
        } else {
            line = Text.translatable(hate ? "heavyseas.reveal.asking_hate" : "heavyseas.reveal.asking_love",
                    nameOf(en.who()));
        }
        context.drawCenteredTextWithShadow(textRenderer, line, width / 2, y, color);
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
    private OrderedText fit(Text text, int maxWidth) {
        return Language.getInstance().reorder(textRenderer.trimToWidth(text, Math.max(1, maxWidth)));
    }
}
