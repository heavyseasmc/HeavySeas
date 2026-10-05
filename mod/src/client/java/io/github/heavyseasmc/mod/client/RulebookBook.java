package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.rulebook.Rulebook;
import io.github.heavyseasmc.mod.rulebook.RulebookData;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.ingame.BookScreen;
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
        try {
            show(client);
        } catch (RuntimeException e) {
            // 讲台右键在渲染线程上：抛出去整个客户端就崩了。记下原因、在聊天栏说一句 —— 不静默，也不为一本书崩游戏
            LOGGER.error("规则书打不开", e);
            if (client.player != null) {
                client.player.sendMessage(Text.translatable("heavyseas.rulebook.failed", String.valueOf(e.getMessage())), false);
            }
        }
    }

    private static void show(MinecraftClient client) {
        String language = client.getLanguageManager().getLanguage();
        Map.Entry<String, String> picked = RulebookData.pick(language);
        Language lang = Language.getInstance();
        Rulebook.Facts facts = RulebookData.facts(key -> lang.hasTranslation(key) ? lang.get(key) : null, keys());
        List<Text> pages = paginate(Rulebook.parse(picked.getValue(), facts), client.textRenderer);
        // 报出用的是哪一份：退回到另一种语言与没退回，在书页上只差在字是什么语言
        LOGGER.info("规则书：客户端语言 {} · 用的是 {} 那一份 · {} 页", language, picked.getKey(), pages.size());
        client.setScreen(new BookScreen(new BookScreen.Contents(pages)));
    }

    /** 附录里的按键：读玩家现在的绑定，不是默认键。 */
    private static List<Rulebook.KeyLine> keys() {
        List<Rulebook.KeyLine> out = new ArrayList<>();
        for (KeyBinding k : List.of(HeavySeasClient.handKey(), HeavySeasClient.actKey(),
                HeavySeasClient.logKey(), HeavySeasClient.themeKey())) {
            out.add(new Rulebook.KeyLine(Text.translatable(k.getTranslationKey()).getString(),
                    k.getBoundKeyLocalizedText().getString()));
        }
        return out;
    }

    static List<Text> paginate(List<Rulebook.Block> blocks, TextRenderer font) {
        Pages pages = new Pages();
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
                }
                case Rulebook.Chapter c -> {
                    pages.newPage();
                    pages.addAll(wrap(font, Text.literal(c.title()).setStyle(CHAPTER), WIDTH));
                    pages.blank(1);
                }
                case Rulebook.Heading h -> {
                    List<Text> lines = wrap(font, Text.literal(h.text()).setStyle(BOLD), WIDTH);
                    pages.gap();
                    // 小节标题不落在页底：连同它后面至少两行放不下，就另起一页
                    pages.keepTogether(lines.size() + 2);
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
        return pages.finish();
    }

    private static MutableText spans(List<Rulebook.Span> spans) {
        MutableText out = Text.empty();
        for (Rulebook.Span s : spans) {
            out.append(Text.literal(s.text()).setStyle(s.bold() ? BOLD : Style.EMPTY));
        }
        return out;
    }

    private static List<Text> wrap(TextRenderer font, Text text, int width) {
        List<Text> out = new ArrayList<>();
        for (StringVisitable line : font.getTextHandler().wrapLines(text, width, Style.EMPTY)) {
            MutableText t = Text.empty();
            line.visit((style, piece) -> {
                t.append(Text.literal(piece).setStyle(style));
                return Optional.empty();
            }, Style.EMPTY);
            out.add(t);
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
