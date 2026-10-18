package io.github.heavyseasmc.mod.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 伤势裂纹四档的贴图（2026-10-13，木刻「粗凿圆环」）：读 PNG 本身，不读烘它的脚本 —— 两边不同源。
 *
 * <ul>
 *   <li>四档齐全、每一倍四档同尺寸、是方的、两两不同（复制粘贴同一张也会「文件都在」）；</li>
 *   <li><b>不遮脸</b>：头像那个圆里，落在内 60% 半径（脸）里的墨占全部墨不超过 5%（用户 10-13：「不遮脸」，
 *       联络表上看的「脸区墨 x%」就是这个量）。</li>
 * </ul>
 * 正向对照：每一张圆里都得真有墨（覆盖 ≥ 1%）—— 一张全透明的贴图「脸区墨 0%」，与「没盖住脸」输出一样。
 */
class HurtCrackTextureTest {

    /** 测试的工作目录是 {@code mod/}。 */
    private static final Path ASSETS = Path.of("src", "main", "resources", "assets", "heavyseas");
    private static final double FACE_RADIUS = 0.6;
    private static final double FACE_SHARE_MAX = 0.05;
    private static final double MIN_COVERAGE = 0.01;

    private static List<HudPart> tiers() {
        List<HudPart> out = new ArrayList<>();
        for (HudPart p : HudPart.values()) {
            if (p.id().startsWith("crack_")) {
                out.add(p);
            }
        }
        return out;
    }

    private static BufferedImage read(HudPart part, int bake) throws IOException {
        Path png = ASSETS.resolve(part.path("common", bake));
        assertTrue(Files.isRegularFile(png), "缺 " + png.toAbsolutePath());
        BufferedImage img = ImageIO.read(png.toFile());
        assertTrue(img != null, png + " 读不出来");
        return img;
    }

    @Test
    @DisplayName("裂纹四档：每一倍都在、四档同尺寸、是方的、两两不同")
    void fourTiersSameSize() throws IOException {
        List<HudPart> tiers = tiers();
        assertEquals(4, tiers.size(), "裂纹应有四档（轻伤 · 重伤 · 昏迷 · 濒死），表里认出 " + tiers);
        for (int bake = 1; bake <= HudPart.BAKES.length; bake++) {
            int[] size = null;
            List<int[]> pixels = new ArrayList<>();
            for (HudPart part : tiers) {
                BufferedImage img = read(part, bake);
                int[] wh = {img.getWidth(), img.getHeight()};
                assertEquals(wh[0], wh[1], part.id() + " " + bake + "x 不是方的：" + wh[0] + "×" + wh[1]);
                if (size == null) {
                    size = wh;
                } else {
                    assertTrue(Arrays.equals(size, wh), part.id() + " " + bake + "x 是 " + wh[0] + "×" + wh[1]
                            + "，与同一倍的其它档 " + size[0] + "×" + size[1] + " 不一样");
                }
                pixels.add(img.getRGB(0, 0, wh[0], wh[1], null, 0, wh[0]));
            }
            for (int i = 0; i < pixels.size(); i++) {
                for (int j = i + 1; j < pixels.size(); j++) {
                    assertTrue(!Arrays.equals(pixels.get(i), pixels.get(j)),
                            tiers.get(i).id() + " 与 " + tiers.get(j).id() + " 的 " + bake + "x 一模一样");
                }
            }
        }
    }

    @Test
    @DisplayName("裂纹不遮脸：内 60% 半径里的墨占全部墨 ≤ 5%，且圆里确实有墨")
    void faceStaysClear() throws IOException {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (HudPart part : tiers()) {
            for (int bake = 1; bake <= HudPart.BAKES.length; bake++) {
                BufferedImage img = read(part, bake);
                int n = img.getWidth();
                double c = (n - 1) / 2.0;
                double face = 0;
                double disc = 0;
                int discPixels = 0;
                for (int y = 0; y < n; y++) {
                    for (int x = 0; x < n; x++) {
                        double r = Math.hypot(x - c, y - c) / (n / 2.0);
                        if (r >= 1) {
                            continue;
                        }
                        double a = ((img.getRGB(x, y) >>> 24) & 0xff) / 255.0;
                        disc += a;
                        discPixels++;
                        if (r < FACE_RADIUS) {
                            face += a;
                        }
                    }
                }
                checked++;
                double coverage = disc / discPixels;
                double share = disc == 0 ? 0 : face / disc;
                if (coverage < MIN_COVERAGE) {
                    problems.add(part.id() + " " + bake + "x 圆里几乎没有墨（覆盖 " + pct(coverage) + "）—— 量的不是裂纹");
                } else if (share > FACE_SHARE_MAX) {
                    problems.add(part.id() + " " + bake + "x 脸区（内 60% 半径）墨占 " + pct(share) + "，超过 "
                            + pct(FACE_SHARE_MAX));
                }
            }
        }
        assertEquals(4 * HudPart.BAKES.length, checked, "只量了 " + checked + " 张");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /** 轻伤档每一倍在头像那个圆里至少盖这么多（2026-10-13 第二轮验收 50：40 px 头像上轻伤那一道几乎看不出，当时 2.5%）。 */
    private static final double LIGHT_MIN_COVERAGE = 0.04;

    @Test
    @DisplayName("轻伤档在 40 px 头像上看得出：每一倍圆里覆盖 ≥ 4%（笔画加粗过；脸区仍由上一条守）")
    void lightTierReadsAtFortyPixels() throws IOException {
        List<String> problems = new ArrayList<>();
        for (int bake = 1; bake <= HudPart.BAKES.length; bake++) {
            BufferedImage img = read(HudPart.CRACK_LIGHT, bake);
            int n = img.getWidth();
            double c = (n - 1) / 2.0;
            double disc = 0;
            int discPixels = 0;
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    if (Math.hypot(x - c, y - c) / (n / 2.0) < 1) {
                        disc += ((img.getRGB(x, y) >>> 24) & 0xff) / 255.0;
                        discPixels++;
                    }
                }
            }
            double coverage = disc / Math.max(1, discPixels);
            if (coverage < LIGHT_MIN_COVERAGE) {
                problems.add("crack_light " + bake + "x 圆里只盖了 " + pct(coverage) + "，低于 " + pct(LIGHT_MIN_COVERAGE));
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    private static String pct(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", v * 100);
    }
}
