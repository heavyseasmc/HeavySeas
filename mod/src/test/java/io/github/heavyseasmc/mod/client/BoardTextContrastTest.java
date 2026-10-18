package io.github.heavyseasmc.mod.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 直接印在板上的字，两套主题下都要读得出（2026-10-13 第二轮验收 01 · 06 · 13 · 30 · 81 · 93：深色主题下座位名、
 * 底栏键位、「时间到……」、计分副标题、返航提示都是浅灰细字压在中等明度的橄榄色板上）。
 *
 * <h2>判据与被测对象不同源</h2>
 * 字色取自 {@link GuiLanguage}（代码），底色取自<b>板的贴图本身</b>（管线烘出来的 PNG，板心那一片里最不利的那一档）——
 * 不是从 GuiLanguage 里再取一个「板色」常量：那样期望值与被测对象同源，贴图换了也照样绿（证伪表：判据的期望值与被测对象同源）。
 *
 * <h2>阈值</h2>
 * WCAG 正文 4.5 : 1。这几处都是 13–15 稿子像素的细字；深色那一套次墨 3.8 : 1 在实拍里就已经读不出来（那是压暗之前的板）。
 * {@link GuiLanguage#dim()}（还没轮到 · 已移出）是有意压淡的状态色，不在这一条里。
 */
class BoardTextContrastTest {

    private static final Path HUD = Path.of("src", "main", "resources", "assets", "heavyseas", "textures", "gui", "hud");
    private static final Path CLIENT_DIR = Path.of("src", "client", "java", "io", "github", "heavyseasmc", "mod", "client");
    /** 两块板：对局各面的海图板、设置一面的账本板。 */
    private static final List<String> BOARDS = List.of("sheet_1x.png", "ledger_1x.png");
    /**
     * 状态色那一条量的深色板：海图板（对局各面）与账本板（设置一面）各两种倍数。
     * 账本板 2026-10-13 跟着海图板压暗（管线 build_hud_parts 的 LEDGER_DARK_PAPER）：原先设置一面的朱砂 3.47、铜绿 4.44 : 1。
     */
    private static final List<String> DARK_SHEETS = List.of("sheet_1x.png", "sheet_2x.png", "ledger_1x.png", "ledger_2x.png");
    /** 2026-10-13 之前深色主题的朱砂：只给对照那一条用。 */
    private static final int OLD_DARK_CINNABAR = 0xFFDA5B3E;
    private static final double MIN_CONTRAST = 4.5;
    private static final int MIN_FILES = 15;

    @AfterEach
    void restoreTheme() {
        GuiLanguage.setTheme(GuiLanguage.Theme.LIGHT);
    }

    @Test
    @DisplayName("板上的正文与次要字，在两套主题、两块板上对比度都不低于 4.5 : 1（底色取自板的贴图）")
    void boardTextReadsOnBothThemes() throws IOException {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (GuiLanguage.Theme theme : GuiLanguage.Theme.values()) {
            GuiLanguage.setTheme(theme);
            String dir = theme == GuiLanguage.Theme.DARK ? "dark" : "light";
            int[][] roles = {{GuiLanguage.ink()}, {GuiLanguage.secondary()}};
            String[] names = {"ink()", "secondary()"};
            for (String board : BOARDS) {
                int[] bg = boardExtremes(HUD.resolve(dir).resolve(board));
                for (int r = 0; r < roles.length; r++) {
                    for (int b : bg) {
                        double c = contrast(roles[r][0], b);
                        checked++;
                        if (c < MIN_CONTRAST) {
                            problems.add(String.format("%s · %s · %s：字 #%06X 对板 #%06X 只有 %.2f : 1", theme, board,
                                    names[r], roles[r][0] & 0xFFFFFF, b & 0xFFFFFF, c));
                        }
                    }
                }
            }
        }
        assertEquals(2 * 2 * 2 * 2, checked, "没在比 —— 主题、板或字色少了一样");
        assertTrue(problems.isEmpty(), "板上的字读不出：\n  " + String.join("\n  ", problems));
    }

    // 原先这里有一条对照「深色的次墨与 INK2 在深色海图板上不够」。2026-10-13 板压暗两档之后它们都够了（8.1 / 6.2 : 1），
    // 那条对照的前提不在了；深色海图板的正向对照改由下面的「旧朱砂」那一条担（证伪表：过期的判据比没有判据更坏）。

    /**
     * 深色海图板上的状态色（铜绿 · 朱砂 · 金）：倒计时的秒数、「轮到你」、被指的人都是这三色的细字（用户 2026-10-13：板压暗两档、
     * 朱砂提到 #EC6B4D）。底色照旧取自板的贴图，两种倍数各量一次 —— 2 倍那一张是大界面尺寸下真正画上去的。
     */
    @Test
    @DisplayName("深色海图板与账本板上，铜绿 · 朱砂 · 金三种状态色对板心对比度都不低于 4.5 : 1（底色取自板的贴图）")
    void statusColorsReadOnDarkSheet() throws IOException {
        GuiLanguage.setTheme(GuiLanguage.Theme.DARK);
        int[] roles = {GuiLanguage.verdigris(), GuiLanguage.cinnabar(), GuiLanguage.gold()};
        String[] names = {"verdigris()", "cinnabar()", "gold()"};
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (String board : DARK_SHEETS) {
            int[] bg = boardExtremes(HUD.resolve("dark").resolve(board));
            for (int r = 0; r < roles.length; r++) {
                for (int b : bg) {
                    double c = contrast(roles[r], b);
                    checked++;
                    if (c < MIN_CONTRAST) {
                        problems.add(String.format("%s · %s：#%06X 对板 #%06X 只有 %.2f : 1", board, names[r],
                                roles[r] & 0xFFFFFF, b & 0xFFFFFF, c));
                    }
                }
            }
        }
        assertEquals(DARK_SHEETS.size() * 3 * 2, checked, "没在比 —— 板或状态色少了一样");
        assertTrue(problems.isEmpty(), "深色板上的状态色读不出：\n  " + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("对照：压暗之前的朱砂 #DA5B3E 在现在的深色海图板与账本板上确实不够（判据分得开新旧两种朱砂）")
    void oldCinnabarFailsOnDarkSheet() throws IOException {
        for (String board : List.of("sheet_1x.png", "ledger_1x.png")) {
            int[] bg = boardExtremes(HUD.resolve("dark").resolve(board));
            double old = Math.min(contrast(OLD_DARK_CINNABAR, bg[0]), contrast(OLD_DARK_CINNABAR, bg[1]));
            assertTrue(old < MIN_CONTRAST, board + "：旧朱砂也够了（" + old + "）—— 这一条就分不出新旧，底色可能没取对");
        }
    }

    /** 扫源码：板上的字不许直接取 {@code GuiLanguage.muted()} 或 {@code Hud.ink2()}（签子上的 onTag、世界上的 onWorld 不算）。 */
    @Test
    @DisplayName("各面印在板上的次要字一律走 GuiLanguage.secondary()，不直接取 muted() / Hud.ink2()")
    void screensAskSecondary() throws IOException {
        Pattern raw = Pattern.compile("(?<!onTag\\()(?<!onWorld\\()GuiLanguage\\.(?:muted\\(\\)|Hud\\.ink2\\(\\))");
        // 正向对照：模式先认得自己要拦的写法，也放得过签子上的那一种（证伪表：grep 有三种结局）
        assertTrue(raw.matcher("int c = GuiLanguage.muted();").find());
        assertTrue(raw.matcher("x, GuiLanguage.Hud.ink2(), y").find());
        assertTrue(!raw.matcher("GuiLanguage.onTag(GuiLanguage.muted())").find());
        List<Path> files;
        try (Stream<Path> listing = Files.list(CLIENT_DIR)) {
            files = listing.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        assertTrue(files.size() >= MIN_FILES, "只扫到 " + files.size() + " 份客户端源码 —— 没在扫，不是干净");
        List<String> problems = new ArrayList<>();
        for (Path file : files) {
            if (file.getFileName().toString().equals("GuiLanguage.java")) {
                continue;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.trim().startsWith("*") || line.trim().startsWith("//")) {
                    continue;
                }
                if (raw.matcher(line).find()) {
                    problems.add(file.getFileName() + ":" + (i + 1) + "  " + line.trim());
                }
            }
        }
        assertTrue(problems.isEmpty(), "板上的字直接取了次墨（深色板上读不出，改用 GuiLanguage.secondary()）：\n  "
                + String.join("\n  ", problems));
    }

    /** 板心那一片（四边各让 1/6，避开木框与包角）里最暗与最亮的那一档（各 5 % 分位）—— 两头都要读得出。 */
    private static int[] boardExtremes(Path png) throws IOException {
        assertTrue(Files.isRegularFile(png), "找不到板的贴图 " + png.toAbsolutePath());
        BufferedImage im = ImageIO.read(png.toFile());
        int x0 = im.getWidth() / 6;
        int y0 = im.getHeight() / 6;
        int x1 = im.getWidth() * 5 / 6;
        int y1 = im.getHeight() * 5 / 6;
        int n = 0;
        int[] rgbs = new int[(x1 - x0) * (y1 - y0)];
        double[] lum = new double[rgbs.length];
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                rgbs[n] = im.getRGB(x, y);
                lum[n] = luminance(rgbs[n]);
                n++;
            }
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Double.compare(lum[a], lum[b]));
        assertTrue(n > 1000, "板心只取到 " + n + " 个像素");
        return new int[]{rgbs[order[n / 20]], rgbs[order[n - 1 - n / 20]]};
    }

    static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(int argb) {
        return 0.2126 * channel(argb >> 16 & 0xFF) + 0.7152 * channel(argb >> 8 & 0xFF) + 0.0722 * channel(argb & 0xFF);
    }

    private static double channel(int c) {
        double v = c / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
}
