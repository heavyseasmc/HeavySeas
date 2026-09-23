package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.ContestActionC2S;
import io.github.heavyseasmc.mod.state.ContestView;
import io.github.heavyseasmc.mod.state.HudView;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 挑牌：抢赢了，从他那里拿走一张（ADR-0023 · 规则 §5）。
 *
 * <h2>两种拿法，只有一种看得见</h2>
 * <b>面前亮着的</b>那几张是公开的，所以可以指名道姓地挑；<b>手牌</b>是暗的，
 * 所以只能「随机抽一张」—— 屏幕上它是一张牌背，旁边写着他手上有几张。
 * 界面上不能让抢夺方看着牌挑，那会把「亮出」这个取舍整个抹掉。
 *
 * <h2>默认答案是手牌那一张，所以高亮一进来就在它上面</h2>
 * 超时时服务端抽的就是手牌里的随机一张（手上没有才取面前第一张）。高亮是<b>你的默认答案</b> ——
 * 与补给箱、口渴两面同一条，只是这一面的默认值不是「第一张」。
 *
 * <h2>小孩只能挑手牌</h2>
 * 决策 ⑦：他的偷窃不问、不打，而且只偷手牌。那种局面下投影里 {@code victimFront} 是空的，
 * 这一面就只剩牌背那一个选项 —— 规则不在这里，这一面只是照投影画。
 */
