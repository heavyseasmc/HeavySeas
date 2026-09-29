package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.state.EndgameProgress;
import io.github.heavyseasmc.mod.state.HudView;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 计分面板（决策 ⑪ 三幕之末 · ADR-0022）。版式照样张 b-5：左边全船按分数排一列（头像 · 名字 · 合计，
 * 胜者一枚桂冠，你那一行金色），右边一张「你的账」—— 四项分开列，合计压底。
 *
 * <h2>为什么不再是一张表</h2>
 * 计分是一局的高潮，而 2026-09-30 之前舞台上只有你自己的四行字、上面一排全员合计 ——
 * 没有头像，也看不出谁赢（ADR-0045 §1.2 界 6）。稿子一直是现在这样，是没照着做。
 *
 * <h2>四项分开列</h2>
 * 「自恋者生存分算两次」是第一项与第三项相加的自然结果，并在一起就看不出来了（ADR-0018 §7.4）。
 *
 * <h2>明细只有你自己的</h2>
 * 别人只有合计：明细会把没翻的那张牌泄出去（「所恨的人死了 7」就指明了是谁），所以投影里只有自己的明细。
 *
 * <h2>动</h2>
 * 左边一行一行「发」下来，右边四项跟着「发」，合计最后「顿」一下。「顿」只抬不放大 ——
 * 字不许套矩阵缩放（ADR-0037 §7.3）。
 *
 * <h2>没有倒计时</h2>
 * 服务端停一段时间就收起会话（{@code EndgamePhase.SCORE_HOLD_MS}），会话一收这一面自己关。
 */
