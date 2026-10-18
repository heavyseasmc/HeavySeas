package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.rulebook.LineBreak;
import io.github.heavyseasmc.mod.rulebook.Rulebook;
import io.github.heavyseasmc.mod.rulebook.RulebookData;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.MutableText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 规则书在游戏里那一本：把 {@link Rulebook} 的块按<b>当前字体</b>折行、切页，交给 Minecraft 自带的 {@link BookScreen}（ADR-0087 §2）。
 *
 * <p>❗{@code BookScreen} 不替你分页：一页宽 114 像素、只画前 14 行（{@code 128 / 9}），多出来的直接不见。
 * 所以分页放在打开那一刻按当前字体做 —— 换语言、换资源包都对；构建时按估的字宽切会在别的字体上溢出。
 */
public final class RulebookBook {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 与 {@code BookScreen} 一致：版心宽 114，一页 14 行。 */
    static final int WIDTH = 114;
    static final int LINES = 14;

    private static final Style CHAPTER = Style.EMPTY.withBold(true).withColor(TextColor.fromRgb(0x7A2E1D));
    private static final Style BOLD = Style.EMPTY.withBold(true);

    private RulebookBook() {
    }

    public static void open(MinecraftClient client) {
        open(client, null, "contents");
    }

    static void open(MinecraftClient client, GameScreen parent, String anchor) {
        try {
            show(client, parent, anchor);
        } catch (RuntimeException e) {
            // 讲台右键在渲染线程上：抛出去整个客户端就崩了。记下原因、在聊天栏说一句 —— 不静默，也不为一本书崩游戏
            LOGGER.error("规则书打不开", e);
            if (client.player != null) {
                client.player.sendMessage(Text.translatable("heavyseas.rulebook.failed", String.valueOf(e.getMessage())), false);
            }
        }
    }

    private static void show(MinecraftClient client, GameScreen parent, String anchor) {
        String language = client.getLanguageManager().getLanguage();
        Map.Entry<String, String> picked = RulebookData.pick(language);
        Language lang = Language.getInstance();
        Rulebook.Facts facts = RulebookData.facts(key -> lang.hasTranslation(key) ? lang.get(key) : null, keys());
        Document document = document(Rulebook.parse(picked.getValue(), facts), client.textRenderer, true);
        // 报出用的是哪一份：退回到另一种语言与没退回，在书页上只差在字是什么语言
        LOGGER.info("规则书：客户端语言 {} · 用的是 {} 那一份 · {} 页", language, picked.getKey(), document.pages().size());
        client.setScreen(new NavigationBook(document, parent, anchor));
    }

    /** 附录里的按键：读玩家现在的绑定，不是默认键。 */
    private static List<Rulebook.KeyLine> keys() {
        List<Rulebook.KeyLine> out = new ArrayList<>();
        for (KeyBinding k : HeavySeasClient.ownKeys()) {
            out.add(new Rulebook.KeyLine(Text.translatable(k.getTranslationKey()).getString(),
                    k.getBoundKeyLocalizedText().getString()));
        }
        return out;
    }

    static List<Text> paginate(List<Rulebook.Block> blocks, TextRenderer font) {
        return document(blocks, font, true).pages();
    }

    /**
     * 同一套排法（扉页 · 章另起一页 · 小节不落在页底 · 折行），但不插目录、不记锚点：书柜里那几本书用（{@link ShelfBookScreen}）。
     */
    static List<Text> plainPages(List<Rulebook.Block> blocks, TextRenderer font) {
        return document(blocks, font, false).pages();
    }

    private record Document(List<Text> pages, Map<String, Integer> anchors) { }

