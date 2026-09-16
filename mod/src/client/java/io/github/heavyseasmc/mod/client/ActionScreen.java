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
 * <h2>这一面不计时</h2>
 * 交互稿原话「这一面本身不计时」—— 所以舞台下方没有那条细横杠，也没有「超时替你选」。
 * 计时属于「指定模式」（15 秒不选人退回重选，再超时算 Pass），那一半还没做。
 * 也正因为不计时，Esc 可以把它收起来：玩家收起它是为了回到世界里谈判，按键再开。
 *
 * <h2>「顿」在确认那一下</h2>
 * 稿子第三步：确认 —— 面板收起，交给世界。与补给箱里的「顿」同一个动词、同一组数（{@link GuiLanguage}）。
 * 补给箱里它说「替你定了」，这里说「你定了」—— 两处说的都是「定了」。
 *
 * <h2>这一版按得动的只有两件</h2>
 * 换座位与抢夺要进「指定模式」：回到世界里看着那个人右键（决策 ⑦）。替身没有实体可看，这一版验不了。
 * 用物资要先给物资效果建模（O19）。三件照样摆在原位、焦点照样落得上去，下面一行字说为什么按不动 ——
 * 藏起来的话，这一面看起来就像船上只有两件事可做。
 */
public final class ActionScreen extends GameScreen {

    /** 交互稿里的五件事，次序照稿子。{@code kind} 为空的就是这一版按不动的。 */
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

