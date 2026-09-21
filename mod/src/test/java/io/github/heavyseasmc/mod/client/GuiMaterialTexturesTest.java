package io.github.heavyseasmc.mod.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 两个主题的材质贴图必须是同一份清单、同样的尺寸，而且尺寸要与 {@code GuiMaterial} 里切九宫格、铺平铺用的常量对得上（ADR-0037）。
 *
 * <p>客户端换主题只换目录名。某个主题少一张，Minecraft <b>不报错</b> —— 只在那个主题下画出一块紫黑格，
 * 而平时开发看的多半是另一个主题。尺寸对不上则更隐蔽：九宫格切歪、平铺接缝错位，看上去只是「有点怪」。
 */
class GuiMaterialTexturesTest {

    private static final Path MATERIAL_DIR = Path.of("src", "main", "resources", "assets", "heavyseas", "textures", "gui", "material");
    private static final Path GUI_MATERIAL = Path.of("src", "client", "java", "io", "github", "heavyseasmc", "mod", "client", "GuiMaterial.java");
    private static final Path PORTRAIT_DIR = Path.of("src", "main", "resources", "assets", "heavyseas", "textures", "gui", "portrait");
    private static final Path ROSTER = Path.of("..", "data", "roster", "default.json");
    /** 每个主题至少这么多张；少于它就是没读到，不是对上了。 */
    private static final int MIN_TEXTURES = 8;
    /** 角色至少这么多个；少于它就是没读到。 */
    private static final int MIN_CHARACTERS = 6;
    /** 头像最大画到 72 物理像素，贴图不小于它的两倍。 */
    private static final int MIN_PORTRAIT = 144;

    @Test
    @DisplayName("浅色与深色是同一份贴图清单、同样的尺寸，且与 GuiMaterial 的常量一致")
    void themesShipTheSameTextures() throws IOException {
        Map<String, int[]> light = sizes("light");
        Map<String, int[]> dark = sizes("dark");
        assertTrue(light.size() >= MIN_TEXTURES, "浅色主题只读到 " + light.size() + " 张贴图 —— 没在查");
        assertEquals(light.keySet(), dark.keySet(), "两个主题的贴图清单不一样：缺的那张只会在那个主题下变成紫黑格");
        for (String name : light.keySet()) {
            assertEquals(describe(light.get(name)), describe(dark.get(name)), name + "：两个主题的尺寸不一样");
        }

        String source = Files.readString(GUI_MATERIAL, StandardCharsets.UTF_8);
        int unit = constant(source, "TEXELS_PER_UNIT");
        expect(light, "sheet.png", constant(source, "SHEET_TEXELS"));
        expect(light, "corners.png", constant(source, "CORNERS_TEXELS"));
        expect(light, "tag.png", constant(source, "TAG_TEXELS"));
        nineSliceBorder(source, "TAG_TEXELS", "TAG_BORDER_TEXELS", unit);

        // 第二刀收尾：倒计时的框 · 头像圈 · 键帽。
        expect(light, "gauge.png", constant(source, "GAUGE_W_TEXELS"), constant(source, "GAUGE_H_TEXELS"));
        assertTrue(2 * constant(source, "GAUGE_CAP_TEXELS") <= constant(source, "GAUGE_W_TEXELS"), "倒计时两头加起来比整张贴图还宽");
        assertEquals(0, constant(source, "GAUGE_CAP_TEXELS") % unit, "倒计时两头要是整数个 GUI 单位，否则圆头会被缩放");
        assertEquals(0, constant(source, "GAUGE_H_TEXELS") % unit, "倒计时的高要是整数个 GUI 单位：版面里的 BAR_H 取它");
        assertEquals(0, constant(source, "GAUGE_INSET_TEXELS") % unit, "倒计时的填充区要缩进整数个 GUI 单位：那一块是代码填的矩形");
        expect(light, "ring.png", constant(source, "RING_TEXELS"));
        expect(light, "ring_mark.png", constant(source, "RING_TEXELS"));
        assertTrue(constant(source, "RING_PORTRAIT_TEXELS") < constant(source, "RING_TEXELS"), "头像比头像圈还大：圈会画进头像里面");
        expect(light, "key.png", constant(source, "KEY_TEXELS"));
        nineSliceBorder(source, "KEY_TEXELS", "KEY_BORDER_TEXELS", unit);
    }

