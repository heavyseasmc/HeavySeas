package io.github.heavyseasmc.mod.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code GuiText} 的字号梯子与 {@code assets/heavyseas/font/} 下的定义必须一一对应（ADR-0037 §7.3）。
 *
 * <p>对不上时 Minecraft <b>不报错</b>：找不到的字体 id 静默退回默认字体 —— 屏幕上只是「字变回像素字了」，
 * 日志、计数、包都正常。这正是本仓库反复遇到的那个形状（静默退回默认值），所以要一道闸门。
 *
 * <p>与 {@code GuiConsistencyTest} 一样读源码文本：客户端类要 Minecraft 才能加载，单测里够不着。
 */
class GuiFontLadderTest {

    private static final Path GUI_TEXT = Path.of("src", "client", "java", "io", "github", "heavyseasmc", "mod", "client", "GuiText.java");
    private static final Path FONT_DIR = Path.of("src", "main", "resources", "assets", "heavyseas", "font");
    /** 两条梯子合起来至少这么多级；少于它就是没读到，不是对上了。 */
    private static final int MIN_DEFINITIONS = 10;

    @Test
    @DisplayName("梯子的每一级都有一份定义，每份定义都在梯子上，定义的字号就是文件名里那个数")
    void ladderMatchesDefinitions() throws IOException {
        String source = Files.readString(GUI_TEXT, StandardCharsets.UTF_8);
        Set<String> wanted = new TreeSet<>();
        ladder(source, "REGULAR_PX").forEach(px -> wanted.add("r" + px));
        ladder(source, "BOLD_PX").forEach(px -> wanted.add("b" + px));
        assertTrue(wanted.size() >= MIN_DEFINITIONS, "只从 GuiText 读到 " + wanted.size() + " 级 —— 没在查");

        Set<String> found = new TreeSet<>();
        try (Stream<Path> listing = Files.list(FONT_DIR)) {
            for (Path p : listing.filter(f -> f.toString().endsWith(".json")).toList()) {
                String id = p.getFileName().toString().replace(".json", "");
                found.add(id);
                String json = Files.readString(p, StandardCharsets.UTF_8);
                Matcher size = Pattern.compile("\"size\":\\s*([0-9.]+)").matcher(json);
                assertTrue(size.find(), id + "：没有 size");
                assertEquals(Double.parseDouble(id.substring(1)), Double.parseDouble(size.group(1)), id + "：size 与文件名不符");
                Matcher over = Pattern.compile("\"oversample\":\\s*([0-9.]+)").matcher(json);
                assertTrue(over.find() && Double.parseDouble(over.group(1)) == 1.0,
                        id + "：oversample 必须是 1 —— 字是在物理像素空间里画的，别的值会糊（ADR-0037 §7.3）");
                Matcher file = Pattern.compile("\"file\":\\s*\"heavyseas:([^\"]+)\"").matcher(json);
                assertTrue(file.find(), id + "：没有 ttf 提供者");
                assertTrueType(FONT_DIR.resolve(file.group(1)));
            }
        }
        assertEquals(wanted, found, "GuiText 的梯子与 font/ 下的定义对不上（改梯子要重跑管线的 build_gui_font.py）");
    }

    /** Minecraft 1.21.1 只收 glyf 的 TrueType；CFF 的 OTF（文件头 OTTO）加载时当场抛异常。 */
    private static void assertTrueType(Path ttf) throws IOException {
        assertTrue(Files.isRegularFile(ttf), "定义指向的字体文件不存在：" + ttf);
        byte[] head = new byte[4];
        try (var in = Files.newInputStream(ttf)) {
            assertEquals(4, in.read(head));
        }
        assertTrue(head[0] == 0 && head[1] == 1 && head[2] == 0 && head[3] == 0,
                ttf.getFileName() + "：文件头不是 TrueType（0x00010000）—— Minecraft 会拒收");
    }

    private static Set<Integer> ladder(String source, String name) {
        Matcher m = Pattern.compile("int\\[\\]\\s+" + name + "\\s*=\\s*\\{([^}]*)}").matcher(source);
        assertTrue(m.find(), "GuiText 里找不到 " + name);
        Set<Integer> out = new TreeSet<>();
        for (String n : m.group(1).split(",")) {
            out.add(Integer.parseInt(n.strip()));
        }
        return out;
    }
}
