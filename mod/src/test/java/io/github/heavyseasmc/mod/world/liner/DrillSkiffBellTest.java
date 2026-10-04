package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 开局的钟认哪一口（ADR-0084 第三轮）：演习艇那一块往外放宽 {@link DrillSkiff#BELL_MARGIN} 格之内（含）的钟。
 * 生成器摆的钟每一格都在这个范围里由 docs 的 deck_shots.check 核对（它读的是同一个常数）；这里只钉住边界：放宽几格、含不含边上那一格。
 */
final class DrillSkiffBellTest {

    @Test
    void onlyBellsHuggingTheDrillSkiffCount() {
        // 演习艇的那一块：模板 11 × 9 × 25 顺时针转 90° 摆下去（右舷 1 号艇：x0 … x0 + 24 · y 49 … 57 · z 29 … 39）
        BlockBox area = new BlockBox(226, 49, 29, 250, 57, 39);
        int m = DrillSkiff.BELL_MARGIN;
        assertEquals(true, m >= 1, "钟最下面一层落在甲板上，比艇的那一块低一格");
        // 艇里、艇的边上、往外 m 格（含）都认
        assertEquals(true, DrillSkiff.nearDrillSkiff(area, new BlockPos(230, 52, 34)));
        assertEquals(true, DrillSkiff.nearDrillSkiff(area, new BlockPos(226, 48, 30)), "钟架最下面一层（甲板上一格）");
        assertEquals(true, DrillSkiff.nearDrillSkiff(area, new BlockPos(226 - m, 49 - m, 29 - m)));
        assertEquals(true, DrillSkiff.nearDrillSkiff(area, new BlockPos(250 + m, 57 + m, 39 + m)));
        // 往外 m + 1 格就不认（任何一个方向）
        assertEquals(false, DrillSkiff.nearDrillSkiff(area, new BlockPos(226 - m - 1, 52, 34)));
        assertEquals(false, DrillSkiff.nearDrillSkiff(area, new BlockPos(230, 49 - m - 1, 34)));
        assertEquals(false, DrillSkiff.nearDrillSkiff(area, new BlockPos(230, 52, 29 - m - 1)));
        assertEquals(false, DrillSkiff.nearDrillSkiff(area, new BlockPos(250 + m + 1, 52, 34)));
    }
}