    /** @param withContents 在扉页之后插目录（规则书）；书柜里的书不插 */
    private static Document document(List<Rulebook.Block> blocks, TextRenderer font, boolean withContents) {
        Pages pages = new Pages();
        Map<String, Integer> anchors = new java.util.LinkedHashMap<>();
        List<Map.Entry<Text, Integer>> chapters = new ArrayList<>();
        int afterTitle = 0;                   // 扉页之后那一页（目录插在这里）
        Rulebook.Block previous = null;
        for (Rulebook.Block block : blocks) {
            switch (block) {
                case Rulebook.TitlePage t -> {
                    pages.blank(3);
                    pages.addAll(centred(font, Text.literal(t.title()).setStyle(CHAPTER)));
                    pages.blank(1);
                    pages.addAll(centred(font, Text.literal(t.subtitle())));
                    pages.blank(3);
                    pages.addAll(wrap(font, Text.literal(t.motto()).setStyle(Style.EMPTY.withItalic(true)), WIDTH));
                    pages.newPage();
                    afterTitle = pages.done.size();
                }
                case Rulebook.Chapter c -> {
                    pages.newPage();
                    if (!c.anchor().isEmpty()) anchors.put(c.anchor(), pages.done.size());
                    chapters.add(Map.entry(Text.literal(c.title()), pages.done.size()));
                    pages.addAll(wrap(font, Text.literal(c.title()).setStyle(CHAPTER), WIDTH));
                    pages.blank(1);
                }
                case Rulebook.Heading h -> {
                    List<Text> lines = wrap(font, Text.literal(h.text()).setStyle(BOLD), WIDTH);
                    pages.gap();
                    // 小节标题不落在页底：连同它后面至少两行放不下，就另起一页
                    pages.keepTogether(lines.size() + 2);
                    if (!h.anchor().isEmpty()) anchors.put(h.anchor(), pages.done.size());
                    pages.addAll(lines);
                }
                case Rulebook.Para p -> {
                    if (!(previous instanceof Rulebook.Heading) && !(previous instanceof Rulebook.Chapter)) {
                        pages.gap();
                    }
                    pages.addAll(wrap(font, spans(p.spans()), WIDTH));
                }
                case Rulebook.Item i -> {
                    if (!(previous instanceof Rulebook.Item) && !(previous instanceof Rulebook.Heading)
                            && !(previous instanceof Rulebook.Chapter)) {
                        pages.gap();
                    }
                    String indent = "  ".repeat(i.depth());
                    String marker = indent + i.marker() + " ";
                    int hang = font.getWidth(marker);
                    List<Text> body = wrap(font, spans(i.spans()), WIDTH - hang);
                    // 向下取整：补白宁窄勿宽，宽了这一行会超出 114、被 BookScreen 再折一次，一页就多出一行而被截掉
                    String pad = " ".repeat(Math.max(1, hang / font.getWidth(" ")));
                    for (int n = 0; n < body.size(); n++) {
                        pages.add(Text.literal(n == 0 ? marker : pad).append(body.get(n)));
                    }
                }
            }
            previous = block;
        }
        // 目录放在扉页之后（2026-10-13 验收）：原先排在书尾，讲台默认打开落在最后一页，「上一页」一翻进的是最后一章。
        // 目录有几页先排一遍量出来（链接只差页码，不改折行），再按这个数把各章的页码往后挪。
        List<Text> body = new ArrayList<>(pages.finish());
        if (!withContents) {
            return new Document(List.copyOf(body), Map.of());
        }
        int insertAt = Math.min(afterTitle, body.size());
        int contentsPages = contents(font, chapters, insertAt, 0).size();
        List<Text> contents = contents(font, chapters, insertAt, contentsPages);
        Map<String, Integer> shifted = new java.util.LinkedHashMap<>();
        anchors.forEach((key, page) -> shifted.put(key, page >= insertAt ? page + contentsPages : page));
        shifted.put("contents", insertAt);
        body.addAll(insertAt, contents);
        return new Document(List.copyOf(body), Map.copyOf(shifted));
    }