    private static void nineSliceBorder(String source, String texels, String border, int unit) {
        assertTrue(2 * constant(source, border) < constant(source, texels), border + "：九宫格的两条边加起来比整张贴图还宽");
        assertEquals(0, constant(source, border) % unit, border + "：九宫格的边要是整数个 GUI 单位，否则边角会被缩放");
    }

    /**
     * 座位轨上的头像：{@code data/roster} 里每个角色一枚，方的。缺一枚 Minecraft 不报错，只在那个人的座位上画一块紫黑格 ——
     * 而开发时常用的六人预设里未必有他。
     */
    @Test
    @DisplayName("每个角色都有一枚方形头像")
    void everyCharacterHasAPortrait() throws IOException {
        java.util.List<String> ids = new java.util.ArrayList<>();
        com.google.gson.JsonParser.parseString(Files.readString(ROSTER, StandardCharsets.UTF_8)).getAsJsonObject()
                .getAsJsonArray("characters").forEach(c -> ids.add(c.getAsJsonObject().get("id").getAsString()));
        assertTrue(ids.size() >= MIN_CHARACTERS, "data/roster 里只读到 " + ids.size() + " 个角色 —— 没在查");
        for (String id : ids) {
            Path p = PORTRAIT_DIR.resolve(id + ".png");
            assertTrue(Files.isRegularFile(p), "缺头像：" + id + ".png（重跑管线的 build_gui_material.py）");
            try (DataInputStream in = new DataInputStream(Files.newInputStream(p))) {
                in.skipBytes(16);
                int w = in.readInt();
                int h = in.readInt();
                assertTrue(w == h && w >= MIN_PORTRAIT, id + ".png 是 " + w + "x" + h + "：头像要是方的，且不小于 " + MIN_PORTRAIT);
            }
        }
    }

    private static void expect(Map<String, int[]> sizes, String file, int side) {
        expect(sizes, file, side, side);
    }

    private static void expect(Map<String, int[]> sizes, String file, int w, int h) {
        assertTrue(sizes.containsKey(file), "缺 " + file);
        assertEquals(w + "x" + h, describe(sizes.get(file)), file + "：尺寸与 GuiMaterial 的常量对不上（改了一边要重跑管线的 build_gui_material.py）");
    }

    private static String describe(int[] wh) {
        return wh[0] + "x" + wh[1];
    }

    private static Map<String, int[]> sizes(String theme) throws IOException {
        Map<String, int[]> out = new TreeMap<>();
        Path dir = MATERIAL_DIR.resolve(theme);
        assertTrue(Files.isDirectory(dir), "没有这个主题的贴图目录：" + dir.toAbsolutePath());
        try (Stream<Path> listing = Files.list(dir)) {
            for (Path p : listing.filter(f -> f.toString().endsWith(".png")).toList()) {
                try (DataInputStream in = new DataInputStream(Files.newInputStream(p))) {
                    in.skipBytes(16);                           // PNG 签名 8 + IHDR 长度 4 + "IHDR" 4
                    out.put(p.getFileName().toString(), new int[] {in.readInt(), in.readInt()});
                }
            }
        }
        return out;
    }

    private static int constant(String source, String name) {
        Matcher m = Pattern.compile("static final int " + name + "\\s*=\\s*(\\d+);").matcher(source);
        assertTrue(m.find(), "GuiMaterial 里找不到常量 " + name);
        return Integer.parseInt(m.group(1));
    }
}
