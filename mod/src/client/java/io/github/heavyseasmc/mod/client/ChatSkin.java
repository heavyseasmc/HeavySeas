package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.ui.ChatFeed;
import io.github.heavyseasmc.mod.ui.HudLayout.Rect;
import io.github.heavyseasmc.mod.ui.HudPart;
import io.github.heavyseasmc.mod.ui.SheetLayout;
import io.github.heavyseasmc.mod.ui.TextFit;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.network.message.ChatVisibility;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.text.TranslatableTextContent;

/**
 * 聊天栏的皮肤（2026-10-13 用户定，样张 {@code 2026-10-13-chat-mockups}）：世界里平时是 <b>C1 逐句纸条</b>，
 * 按 T 打开聊天时换成 <b>C2 账本页</b>（与航海日志同一张搪瓷卡）。只换画法：收消息、发消息、指令补全、点字里的链接、
 * 悬停看说明都还是 Minecraft 自己那一套 —— {@code ChatHudMixin} 把它的 {@code render} 换成这里，
 * {@code getTextStyleAt} 按这里排出来的版面答「指针下是哪个字」，滚动按这里的行数夹。
 *
 * <h2>全程生效</h2>
 * 大厅、北辰号、对局里一样（ADR 与现有做法里没有「只在对局中换聊天」的先例；拿不准按用户的话「全程」）。
 * 对局界面开着时聊天栏本来就不画（{@code InGameHudMixin}），面板里另有窄条（{@link GameScreen}）。
 *
 * <h2>字</h2>
 * 正文用常规体（字体子集收了 GB2312 全集）；全集之外的生僻字照旧静默退回 Minecraft 的像素字 —— 已接受的代价。
 * 输入框里的字仍是 Minecraft 自己的字：光标、选区、补全框的位置都按那套字宽算，换了字就对不上（只把颜色改成纸上的墨）。
 */
final class ChatSkin {

    private ChatSkin() {
    }

    /** 面板窄条与未读数的那一份（{@link ChatFeed}）。只在渲染线程上碰。 */
    static final ChatFeed FEED = new ChatFeed();

    // ------------------------------------------------------------------ 稿子像素（1280×720 · k = 1），照样张 build.py

    /** C1：离屏幕左沿 20，栈底在 676（离底 44），最宽 460，字 16、行 22，纸边 10 · 3，纸条之间 4，只留最近 3 句。 */
    private static final double C1_LEFT = 20;
    private static final double C1_FROM_BOTTOM = 44;
    private static final double C1_MAX_W = 460;
    private static final double C1_PX = 16;
    private static final double C1_ROW = 22;
    private static final double C1_PAD_X = 10;
    private static final double C1_PAD_Y = 3;
    private static final double C1_GAP = 4;
    private static final int C1_LINES = 3;
    /** Minecraft 聊天栏每句停 200 tick（10 秒），最后 1 秒淡出 —— 照它。 */
    private static final int C1_TICKS = 200;

    /** C2：左 20 · 宽 560，内边 18 · 14，页头 26，字 16、行 23，页头字 14、提示 12，右缘滚动条 2 宽。 */
    private static final double C2_LEFT = 20;
    private static final double C2_W = 560;
    private static final double C2_PAD_X = 18;
    private static final double C2_PAD_TOP = 14;
    private static final double C2_HEAD = 26;
    private static final double C2_PX = 16;
    private static final double C2_ROW = 23;
    private static final double C2_HEAD_PX = 14;
    private static final double C2_HINT_PX = 12;
    private static final double C2_BAR = 2;
    private static final double C2_BAR_GAP = 6;
    /** 输入槽与它上面那一句之间、与卡底之间。 */
    private static final double C2_SLOT_GAP = 10;
    private static final double C2_SLOT_BOTTOM = 6;
    /** 页顶给决定那张「剩余 N 秒」签让出来的高度（{@code DecisionNavigation.drawPending} 在 6–42）。 */
    private static final double C2_TOP_ROOM = 56;
    /**
     * 字那一块最高多少：样张那一页 8 句约 230 高，这里给到 13 行左右。Minecraft 自己的「聊天高度（打开时）」若设得更矮，取它；
     * 设得更高也不跟 —— 一整屏的页会盖住左上角的状态牌（实拍：100 句时页顶到了 y 100）。
     */
    private static final double C2_MAX_AREA = 300;
    /** 一句最多折几行（聊天一句最长 256 字）。 */
    private static final int MAX_ROWS_PER_LINE = 12;