    /**
     * 目录那几页：每一章一行，点了翻到那一章。
     *
     * @param insertAt 目录插在第几页（它之后的页码都要往后挪）
     * @param shift    往后挪几页（= 目录自己有几页；量页数的那一遍传 0）
     */
    private static List<Text> contents(TextRenderer font, List<Map.Entry<Text, Integer>> chapters, int insertAt, int shift) {
        Pages contents = new Pages();
        contents.addAll(wrap(font, Text.translatable("heavyseas.navigation.contents").setStyle(CHAPTER), WIDTH));
        for (var chapter : chapters) {
            contents.gap();
            int page = chapter.getValue() >= insertAt ? chapter.getValue() + shift : chapter.getValue();
            Text link = chapter.getKey().copy().styled(s -> s.withUnderline(true).withClickEvent(
                    new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.CHANGE_PAGE,
                            Integer.toString(page + 1))));
            contents.addAll(wrap(font, link, WIDTH));
        }
        return contents.finish();
    }

    /** The same native book as the lectern, with a live timer and an explicit route back. */
    private static final class NavigationBook extends BookScreen implements DecisionNavigation.Child {
        private final Document document;
        private final GameScreen parent;
        private final DecisionNavigation.Context origin;
        private final String anchor;
        private boolean opened;

        NavigationBook(Document document, GameScreen parent, String anchor) {
            super(new BookScreen.Contents(document.pages()));
            this.document = document;
            this.parent = parent;
            this.origin = parent == null ? null : parent.decisionContext();
            this.anchor = anchor;
        }

        @Override public DecisionNavigation.Context decisionContext() { return origin; }
        @Override public boolean shouldPause() { return false; }
        @Override protected void init() {
            super.init();
            if (!opened) {
                setPage(document.anchors().getOrDefault(anchor, document.anchors().get("contents")));
                opened = true;
            }
        }
        @Override protected void addCloseButton() {
            // 位置与大小照 BookScreen 自己那一排（196 起、两枚各 96 宽），画法换成对局各面的按钮（2026-10-13 验收：灰色按钮与纸板不搭）
            addDrawableChild(new BookButton(width / 2 - 100, 196, 96, 20, Text.translatable("heavyseas.navigation.contents"),
                    b -> setPage(document.anchors().get("contents"))));
            addDrawableChild(new BookButton(width / 2 + 4, 196, 96, 20, Text.translatable("heavyseas.navigation.return"),
                    b -> close()));
        }
        @Override public void close() { DecisionNavigation.back(parent, origin); }
        @Override protected void closeScreen() { close(); }
        @Override public boolean keyPressed(int key, int scan, int modifiers) {
            if (HeavySeasClient.actKey().matchesKey(key, scan)) {
                if (origin == null) {
                    client.setScreen(null);
                    HeavySeasClient.requestDecision(client);
                } else DecisionNavigation.back(null, origin);
                return true;
            }
            return super.keyPressed(key, scan, modifiers);
        }
        @Override public void render(net.minecraft.client.gui.DrawContext context, int x, int y, float delta) {
            super.render(context, x, y, delta);
            DecisionNavigation.drawBookPending(context, origin);
        }
    }

    /**
     * 书底下那两枚按钮：行为照 Minecraft 的按钮（键盘焦点 · 点击 · 朗读），只把画法换成对局各面那一枚
     * （{@link io.github.heavyseasmc.mod.ui.HudPart#BTN} 的搪瓷底，悬停或有焦点时换描金那一件；字照签上的字）。
     * 在物理像素里画，与各面的按钮同一套 {@link io.github.heavyseasmc.mod.ui.SheetLayout} 比例。
     */
    static final class BookButton extends ButtonWidget {
        /** 字号与左右留白（稿子像素，随 {@code SheetLayout.k} 缩放）。 */
        private static final double TEXT_PX = 17;
        private static final double PAD_X = 8;

        BookButton(int x, int y, int w, int h, Text message, PressAction onPress) {
            super(x, y, w, h, message, onPress, DEFAULT_NARRATION_SUPPLIER);
        }

        @Override
        protected void renderWidget(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta) {
            var window = MinecraftClient.getInstance().getWindow();
            int s = Math.max(1, (int) Math.round(window.getScaleFactor()));
            var l = io.github.heavyseasmc.mod.ui.SheetLayout.of(window.getFramebufferWidth(), window.getFramebufferHeight());
            context.getMatrices().push();
            context.getMatrices().scale(1f / s, 1f / s, 1f);
            int x = getX() * s;
            int y = getY() * s;
            int w = getWidth() * s;
            int h = getHeight() * s;
            GuiMaterial.hudPart(context, isSelected() ? io.github.heavyseasmc.mod.ui.HudPart.BTN_FOCUS
                    : io.github.heavyseasmc.mod.ui.HudPart.BTN, x, y, w, h, l.k());
            int px = l.len(TEXT_PX);
            int pad = l.len(PAD_X);
            GuiText.drawPx(context, getMessage().getString(), x + pad, y + (h - GuiText.linePxAt(px, false)) / 2,
                    w - 2 * pad, px, false, GuiLanguage.onTag(GuiLanguage.ink()), GuiText.Align.CENTER, 0);
            context.getMatrices().pop();
        }
    }

    private static MutableText spans(List<Rulebook.Span> spans) {
        MutableText out = Text.empty();
        for (Rulebook.Span s : spans) {
            out.append(Text.literal(s.text()).setStyle(s.bold() ? BOLD : Style.EMPTY));
        }
        return out;
    }

    /**
     * 按当前字体一个字一个字量宽，交给 {@link LineBreak} 断行（避头尾 · 数字不与后字拆开），再按原来的样式拼回每一行。
     * 原先用 Minecraft 自带的 {@code wrapLines}：它只认空格，中文里会断出「被指的人有 20」/「，算他同意。」/ 单独一行「。」
     * （2026-10-13 真机验收）。每行不超过 {@code width}，所以 {@code BookScreen} 不会再折一次。
     */
    private static List<Text> wrap(TextRenderer font, Text text, int width) {
        StringBuilder chars = new StringBuilder();
        List<Style> styles = new ArrayList<>();
        text.visit((style, piece) -> {
            chars.append(piece);
            for (int i = 0; i < piece.length(); i++) {
                styles.add(style);
            }
            return Optional.empty();
        }, Style.EMPTY);
        String all = chars.toString();
        List<Text> out = new ArrayList<>();
        int base = 0;
        for (String paragraph : all.split("\n", -1)) {
            float[] widths = new float[paragraph.length()];
            for (int i = 0; i < paragraph.length(); i++) {
                char c = paragraph.charAt(i);
                if (Character.isLowSurrogate(c)) {
                    continue;                 // 代理对的宽记在高位上
                }
                int end = Character.isHighSurrogate(c) && i + 1 < paragraph.length() ? i + 2 : i + 1;
                widths[i] = font.getTextHandler().getWidth(StringVisitable.styled(paragraph.substring(i, end), styles.get(base + i)));
            }
            List<LineBreak.Line> lines = LineBreak.wrap(paragraph, widths, width);
            if (lines.isEmpty()) {
                out.add(Text.empty());
            }
            for (LineBreak.Line line : lines) {
                MutableText t = Text.empty();
                int from = line.start();
                for (int i = line.start() + 1; i <= line.end(); i++) {
                    if (i == line.end() || !styles.get(base + i).equals(styles.get(base + from))) {
                        t.append(Text.literal(paragraph.substring(from, i)).setStyle(styles.get(base + from)));
                        from = i;
                    }
                }
                out.add(t);
            }
            base += paragraph.length() + 1;  // 跳过那个换行
        }
        return out;
    }

    private static List<Text> centred(TextRenderer font, Text text) {
        List<Text> out = new ArrayList<>();
        for (Text line : wrap(font, text, WIDTH)) {
            int spaces = Math.max(0, (WIDTH - font.getWidth(line)) / 2 / font.getWidth(" "));
            out.add(Text.literal(" ".repeat(spaces)).append(line));
        }
        return out;
    }

    /** 一页一页往下排。 */
    private static final class Pages {
        private final List<Text> done = new ArrayList<>();
        private final List<Text> page = new ArrayList<>();

        void add(Text line) {
            if (page.size() == LINES) {
                flush();
            }
            page.add(line);
        }

        void addAll(List<Text> lines) {
            lines.forEach(this::add);
        }

        /** 段与段之间空一行；页顶不空。 */
        void gap() {
            if (!page.isEmpty() && !page.getLast().getString().isEmpty()) {
                if (page.size() == LINES) {
                    flush();
                } else {
                    page.add(Text.empty());
                }
            }
        }

        void blank(int n) {
            for (int i = 0; i < n && page.size() < LINES; i++) {
                page.add(Text.empty());
            }
        }

        void keepTogether(int lines) {
            if (!page.isEmpty() && page.size() + lines > LINES) {
                flush();
            }
        }

        void newPage() {
            if (!page.isEmpty()) {
                flush();
            }
        }

        private void flush() {
            while (!page.isEmpty() && page.getLast().getString().isEmpty()) {
                page.removeLast();
            }
            MutableText text = Text.empty();
            for (int i = 0; i < page.size(); i++) {
                if (i > 0) {
                    text.append("\n");
                }
                text.append(page.get(i));
            }
            done.add(text);
            page.clear();
        }

        List<Text> finish() {
            newPage();
            return List.copyOf(done);
        }
    }
}
