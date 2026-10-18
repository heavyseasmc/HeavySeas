package io.github.heavyseasmc.mod.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.heavyseasmc.mod.rulebook.ShelfBook;
import net.minecraft.client.font.FontStorage;
import net.minecraft.client.font.Glyph;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 书柜里的书在游戏字体里排出来是什么样（ADR-0109 ③）：每本 8–16 页、每页不超过 14 行、每行不超过 114 像素，
 * 书里没有游戏字体画不出来的字。
 *
 * <p>排版走的就是游戏里那一条（{@link RulebookBook#plainPages}）；字宽<b>不估</b>，从 Minecraft 自己的字体文件算：
 * 读 jar 里的 {@code font/include/default.json} 与那几张位图，照 1.21.1 {@code BitmapFont.Loader} 的算法
 * （每格从右往左找第一列有不透明像素的，宽 + 1）得出每个字的步进，空格照 {@code include/space.json} 取 4。
 * 默认字体之后还有 unifont 兜底 —— 书里落到兜底的字在这里直接判失败（画得出来，但量不准、也不像那一年的铅字）。
 */
class ShelfBookPagesTest {

    private static final int MIN_PAGES = 8;
    private static final int MAX_PAGES = 16;

    private static Map<Integer, Integer> advances;
    private static TextRenderer font;

    @BeforeAll
    static void loadFont() throws IOException {
        advances = new HashMap<>();
        advances.put((int) ' ', 4);                      // include/space.json
        JsonObject def = json("/assets/minecraft/font/include/default.json");
        int bitmaps = 0;
        for (JsonElement e : def.getAsJsonArray("providers")) {
            JsonObject p = e.getAsJsonObject();
            if (!"bitmap".equals(p.get("type").getAsString())) {
                continue;
            }
            bitmaps++;
            Identifier file = Identifier.of(p.get("file").getAsString());
            int height = p.has("height") ? p.get("height").getAsInt() : 8;
            BufferedImage image;
            try (InputStream in = ShelfBookPagesTest.class.getResourceAsStream(
                    "/assets/" + file.getNamespace() + "/textures/" + file.getPath())) {
                assertNotNull(in, "类路径上没有字体位图 " + file + " —— 没在测，不是通过");
                image = ImageIO.read(in);
            }
            List<int[]> rows = new ArrayList<>();
            for (JsonElement r : p.getAsJsonArray("chars")) {
                rows.add(r.getAsString().codePoints().toArray());
            }
            int cw = image.getWidth() / rows.getFirst().length;
            int ch = image.getHeight() / rows.size();
            float scale = (float) height / ch;
            for (int row = 0; row < rows.size(); row++) {
                int[] cps = rows.get(row);
                for (int col = 0; col < cps.length; col++) {
                    if (cps[col] == 0) {
                        continue;
                    }
                    int w = inkWidth(image, cw, ch, col, row);
                    advances.putIfAbsent(cps[col], (int) (0.5 + w * scale) + 1);    // 先登记的字体先用（同 FontStorage）
                }
            }
        }
        assertTrue(bitmaps >= 2 && advances.size() > 500, "字体只读到 " + bitmaps + " 张位图 · " + advances.size() + " 个字");
        // 正向对照：量出来的是 Minecraft 那一套（小写 a 是 6、i 是 2、l 是 3）。读错了字体，这里先红
        assertEquals(6, advances.get((int) 'a'));
        assertEquals(2, advances.get((int) 'i'));
        assertEquals(3, advances.get((int) 'l'));
        FontStorage storage = new FontStorage(null, Identifier.ofVanilla("default")) {
            @Override
            public Glyph getGlyph(int codePoint, boolean validateAdvance) {
                Integer a = advances.get(codePoint);
                float w = a == null ? 0 : a;
                return (Glyph.EmptyGlyph) () -> w;
            }
        };
        font = new TextRenderer(id -> storage, false);
    }

    private static int inkWidth(BufferedImage image, int cw, int ch, int col, int row) {
        for (int x = cw - 1; x >= 0; x--) {
            for (int y = 0; y < ch; y++) {
                if ((image.getRGB(col * cw + x, row * ch + y) >>> 24) != 0) {
                    return x + 1;
                }
            }
        }
        return 0;
    }

    private static JsonObject json(String path) throws IOException {
        try (InputStream in = ShelfBookPagesTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "类路径上没有 " + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    static List<Text> pages(String source) {
        return RulebookBook.plainPages(ShelfBook.parse(source), font);
    }

    @Test
    @DisplayName("每一本书只用游戏默认字体里有的字")
    void onlyGlyphsTheGameFontHas() {
        for (String id : io.github.heavyseasmc.mod.rulebook.ShelfBookTest.allBooks()) {
            String src = ShelfBook.source(id, ShelfBook.BASE_LANGUAGE).orElseThrow();
            TreeSet<String> missing = new TreeSet<>();
            src.codePoints().filter(c -> c != '\n' && !advances.containsKey(c))
                    .forEach(c -> missing.add(new String(Character.toChars(c)) + " U+" + Integer.toHexString(c)));
            assertTrue(missing.isEmpty(), id + " 里有游戏字体没有的字：" + missing);
        }
    }

    @Test
    @DisplayName("每一本 8–16 页，每页不超过 14 行、每行不超过 114 像素")
    void everyBookFitsTheBook() {
        StringBuilder report = new StringBuilder();
        for (String id : io.github.heavyseasmc.mod.rulebook.ShelfBookTest.allBooks()) {
            List<Text> pages = pages(ShelfBook.source(id, ShelfBook.BASE_LANGUAGE).orElseThrow());
            report.append(id).append(' ').append(pages.size()).append(" 页；");
            dump(id, pages);
            for (int n = 0; n < pages.size(); n++) {
                String[] lines = pages.get(n).getString().split("\n", -1);
                assertTrue(lines.length <= RulebookBook.LINES, id + " 第 " + (n + 1) + " 页 " + lines.length + " 行");
                for (String line : lines) {
                    assertTrue(font.getWidth(line) <= RulebookBook.WIDTH,
                            id + " 第 " + (n + 1) + " 页这一行宽 " + font.getWidth(line) + "：" + line);
                }
            }
            assertTrue(pages.size() >= MIN_PAGES && pages.size() <= MAX_PAGES, id + " 排出 " + pages.size() + " 页");
            // 扉页一页放得下：第二页就是第一章。扉页的排法不管页满（规则书那一套），副题、题词长了会把尾巴挤到单独一页
            String first = ShelfBook.parse(ShelfBook.source(id, ShelfBook.BASE_LANGUAGE).orElseThrow()).stream()
                    .filter(b -> b instanceof io.github.heavyseasmc.mod.rulebook.Rulebook.Chapter)
                    .map(b -> ((io.github.heavyseasmc.mod.rulebook.Rulebook.Chapter) b).title()).findFirst().orElseThrow();
            assertTrue(pages.get(1).getString().startsWith(first.split(" ")[0]),
                    id + " 扉页一页放不下，第二页是：" + pages.get(1).getString().split("\n")[0]);
        }
        // 把页数打出来：人一眼看得出量的是几本、各几页（「0 本」与「都通过」输出不同）
        System.out.println("书柜里的书：" + report);
    }

    /** 排出来的样子落一份到 {@code build/shelf-books/<id>.txt}（一页一段），挑书、改稿时对着看。 */
    private static void dump(String id, List<Text> pages) {
        StringBuilder out = new StringBuilder();
        for (int n = 0; n < pages.size(); n++) {
            out.append("---- ").append(n + 1).append(" / ").append(pages.size()).append(" ----\n")
                    .append(pages.get(n).getString()).append("\n");
        }
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("build", "shelf-books");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve(id + ".txt"), out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("量具自检：一段长到 20 页的书判得出超页，一行一个字也排得开")
    void gaugeCatchesAnOverlongBook() {
        StringBuilder src = new StringBuilder("@title T\n@subtitle S\n@motto M\n# One\n");
        for (int i = 0; i < 120; i++) {
            src.append("The sea was grey and the deck was wet, and nobody went out upon it.\n");
        }
        int n = pages(src.toString()).size();
        assertTrue(n > MAX_PAGES, "120 段只排出 " + n + " 页 —— 量具没在量");
        assertEquals(2, pages("@title T\n@subtitle S\n@motto M\n# One\nA.\n").size());
    }
}