    // ------------------------------------------------------------------ 收消息

    /** Minecraft 聊天栏收到一句（{@code ChatHud.addMessage}）：记进窄条；此刻开着的是对局界面才算未读。 */
    static void arrived(Text message) {
        Flat f = flat(message);
        MinecraftClient mc = MinecraftClient.getInstance();
        String speaker = f.system() ? null : f.text().substring(0, f.nameLen());
        // 自己说的那句不算未读：在聊天里按 Enter 发出去、回到面板之后服务端才回显，不该挂一个「1」
        boolean mine = speaker != null && mc.player != null
                && (speaker.equals(mc.player.getName().getString()) || speaker.equals(mc.player.getDisplayName().getString()));
        // 只数人说的话：系统句（截图已保存、谁上了艇）不挂未读、也不进窄条（ChatFeed#arrive）——
        // 第一版两样都收，F2 那一句把人话挤出了窄条（2026-10-13 总截图）
        boolean counts = mc.currentScreen instanceof GameScreen && speaker != null && !mine;
        FEED.arrive(new ChatFeed.Entry(speaker, f.body(), System.currentTimeMillis()), counts);
    }

    /** 按 T 打开了聊天：看过了。 */
    static void opened(TextFieldWidget field) {
        FEED.seen();
        chatField = new WeakReference<>(field);
        if (on()) {
            field.setEditableColor(GuiLanguage.Chat.PAGE_INK);
        }
    }

    /** 聊天栏清空（换世界、断线）：窄条与未读一起清。 */
    static void forget() {
        FEED.clear();
        FLATS.clear();
    }

    // ------------------------------------------------------------------ 拆句：说话的人 · 正文 · 每个字的样式

    /**
     * 一句拆平：{@code text} 是要画的字（格式记号剥掉），{@code styles[i]} 是第 i 个 char 的样式（点击 · 悬停都在里面）；
     * 玩家说的话认 {@code chat.type.text}（「人名：正文」），其余一律是系统句，整句次墨。
     */
    record Flat(String text, Style[] styles, int nameLen, int bodyFrom, boolean system) {
        String body() {
            return text.substring(bodyFrom);
        }
    }

    private static final Map<Text, Flat> FLATS = new IdentityHashMap<>();

    static Flat flat(Text message) {
        Flat cached = FLATS.get(message);
        if (cached != null) {
            return cached;
        }
        if (FLATS.size() > 400) {
            FLATS.clear();
        }
        StringBuilder sb = new StringBuilder();
        List<Style> styles = new ArrayList<>();
        Flat out;
        if (message.getContent() instanceof TranslatableTextContent t && "chat.type.text".equals(t.getKey())
                && t.getArgs().length >= 2) {
            append(sb, styles, asText(t.getArgs()[0]), message.getStyle());
            int nameLen = sb.length();
            for (char c : Text.translatable("heavyseas.chat.name_sep").getString().toCharArray()) {
                sb.append(c);
                styles.add(Style.EMPTY);
            }
            int bodyFrom = sb.length();
            append(sb, styles, asText(t.getArgs()[1]), message.getStyle());
            out = new Flat(sb.toString(), styles.toArray(new Style[0]), nameLen, bodyFrom, nameLen == 0);
        } else {
            append(sb, styles, message, Style.EMPTY);
            out = new Flat(sb.toString(), styles.toArray(new Style[0]), 0, 0, true);
        }
        FLATS.put(message, out);
        return out;
    }

    private static Text asText(Object arg) {
        return arg instanceof Text t ? t : Text.literal(String.valueOf(arg));
    }

