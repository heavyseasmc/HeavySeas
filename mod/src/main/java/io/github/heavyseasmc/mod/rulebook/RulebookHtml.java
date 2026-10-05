package io.github.heavyseasmc.mod.rulebook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 规则书网页版：与游戏里那本是同一份源文件、同一串块（ADR-0087 §2「网页版同源」）。
 *
 * <p>{@code ./gradlew :mod:rulebookHtml} → {@code mod/build/rulebook/<语言>.html}。只生成 jar 里有源文件的语言。
 */
public final class RulebookHtml {

    /**
     * 附录里的按键：网页上只能写默认键。动作名取 lang；默认键与 {@code HeavySeasClient} 注册时给的一致
     * （R · G · L · F8；那边改了这里要跟着改 —— 游戏里那本书读的是玩家当前的绑定，不经过这张表）。
     */
    private static final List<String[]> DEFAULT_KEYS = List.of(
            new String[]{"key.heavyseas.hand", "R"},
            new String[]{"key.heavyseas.act", "G"},
            new String[]{"key.heavyseas.log", "L"},
            new String[]{"key.heavyseas.theme", "F8"});

    private RulebookHtml() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("用法：RulebookHtml <输出目录>");
        }
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        int written = 0;
        for (String lang : List.of("zh_cn", "en_us")) {
            var source = RulebookData.source(lang);
            if (source.isEmpty()) {
                System.out.println("规则书网页版：jar 里没有 " + lang + " 的源文件，跳过");
                continue;
            }
            Path file = out.resolve(lang + ".html");
            Files.writeString(file, render(lang, source.get()), StandardCharsets.UTF_8);
            System.out.println("规则书网页版：" + file);
            written++;
        }
        if (written == 0) {
            throw new IllegalStateException("一份都没生成");
        }
    }

    public static String render(String lang, String source) {
        Map<String, String> strings = RulebookData.langFile(lang);
        List<Rulebook.KeyLine> keys = DEFAULT_KEYS.stream()
                .map(k -> new Rulebook.KeyLine(require(strings, k[0]), k[1])).toList();
        List<Rulebook.Block> blocks = Rulebook.parse(source, RulebookData.facts(strings::get, keys));

        StringBuilder header = new StringBuilder();
        StringBuilder toc = new StringBuilder();
        StringBuilder body = new StringBuilder();
        String title = "";
        int chapter = 0;
        List<String> openList = new ArrayList<>();
        for (Rulebook.Block block : blocks) {
            if (!(block instanceof Rulebook.Item)) {
                closeLists(body, openList, 0);
            }
            switch (block) {
                case Rulebook.TitlePage t -> {
                    title = t.title();
                    header.append("<header><h1>").append(esc(t.title())).append("</h1><p class=\"sub\">")
                            .append(esc(t.subtitle())).append("</p><p class=\"motto\">").append(esc(t.motto()))
                            .append("</p></header>\n");
                }
                case Rulebook.Chapter c -> {
                    chapter++;
                    toc.append("<li><a href=\"#c").append(chapter).append("\">").append(esc(c.title())).append("</a></li>\n");
                    body.append("<h2 id=\"c").append(chapter).append("\">").append(esc(c.title())).append("</h2>\n");
                }
                case Rulebook.Heading h -> body.append("<h3>").append(esc(h.text())).append("</h3>\n");
                case Rulebook.Para p -> body.append("<p>").append(spans(p.spans())).append("</p>\n");
                case Rulebook.Item i -> {
                    String kind = i.marker().endsWith(".") ? "ol" : "ul";
                    closeLists(body, openList, i.depth() + 1);
                    while (openList.size() < i.depth() + 1) {
                        openList.add(kind);
                        body.append("<").append(kind).append(">\n");
                    }
                    if (!openList.getLast().equals(kind)) {
                        body.append("</").append(openList.removeLast()).append(">\n<").append(kind).append(">\n");
                        openList.add(kind);
                    }
                    String value = kind.equals("ol") ? " value=\"" + i.marker().replace(".", "") + "\"" : "";
                    body.append("<li").append(value).append(">").append(spans(i.spans())).append("</li>\n");
                }
            }
        }
        closeLists(body, openList, 0);
        return """
                <!doctype html>
                <html lang="%s"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%s</title>
                <style>
                :root{--paper:#f4ecd8;--ink:#2b2418;--rule:#b8a77f;--accent:#7a2e1d}
                @media (prefers-color-scheme:dark){:root{--paper:#1f1b14;--ink:#e8dfc8;--rule:#5c5140;--accent:#d98b6a}}
                body{margin:0;background:var(--paper);color:var(--ink);font:17px/1.75 "Songti SC","Noto Serif SC","Source Han Serif SC",Georgia,serif}
                main{max-width:42em;margin:0 auto;padding:2em 16px 5em}
                header{text-align:center;border-bottom:1px solid var(--rule);padding-bottom:1.5em;margin-bottom:1.5em}
                h1{font-size:2.2em;letter-spacing:.2em;margin:.4em 0}.sub{opacity:.75}.motto{font-style:italic}
                h2{color:var(--accent);border-top:1px solid var(--rule);padding-top:1em;margin-top:2.5em}
                h3{margin:1.6em 0 .4em}nav ol{columns:2;column-gap:2em}a{color:var(--accent)}
                ul,ol{padding-left:1.6em}li{margin:.2em 0}
                </style></head><body><main>
                %s<nav><ol>
                %s</ol></nav>
                %s</main></body></html>
                """.formatted(lang.replace('_', '-'), esc(title), header, toc, body);
    }

    private static void closeLists(StringBuilder body, List<String> open, int keep) {
        while (open.size() > keep) {
            body.append("</").append(open.removeLast()).append(">\n");
        }
    }

    private static String spans(List<Rulebook.Span> spans) {
        StringBuilder sb = new StringBuilder();
        for (Rulebook.Span s : spans) {
            sb.append(s.bold() ? "<b>" + esc(s.text()) + "</b>" : esc(s.text()));
        }
        return sb.toString();
    }

    private static String require(Map<String, String> strings, String key) {
        String v = strings.get(key);
        if (v == null) {
            throw new IllegalArgumentException("lang 里没有 " + key);
        }
        return v;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
