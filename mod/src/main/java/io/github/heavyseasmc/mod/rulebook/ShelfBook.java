package io.github.heavyseasmc.mod.rulebook;

import io.github.heavyseasmc.engine.data.RosterData;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.weather.WeatherCard;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 书柜右键翻开的那一本书（ADR-0109 ③）：与讲台上的规则书<b>分开</b> —— 不同的书、不同的钩子。
 *
 * <p>书是 1912 年邮轮图书室 / 客舱里放的那种印刷品，不讲规则。文字放在资源里，一本书一个目录：
 * {@code assets/heavyseas/books/<书的 id>/<语言>.md}，写法与规则书的源文件相同（{@link Rulebook#parse} 那一套：
 * {@code @title · @subtitle · @motto} 扉页、{@code #} 章另起一页、{@code ##} 小节、{@code **粗体**}），
 * 只是<b>不许用从数据展开的指令</b>（{@code @roster} 这类）—— 用了就抛，带行号。
 *
 * <p>书柜里放哪一本只看 {@link #SHELVED}：换一份 = 改这一个 id。书只有英文那一份时，中文客户端翻到的也是英文
 * （1912 年英国邮轮图书室里的书）；哪天加了某种语言的那一份，那种语言的客户端就翻到它（{@link #pick}）。
 *
 * <p>钩子同 {@link RulebookView}：书柜在 {@code main} 里，书页在 {@code client} 里；{@code main} 里的类绝不引用客户端类
 * （专用服务端一加载就崩，ADR-0013）。客户端初始化时用 {@link #install} 把「开这本书」塞进来，书柜在客户端那一侧的
 * {@code onUse} 里调 {@link #open}。专用服务端上从来没人登记 —— {@link #open} 什么都不做、不抛，返回 {@code false}。
 */
public final class ShelfBook {

    /** 书柜里放的那一本。内容还没定（2026-10-17 用户「明早挑」）：先放倾向的那一份，候选都在 {@code books/} 底下。 */
    public static final String SHELVED = "ship_gazette";

    /** 书都有的那一种语言：客户端语言没有那一份就用它。 */
    public static final String BASE_LANGUAGE = "en_us";

    private static final Pattern ID = Pattern.compile("[a-z0-9_]+");

    private static volatile Consumer<String> opener;

    private ShelfBook() {
    }

    /** 那本书那种语言的源文件；jar 里没有就是空。 */
    public static Optional<String> source(String id, String language) {
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("书的 id 只能是小写字母、数字与下划线：" + id);
        }
        String path = "/assets/heavyseas/books/" + id + "/" + language.toLowerCase(Locale.ROOT) + ".md";
        try (InputStream in = ShelfBook.class.getResourceAsStream(path)) {
            return in == null ? Optional.empty() : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 按客户端语言挑一份：先要那一种，没有就用 {@link #BASE_LANGUAGE}。返回实际用的语言与正文 —— 调用方要报出来
     * （退回与没退回在书页上只差在字是什么语言）。jar 里连英文那一份都没有就抛：静默开一本空书与「没这本书」看不出差别。
     */
    public static Map.Entry<String, String> pick(String id, String language) {
        for (String lang : List.of(language, BASE_LANGUAGE)) {
            Optional<String> text = source(id, lang);
            if (text.isPresent()) {
                return Map.entry(lang, text.get());
            }
        }
        throw new IllegalStateException("jar 里没有书 " + id + "（assets/heavyseas/books/" + id + "/" + BASE_LANGUAGE + ".md）");
    }

    /** 解析成规则书那一串块（扉页 · 章 · 小节 · 段 · 列表）。用了从数据展开的指令就抛。 */
    public static List<Rulebook.Block> parse(String source) {
        return Rulebook.parse(source, NO_DATA);
    }

    /** 客户端初始化时调一次：参数是书的 id。传 {@code null} = 撤掉（单测收尾用）。 */
    public static void install(Consumer<String> o) {
        opener = o;
    }

    /** 开书柜里那一本（{@link #SHELVED}）。@return 开了没有：没登记（服务端、单测）时是 {@code false} */
    public static boolean open() {
        Consumer<String> o = opener;
        if (o == null) {
            return false;
        }
        o.accept(SHELVED);
        return true;
    }

    /** 书柜里的书不从数据展开：任何一条展开指令一用就抛（{@link Rulebook#parse} 会带上行号）。 */
    private static final Rulebook.Facts NO_DATA = new Rulebook.Facts() {
        private IllegalArgumentException refuse() {
            return new IllegalArgumentException("书柜里的书不从数据展开（这类指令只给规则书用）");
        }

        @Override
        public String text(String key, Object... args) {
            throw refuse();
        }

        @Override
        public RosterData roster() {
            throw refuse();
        }

        @Override
        public Provisions provisions() {
            throw refuse();
        }

        @Override
        public List<NavigationCard> navigation() {
            throw refuse();
        }

        @Override
        public List<WeatherCard> weather() {
            throw refuse();
        }

        @Override
        public List<Rulebook.KeyLine> keys() {
            throw refuse();
        }
    };
}