    /** 逐段取字与样式；字面量里的格式记号（§ 加一个字）不上屏，一并跳过 —— 与 {@link GuiText#plain} 同一个规矩。 */
    private static void append(StringBuilder sb, List<Style> styles, Text text, Style base) {
        text.visit((style, s) -> {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '§') {
                    i++;
                    continue;
                }
                if (c == '\n' || c == '\r') {
                    c = ' ';
                }
                sb.append(c);
                styles.add(style);
            }
            return Optional.empty();
        }, base);
    }

    /** 每个字什么颜色：人名一色、正文一色、系统句整句次墨；截断补的「…」跟前一个字。 */
    private static int[] colors(Flat f, int[] idx, int name, int ink, int muted, float alpha) {
        int[] out = new int[idx.length];
        int last = f.system() ? muted : ink;
        for (int j = 0; j < idx.length; j++) {
            if (idx[j] >= 0) {
                last = f.system() ? muted : idx[j] < f.nameLen() ? name : ink;
            }
            out[j] = GuiLanguage.Hud.alpha(last, alpha);
        }
        return out;
    }

    // ------------------------------------------------------------------ 画

    /** 这一帧换不换：聊天设成「隐藏」时 Minecraft 自己什么都不画，这里也不画（交还给它，它照样什么都不画）。 */
    static boolean on() {
        return MinecraftClient.getInstance().options.getChatVisibility().getValue() != ChatVisibility.HIDDEN;
    }

    /**
     * 代替 {@code ChatHud.render}：没打开聊天时是 C1 纸条，打开了是 C2 账本页。
     *
     * @param lines    Minecraft 聊天栏存着的那几句（新的在前）
     * @param scrolled 往上翻了几行（Minecraft 那个计数，这里按自己的行数解释）
     */
    static void draw(DrawContext context, List<ChatHudLine> lines, int tick, boolean focused, int scrolled, ChatHud hud) {
        hits.clear();
        if (lines.isEmpty() && !focused) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        var window = mc.getWindow();
        int g = Math.max(1, (int) Math.round(window.getScaleFactor()));
        context.getMatrices().push();
        // ❗不往前推 z：页画完之后 ChatScreen 还要在 z = 0 上画输入槽与输入框，推到 50 就把它们挡在页后面（实拍过）
        context.getMatrices().scale(1f / g, 1f / g, 1f);
        try {
            double k = SheetLayout.of(window.getFramebufferWidth(), window.getFramebufferHeight()).k();
            if (focused) {
                drawPage(context, lines, scrolled, hud, window.getFramebufferWidth(), window.getFramebufferHeight(),
                        window.getScaledHeight(), g, k);
            } else {
                drawStrips(context, lines, tick, window.getFramebufferWidth(), window.getFramebufferHeight(), k);
            }
        } finally {
            context.getMatrices().pop();
        }
    }

    private static int len(double design, double k) {
        return (int) Math.round(design * k);
    }

    /** C1：最近 3 句、每句一张纸条，新的在下；每句停 10 秒、最后 1 秒自己淡出，照 Minecraft 的聊天栏。 */
    private static void drawStrips(DrawContext context, List<ChatHudLine> lines, int tick, int fbW, int fbH, double k) {
        double opacity = MinecraftClient.getInstance().options.getChatOpacity().getValue() * 0.9 + 0.1;
        int left = len(C1_LEFT, k);
        int maxW = Math.min(len(C1_MAX_W, k), fbW - 2 * left);
        int size = len(C1_PX, k);
        int row = len(C1_ROW, k);
        int padX = len(C1_PAD_X, k);
        int padY = len(C1_PAD_Y, k);
        int y = fbH - len(C1_FROM_BOTTOM, k);
        int shown = 0;
        for (ChatHudLine line : lines) {
            int age = tick - line.creationTick();
            if (age >= C1_TICKS || shown >= C1_LINES) {
                break;
            }
            shown++;
            float alpha = (float) (fade(age) * opacity);
            Flat f = flat(line.content());
            TextFit.Result fit = GuiText.fitFixedPx(f.text(), maxW - 2 * padX, size, MAX_ROWS_PER_LINE);
            if (fit.lines().isEmpty()) {
                continue;
            }
            int w = fit.lines().stream().mapToInt(TextFit.Line::width).max().orElse(0) + 2 * padX;
            int h = fit.lines().size() * row + 2 * padY;
            y -= h;
            if (alpha > 0.02f) {
                GuiMaterial.chatStrip(context, left, y, w, h, k, alpha);
                List<String> texts = fit.lines().stream().map(TextFit.Line::text).toList();
                int[][] idx = GuiText.lineIndices(f.text(), texts);
                for (int i = 0; i < texts.size(); i++) {
                    GuiText.drawRowRunsPx(context, texts.get(i), colors(f, idx[i], GuiLanguage.Chat.stripName(),
                                    GuiLanguage.Chat.stripInk(), GuiLanguage.Chat.stripMuted(), alpha),
                            left + padX, y + padY + i * row, row, fit.size());
                }
            }
            y -= len(C1_GAP, k);
        }
    }

    /** 与 Minecraft 聊天栏同一条淡出曲线：前 9 秒全不透明，最后 1 秒平方地退。 */
    private static double fade(int age) {
        double d = Math.max(0.0, Math.min(1.0, (1.0 - age / (double) C1_TICKS) * 10.0));
        return d * d;
    }

    /** C2 那一页里每一行字画在哪（物理像素），点字、悬停时按它找是哪个字。 */
    private record Hit(Rect box, String line, int[] idx, Flat flat, int size) {
    }

    private static final List<Hit> hits = new ArrayList<>();
    /** 上一帧排出来最多能往上翻几行（{@code ChatHud.scroll} 按它夹）。 */
    private static int maxScroll;

    private static void drawPage(DrawContext context, List<ChatHudLine> lines, int scrolled, ChatHud hud,
                                 int fbW, int fbH, int guiH, int g, double k) {
        Rect card = pageColumn(fbW, k);
        int padX = len(C2_PAD_X, k);
        int size = len(C2_PX, k);
        int row = len(C2_ROW, k);
        int textW = card.w() - 2 * padX - len(C2_BAR_GAP + C2_BAR, k);
        Rect slot = inputSlot(fbW, guiH, g, k);
        int rowsBottom = slot.y() - len(C2_SLOT_GAP, k);
        int headTop = len(C2_PAD_TOP, k);
        int head = len(C2_HEAD, k);
        int roomTop = len(C2_TOP_ROOM, k);
        int maxArea = Math.max(row, Math.min(Math.min(hud.getHeight() * g, len(C2_MAX_AREA, k)),
                rowsBottom - head - headTop - roomTop));
        if (faceRoom != null) {
            // 不压舞台；但至少留三行 —— 只按舞台下沿收，1280×720 下只剩一行，一句折成两行时只露出后半截（2026-10-13 复拍）。
            // 多出来的那一点伸进舞台底下那一截空处，牌一般在舞台里竖向居中、碰不到
            int faceCap = rowsBottom - head - headTop - faceRoom[1];
            maxArea = Math.max(row, Math.min(maxArea, Math.max(faceCap, C2_MIN_ROWS_OVER_FACE * row)));
        }

        // 自下而上：最新的一句在最底下。每一行记着它属于哪一句、是不是那一句的最后一行（底下画一道细线）
        record Row(Flat flat, String text, int[] idx, boolean last, int size) { }
        List<Row> rows = new ArrayList<>();
        for (int n = lines.size() - 1; n >= 0; n--) {
            Flat f = flat(lines.get(n).content());
            TextFit.Result fit = GuiText.fitFixedPx(f.text(), textW, size, MAX_ROWS_PER_LINE);
            List<String> texts = fit.lines().stream().map(TextFit.Line::text).toList();
            int[][] idx = GuiText.lineIndices(f.text(), texts);
            for (int i = 0; i < texts.size(); i++) {
                rows.add(new Row(f, texts.get(i), idx[i], i == texts.size() - 1, fit.size()));
            }
        }
        int fits = Math.max(1, maxArea / row);
        int visible = Math.min(rows.size(), fits);
        maxScroll = Math.max(0, rows.size() - visible);
        int skip = Math.max(0, Math.min(scrolled, maxScroll));
        int areaH = Math.max(visible, 1) * row;
        int top = rowsBottom - areaH - head - headTop;
        int bottom = Math.min(fbH - 1, slot.bottom() + len(C2_SLOT_BOTTOM, k));
        GuiMaterial.hudPart(context, HudPart.ENAMEL, card.x(), top, card.w(), bottom - top, k);

        int x = card.x() + padX;
        int headPx = len(C2_HEAD_PX, k);
        GuiText.drawPx(context, Text.translatable("heavyseas.navigation.chat").getString(), x, top + headTop,
                textW / 2, headPx, false, GuiLanguage.Chat.PAGE_HEAD, GuiText.Align.LEFT, 0);
        GuiText.drawPx(context, Text.translatable("heavyseas.chat.page_hint").getString(), x + textW / 2,
                top + headTop + (GuiText.linePxAt(headPx, false) - GuiText.linePxAt(len(C2_HINT_PX, k), false)) / 2,
                card.right() - padX - x - textW / 2, len(C2_HINT_PX, k), false, GuiLanguage.Chat.PAGE_MUTED,
                GuiText.Align.RIGHT, 0);

        int y0 = rowsBottom - areaH;
        int from = rows.size() - skip - visible;
        int rule = Math.max(1, (int) Math.round(k));
        for (int i = 0; i < visible; i++) {
            Row r = rows.get(from + i);
            int y = y0 + i * row;
            if (r.last()) {
                context.fill(x, y + row - rule, x + textW, y + row, GuiLanguage.Chat.PAGE_RULE);
            }
            GuiText.drawRowRunsPx(context, r.text(), colors(r.flat(), r.idx(), GuiLanguage.Chat.PAGE_NAME,
                    GuiLanguage.Chat.PAGE_INK, GuiLanguage.Chat.PAGE_MUTED, 1f), x, y, row, r.size());
            hits.add(new Hit(new Rect(x, y, textW, row), r.text(), r.idx(), r.flat(), r.size()));
        }
        if (rows.size() > visible) {
            int barX = card.right() - padX + len(C2_BAR_GAP, k) / 2;
            int barW = Math.max(1, len(C2_BAR, k));
            int thumb = Math.max(row / 2, areaH * visible / rows.size());
            int thumbY = y0 + (areaH - thumb) * (maxScroll - skip) / Math.max(1, maxScroll);
            context.fill(barX, thumbY, barX + barW, thumbY + thumb, GuiLanguage.Hud.alpha(GuiLanguage.Chat.PAGE_HEAD, 0.7f));
        }
    }

    /** C2 那一页的横向位置（物理像素；竖向由内容定）。叠在对局界面上时右沿收到 {@link #faceRoom} 给的那一道。 */
    private static Rect pageColumn(int fbW, double k) {
        int x = len(C2_LEFT, k);
        int w = Math.min(len(C2_W, k), fbW - 2 * x);
        if (faceRoom != null) {
            w = Math.max(len(C2_MIN_W_OVER_FACE, k), Math.min(w, faceRoom[0] - x));
        }
        return new Rect(x, 0, w, 0);
    }

    /**
     * 叠在对局界面上时聊天页至少多宽（稿子像素）：再窄输入框就打不下一句话。第一版 300 —— 1280×720 钉住日志时
     * 倒计时横杠左边只剩约 258，下限把页撑过去压住了横杠的左端（2026-10-13 复拍）。
     */
    private static final double C2_MIN_W_OVER_FACE = 180;
    /** 叠在对局界面上时聊天页至少放几行。 */
    private static final int C2_MIN_ROWS_OVER_FACE = 3;

    /**
     * 聊天叠在一面对局界面上时（{@link DecisionChatScreen}）那一面给聊天页留的一角：{右沿, 上沿}（物理像素）；不叠时 {@code null}。
     * 页的上沿不过它、右沿不过它，牌与倒计时露在外面（{@link GameScreen#chatRoom}）。
     */
    private static int[] faceRoom;

    static void faceRoom(int[] room) {
        faceRoom = room;
    }

    /**
     * 输入槽（物理像素）：竖向就是 Minecraft 输入框那一条（GUI 单位 {@code 高 − 14 … 高 − 2}）——
     * 补全框与指令用法提示都按屏幕底往上排，输入框挪了位置它们就压在页上。横向收进页里。
     */
    static Rect inputSlot(int fbW, int guiH, int g, double k) {
        Rect col = pageColumn(fbW, k);
        int padX = len(C2_PAD_X, k);
        return new Rect(col.x() + padX, (guiH - 14) * g, col.w() - 2 * padX, 12 * g);
    }

    /** 画输入槽（代替 {@code ChatScreen.render} 里那一条黑底）：GUI 单位的矩阵里调，自己换到物理像素。 */
    static void drawInputSlot(DrawContext context) {
        MinecraftClient mc = MinecraftClient.getInstance();
        var window = mc.getWindow();
        int g = Math.max(1, (int) Math.round(window.getScaleFactor()));
        double k = SheetLayout.of(window.getFramebufferWidth(), window.getFramebufferHeight()).k();
        Rect slot = inputSlot(window.getFramebufferWidth(), window.getScaledHeight(), g, k);
        context.getMatrices().push();
        context.getMatrices().scale(1f / g, 1f / g, 1f);
        GuiMaterial.hudPartOnEnamel(context, HudPart.TRACK, slot.x(), slot.y(), slot.w(), slot.h(), k);
        context.getMatrices().pop();
    }

    /**
     * 输入框收进槽里（GUI 单位）：左边离槽沿一点、宽到槽的右沿。竖向不动（见 {@link #inputSlot}）。
     * 返回 {x, 宽}。
     */
    static int[] fieldSpan() {
        MinecraftClient mc = MinecraftClient.getInstance();
        var window = mc.getWindow();
        int g = Math.max(1, (int) Math.round(window.getScaleFactor()));
        double k = SheetLayout.of(window.getFramebufferWidth(), window.getFramebufferHeight()).k();
        Rect slot = inputSlot(window.getFramebufferWidth(), window.getScaledHeight(), g, k);
        int x = Math.floorDiv(slot.x() + len(8, k) + g - 1, g);
        int right = Math.floorDiv(slot.right() - len(8, k), g);
        return new int[]{x, Math.max(10, right - x)};
    }

    // ------------------------------------------------------------------ 指针下是哪个字（代替 ChatHud.getTextStyleAt）

    static Style styleUnder(double guiX, double guiY) {
        int g = Math.max(1, (int) Math.round(MinecraftClient.getInstance().getWindow().getScaleFactor()));
        int px = (int) Math.floor(guiX * g);
        int py = (int) Math.floor(guiY * g);
        for (Hit h : hits) {
            Rect b = h.box();
            if (px < b.x() || px >= b.right() || py < b.y() || py >= b.bottom()) {
                continue;
            }
            int dx = px - b.x();
            for (int j = 0; j < h.line().length(); j++) {
                if (dx < GuiText.prefixWidthPx(h.line(), j + 1, h.size())) {
                    int at = h.idx()[j];
                    return at >= 0 && at < h.flat().styles().length ? h.flat().styles()[at] : null;
                }
            }
            return null;
        }
        return null;
    }

    /** {@code ChatHud.scroll} 的夹法：按上一帧排出来的行数，不按 Minecraft 自己折的行数（两边字宽不同）。 */
    static int clampScroll(int wanted) {
        return Math.max(0, Math.min(wanted, maxScroll));
    }

    // ------------------------------------------------------------------ 输入框（TextFieldWidgetMixin）

    private static WeakReference<TextFieldWidget> chatField = new WeakReference<>(null);

    /** 这个输入框是不是此刻聊天界面里那一个（输入框到处都有，只改这一个的颜色与阴影）。 */
    static boolean ownsField(TextFieldWidget field) {
        return field != null && field == chatField.get() && on()
                && MinecraftClient.getInstance().currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen;
    }

    /** 纸上的墨：Minecraft 给黑底配的那几种亮色（指令高亮的青、黄、绿、粉、金，报错的红，说明的灰）换成纸上读得出的深色。 */
    static OrderedText inkOnPaper(OrderedText text) {
        return visitor -> text.accept((index, style, cp) -> visitor.accept(index, paperStyle(style), cp));
    }

    private static Style paperStyle(Style style) {
        TextColor c = style.getColor();
        if (c == null) {
            return style;
        }
        // 槽永远是浅纸（hudPartOnEnamel）：语义色取完再按「印在签上」换成浅色主题那一套深色
        return style.withColor(TextColor.fromRgb(GuiLanguage.onTag(GuiLanguage.semantic(c.getRgb())) & 0xFFFFFF));
    }
}
