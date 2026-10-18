package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.ui.HudPart;
import io.github.heavyseasmc.mod.ui.SheetLayout;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 动作栏那一句，在对局各面开着时照样看得见；按它说的是什么分两路画（ADR-0100）。
 *
 * <h2>为什么要它</h2>
 * 服务端拒绝一次操作时说的那句人话（「没有人受伤，用不上医疗箱」「现在不能打出这张牌」……）一律发在动作栏
 * （{@code sendMessage(text, true)}）。动作栏是 Minecraft 自带的 HUD 画的，比 Screen 先画 —— 而对局各面铺的是
 * 不透明的纸板（ADR-0037），<b>那一句恰好被盖在板底下</b>。于是「按了，被拒了」与「按了没反应」在屏幕上一模一样
 * （2026-10-07 用户实拍：手牌一面「按 Enter 没反应」）。所以在客户端认下动作栏收到的每一句（{@code InGameHudMixin}），
 * 界面开着时在最上面一层再画一遍。
 *
 * <h2>两路（用户 2026-10-07：「提示做的太大了，应该放到右下角，并且不套白框，闪烁样式」）</h2>
 * <ul>
 *   <li><b>按键提示</b>（{@link #CORNER_KEYS}：「按下 左Shift 来脱离」「轮到你了 · 按 G 行动」）：右下角一行 14 像素的字，
 *       不套框，只托一团很淡的暗影；键名画成一枚小键帽，亮三下后停住，到时淡出（样张 A）。对局中没开界面时，
 *       世界里那两句也由这里画在右下角（{@code InGameHudMixin} 接 {@code renderOverlayMessage}），暗影加深一档 ——
 *       雾海的天是亮的，浅色小字托不住。</li>
 *   <li><b>其余一律算拒绝</b>（R1）：留在正中、倒计时那一条之上，一块小米色牌、前面一个朱砂点、出现时轻抖一下。
 *       新来源漏登记时落在显眼的这一边，而不是悄悄缩到角落里。</li>
 * </ul>
 */
public final class ActionBarEcho {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 与 Minecraft 自带动作栏同长（60 tick）。 */
    static final long SHOW_MS = 3_000L;
    /** 最后这么久淡出。 */
    static final long FADE_MS = 600L;
    /** 键帽亮几下、每下多久（样张 A：约 1.8 秒亮三下，之后停住）。 */
    static final int GLOW_PULSES = 3;
    static final long GLOW_PULSE_MS = 600L;
    /** 拒绝那块牌出现时抖多久。 */
    static final long SHAKE_MS = 320L;

    static final String TURN_BANNER = "heavyseas.hud.turn_banner";
    static final String DISMOUNT = "mount.onboard";

    /** 走右下角的那几句（翻译键）。其余一律按拒绝画在正中 —— 默认落在显眼的那一边。 */
    static final java.util.Set<String> CORNER_KEYS = java.util.Set.of(DISMOUNT, TURN_BANNER);

    /** 对局各面上不画的：坐上座位那一刻 Minecraft 自己发的「按左 Shift 下来」（2026-10-13 验收 L01：压在补给箱的说明签上；
     *  坐在艇上本来就下不来，下船要走魔镜，这一句在对局界面里只会误导）。世界里照样挪到右下角画（ADR-0100）。 */
    private static final java.util.Set<String> NOT_ON_SCREENS = java.util.Set.of(DISMOUNT);

    enum Route { CORNER, REJECT }

    private static Text corner;
    private static long cornerAt;
    private static Text reject;
    private static long rejectAt;

    private ActionBarEcho() {
    }

    /** 动作栏收到一句。客户端自己要说的（比如「没轮到你」）也走这里，与服务端那几句同一个位置。 */
    public static void record(Text text) {
        if (text == null || text.getString().isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean toCorner = route(text) == Route.CORNER;
        Text previous = toCorner ? corner : reject;
        boolean changed = previous == null || !previous.getString().equals(text.getString());
        if (toCorner) {
            corner = text;
            cornerAt = now;
        } else {
            reject = text;
            rejectAt = now;                    // 再按一次再抖一次：重复的拒绝也要看得出「又被拒了」
        }
        // 客户端回归靠这一行判「拒绝那句真的到了」（文字随语言变，前缀不变）。只在换了一句时记：
        // 北辰号上的房间名每秒刷一次动作栏，原先每秒一行把日志冲烂（2026-10-07 用户客户端日志）
        if (changed) {
            LOGGER.info("动作栏：{}", text.getString());
        }
    }

    static Route route(Text text) {
        return translationKey(text).filter(CORNER_KEYS::contains).isPresent() ? Route.CORNER : Route.REJECT;
    }

    private static java.util.Optional<String> translationKey(Text text) {
        return text.getContent() instanceof TranslatableTextContent t ? java.util.Optional.of(t.getKey())
                : java.util.Optional.empty();
    }

    /** 这一句不在对局各面上画（世界里另说，见 {@link #drawWorld}）。 */
    static boolean ignored(Text text) {
        return translationKey(text).filter(NOT_ON_SCREENS::contains).isPresent();
    }

    /**
     * 「轮到你了 · 按 G 行动」是给开着别的面的人看的；行动一面已经开着时它只是压在牌上（2026-10-13 验收 C05：
     * 医疗箱挑人那一步 Esc 退回行动一面，这一句跟着画在行动一面上）。
     */
    static boolean hiddenOn(Text text, boolean actionFaceOpen) {
        return actionFaceOpen && translationKey(text).filter(TURN_BANNER::equals).isPresent();
    }

    // ------------------------------------------------------------------ 画：界面里

    /** 在这一面的最上层画（{@code GameScreenSidebar} 的 afterRender 里，侧栏与悬停签之后）。 */
    static void draw(DrawContext context, GameScreen screen) {
        long now = System.currentTimeMillis();
        boolean actionFace = screen instanceof ActionScreen;
        Text c = corner;
        Text r = reject;
        boolean showCorner = c != null && now - cornerAt <= SHOW_MS && !ignored(c) && !hiddenOn(c, actionFace);
        boolean showReject = r != null && now - rejectAt <= SHOW_MS && !hiddenOn(r, actionFace);
        if (!showCorner && !showReject) {
            return;
        }
        SheetLayout l = screen.sheet();
        float f = 1f / screen.guiScale();
        context.getMatrices().push();
        context.getMatrices().translate(0, 0, 450);
        context.getMatrices().scale(f, f, 1f);
        if (showReject) {
            drawReject(context, l, r, now - rejectAt);
        }
        if (showCorner) {
            drawCorner(context, l, c, now - cornerAt, SHADE_ON_SCREEN);
        }
        context.getMatrices().pop();
    }

    // ------------------------------------------------------------------ 画：世界里（没开界面）

    /** 这一帧世界里要画在右下角的那一句（{@link #drawWorld} 认下、{@link #drawPendingWorld} 画）；没有就是 {@code null}。 */
    private static Text pendingWorld;
    private static long pendingWorldAge;

    /**
     * 世界里那一半（{@code InGameHudMixin} 在 {@code renderOverlayMessage} 开头调）：对局中、动作栏此刻是右下角那几句之一时，
     * 返回 {@code true} 让 Minecraft 自己那一行不画，由我们画在右下角。其余（北辰号的房间名……）照旧。
     *
     * <p>❗这里只认下、不画：右下角那一句要压在右栏之上，而右栏为了盖过聊天抬到了所有自带层之上
     * （{@code GameHud#ABOVE_CHAT_Z}）、又在这之后才画 —— 在这里画就被钉住的日志盖掉
     * （2026-10-13 第二轮验收 L05：「轮到你」键帽提示整句看不到）。真正画在 {@link #drawPendingWorld}，
     * 由 {@code GameHud#render} 在右栏之后调，谁压谁由一处排定。
     */
    public static boolean drawWorld(DrawContext context, Text overlay) {
        WorldLayer layer = worldLayer(overlay, GameHud.voyageHud(),
                MinecraftClient.getInstance().currentScreen instanceof GameScreen);
        if (layer == WorldLayer.VANILLA) {
            return false;
        }
        if (layer == WorldLayer.NONE) {
            return true;
        }
        long age = System.currentTimeMillis() - cornerAt;
        if (age <= SHOW_MS) {
            pendingWorld = corner != null ? corner : overlay;
            pendingWorldAge = age;
        }
        return true;                           // 到时就收：Minecraft 那一行比我们晚灭，不让它接着露出来
    }

    /** 画这一帧认下的那一句（见 {@link #drawWorld}），{@code z} 是它要压过的那一层之上。画完就清，下一帧重新认。 */
    static void drawPendingWorld(DrawContext context, float z) {
        Text shown = pendingWorld;
        pendingWorld = null;
        if (shown == null) {
            return;
        }
        var window = MinecraftClient.getInstance().getWindow();
        SheetLayout l = SheetLayout.of(window.getFramebufferWidth(), window.getFramebufferHeight());
        float f = (float) (1 / window.getScaleFactor());
        context.getMatrices().push();
        context.getMatrices().translate(0, 0, z);
        context.getMatrices().scale(f, f, 1f);
        drawCorner(context, l, shown, pendingWorldAge, SHADE_IN_WORLD);
        context.getMatrices().pop();
    }

    /** 世界那一层（HUD，画在界面底下）上这一句怎么办。 */
    enum WorldLayer {
        /** 不归我们：Minecraft 自己那一行照画。 */
        VANILLA,
        /** 归我们、但这一层不画：对局的一面开着，它在压暗的世界底下读不出来，由界面那一层（{@link #draw}）在最上面画或不画。 */
        NONE,
        /** 归我们，画在右下角。 */
        CORNER
    }

    /**
     * 世界那一层画不画。❗对局的一面开着时这一层在压暗之下：「轮到你了 · 按 G 行动」「按左 Shift 脱离」被画在面板外的深色底上，
     * 几乎看不见（2026-10-13 验收 05_g · L01）—— 而且行动一面开着时界面那一层本来就不画「轮到你」（{@link #hiddenOn}），
     * 露出来的只有底下这一份。界面开着时这一层一律不画，要画的由界面那一层画在最上面。
     */
    static WorldLayer worldLayer(Text overlay, boolean voyageHud, boolean gameScreenOpen) {
        if (overlay == null || route(overlay) != Route.CORNER || !voyageHud) {
            return WorldLayer.VANILLA;
        }
        return gameScreenOpen ? WorldLayer.NONE : WorldLayer.CORNER;
    }

    // ------------------------------------------------------------------ 右下角的按键提示（样张 A）

    /** 字多大、离右边与下边多远（稿子像素，随 {@code SheetLayout.k} 缩放）。 */
    static final double CORNER_PX = 14;
    private static final double CORNER_RIGHT = 24;
    private static final double CORNER_BOTTOM = 8;
    /** 托字的那团暗影有多深：界面里背后是压暗的世界；世界里背后多半是雾海亮的天，加深一档（主会话看图补的一条）。 */
    static final float SHADE_ON_SCREEN = 0.45f;
    static final float SHADE_IN_WORLD = 0.68f;
    /** 键帽亮起来的光（铜金），不按主题：它画在世界上，不在板上。 */
    private static final float[] GLOW_RGB = {0.965f, 0.84f, 0.47f};

    /** 此刻还剩几成不透明：最后 {@link #FADE_MS} 线性淡出。 */
    static float fade(long ageMs) {
        if (ageMs < 0 || ageMs > SHOW_MS) {
            return 0f;
        }
        long left = SHOW_MS - ageMs;
        return left >= FADE_MS ? 1f : left / (float) FADE_MS;
    }

    /** 键帽此刻亮几成：前 {@link #GLOW_PULSES} × {@link #GLOW_PULSE_MS} 里亮 {@link #GLOW_PULSES} 下，之后停住；少动效时不亮。 */
    static float glow(long ageMs, boolean reducedMotion) {
        if (reducedMotion || ageMs < 0 || ageMs >= GLOW_PULSES * GLOW_PULSE_MS) {
            return 0f;
        }
        double s = Math.sin(Math.PI * ageMs / GLOW_PULSE_MS);
        return (float) (s * s);
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:1\\$)?s");

    /**
     * 把一句拆成「键名前 · 键名 · 键名后」，键名画成键帽。只认恰好一个占位、且那个参数就是键名的句子；
     * 别的（没有占位、两个占位）返回 {@code null}，整句照字画。
     */
    static String[] splitAroundKey(String format, String keyLabel) {
        if (format == null || keyLabel == null || keyLabel.isBlank()) {
            return null;
        }
        Matcher m = PLACEHOLDER.matcher(format);
        if (!m.find()) {
            return null;
        }
        int start = m.start();
        int end = m.end();
        if (m.find() || format.indexOf('%', end) >= 0 || format.substring(0, start).indexOf('%') >= 0) {
            return null;
        }
        return new String[]{format.substring(0, start).strip(), keyLabel, format.substring(end).strip()};
    }

    private static String[] parts(Text text) {
        if (text.getContent() instanceof TranslatableTextContent t && t.getArgs().length == 1) {
            Object arg = t.getArgs()[0];
            String key = arg instanceof Text a ? a.getString() : String.valueOf(arg);
            String[] split = splitAroundKey(Language.getInstance().get(t.getKey()), key);
            if (split != null) {
                return split;
            }
        }
        return new String[]{text.getString(), null, ""};
    }

    private static void drawCorner(DrawContext context, SheetLayout l, Text text, long age, float shade) {
        float a = fade(age);
        if (a <= 0f) {
            return;
        }
        String[] p = parts(text);
        int px = l.len(CORNER_PX);
        int line = GuiText.linePxAt(px, false);
        int keyH = line + Math.max(2, l.len(4));
        int gap = Math.max(2, l.len(5));
        int keyW = p[1] == null ? 0 : Math.max(keyH, GuiText.widthPx(p[1], px, true, 0) + 2 * l.len(6));
        int w0 = p[0].isEmpty() ? 0 : GuiText.widthPx(p[0], px, false, 0);
        int w2 = p[2].isEmpty() ? 0 : GuiText.widthPx(p[2], px, false, 0);
        int w = w0 + (p[1] == null ? 0 : (w0 > 0 ? gap : 0) + keyW + (w2 > 0 ? gap : 0)) + w2;
        int x = l.width() - l.len(CORNER_RIGHT) - w;
        int cy = l.height() - l.len(CORNER_BOTTOM) - keyH / 2;
        // 暗影（白色的一团着成近黑）：比字宽出两头、比键帽高出上下 —— 不是框，四边都是渐无的
        int sx = l.len(26);
        int sy = l.len(10);
        context.setShaderColor(0.04f, 0.03f, 0.02f, shade * a);
        GuiMaterial.hudPart(context, HudPart.HALO, x - sx, cy - keyH / 2 - sy, w + 2 * sx, keyH + 2 * sy, l.k());
        context.setShaderColor(1f, 1f, 1f, 1f);
        int ink = GuiLanguage.Hud.alpha(GuiLanguage.onWorld(GuiLanguage.ink()), a);
        int textTop = cy - line / 2;
        if (w0 > 0) {
            GuiText.drawPx(context, p[0], x, textTop, w0 + 2, px, false, ink, GuiText.Align.LEFT, 0);
            x += w0 + gap;
        }
        if (p[1] != null) {
            float g = glow(age, ClientPrefs.reducedMotion());
            if (g > 0f) {
                int gx = l.len(9);
                int gy = l.len(7);
                context.setShaderColor(GLOW_RGB[0], GLOW_RGB[1], GLOW_RGB[2], 0.95f * g * a);
                GuiMaterial.hudPart(context, HudPart.HALO, x - gx, cy - keyH / 2 - gy, keyW + 2 * gx, keyH + 2 * gy, l.k());
            }
            context.setShaderColor(1f, 1f, 1f, a);
            GuiMaterial.hudPart(context, HudPart.KEY, x, cy - keyH / 2, keyW, keyH, l.k());
            context.setShaderColor(1f, 1f, 1f, 1f);
            // 键名用粗体（与各面键帽一致）；整句用常规体 —— 粗体子集里没有「脱」（ADR-0100 风险 ④）
            GuiText.drawPx(context, p[1], x, cy - keyH / 2 + (keyH - Math.max(2, l.len(2)) - GuiText.linePxAt(px, true)) / 2,
                    keyW, px, true, GuiLanguage.Hud.alpha(GuiLanguage.Hud.ENAMEL_LINE, a), GuiText.Align.CENTER, 0);
            x += keyW + gap;
        }
        if (w2 > 0) {
            GuiText.drawPx(context, p[2], x, textTop, w2 + 2, px, false, ink, GuiText.Align.LEFT, 0);
        }
    }

    // ------------------------------------------------------------------ 正中的拒绝（样张 R1）

    private static final double REJECT_PX = 14;

    /** 出现那一下横着抖几像素（稿子像素）：衰减的正弦，{@link #SHAKE_MS} 内停下；少动效时不抖。 */
    static double shake(long ageMs, boolean reducedMotion) {
        if (reducedMotion || ageMs < 0 || ageMs >= SHAKE_MS) {
            return 0;
        }
        double t = ageMs / 1000.0;
        return 4.0 * Math.sin(2 * Math.PI * 14 * t) * (1 - ageMs / (double) SHAKE_MS);
    }

    private static void drawReject(DrawContext context, SheetLayout l, Text text, long age) {
        float a = fade(age);
        if (a <= 0f) {
            return;
        }
        String line = text.getString();
        int px = l.len(REJECT_PX);
        int lineH = GuiText.linePxAt(px, false);
        int dot = Math.max(3, l.len(7));
        int padX = l.len(12);
        int padY = Math.max(2, l.len(3));
        int gap = l.len(7);
        int maxText = Math.max(1, l.sheet().w() - 2 * l.len(60) - 2 * padX - dot - gap);
        int textW = Math.min(GuiText.widthPx(line, px, false, 0), maxText);
        int w = 2 * padX + dot + gap + textW;
        int h = lineH + 2 * padY;
        int x = l.width() / 2 - w / 2 + (int) Math.round(shake(age, ClientPrefs.reducedMotion()) * l.k());
        // 落在倒计时横杠与提示那一行之间那道缝的正中（SheetLayout.rejectTop）：盖住的只是「时间到……」那一行小字。
        // 原先落在横杠之上，手牌一面那一排牌就在那里，提示贴着牌底压住两张（2026-10-13 验收 C04）
        int y = l.rejectTop(h);
        context.setShaderColor(1f, 1f, 1f, a);
        GuiMaterial.hudPart(context, HudPart.BTN, x, y, w, h, l.k());
        context.setShaderColor(1f, 1f, 1f, 1f);
        // 朱砂点印在米色牌上：取浅色主题那一档的朱砂（onTag），两个主题一样
        GuiMaterial.hudIcon(context, HudPart.DOT, x + padX, y + (h - dot) / 2, dot, dot,
                GuiLanguage.Hud.alpha(GuiLanguage.onTag(GuiLanguage.cinnabar()), a), l.k());
        GuiText.drawPx(context, line, x + padX + dot + gap, y + padY, textW, px, false,
                GuiLanguage.Hud.alpha(GuiLanguage.onTag(GuiLanguage.ink()), a), GuiText.Align.LEFT, 0);
    }
}
