package io.github.heavyseasmc.mod.llm;

import io.github.heavyseasmc.mod.rulebook.Rulebook;
import io.github.heavyseasmc.mod.rulebook.RulebookData;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 给大模型看的规则摘录：从<b>我们自己的规则书</b>（jar 里的 {@code assets/heavyseas/rulebook/<语言>.md}）按决定的种类挑几节。
 *
 * <p>❗clean-room：提示词里的规则只许来自这本书 —— 与书页、网页版同一个源头、同一套展开（{@link Rulebook#parse}），
 * 第三、十二、十三章的名字与数字照样从 {@code data/} 与 lang 展开。不另抄一份规则。
 *
 * <h2>按什么挑</h2>
 * 一节 = 第几章（从 1 数，与书里「第 N 章」一致）+ <b>中文</b>小节标题；标题为 {@code null} 是整章。
 * 英文那本按<b>位置</b>对过去：先在中文书里找到这个标题是第几章的第几节，再取英文书同一章的同一节 ——
 * 两本书同构由 {@code RulebookTest#englishMirrorsChinese} 守着，这里再按用到的每一章逐章核一遍小节数。
 * 找不到、对不上一律抛，带章号与标题：表过期了与「这一节本来就是空的」在提示词里长得一样。
 *
 * <p>每一种决定都带 {@link #CORE}（这一局要干什么 · 分怎么算 · 心里的两个人），再加它自己那几节；
 * 拼出来按书里的次序排、去重，并且不超过 {@link #maxChars}（超了抛 —— 要么删节，要么改上限，不悄悄截断）。
 */
public final class RulebookExcerpts {

    /** 一节：第几章（从 1 数）+ 中文小节标题；标题为 {@code null} 表示整章。 */
    public record Section(int chapter, String heading) {
    }

    private static Section s(int chapter, String heading) {
        return new Section(chapter, heading);
    }

    private static Section whole(int chapter) {
        return new Section(chapter, null);
    }

    /** 每一种决定都带的几节。 */
    public static final List<Section> CORE = List.of(
            s(1, "一局里你要做的事"), s(1, "活下来不等于赢"), whole(4), s(11, "计分"));

    /** 各种决定自己要读的那几节（拼的时候再按书里的次序排）。 */
    public static final Map<DecisionKind, List<Section>> SECTIONS = sections();

    private static Map<DecisionKind, List<Section>> sections() {
        Map<DecisionKind, List<Section>> m = new EnumMap<>(DecisionKind.class);
        m.put(DecisionKind.PROVISION, List.of(s(5, "手里和面前"), s(6, "补给箱"), s(6, "牌堆抽完就没了"), whole(12)));
        m.put(DecisionKind.ACTION, List.of(whole(7), s(8, "什么时候会打起来"), s(8, "谁赢")));
        m.put(DecisionKind.USE_PROVISION, List.of(s(7, "用物资"), s(10, "治疗"),
                s(12, "消耗品"), s(12, "装备"), s(12, "武器")));
        m.put(DecisionKind.ROW, List.of(s(7, "划船"), s(9, "挑一张航海牌"), s(9, "一张航海牌上有什么"), s(9, "海鸥"),
                s(9, "落海"), s(9, "口渴"), s(14, "两个图示"), s(14, "看一张牌")));
        m.put(DecisionKind.TARGET, List.of(s(3, "体型和生存分"), s(5, "别人看得见你什么"), s(7, "换座位"), s(7, "抢"),
                s(7, "指人"), s(8, "什么时候会打起来"), s(8, "谁赢")));
        m.put(DecisionKind.CONTEST_ANSWER, List.of(s(7, "换座位"), s(7, "抢"), s(8, "什么时候会打起来"), s(8, "先表态"),
                s(8, "站队"), s(8, "押武器"), s(8, "谁赢")));
        m.put(DecisionKind.CONTEST_JOIN, List.of(s(3, "体型和生存分"), s(8, "站队"), s(8, "押武器"), s(8, "谁赢")));
        m.put(DecisionKind.CONTEST_WEAPON, List.of(s(8, "押武器"), s(8, "谁赢"), s(12, "武器")));
        m.put(DecisionKind.STEAL_PICK, List.of(s(5, "手里和面前"), s(7, "抢"), s(12, "财宝")));
        m.put(DecisionKind.HELM, List.of(s(9, "舵手"), s(9, "挑一张航海牌"), s(9, "一张航海牌上有什么"), s(9, "海鸥"),
                s(9, "落海"), s(9, "口渴"), s(9, "天候会改的地方"), s(14, "看一张牌")));
        m.put(DecisionKind.OVERBOARD, List.of(s(9, "落海"), s(12, "消耗品"), s(12, "装备")));
        m.put(DecisionKind.THIRST, List.of(s(9, "口渴"), s(10, "三种状态"), s(12, "消耗品")));
        m.put(DecisionKind.GIVE_WATER, List.of(s(9, "口渴"), s(10, "三种状态"), s(12, "消耗品")));
        m.put(DecisionKind.FREE_ACTION, List.of(s(5, "手里和面前"), s(5, "送牌"), s(7, "不占行动的三件事"), s(12, "装备")));
        m.put(DecisionKind.OTHER, List.of(s(5, "四个阶段")));
        if (m.size() != DecisionKind.values().length) {
            throw new IllegalStateException("规则摘录：有的决定种类没有登记要读哪几节");
        }
        return Map.copyOf(m);
    }

    /**
     * 一份摘录最多多少字。按语言分开：同样的规则，英文的字符数约是中文的三倍，token 数却差不多。
     * 上限只防「哪天有人把整本书挂上去」，正常的摘录离它很远（单测打印实际字数）。
     */
    public static int maxChars(String language) {
        return language.equals("en_us") ? 14_000 : 5_000;
    }

    private final String language;
    private final String source;
    private final Map<DecisionKind, String> excerpts;

    private RulebookExcerpts(String language, String source, Map<DecisionKind, String> excerpts) {
        this.language = language;
        this.source = source;
        this.excerpts = excerpts;
    }

    public String language() {
        return language;
    }

    /** 这一种决定的规则摘录（已渲染成带 {@code #} 标题的纯文本）。 */
    public String excerpt(DecisionKind kind) {
        return excerpts.get(kind);
    }

    /**
     * 某个角色在规则书第三章里的本事（{@code @roster <id>} 底下那几行）。调用方拼「这一座的局面」时用得上 ——
     * 替身是谁、有什么本事，书里写着，别在别处另抄一份。找不到是空表。
     */
    public List<String> characterNotes(String characterId) {
        return Rulebook.notesUnder(source, "roster", characterId);
    }

    /**
     * 读这种语言的规则书，把每一种决定的摘录都拼好（服务启动时调一次，之后只读）。
     *
     * @throws IllegalStateException jar 里没有这本书、表里的某一节找不到、中英对不上、或者摘录超了上限 —— 带原因
     */
    public static RulebookExcerpts load(String language) {
        return load(language, SECTIONS);
    }

    /** 单测用：换一张节表（造一张过期的表，核对它真的会抛）。 */
    static RulebookExcerpts load(String language, Map<DecisionKind, List<Section>> sections) {
        String zhSource = RulebookData.source("zh_cn")
                .orElseThrow(() -> new IllegalStateException("jar 里没有中文规则书（rulebook/zh_cn.md）—— 规则摘录的节是按它找的"));
        String source = language.equals("zh_cn") ? zhSource : RulebookData.source(language)
                .orElseThrow(() -> new IllegalStateException("jar 里没有 rulebook/" + language + ".md"));
        Outline zh = Outline.of(parse("zh_cn", zhSource));
        Outline target = language.equals("zh_cn") ? zh : Outline.of(parse(language, source));

        Map<DecisionKind, String> out = new EnumMap<>(DecisionKind.class);
        for (DecisionKind kind : DecisionKind.values()) {
            List<Section> wanted = new ArrayList<>(CORE);
            List<Section> own = sections.get(kind);
            if (own == null) {
                throw new IllegalStateException("规则摘录：" + kind + " 没有登记要读哪几节");
            }
            wanted.addAll(own);
            String text = render(zh, target, wanted, language);
            if (text.isBlank()) {
                throw new IllegalStateException("规则摘录：" + kind + " 拼出来是空的");
            }
            if (text.length() > maxChars(language)) {
                throw new IllegalStateException("规则摘录：" + kind + "（" + language + "）有 " + text.length()
                        + " 字，超过上限 " + maxChars(language) + " —— 删几节，或者改 maxChars");
            }
            out.put(kind, text);
        }
        return new RulebookExcerpts(language, source, Map.copyOf(out));
    }

    private static List<Rulebook.Block> parse(String language, String source) {
        Map<String, String> strings = RulebookData.langFile(language);
        // 附录那一章（@keys）要至少一个按键才展开得了；摘录从来不取附录，给一个占位即可
        List<Rulebook.KeyLine> keys = List.of(new Rulebook.KeyLine("-", "-"));
        return Rulebook.parse(source, RulebookData.facts(strings::get, keys));
    }

    /** 挑出来的节按书里的次序排、去重；整章挑了，同一章的单节就不再重复。 */
    private static String render(Outline zh, Outline target, List<Section> wanted, String language) {
        TreeMap<Integer, TreeSet<Integer>> picked = new TreeMap<>();
        for (Section section : wanted) {
            int c = section.chapter();
            if (c < 1 || c > zh.chapters().size()) {
                throw new IllegalStateException("规则摘录：没有第 " + c + " 章（中文书共 " + zh.chapters().size() + " 章）");
            }
            Chapter zc = zh.chapters().get(c - 1);
            if (c > target.chapters().size()) {
                throw new IllegalStateException("规则摘录：" + language + " 的书没有第 " + c + " 章");
            }
            Chapter tc = target.chapters().get(c - 1);
            if (zc.parts().size() != tc.parts().size()) {
                throw new IllegalStateException("规则摘录：第 " + c + " 章中文有 " + zc.parts().size() + " 节、" + language
                        + " 有 " + tc.parts().size() + " 节 —— 两本书不同构，按位置对过去会对错");
            }
            TreeSet<Integer> parts = picked.computeIfAbsent(c, k -> new TreeSet<>());
            if (section.heading() == null) {
                parts.add(-1);
                continue;
            }
            int index = -1;
            for (int i = 0; i < zc.parts().size(); i++) {
                if (zc.parts().get(i).heading().equals(section.heading())) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                throw new IllegalStateException("规则摘录：中文规则书第 " + c + " 章「" + zc.title() + "」里没有小节「"
                        + section.heading() + "」—— RulebookExcerpts.SECTIONS 那张表过期了");
            }
            parts.add(index);
        }

        StringBuilder sb = new StringBuilder();
        picked.forEach((c, parts) -> {
            Chapter chapter = target.chapters().get(c - 1);
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("# ").append(chapter.title()).append('\n');
            if (parts.contains(-1)) {
                chapter.intro().forEach(b -> line(sb, b));
                for (Part part : chapter.parts()) {
                    part(sb, part);
                }
            } else {
                for (int i : parts) {
                    part(sb, chapter.parts().get(i));
                }
            }
        });
        return sb.toString().strip();
    }

    private static void part(StringBuilder sb, Part part) {
        sb.append("## ").append(part.heading()).append('\n');
        part.blocks().forEach(b -> line(sb, b));
    }

    private static void line(StringBuilder sb, Rulebook.Block block) {
        switch (block) {
            case Rulebook.Para p -> sb.append(spans(p.spans())).append('\n');
            case Rulebook.Item i -> {
                String indent = i.depth() > 0 ? "  " : "";
                String marker = i.marker().endsWith(".") ? i.marker() : "-";
                sb.append(indent).append(marker).append(' ').append(spans(i.spans())).append('\n');
            }
            default -> {
                // 章与小节由外层排；扉页不进摘录
            }
        }
    }

    private static String spans(List<Rulebook.Span> spans) {
        StringBuilder sb = new StringBuilder();
        for (Rulebook.Span span : spans) {
            sb.append(span.bold() ? "**" + span.text() + "**" : span.text());
        }
        return sb.toString();
    }

    // ---- 书的骨架：章 → 章首的几段 + 各小节 ----

    private record Part(String heading, List<Rulebook.Block> blocks) {
    }

    private record Chapter(String title, List<Rulebook.Block> intro, List<Part> parts) {
    }

    private record Outline(List<Chapter> chapters) {

        static Outline of(List<Rulebook.Block> blocks) {
            List<Chapter> chapters = new ArrayList<>();
            List<Rulebook.Block> current = null;
            List<Part> parts = null;
            for (Rulebook.Block block : blocks) {
                switch (block) {
                    case Rulebook.TitlePage t -> {
                        // 扉页不属于任何一章
                    }
                    case Rulebook.Chapter c -> {
                        current = new ArrayList<>();
                        parts = new ArrayList<>();
                        chapters.add(new Chapter(c.title(), current, parts));
                    }
                    case Rulebook.Heading h -> {
                        if (parts == null) {
                            throw new IllegalStateException("规则书在第一章之前就有小节「" + h.text() + "」");
                        }
                        current = new ArrayList<>();
                        parts.add(new Part(h.text(), current));
                    }
                    default -> {
                        if (current == null) {
                            throw new IllegalStateException("规则书在第一章之前就有正文");
                        }
                        current.add(block);
                    }
                }
            }
            return new Outline(List.copyOf(chapters));
        }
    }
}
