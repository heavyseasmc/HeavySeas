package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.state.EndgameProgress;
import io.github.heavyseasmc.mod.state.ExperienceView;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.HudView;
import io.github.heavyseasmc.mod.ui.HudLayout.Rect;
import io.github.heavyseasmc.mod.ui.HudPart;
import io.github.heavyseasmc.mod.ui.ScoreLayout;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 计分面板（决策 ⑪ 三幕之末 · ADR-0022）。版式照样张 b-5：左边全船按分数排一列（桂冠 · 头像 · 名字 · 合计），
 * 右边一张账 —— 头像与名字、四项各带一枚木刻图标、一道线、金色的大号合计。几何在 {@link ScoreLayout}。
 *
 * <h2>点谁看谁的账</h2>
 * 左边一列可以选（← → 或点一下）：选中的那一行一抹金，右边换成他的四项。试合入（O46）之后计分这一幕全船看得到
 * 每个人的四项小计（翻牌那一幕已经过去，爱恨都亮了）；<b>来源小字只有自己的</b>（投影里只有收件人自己的
 * {@code scoreExplanation}）—— 来源会点名，点名的事只说给本人听。
 *
 * <h2>四项分开列</h2>
 * 「自恋者生存分算两次」是第一项与第三项相加的自然结果，并在一起就看不出来了（ADR-0018 §7.4）。
 * 来源小字很短，只写这一分是怎么得的（「医生 活着」「留在艇上」），不讲规则（界面不是说明书）。
 *
 * <h2>动</h2>
 * 左边一行一行「发」下来，右边四项跟着「发」，合计最后「顿」一下；换一个人看，他的账重发一遍。
 * 「顿」只抬不放大 —— 字不许套矩阵缩放（ADR-0037 §7.3）。
 *
 * <h2>上一局</h2>
 * 也是「最近一局」的回看（{@link #openLast}）：结果由服务端按进程留着，客户端不存档。
 * 服务端停一段时间就收起会话（{@code EndgamePhase.SCORE_HOLD_MS}），左下只写「几秒后返航」，不画倒计时条。
 */
public final class ScoreScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 四项的图标（ADR-0090 的木刻：救生圈 · 钱币 · 心 · 碎心），与牌上同一样东西；按主题着色。 */
    private static final HudPart[] ITEM_ICONS = {HudPart.IC_BUOY, HudPart.IC_COIN, HudPart.IC_HEART, HudPart.IC_HATE};
    /** 合计是「这一账」的数：金墨（样张 .goldink）。账永远是浅底的搪瓷，所以不随主题换。 */
    private static final int GOLD_INK = 0xFF7C5A0C;
    /** 为 0 的那一项、来源小字、移出游戏的人淡到多少。 */
    private static final float ZERO_ALPHA = 0.42f;
    private static final float NOTE_ALPHA = 0.7f;
    private static final float GONE_ALPHA = 0.35f;
    /** 账上四项从第几个「发」的位次起（左边一列先发两行）。 */
    private static final int ACCOUNT_DEAL_OFFSET = 2;

    /** 左下那一行是返航提示（「10 秒后返航…」）：聊天窄条不摆在这一面（样张 A1 的唯一例外）。 */
    @Override
    protected boolean showsChatStrip() {
        return false;
    }

    private HudView view = HudView.IDLE;
    private ExperienceView.Result result = ExperienceView.Result.NONE;
    private List<ExperienceView.ScoreSummary> scores = List.of();
    private final List<Box> rankHits = new ArrayList<>();
    private boolean review;
    private boolean logged;
    private int selected;
    private String selectedWho = "";
    private String shownMatch = "";
    private long dealAt;
    private long accountAt;

    public ScoreScreen() {
        this(false);
    }

    private ScoreScreen(boolean review) {
        super(Text.translatable(review ? "heavyseas.score.last_title" : "heavyseas.score.title"));
        this.review = review;
    }

    /** 客户端不存档：每次打开都问服务端此刻按进程留着的那一局。 */
    public static boolean openLast(MinecraftClient client) {
        if (client.world == null || !GameComponents.of(client.world).hudView().experience().lastResult().present()) {
            return false;
        }
        ScoreScreen screen = new ScoreScreen(true);
        if (client.currentScreen instanceof GameScreen parent) {
            parent.openChild(screen);
        } else {
            client.setScreen(screen);
        }
        return true;
    }

    private boolean refresh() {
        if (client == null || client.world == null) {
            return false;
        }
        view = projection();
        boolean live = !review && view.endgame().stage() == EndgameProgress.Stage.SCORES
                && !view.experience().scores().isEmpty();
        if (live) {
            result = new ExperienceView.Result(view.experience().matchId(), view.turn(),
                    view.endgame().outcome().name(), view.endgame().alive(), view.experience().scores(),
                    view.seated() ? view.character() : "", view.experience().scoreExplanation(), view.endgame().debugChanges(),
                    view.removed());
        } else {
            result = view.experience().lastResult();
            if (!result.present()) {
                return false;
            }
            review = true;
        }
        scores = result.scores().stream().sorted(Comparator.comparingInt(ExperienceView.ScoreSummary::total)
                .reversed()).toList();
        if (scores.isEmpty()) {
            return false;
        }
        if (!shownMatch.equals(result.matchId())) {
            shownMatch = result.matchId();
            selectedWho = result.yours();
            logged = false;
            dealAt = System.currentTimeMillis();
            accountAt = dealAt;
        }
        selected = 0;
        for (int i = 0; i < scores.size(); i++) {
            if (scores.get(i).who().equals(selectedWho)) {
                selected = i;
            }
        }
        selectedWho = scores.get(selected).who();
        return true;
    }

    @Override
    public void tick() {
        if (!refresh()) {
            close();
        }
    }

    @Override
    protected boolean stillWanted() {
        return client != null && client.world != null && (projection().endgame().stage() == EndgameProgress.Stage.SCORES
                || projection().experience().lastResult().present());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (!refresh()) {
            return;
        }
        renderBackdrop(context, mouseX, mouseY, delta);
        long now = System.currentTimeMillis();
        Bands b = drawChrome(context, view);
        var pane = budget();                 // 日志钉住时界面那一块（不是整窗）：与骨架同一扇窗口
        ScoreLayout l = ScoreLayout.of(pane.paneWidth(), pane.height(), GuiText::linePxAt,
                (px, bold) -> GuiText.linePxAt(GuiText.readingSizePx(px, bold), bold), scores.size());
        pxBegin(context);
        drawHeadline(context, l);
        drawRanking(context, l, now);
        drawAccount(context, l, now);
        drawFoot(context, l);
        pxEnd(context);
        drawFootBand(context, b, List.of(keys("select", "←", "→")), List.of(keys("close", "Esc")), now, null);
    }

    /** 上面两行：这一局怎么结束的（第几天）· 几个人活着上岸；回看上一局时标题是「上一局的账」，下面一行才是怎么结束的。 */
    private void drawHeadline(DrawContext context, ScoreLayout l) {
        boolean landed = "LANDED".equals(result.outcome());
        Text day = Text.translatableWithFallback("heavyseas.numeral." + result.turn(), Integer.toString(result.turn()));
        String ending = Text.translatable(landed ? "heavyseas.score.headline.landed" : "heavyseas.score.headline.all_dead",
                day).getString();
        String title = review ? Text.translatable("heavyseas.score.last_title").getString() : ending;
        String subtitle = review ? ending
                : landed ? Text.translatable("heavyseas.score.subline", result.alive()).getString() : "";
        Rect t = l.title();
        GuiText.drawPx(context, title, t.x(), t.y(), t.w(), l.len(ScoreLayout.TITLE_PX), false, GuiLanguage.ink(),
                GuiText.Align.CENTER, l.len(3));
        if (!subtitle.isEmpty()) {
            Rect s = l.subtitle();
            GuiText.drawPx(context, subtitle, s.x(), s.y(), s.w(), readingPx(l, ScoreLayout.SUB_PX, false), false,
                    GuiLanguage.secondary(), GuiText.Align.CENTER, l.len(2));
        }
    }

    /**
     * 左边一列（样张 .rank）：全船按合计从高到低。胜者（最高分，并列都算）一枚桂冠、合计是金的；
     * 选中的那一行底下一抹金、左边一道金；你自己的头像一圈金；移出游戏的淡下去。
     */
    private void drawRanking(DrawContext context, ScoreLayout l, long now) {
        rankHits.clear();
        int best = scores.get(0).total();
        int namePx = readingPx(l, ScoreLayout.NAME_PX, false);
        int totalPx = l.len(ScoreLayout.TOTAL_PX);
        int lineInk = GuiLanguage.Hud.alpha(GuiLanguage.ink(), 0.18f);
        for (int i = 0; i < scores.size(); i++) {
            ScoreLayout.Row slot = l.rows().get(i);
            rankHits.add(unitBox(slot.row()));
            float p = GuiLanguage.deal(now, dealAt, i);
            if (p <= 0f) {
                continue;
            }
            int dy = Math.round((1f - p) * (float) GuiMetrics.pixels(GuiLanguage.DEAL_RISE));
            ExperienceView.ScoreSummary score = scores.get(i);
            boolean mine = score.who().equals(result.yours());
            boolean gone = result.removed().contains(score.who());     // 当前局与「上一局」同一个来源：快照里带着
            boolean winner = score.total() == best;
            Rect row = shift(slot.row(), dy);
            if (i == selected) {
                // .r.me：linear-gradient(90deg, rgba(230,188,80,.22), transparent 70%) + 左边一道金
                int fadeW = Math.round(row.w() * 0.7f);
                int band = Math.max(1, l.len(2));
                for (int c = 0; c < fadeW; c += band) {
                    float a = 0.22f * (1f - c / (float) fadeW);
                    context.fill(row.x() + c, row.y(), row.x() + Math.min(fadeW, c + band), row.bottom(),
                            GuiLanguage.Hud.alpha(GuiLanguage.gold(), a));
                }
                context.fill(row.x(), row.y(), row.x() + Math.max(2, l.len(4)), row.bottom(), GuiLanguage.gold());
            }
            if (winner) {
                Rect laurel = shift(slot.laurel(), dy);
                GuiMaterial.hudIcon(context, HudPart.IC_LAUREL, laurel.x(), laurel.y(), laurel.w(), laurel.h(),
                        GuiLanguage.gold(), laurel.w() / 24.0);
            }
            Rect avatar = shift(slot.avatar(), dy);
            GuiMaterial.portrait(context, score.who(), avatar.x(), avatar.y(), avatar.w(), gone ? GONE_ALPHA : 1f);
            GuiMaterial.hudPart(context, mine ? HudPart.TOK42_YOU : HudPart.TOK42_PLAIN, avatar.x(), avatar.y(),
                    avatar.w(), avatar.h(), avatar.w() / 42.0);
            int ink = gone ? GuiLanguage.dim() : GuiLanguage.ink();
            Rect name = shift(slot.name(), dy);
            GuiText.drawPx(context, nameOf(score.who()).getString(), name.x(), name.y(), name.w(), namePx, false, ink,
                    GuiText.Align.LEFT, 0);
            Rect total = shift(slot.total(), dy);
            GuiText.drawPx(context, Integer.toString(Math.max(0, score.total())), total.x(), total.y(), total.w(), totalPx,
                    true, gone ? GuiLanguage.dim() : winner ? GuiLanguage.gold() : ink, GuiText.Align.RIGHT, 0);
            // 每一行底下一道淡线（样张 border-bottom）：浅纸上用墨的淡色，不用深木底那一道黑
            context.fill(row.x(), row.bottom() - 1, row.right(), row.bottom(), lineInk);
        }
    }

    /** 右边那张账（样张 .mine，搪瓷）：头像与名字 · 四项 · 一道线 · 合计。 */
    private void drawAccount(DrawContext context, ScoreLayout l, long now) {
        ExperienceView.ScoreSummary score = scores.get(selected);
        boolean mine = score.who().equals(result.yours());
        Rect card = l.card();
        int ink = GuiLanguage.Hud.ENAMEL_LINE;
        GuiMaterial.hudPart(context, HudPart.ENAMEL, card.x(), card.y(), card.w(), card.h(), l.k());
        Rect head = l.headAvatar();
        GuiMaterial.portrait(context, score.who(), head.x(), head.y(), head.w(), 1f);
        GuiMaterial.hudPart(context, mine ? HudPart.TOK48_YOU : HudPart.TOK48_PLAIN, head.x(), head.y(), head.w(), head.h(),
                head.w() / 48.0);
        Rect cap = l.caption();
        GuiText.drawPx(context, Text.translatable(mine ? "heavyseas.score.yours" : "heavyseas.score.theirs").getString(),
                cap.x(), cap.y(), cap.w(), readingPx(l, ScoreLayout.CAPTION_PX, false), false,
                GuiLanguage.Hud.alpha(ink, 0.75f), GuiText.Align.LEFT, l.len(4));
        Rect name = l.headName();
        GuiText.drawPx(context, nameOf(score.who()).getString(), name.x(), name.y(), name.w(),
                l.len(ScoreLayout.ACCOUNT_NAME_PX), true, ink, GuiText.Align.LEFT, l.len(3));

        int[] values = {score.selfSurvival(), score.treasure(), score.loved(), score.hated()};
        List<Text> notes = mine ? result.explanation() : List.of();
        int labelPx = readingPx(l, ScoreLayout.LABEL_PX, false);
        int notePx = readingPx(l, ScoreLayout.NOTE_PX, false);
        int valuePx = l.len(ScoreLayout.VALUE_PX);
        for (int i = 0; i < values.length; i++) {
            float p = GuiLanguage.deal(now, accountAt, i + ACCOUNT_DEAL_OFFSET);
            if (p <= 0f) {
                continue;
            }
            int dy = Math.round((1f - p) * (float) GuiMetrics.pixels(GuiLanguage.DEAL_RISE) / 2f);
            ScoreLayout.Item item = l.items().get(i);
            int color = values[i] == 0 ? GuiLanguage.Hud.alpha(ink, ZERO_ALPHA) : ink;
            Rect icon = shift(item.icon(), dy);
            GuiMaterial.hudIcon(context, ITEM_ICONS[i], icon.x(), icon.y(), icon.w(), icon.h(), color, icon.w() / 24.0);
            Rect text = shift(item.text(), dy);
            String label = ObjectiveNotes.scoreLabel(score.selfHate(), i).getString();
            int labelW = GuiText.drawPx(context, label, text.x(), text.y() + (text.h() - GuiText.linePxAt(labelPx, false)) / 2,
                    text.w(), labelPx, false, color, GuiText.Align.LEFT, 0);
            // 自己的每一项后面跟一句很短的来源（只给本人，见类注释）；放不下就缩、再不行截短，不挤到数上
            if (i < notes.size()) {
                int nx = text.x() + labelW + l.len(10);
                int room = text.right() - nx;
                if (room > l.len(24)) {
                    GuiText.drawPx(context, notes.get(i).getString(), nx,
                            text.y() + (text.h() - GuiText.linePxAt(notePx, false)) / 2, room, notePx, false,
                            GuiLanguage.Hud.alpha(ink, values[i] == 0 ? ZERO_ALPHA * NOTE_ALPHA : NOTE_ALPHA),
                            GuiText.Align.LEFT, 0);
                }
            }
            Rect value = shift(item.value(), dy);
            GuiText.drawPx(context, Integer.toString(values[i]), value.x(), value.y(), value.w(), valuePx, true, color,
                    GuiText.Align.RIGHT, 0);
        }
        // 最后一项落定那一刻，合计「顿」一下
        long snapAt = accountAt + (values.length - 1 + ACCOUNT_DEAL_OFFSET) * GuiLanguage.DEAL_STAGGER_MS
                + GuiLanguage.DEAL_MS;
        if (now >= snapAt) {
            Rect rule = l.rule();
            context.fill(rule.x(), rule.y(), rule.right(), rule.bottom(), ink);
            // 合计是这一账的数 —— 金墨。「顿」只抬，不放大。
            int rise = (int) Math.round(GuiMetrics.pixels(GuiLanguage.snapRise(GuiLanguage.snap(now, snapAt))));
            Rect sumLabel = shift(l.sumLabel(), rise);
            int sumLabelPx = readingPx(l, ScoreLayout.SUM_LABEL_PX, false);
            GuiText.drawPx(context, Text.translatable("heavyseas.score.total").getString(), sumLabel.x(),
                    sumLabel.y() + (sumLabel.h() - GuiText.linePxAt(sumLabelPx, false)) / 2, sumLabel.w(), sumLabelPx, false,
                    GOLD_INK, GuiText.Align.LEFT, l.len(3));
            Rect sum = shift(l.sum(), rise);
            int sumPx = l.len(ScoreLayout.SUM_PX);
            GuiText.drawPx(context, Integer.toString(score.total()), sum.x(),
                    sum.y() + (sum.h() - GuiText.linePxAt(sumPx, true)) / 2, sum.w(), sumPx, true, GOLD_INK,
                    GuiText.Align.RIGHT, 0);
        }
        if (mine && !logged) {
            logged = true;
            // 与语言无关的一行：GUI 回归靠它判「计分面板拿到了我自己的明细」，并与服务端那行「计分：」对账。
            LOGGER.info("计分面板：我 {} 存活 {} + 财宝 {} + 所爱 {} + 所恨 {} = {}", result.yours(),
                    score.selfSurvival(), score.treasure(), score.loved(), score.hated(), score.total());
        }
    }

    /**
     * 左下一行小字：几秒后返航（只在这一局还开着时）；靠右一行「本局有 N 处调试改动」（保留：悄悄改比不能改更伤信任，ADR-0060 D3）。
     * 两行都是板上的次要字（{@link GuiLanguage#secondary}）：原先调试那一行是淡朱砂，深色海图板上只有 1.7 : 1（2026-10-13 验收 93）。
     */
    private void drawFoot(DrawContext context, ScoreLayout l) {
        Rect foot = l.foot();
        int px = readingPx(l, ScoreLayout.FOOT_PX, false);
        int half = foot.w() / 2;
        if (!review && view.experience().returnAtMs() > 0) {
            String returning = Text.translatable("heavyseas.score.returning",
                    (GameClock.remaining(view.experience().returnAtMs()) + 999) / 1000).getString();
            GuiText.drawPx(context, returning, foot.x(), foot.y(), half, px, false, GuiLanguage.secondary(),
                    GuiText.Align.LEFT, 0);
        }
        if (result.debugChanges() > 0) {
            GuiText.drawPx(context, Text.translatable("heavyseas.debug.stamp", result.debugChanges()).getString(),
                    foot.x() + half, foot.y(), foot.w() - half, px, false,
                    GuiLanguage.secondary(), GuiText.Align.RIGHT, 0);
        }
    }

    /** 阅读字号那一档（设置里的「大字」）下这一级字画多大（物理像素）。 */
    private static int readingPx(ScoreLayout l, double design, boolean bold) {
        return GuiText.readingSizePx(l.len(design), bold);
    }

    private static Rect shift(Rect r, int dy) {
        return new Rect(r.x(), r.y() + dy, r.w(), r.h());
    }

    private void select(int index) {
        if (scores.isEmpty()) {
            return;
        }
        int next = Math.max(0, Math.min(scores.size() - 1, index));
        if (next != selected) {
            accountAt = System.currentTimeMillis();   // 换一个人看：他的账重发一遍
        }
        selected = next;
        selectedWho = scores.get(selected).who();
    }

    @Override
    protected boolean leftClick(double x, double y) {
        // 鼠标给的是 GUI 单位，命中框也是（unitBox 从物理像素换过来）
        for (int i = 0; i < rankHits.size(); i++) {
            if (rankHits.get(i).contains((int) x, (int) y)) {
                select(i);
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean onKey(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT) {
            select(selected + (key == GLFW.GLFW_KEY_LEFT ? -1 : 1));
            return true;
        }
        // 用哪个键开的，就用哪个键收起来（理由同行动一面：写死的键，玩家改了键位就收不起来）
        if (HeavySeasClient.actKey().matchesKey(key, scan)) {
            close();
            return true;
        }
        return super.onKey(key, scan, modifiers);
    }

    /** 这一面没有上带（样张 b-5：上面是两行字，由 {@link #drawHeadline} 画）。 */
    @Override
    protected void drawTopBand(DrawContext context, HudView v, Bands b) {
    }

    /** 这一条带上不画座位：计分那一列就是这一局的全员。 */
    @Override
    protected void drawRailBand(DrawContext context, HudView v, Bands b) {
    }
}
