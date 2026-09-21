package io.github.heavyseasmc.mod.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GUI 一门语言一份定义（ADR-0033）：扫 {@code src/client} 的源码，「又写了一份」在这里红，不等下一次审查。
 *
 * <h2>为什么扫源码而不是跑界面</h2>
 * 这些界面要 Minecraft 客户端才能实例化，单测起不了它。而要抓的东西 —— 版面常量在某一面又声明了一遍、
 * 金框又画成 1 像素、颜色又从 Minecraft 自带的 {@code Formatting} 取 —— 全是源码上一眼能认的形状。
 * 2026-09-18 审查抓到时，{@code TOP_BAND_Y = 12} 在 10 个界面各写一遍、卡高上限三种值，没有一处报错。
 *
 * <h2>正向对照</h2>
 * 扫到的文件数必须够多。扫错目录时什么都扫不到，而「0 命中」与「没在扫」输出完全相同（证伪表第一条）。
 */
class GuiConsistencyTest {

    /** 测试的工作目录是 {@code mod/}（Gradle 的默认），客户端源码在它下面。 */
    private static final Path CLIENT_DIR = Path.of("src", "client", "java", "io", "github", "heavyseasmc", "mod", "client");

    /** 全部界面里至少这么多份 {@code *.java}；少于它就是没扫到，不是干净。 */
    private static final int MIN_FILES = 15;

    /** 只许在 {@code GameScreen} 里声明的版面常量与帧计时字段。 */
    private static final Pattern HOISTED = Pattern.compile(
            "\\b(?:static\\s+final\\s+(?:int|float)|private\\s+long)\\s+"
                    + "(TOP_BAND_Y|TOP_BAND_H|SEA_LINE_Y|SIDE|BAR_H|BAR_TO_TEXT|BELOW_CARDS|HINT_GAP|BORDER_ROOM"
                    + "|CARD_FRAME|MIN_CARD_H|MAX_CARD_H_RATIO|RAIL_H|BTN_PAD_X|BTN_PAD_Y|lastFrameMs)\\b");

