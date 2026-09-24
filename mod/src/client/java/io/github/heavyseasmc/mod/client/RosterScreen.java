package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.RosterConfigS2C;
import io.github.heavyseasmc.mod.net.StartVoyageC2S;
import io.github.heavyseasmc.mod.state.HudView;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 大厅房主阵容面板：三套预设只是快捷键，八个角色都可自由勾选（对方 Wiki ADR-0033 §7.2）。
 *
 * <h2>与对局里的每一面同一个世界</h2>
 * 这是房主看到的<b>第一个</b> HeavySeas 界面。原先它是一张 Minecraft 自带的 {@code Screen}（自带的按钮、自带的底、纯白字），
 * 一点之后的补给箱却是 ADR-0018 那套语言 —— 同一局里两个视觉世界（2026-09-18 审查抓到的）。
 * 现在它与其余各面共用 {@link GameScreen}：同一层底、同一种按钮、焦点金框与「抬」、方向键与回车。
 *
 * <h2>勾选与焦点是两件事</h2>
 * 焦点 = 「你指着的那个」，金框（金 = 你）；勾选 = 「这个角色上船」，铜绿字（可选 · 安全），
 * 没勾的用次要色。「敲铃开航」在人数对不上时按不动：底与字一起淡下去，与行动一面按不动的那几件同一个样子。
 *
 * <h2>客户端选择不是事实</h2>
 * 服务端收到包后重新检查大厅船、发起者、人数、角色与对局状态才开局；这一面只负责把意图发出去。
 */
