package io.github.heavyseasmc.mod.rulebook;

import io.github.heavyseasmc.engine.data.RosterData;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.weather.WeatherCard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 规则书《乘客须知》：源文件的解析与展开（ADR-0087 §2）。
 *
 * <p>源文件一种语言一份，{@code assets/heavyseas/rulebook/<语言>.md}，是书与网页版唯一的文字源头。
 * 第三章八人一览、第十二章物资、第十三章天候里的名字与数字由 {@code @} 指令从 {@code data/} 与 lang 展开 ——
 * 数据或 lang 一改，书跟着变，不手抄。
 *
 * <p>❗<b>不碰 Minecraft 的类</b>：客户端把块排成 {@code BookScreen} 的书页，网页版与单测在没有游戏的地方用同一串块。
 *
 * <h2>源文件的写法</h2>
 * <ul>
 *   <li>{@code # 标题} 一章（书里另起一页）· {@code ## 标题} 小节 · 其余每一行是一段；空行只为好读，不产生内容。</li>
 *   <li>{@code - } 列表 · 缩进两格的 {@code - } 是下一层 · {@code 1. } 编号；{@code **粗体**}。</li>
 *   <li>{@code @title} · {@code @subtitle} · {@code @motto} 扉页三行；
 *       {@code @presets} · {@code @roster <角色>} · {@code @provision-total} · {@code @provision <物资>} ·
 *       {@code @weather <天候>} · {@code @navstats} · {@code @keys} 从数据展开。</li>
 * </ul>
 * 认不出的指令、找不到的 id、没配对的 {@code **} 一律抛，带行号 —— 静默跳过一行与「这一行本来就没有」长得一样。
 */
public final class Rulebook {

    private Rulebook() {
    }

    /** 一段字里的一截：粗体或不粗。 */
    public record Span(String text, boolean bold) {
    }

    public sealed interface Block permits TitlePage, Chapter, Heading, Para, Item {
    }

    public record TitlePage(String title, String subtitle, String motto) implements Block {
    }

    public record Chapter(String title) implements Block {
    }

    public record Heading(String text) implements Block {
    }

    public record Para(List<Span> spans) implements Block {
        public Para {
            spans = List.copyOf(spans);
        }
    }

    /** 列表的一项。{@code marker} 是「·」「–」或「3.」这样的编号。 */
    public record Item(int depth, String marker, List<Span> spans) implements Block {
        public Item {
            spans = List.copyOf(spans);
        }
    }

    /** 一个按键：它做什么（已本地化）· 现在绑在哪个键上（已本地化）。 */
    public record KeyLine(String action, String key) {
    }

    /** 展开指令要用的东西。客户端从 jar 与 {@code I18n} 取，单测与网页版从类路径与 lang 文件取。 */
    public interface Facts {
        /** lang 里那一条，按 {@code %s} 填参数；没有这一条就抛。 */
        String text(String key, Object... args);

        RosterData roster();

        Provisions provisions();

        List<NavigationCard> navigation();

        List<WeatherCard> weather();

        List<KeyLine> keys();
    }

    private static final Pattern NUMBERED = Pattern.compile("^(\\d+)\\.\\s+(.*)$");
    private static final Pattern DIRECTIVE = Pattern.compile("^@([a-z-]+)(?:\\s+(.*))?$");

    public static List<Block> parse(String source, Facts facts) {
        List<Block> out = new ArrayList<>();
        String title = null;
        String subtitle = null;
        String motto = null;
        String[] lines = source.split("\n", -1);
        for (int n = 0; n < lines.length; n++) {
            String raw = lines[n].stripTrailing();
            int line = n + 1;
            if (raw.isEmpty()) {
                continue;
            }
            try {
                Matcher d = DIRECTIVE.matcher(raw);
                if (d.matches()) {
                    String name = d.group(1);
                    String arg = d.group(2) == null ? "" : d.group(2).strip();
                    switch (name) {
                        case "title" -> title = requireArg(name, arg);
                        case "subtitle" -> subtitle = requireArg(name, arg);
                        case "motto" -> motto = requireArg(name, arg);
                        default -> {
                            flushTitle(out, title, subtitle, motto);
                            title = subtitle = motto = null;
                            expand(name, arg, facts, out);
                        }
                    }
                    continue;
                }
                flushTitle(out, title, subtitle, motto);
                title = subtitle = motto = null;
                if (raw.startsWith("## ")) {
                    out.add(new Heading(raw.substring(3).strip()));
                } else if (raw.startsWith("# ")) {
                    out.add(new Chapter(raw.substring(2).strip()));
                } else {
                    int indent = raw.length() - raw.stripLeading().length();
                    String body = raw.stripLeading();
                    int depth = indent >= 2 ? 1 : 0;
                    Matcher num = NUMBERED.matcher(body);
                    if (body.startsWith("- ")) {
                        out.add(new Item(depth, depth == 0 ? "·" : "–", spans(body.substring(2).strip())));
                    } else if (num.matches()) {
                        out.add(new Item(depth, num.group(1) + ".", spans(num.group(2).strip())));
                    } else if (indent > 0) {
                        throw new IllegalArgumentException("缩进的行只能是列表项");
                    } else {
                        out.add(new Para(spans(body)));
                    }
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("规则书第 %d 行：%s（%s）".formatted(line, e.getMessage(), raw), e);
            }
        }
        flushTitle(out, title, subtitle, motto);
        if (out.isEmpty() || !(out.getFirst() instanceof TitlePage)) {
            throw new IllegalArgumentException("规则书要以 @title · @subtitle · @motto 扉页开头");
        }
        return List.copyOf(out);
    }

    private static String requireArg(String name, String arg) {
        if (arg.isEmpty()) {
            throw new IllegalArgumentException("@" + name + " 后面要有字");
        }
        return arg;
    }

    private static void flushTitle(List<Block> out, String title, String subtitle, String motto) {
        if (title == null && subtitle == null && motto == null) {
            return;
        }
        if (title == null || subtitle == null || motto == null) {
            throw new IllegalArgumentException("扉页要 @title · @subtitle · @motto 三行齐全");
        }
        out.add(new TitlePage(title, subtitle, motto));
    }

    private static void expand(String name, String arg, Facts facts, List<Block> out) {
        switch (name) {
            case "presets" -> {
                noArg(name, arg);
                for (Map.Entry<Integer, List<CharacterId>> preset : new TreeMap<>(facts.roster().presets()).entrySet()) {
                    String names = preset.getValue().stream()
                            .map(id -> facts.text("heavyseas.character." + id.value()))
                            .collect(Collectors.joining(" · "));
                    out.add(new Item(0, "·", plain(facts.text("heavyseas.rulebook.preset", preset.getKey(), names))));
                }
            }
            case "roster" -> {
                Survivor s = facts.roster().characters().stream()
                        .filter(c -> c.id().value().equals(arg)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("data/roster 里没有角色 " + arg));
                out.add(new Para(List.of(
                        new Span(facts.text("heavyseas.character." + arg), true),
                        new Span("　" + facts.text("heavyseas.rulebook.roster", s.seat(), s.size(), s.survival()), false))));
            }
            case "provision-total" -> {
                noArg(name, arg);
                int total = facts.provisions().all().stream().mapToInt(Provision::count).sum();
                out.add(new Para(plain(facts.text("heavyseas.rulebook.provision_total", total))));
            }
            case "provision" -> {
                Provision p = facts.provisions().all().stream()
                        .filter(c -> c.id().equals(arg)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("data/provisions 里没有物资 " + arg));
                String category = facts.text("heavyseas.category." + p.category().name().toLowerCase(Locale.ROOT));
                out.add(new Para(List.of(
                        new Span(facts.text("heavyseas.provision." + arg), true),
                        new Span("　" + facts.text("heavyseas.rulebook.provision", p.count(), category), false))));
                out.add(new Para(plain(facts.text("heavyseas.rulebook.card_text",
                        facts.text("heavyseas.provision." + arg + ".effect")))));
            }
            case "weather" -> {
                facts.weather().stream().filter(c -> c.id().equals(arg)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("data/weather 里没有天候 " + arg));
                out.add(new Para(List.of(new Span(facts.text("heavyseas.weather." + arg), true))));
                out.add(new Para(plain(facts.text("heavyseas.rulebook.card_text",
                        facts.text("heavyseas.weather.effect." + arg)))));
            }
            case "navstats" -> {
                noArg(name, arg);
                List<NavigationCard> deck = facts.navigation();
                long up = deck.stream().filter(c -> c.gull() > 0).count();
                long down = deck.stream().filter(c -> c.gull() < 0).count();
                long none = deck.stream().filter(c -> c.gull() == 0).count();
                long oar = deck.stream().filter(NavigationCard::thirstRowers).count();
                long fight = deck.stream().filter(NavigationCard::thirstFighters).count();
                out.add(new Para(plain(facts.text("heavyseas.rulebook.navstats",
                        deck.size(), up, down, none, oar, fight))));
            }
            case "keys" -> {
                noArg(name, arg);
                List<KeyLine> keys = facts.keys();
                if (keys.isEmpty()) {
                    throw new IllegalArgumentException("@keys 一个按键都没拿到");
                }
                for (KeyLine k : keys) {
                    out.add(new Item(0, "·", plain(facts.text("heavyseas.rulebook.key", k.action(), k.key()))));
                }
            }
            default -> throw new IllegalArgumentException("认不出的指令 @" + name);
        }
    }

    private static void noArg(String name, String arg) {
        if (!arg.isEmpty()) {
            throw new IllegalArgumentException("@" + name + " 不带参数");
        }
    }

    private static List<Span> plain(String text) {
        return List.of(new Span(text, false));
    }

    /** {@code **粗体**} 切成几截。 */
    static List<Span> spans(String text) {
        String[] parts = text.split("\\*\\*", -1);
        if (parts.length % 2 == 0) {
            throw new IllegalArgumentException("** 没有配对");
        }
        List<Span> out = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                out.add(new Span(parts[i], i % 2 == 1));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("空的一段");
        }
        return out;
    }

    /** 整本书里出现过的 {@code @指令 id}（单测核对「每张牌、每个人都写到了」用）。 */
    public static List<String> directiveIds(String source, String directive) {
        List<String> ids = new ArrayList<>();
        for (String raw : source.split("\n")) {
            Matcher d = DIRECTIVE.matcher(raw.strip());
            if (d.matches() && d.group(1).equals(directive) && d.group(2) != null) {
                ids.add(d.group(2).strip());
            }
        }
        return ids;
    }
}
