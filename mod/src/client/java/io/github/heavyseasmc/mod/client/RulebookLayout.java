package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.rulebook.Rulebook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则书自家书页的排版（2026-10-19，P3 第 3 条，样张 B「侧栏目录 + 单页」）：把 {@link Rulebook} 的块按页框的物理像素排成一页一页。
 * 不碰 Minecraft 的客户端类 —— 量字交给 {@link Metrics}，单测拿等宽的假字量（{@code RulebookLayoutTest}）。
 *
 * <p>排法照原来那本（{@link RulebookBook}）：扉页单独一页 · 章另起一页 · 小节标题连同后面至少两行放不下就另起一页 · 列表悬挂缩进。
 * 不同的是页框大小随窗口（原来的书页固定 114 × 14 行），目录不占页 —— 常驻在左边的侧栏里。
 */
final class RulebookLayout {

    enum Kind { TITLE, SUBTITLE, MOTTO, CHAPTER, HEADING, BODY }

    /** 量字：按 {@link GuiText} 的阅读字号那一套。 */
    interface Metrics {
        /** 一段（每个字各自给粗细）按 boxPx 折行 → 每行在原文里的 [start, end)。 */
        List<int[]> wrap(String text, boolean[] bold, int boxPx, int basePx);

        /** 这一级字一行占多高（物理像素）。 */
        int lineStep(int basePx);

        int width(String text, int basePx, boolean bold);
    }

    /** 字号（物理像素，阅读字号梯子上挑之前的那个数）。 */
    record Sizes(int title, int chapter, int heading, int body) {
    }

    /** 一行：indentPx 是正文往右缩多少；marker 是列表项第一行的记号（画在 indentPx 之前那一截里）。 */
    /** para：这一行属于第几段（同一段的行同一个号；空行 −1），单测查「段落末行不单独落页」用。 */
    record Line(Kind kind, String text, boolean[] bold, int basePx, int indentPx, String marker, int stepPx, int para) {
    }

    record Page(List<Line> lines, int chapter) {
    }

    /** 侧栏里的一章：标题 · 第几页起 · 它的小节（标题 · 第几页）。 */
    record Chapter(String title, int page, List<Section> sections) {
    }

    record Section(String title, int page) {
    }

    record Result(List<Page> pages, List<Chapter> chapters, Map<String, Integer> anchors) {
    }

    private RulebookLayout() {
    }

    static Result layout(List<Rulebook.Block> blocks, int boxPx, int pageHeightPx, Sizes sizes, Metrics m) {
        Builder b = new Builder(boxPx, pageHeightPx, m);
        List<Chapter> chapters = new ArrayList<>();
        List<Section> sections = null;
        Map<String, Integer> anchors = new LinkedHashMap<>();
        Rulebook.Block previous = null;
        for (Rulebook.Block block : blocks) {
            switch (block) {
                case Rulebook.TitlePage t -> {
                    b.newPage(-1);
                    b.skip(pageHeightPx / 4);
                    b.para(Kind.TITLE, t.title(), true, sizes.title(), 0, null);
                    b.gap(sizes.body());
                    b.para(Kind.SUBTITLE, t.subtitle(), false, sizes.body(), 0, null);
                    b.gap(sizes.body());
                    b.gap(sizes.body());
                    b.para(Kind.MOTTO, t.motto(), false, sizes.body(), 0, null);
                    anchors.put("contents", 0);
                }
                case Rulebook.Chapter c -> {
                    b.newPage(chapters.size());
                    sections = new ArrayList<>();
                    chapters.add(new Chapter(c.title(), b.pageIndex(), sections));
                    if (!c.anchor().isEmpty()) {
                        anchors.put(c.anchor(), b.pageIndex());
                    }
                    b.para(Kind.CHAPTER, c.title(), true, sizes.chapter(), 0, null);
                    b.gap(sizes.body());
                }
                case Rulebook.Heading h -> {
                    List<int[]> lines = m.wrap(h.text(), allBold(h.text()), boxPx, sizes.heading());
                    if (!(previous instanceof Rulebook.Chapter)) {
                        b.gap(sizes.body());
                    }
                    // 小节标题不落在页底：连同它后面至少两行正文放不下，就另起一页
                    b.keepTogether(lines.size() * m.lineStep(sizes.heading()) + 2 * m.lineStep(sizes.body()));
                    if (sections != null) {
                        sections.add(new Section(h.text(), b.pageIndex()));
                    }
                    if (!h.anchor().isEmpty()) {
                        anchors.put(h.anchor(), b.pageIndex());
                    }
                    b.para(Kind.HEADING, h.text(), true, sizes.heading(), 0, null);
                }
                case Rulebook.Para p -> {
                    if (!(previous instanceof Rulebook.Heading) && !(previous instanceof Rulebook.Chapter)) {
                        b.gap(sizes.body());
                    }
                    b.spans(p.spans(), sizes.body(), 0, null);
                }
                case Rulebook.Item i -> {
                    if (!(previous instanceof Rulebook.Item) && !(previous instanceof Rulebook.Heading)
                            && !(previous instanceof Rulebook.Chapter)) {
                        b.gap(sizes.body());
                    }
                    String marker = i.marker() + " ";
                    int indent = m.width("  ".repeat(i.depth()), sizes.body(), false) + m.width(marker, sizes.body(), false);
                    b.spans(i.spans(), sizes.body(), indent, marker);
                }
            }
            previous = block;
        }
        return new Result(b.finish(), List.copyOf(chapters), Map.copyOf(anchors));
    }

