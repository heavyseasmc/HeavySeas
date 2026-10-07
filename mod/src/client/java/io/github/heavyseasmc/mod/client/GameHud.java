package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.HudView;
import io.github.heavyseasmc.mod.ui.HudLayout;
import io.github.heavyseasmc.mod.ui.HudLayout.Rect;
import io.github.heavyseasmc.mod.ui.HudPart;
import io.github.heavyseasmc.mod.ui.NotificationSidebarLayout;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;

import java.util.ArrayList;
import java.util.List;

/**
 * 主画面 HUD：左上一块状态牌与挂在它下面的金签、正上方一条座位轨、右上一扇天候舷窗与一枚日志页签；
 * 有新播报时右上换成展开的那一列（天候卡 · 效果说明签 · 航海日志）。收着的样子照样张 b-1，展开的照 b-2。
 *
 * <h2>照样张还原，一个坐标系</h2>
 * 用户 2026-09-30：「以第一张参考图为唯一视觉目标……不要重新设计，不要自行添加参考图中不存在的装饰」。
 * 几何全部在 {@link HudLayout}（稿子像素 × 一个系数 k，左上 / 右上两组锚点），装饰件全部是样张 CSS
 * 由浏览器渲出来的贴图（{@link HudPart}）—— 这里只负责按那两张表摆、把动态的东西画上去。
 * 整块在<b>物理像素</b>里画：矩阵先缩到 1 / 界面尺寸，界面尺寸 3 下与样张一个像素不差。
 * 2026-09-30 之前这里按 GUI 单位现排，用的是对局大面板的材质（带铜包角），实拍与样张差得最多的四处
 * （包角压住眼睛与 R · 舵轮盖住头像 · 右上两块大面板 · Minecraft 自带的心与饥饿同屏）见 ADR-0046。
 *
 * <h2>为什么一行字都没有</h2>
 * ADR-0037 §7.1 第 5 条「主画面不要成行的文字」，稿子上的主画面是<b>物件</b>。金签上只有铃与键帽 ——
 * 2026-09-25 起那里多过一句 ≤8 字的短语（ADR-0043 D3 (b)），用户 2026-09-30 定以样张为唯一基准后收回；
 * 短语仍在各面上带里。唯一的例外是「举着拳头找人」那几秒：它有倒计时、到点就退回，秒数照旧印在金签里（样张没画这一态）。
 *
 * <h2>它只说「有」，不说「是什么」</h2>
 * 手牌只给张数与开界面的键。牌面本身归手牌那一面（{@link HandScreen}）——
 * 把牌名铺在 HUD 上，一是挤，二是<b>别人凑过来看屏幕就全知道了</b>，而本作的手牌是隐藏信息。
 *
 * <h2>键永远是实际绑定的那一个</h2>
 * 金签与状态牌上的键帽印的是 {@link KeyBinding#getBoundKeyLocalizedText()}：改了键位还印 G 就是在说谎。
 *
 * <h2>色只从 {@link GuiLanguage} 取</h2>
 * 金 = 你 · 铜绿 = 正轮到 · 朱砂 = 紧迫与伤害（ADR-0018 §7.3）；样张里的其余颜色在 {@link GuiLanguage.Hud}。
 */
public final class GameHud {

    private static final int MARGIN = NotificationSidebarLayout.MARGIN;
    /** 普通 HUD 底部留给热栏；对局 Screen 没有热栏，可以用到窗口底。 */
    private static final int HUD_BOTTOM_SAFE = 48;

    /** 状态牌第二行四格阶段的图标（样张 b-1：天候 · 补给 · 行动 · 航海，与 {@link Phase} 的顺序一致）。 */
    private static final HudPart[] PHASE_ICONS = {HudPart.IC_SUN, HudPart.IC_CRATE, HudPart.IC_FIST, HudPart.IC_BOAT};
    /** 样张里几处字号（{@code font-size}，稿子像素）。 */
    private static final double ROMAN_PX = 22;
    private static final double COUNT_PX = 19;
    private static final double KEY_PX = 14;
    private static final double BADGE_PX = 13;
    private static final double UNREAD_PX = 12;
    /** 日志栏头的字距（{@code letter-spacing: 4px}）。 */
    private static final double HEAD_SPACING = 4;
    /** 展开时日志最多几条（样张 b-2 是四条），每条最多折两行。 */
    private static final int LOG_ENTRIES = 4;
    /** 钉住（L）时一屏最多几条：放得下就多给，放不下照旧从最旧的往回减；再早的靠滚轮翻（用户 2026-10-07）。 */
    private static final int PINNED_LOG_ENTRIES = 12;
    /** 拉伸（Shift + L）时一屏最多几条：放得下多少就排多少，一直排到快捷栏上面。 */
    private static final int EXPANDED_LOG_ENTRIES = 60;
    private static final int LOG_LINES_PER_ENTRY = 2;
    /** 效果说明签最多几行。 */
    private static final int TIP_LINES = 3;

    /** 金签上次换内容的时刻：换了就重新「发」一次（ADR-0018 §7.2：新东西到你面前）。 */
    private static String cueShown = "";
    private static long cueAt = 0L;
    /** 日志页签上的未读数：展开到底那一刻，这一局一共到过几条。 */
    private static int seenArrived = 0;

    private GameHud() {
    }