public final class RosterScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 头像章的直径上下限（GUI 单位）：小于下限只剩一团色，大于上限就把预设那一排挤出舞台。 */
    private static final int AVATAR_MIN = 18;
    private static final int AVATAR_MAX = 44;
    /** 按钮自带的那道阴影往下多出的一截，外加一点余量：量高度时一起算进去。 */
    private static final int AVATAR_SPARE = 4;
    private static final int[] PRESETS = {6, 7, 8};

    private final RosterConfigS2C config;
    private final Set<String> selected = new LinkedHashSet<>();
    /** 焦点在整片按钮里的下标：角色格 · 三个预设 · 开航 · 取消，依次排。 */
    private int focus;
    private final float[] lift;
    /** 上一帧排出来的全部按钮（与 {@link #focus} 同一套下标），点击与悬停按它判。 */
    private List<Box> boxes = List.of();

    public RosterScreen(RosterConfigS2C config) {
        super(Text.translatable("heavyseas.roster.title"));
        this.config = config;
        selected.addAll(preset(config.players()));
        this.lift = new float[config.characters().size() + PRESETS.length + 2];
    }

    private int characterCount() {
        return config.characters().size();
    }

    private int startIndex() {
        return characterCount() + PRESETS.length;
    }

    private int cancelIndex() {
        return startIndex() + 1;
    }

    private boolean canStart() {
        return selected.size() == config.players() && selected.size() >= 6 && selected.size() <= 8;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        long now = System.currentTimeMillis();
        long dt = frameDelta(now);

        // 还没入座，所以上带写标题、座位轨那一条带写人数。
        Bands b = drawChrome(context, projection());

        // 两排自上而下：八枚头像章（与座位轨同一个画法）· 预设与开航 / 取消。
        // ❗2026-09-25 第一次实拍（四面补拍）：原先是三片**文字按钮**（角色两行 · 预设 · 开航与取消），
        //   1280×720 装不下时整片往上挪进座位轨那一条带，两道通栏线从按钮中间穿过去；
        //   854×480 并成两片之后最后一排仍压着下沿线。角色是「人」，不是表单项 —— 换成与座位轨同一种头像章，
        //   一排放得下八个，舞台才装得下。带位是只由窗口算的（§7.11），这一面不许越过它。
        List<Text> presetLabels = new ArrayList<>();
        for (int players : PRESETS) {
            presetLabels.add(Text.translatable("heavyseas.roster.preset", players));
        }
        List<Text> actionLabels = List.of(Text.translatable("heavyseas.roster.start"), Text.translatable("gui.cancel"));
        List<Text> tailLabels = new ArrayList<>(presetLabels);
        tailLabels.addAll(actionLabels);

        int n = characterCount();
        int cell = railCell(Math.max(1, n));
        int lead = BTN_GAP + buttonLiftRoom();
        int tailH = rowHeight(layoutButtonRow(tailLabels, 0, BTN_GAP));
        int room = b.stageH() - buttonLiftRoom();
        // 头像章多大：先按格子宽（留出圈），再一格一格缩到连圈带名字、加上下面那一排都放得进舞台为止。
        // ❗第一版只按直径估高，漏了圈往外多出的那一截：854×480 下最后一排按钮的阴影压上了舞台下沿线。
        int d = Math.min(cell - 12, AVATAR_MAX);
        while (d > AVATAR_MIN
                && d + 2 * GuiMaterial.ringMargin(d) + 2 + lineStep() + lead + tailH + AVATAR_SPARE > room) {
            d--;
        }
        int m = GuiMaterial.ringMargin(d);
        int chipH = d + 2 * m + 2 + lineStep();
        int total = chipH + lead + tailH;
        int rowTop = b.stageTop() + buttonLiftRoom() + Math.max(0, (room - total) / 2);
        int left = (width - n * cell) / 2;
        List<Box> chips = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            chips.add(new Box(left + i * cell, rowTop, cell, chipH));
        }
        List<Box> tail = layoutButtonRow(tailLabels, rowTop + chipH + lead, BTN_GAP);

        List<Box> all = new ArrayList<>(chips);
        all.addAll(tail);
        boxes = all;

        // 鼠标真的动了才把焦点带过去（停着的指针不算指向，见 GameScreen#mouseActuallyMoved）。
        if (mouseActuallyMoved(mouseX, mouseY)) {
            int hovered = indexAt(all, mouseX, mouseY);
            if (hovered >= 0) {
                focus = hovered;
            }
        }

        for (int i = 0; i < all.size(); i++) {
            lift[i] = GuiLanguage.approach(lift[i], i == focus ? GuiLanguage.LIFT_PX : 0f, dt);
            if (i < n) {
                // 勾上 = 头像亮着、圈上一道铜绿（铜绿 = 可选 · 安全）；没勾 = 淡下去（与座位轨上「还没轮到」同一个淡）。
                // 焦点 = 金圈（金 = 你指着的那个）。原先在名字前加一个「✓」——那个字不在 GUI 字体的子集里，
                // 实拍只剩一个认不出的小点（2026-09-25，GuiGlyphCoverageTest 从此守着）。
                String id = config.characters().get(i);
                boolean on = selected.contains(id);
                Box box = all.get(i);
                int y = Math.round(box.y() + m - lift[i]);
                GuiMaterial.avatar(context, id, box.x() + (cell - d) / 2, y, d,
                        i == focus ? GuiLanguage.gold() : on ? GuiLanguage.verdigris() : 0, on ? 1f : SEAT_FADED);
                drawLineIn(context, nameOf(id), box.x() + 2, y + d + m + 2, cell - 4,
                        i == focus ? GuiLanguage.gold() : on ? GuiLanguage.verdigris() : GuiLanguage.muted());
                continue;
            }
            Text text;
            int color;
            int fill = GuiLanguage.ground();
            if (i < startIndex()) {
                text = presetLabels.get(i - n);
                color = GuiLanguage.ink();
            } else if (i == startIndex()) {
                text = actionLabels.get(0);
                // 人数对不上时按不动：底与字一起淡下去（只淡字与本来就淡字的「取消」分不开）。
                color = canStart() ? GuiLanguage.verdigris() : withAlpha(GuiLanguage.verdigris(), DISABLED_TEXT_ALPHA);
                fill = canStart() ? GuiLanguage.ground() : withAlpha(GuiLanguage.ground(), DISABLED_FILL_ALPHA);
            } else {
                text = actionLabels.get(1);
                color = GuiLanguage.muted();
            }
            drawButton(context, all.get(i), text, i == focus, color, fill, -lift[i], 1f);
        }
    }

    /** 还没开局，上带写这一面的标题。 */
    @Override
    protected void drawTopBand(DrawContext context, HudView view, Bands b) {
        drawLine(context, title, width / 2, b.topY(), GuiLanguage.ink());
    }

    /** 还没有座位，这一条带上写「要几个人 · 已选几个」。 */
    @Override
    protected void drawRailBand(DrawContext context, HudView view, Bands b) {
        drawLine(context, Text.translatable("heavyseas.roster.detail", config.players(), selected.size()),
                width / 2, b.railY(), GuiLanguage.muted());
    }

    private void toggle(String id) {
        if (!selected.remove(id)) {
            selected.add(id);
        }
    }

    private void applyPreset(List<String> ids) {
        selected.clear();
        selected.addAll(ids);
    }

    private List<String> preset(int players) {
        return switch (players) {
            case 6 -> config.preset6();
            case 7 -> config.preset7();
            case 8 -> config.preset8();
            default -> List.of();
        };
    }

    /** 按下焦点所在的那个按钮。 */
    private void activate(int index) {
        if (index < 0 || index >= boxes.size()) {
            return;
        }
        if (index < characterCount()) {
            toggle(config.characters().get(index));
        } else if (index < startIndex()) {
            applyPreset(preset(PRESETS[index - characterCount()]));
        } else if (index == startIndex()) {
            start();
        } else {
            close();
        }
    }

    private void start() {
        if (!canStart()) {
            return;                           // 按不动：人数对不上，上面那行字已经在说
        }
        List<String> ordered = config.characters().stream().filter(selected::contains).toList();
        ClientPlayNetworking.send(new StartVoyageC2S(config.anchor(), ordered));
        // 与语言无关的一行：验收靠它判「阵容真的发出去了」。
        LOGGER.info("阵容：敲铃开航 {} 人 {}", ordered.size(), ordered);
        close();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int i = indexAt(boxes, (int) mouseX, (int) mouseY);
        if (i >= 0) {
            focus = i;
            activate(i);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int last = Math.max(0, boxes.size() - 1);
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> {
                focus = Math.max(0, focus - 1);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                focus = Math.min(last, focus + 1);
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                // 上下是在两排之间跳：下面那一排回到头像那一排的第一个
                focus = focus >= characterCount() ? 0 : focus;
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                focus = focus < characterCount() ? Math.min(last, characterCount()) : focus;
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                activate(focus);
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }
}
