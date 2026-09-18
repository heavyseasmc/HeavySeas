package io.github.heavyseasmc.mod.world;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入库的船体结构模板必须是 Minecraft 读得懂的、与布局说的同一条船。
 *
 * <p>模板由 {@code docs/tools/scene/hull_builder.py} 生成（源文件在那边，NBT 是生成物）。这里读的是<b>落盘的那份</b>：
 * 生成器改了没重跑、或有人手改了 NBT，都在这里红。判据与生成器正交 —— 用 Minecraft 自己的 NBT 读取器解析。
 */
final class HullStructureTest {

    /** 测试的工作目录是 {@code mod/}（Gradle 的默认）。 */
    private static final Path NBT = Path.of("src", "main", "resources", "data", "heavyseas", "structure", "boat_hull.nbt");

    /** 与 voyage/default.json 的 hull.anchor 同一个数：船头座位那一格必须是座板。 */
    private static final int[] ANCHOR = {2, 2, 15};

    /** 1.21.1 的 world_version（jar 内 version.json）。 */
    private static final int DATA_VERSION = 3955;

    @Test
    void hullTemplateIsAReadableStructureWithASeatAtTheAnchor() throws IOException {
        assertTrue(Files.isRegularFile(NBT), "船体模板不在：" + NBT.toAbsolutePath());
        NbtCompound root = NbtIo.readCompressed(NBT, NbtSizeTracker.ofUnlimitedBytes());

        assertEquals(DATA_VERSION, root.getInt("DataVersion"));
        NbtList size = root.getList("size", NbtElement.INT_TYPE);
        assertEquals(3, size.size());
        assertTrue(size.getInt(0) <= 48 && size.getInt(1) <= 48 && size.getInt(2) <= 48, "结构模板每边最多 48 格");
        assertTrue(size.getInt(2) >= 16, "8 座 × 2 格间距至少 15 格长，实际 " + size.getInt(2));

        NbtList palette = root.getList("palette", NbtElement.COMPOUND_TYPE);
        assertTrue(palette.size() >= 5, "调色板至少该有船板、甲板、座板、栏杆、灯笼，实际 " + palette.size());
        for (int i = 0; i < palette.size(); i++) {
            String name = palette.getCompound(i).getString("Name");
            assertTrue(name.startsWith("minecraft:") || name.startsWith("heavyseas:"),
                    "调色板里有别的命名空间的方块：" + name);
        }

        NbtList blocks = root.getList("blocks", NbtElement.COMPOUND_TYPE);
        assertTrue(blocks.size() >= 100, "一条船不该只有 " + blocks.size() + " 个方块 —— 生成器八成没跑全");
        Set<String> seen = new HashSet<>();
        String anchorBlock = null;
        for (int i = 0; i < blocks.size(); i++) {
            NbtCompound block = blocks.getCompound(i);
            NbtList pos = block.getList("pos", NbtElement.INT_TYPE);
            int x = pos.getInt(0);
            int y = pos.getInt(1);
            int z = pos.getInt(2);
            assertTrue(x >= 0 && x < size.getInt(0) && y >= 0 && y < size.getInt(1) && z >= 0 && z < size.getInt(2),
                    "方块越出 size：" + x + "," + y + "," + z);
            assertTrue(seen.add(x + "," + y + "," + z), "同一格写了两次：" + x + "," + y + "," + z);
            int state = block.getInt("state");
            assertTrue(state >= 0 && state < palette.size(), "state 下标越界：" + state);
            if (x == ANCHOR[0] && y == ANCHOR[1] && z == ANCHOR[2]) {
                anchorBlock = palette.getCompound(state).getString("Name");
            }
        }
        assertEquals("minecraft:dark_oak_slab", anchorBlock, "锚点那一格该是座板（布局 hull.anchor 指的就是它）");
    }
}