    public static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null || client.options.hudHidden) {
            return;
        }
        // HudRenderCallback 发生在 Screen.renderWithTooltip 之前。对局界面自己的底色会盖住这里画的东西，
        // 所以它们的侧栏交给 GameScreenSidebar 的 afterRender；这里必须彻底跳过，不能画一份在背后。
        if (client.currentScreen instanceof GameScreen) {
            return;
        }
        HudView view = GameComponents.of(client.world).hudView();
        if (!view.active()) {
            SidebarReveal.forget();          // 这一局结束了：下一局第一条播报要能触发滑出来
            cueShown = "";
            seenArrived = 0;
            return;                          // 没有对局就什么都不画，不留一个空框
        }
        long now = System.currentTimeMillis();
        SidebarReveal.observe(view.notifications(), now);
        if (WindowNotice.required()) {
            if (client.currentScreen == null) {
                WindowNotice.render(context);
            }
            return;
        }
        int scale = guiScale(client);
        HudLayout layout = HudLayout.of(client.getWindow().getFramebufferWidth(),
                client.getWindow().getFramebufferHeight());
        MatrixStack matrices = context.getMatrices();
        matrices.push();
        matrices.scale(1f / scale, 1f / scale, 1f);          // 往下全是物理像素（HudLayout 的坐标）
        drawPlaque(context, layout, view);
        drawRibbon(context, layout, view, now);
        drawLegend(context, layout, view);
        drawRail(context, layout, view);
        drawRight(context, layout, view, now, scale);
        matrices.pop();
        if (client.currentScreen == null) {
            DesignationAim.draw(context, client);   // 举着拳头时准星下面「指着：谁 · 滚轮换人」（ADR-0095 F2）
        }
        OverboardCue.drawFlash(context);
    }

    // ---------------------------------------------------------------- Minecraft 自带 HUD 在对局中的样子（InGameHudMixin 调）

    /**
     * 此刻要不要按样张收起 Minecraft 自带的心 · 饥饿 · 护甲 · 氧气 · 经验、把热栏画成样张那一条：有对局、且偏好没说要自带的。
     *
     * <p>对局中这几条不带信息：服务端每 tick 把饥饿钉在满、血量只是引擎体力的镜像、身体不许受伤（{@code PlayerBodies}），
     * 背包在开航时托管清空（{@code MistSea}）—— 体力已经画在状态牌的点上。偏好 {@code vanillaHud=show} 回到 Minecraft 自带的样子。
     */
    /**
     * 这一帧热栏画不画：在雾海维度里不画（用户 2026-10-07，{@code InGameHudMixin}）。
     *
     * <p>「在不在雾海」认服务端那一包天色（{@link SkyOverride}）：服务端只给进了雾海的人发接管，维度名来自航程布局的数据，
     * 客户端不另写一份。偏好 {@code vanillaHud=show} 照旧回到 Minecraft 自带的样子（用户 2026-09-30：「不要直接删除这些功能」）。
     */
    public static boolean hotbarHidden() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.world != null && !ClientPrefs.vanillaHudInVoyage() && SkyOverride.forWorld(client.world) != null;
    }

    public static boolean voyageHud() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || ClientPrefs.vanillaHudInVoyage()) {
            return false;
        }
        return GameComponents.of(client.world).hudView().active();
    }

    /**
     * 热栏的底，照样张 {@code .hotbar}：一圈 3 px 的深边（{@code rgba(20,20,20,.8)}），里面半透明的深色格
     * （{@code rgba(0,0,0,.55)}），格与格之间一道 3 px 的灰线（{@code rgba(140,140,140,.7)}）。
     *
     * <p>坐标是 Minecraft 给的那一块（182×22 个 GUI 单位）：样张的 3 px 在界面尺寸 3 下正好是 1 个单位，所以边与线都按 1 个单位画，
     * 任何界面尺寸下都跟着热栏走；格距用 Minecraft 的 20 个单位 —— 样张是示意，61 px 的重复格走到第九格只剩一截，
     * 直接用它的话物品会一格比一格偏出格子。
     */
    public static void drawHotbar(DrawContext context, int x, int y, int w, int h) {
        context.fill(x, y, x + w, y + h, GuiLanguage.Hud.HOTBAR_EDGE);
        context.fill(x + 1, y + 1, x + w - 1, y + h - 1, GuiLanguage.Hud.HOTBAR_CELL);
        for (int i = 1; i < 9; i++) {
            int sx = x + 1 + i * 20 - 1;
            context.fill(sx, y + 1, sx + 1, y + h - 1, GuiLanguage.Hud.HOTBAR_RULE);
        }
    }

    static int guiScale(MinecraftClient client) {
        return Math.max(1, (int) Math.round(client.getWindow().getScaleFactor()));
    }

    // ---------------------------------------------------------------- 状态牌（样张 .plaque）

    private static void drawPlaque(DrawContext context, HudLayout l, HudView view) {
        double k = l.k();
        Rect p = l.plaque();
        GuiMaterial.hudPart(context, HudPart.PLAQUE, p.x(), p.y(), p.w(), p.h(), k);
        int ink = GuiLanguage.ink();
        int max = view.seated() ? Math.max(1, view.maxHealth()) : 0;
        // 头像：金圈 = 你。旁观的人没有座位 —— 画一枚空圈也是在说谎，所以不画。
        if (view.seated()) {
            Rect t = l.token();
            GuiMaterial.portrait(context, view.character(), t.x(), t.y(), t.w(),
                    view.condition() == Condition.DEAD ? 0.35f : 1f);
            GuiMaterial.hudPart(context, HudPart.TOK50_YOU, t.x(), t.y(), t.w(), t.h(), k);
            // 第一行：体力点（实心 = 还剩的）· 口渴那滴水 · 清醒的眼睛
            for (int i = 0; i < max; i++) {
                Rect r = l.pip(i);
                GuiMaterial.hudPart(context, i < view.health() ? HudPart.PIP_ON : HudPart.PIP_OFF,
                        r.x(), r.y(), r.w(), r.h(), k);
            }
            Rect drop = l.drop(max);
            // 口渴标记：没有是铜绿（样张的 .verd）；有就是朱砂 —— 它会在航海之后伤人（紧迫）
            GuiMaterial.hudIcon(context, HudPart.IC_DROP, drop.x(), drop.y(), drop.w(), drop.h(),
                    view.thirst() > 0 ? GuiLanguage.cinnabar() : GuiLanguage.verdigris(), k);
            Rect eye = l.eye(max);
            if (view.condition() == Condition.CONSCIOUS) {
                GuiMaterial.hudIcon(context, HudPart.IC_EYE, eye.x(), eye.y(), eye.w(), eye.h(), ink, k);
            } else {
                // 昏迷 · 死了：样张没画这两态，用管线那两枚
                GuiMaterial.icon(context, view.condition() == Condition.DEAD ? "dead" : "eye_closed",
                        eye.x(), eye.y(), eye.w(), GuiLanguage.cinnabar());
            }
        }
        // 第二行（公开）：四格阶段，此刻那一格垫一枚铜绿圆底；其余 42% 不透明（样张 .wheel .ph）
        int icon = l.len(HudLayout.ICON);
        for (int i = 0; i < HudLayout.PHASES; i++) {
            Rect ph = l.phase(i);
            boolean on = view.phase().ordinal() == i;
            if (on) {
                GuiMaterial.hudPart(context, HudPart.PHASE_ON, ph.x(), ph.y(), ph.w(), ph.h(), k);
            }
            GuiMaterial.hudIcon(context, PHASE_ICONS[i], ph.x() + (ph.w() - icon) / 2, ph.y() + (ph.h() - icon) / 2,
                    icon, icon, on ? GuiLanguage.Hud.PHASE_ON_ICON : GuiLanguage.Hud.alpha(ink, GuiLanguage.Hud.PHASE_OFF_ALPHA), k);
        }
        // 此刻是什么阶段，写出字来（用户 2026-10-07：「另外写出当前阶段」，样张 A：四格阶段右头）。
        // 那一截在窄窗口里放不下「行动阶段」四个字时只写「行动」（HudLayout.phaseWord）
        Rect pw = l.phaseWord();
        int wordPx = l.len(PHASE_WORD_PX);
        String name = phaseName(view.phase()).getString();
        String word = Text.translatable("heavyseas.hud.phase_now", name).getString();
        if (GuiText.widthPx(word, wordPx, true, 0) > pw.w()) {
            word = name;
        }
        GuiText.drawPx(context, word, pw.x(), pw.y() + (pw.h() - GuiText.linePxAt(wordPx, true)) / 2, pw.w(), wordPx,
                true, GuiLanguage.verdigris(), GuiText.Align.LEFT, 0);
        // 第三行：第几天（罗马数字）· 四只海鸥 · 手牌几张与开手牌的键
        Rect ro = l.roman();
        int romanPx = l.len(ROMAN_PX);
        GuiText.drawPx(context, roman(view.turn()), ro.x(), ro.y() + (ro.h() - GuiText.linePxAt(romanPx, true)) / 2,
                ro.w(), romanPx, true, ink, GuiText.Align.CENTER, 0);
        for (int i = 0; i < GameState.GULLS_TO_LAND; i++) {
            Rect g = l.gull(i);
            GuiMaterial.hudIcon(context, HudPart.IC_GULL, g.x(), g.y(), g.w(), g.h(),
                    i < view.gulls() ? ink : GuiLanguage.Hud.alpha(ink, GuiLanguage.Hud.GULL_OFF_ALPHA), k);
        }
        if (view.seated() && (!view.hand().isEmpty() || !view.love().isEmpty())) {
            String key = keyLabel(HeavySeasClient.handKey());
            double keyW = keyWidthDesign(l, key);
            Rect kr = l.handKey(keyW);
            drawKey(context, l, kr, key, false);
            String count = Integer.toString(view.hand().size());
            int countPx = l.len(COUNT_PX);
            double numW = GuiText.widthPx(count, countPx, true, 0) / l.k();
            Rect cr = l.handCount(keyW, numW);
            GuiText.drawPx(context, count, cr.x(), cr.y() + (cr.h() - GuiText.linePxAt(countPx, true)) / 2, cr.w() + 2,
                    countPx, true, ink, GuiText.Align.LEFT, 0);
            Rect hi = l.handIcon(keyW, numW);
            GuiMaterial.hudIcon(context, HudPart.IC_CARD, hi.x(), hi.y(), hi.w(), hi.h(), ink, l.k());
        }
    }

    /** 阶段字的字号（稿子像素）：比海鸥那一行的数字小一号，四个字才塞得进第四格右头那一截。 */
    private static final double PHASE_WORD_PX = 13;

    /** 阶段名。写成 switch 而不是拼键：拼出来的键 checkLangKeys 扫不到（与 GameFlow.phaseName 同一条）。 */
    private static Text phaseName(io.github.heavyseasmc.engine.state.Phase phase) {
        return Text.translatable(switch (phase) {
            case WEATHER -> "heavyseas.phase.weather";
            case PROVISION -> "heavyseas.phase.provision";
            case ACTION -> "heavyseas.phase.action";
            case NAVIGATION -> "heavyseas.phase.navigation";
        });
    }

    // ---------------------------------------------------------------- 图例（用户 2026-10-07 · 样张 A）

    /**
     * 状态牌下面那块图例：主画面上每一个不带字的图标是什么（用户 2026-10-07：「左侧角色卡下面并排列出图示，
     * 每个没有文字解释的图标都是做什么的，类似于 how to play 那种，可以按 H 收起，按键提示写在列表下方」）。
     *
     * <p>只写名字，不写规则（memory「界面不是说明书」）：「口渴」「舵手 · 划船堆」，不写口渴了会怎样。
     * 图标用主画面上那同一套贴图，颜色压到搪瓷底上读得出的那一档（与日志同一块搪瓷）。
     * 收起之后只剩一枚「H 图例」小签；展开与否记在客户端偏好里（{@link ClientPrefs#legendShown}）。
     */
    private static void drawLegend(DrawContext context, HudLayout l, HudView view) {
        double k = l.k();
        int ink = GuiLanguage.Hud.ENAMEL_LINE;
        int textPx = l.len(LEGEND_TEXT_PX);
        String legendKey = keyLabel(HeavySeasClient.legendKey());
        String title = Text.translatable("heavyseas.hud.legend.title").getString();
        if (!ClientPrefs.legendShown()) {
            double keyW = keyWidthDesign(l, legendKey);
            double w = 10 + keyW + 7 + GuiText.widthPx(title, textPx, false, 0) / k + 12;
            Rect tab = l.legendTab(w);
            GuiMaterial.hudPart(context, HudPart.ENAMEL, tab.x(), tab.y(), tab.w(), tab.h(), k);
            Rect kr = new Rect(tab.x() + l.len(10), tab.y() + (tab.h() - l.len(HudLayout.KEY)) / 2, l.len(keyW),
                    l.len(HudLayout.KEY));
            drawKey(context, l, kr, legendKey, false);
            int tx = kr.right() + l.len(7);
            GuiText.drawPx(context, title, tx, tab.y() + (tab.h() - GuiText.linePxAt(textPx, false)) / 2,
                    tab.right() - tx, textPx, false, ink, GuiText.Align.LEFT, 0);
            return;
        }
        Rect box = l.legend(HudLayout.LEGEND_ROWS);
        GuiMaterial.hudPart(context, HudPart.ENAMEL, box.x(), box.y(), box.w(), box.h(), k);
        Rect head = l.legendHead();
        int headPx = l.len(HudLayout.LOG_HEAD_TEXT);
        GuiText.drawPx(context, title, head.x(), head.y() + (head.h() - GuiText.linePxAt(headPx, false)) / 2, head.w(),
                headPx, false, GuiLanguage.Hud.alpha(ink, GuiLanguage.Hud.LOG_LABEL_ALPHA), GuiText.Align.LEFT,
                (int) Math.round(HEAD_SPACING * k));

        int verd = GuiLanguage.Hud.LOG_WEATHER;
        int cinn = GuiLanguage.Hud.LOG_CINNABAR;
        int gap = Math.max(1, l.len(3));
        // 第 0 行：体力 · 口渴
        legendItem(context, l, 0, 0, false, "heavyseas.hud.legend.hp", (x, y, s) -> {
            int d = Math.max(2, (int) Math.round(s * 0.56));
            int cy = y + (s - d) / 2;
            for (int i = 0; i < 3; i++) {
                GuiMaterial.hudPart(context, i < 2 ? HudPart.PIP_ON : HudPart.PIP_OFF, x + i * (d + gap), cy, d, d,
                        d / (double) HudPart.PIP_ON.w());
            }
            return 3 * d + 2 * gap;
        });
        legendItem(context, l, 0, 1, false, "heavyseas.hud.legend.thirst", (x, y, s) -> {
            GuiMaterial.hudIcon(context, HudPart.IC_DROP, x, y, s, s, verd, s / 24.0);
            GuiMaterial.hudIcon(context, HudPart.IC_DROP, x + s + gap, y, s, s, cinn, s / 24.0);
            return 2 * s + gap;
        });
        // 第 1 行：清醒 / 昏迷 · 手牌张数
        legendItem(context, l, 1, 0, false, "heavyseas.hud.legend.awake", (x, y, s) -> {
            GuiMaterial.hudIcon(context, HudPart.IC_EYE, x, y, s, s, ink, s / 24.0);
            GuiMaterial.icon(context, "eye_closed", x + s + gap, y, s, ink);
            return 2 * s + gap;
        });
        legendItem(context, l, 1, 1, false, "heavyseas.hud.legend.hand", (x, y, s) -> {
            GuiMaterial.hudIcon(context, HudPart.IC_CARD, x, y, s, s, ink, s / 24.0);
            int px = l.len(LEGEND_TEXT_PX);
            int w = GuiText.drawPx(context, "3", x + s + gap, y + (s - GuiText.linePxAt(px, true)) / 2, s * 2, px, true,
                    ink, GuiText.Align.LEFT, 0);
            return s + gap + w;
        });
        // 第 2 行：四格阶段（亮的那一格垫铜绿圆底）· 名字就是那四个阶段名
        String phases = String.join(" · ", phaseName(io.github.heavyseasmc.engine.state.Phase.WEATHER).getString(),
                phaseName(io.github.heavyseasmc.engine.state.Phase.PROVISION).getString(),
                phaseName(io.github.heavyseasmc.engine.state.Phase.ACTION).getString(),
                phaseName(io.github.heavyseasmc.engine.state.Phase.NAVIGATION).getString());
        legendItemText(context, l, 2, 0, true, phases, (x, y, s) -> {
            int cx = x;
            for (int i = 0; i < HudLayout.PHASES; i++) {
                if (i == 2) {
                    int d = s + l.len(2);
                    GuiMaterial.hudPart(context, HudPart.PHASE_ON, cx, y - l.len(1), d, d, d / (double) HudPart.PHASE_ON.w());
                    int ic = (int) Math.round(d * 0.66);
                    GuiMaterial.hudIcon(context, PHASE_ICONS[i], cx + (d - ic) / 2, y - l.len(1) + (d - ic) / 2, ic, ic,
                            GuiLanguage.Hud.PHASE_ON_ICON, ic / 24.0);
                    cx += d + gap;
                } else {
                    GuiMaterial.hudIcon(context, PHASE_ICONS[i], cx, y, s, s, ink, s / 24.0);
                    cx += s + gap;
                }
            }
            return cx - gap - x;
        });
        // 第 3 行：第几天 · 海鸥
        legendItem(context, l, 3, 0, false, "heavyseas.hud.legend.day", (x, y, s) -> {
            int px = l.len(16);
            return GuiText.drawPx(context, roman(3), x, y + (s - GuiText.linePxAt(px, true)) / 2, s * 2, px, true, ink,
                    GuiText.Align.LEFT, 0);
        });
        legendItemText(context, l, 3, 1, false,
                Text.translatable("heavyseas.hud.legend.gulls", GameState.GULLS_TO_LAND).getString(), (x, y, s) -> {
                    int w = (int) Math.round(s * 26 / 20.0);
                    GuiMaterial.hudIcon(context, HudPart.IC_GULL, x, y, w, s, ink, s / 20.0);
                    return w;
                });
        // 第 4 行：三种圈（你 · 正在等他 · 被抢 / 被换），用这一局里真有的人
        List<String> seats = view.seats();
        String you = view.seated() && !view.character().isEmpty() ? view.character() : seats.isEmpty() ? "" : seats.get(0);
        List<String> others = seats.stream().filter(id -> !id.equals(you)).toList();
        legendItem(context, l, 4, 0, true, "heavyseas.hud.legend.rings", (x, y, s) -> {
            HudPart[] rings = {HudPart.TOK40_YOU, HudPart.TOK40_ACT, HudPart.TOK40_CINN};
            String[] who = {you, others.isEmpty() ? you : others.get(0), others.size() < 2 ? you : others.get(1)};
            int d = s + l.len(2);
            int cx = x;
            for (int i = 0; i < 3; i++) {
                if (!who[i].isEmpty()) {
                    GuiMaterial.portrait(context, who[i], cx, y - l.len(1), d, 1f);
                }
                GuiMaterial.hudPart(context, rings[i], cx, y - l.len(1), d, d, d / (double) rings[i].w());
                cx += d + gap * 2;
            }
            return cx - gap * 2 - x;
        });
        // 第 5 行：舵手那枚牌 · 体力 / 体型的印章
        legendItem(context, l, 5, 0, false, "heavyseas.hud.legend.helm", (x, y, s) -> {
            int px = l.len(BADGE_PX);
            int numW = GuiText.widthPx("2", px, true, 0);
            int ic = (int) Math.round(s * 0.8);
            int bw = l.len(4) + ic + l.len(2) + numW + l.len(6);
            GuiMaterial.hudPart(context, HudPart.BADGE, x, y, bw, s, s / (double) HudPart.BADGE.h());
            GuiMaterial.hudIcon(context, HudPart.IC_HELM18, x + l.len(4), y + (s - ic) / 2, ic, ic, ink, ic / 18.0);
            GuiText.drawPx(context, "2", x + l.len(4) + ic + l.len(2), y + (s - GuiText.linePxAt(px, true)) / 2, numW + 2,
                    px, true, ink, GuiText.Align.LEFT, 0);
            return bw;
        });
        legendItem(context, l, 5, 1, false, "heavyseas.hud.legend.seal", (x, y, s) -> {
            int big = l.len(14);
            int small = l.len(11);
            int bw = GuiText.widthPx("3", big, true, 0);
            int sw = GuiText.widthPx("/4", small, false, 0);
            int pad = l.len(4);
            int w = pad + bw + sw + pad;
            int edge = Math.max(1, l.len(1.5));
            context.fill(x, y, x + w, y + s, cinn);
            context.fill(x + edge, y + edge, x + w - edge, y + s - edge, GuiLanguage.Hud.COUNT_FILL);
            GuiText.drawPx(context, "3", x + pad, y + (s - GuiText.linePxAt(big, true)) / 2, bw + 2, big, true, ink,
                    GuiText.Align.LEFT, 0);
            GuiText.drawPx(context, "/4", x + pad + bw, y + (s - GuiText.linePxAt(small, false)) / 2 + l.len(1), sw + 2,
                    small, false, ink, GuiText.Align.LEFT, 0);
            return w;
        });
        // 第 6 行：落海 / 死亡 · 轮到你（金签）
        legendItem(context, l, 6, 0, false, "heavyseas.hud.legend.gone", (x, y, s) -> {
            int ww = s;
            int wh = (int) Math.round(s * 11 / 25.0);
            GuiMaterial.hudIcon(context, HudPart.IC_WAVES40, x, y + (s - wh) / 2, ww, wh, ink, ww / 25.0);
            int cs = (int) Math.round(s * 0.62);
            GuiMaterial.hudIcon(context, HudPart.IC_CROSS40, x + ww + gap * 2, y + (s - cs) / 2, cs, cs, ink, cs / 11.0);
            return ww + gap * 2 + cs;
        });
        legendItem(context, l, 6, 1, false, "heavyseas.hud.legend.turn", (x, y, s) -> {
            String act = keyLabel(HeavySeasClient.actKey());
            int px = l.len(12);
            int ic = (int) Math.round(s * 0.75);
            int w = l.len(5) + ic + l.len(3) + GuiText.widthPx(act, px, true, 0) + l.len(6);
            GuiMaterial.hudPart(context, HudPart.RIBBON, x, y, w, s, s / (double) HudPart.RIBBON.h());
            GuiMaterial.hudIcon(context, HudPart.IC_BELL, x + l.len(5), y + (s - ic) / 2, ic, ic,
                    GuiLanguage.Hud.RIBBON_INK, ic / 24.0);
            GuiText.drawPx(context, act, x + l.len(5) + ic + l.len(3), y + (s - GuiText.linePxAt(px, true)) / 2,
                    w, px, true, GuiLanguage.Hud.RIBBON_INK, GuiText.Align.LEFT, 0);
            return w;
        });
        // 第 7 行：今天的天候（舷窗）· 航海日志
        legendItem(context, l, 7, 0, false, "heavyseas.hud.legend.weather", (x, y, s) -> {
            if (!view.weather().isEmpty()) {
                CardTexture.drawWeatherDisc(context, view.weather(), x, y, s);
            }
            GuiMaterial.hudPart(context, HudPart.MEDAL, x, y, s, s, s / (double) HudPart.MEDAL.w());
            return s;
        });
        legendItem(context, l, 7, 1, false, "heavyseas.hud.legend.log", (x, y, s) -> {
            GuiMaterial.hudIcon(context, HudPart.IC_BOOK, x, y, s, s, ink, s / 24.0);
            return s;
        });

        // 键位那一行（「按键提示写在列表下方」）：先一道细线，再 H 收起 · 手牌 · 行动 · 日志，都印实际绑定的键
        Rect keys = l.legendKeys(HudLayout.LEGEND_ROWS);
        int ly = keys.y() - l.len(8);
        context.fill(keys.x(), ly, keys.right(), ly + Math.max(1, l.len(1)), GuiLanguage.Hud.alpha(ink, 0.25f));
        int kx = keys.x();
        int keyPx = l.len(14);
        String[][] pairs = {
                {legendKey, "heavyseas.keys.close"},
                {keyLabel(HeavySeasClient.handKey()), "heavyseas.keys.hand"},
                {keyLabel(HeavySeasClient.actKey()), "heavyseas.keys.act"},
                {keyLabel(HeavySeasClient.logKey()), "heavyseas.keys.log"}};
        for (String[] p : pairs) {
            double keyW = keyWidthDesign(l, p[0]);
            Rect kr = new Rect(kx, keys.y(), l.len(keyW), keys.h());
            String label = Text.translatable(p[1]).getString();
            int lw = GuiText.widthPx(label, keyPx, false, 0);
            if (kr.right() + l.len(5) + lw > keys.right()) {
                break;                        // 放不下就不排了：小窗口里 H 收起永远在第一个
            }
            drawKey(context, l, kr, p[0], false);
            GuiText.drawPx(context, label, kr.right() + l.len(5), keys.y() + (keys.h() - GuiText.linePxAt(keyPx, false)) / 2,
                    lw + 2, keyPx, false, ink, GuiText.Align.LEFT, 0);
            kx = kr.right() + l.len(5) + lw + l.len(12);
        }
    }

    /** 图例里名字的字号（稿子像素）。 */
    private static final double LEGEND_TEXT_PX = 15;

    /** 一格里先画图标、返回它们占了多宽（物理像素）。{@code s} 是图标的边长。 */
    @FunctionalInterface
    private interface LegendIcons {
        int draw(int x, int y, int s);
    }

    /** 名字取自 lang 的一格；键写全（拼出来的键 checkLangKeys 扫不到）。 */
    private static void legendItem(DrawContext context, HudLayout l, int row, int col, boolean wide, String key,
                                   LegendIcons icons) {
        legendItemText(context, l, row, col, wide, Text.translatable(key).getString(), icons);
    }

    private static void legendItemText(DrawContext context, HudLayout l, int row, int col, boolean wide, String label,
                                       LegendIcons icons) {
        Rect cell = l.legendCell(row, col, wide);
        int s = cell.h();
        int w = icons.draw(cell.x(), cell.y(), s);
        int tx = cell.x() + w + l.len(6);
        int px = l.len(LEGEND_TEXT_PX);
        GuiText.drawPx(context, label, tx, cell.y() + (s - GuiText.linePxAt(px, false)) / 2, Math.max(1, cell.right() - tx),
                px, false, GuiLanguage.Hud.ENAMEL_LINE, GuiText.Align.LEFT, 0);
    }

    // ---------------------------------------------------------------- 金签（样张 .ribbon）

    /**
     * 轮到你时挂下来的那枚金签：铃 · 键帽，吊在状态牌底下。「轮到你 = 一件金色的物件落下来，不是描边发光」（样张页）。
     * 换内容时重新「发」一次（从上面落下来、由淡到实）。
     */
    private static void drawRibbon(DrawContext context, HudLayout l, HudView view, long now) {
        Cue cue = cue(view, now);
        if (cue == null) {
            cueShown = "";
            return;
        }
        if (!cue.id().equals(cueShown)) {
            cueShown = cue.id();
            cueAt = now;
        }
        // 「发」：从上面一点落到位（ADR-0018 §7.2）
        float p = GuiLanguage.deal(now, cueAt, 0);
        int dy = Math.round((1f - p) * l.len(GuiLanguage.DEAL_RISE * HudLayout.DESIGN_GUI_SCALE));
        MatrixStack matrices = context.getMatrices();
        matrices.push();
        matrices.translate(0, dy, 0);
        Rect rb = l.ribbon();
        String key = keyLabel(HeavySeasClient.actKey());
        if (cue.seconds() == null) {
            GuiMaterial.hudPart(context, HudPart.RIBBON, rb.x(), rb.y(), rb.w(), rb.h(), l.k());
            Rect bell = l.ribbonBell();
            GuiMaterial.hudIcon(context, HudPart.IC_BELL, bell.x(), bell.y(), bell.w(), bell.h(), GuiLanguage.Hud.RIBBON_INK, l.k());
            drawKey(context, l, l.ribbonKey(), key, true);
        } else {
            // 找人那几秒：金签往右加宽，铃与键帽之间印秒数（样张没画这一态；吊绳那一段不拉）
            int numPx = l.len(COUNT_PX);
            int numW = GuiText.widthPx(cue.seconds(), numPx, true, 0);
            int gap = l.len(7);
            int extra = numW + gap;
            GuiMaterial.ribbonWide(context, rb.x(), rb.y(), rb.w() + extra, rb.h(), l.k());
            Rect bell = l.ribbonBell();
            Rect kr = l.ribbonKey();
            GuiMaterial.hudIcon(context, HudPart.IC_BELL, bell.x(), bell.y(), bell.w(), bell.h(), GuiLanguage.Hud.RIBBON_INK, l.k());
            GuiText.drawPx(context, cue.seconds(), bell.right() + gap,
                    rb.y() + (rb.h() - GuiText.linePxAt(numPx, true)) / 2, numW + 2, numPx, true,
                    cue.color(), GuiText.Align.LEFT, 0);
            drawKey(context, l, new Rect(kr.x() + extra, kr.y(), kr.w(), kr.h()), key, true);
        }
        matrices.pop();
    }

    /** 金签的一态：{@code seconds} 非空时印秒数（只有找人那一态）。 */
    private record Cue(String id, String seconds, int color) {
    }

    /** 金签挂不挂：一次只有一件事要你做。 */
    private static Cue cue(HudView view, long now) {
        if (!view.seated()) {
            return null;
        }
        if (view.endgame().active()) {
            return new Cue("endgame", null, 0);
        }
        if (view.myDesignating()) {
            // 举着拳头找人：有倒计时，到点就退回 —— 紧迫，秒数用朱砂（ADR-0025）
            long left = Math.max(0L, view.designateUntil() - now);
            return new Cue("designate", Long.toString((left + 999) / 1000), GuiLanguage.Hud.LOG_CINNABAR);
        }
        if (view.myContestChoice()) {
            return new Cue("contest", null, 0);
        }
        if (view.myThirstChoice()) {
            return new Cue("thirst", null, 0);
        }
        if (view.myWaterDonation()) {
            return new Cue("donate", null, 0);
        }
        if (overboardChoice()) {
            // 落海那一窗：收起之后按 G 回来（HeavySeasClient.pollTurn 认 G）—— 原先这一窗没有金签，收起就找不回来了
            return new Cue("overboard", null, 0);
        }
        if (view.myRowPending()) {
            return new Cue("row", null, 0);
        }
        if (view.myTurnToAct()) {
            return new Cue("act", null, 0);
        }
        return null;
    }

    /** 落海那一窗开着、而我手上有牌可出（与 {@code HeavySeasClient.pollTurn} 弹那一面同一个判据）。 */
    private static boolean overboardChoice() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return false;
        }
        var table = GameComponents.of(client.world).tableView();
        return table.overboardOpen() && !table.plays().isEmpty();
    }

    // ---------------------------------------------------------------- 座位轨（样张 .rail）

    /**
     * 这一局的座位：金圈 = 你，铜绿圈 = 正轮到的那一位，朱砂圈 = 被抢 / 被换的那一位，
     * 舵手身上一枚舵轮（带划船堆张数）；每一座的体力印章 · 昏迷 · 移出 · 死亡照 D1 (a)（{@link SeatMarks}，ADR-0048）。
     */
    private static void drawRail(DrawContext context, HudLayout l, HudView view) {
        List<String> seats = view.seats();
        if (seats.isEmpty()) {
            return;
        }
        double k = l.k();
        Rect rail = l.rail(seats.size());
        GuiMaterial.hudPart(context, HudPart.RAIL, rail.x(), rail.y(), rail.w(), rail.h(), k);
        String waitingOn = waitingOn(view);
        String target = view.contest().active() ? view.contest().target() : "";
        boolean sea = !view.endgame().active()
                && (view.phase() == Phase.ACTION || view.phase() == Phase.NAVIGATION);
        for (int i = 0; i < seats.size(); i++) {
            String id = seats.get(i);
            Rect t = l.seatToken(i);
            var seat = SeatMarks.seat(id);
            GuiMaterial.portrait(context, id, t.x(), t.y(), t.w(), 1f);
            seat.ifPresent(s -> SeatMarks.shade(context, t, SeatMarks.Kit.TOKEN40, s, k));
            HudPart ring = id.equals(view.character()) && view.seated() ? HudPart.TOK40_YOU
                    : id.equals(target) ? HudPart.TOK40_CINN
                    : id.equals(waitingOn) ? HudPart.TOK40_ACT : HudPart.TOK40_PLAIN;
            if (ring == HudPart.TOK40_ACT) {
                GuiMaterial.breathingRing(context, HudPart.TOK40_PLAIN, ring, t.x(), t.y(), t.w(), t.h(), k);
            } else {
                GuiMaterial.hudPart(context, ring, t.x(), t.y(), t.w(), t.h(), k);
            }
            boolean helm = sea && id.equals(view.sea().helmsman());
            seat.ifPresent(s -> SeatMarks.marks(context, t, s, k, helm));
            if (helm) {
                // 舵轮挂在这一座右下，后面几座随后画、按样张的先后压在它上面
                String n = Integer.toString(view.sea().rowStack());
                int numPx = l.len(BADGE_PX);
                double numW = GuiText.widthPx(n, numPx, true, 0) / k;
                Rect b = l.helmBadge(i, numW);
                GuiMaterial.hudPart(context, HudPart.BADGE, b.x(), b.y(), b.w(), b.h(), k);
                Rect bi = l.helmBadgeIcon(i, numW);
                GuiMaterial.hudIcon(context, HudPart.IC_HELM18, bi.x(), bi.y(), bi.w(), bi.h(), GuiLanguage.Hud.ENAMEL_LINE, k);
                Rect bt = l.helmBadgeText(i, numW);
                GuiText.drawPx(context, n, bt.x(), bt.y() + (bt.h() - GuiText.linePxAt(numPx, true)) / 2, bt.w() + 2,
                        numPx, true, GuiLanguage.Hud.ENAMEL_LINE, GuiText.Align.LEFT, 0);
            }
        }
    }

    /** 此刻全船在等谁：口渴轮到的那一位，否则是正轮到行动的那一位。 */
    private static String waitingOn(HudView view) {
        if (view.thirstPrompt().active()) {
            return view.thirstPrompt().who();
        }
        if (view.contest().active()) {
            return view.contest().attacker();
        }
        return view.actor();
    }

    // ---------------------------------------------------------------- 右上：收着（.dock）与展开（.drawer）

    /**
     * 右栏收着的时候只剩一扇天候舷窗、一枚日志页签（带未读数）和日志键（样张 b-1）；有新播报时换成展开的那一列
     * 自己露出几秒再收回，按日志键钉住（样张 b-2 · ADR-0037 §7.1 第 4 条）。两态不同时画 —— 滑动那几帧除外。
     */
    private static void drawRight(DrawContext context, HudLayout l, HudView view, long now, int scale) {
        float open = SidebarReveal.openness(now);
        if (open >= 1f) {
            seenArrived = SidebarReveal.arrived();
        }
        if (open < 1f) {
            drawDock(context, l, view);
        }
        if (open > 0f) {
            int slide = Math.round((1f - open) * (l.len(HudLayout.DRAWER_W + HudLayout.DOCK_RIGHT) + l.len(20)));
            MatrixStack matrices = context.getMatrices();
            matrices.push();
            matrices.translate(slide, 0, 0);
            drawDrawer(context, l, view, now, scale, LOG_ENTRIES);
            matrices.pop();
        }
    }

    private static void drawDock(DrawContext context, HudLayout l, HudView view) {
        double k = l.k();
        Rect medal = l.medal();
        CardTexture.drawWeatherDisc(context, view.weather(), medal.x(), medal.y(), medal.w());
        GuiMaterial.hudPart(context, HudPart.MEDAL, medal.x(), medal.y(), medal.w(), medal.h(), k);
        Rect tab = l.logTab();
        GuiMaterial.hudPart(context, HudPart.LOGTAB, tab.x(), tab.y(), tab.w(), tab.h(), k);
        int icon = l.len(HudLayout.ICON);
        GuiMaterial.hudIcon(context, HudPart.IC_BOOK, tab.centerX() - icon / 2, tab.centerY() - icon / 2, icon, icon,
                GuiLanguage.ink(), k);
        int unread = Math.max(0, SidebarReveal.arrived() - seenArrived);
        if (unread > 0) {
            String n = Integer.toString(Math.min(unread, 99));
            int px = l.len(UNREAD_PX);
            double w = Math.max(HudLayout.COUNT, GuiText.widthPx(n, px, true, 0) / k + 8);
            Rect c = l.logCount(w);
            GuiMaterial.hudPart(context, HudPart.COUNT, c.x(), c.y(), c.w(), c.h(), k);
            GuiText.drawPx(context, n, c.x(), c.y() + (c.h() - GuiText.linePxAt(px, true)) / 2, c.w(), px, true,
                    GuiLanguage.Hud.ENAMEL_LINE, GuiText.Align.CENTER, 0);
        }
        String key = keyLabel(HeavySeasClient.logKey());
        drawKey(context, l, l.logKey(keyWidthDesign(l, key)), key, false);
    }

    /** 展开的那一列：天候卡 · 效果说明签（带尖角）· 航海日志。竖向在稿子像素里往下累加，最后才换成物理像素。 */
    private static void drawDrawer(DrawContext context, HudLayout l, HudView view, long now, int scale, int maxEntries) {
        double k = l.k();
        double y = HudLayout.DOCK_Y;
        String weather = view.weather();
        if (!weather.isEmpty() && !SidebarReveal.expanded()) {   // 拉伸时收起天候卡与说明签，日志占满整条右栏
            Rect card = l.drawerCard();
            GuiMaterial.hudPart(context, HudPart.CARD_SHADOW, card.x(), card.y(), card.w(), card.h(), k);
            CardTexture.drawWeatherPx(context, weather, card.x(), card.y(), card.w(), card.h());
            y += HudLayout.DRAWER_CARD_H;
            // 效果说明签：样张 .tip（尖角指着卡的横向正中）
            String effect = Text.translatable("heavyseas.weather.effect." + weather).getString();
            int textPx = l.len(HudLayout.TIP_TEXT);
            int box = l.len(HudLayout.DRAWER_W - 2 * HudLayout.TIP_PAD_X);
            int lines = GuiText.paragraphLines(effect, box, textPx, false, TIP_LINES);
            double h = HudLayout.TIP_PAD_T + lines * HudLayout.TIP_LINE + HudLayout.TIP_PAD_B;
            Rect tip = l.drawerBlock(y, HudLayout.TIP_OVERLAP, h);
            GuiMaterial.hudPart(context, HudPart.ENAMEL, tip.x(), tip.y(), tip.w(), tip.h(), k);
            int ptr = l.len(HudLayout.TIP_POINTER);
            GuiMaterial.hudPart(context, HudPart.TIP_POINTER,
                    l.xRight(HudLayout.drawerCenterDesign() - HudLayout.TIP_POINTER / 2),
                    tip.y() + l.len(HudLayout.TIP_POINTER_TOP), ptr, ptr, k);
            GuiText.paragraphPx(context, effect, tip.x() + l.len(HudLayout.TIP_PAD_X), tip.y() + l.len(HudLayout.TIP_PAD_T),
                    box, textPx, false, GuiLanguage.Hud.ENAMEL_LINE, TIP_LINES, l.len(HudLayout.TIP_LINE));
            y += HudLayout.DRAWER_GAP - HudLayout.TIP_OVERLAP + h;
        }
        // 「只有你看得到」的爱恨跟着侧栏一起出来：自己滑出来（来了新播报）与按 L 钉住都有
        // （用户 2026-10-07：「爱恨提示不能只在手牌页面，L 菜单也放一个」，随后「爱恨随侧边滑出，不是按键触发」——
        // 原先只在钉住时给，L 就成了「看爱恨」的键，与它「钉住日志」的本意撞在一起）。
        // （拉伸时天候卡与说明签在上面已经跳过：日志占满整条右栏）
        if (view.seated() && !view.love().isEmpty()) {
            y += drawSecret(context, l, view, y) + HudLayout.DRAWER_GAP;
        }
        drawLog(context, l, view, now, y, scale, SidebarReveal.expanded() ? EXPANDED_LOG_ENTRIES
                : SidebarReveal.pinned() ? PINNED_LOG_ENTRIES : maxEntries);
    }

    /**
     * 「只有你看得到」的爱与恨（与手牌一面右上那一块同一个画法，样张 .secret）：眼睛 + 小标题；爱（朱砂的心）· 恨（碎心）各一行，
     * 头像 38 + 名字。
     *
     * @return 这一块占了多高（稿子像素），调用方往下排日志
     */
    private static double drawSecret(DrawContext context, HudLayout l, HudView view, double aboveBottom) {
        double k = l.k();
        double headDesign = 13 * 1.75;
        double rowDesign = 44;
        double hDesign = 12 + headDesign + 8 + 2 * rowDesign + 14;
        Rect box = l.drawerBlock(aboveBottom, 0, hDesign);
        int ink = GuiLanguage.Hud.ENAMEL_LINE;
        GuiMaterial.hudPart(context, HudPart.ENAMEL, box.x(), box.y(), box.w(), box.h(), k);
        int tx = box.x() + l.len(16);
        int y = box.y() + l.len(12);
        int headH = l.len(headDesign);
        int icon = l.len(24);
        GuiMaterial.hudIcon(context, HudPart.IC_EYE, tx, y + (headH - icon) / 2, icon, icon,
                GuiLanguage.Hud.alpha(ink, 0.8f), k);
        int headPx = l.len(13);
        GuiText.drawPx(context, Text.translatable("heavyseas.hand.secret").getString(), tx + icon + l.len(8),
                y + (headH - GuiText.linePxAt(headPx, false)) / 2, box.w() - icon - l.len(40), headPx, false,
                GuiLanguage.Hud.alpha(ink, 0.8f), GuiText.Align.LEFT, l.len(3));
        y += headH + l.len(8);
        String[] who = {view.love(), view.hate()};
        HudPart[] mark = {HudPart.IC_HEART, HudPart.IC_HATE};
        int[] color = {GuiLanguage.Hud.LOG_CINNABAR, ink};
        int rowH = l.len(rowDesign);
        int tok = l.len(38);
        int namePx = l.len(17);
        // 名字前写「爱」「恨」两个字（用户 2026-10-07：「爱恨的显示很反直觉」）
        String[] word = {Text.translatable("heavyseas.hand.love").getString(), Text.translatable("heavyseas.hand.hate").getString()};
        int wordPx = l.len(17);
        int wordW = Math.max(GuiText.widthPx(word[0], wordPx, true, 0), GuiText.widthPx(word[1], wordPx, true, 0));
        for (int i = 0; i < 2; i++) {
            int cy = y + rowH / 2;
            GuiText.drawPx(context, word[i], tx, cy - GuiText.linePxAt(wordPx, true) / 2, wordW + 2, wordPx, true,
                    color[i], GuiText.Align.LEFT, 0);
            int ix = tx + wordW + l.len(6);
            GuiMaterial.hudIcon(context, mark[i], ix, cy - icon / 2, icon, icon, color[i], k);
            int tokX = ix + icon + l.len(10);
            GuiMaterial.portrait(context, who[i], tokX, cy - tok / 2, tok, 1f);
            GuiMaterial.hudPart(context, HudPart.TOK38_PLAIN, tokX, cy - tok / 2, tok, tok, k);
            GuiText.drawPx(context, Text.translatable("heavyseas.character." + who[i]).getString(), tokX + tok + l.len(10),
                    cy - GuiText.linePxAt(namePx, false) / 2, box.right() - (tokX + tok + l.len(10)) - l.len(12), namePx,
                    false, ink, GuiText.Align.LEFT, 0);
            y += rowH;
        }
        return hDesign;
    }

    /** 航海日志（样张 b-2 的 .log）：栏头「航海日志 · 第几天」+ 图钉 + 日志键，下面最新的几条，最底下一道自动收回的短横。 */
    private static void drawLog(DrawContext context, HudLayout l, HudView view, long now, double aboveBottom, int scale,
                                int maxEntries) {
        // 这一局客户端自己记下的那一本（往回翻要它：投影只留最新八条）；还没记下任何一条时退回投影
        List<Text> notes = SidebarReveal.history().isEmpty() ? view.notifications() : SidebarReveal.history();
        if (notes.isEmpty()) {
            return;
        }
        int skip = Math.min(SidebarReveal.scroll(), notes.size() - 1);
        double k = l.k();
        int textPx = l.len(HudLayout.LOG_TEXT);
        int labelPx = l.len(HudLayout.LOG_HEAD_TEXT);
        int labelW = l.len(HudLayout.LOG_LABEL_W);
        int textX0 = l.len(HudLayout.LOG_PAD_X + HudLayout.LOG_LABEL_W + HudLayout.LOG_LABEL_GAP);
        int textBox = l.len(HudLayout.DRAWER_W - 2 * HudLayout.LOG_PAD_X - HudLayout.LOG_LABEL_W - HudLayout.LOG_LABEL_GAP);
        int lineStep = l.len(HudLayout.LOG_LINE);
        boolean autoclose = !SidebarReveal.pinned();
        // 最新的在最上；最近一批标「刚刚」、不压淡，更早的标类别、压淡（样张 .log .old）
        int batch = Math.max(1, SidebarReveal.lastBatch());
        List<Entry> entries = new ArrayList<>();
        for (int i = notes.size() - 1 - skip; i >= 0 && entries.size() < maxEntries; i--) {
            Text note = notes.get(i);
            int age = notes.size() - 1 - i;
            boolean fresh = age < batch;
            // 最新那一条标「刚刚」；其余（同一批的也是）标类别 —— 原先同一批的几条左边空着，
            // 一眼分不出哪条是打架、哪条是口渴（用户 2026-10-07：「日志看起来很费劲」）
            String cat = age == 0 ? "" : category(note);
            String label = age == 0 ? Text.translatable("heavyseas.hud.log.just_now").getString()
                    : cat.isEmpty() ? "" : Text.translatable("heavyseas.hud.log.cat." + cat).getString();
            int lines = GuiText.paragraphLines(note.getString(), textBox, textPx, false, LOG_LINES_PER_ENTRY);
            entries.add(new Entry(note, label, cat, fresh, lines));
        }
        // 放不下就从最旧的那条往回减：日志底边不许压到热栏（热栏 22 个 GUI 单位，贴底）
        int hotbarTop = hotbarHidden() ? l.height() : l.height() - 22 * scale;   // 雾海里热栏不画：那一截让给日志
        // 往回翻过就停在那儿（用户 2026-10-07：「使用滚轮调整日志后应该暂停滚动 可以按键恢复」）：底下多一行说怎么回到最新
        boolean paused = SidebarReveal.paused();
        double fixed = HudLayout.LOG_PAD_T + HudLayout.LOG_HEAD_H + HudLayout.LOG_HEAD_GAP + HudLayout.LOG_PAD_B
                + (autoclose ? HudLayout.AUTOCLOSE_GAP + HudLayout.AUTOCLOSE_H : 0)
                + (paused ? HudLayout.LOG_LINE : 0);
        double h;
        while (true) {
            int lines = entries.stream().mapToInt(Entry::lines).sum();
            h = fixed + lines * HudLayout.LOG_LINE;
            Rect probe = l.drawerBlock(aboveBottom, 0, h);
            if (probe.bottom() + l.len(8) <= hotbarTop || entries.size() <= 1) {
                break;
            }
            entries.remove(entries.size() - 1);
        }
        Rect log = l.drawerBlock(aboveBottom, 0, h);
        GuiMaterial.hudPart(context, HudPart.ENAMEL, log.x(), log.y(), log.w(), log.h(), k);
        int line = GuiLanguage.Hud.ENAMEL_LINE;
        int x0 = log.x() + l.len(HudLayout.LOG_PAD_X);
        int headTop = log.y() + l.len(HudLayout.LOG_PAD_T);
        int headH = l.len(HudLayout.LOG_HEAD_H);
        // 栏头：左「航海日志 · 第几天」（字距 4），右图钉 + 日志键
        String title = Text.translatable("heavyseas.hud.log_title",
                Text.translatableWithFallback("heavyseas.numeral." + view.turn(), Integer.toString(view.turn()))).getString();
        GuiText.drawPx(context, title, x0, headTop + (headH - GuiText.linePxAt(labelPx, false)) / 2,
                log.w() - 2 * l.len(HudLayout.LOG_PAD_X), labelPx, false, line, GuiText.Align.LEFT,
                (int) Math.round(HEAD_SPACING * k));
        String key = keyLabel(HeavySeasClient.logKey());
        double keyW = keyWidthDesign(l, key);
        int kw = l.len(keyW);
        Rect kr = new Rect(log.right() - l.len(HudLayout.LOG_PAD_X) - kw, headTop, kw, l.len(HudLayout.KEY));
        drawKey(context, l, kr, key, false);
        int icon = l.len(HudLayout.ICON);
        GuiMaterial.hudIcon(context, HudPart.IC_PIN, kr.x() - l.len(5) - icon, headTop + (headH - icon) / 2, icon, icon,
                line, k);
        int y = headTop + headH + l.len(HudLayout.LOG_HEAD_GAP);
        for (Entry e : entries) {
            float old = e.fresh() ? 1f : GuiLanguage.Hud.LOG_OLD_ALPHA;
            if (!e.label().isEmpty()) {
                // 类别字各类一色（用户 2026-10-07：「日志看起来很费劲」）；「刚刚」仍是次墨
                int labelInk = e.category().isEmpty() ? line : categoryInk(e.category());
                float a = (e.category().isEmpty() ? GuiLanguage.Hud.LOG_LABEL_ALPHA : 1f) * old;
                // 样张 .log .l i：13px、行高 1.75、padding-top 2，与正文顶对齐
                // 右沿对齐在那一格的右边；框往左伸进内边距 —— 小窗口里字号有地板（12 px），
                // 「刚刚」两个字比按比例缩下来的 34 宽，不伸就被截成「…」（2026-09-30 854×480 实拍）
                int pad = l.len(HudLayout.LOG_PAD_X) - 1;
                GuiText.drawPx(context, e.label(), x0 - pad, y + l.len(2)
                                + (l.len(HudLayout.LOG_HEAD_TEXT * 1.75) - GuiText.linePxAt(labelPx, false)) / 2,
                        labelW + pad, labelPx, false, GuiLanguage.Hud.alpha(labelInk, a), GuiText.Align.RIGHT, 0);
            }
            LogInk.Runs runs = LogInk.runs(e.note());
            int[] colors = new int[runs.kinds().length];
            for (int c = 0; c < colors.length; c++) {
                colors[c] = GuiLanguage.Hud.alpha(runInk(runs.kinds()[c], runs.styledRgb()[c]), old);
            }
            GuiText.paragraphRunsPx(context, runs.text(), colors, log.x() + textX0, y, textBox, textPx, false,
                    LOG_LINES_PER_ENTRY, lineStep);
            y += e.lines() * lineStep;
        }
        // 钉住而且一屏放不下这一局的全部：右边一道细的滚动条（最新在上，往回翻 = 滑块往下走），看得出还有、翻到了哪儿
        if (SidebarReveal.pinned() && notes.size() > entries.size()) {
            int trackTop = headTop + headH + l.len(HudLayout.LOG_HEAD_GAP);
            int trackH = Math.max(1, y - trackTop);
            int barW = Math.max(1, l.len(3));
            int barX = log.right() - l.len(7);
            context.fill(barX, trackTop, barX + barW, trackTop + trackH, GuiLanguage.Hud.alpha(line, 0.15f));
            int hidden = notes.size() - entries.size();
            int thumbH = Math.max(l.len(12), trackH * entries.size() / notes.size());
            int thumbTop = trackTop + (int) ((long) (trackH - thumbH) * Math.min(skip, hidden) / hidden);
            context.fill(barX, thumbTop, barX + barW, Math.min(trackTop + trackH, thumbTop + thumbH),
                    GuiLanguage.Hud.alpha(line, 0.6f));
        }
        if (paused) {
            String latest = HeavySeasClient.logLatestKey() == null ? "End"
                    : HeavySeasClient.logLatestKey().getBoundKeyLocalizedText().getString();
            GuiText.drawPx(context, Text.translatable("heavyseas.hud.log.paused", latest).getString(), x0, y,
                    log.w() - 2 * l.len(HudLayout.LOG_PAD_X), labelPx, false,
                    GuiLanguage.Hud.alpha(line, GuiLanguage.Hud.LOG_LABEL_ALPHA), GuiText.Align.LEFT, 0);
            y += lineStep;
        }
        if (autoclose) {
            int barW = Math.round(l.len(HudLayout.DRAWER_W - 2 * HudLayout.LOG_PAD_X) * SidebarReveal.remaining(now));
            int by = y + l.len(HudLayout.AUTOCLOSE_GAP);
            context.fill(x0, by, x0 + barW, by + Math.max(1, l.len(HudLayout.AUTOCLOSE_H)), GuiLanguage.Hud.INK2);
        }
    }

    private record Entry(Text note, String label, String category, boolean fresh, int lines) {
    }

    /**
     * 播报印在搪瓷上，一个字一个字地定色（{@link LogInk}）：人名 · 物资牌 · 天候各一色；
     * 服务端标了红（战斗结算）的用日志里的朱砂，其余一律深墨（样张 .log 与 .cinn）。
     */
    private static int runInk(LogInk.Kind kind, int styledRgb) {
        return switch (kind) {
            case NAME -> GuiLanguage.Hud.LOG_NAME;
            case CARD -> GuiLanguage.Hud.LOG_CARD;
            case WEATHER -> GuiLanguage.Hud.LOG_WEATHER;
            case STYLED -> GuiLanguage.semantic(styledRgb) == GuiLanguage.cinnabar()
                    ? GuiLanguage.Hud.LOG_CINNABAR : GuiLanguage.Hud.ENAMEL_LINE;
            case PLAIN -> GuiLanguage.Hud.ENAMEL_LINE;
        };
    }

    /** 左边那一格类别字的颜色：与正文里同类的字同色系。 */
    private static int categoryInk(String category) {
        return switch (category) {
            case "weather", "navigation" -> GuiLanguage.Hud.LOG_WEATHER;
            case "provision" -> GuiLanguage.Hud.LOG_CARD;
            case "thirst" -> GuiLanguage.Hud.LOG_CAT_THIRST;
            case "contest" -> GuiLanguage.Hud.LOG_CAT_CONTEST;
            case "action" -> GuiLanguage.Hud.LOG_CAT_ACTION;
            default -> GuiLanguage.Hud.ENAMEL_LINE;
        };
    }

    /**
     * 一条播报属于哪一类（样张 b-2 左边那一格：物资 · 天候……），返回类别 id（lang 里 {@code hud.log.cat.*} 的尾巴）：
     * 按服务端文本键的前缀归，归不上的返回空串、不标。键是服务端 {@code GameFlow.broadcast} 发的那一串，客户端收到的仍是可翻译文本。
     */
    private static final String NAMESPACE = "heavyseas.";

    private static String category(Text note) {
        if (!(note.getContent() instanceof TranslatableTextContent t)) {
            return "";
        }
        // 去掉命名空间再比前缀：带着命名空间写的前缀读起来像一个 lang 键，checkLangKeys 会当成缺键（2026-09-30 实测）
        String key = t.getKey().startsWith(NAMESPACE) ? t.getKey().substring(NAMESPACE.length()) : "";
        String cat;
        if (key.startsWith("game.weather")) {
            cat = "weather";
        } else if (key.startsWith("game.thirst")) {
            cat = "thirst";
        } else if (key.startsWith("game.provision") || key.startsWith("game.card_played")) {
            cat = "provision";
        } else if (key.startsWith("game.row") || key.startsWith("game.helmsman") || key.startsWith("game.compass")
                || key.startsWith("game.overboard") || key.startsWith("game.top_card") || key.startsWith("game.removed")
                || key.startsWith("row.") || key.startsWith("overboard.") || key.startsWith("command.rowed")) {
            cat = "navigation";
        } else if (key.startsWith("contest.") || key.startsWith("command.swapped") || key.startsWith("command.fought")
                || key.startsWith("command.cannot_fight") || key.startsWith("designate.")) {
            cat = "contest";
        } else if (key.startsWith("game.your_turn") || key.startsWith("game.seat") || key.startsWith("command.")
                || key.startsWith("action.")) {
            // 其余的 command.*：亮出 · 赠送 · 喝 · 治 · 撑伞 · 分食 · 信号枪 · 什么也没做 —— 都是谁在自己那一手里做了什么
            cat = "action";
        } else if (key.startsWith("endgame.") || key.startsWith("game.over") || key.startsWith("game.final_line")) {
            cat = "endgame";
        } else {
            return "";
        }
        return cat;
    }

    // ---------------------------------------------------------------- 键帽

    /** 键帽宽（稿子像素）：样张 {@code .key { min-width: 26px; padding: 0 7px }}。 */
    private static double keyWidthDesign(HudLayout l, String key) {
        return Math.max(HudLayout.KEY, GuiText.widthPx(key, l.len(KEY_PX), true, 0) / l.k() + 2 * HudLayout.KEY_PAD_X);
    }

    /** 一枚键帽：浅的（状态牌 · 日志）或金签上那种深底金字的。字 600 14px。 */
    private static void drawKey(DrawContext context, HudLayout l, Rect r, String key, boolean onRibbon) {
        GuiMaterial.hudPart(context, onRibbon ? HudPart.RIBBON_KEY : HudPart.KEY, r.x(), r.y(), r.w(), r.h(), l.k());
        int px = l.len(KEY_PX);
        GuiText.drawPx(context, key, r.x(), r.y() + (r.h() - GuiText.linePxAt(px, true)) / 2, r.w(), px, true,
                onRibbon ? GuiLanguage.Hud.RIBBON_TOP : GuiLanguage.Hud.ENAMEL_LINE, GuiText.Align.CENTER, 0);
    }

    // ---------------------------------------------------------------- 对局界面里的日志（最后一层）

    /**
     * 对局界面最后一层的通知侧栏。只由 {@link GameScreenSidebar} 的 afterRender 调用；
     * 放回普通 HUD 回调会被 Screen 的底色盖住。
     */
    static void renderSidebar(DrawContext context, MinecraftClient client) {
        if (client.world == null || client.player == null || client.options.hudHidden
                || !(client.currentScreen instanceof GameScreen)) {
            return;
        }
        HudView view = GameComponents.of(client.world).hudView();
        if (!view.active()) {
            SidebarReveal.forget();
            return;
        }
        long now = System.currentTimeMillis();
        SidebarReveal.observe(view.notifications(), now);
        // 纸板开着时<b>一律不自己滑出来</b>：它会压住纸板右上角与座位轨末尾那两座（用户 2026-10-01 定，ADR-0050 §5 ——
        // 把 2026-09-30 计分面板那一条例外推广到每一面）。播报照数（observe 在上面，未读数挂在主画面的日志页签上，
        // 纸板一收就看得见）；按 L 钉住照旧给整列。原先自己滑出来的那一支（最新两条、自动收回）整支删掉，
        // 不留开关 —— 从结构上不可能再压上来。
        if (!SidebarReveal.pinned()) {
            return;
        }
        // ❗**盖在舞台上**，不占版面：舞台一个像素都不动，否则每钉一次整屏就要重排一次（§7.13）。
        // 与主画面展开那一列同一个画法（样张 b-2：天候卡 · 说明签 · 日志）。
        int scale = guiScale(client);
        HudLayout layout = HudLayout.of(client.getWindow().getFramebufferWidth(), client.getWindow().getFramebufferHeight());
        MatrixStack matrices = context.getMatrices();
        matrices.push();
        matrices.scale(1f / scale, 1f / scale, 1f);
        drawDrawer(context, layout, view, now, scale, LOG_ENTRIES);
        matrices.pop();
    }

    /**
     * 主画面（没开界面）里的滚轮（{@code MouseScrollMixin}）：举着拳头找人时换一个人、吃掉这一下；其余照 Minecraft 的默认。
     *
     * @return 这一下用掉了没有
     */
    public static boolean scrollLog(double vertical) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen != null || vertical == 0) {
            return false;
        }
        // 世界里滚轮只在举着拳头时「换人」（ADR-0095 F2）；日志在世界里用 ↑ ↓ 翻（用户 2026-10-07：
        // 「在世界里的时候使用上下键控制日志滚动，在 gui 界面才使用滚轮」）
        return DesignationAim.scroll(vertical);
    }

    /** GameScreen 与实际绘制共用这一份几何；两边各算一遍仍会得到完全相同的边界。 */
    static NotificationSidebarLayout sidebarLayout(int screenWidth, HudView view) {
        boolean visible = view.active() && (!view.notifications().isEmpty() || !view.weather().isEmpty())
                && !(MinecraftClient.getInstance().currentScreen instanceof GameScreen);
        return NotificationSidebarLayout.of(screenWidth, visible);
    }


    // ---------------------------------------------------------------- 小件

    private static String keyLabel(KeyBinding key) {
        return key.getBoundKeyLocalizedText().getString();
    }

    /** 第几天：罗马数字（样张 b-1）。一局到不了几十天，写到 39 就够。 */
    static String roman(int n) {
        if (n <= 0) {
            return "—";
        }
        int[] v = {10, 9, 5, 4, 1};
        String[] s = {"X", "IX", "V", "IV", "I"};
        StringBuilder out = new StringBuilder();
        int left = Math.min(n, 39);
        for (int i = 0; i < v.length; i++) {
            while (left >= v[i]) {
                out.append(s[i]);
                left -= v[i];
            }
        }
        return out.toString();
    }
}
