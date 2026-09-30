package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.state.EndgameProgress;
import io.github.heavyseasmc.mod.state.HudView;
import io.github.heavyseasmc.mod.ui.SheetLayout;
import io.github.heavyseasmc.mod.ui.HudLayout.Rect;
import io.github.heavyseasmc.mod.ui.HudPart;
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
    // 样张 b-5 的四枚：救生圈 · 宝石 · 心 · 碎心（样张 <symbol> 渲出来的那几枚，HudPart.IC_*）
    private static final HudPart[] ITEM_ICONS = {HudPart.IC_BUOY, HudPart.IC_GEM, HudPart.IC_HEART, HudPart.IC_HATE};

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

        // 照样张 b-5：上面两行字（这一局怎么结束的 · 几人活着上岸），左边全船按合计排，右边一块「你的账」，下面 Esc 收起。
        Bands b = drawChrome(context, view);
        SheetLayout l = sheet();
        HudView.Score s = view.myScore();
        pxBegin(context);
        drawHeadline(context, l, e);
        drawRanking(context, l, e, now);
        if (!s.present()) {
            Rect sheetBox = l.sheet();
            int px = l.len(17);
            GuiText.drawPx(context, Text.translatable(view.seated() ? "heavyseas.score.waiting" : "heavyseas.endgame.spectating")
                            .getString(), sheetBox.right() - l.len(ACCOUNT_RIGHT + ACCOUNT_W), sheetBox.y() + l.len(ACCOUNT_Y + 60),
                    l.len(ACCOUNT_W), px, false, GuiLanguage.Hud.ink2(), GuiText.Align.CENTER, 0);
        } else {
            if (!logged) {
                logged = true;
                // 与语言无关的一行：GUI 回归靠它判「计分面板拿到了我自己的明细」，并与服务端那行「计分：」对账。
                LOGGER.info("计分面板：我 {} 存活 {} + 财宝 {} + 所爱 {} + 所恨 {} = {}", view.character(),
                        s.selfSurvival(), s.treasure(), s.loved(), s.hated(), s.total());
            }
            drawAccount(context, l, s, now);
        }
        pxEnd(context);
        drawFootBand(context, b, List.of(), List.of(keys("close", "Esc")), now, null);
    }

    // 样张 b-5 的几何（稿子像素，相对板）：.sc-title { top: 30 } 22px 字距 3 + 小一行 14px 字距 2（70%）·
    // .rank { left: 86; top: 116; width: 520 } 一行 58 高：桂冠一格 26 · 头像 42 · 名字 19px · 合计 28px 粗，间距 14 ·
    // .mine { right: 86; top: 130; width: 440; padding: 22 28 24 }：头像 48 · 小标题 14px 字距 4 · 名字 22px 字距 3；
    // 四项一行 50 高、19px / 24px，为 0 的 42%；合计上面 2px 一道线、21px 字距 3 · 40px 粗，金墨 #7c5a0c。
    private static final double TITLE_Y = 30;
    private static final double RANK_X = 86;
    private static final double RANK_Y = 116;
    private static final double RANK_W = 520;
    private static final double RANK_ROW = 58;
    private static final double ACCOUNT_RIGHT = 86;
    private static final double ACCOUNT_Y = 130;
    private static final double ACCOUNT_W = 440;
    private static final int GOLD_INK = 0xFF7C5A0C;

    /** 上面两行：第几天、怎么结束的；几个人活着上岸（样张 .sc-title）。 */
    private void drawHeadline(DrawContext context, SheetLayout l, HudView.Endgame e) {
        Rect sheetBox = l.sheet();
        boolean landed = e.outcome() == GameState.Outcome.LANDED;
        Text day = Text.translatableWithFallback("heavyseas.numeral." + view.turn(), Integer.toString(view.turn()));
        String title = Text.translatable(landed ? "heavyseas.score.headline.landed" : "heavyseas.score.headline.all_dead",
                day).getString();
        int titlePx = l.len(22);
        int y = sheetBox.y() + l.len(TITLE_Y);
        GuiText.drawPx(context, title, sheetBox.x(), y, sheetBox.w(), titlePx, false, GuiLanguage.ink(),
                GuiText.Align.CENTER, l.len(3));
        if (landed) {
            int subPx = l.len(14);
            GuiText.drawPx(context, Text.translatable("heavyseas.score.subline", e.alive()).getString(), sheetBox.x(),
                    y + l.len(22 * 1.7) + l.len(2), sheetBox.w(), subPx, false,
                    GuiLanguage.Hud.alpha(GuiLanguage.ink(), 0.7f), GuiText.Align.CENTER, l.len(2));
        }
    }

    /**
     * 左边一列（样张 .rank）：全船按合计从高到低。胜者（最高分，并列都算）一枚桂冠、合计是金的；
     * 你那一行底下一抹金、左边一道金；移出游戏的淡下去。
     */
    private void drawRanking(DrawContext context, SheetLayout l, HudView.Endgame e, long now) {
        List<HudView.Endgame.Entry> entries = new ArrayList<>(e.entries());
        if (entries.isEmpty()) {
            return;
        }
        entries.sort(Comparator.comparingInt(HudView.Endgame.Entry::total).reversed());
        int best = entries.get(0).total();
        Rect sheetBox = l.sheet();
        double k = l.k();
        int x = sheetBox.x() + l.len(RANK_X);
        int w = l.len(RANK_W);
        int rowH = l.len(RANK_ROW);
        int top = sheetBox.y() + l.len(RANK_Y);
        int tok = l.len(42);
        int laurel = l.len(26);
        int namePx = l.len(19);
        int totalPx = l.len(28);
        for (int i = 0; i < entries.size(); i++) {
            float p = GuiLanguage.deal(now, dealAt, i);
            if (p <= 0f) {
                continue;
            }
            HudView.Endgame.Entry en = entries.get(i);
            int y = Math.round(top + i * rowH + (1f - p) * GuiLanguage.DEAL_RISE * guiScale());
            boolean me = view.seated() && en.who().equals(view.character());
            boolean gone = view.removed().contains(en.who());
            boolean winner = en.total() == best;
            if (me) {
                // .r.me：linear-gradient(90deg, rgba(230,188,80,.22), transparent 70%) + 左边 4px 金
                int fadeW = Math.round(w * 0.7f);
                for (int c = 0; c < fadeW; c += 2) {
                    float a = 0.22f * (1f - c / (float) fadeW);
                    context.fill(x + c, y, x + Math.min(fadeW, c + 2), y + rowH, GuiLanguage.Hud.alpha(GuiLanguage.gold(), a));
                }
                context.fill(x, y, x + l.len(4), y + rowH, GuiLanguage.gold());
            }
            int cy = y + rowH / 2;
            int cx = x + l.len(10);
            if (winner) {
                int icon = l.len(24);
                GuiMaterial.hudIcon(context, HudPart.IC_LAUREL, cx + (laurel - icon) / 2, cy - icon / 2, icon, icon,
                        GuiLanguage.gold(), k);
            }
            cx += laurel + l.len(14);
            GuiMaterial.portrait(context, en.who(), cx, cy - tok / 2, tok, gone ? 0.35f : 1f);
            GuiMaterial.hudPart(context, me ? HudPart.TOK42_YOU : HudPart.TOK42_PLAIN, cx, cy - tok / 2, tok, tok, k);
            cx += tok + l.len(14);
            String total = Integer.toString(Math.max(0, en.total()));
            int totalW = GuiText.widthPx(total, totalPx, true, 0);
            int right = x + w - l.len(14);
            GuiText.drawPx(context, nameOf(en.who()).getString(), cx, cy - GuiText.linePxAt(namePx, false) / 2,
                    Math.max(1, right - totalW - l.len(10) - cx), namePx, false,
                    gone ? GuiLanguage.dim() : GuiLanguage.ink(), GuiText.Align.LEFT, 0);
            GuiText.drawPx(context, total, right - totalW, cy - GuiText.linePxAt(totalPx, true) / 2, totalW + 2, totalPx, true,
                    gone ? GuiLanguage.dim() : winner ? GuiLanguage.gold() : GuiLanguage.ink(), GuiText.Align.LEFT, 0);
            // 每一行底下一道暗线 + 一道很淡的亮线（样张：border-bottom rgba(0,0,0,.55) · 0 1px rgba(255,220,170,.08)）
            context.fill(x, y + rowH - 1, x + w, y + rowH, 0x8C000000);
            context.fill(x, y + rowH, x + w, y + rowH + 1, 0x14FFDCAA);
        }
    }

    /** 右边那块「你的账」（样张 .mine，搪瓷）：头像与名字 · 四项 · 一道线 · 合计。 */
    private void drawAccount(DrawContext context, SheetLayout l, HudView.Score s, long now) {
        Rect sheetBox = l.sheet();
        double k = l.k();
        int w = l.len(ACCOUNT_W);
        int x = sheetBox.right() - l.len(ACCOUNT_RIGHT) - w;
        int top = sheetBox.y() + l.len(ACCOUNT_Y);
        int padX = l.len(28);
        int headTok = l.len(48);
        int itemH = l.len(50);
        int h = l.len(22) + headTok + l.len(10) + 4 * itemH + l.len(10 + 14) + l.len(40) + l.len(24);
        int ink = GuiLanguage.Hud.ENAMEL_LINE;
        GuiMaterial.hudPart(context, HudPart.ENAMEL, x, top, w, h, k);
        int tx = x + padX;
        int y = top + l.len(22);
        GuiMaterial.portrait(context, view.character(), tx, y, headTok, 1f);
        GuiMaterial.hudPart(context, HudPart.TOK48_YOU, tx, y, headTok, headTok, k);
        int hx = tx + headTok + l.len(12);
        int capPx = l.len(14);
        GuiText.drawPx(context, Text.translatable("heavyseas.score.yours").getString(), hx, y + l.len(2), w - (hx - x) - padX,
                capPx, false, GuiLanguage.Hud.alpha(ink, 0.75f), GuiText.Align.LEFT, l.len(4));
        int namePx = l.len(22);
        GuiText.drawPx(context, nameOf(view.character()).getString(), hx, y + l.len(20), w - (hx - x) - padX, namePx, true,
                ink, GuiText.Align.LEFT, l.len(3));
        y += headTok + l.len(10);
        int[] values = {s.selfSurvival(), s.treasure(), s.loved(), s.hated()};
        int labelPx = l.len(19);
        int valuePx = l.len(24);
        int icon = l.len(24);
        for (int i = 0; i < ITEM_LABELS.length; i++) {
            float p = GuiLanguage.deal(now, dealAt, i + 2);
            if (p > 0f) {
                int yy = Math.round(y + i * itemH + (1f - p) * GuiLanguage.DEAL_RISE * guiScale() / 2f);
                int cy = yy + itemH / 2;
                int color = values[i] == 0 ? GuiLanguage.Hud.alpha(ink, 0.42f) : ink;
                GuiMaterial.hudIcon(context, ITEM_ICONS[i], tx, cy - icon / 2, icon, icon, color, k);
                String value = Integer.toString(values[i]);
                int vw = GuiText.widthPx(value, valuePx, true, 0);
                GuiText.drawPx(context, Text.translatable(ITEM_LABELS[i]).getString(), tx + icon + l.len(14),
                        cy - GuiText.linePxAt(labelPx, false) / 2, Math.max(1, w - 2 * padX - icon - l.len(14) - vw - l.len(8)),
                        labelPx, false, color, GuiText.Align.LEFT, 0);
                GuiText.drawPx(context, value, x + w - padX - vw, cy - GuiText.linePxAt(valuePx, true) / 2, vw + 2, valuePx,
                        true, color, GuiText.Align.LEFT, 0);
            }
        }
        long snapAt = dealAt + 5 * GuiLanguage.DEAL_STAGGER_MS + GuiLanguage.DEAL_MS;
        if (now >= snapAt) {
            int ruleY = y + 4 * itemH + l.len(10);
            context.fill(tx, ruleY, x + w - padX, ruleY + Math.max(1, l.len(2)), ink);
            // 合计是「你」的数 —— 金墨（样张 .goldink）。「顿」只抬，不放大。
            int rise = Math.round(GuiLanguage.snapRise(GuiLanguage.snap(now, snapAt)) * guiScale());
            int ty = ruleY + l.len(14) + rise;
            String total = Integer.toString(s.total());
            int totalPx = l.len(40);
            int tw = GuiText.widthPx(total, totalPx, true, 0);
            int lineH = GuiText.linePxAt(totalPx, true);
            int sumPx = l.len(21);
            GuiText.drawPx(context, Text.translatable("heavyseas.score.total").getString(), tx,
                    ty + (lineH - GuiText.linePxAt(sumPx, false)) / 2, Math.max(1, w - 2 * padX - tw - l.len(8)), sumPx, false,
                    GOLD_INK, GuiText.Align.LEFT, l.len(3));
            GuiText.drawPx(context, total, x + w - padX - tw, ty, tw + 2, totalPx, true, GOLD_INK, GuiText.Align.LEFT, 0);
        }
    }

    /** 这一面没有上带（样张 b-5：上面是两行字，由 {@link #drawHeadline} 画）。 */
    @Override
    protected void drawTopBand(DrawContext context, HudView v, Bands b) {
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