    private static boolean[] allBold(String text) {
        boolean[] out = new boolean[text.length()];
        java.util.Arrays.fill(out, true);
        return out;
    }

    /** 一页一页往下排（物理像素）。 */
    private static final class Builder {
        private final int box;
        private final int height;
        private final Metrics m;
        private final List<Page> done = new ArrayList<>();
        private List<Line> page = new ArrayList<>();
        private int used;
        private int chapter = -1;
        private int paras;

        Builder(int box, int height, Metrics m) {
            this.box = box;
            this.height = height;
            this.m = m;
        }

        int pageIndex() {
            return done.size();
        }

        void newPage(int nextChapter) {
            if (!page.isEmpty() || used > 0) {
                flush();
            }
            chapter = nextChapter;
        }

        void skip(int px) {
            if (px > 0) {
                page.add(new Line(Kind.BODY, "", new boolean[0], 0, 0, null, Math.min(px, height - used), -1));
                used += Math.min(px, height - used);
            }
        }

        /** 段与段之间空半行；页顶不空。 */
        void gap(int basePx) {
            if (page.isEmpty()) {
                return;
            }
            int step = m.lineStep(basePx) / 2;
            if (used + step > height) {
                flush();
                return;
            }
            page.add(new Line(Kind.BODY, "", new boolean[0], basePx, 0, null, step, -1));
            used += step;
        }

        void keepTogether(int px) {
            if (!page.isEmpty() && used + px > height) {
                flush();
            }
        }

        void para(Kind kind, String text, boolean bold, int basePx, int indent, String marker) {
            boolean[] b = new boolean[text.length()];
            java.util.Arrays.fill(b, bold);
            lines(kind, text, b, basePx, indent, marker);
        }

        void spans(List<Rulebook.Span> spans, int basePx, int indent, String marker) {
            StringBuilder text = new StringBuilder();
            List<Boolean> bold = new ArrayList<>();
            for (Rulebook.Span s : spans) {
                text.append(s.text());
                for (int i = 0; i < s.text().length(); i++) {
                    bold.add(s.bold());
                }
            }
            boolean[] b = new boolean[bold.size()];
            for (int i = 0; i < b.length; i++) {
                b[i] = bold.get(i);
            }
            lines(Kind.BODY, text.toString(), b, basePx, indent, marker);
        }

        private void lines(Kind kind, String text, boolean[] bold, int basePx, int indent, String marker) {
            int step = m.lineStep(basePx);
            List<int[]> spans = m.wrap(text, bold, Math.max(1, box - indent), basePx);
            for (int n = 0; n < spans.size(); n++) {
                int[] s = spans.get(n);
                // 末行不单独落到下一页（第一次实拍：一页上只剩上一段的最后一行）：倒数第二行放得下、末行放不下时，倒数第二行就先换页
                //   两行的段落拆成一行 + 一行时，整段挪到下一页（页上已经有东西时）
                boolean widow = n == spans.size() - 2 && (n >= 1 || !page.isEmpty()) && used + 2 * step > height && used + step <= height;
                if (used + step > height || widow) {
                    flush();
                }
                page.add(new Line(kind, text.substring(s[0], s[1]), java.util.Arrays.copyOfRange(bold, s[0], s[1]), basePx, indent,
                        n == 0 ? marker : null, step, paras));
                used += step;
            }
            paras++;
        }

        private void flush() {
            while (!page.isEmpty() && page.getLast().text().isEmpty()) {
                page.removeLast();
            }
            done.add(new Page(List.copyOf(page), chapter));
            page = new ArrayList<>();
            used = 0;
        }

        List<Page> finish() {
            if (!page.isEmpty()) {
                flush();
            }
            return List.copyOf(done);
        }
    }
}
