package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.ActionChoiceC2S;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.HudView;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 行动：轮到你时，一键选一件事（决策 ⑦ · ADR-0018 §7.4）。
 *
 * <h2>三带，与手牌一面同一套</h2>
 * <ul>
 *   <li><b>上带（公开）</b>：回合 · 阶段 · 海鸥，下面是座位轨 —— 全船都看得见轮到谁。</li>
 *   <li><b>中带（待决）</b>：五件事，次序与颜色照交互稿。焦点一进来就在「划船」（稿子的第一步）。</li>
 *   <li><b>下带（私有）</b>：你是谁、还剩多少。</li>
 * </ul>
 *
 * <h2>长倒计时</h2>
 * 行动选择留一分钟谈判；超时按「什么也不做」。Esc 仍可收起，服务端倒计时继续走，按行动键可再开。
 *
 * <h2>「顿」在确认那一下</h2>
 * 稿子第三步：确认 —— 面板收起，交给世界。与补给箱里的「顿」同一个动词、同一组数（{@link GuiLanguage}）。
 * 补给箱里它说「替你定了」，这里说「你定了」—— 两处说的都是「定了」。
 *
 * <h2>用物资打开手牌</h2>
 * 这一格不是当场猜一张牌：它打开手牌一面，让玩家看到卡面再按 U。医疗箱随后还会进入目标一面。
 */
public final class ActionScreen extends GameScreen {

    /** 交互稿里的五件事，次序照稿子。用物资没有 {@link ActionChoiceC2S.Kind}，因为它先打开手牌。 */
    private enum Choice {
        ROW("heavyseas.action.row", "heavyseas.action.row_hint", ActionChoiceC2S.Kind.ROW, false),
        SWAP("heavyseas.action.swap", "heavyseas.action.swap_hint", ActionChoiceC2S.Kind.SWAP, false),
        STEAL("heavyseas.action.steal", "heavyseas.action.steal_hint", ActionChoiceC2S.Kind.STEAL, true),
        USE("heavyseas.action.use", "heavyseas.action.use_hint", null, false),
        PASS("heavyseas.action.pass", "heavyseas.action.pass_hint", ActionChoiceC2S.Kind.PASS, false);

        private final String label;
        private final String hint;
        private final ActionChoiceC2S.Kind kind;
        /** 稿子里抢夺是朱砂色的按钮：它会让人受伤（朱砂只给紧迫与伤害，ADR-0018 §7.3）。 */
        private final boolean harmful;

        Choice(String label, String hint, ActionChoiceC2S.Kind kind, boolean harmful) {
            this.label = label;
            this.hint = hint;
            this.kind = kind;
            this.harmful = harmful;
        }

        boolean enabled(HudView view) {
            return kind != null || this == USE;
        }

        String hint(HudView view) {
            return hint;
        }
    }

    private static final Choice[] CHOICES = Choice.values();

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private HudView view = HudView.IDLE;
    /** 焦点。<b>一进来就在「划船」</b>，不等玩家先动一下。 */
    private int focus;
    /** 每个按钮当前的抬起量，向目标插值。 */
    private final float[] lift = new float[CHOICES.length];
    /** 上一帧排出来的按钮位置，点击与悬停按它判。 */
    private List<Box> buttons = List.of();
    /** 确认了哪一件；{@code -1} 表示还没确认。 */
    private int snapIndex = -1;
    /** 「顿」的起点，0 表示没在播。 */
    private long snapAt;

    public ActionScreen() {
        super(Text.translatable("heavyseas.action.title"));
        refresh();
    }

    private void refresh() {
        MinecraftClient mc = MinecraftClient.getInstance();
        view = mc.world == null ? HudView.IDLE : GameComponents.of(mc.world).hudView();
    }