public final class PickScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private HudView view;
    /** 高亮。<b>一进来就在手牌那一张</b>（有的话）—— 超时抽的就是它。 */
    private int highlight;
    private boolean committed;
    private float[] lift = new float[0];

    public PickScreen(HudView view) {
        super(Text.translatable("heavyseas.pick.title"));
        this.view = view;
        int count = options(view.contest());
        this.lift = new float[Math.max(1, count)];
        this.highlight = Math.max(0, count - (view.contest().victimHand() > 0 ? 1 : 0) - 1);
        if (view.contest().victimHand() > 0) {
            this.highlight = count - 1;       // 手牌那一张排在最后，而它就是默认答案
        }
    }

    /** 屏幕上一共几个选项：他面前亮着的每一张，外加「手牌随机一张」（他手上有牌时才有）。 */
    private static int options(ContestView contest) {
        return contest.victimFront().size() + (contest.victimHand() > 0 ? 1 : 0);
    }

    /** 这一格是不是「手牌随机一张」。 */
    private boolean isHandOption(int index) {
        return index == view.contest().victimFront().size() && view.contest().victimHand() > 0;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;                         // 逃了也只是超时替你抽一张，那是替你做的
    }

    @Override
    public void tick() {
        view = projection();
        if (!view.myPick()) {
            close();
        }
    }

    private record Layout(int w, int h, int left, int cardsTop, int rowW, int headerY, int hintY) {

        int cardX(int i) {
            return left + i * (w + CARD_GAP);
        }
    }

    private Layout layout(Bands b, int count) {
        int n = Math.max(1, count);
        // 牌下面贴着舞台底边的三行字：这一段是什么 · 说明 · 键位。
        int headerY = footerTop(b, 2);
        int step = lineStep();
        int room = liftRoom();
        int avail = headerY - HINT_GAP - b.stageTop();
        int h = cardHeightFor(n, avail - room);
        int w = GuiLanguage.cardWidth(h);
        int cardsTop = cardsTopIn(b.stageTop(), headerY - HINT_GAP, h, room);
        int rowW = cardRowWidth(n, w);
        return new Layout(w, h, (width - rowW) / 2, cardsTop, rowW, headerY, headerY + step);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        long now = System.currentTimeMillis();
        long dt = frameDelta(now);
        ContestView c = view.contest();
        int count = options(c);
        if (count == 0) {
            return;                           // 他身上一张都没有 —— 服务端根本不会走到这一段，tick 会收起来
        }
        if (lift.length != count) {
            lift = new float[count];
            highlight = Math.min(highlight, count - 1);
        }
        Bands b = drawChrome(context, view);
        Layout l = layout(b, count);
        Inspect in = inspect(b);
        float gathered = gathered(now);

        // 查看态里不认悬停：牌叠在一起了，命中框却还在摊开那一排的位置上。
        boolean moved = mouseActuallyMoved(mouseX, mouseY);
        if (moved && !committed && !inspecting()) {
            int hovered = indexAt(mouseX, mouseY, l, count);
            if (hovered >= 0) {
                highlight = hovered;
            }
        }
        // 高亮那一张最后画：收成一叠时它在堆顶，摊开时它抬起来 —— 两种状态下都要压在别人上面。
        for (int i = 0; i < count; i++) {
            if (i != highlight) {
                drawOne(context, dt, l, in, gathered, c, i);
            }
        }
        if (highlight >= 0 && highlight < count) {
            drawOne(context, dt, l, in, gathered, c, highlight);
        }


        drawLine(context, Text.translatable("heavyseas.pick.header", nameOf(c.target())),
                width / 2, l.headerY(), GuiLanguage.ink());
        // 说明只跟高亮走一行 —— 把每一张都摊开就变成读说明书了（与补给箱同一条）。
        drawLine(context, isHandOption(highlight)
                        ? Text.translatable("heavyseas.pick.from_hand", c.victimHand())
                        : provisionName(c.victimFront().get(highlight)),
                width / 2, l.hintY(), GuiLanguage.ink());
        // 牌背那一格没有说明可看 —— 抽到哪张不由你定，说出来就等于泄了底。
        if (gathered > 0f && highlight >= 0 && highlight < count && !isHandOption(highlight)) {
            String card = c.victimFront().get(highlight);
            drawCardPlate(context, in.plateX(), in.plateY(), in.plateW(), -1,
                    null, provisionName(card), provisionEffect(card));
        }
        drawFootBand(context, b, List.of(keys("select", "←", "→")), inspectHints("take"), now,
                new Countdown(c.deadlineMs(), c.windowMs(), l.rowW()));
    }

    /** 画一张：位置与大小在「摊成一排」与「收成一叠」之间按 {@code gathered} 插值。 */
    private void drawOne(DrawContext context, long dt, Layout l, Inspect in, float gathered, ContestView c, int i) {
        boolean hi = i == highlight;
        lift[i] = GuiLanguage.approach(lift[i], hi ? GuiLanguage.LIFT_PX : 0f, dt);
        CardPose pose = cardPose(in, gathered, l.cardX(i) + l.w() / 2f, l.cardsTop() + l.h(), l.w(), l.h(),
                hi ? 0 : 1 + Math.abs(i - Math.max(0, highlight)));
        context.getMatrices().push();
        context.getMatrices().translate(pose.cx() - pose.w() / 2f,
                pose.bottom() - pose.h() - lift[i] * (1f - gathered), 0);
        if (isHandOption(i)) {
            // 牌背：手牌是暗的，抽到哪张不由你定。
            CardTexture.drawBack(context, "provision", 0, 0, pose.w(), pose.h());
        } else {
            CardTexture.drawProvision(context, c.victimFront().get(i), 0, 0, pose.w(), pose.h());
        }
        if (hi) {
            drawCardFrame(context, pose.w(), pose.h());
        }
        context.getMatrices().pop();
    }

    private int indexAt(int mouseX, int mouseY, Layout l, int count) {
        return cardIndexAt(mouseX, mouseY, l.left(), l.cardsTop(), l.w(), l.h(), count);
    }

    /** 拿走高亮的那一张。定了就不再改 —— 再按一下会撞上「现在不是挑牌的时候」。 */
    private void commit() {
        if (committed) {
            return;
        }
        ContestView c = view.contest();
        int count = options(c);
        if (highlight < 0 || highlight >= count) {
            return;
        }
        committed = true;
        boolean fromHand = isHandOption(highlight);
        ClientPlayNetworking.send(fromHand
                ? ContestActionC2S.of(ContestActionC2S.Kind.PICK_HAND)
                : ContestActionC2S.of(ContestActionC2S.Kind.PICK_FRONT, c.victimFront().get(highlight)));
        // 与语言无关的一行：验收靠它与服务端那行「挑牌（界面）」对上。不写是哪一张 —— 手牌那条本来就不知道。
        LOGGER.info("挑牌：确认「{}」", fromHand ? "HAND" : "FRONT");
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (inspectClick(button)) {
            return true;
        }
        int count = options(view.contest());
        if (!committed && count > 0 && !inspecting()) {
            int i = indexAt((int) mouseX, (int) mouseY, layout(bands(), count), count);
            if (i >= 0) {
                highlight = i;
                commit();
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
        if (committed) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        int count = options(view.contest());
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> {
                highlight = Math.max(0, highlight - 1);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                highlight = Math.min(Math.max(0, count - 1), highlight + 1);
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                commit();
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }
}