        boolean enabled() {
            return kind != null;
        }
    }

    private static final Choice[] CHOICES = Choice.values();

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private static final int SIDE = 20;
    /** 上带：一行字加一排海鸥格，与手牌一面同一个位置、同一个高度。 */
    private static final int TOP_BAND_Y = 12;
    private static final int TOP_BAND_H = 34;
    /** 座位轨：一行字，下面一条线。 */
    private static final int RAIL_H = 12;
    private static final int PAD_X = 10;
    private static final int PAD_Y = 6;
    private static final int GAP = 6;
    private static final int HINT_GAP = 8;
    /**
     * 按不动的那几件：底和字都只剩这么多不透明度。不另起一个颜色：语义色只有三个。
     *
     * <p>❗只淡字不够：「什么也不做」按稿子本来就是淡字（{@code MUTED}），第一次实拍时它和
     * 按不动的「换座位」看上去一样灰，分不出哪几个按得动。按钮的底一起淡下去才分得开。
     */
    private static final int DISABLED_FILL_ALPHA = 0x55;
    private static final int DISABLED_TEXT_ALPHA = 0x66;

    private HudView view = HudView.IDLE;
    /** 焦点。<b>一进来就在「划船」</b>，不等玩家先动一下。 */
    private int focus;
    /** 每个按钮当前的抬起量，向目标插值。 */
    private final float[] lift = new float[CHOICES.length];
    /** 上一帧的墙钟。插值按真实毫秒推，不按帧。 */
    private long lastFrameMs = System.currentTimeMillis();
    /** 上一帧排出来的按钮位置，点击与悬停按它判。 */
    private List<Button> buttons = List.of();
    /** 确认了哪一件；{@code -1} 表示还没确认。 */
    private int snapIndex = -1;
    /** 「顿」的起点，0 表示没在播。 */
    private long snapAt;

    private record Button(int x, int y, int w, int h) {

        boolean contains(int mx, int my) {
            return mx >= x && mx < x + w && my >= y - GuiLanguage.LIFT_PX && my < y + h;
        }
    }

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
        long dt = Math.max(0L, Math.min(200L, now - lastFrameMs));   // 掉帧时别让插值一步跳到底
        lastFrameMs = now;

        int text = textRenderer.fontHeight;
        drawPublicBand(context, view, TOP_BAND_Y);
        int railY = TOP_BAND_H + 4;
        int identityY = height - Math.max(8, Math.round(height * 0.05f)) - text;
        // 按钮连同下面那行说明，在座位轨与身份行之间居中。顶上留出「抬」与「顿」的高度，免得弹进轨里。
        buttons = layoutButtons(railY + RAIL_H + topRoom(), identityY - GAP - text - HINT_GAP);
        drawRail(context, railY, stageWidth());

        // 鼠标真的动了才把焦点带过去（停着的指针不算指向，见 GameScreen#mouseActuallyMoved）。
        // ❗每帧都要调一次：它记的是上一帧指针在哪，停一帧就会漏掉一次移动。
        boolean moved = mouseActuallyMoved(mouseX, mouseY);
        if (moved && !decided()) {
            int hovered = indexAt(mouseX, mouseY);
            if (hovered >= 0) {
                focus = hovered;
            }
        }

        float snapP = GuiLanguage.snap(now, snapAt);
        int bottom = 0;
        for (int i = 0; i < CHOICES.length; i++) {
            Button b = buttons.get(i);
            bottom = Math.max(bottom, b.y() + b.h());
            lift[i] = GuiLanguage.approach(lift[i], i == focus ? GuiLanguage.LIFT_PX : 0f, dt);
            float rise = -lift[i];
            float scale = 1f;
            if (i == snapIndex) {
                rise += GuiLanguage.snapRise(snapP);
                scale = GuiLanguage.snapScale(snapP);
            }
            drawButton(context, b, CHOICES[i], i == focus, rise, scale);
        }
        // 说明只跟焦点走一行：按不动的那几件，这一行说为什么。
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable(CHOICES[focus].hint),
                width / 2, bottom + HINT_GAP, GuiLanguage.MUTED);
        drawIdentity(context, view, identityY);
    }

    /**
     * 按钮顶上要留多少空：「抬」· 「顿」的位移 · 「顿」放大长出来的那一截（绕底边缩放，全在上面）· 金框。
     *
     * <p>与补给箱那一面同一个算法（{@code ProvisionScreen#topRoom}）—— 那边是实拍到金框切进座位轨之后才补上的。
     */
    private int topRoom() {
        int h = textRenderer.fontHeight + 2 * PAD_Y;
        return (int) Math.ceil(GuiLanguage.LIFT_PX + GuiLanguage.SNAP_PEAK_RISE
                + (GuiLanguage.SNAP_PEAK_SCALE - 1f) * h) + 1 + 2;
    }

    /**
     * 五个按钮排在哪。每帧按当前的 {@code width}/{@code height} 重算：窗口与界面尺寸随时会变。
     *
     * <p>一行放不下就折行（交互稿里是 flex-wrap）：界面尺寸大、窗口窄的时候，
     * 英文的「Use a provision」一行放不下五个。折出去的那一行同样留出「抬」与「顿」的高度。
     */
    private List<Button> layoutButtons(int top, int bottom) {
        int h = textRenderer.fontHeight + 2 * PAD_Y;
        int avail = width - 2 * SIDE;
        int[] w = new int[CHOICES.length];
        for (int i = 0; i < CHOICES.length; i++) {
            w[i] = textRenderer.getWidth(Text.translatable(CHOICES[i].label)) + 2 * PAD_X;
        }
        List<int[]> rows = new ArrayList<>();
        int from = 0;
        int rowW = w[0];
        for (int i = 1; i < CHOICES.length; i++) {
            if (rowW + GAP + w[i] > avail) {
                rows.add(new int[]{from, i});
                from = i;
                rowW = w[i];
            } else {
                rowW += GAP + w[i];
            }
        }
        rows.add(new int[]{from, CHOICES.length});

        int rowStep = h + GAP + topRoom();
        int blockH = rows.size() * h + (rows.size() - 1) * (GAP + topRoom());
        int y = top + Math.max(0, (bottom - top - blockH) / 2);
        List<Button> out = new ArrayList<>(CHOICES.length);
        for (int[] row : rows) {
            int total = -GAP;
            for (int i = row[0]; i < row[1]; i++) {
                total += w[i] + GAP;
            }
            int x = (width - total) / 2;
            for (int i = row[0]; i < row[1]; i++) {
                out.add(new Button(x, y, w[i], h));
                x += w[i] + GAP;
            }
            y += rowStep;
        }
        return out;
    }

    /** 按钮那一片有多宽。座位轨跟着它排，别让轨铺满全屏、按钮缩在中间一小截。 */
    private int stageWidth() {
        int left = Integer.MAX_VALUE;
        int right = 0;
        for (Button b : buttons) {
            left = Math.min(left, b.x());
            right = Math.max(right, b.x() + b.w());
        }
        return buttons.isEmpty() ? 0 : right - left;
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
            widest = Math.max(widest, textRenderer.getWidth(Text.translatable("heavyseas.character." + id)));
        }
        int cell = Math.min(Math.max(widest + 8, stageW / n), (width - 2 * SIDE) / n);
        int left = (width - n * cell) / 2;
        for (int i = 0; i < n; i++) {
            String id = seats.get(i);
            boolean here = id.equals(view.actor());
            int x = left + i * cell;
            context.fill(x + 2, y + 10, x + cell - 2, y + 11, here ? GuiLanguage.GOLD : GuiLanguage.GROUND);
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable("heavyseas.character." + id),
                    x + cell / 2, y, here ? GuiLanguage.GOLD : GuiLanguage.MUTED);
        }
    }

    private void drawButton(DrawContext context, Button b, Choice c, boolean focused, float rise, float scale) {
        context.getMatrices().push();
        // 绕底边缩放（与卡同一个做法）：「顿」长出来的那一截全在上面，版面留空才算得准。
        context.getMatrices().translate(b.x() + b.w() / 2f, b.y() + b.h() + rise, 0);
        context.getMatrices().scale(scale, scale, 1f);
        context.getMatrices().translate(-b.w() / 2f, -b.h(), 0);
        context.fill(0, 0, b.w(), b.h(),
                c.enabled() ? GuiLanguage.GROUND : withAlpha(GuiLanguage.GROUND, DISABLED_FILL_ALPHA));
        if (focused) {
            // 金 =「你 · 你选的那个」，与补给箱、手牌同一个用法。框画在同一个矩阵里，跟着按钮一起升、一起缩放。
            context.drawBorder(-1, -1, b.w() + 2, b.h() + 2, GuiLanguage.GOLD);
        }
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable(c.label), b.w() / 2,
                (b.h() - textRenderer.fontHeight) / 2 + 1, labelColor(c));
        context.getMatrices().pop();
    }

    private static int labelColor(Choice c) {
        int base = c.harmful ? GuiLanguage.CINNABAR : (c == Choice.PASS ? GuiLanguage.MUTED : GuiLanguage.INK);
        return c.enabled() ? base : withAlpha(base, DISABLED_TEXT_ALPHA);
    }

    private static int withAlpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0xFFFFFF);
    }

    private int indexAt(int mouseX, int mouseY) {
        for (int i = 0; i < buttons.size(); i++) {
            if (buttons.get(i).contains(mouseX, mouseY)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!decided()) {
            int i = indexAt((int) mouseX, (int) mouseY);
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
        if (!c.enabled()) {
            return;                           // 按不动的那几件：下面那行字已经在说为什么，不发包、不「顿」
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
