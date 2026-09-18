package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.state.GameState;
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
 * 计分面板（决策 ⑪ 三幕之末 · ADR-0022）。版式照交互稿「计分面板」那一面（用户 2026-09-15 定）。
 *
 * <h2>四项分开列</h2>
 * 「自恋者生存分算两次」是第一项与第三项相加的自然结果，并在一起就看不出来了（ADR-0018 §7.4）。
 * 所以舞台上是<b>你自己</b>的四行，用「发」逐条落下；合计用「顿」—— 稿子就是这么动的。
 *
 * <h2>上带多了一排全员合计</h2>
 * 稿子只画了一个人的四行；多人局里谁赢是公开的，得看得见。明细不公开：明细会把没翻的那张牌泄出去
 * （「所恨的人死了 7」就指明了是谁），所以投影里只有自己的明细，别人只有合计。
 *
 * <h2>没有倒计时</h2>
 * 服务端停一段时间就收起会话（{@code EndgamePhase.SCORE_HOLD_MS}），会话一收这一面自己关。
 */
public final class ScoreScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 你自己那四行最宽多少：一行一个标签一个数，太宽读起来要扫视。 */
    private static final int ROW_MAX_W = 240;

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
        int fh = textRenderer.fontHeight;

        // 上带（公开）：这一局怎么结束的，然后全员合计。
        Text outcome = Text.translatable(e.outcome() == GameState.Outcome.LANDED
                ? "heavyseas.game.over.landed" : "heavyseas.game.over.all_dead", view.turn(), e.alive());
        context.drawCenteredTextWithShadow(textRenderer, outcome, width / 2, TOP_BAND_Y, GuiLanguage.INK);
        int totalsBottom = drawTotals(context, e, TOP_BAND_Y + fh + 8);

        HudView.Score s = view.myScore();
        int identityY = identityY();
        if (!s.present()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable(
                            view.seated() ? "heavyseas.score.waiting" : "heavyseas.endgame.spectating"),
                    width / 2, (totalsBottom + identityY) / 2, GuiLanguage.MUTED);
            if (view.seated()) {
                drawIdentity(context, view, identityY);
            }
            return;
        }
        if (!logged) {
            logged = true;
            // 与语言无关的一行：GUI 回归靠它判「计分面板拿到了我自己的明细」，并与服务端那行「计分：」对账。
            LOGGER.info("计分面板：我 {} 存活 {} + 财宝 {} + 所爱 {} + 所恨 {} = {}", view.character(),
                    s.selfSurvival(), s.treasure(), s.loved(), s.hated(), s.total());
        }

        // 舞台：你自己的四行（发），合计（顿）。
        String[] labels = {"heavyseas.score.self", "heavyseas.score.treasure",
                "heavyseas.score.loved", "heavyseas.score.hated"};
        int[] values = {s.selfSurvival(), s.treasure(), s.loved(), s.hated()};
        int rowW = Math.min(ROW_MAX_W, width - 2 * SIDE);
        int rowH = fh + 6;
        int blockH = 5 * rowH + 6;
        int top = totalsBottom + Math.max(8, (identityY - 8 - totalsBottom - blockH) / 2);
        int left = (width - rowW) / 2;
        for (int i = 0; i < labels.length; i++) {
            float p = GuiLanguage.deal(now, dealAt, i);
            if (p <= 0f) {
                continue;
            }
            float rise = (1f - p) * GuiLanguage.DEAL_RISE;
            int y = Math.round(top + i * rowH + rise);
            int color = values[i] == 0 ? GuiLanguage.DIM : GuiLanguage.INK;
            context.drawTextWithShadow(textRenderer, Text.translatable(labels[i]), left, y, color);
            Text value = Text.literal(Integer.toString(values[i]));
            context.drawTextWithShadow(textRenderer, value, left + rowW - textRenderer.getWidth(value), y, color);
        }
        long snapAt = dealAt + 3 * GuiLanguage.DEAL_STAGGER_MS + GuiLanguage.DEAL_MS;
        if (now >= snapAt) {
            int ruleY = top + 4 * rowH + 1;
            context.fill(left, ruleY, left + rowW, ruleY + 1, GuiLanguage.GROUND);
            float p = GuiLanguage.snap(now, snapAt);
            float rise = GuiLanguage.snapRise(p);
            float scale = GuiLanguage.snapScale(p);
            int y = top + 4 * rowH + 6;
            context.getMatrices().push();
            // 绕这一行的中心缩放：「顿」长出来的那一截上下对称，不会压到上面那道线。
            context.getMatrices().translate(width / 2f, y + fh / 2f + rise, 0);
            context.getMatrices().scale(scale, scale, 1f);
            context.getMatrices().translate(-width / 2f, -(y + fh / 2f), 0);
            // 合计是「你」的数 —— 金色（ADR-0018 §7.3：金 = 你）。
            context.drawTextWithShadow(textRenderer, Text.translatable("heavyseas.score.total"), left, y, GuiLanguage.GOLD);
            Text total = Text.literal(Integer.toString(s.total()));
            context.drawTextWithShadow(textRenderer, total, left + rowW - textRenderer.getWidth(total), y, GuiLanguage.GOLD);
            context.getMatrices().pop();
        }
        drawIdentity(context, view, identityY);
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

    /**
     * 全员合计：名字一行、分数一行。最高分用墨色、其余用灰；你自己的名字是金色。
     *
     * @return 这一排的下沿
     */
    private int drawTotals(DrawContext context, HudView.Endgame e, int y) {
        List<HudView.Endgame.Entry> entries = e.entries();
        int n = entries.size();
        if (n == 0) {
            return y;
        }
        int fh = textRenderer.fontHeight;
        int best = Integer.MIN_VALUE;
        int widest = 0;
        for (HudView.Endgame.Entry en : entries) {
            best = Math.max(best, en.total());
            widest = Math.max(widest, textRenderer.getWidth(Text.translatable("heavyseas.character." + en.who())));
        }
        int cell = Math.min(Math.max(widest + 8, 48), (width - 2 * SIDE) / n);
        int left = (width - n * cell) / 2;
        for (int i = 0; i < n; i++) {
            HudView.Endgame.Entry en = entries.get(i);
            int x = left + i * cell + cell / 2;
            boolean me = en.who().equals(view.character());
            boolean top = en.total() == best;
            boolean gone = view.removed().contains(en.who());
            int nameColor = me ? GuiLanguage.GOLD : top ? GuiLanguage.INK : GuiLanguage.MUTED;
            context.drawCenteredTextWithShadow(textRenderer, fit(Text.translatable("heavyseas.character." + en.who()), cell - 4),
                    x, y, gone ? GuiLanguage.DIM : nameColor);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(Integer.toString(Math.max(0, en.total()))),
                    x, y + fh + 3, top ? GuiLanguage.INK : GuiLanguage.MUTED);
        }
        return y + 2 * fh + 6;
    }
}