public final class ScoreScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 左边一列与右边那张账最宽多少（样张 b-5 在 1280×720 下量的），GUI 单位。 */
    private static final int LIST_MAX_W = 174;
    private static final int CARD_MAX_W = 146;
    private static final int COLUMN_GAP = 16;
    /** 左边一行最高多少：八个人排得下为准。 */
    private static final int ROW_MAX_H = 19;
    private static final int LIST_AVATAR = 14;
    private static final int CARD_AVATAR = 18;
    private static final int ICON = 9;
    private static final int PAD = 7;

    private static final String[] ITEM_LABELS = {"heavyseas.score.self", "heavyseas.score.treasure",
            "heavyseas.score.loved", "heavyseas.score.hated"};
    private static final String[] ITEM_ICONS = {"score_alive", "score_treasure", "heart", "heart_broken"};

    private HudView view = HudView.IDLE;
    private long dealAt;
    private boolean opened;
    private boolean logged;

    public ScoreScreen() {
        super(Text.translatable("heavyseas.score.title"));
    }

    @Override
    protected void init() {
        super.init();
        if (!opened) {
            opened = true;
            dealAt = System.currentTimeMillis();
        }
    }

    @Override
    public void tick() {
        view = projection();
        if (!view.myEndgame() || view.endgame().stage() != EndgameProgress.Stage.SCORES) {
            close();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        view = projection();
        HudView.Endgame e = view.endgame();
        if (!view.myEndgame() || e.stage() != EndgameProgress.Stage.SCORES) {
            return;
        }
        renderBackdrop(context, mouseX, mouseY, delta);
        long now = System.currentTimeMillis();

        // 上带（公开）：这一局怎么结束的（drawTopBand 覆写）。座位轨那一条带不画座位 —— 这一面的内容从那里就开始排。
        Bands b = drawChrome(context, view);
        int top = b.railY();
        int bottom = b.stageBottom() - 4;
        int avail = width - 2 * SIDE;
        HudView.Score s = view.myScore();
        boolean card = s.present();
        int listW = card ? Math.min(LIST_MAX_W, Math.round((avail - COLUMN_GAP) * 0.54f)) : Math.min(LIST_MAX_W, avail);
        int cardW = card ? Math.min(CARD_MAX_W, avail - COLUMN_GAP - listW) : 0;
        int total = listW + (card ? COLUMN_GAP + cardW : 0);
        int left = (width - total) / 2;
        drawRanking(context, e, now, left, top, listW, bottom);

        if (!card) {
            drawLine(context, Text.translatable(
                            view.seated() ? "heavyseas.score.waiting" : "heavyseas.endgame.spectating"),
                    left + listW + (width - left - listW) / 2, (top + bottom) / 2, GuiLanguage.muted());
        } else {
            if (!logged) {
                logged = true;
                // 与语言无关的一行：GUI 回归靠它判「计分面板拿到了我自己的明细」，并与服务端那行「计分：」对账。
                LOGGER.info("计分面板：我 {} 存活 {} + 财宝 {} + 所爱 {} + 所恨 {} = {}", view.character(),
                        s.selfSurvival(), s.treasure(), s.loved(), s.hated(), s.total());
            }
            drawAccount(context, s, now, left + listW + COLUMN_GAP, top, cardW, bottom);
        }
        drawKeyHints(context, List.of(keys("close", "Esc")), b.gaugeY(), GuiLanguage.muted());
    }

    /**
     * 左边一列：全船按合计从高到低。胜者（最高分，并列都算）一枚桂冠；你那一行金色、左边一道金；移出游戏的淡下去。
     */
    private void drawRanking(DrawContext context, HudView.Endgame e, long now, int x, int top, int w, int bottom) {
        List<HudView.Endgame.Entry> entries = new ArrayList<>(e.entries());
        if (entries.isEmpty()) {
            return;
        }
        entries.sort(Comparator.comparingInt(HudView.Endgame.Entry::total).reversed());
        int best = entries.get(0).total();
        int rowH = Math.max(textH() + 2, Math.min(ROW_MAX_H, (bottom - top) / entries.size()));
        int d = Math.min(LIST_AVATAR, rowH - 2 * GuiMaterial.ringMargin(LIST_AVATAR) - 1);
        int laurelW = ICON + 3;
        for (int i = 0; i < entries.size(); i++) {
            float p = GuiLanguage.deal(now, dealAt, i);
            if (p <= 0f) {
                continue;
            }
            HudView.Endgame.Entry en = entries.get(i);
            int y = Math.round(top + i * rowH + (1f - p) * GuiLanguage.DEAL_RISE);
            boolean me = view.seated() && en.who().equals(view.character());
            boolean gone = view.removed().contains(en.who());
            if (me) {
                context.fill(x, y, x + w, y + rowH - 1, (GuiLanguage.gold() & 0x00FFFFFF) | 0x2E000000);
                context.fill(x, y, x + 2, y + rowH - 1, GuiLanguage.gold());
            }
            if (en.total() == best) {
                GuiMaterial.icon(context, "laurel", x + 4, y + (rowH - ICON) / 2, ICON, GuiLanguage.gold());
            }
            int ax = x + 4 + laurelW + GuiMaterial.ringMargin(d);
            if (d > 4) {
                GuiMaterial.avatar(context, en.who(), ax, y + (rowH - d) / 2, d, me ? GuiLanguage.gold() : 0,
                        gone ? 0.35f : 1f);
            }
            int nameX = ax + d + GuiMaterial.ringMargin(d) + 5;
            Text score = Text.literal(Integer.toString(Math.max(0, en.total())));
            int scoreW = GuiText.width(score.getString(), GuiText.NAME, true);
            int nameColor = gone ? GuiLanguage.dim() : me ? GuiLanguage.gold() : GuiLanguage.ink();
            GuiText.line(context, nameOf(en.who()), nameX, y + (rowH - textH()) / 2, Math.max(1, x + w - 6 - scoreW - nameX),
                    GuiText.BODY, false, nameColor, GuiText.Align.LEFT);
            int lineH = GuiText.lineHeight(GuiText.NAME, true);
            GuiText.line(context, score, x + w - 4 - scoreW, y + (rowH - lineH) / 2, scoreW, GuiText.NAME, true,
                    gone ? GuiLanguage.dim() : me ? GuiLanguage.gold() : GuiLanguage.ink(), GuiText.Align.LEFT);
            context.fill(x, y + rowH - 1, x + w, y + rowH, (GuiLanguage.ground() & 0x00FFFFFF) | 0x66000000);
        }
    }

    /** 右边那张「你的账」：头像与名字 · 四项 · 一道线 · 合计。印在纸签上，字一律 onTag。 */
    private void drawAccount(DrawContext context, HudView.Score s, long now, int x, int top, int w, int bottom) {
        int fh = textH();
        int rowH = fh + 7;
        int headH = CARD_AVATAR + 2 * GuiMaterial.ringMargin(CARD_AVATAR);
        int totalH = GuiText.lineHeight(GuiText.TITLE, true);
        int h = PAD + headH + 6 + 4 * rowH + 6 + totalH + PAD;
        int y = top + Math.max(0, (bottom - top - h) / 2);
        GuiMaterial.tag(context, x, y, w, h);
        int ink = GuiLanguage.onTag(GuiLanguage.ink());
        int dim = GuiLanguage.onTag(GuiLanguage.dim());
        int muted = GuiLanguage.onTag(GuiLanguage.muted());
        int ring = GuiMaterial.ringMargin(CARD_AVATAR);
        GuiMaterial.avatar(context, view.character(), x + PAD + ring, y + PAD + ring, CARD_AVATAR, GuiLanguage.gold(), 1f);
        int hx = x + PAD + headH + 6;
        GuiText.line(context, Text.translatable("heavyseas.score.yours"), hx, y + PAD, w - (hx - x) - PAD,
                GuiText.CAPTION, false, muted, GuiText.Align.LEFT);
        GuiText.line(context, nameOf(view.character()), hx, y + PAD + GuiText.lineHeight(GuiText.CAPTION, false) + 1,
                w - (hx - x) - PAD, GuiText.NAME, true, ink, GuiText.Align.LEFT);

        int[] values = {s.selfSurvival(), s.treasure(), s.loved(), s.hated()};
        int ry = y + PAD + headH + 6;
        for (int i = 0; i < ITEM_LABELS.length; i++) {
            float p = GuiLanguage.deal(now, dealAt, i + 2);
            if (p > 0f) {
                int yy = Math.round(ry + i * rowH + (1f - p) * GuiLanguage.DEAL_RISE / 2f);
                int color = values[i] == 0 ? dim : ink;
                GuiMaterial.icon(context, ITEM_ICONS[i], x + PAD, yy + (fh - ICON) / 2 + 1, ICON, color);
                Text value = Text.literal(Integer.toString(values[i]));
                int vw = GuiText.width(value.getString(), GuiText.BODY, false);
                GuiText.line(context, Text.translatable(ITEM_LABELS[i]), x + PAD + ICON + 6, yy,
                        Math.max(1, w - 2 * PAD - ICON - 6 - vw - 4), GuiText.BODY, false, color, GuiText.Align.LEFT);
                GuiText.line(context, value, x + w - PAD - vw, yy, vw, GuiText.BODY, false, color, GuiText.Align.LEFT);
            }
        }
        long snapAt = dealAt + 5 * GuiLanguage.DEAL_STAGGER_MS + GuiLanguage.DEAL_MS;
        if (now >= snapAt) {
            int ruleY = ry + 4 * rowH + 2;
            context.fill(x + PAD, ruleY, x + w - PAD, ruleY + 1, ink);
            // 合计是「你」的数 —— 金色（ADR-0018 §7.3：金 = 你）。「顿」只抬，不放大。
            int rise = Math.round(GuiLanguage.snapRise(GuiLanguage.snap(now, snapAt)));
            int ty = ruleY + 4 + rise;
            Text totalText = Text.literal(Integer.toString(s.total()));
            int tw = GuiText.width(totalText.getString(), GuiText.TITLE, true);
            GuiText.line(context, Text.translatable("heavyseas.score.total"), x + PAD, ty + (totalH - fh) / 2,
                    Math.max(1, w - 2 * PAD - tw - 4), GuiText.BODY, false, muted, GuiText.Align.LEFT);
            GuiText.line(context, totalText, x + w - PAD - tw, ty, tw, GuiText.TITLE, true,
                    GuiLanguage.onTag(GuiLanguage.gold()), GuiText.Align.LEFT);
        }
    }

    /** 对局已经结束，上带写的是「这一局怎么结束的」，不是回合与阶段（ADR-0022）。 */
    @Override
    protected void drawTopBand(DrawContext context, HudView v, Bands b) {
        HudView.Endgame e = v.endgame();
        drawLine(context, Text.translatable(e.outcome() == GameState.Outcome.LANDED
                        ? "heavyseas.game.over.landed" : "heavyseas.game.over.all_dead", v.turn(), e.alive()),
                width / 2, b.topY(), GuiLanguage.ink());
    }

    /** 这一条带上不画座位：计分那一列就是这一局的全员，从这里开始排。 */
    @Override
    protected void drawRailBand(DrawContext context, HudView v, Bands b) {
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
}
