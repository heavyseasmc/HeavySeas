package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.card.ActionCard;
import io.github.heavyseasmc.mod.net.ContestActionC2S;
import io.github.heavyseasmc.mod.state.ContestView;
import io.github.heavyseasmc.mod.state.HudView;
import io.github.heavyseasmc.mod.ui.HudPart;
import io.github.heavyseasmc.mod.ui.SheetLayout;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 站队：打起来了，谁来帮谁（ADR-0023 · 决策 ④）。
 *
 * <h2>三张牌（ADR-0050）</h2>
 * 与行动一面同一形态（{@link ChoiceCards}）：加入进攻（一把弯刀）· 加入防守（圆盾）· 旁观（眼睛）。
 * 进攻、防守两张牌下各挂一排那一边的人与体型和 —— 原先是两行字「进攻：收藏家 · 体型和 9」。
 *
 * <h2>两边的体型和摆在脸上，武器一个字都不提</h2>
 * 阵营与体型和是<b>公开</b>的 —— 谈判就靠它：看得见「进攻 9 · 防守 7」，才谈得成「我帮你，你给我一张水」。
 * 而押下的武器是全场唯一的暗牌（决策 ④），所以这两个数里<b>不含武器</b>；
 * 含了的话，减一减就知道对面押了几点，挂武器那一段就白做了。
 *
 * <h2>焦点一进来在「旁观」</h2>
 * 什么也不做的结果就是不加入 —— 焦点落在那个「你什么都不按会发生的事」上，与另外几面同一条。
 * 更要紧的是<b>不替人预选一边</b>：预选进攻方等于替他站了队。
 *
 * <h2>加入之后这一面就没有可做的决定了</h2>
 * 「加入后不得反悔、也不能换边」（规则 §9.3），所以定了之后「顿」一下就收起来 ——
 * 留着它只会让人以为还能改。已经在场上的人（进攻方、防守方、先加入的助拳者）根本不会看到这一面。
 */
