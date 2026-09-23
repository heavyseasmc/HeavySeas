package io.github.heavyseasmc.mod.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 两个主题的材质贴图必须是同一份清单、同样的尺寸，而且尺寸要与 {@code GuiMaterial} 里切九宫格、铺平铺用的常量对得上（ADR-0037）。
 *
 * <p>客户端换主题只换目录名。某个主题少一张，Minecraft <b>不报错</b> —— 只在那个主题下画出一块紫黑格，
 * 而平时开发看的多半是另一个主题。尺寸对不上则更隐蔽：九宫格切歪、平铺接缝错位，看上去只是「有点怪」。
 *
 * <p>还有一条比清单更根本的：<b>两个主题只许差颜色，不许差形制</b>（ADR-0037 §7.12）。
 * 2026-09-23 之前不是这样 —— 浅色照 A 稿（罗经花 · 三角压角 · 比例尺）、深色照 B 稿（灯光 · L 形包角 · 液位管），
 * 同一件东西两种画法。代价是每次改动都要做两遍，而实拍一次只看得了一个主题：
 * §7.7「深色液位管看不出水位」就是这么漏过去的。
 */
class GuiMaterialTexturesTest {

    private static final Path MATERIAL_DIR = Path.of("src", "main", "resources", "assets", "heavyseas", "textures", "gui", "material");
    private static final Path GUI_MATERIAL = Path.of("src", "client", "java", "io", "github", "heavyseasmc", "mod", "client", "GuiMaterial.java");
    private static final Path PORTRAIT_DIR = Path.of("src", "main", "resources", "assets", "heavyseas", "textures", "gui", "portrait");
    private static final Path ROSTER = Path.of("..", "data", "roster", "default.json");
    private static final Path ICON_DIR = Path.of("src", "main", "resources", "assets", "heavyseas", "textures", "gui", "icon");
    private static final Path WEATHER = Path.of("..", "data", "weather", "default.json");
    /** 每个主题至少这么多张；少于它就是没读到，不是对上了。 */
    private static final int MIN_TEXTURES = 8;
    /** 角色至少这么多个；少于它就是没读到。 */
    private static final int MIN_CHARACTERS = 6;
    /** 头像最大画到 72 物理像素，贴图不小于它的两倍。 */
    private static final int MIN_PORTRAIT = 144;
    /** 至少这么多张贴图的 alpha 版图上真有东西（有透明也有不透明）；少于它，同形那一条就是在比空气。 */
    private static final int MIN_SHAPED = 4;
    /** 天候至少这么多种；少于它就是没读到。 */
    private static final int MIN_WEATHER = 8;
    /** 上带图标画到 12 个 GUI 单位，界面尺寸 4 时 48 物理像素 —— 贴图不小于它。 */
    private static final int MIN_ICON = 48;

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

    /**
     * 两个主题的贴图<b>逐像素同形</b>：alpha 版图必须一个比特不差，颜色随便。
     *
     * <p>⚠️它只查得了 alpha —— 不透明区域里的形状差异（比如一条线挪了位置）它看不见。
     * 真正把关的是管线里的 {@code check_one_shape()}：同一份生成器代入两套调色板，
     * 必须逐字节还原出两个主题的母版。这一条是**出货那一侧**的对照 —— 管线不在这个仓库里，
     * 而玩家拿到的正是贴图。
     */
    @Test
    @DisplayName("两个主题的贴图逐像素同形：只有颜色不同")
    void themesDifferOnlyInShape() throws IOException {
        Map<String, int[]> light = sizes("light");
        assertTrue(light.size() >= MIN_TEXTURES, "浅色主题只读到 " + light.size() + " 张贴图 —— 没在查");
        int informative = 0;
        for (String name : new java.util.TreeSet<>(light.keySet())) {
            BufferedImage a = ImageIO.read(MATERIAL_DIR.resolve("light").resolve(name).toFile());
            BufferedImage b = ImageIO.read(MATERIAL_DIR.resolve("dark").resolve(name).toFile());
            assertTrue(a != null && b != null, name + "：读不出来");
            assertEquals(a.getWidth() + "x" + a.getHeight(), b.getWidth() + "x" + b.getHeight(), name + "：两个主题的尺寸不一样");
            boolean sawClear = false;
            boolean sawInk = false;
            for (int y = 0; y < a.getHeight(); y++) {
                for (int x = 0; x < a.getWidth(); x++) {
                    int pa = a.getRGB(x, y) >>> 24;
                    int pb = b.getRGB(x, y) >>> 24;
                    assertEquals(pa, pb, name + " 第 " + x + "," + y + " 个像素：两个主题的形制不一样（alpha " + pa + " vs " + pb
                            + "）。只许换颜色 —— 形制改了就得两个主题一起改，重跑管线的 build_gui_material.py");
                    sawClear |= pa == 0;
                    sawInk |= pa == 255;
                }
            }
            if (sawClear && sawInk) {
                informative++;                            // 这一张的 alpha 版图真的画出了东西，不是一整片不透明
            }
        }
        assertTrue(informative >= MIN_SHAPED, "只有 " + informative + " 张贴图有透明区 —— 这一条等于什么都没查"
                + "（全是不透明的话，逐像素比 alpha 永远成立）");
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

    /**
     * 上带那一排图标：每一种天候一枚，阶段轮盘每一格一张，外加海鸥（ADR-0037 §7.12 第 2 条）。
     *
     * <p>缺一枚 Minecraft <b>不报错</b> —— 只在翻到那一张天候时画出一块紫黑格，而开发时未必翻得到它。
     * 轮盘那几格的张数<b>从 {@code Phase.values().length} 取</b>，不写死：
     * 判据里的每个字面量都是一颗定时器，而阶段是加得了的。
     */
    @Test
    @DisplayName("每一种天候都有图标，阶段轮盘的格数跟着 Phase 走")
    void topBandIconsAreComplete() throws IOException {
        java.util.List<String> ids = new java.util.ArrayList<>();
        com.google.gson.JsonParser.parseString(Files.readString(WEATHER, StandardCharsets.UTF_8)).getAsJsonObject()
                .getAsJsonArray("cards").forEach(c -> ids.add(c.getAsJsonObject().get("id").getAsString()));
        assertTrue(ids.size() >= MIN_WEATHER, "data/weather 里只读到 " + ids.size() + " 种天候 —— 没在查");

        java.util.List<String> want = new java.util.ArrayList<>();
        ids.forEach(id -> want.add("weather_" + id));
        for (int i = 0; i < io.github.heavyseasmc.engine.state.Phase.values().length; i++) {
            want.add("phase_" + i);
        }
        want.add("gull");
        for (String name : want) {
            Path p = ICON_DIR.resolve(name + ".png");
            assertTrue(Files.isRegularFile(p), "缺图标：" + name + ".png（重跑管线的 build_gui_material.py）");
            try (DataInputStream in = new DataInputStream(Files.newInputStream(p))) {
                in.skipBytes(16);
                int w = in.readInt();
                int h = in.readInt();
                assertTrue(w == h && w >= MIN_ICON, name + ".png 是 " + w + "x" + h + "：图标要是方的，且不小于 " + MIN_ICON);
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
