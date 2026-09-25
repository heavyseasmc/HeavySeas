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
 * <h2>版面就是那一套带位（ADR-0037 §7.10）</h2>
 * 上带 · 座位轨 · 倒计时 · 身份行四条带由 {@code GameScreen} 排定 —— 与别的每一面逐像素相同，
 * 换面时它们一个像素都不动。这一面只填舞台那一格：五件事，次序与颜色照交互稿，
 * 焦点一进来就在「划船」（稿子的第一步）；舞台底边上两行说明。
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
        ROW("heavyseas.action.row", ActionChoiceC2S.Kind.ROW, false),
        SWAP("heavyseas.action.swap", ActionChoiceC2S.Kind.SWAP, false),
        STEAL("heavyseas.action.steal", ActionChoiceC2S.Kind.STEAL, true),
        USE("heavyseas.action.use", null, false),
        PASS("heavyseas.action.pass", ActionChoiceC2S.Kind.PASS, false);

        private final String label;
        private final ActionChoiceC2S.Kind kind;
        /** 稿子里抢夺是朱砂色的按钮：它会让人受伤（朱砂只给紧迫与伤害，ADR-0018 §7.3）。 */
        private final boolean harmful;

        // ❗每件事「是什么」原先各挂一句说明，跟着焦点显示在按钮下面。那是规矩，不是状态 ——
        //   用户 2026-09-22：「GUI 文字不是用来教玩家怎么玩游戏的，承担这个的另有它物」（提示签，§7.12）。
        Choice(String label, ActionChoiceC2S.Kind kind, boolean harmful) {
            this.label = label;
            this.kind = kind;
            this.harmful = harmful;
        }

        /**
         * 这一件现在按不按得动。
         *
         * <p>❗2026-09-23 之前这里写的是 {@code kind != null || this == USE} —— 五件事**永远都可按**，
         * 参数 {@code view} 一次都没被读过，而 {@link #select} 里那条「按不动就不发包」的分支
         * 从来没有执行过。屏幕上看不出来：无风那一天界面照样给出「划船」，按下去才被服务端拒
         * （`风平浪静时没有航海阶段，不能划船`）。**界面给出一件必然失败的事，比不给更糟**。
         */
        boolean enabled(HudView view) {
            return this != ROW || view.canRow();
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
        if (!CHOICES[focus].enabled(view)) {
            focus = firstEnabled();           // 开面那一下，或天候变了之后：焦点不该停在按不动的那一件上
        }
        long now = System.currentTimeMillis();
        long dt = frameDelta(now);

        // 共有的四条带（上带 · 座位轨 · 倒计时 · 身份行）在 GameScreen 里排定，这一面只填舞台那一格。
        Bands b = drawChrome(context, view);
        // 舞台整格都给按钮：每件事是什么、超时会怎么办，都是规矩，不该由 GUI 来讲
        // （用户 2026-09-22：「GUI 文字不是用来教玩家怎么玩游戏的，承担这个的另有它物」）。
        buttons = layoutButtons(b.stageTop() + topRoom(), b.stageBottom());

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
            Box box = buttons.get(i);
            lift[i] = GuiLanguage.approach(lift[i], i == focus ? GuiLanguage.LIFT_PX : 0f, dt);
            float rise = -lift[i];
            float scale = 1f;
            if (i == snapIndex) {
                rise += GuiLanguage.snapRise(snapP);
                scale = GuiLanguage.snapScale(snapP);
            }
            Choice c = CHOICES[i];
            drawButton(context, box, Text.translatable(c.label), i == focus, labelColor(c),
                    c.enabled(view) ? GuiLanguage.ground() : withAlpha(GuiLanguage.ground(), DISABLED_FILL_ALPHA),
                    rise, scale);
        }
        drawFootBand(context, b, List.of(keys("select", "←", "→")), List.of(confirm("Enter")), now,
                new Countdown(view.actionDeadlineMs(), view.actionWindowMs(), rowWidth(buttons)));
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

    private int labelColor(Choice c) {
        int base = c.harmful ? GuiLanguage.cinnabar() : (c == Choice.PASS ? GuiLanguage.muted() : GuiLanguage.ink());
        return c.enabled(view) ? base : withAlpha(base, DISABLED_TEXT_ALPHA);
    }

    @Override
    protected boolean leftClick(double mouseX, double mouseY) {
        if (!decided()) {
            int i = indexAt(buttons, (int) mouseX, (int) mouseY);
            if (i >= 0) {
                focus = i;
                confirm();
                return true;
            }
        }
        return false;
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
                    focus = step(-1);
                    return true;
                }
                case GLFW.GLFW_KEY_RIGHT -> {
                    focus = step(1);
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

    /**
     * 往一边挪一格，**跳过按不动的那几件**。挪不动就留在原地。
     *
     * <p>焦点停在一件按不动的东西上，按 Enter 什么也不会发生 —— 而「按了没反应」与「界面卡住了」
     * 在屏幕上长得一样。无风那一天「划船」正是这一档（2026-09-23 实拍）。
     */
    private int step(int dir) {
        for (int i = focus + dir; i >= 0 && i < CHOICES.length; i += dir) {
            if (CHOICES[i].enabled(view)) {
                return i;
            }
        }
        return focus;
    }

    /** 开面那一下，焦点落在第一件按得动的事上。 */
    private int firstEnabled() {
        for (int i = 0; i < CHOICES.length; i++) {
            if (CHOICES[i].enabled(view)) {
                return i;
            }
        }
        return 0;
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
