package io.github.heavyseasmc.mod.rulebook;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.heavyseasmc.engine.data.NavigationLoader;
import io.github.heavyseasmc.engine.data.ProvisionLoader;
import io.github.heavyseasmc.engine.data.RosterData;
import io.github.heavyseasmc.engine.data.RosterLoader;
import io.github.heavyseasmc.engine.data.WeatherLoader;
import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.weather.WeatherCard;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 规则书的源文件与它要展开的数据，一律从<b>类路径</b>读 —— 也就是模组 jar 里的那一份
 * （{@code data/heavyseas/*} 由构建从仓库根的 {@code data/} 搬进来，{@code assets/heavyseas/rulebook/*.md} 是书的源文件）。
 *
 * <p>书讲的是这个模组本身的规则，所以读 jar 里的内置数据，不读服务端可能改过的数据包；客户端没连服务端也能翻。
 */
public final class RulebookData {

    /** 源文件的语言：书按客户端语言挑，没有那一种就用这几种（依次）。 */
    public static final List<String> FALLBACK_LANGUAGES = List.of("en_us", "zh_cn");

    private RulebookData() {
    }

    /** 那种语言的源文件；jar 里没有就是空。 */
    public static Optional<String> source(String language) {
        return read("/assets/heavyseas/rulebook/" + language.toLowerCase(Locale.ROOT) + ".md");
    }

    /**
     * 按客户端语言挑一份源文件：先要那一种，没有就依次退到 {@link #FALLBACK_LANGUAGES}。
     * 返回实际用的语言与正文 —— 调用方要把「用的是哪一份」报出来，退回与没退回在书页上看不出来。
     */
    public static Map.Entry<String, String> pick(String language) {
        for (String lang : java.util.stream.Stream.concat(java.util.stream.Stream.of(language), FALLBACK_LANGUAGES.stream()).toList()) {
            Optional<String> text = source(lang);
            if (text.isPresent()) {
                return Map.entry(lang, text.get());
            }
        }
        throw new IllegalStateException("jar 里一份规则书源文件都没有（assets/heavyseas/rulebook/*.md）");
    }

    /**
     * 用类路径上的数据与给定的 lang 查表拼出展开所需的一切。
     *
     * @param lang 按键查 lang 模板；没有这一条返回 {@code null}（→ 展开时抛，带键名）
     * @param keys 附录里列的按键（客户端读当前绑定；网页版用默认键）
     */
    public static Rulebook.Facts facts(Function<String, String> lang, List<Rulebook.KeyLine> keys) {
        RosterData roster = load("data/heavyseas/roster/default.json", r -> RosterLoader.load("roster", r));
        Provisions provisions = load("data/heavyseas/provisions/default.json",
                r -> ProvisionLoader.loadCatalog("provisions", r).value());
        Set<String> provisionIds = provisions.all().stream().map(Provision::id).collect(Collectors.toSet());
        List<NavigationCard> navigation = load("data/heavyseas/navigation/default.json",
                r -> NavigationLoader.load("navigation", r, roster.ids(), provisionIds));
        List<WeatherCard> weather = load("data/heavyseas/weather/default.json", r -> WeatherLoader.load("weather", r));
        List<Rulebook.KeyLine> keyLines = List.copyOf(keys);
        return new Rulebook.Facts() {
            @Override
            public String text(String key, Object... args) {
                String template = lang.apply(key);
                if (template == null) {
                    throw new IllegalArgumentException("lang 里没有 " + key);
                }
                return args.length == 0 ? template : String.format(Locale.ROOT, template, args);
            }

            @Override
            public RosterData roster() {
                return roster;
            }

            @Override
            public Provisions provisions() {
                return provisions;
            }

            @Override
            public List<NavigationCard> navigation() {
                return navigation;
            }

            @Override
            public List<WeatherCard> weather() {
                return weather;
            }

            @Override
            public List<Rulebook.KeyLine> keys() {
                return keyLines;
            }
        };
    }

    /** jar 里那份 lang（网页版与单测用；客户端走 {@code I18n}，资源包改过的字也算）。 */
    public static Map<String, String> langFile(String language) {
        String json = read("/assets/heavyseas/lang/" + language + ".json")
                .orElseThrow(() -> new IllegalStateException("jar 里没有 lang/" + language + ".json"));
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, String> out = new LinkedHashMap<>();
        root.entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsString()));
        return out;
    }

    private interface ReaderFunction<T> {
        T apply(Reader reader);
    }

    private static <T> T load(String path, ReaderFunction<T> parse) {
        try (InputStream in = RulebookData.class.getResourceAsStream("/" + path)) {
            if (in == null) {
                throw new IllegalStateException("类路径上没有 " + path + " —— 规则书展开不了第三、十二、十三章");
            }
            return parse.apply(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Optional<String> read(String path) {
        try (InputStream in = RulebookData.class.getResourceAsStream(path)) {
            return in == null ? Optional.empty() : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
