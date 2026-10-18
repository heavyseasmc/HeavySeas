package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.ui.HudPart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 贴图缩几级（{@link CardTexture#mipLevels}）：每一级短边至少 1 像素。
 *
 * <p>2026-09-30 D1 的叉与波纹只有 13 像素高，按固定 4 级缩到第 4 级是 0×0 —— 客户端进服预载就抛（ADR-0048 §9）。
 */
class CardTextureMipTest {

    @Test
    @DisplayName("短边 13：只缩 3 级（13 → 6 → 3 → 1），不缩到 0")
    void smallSideStopsBeforeZero() {
        assertEquals(3, CardTexture.mipLevels(13, 13, 4));
        assertEquals(3, CardTexture.mipLevels(27, 13, 4));
        assertEquals(4, CardTexture.mipLevels(26, 26, 4));
        assertEquals(4, CardTexture.mipLevels(600, 840, 4));
        assertEquals(0, CardTexture.mipLevels(1, 40, 4));
    }

    @Test
    @DisplayName("资源里每一张 GUI 贴图：按它缩的每一级短边都 ≥ 1")
    void everyShippedTextureHasNoEmptyLevel() throws IOException {
        Path root = Path.of("src/main/resources/assets/heavyseas/textures/gui");
        List<String> problems = new ArrayList<>();
        int read = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".png")).toList()) {
                int[] size = pngSize(p);
                int levels = CardTexture.mipLevels(size[0], size[1], 4);
                if ((Math.min(size[0], size[1]) >> levels) < 1) {
                    problems.add(root.relativize(p) + "：" + size[0] + "×" + size[1] + " 缩 " + levels + " 级");
                }
                read++;
            }
        }
        // 正向对照：确实扫到了东西，而且扫到了最小的那几件（13 像素的叉）
        assertTrue(read > 200, "只读到 " + read + " 张贴图 —— 没在查");
        int[] cross = pngSize(root.resolve("hud/common/" + HudPart.IC_CROSS40.id() + "_1x.png"));
        assertTrue(Math.min(cross[0], cross[1]) < 16, "最小那件不小于 16，这条判据没测到它要测的局面");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    private static int[] pngSize(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            byte[] head = in.readNBytes(24);
            int w = ((head[16] & 0xFF) << 24) | ((head[17] & 0xFF) << 16) | ((head[18] & 0xFF) << 8) | (head[19] & 0xFF);
            int h = ((head[20] & 0xFF) << 24) | ((head[21] & 0xFF) << 16) | ((head[22] & 0xFF) << 8) | (head[23] & 0xFF);
            return new int[]{w, h};
        }
    }
}