public final class StanceScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private static final List<ActionCard> CARDS = List.of(ActionCard.ATTACK, ActionCard.DEFEND, ActionCard.WATCH);
    /** 旁观在最后，也是默认焦点。 */
    private static final int WATCH = 2;
    /** 牌下那一排：头像直径 · 头像之间 · 头像与「体型和」之间 · 离牌底多远（稿子像素）；字号（物理像素，稿子单位）。 */
    private static final double SIDE_TOKEN = 34;
    private static final double SIDE_TOKEN_GAP = 4;
    private static final double SIDE_GAP = 8;
    private static final double SIDE_TOP = 12;
    private static final double SIDE_CAP_PX = 13;
    private static final double SIDE_NUM_PX = 28;

    private HudView view;
    private final Contest.Kind kind;
    private final String attacker;
    private final String target;
    private final ChoiceCards cards;

    public StanceScreen(HudView view) {
        super(Text.translatable("heavyseas.stance.title"));
        this.view = view;
        this.kind = view.contest().kind();
        this.attacker = view.contest().attacker();
        this.target = view.contest().target();
        this.cards = new ChoiceCards(this, CARDS, false, WATCH, i -> true, c -> Text.translatable(c.effectKey()));
    }

    @Override
    public void tick() {
        view = projection();
        if (cards.decided()) {
            if (!cards.snapping()) {
                close();                      // 定了：「顿」播完就收（旁观不发包，投影不会替它收）
            }
            return;
        }
        // ❗认的是「这一段还轮不轮得到我」，不是某个包：站队段结束、这一场收场、我被人拉进场上 —— 都该收。
        if (!view.myStance()) {
            close();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        long now = System.currentTimeMillis();
        long dt = frameDelta(now);
        ContestView c = view.contest();

        Bands b = drawChrome(context, view);
        int headerY = b.stageTop();
        drawLine(context, Text.translatable(switch (kind) {
                            case SWAP -> "heavyseas.stance.header_swap";
                            case STEAL -> "heavyseas.stance.header_steal";
                            case RATION -> "heavyseas.stance.header_ration";
                        },
                        nameOf(attacker), nameOf(target)), width / 2, headerY, GuiLanguage.cinnabar());
        SheetLayout l = sheet();
        double k = l.k();
        int s = guiScale();
        double top0 = Math.max(l.railBottom() + CardRow.RAIL_CLEAR * k, (headerY + lineStep() + HINT_GAP) * (double) s);
        double below = (SIDE_TOP + Math.max(SIDE_TOKEN, SIDE_NUM_PX * 1.25)) * k;
        ChoiceCards.Geometry g = cards.render(context, b, mouseX, mouseY, now, dt, top0, below);
        if (!inspecting()) {
            drawSide(context, l, g, 0, c.attackSide(), c.attackPower());
            drawSide(context, l, g, 1, c.defendSide(), c.defendPower());
        }

        drawFootBand(context, b, List.of(keys("select", "←", "→")), inspectHints("confirm"), now,
                new Countdown(c.deadlineMs(), c.windowMs(), 0));
    }

    /**
     * 第 {@code i} 张牌下那一排：这一边站了谁（小头像）· 体型和。物理像素画。
     * 一个人都没有时只写一道破折号 —— 空白与「还没读到」长得一样。
     */
    private void drawSide(DrawContext context, SheetLayout l, ChoiceCards.Geometry g, int i, List<String> ids, int power) {
        double k = l.k();
        int d = l.len(SIDE_TOKEN);
        int tokenGap = l.len(SIDE_TOKEN_GAP);
        int gap = l.len(SIDE_GAP);
        int capPx = l.len(SIDE_CAP_PX);
        int numPx = l.len(SIDE_NUM_PX);
        String cap = Text.translatable("heavyseas.stance.power").getString();
        String num = ids.isEmpty() ? "—" : Integer.toString(power);
        int tokensW = ids.isEmpty() ? 0 : ids.size() * d + (ids.size() - 1) * tokenGap;
        int capW = GuiText.widthPx(cap, capPx, false, 0);
        int numW = GuiText.widthPx(num, numPx, true, 0);
        int total = tokensW + (tokensW > 0 ? gap : 0) + capW + gap / 2 + numW;
        int x = g.x()[i] + g.cw()[i] / 2 - total / 2;
        int top = g.bottom() + l.len(SIDE_TOP);
        int rowH = Math.max(d, GuiText.linePxAt(numPx, true));
        int mid = top + rowH / 2;
        pxBegin(context);
        for (String id : ids) {
            GuiMaterial.portrait(context, id, x, mid - d / 2, d, 1f);
            GuiMaterial.hudPart(context, HudPart.TOK38_PLAIN, x, mid - d / 2, d, d, d / (double) HudPart.TOK38_PLAIN.w());
            x += d + tokenGap;
        }
        if (tokensW > 0) {
            x += gap - tokenGap;
        }
        GuiText.drawPx(context, cap, x, mid - GuiText.linePxAt(capPx, false) / 2, capW, capPx, false,
                GuiLanguage.muted(), GuiText.Align.LEFT, 0);
        x += capW + gap / 2;
        GuiText.drawPx(context, num, x, mid - GuiText.linePxAt(numPx, true) / 2, numW, numPx, true,
                GuiLanguage.ink(), GuiText.Align.LEFT, 0);
        pxEnd(context);
    }

    /**
     * 站队。「旁观」也发一个包（ADR-0095 D1）：规则里没有「宣布中立」，它不改局面，只告诉服务端「我不加入了」——
     * 该答的真人都答了，这一段就提前收；演示局不限时之后没有它会一直等。
     */
    private boolean confirm(int i) {
        ActionCard c = CARDS.get(i);
        // 这一段我答过了：服务端随即可能把窗口收短（该答的真人都答了），收短不是一扇新窗 —— 不能再把这一面弹回来（U14）
        HeavySeasClient.answeredContest(view);
        if (c == ActionCard.WATCH) {
            ClientPlayNetworking.send(ContestActionC2S.of(ContestActionC2S.Kind.STAND_ASIDE));
            LOGGER.info("站队：确认「WATCH」，告诉服务端不加入");
            return true;
        }
        boolean attack = c == ActionCard.ATTACK;
        ClientPlayNetworking.send(ContestActionC2S.of(attack
                ? ContestActionC2S.Kind.JOIN_ATTACK : ContestActionC2S.Kind.JOIN_DEFEND));
        // 与语言无关的一行：验收靠它与服务端那行「站队（界面）」对上。
        LOGGER.info("站队：确认「{}」", attack ? "ATTACK" : "DEFEND");
        return true;
    }

    @Override
    protected boolean leftClick(double mouseX, double mouseY) {
        return cards.leftClick(mouseX, mouseY, this::confirm);
    }

    @Override
    protected boolean rightClick(double mouseX, double mouseY) {
        return cards.rightClick(mouseX, mouseY);
    }

    @Override
    protected int inspectedIndex() {
        return cards.focus();
    }

    @Override
    protected boolean onKey(int keyCode, int scanCode, int modifiers) {
        if (cards.keyPressed(keyCode, this::confirm)) {
            return true;
        }
        return super.onKey(keyCode, scanCode, modifiers);
    }

    /** 上带左头那一句（ADR-0043 D3 (b)）：只在还没定的时候说。 */
    @Override
    protected Text cue() {
        return cards.decided() ? null : Text.translatable("heavyseas.cue.stance");
    }
}
