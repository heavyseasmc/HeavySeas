package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.rulebook.Rulebook;
import io.github.heavyseasmc.mod.rulebook.RulebookData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则书自家书页的排版（{@link RulebookLayout}，2026-10-19）：拿整本真书（两种语言）、等宽的假字量，查四件 ——
 * 每一页不超出页框 · 一个字都不丢、不重、不乱序 · 每一章从页顶起 · 小节标题不落在页底；锚点都指着写着它的那一页。
 * 两种页框（小窗与大窗）各排一遍：排法与窗口无关的性质要在两种尺寸下都成立。
 */
final class RulebookLayoutTest {

    /** 等宽假字：每个字宽 = 字号的一半（中文按整字号），一行高 = 字号 × 1.4 向上取整。 */
    private static final RulebookLayout.Metrics FAKE = new RulebookLayout.Metrics() {
        private int w(char c, int px) {
            return c < 0x2E80 ? (px + 1) / 2 : px;
        }

        @Override
        public List<int[]> wrap(String text, boolean[] bold, int boxPx, int basePx) {
            List<int[]> out = new ArrayList<>();
            int start = 0;
            int used = 0;
            for (int i = 0; i < text.length(); i++) {
                int cw = w(text.charAt(i), basePx);
                if (used + cw > boxPx && i > start) {
                    out.add(new int[]{start, i});
                    start = i;
                    used = 0;
                }
                used += cw;
            }
            out.add(new int[]{start, text.length()});
            return out;
        }

        @Override
        public int lineStep(int basePx) {
            return (int) Math.ceil(basePx * 1.4);
        }

        @Override
        public int width(String text, int basePx, boolean bold) {
            int sum = 0;
            for (int i = 0; i < text.length(); i++) {
                sum += w(text.charAt(i), basePx);
            }
            return sum;
        }
    };

    private static List<Rulebook.Block> book(String lang) {
        Map<String, String> strings = RulebookData.langFile(lang);
        Rulebook.Facts facts = RulebookData.facts(strings::get, List.of(new Rulebook.KeyLine("查看手牌", "R")));
        return Rulebook.parse(RulebookData.source(lang).orElseThrow(), facts);
    }

    @ParameterizedTest(name = "{0} · 页框 {1}×{2}")
    @CsvSource({"zh_cn, 420, 520", "zh_cn, 900, 1100", "en_us, 420, 520", "en_us, 900, 1100"})
    @DisplayName("每页不超框 · 字不丢不重 · 章从页顶起 · 小节标题不落页底 · 锚点指对页")
    void laysOutTheWholeBook(String lang, int box, int height) {
        List<Rulebook.Block> blocks = book(lang);
        RulebookLayout.Sizes sizes = new RulebookLayout.Sizes(32, 26, 19, 17);
        RulebookLayout.Result r = RulebookLayout.layout(blocks, box, height, sizes, FAKE);
        List<String> problems = new ArrayList<>();
        // ① 不超框
        for (int i = 0; i < r.pages().size(); i++) {
            int used = r.pages().get(i).lines().stream().mapToInt(RulebookLayout.Line::stepPx).sum();
            if (used > height) {
                problems.add("第 " + (i + 1) + " 页高 " + used + " > " + height);
            }
            for (RulebookLayout.Line line : r.pages().get(i).lines()) {
                if (FAKE.width(line.text(), line.basePx(), false) + line.indentPx() > box) {
                    problems.add("第 " + (i + 1) + " 页一行太宽：" + line.text());
                }
            }
        }
        // ② 字不丢不重不乱序：正文 · 标题按块的次序拼起来，等于各页的行按页的次序拼起来
        StringBuilder want = new StringBuilder();
        for (Rulebook.Block b : blocks) {
            switch (b) {
                case Rulebook.TitlePage t -> want.append(t.title()).append(t.subtitle()).append(t.motto());
                case Rulebook.Chapter c -> want.append(c.title());
                case Rulebook.Heading h -> want.append(h.text());
                case Rulebook.Para p -> p.spans().forEach(s -> want.append(s.text()));
                case Rulebook.Item it -> it.spans().forEach(s -> want.append(s.text()));
            }
        }
        StringBuilder got = new StringBuilder();
        r.pages().forEach(p -> p.lines().forEach(l -> got.append(l.text())));
        if (!want.toString().contentEquals(got)) {
            problems.add("拼回来的字与书不同：书 " + want.length() + " 字、页上 " + got.length() + " 字");
        }
        // ③ 每一章从页顶起；④ 小节标题后面至少还有一行正文在同一页（不落页底）
        for (RulebookLayout.Chapter c : r.chapters()) {
            RulebookLayout.Line first = r.pages().get(c.page()).lines().get(0);
            if (first.kind() != RulebookLayout.Kind.CHAPTER) {
                problems.add("章「" + c.title() + "」不在页顶");
            }
        }
        for (int i = 0; i < r.pages().size(); i++) {
            List<RulebookLayout.Line> lines = r.pages().get(i).lines();
            if (!lines.isEmpty() && lines.getLast().kind() == RulebookLayout.Kind.HEADING) {
                problems.add("第 " + (i + 1) + " 页最后一行是小节标题：" + lines.getLast().text());
            }
        }
        // ④b 段落的末行不单独落在下一页的页顶（那一段多于一行时）
        for (int i = 1; i < r.pages().size(); i++) {
            List<RulebookLayout.Line> prev = r.pages().get(i - 1).lines();
            List<RulebookLayout.Line> cur = r.pages().get(i).lines();
            if (prev.isEmpty() || cur.isEmpty() || cur.get(0).para() < 0 || cur.get(0).para() != prev.getLast().para()) {
                continue;
            }
            boolean last = cur.size() == 1 || cur.get(1).para() != cur.get(0).para();
            if (last) {
                problems.add("第 " + (i + 1) + " 页页顶只有上一页那一段的最后一行：" + cur.get(0).text());
            }
        }
        // ⑤ 锚点：每个带锚点的章 / 小节都有，指着的那一页上写着它的标题
        int anchored = 0;
        for (Rulebook.Block b : blocks) {
            String key = b instanceof Rulebook.Chapter c ? c.anchor() : b instanceof Rulebook.Heading h ? h.anchor() : "";
            String text = b instanceof Rulebook.Chapter c ? c.title() : b instanceof Rulebook.Heading h ? h.text() : "";
            if (key.isEmpty()) {
                continue;
            }
            anchored++;
            Integer page = r.anchors().get(key);
            String head = text.substring(0, Math.min(4, text.length()));
            if (page == null || r.pages().get(page).lines().stream().noneMatch(l -> l.text().startsWith(head))) {
                problems.add("锚点 " + key + " → 第 " + page + " 页上没有「" + text + "」");
            }
        }
        assertTrue(r.pages().size() > 20 && r.chapters().size() >= 12 && anchored > 20,
                "正向对照：" + r.pages().size() + " 页 · " + r.chapters().size() + " 章 · " + anchored + " 个锚点");
        assertEquals(List.of(), problems.subList(0, Math.min(10, problems.size())));
    }
}