    /** 只许在 {@code GameScreen} 里出现的写法：各是审查抓到过的一种「又写了一份」。 */
    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("drawBorder\\(\\s*-1\\b"),          // 金框 1 像素：卡的框只有 drawCardFrame 一处
            Pattern.compile(",\\s*0xFFFFFF\\b"),                  // 纯白当颜色用：纸色是 GuiLanguage.INK
            Pattern.compile("0xA0A0A0"),                          // Minecraft 自带的灰
            Pattern.compile("\\bFormatting\\."),                  // Minecraft 自带的 16 色：语义色只有 GuiLanguage 那三个
            Pattern.compile("\\bNavCardFace\\b"),                 // 字画的航海卡已删（O24）
            Pattern.compile("extends\\s+Screen\\b"));              // 界面一律 extends GameScreen

    @Test
    void layoutIsDefinedOnceInGameScreen() throws IOException {
        List<Path> files;
        try (Stream<Path> listing = Files.list(CLIENT_DIR)) {
            files = listing.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        assertTrue(files.size() >= MIN_FILES,
                "只扫到 " + files.size() + " 份客户端源码（" + CLIENT_DIR.toAbsolutePath() + "）—— 没在扫，不是干净");

        List<String> problems = new ArrayList<>();
        for (Path file : files) {
            if (file.getFileName().toString().equals("GameScreen.java")) {
                continue;                                     // 唯一一份定义就在这里
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String where = file.getFileName() + ":" + (i + 1);
                var hoisted = HOISTED.matcher(line);
                if (hoisted.find()) {
                    problems.add(where + "  又声明了一份 " + hoisted.group(1) + "（只许在 GameScreen 里）：" + line.trim());
                }
                for (Pattern p : FORBIDDEN) {
                    if (p.matcher(line).find()) {
                        problems.add(where + "  " + p.pattern() + "：" + line.trim());
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "GUI 定义不再只有一份（ADR-0033）：\n  " + String.join("\n  ", problems));
    }

    /** 材质相关的写法：只许出现在 {@code GuiMaterial} 里（ADR-0037 第二刀）。 */
    private static final List<Pattern> RAW_MATERIAL = List.of(
            Pattern.compile("textures/gui/material"),            // 材质贴图的路径：主题怎么选只此一处
            Pattern.compile("\\brenderBackground\\s*\\("),       // Minecraft 那层「模糊 + 压暗」：界面像深色模式应用的原因
            Pattern.compile("\\bapplyBlur\\s*\\("),
            Pattern.compile("\\brenderInGameBackground\\s*\\("));

    /**
     * 铺底与标签只经 {@code GuiMaterial}。哪一面自己去调模糊背景、自己去拼材质贴图的路径，就又回到
     * 「各面各铺各的底」，而且换主题时那一面不会跟着换 —— 屏幕上不报错，只是有一面永远是另一个样子。
     */
    @Test
    void materialIsDefinedOnlyInGuiMaterial() throws IOException {
        List<Path> files;
        try (Stream<Path> listing = Files.list(CLIENT_DIR)) {
            files = listing.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        assertTrue(files.size() >= MIN_FILES, "只扫到 " + files.size() + " 份客户端源码 —— 没在扫，不是干净");
        assertTrue(files.stream().anyMatch(f -> f.getFileName().toString().equals("GuiMaterial.java")),
                "GuiMaterial.java 不在扫到的文件里 —— 豁免的那一份都没找到，这道判据多半扫错了目录");

        List<String> problems = new ArrayList<>();
        for (Path file : files) {
            if (file.getFileName().toString().equals("GuiMaterial.java")) {
                continue;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String code = lines.get(i).strip();
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) {
                    continue;
                }
                for (Pattern p : RAW_MATERIAL) {
                    if (p.matcher(code).find()) {
                        problems.add(file.getFileName() + ":" + (i + 1) + "  " + p.pattern() + "：" + code);
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "有界面绕开了 GuiMaterial（ADR-0037）：" + System.lineSeparator() + "  "
                + String.join(System.lineSeparator() + "  ", problems));
    }

    /** 直接画字、直接量字的写法：只许出现在 {@code GuiText} 里（ADR-0037）。 */
    private static final List<Pattern> RAW_TEXT = List.of(
            Pattern.compile("drawTextWithShadow"),
            Pattern.compile("drawCenteredTextWithShadow"),
            Pattern.compile("context\\.drawText\\("),
            Pattern.compile("textRenderer\\s*\\."),
            Pattern.compile("\\.fontHeight\\b"));

    /**
     * 界面上的字只经 {@code GuiText}：它在物理像素空间里排、量过才画、放不下就缩 → 折 → 截（ADR-0037）。
     * 绕开它直接调 Minecraft 的画字接口，就回到了像素字，而且不再有「不越界」的保证 —— 屏幕上不会报任何错。
     */
    @Test
    void textIsDrawnOnlyThroughGuiText() throws IOException {
        List<Path> files;
        try (Stream<Path> listing = Files.list(CLIENT_DIR)) {
            files = listing.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        assertTrue(files.size() >= MIN_FILES, "只扫到 " + files.size() + " 份客户端源码 —— 没在扫，不是干净");
        assertTrue(files.stream().anyMatch(f -> f.getFileName().toString().equals("GuiText.java")),
                "GuiText.java 不在扫到的文件里 —— 豁免的那一份都没找到，这道判据多半扫错了目录");

        List<String> problems = new ArrayList<>();
        for (Path file : files) {
            if (file.getFileName().toString().equals("GuiText.java")) {
                continue;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String code = line.strip();
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) {
                    continue;                                 // 注释里提到旧写法不算
                }
                for (Pattern p : RAW_TEXT) {
                    if (p.matcher(line).find()) {
                        problems.add(file.getFileName() + ":" + (i + 1) + "  " + p.pattern() + "：" + code);
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "有字绕开了 GuiText（ADR-0037）：" + System.lineSeparator() + "  "
                + String.join(System.lineSeparator() + "  ", problems));
    }
}