    /**
     * 收界面只在这里做。
     *
     * <p>❗不在 render 里换屏：这一帧余下的部分还在用一个已经 {@code removed} 的 Screen。
     */
    @Override
    public void tick() {
        refresh();
        if (decided()) {
            if (!snapping()) {
                close();                      // 「顿」播完：面板收起，交给世界
            }
            return;
        }
        if (!view.myTurnToAct()) {
            close();                          // 轮次已经走了（对局结束、被指令推进……）
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        refresh();
        if (!view.active() || !view.seated()) {
            return;                           // 这一帧什么都不画，tick 会把它收起来
        }
        renderBackdrop(context, mouseX, mouseY, delta);
        long now = System.currentTimeMillis();
        long dt = frameDelta(now);

        int text = textH();
        drawPublicBand(context, view, TOP_BAND_Y);
        int railY = topBandH() + 4;
        int identityY = identityY();
        int timeoutY = identityY - HINT_GAP - text;
        int countdownY = timeoutY - HINT_GAP - text;
        int barY = countdownY - BAR_TO_TEXT - BAR_H;
        int hintY = barY - HINT_GAP - text;
        // 按钮排几行只看宽度，所以能先算它要多高；座位轨拿走的是<b>剩下的</b>那点高 ——
        // 反过来（轨先按窗口取高、按钮再挤剩下的）按钮会被顶出去盖住说明那一行。
        capRail(hintY - BTN_GAP - buttonsH() - topRoom() - railY);
        // 按钮连同下面那行说明，在座位轨与身份行之间居中。顶上留出「抬」与「顿」的高度，免得弹进轨里。
        buttons = layoutButtons(railY + railH() + topRoom(), hintY - BTN_GAP);
        drawRail(context, railY, rowWidth(buttons));

        // 鼠标真的动了才把焦点带过去（停着的指针不算指向，见 GameScreen#mouseActuallyMoved）。
        // ❗每帧都要调一次：它记的是上一帧指针在哪，停一帧就会漏掉一次移动。
        boolean moved = mouseActuallyMoved(mouseX, mouseY);
        if (moved && !decided()) {
            int hovered = indexAt(buttons, mouseX, mouseY);
            if (hovered >= 0) {
                focus = hovered;
            }
        }

        float snapP = GuiLanguage.snap(now, snapAt);
        for (int i = 0; i < CHOICES.length; i++) {
            Box b = buttons.get(i);
            lift[i] = GuiLanguage.approach(lift[i], i == focus ? GuiLanguage.LIFT_PX : 0f, dt);
            float rise = -lift[i];
            float scale = 1f;
            if (i == snapIndex) {
                rise += GuiLanguage.snapRise(snapP);
                scale = GuiLanguage.snapScale(snapP);
            }
            Choice c = CHOICES[i];
            drawButton(context, b, Text.translatable(c.label), i == focus, labelColor(c),
                    c.enabled(view) ? GuiLanguage.ground() : withAlpha(GuiLanguage.ground(), DISABLED_FILL_ALPHA),
                    rise, scale);
        }
        // 说明只跟焦点走一行：按不动的那几件，这一行说为什么。
        drawLine(context, Text.translatable(CHOICES[focus].hint(view)),
                width / 2, hintY, GuiLanguage.muted());
        int barW = countdownWidth(rowWidth(buttons));
        drawCountdown(context, now, view.actionDeadlineMs(), view.actionWindowMs(),
                (width - barW) / 2, barY, barW, countdownY);
        drawLine(context, Text.translatable("heavyseas.action.timeout_hint"),
                width / 2, timeoutY, GuiLanguage.dim());
        drawIdentity(context, view, identityY);
    }

    /**
     * 按钮顶上要留多少空：「抬」· 「顿」的位移 · 「顿」放大长出来的那一截（绕底边缩放，全在上面）· 金框。
     *
     * <p>与卡的 {@link #snapRoom} 同一个算法，只是框是按钮的 1 像素 —— 那边是实拍到金框切进座位轨之后才补上的。
     */
    private int topRoom() {
        int h = buttonHeight();
        return (int) Math.ceil(GuiLanguage.LIFT_PX + GuiLanguage.SNAP_PEAK_RISE
                + (GuiLanguage.SNAP_PEAK_SCALE - 1f) * h) + 1 + 2;
    }

    /**
     * 五个按钮排在哪。每帧按当前的 {@code width}/{@code height} 重算：窗口与界面尺寸随时会变。
     *
     * <p>一行放不下就折行（交互稿里是 flex-wrap）：界面尺寸大、窗口窄的时候，
     * 英文的「Use a provision」一行放不下五个。折出去的那一行同样留出「抬」与「顿」的高度。
     */
    private List<Box> layoutButtons(int top, int bottom) {
        int h = buttonHeight();
        int[] w = buttonWidths();
        List<int[]> rows = buttonRows(w);

        int rowStep = h + BTN_GAP + topRoom();
        // 放得下就居中；放不下就贴着 bottom 往上排 —— 顶上的那点「抬」与「顿」的余量可以让，
        // 说明那一行不能让：854×480 下就算不画头像，只有名字的轨加按钮也还差两个单位。
        int blockH = buttonsH();
        int y = bottom - top >= blockH ? top + (bottom - top - blockH) / 2 : bottom - blockH;
        List<Box> out = new ArrayList<>(CHOICES.length);
        for (int[] row : rows) {
            int total = -BTN_GAP;
            for (int i = row[0]; i < row[1]; i++) {
                total += w[i] + BTN_GAP;
            }
            int x = (width - total) / 2;
            for (int i = row[0]; i < row[1]; i++) {
                out.add(new Box(x, y, w[i], h));
                x += w[i] + BTN_GAP;
            }
            y += rowStep;
        }
        return out;
    }

    private int[] buttonWidths() {
        int[] w = new int[CHOICES.length];
        for (int i = 0; i < CHOICES.length; i++) {
            w[i] = buttonWidth(Text.translatable(CHOICES[i].label));
        }
        return w;
    }

    /** 哪几个按钮排在同一行：{@code [起, 止)}。只看宽度，所以不必先知道排在哪个高度上。 */
    private List<int[]> buttonRows(int[] w) {
        int avail = width - 2 * SIDE;
        List<int[]> rows = new ArrayList<>();
        int from = 0;
        int rowW = w[0];
        for (int i = 1; i < CHOICES.length; i++) {
            if (rowW + BTN_GAP + w[i] > avail) {
                rows.add(new int[]{from, i});
                from = i;
                rowW = w[i];
            } else {
                rowW += BTN_GAP + w[i];
            }
        }
        rows.add(new int[]{from, CHOICES.length});
        return rows;
    }

    /** 按钮那一片连行间的「抬」与「顿」一共多高 —— 座位轨要按它让出高度。 */
    private int buttonsH() {
        int rows = buttonRows(buttonWidths()).size();
        return rows * buttonHeight() + (rows - 1) * (BTN_GAP + topRoom());
    }

    /**
     * 座位轨：谁坐哪、轮到谁。<b>公开信息</b> —— 等别人行动时，全船看的就是这一条。
     *
     * <p>每格至少放得下最长的那个名字；整条跟按钮那一片一样宽，但绝不出屏。
     */
    private void drawRail(DrawContext context, int y, int stageW) {
        List<String> seats = view.seats();
        if (seats.isEmpty()) {
            return;
        }
        int n = seats.size();
        int widest = 0;
        for (String id : seats) {
            widest = Math.max(widest, textW(Text.translatable("heavyseas.character." + id)));
        }
        int cell = Math.min(Math.max(widest + 8, stageW / n), (width - 2 * SIDE) / n);
        int left = (width - n * cell) / 2;
        for (int i = 0; i < n; i++) {
            String id = seats.get(i);
            boolean here = id.equals(view.actor());
            int x = left + i * cell;
            drawSeat(context, id, x, y, cell, here ? GuiLanguage.gold() : 0, false,
                    here ? GuiLanguage.gold() : GuiLanguage.muted(), here ? GuiLanguage.gold() : GuiLanguage.ground());
        }
    }

    private int labelColor(Choice c) {
        int base = c.harmful ? GuiLanguage.cinnabar() : (c == Choice.PASS ? GuiLanguage.muted() : GuiLanguage.ink());
        return c.enabled(view) ? base : withAlpha(base, DISABLED_TEXT_ALPHA);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!decided()) {
            int i = indexAt(buttons, (int) mouseX, (int) mouseY);
            if (i >= 0) {
                focus = i;
                confirm();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 用哪个键开的，就用哪个键收起来（理由同手牌一面：写死的键，玩家改了键位就收不起来）。
        if (HeavySeasClient.actKey().matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        if (!decided()) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_LEFT -> {
                    focus = Math.max(0, focus - 1);
                    return true;
                }
                case GLFW.GLFW_KEY_RIGHT -> {
                    focus = Math.min(CHOICES.length - 1, focus + 1);
                    return true;
                }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                    confirm();
                    return true;
                }
                default -> {
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void confirm() {
        Choice c = CHOICES[focus];
        if (!c.enabled(view)) {
            return;                           // 按不动的那几件：下面那行字已经在说为什么，不发包、不「顿」
        }
        if (c == Choice.USE) {
            if (client != null) {
                client.setScreen(new HandScreen());
                LOGGER.info("行动：打开手牌挑特殊物资");
            }
            return;
        }
        ClientPlayNetworking.send(ActionChoiceC2S.of(c.kind));
        snapIndex = focus;
        snapAt = System.currentTimeMillis();
        // 验收靠这一行与服务端那行「行动（界面）」对上：客户端按了、服务端认了，两个来源。
        LOGGER.info("行动：确认「{}」，播一次「顿」", c.name());
    }

    /** 确认之后这一面就定了 —— 再点再按都不作数，否则会往服务端发第二个行动。 */
    private boolean decided() {
        return snapAt > 0L;
    }

    private boolean snapping() {
        return decided() && GuiLanguage.snap(System.currentTimeMillis(), snapAt) < 1f;
    }
}
