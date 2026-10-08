package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.engine.data.RosterData;
import io.github.heavyseasmc.engine.data.RosterLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RosterConfigS2CTest {

    private static final RosterData ROSTER = RosterLoader.load(rosterFile());

    @Test
    void factoryCarriesTheRealUniqueCatalogAndAllThreePresets() {
        for (int players = 6; players <= 8; players++) {
            RosterConfigS2C packet = RosterConfigS2C.from(1234L, players, ROSTER);

            assertEquals(1234L, packet.anchor());
            assertEquals(players, packet.players());
            assertEquals(8, packet.characters().size());
            assertEquals(packet.characters().size(), Set.copyOf(packet.characters()).size());
            assertPreset(packet.characters(), packet.preset6(), 6);
            assertPreset(packet.characters(), packet.preset7(), 7);
            assertPreset(packet.characters(), packet.preset8(), 8);
        }
    }

    @Test
    void factoryRejectsPlayerCountsOutsideTheScreenContract() {
        assertThrows(IllegalArgumentException.class, () -> RosterConfigS2C.from(0L, 5, ROSTER));
        assertThrows(IllegalArgumentException.class, () -> RosterConfigS2C.from(0L, 9, ROSTER));
    }

    /**
     * 数据包里的角色多于 8 个（数据那一侧不限个数，只限选出来的 6–8 人）：目录照样编得出去（审查 2026-10-07 U8）。
     * 原先目录也按 8 个封顶，编码在网络线程上失败，坐在演习艇里打开阵容面板的人被踢下线。
     */
    @Test
    void catalogLargerThanAVoyageStillEncodes() {
        List<String> nine = java.util.stream.IntStream.range(0, 9).mapToObj(i -> "c" + i).toList();
        RosterConfigS2C packet = new RosterConfigS2C(1L, 6, nine, nine.subList(0, 6), nine.subList(0, 7), nine.subList(0, 8));
        net.minecraft.network.RegistryByteBuf buf = new net.minecraft.network.RegistryByteBuf(
                io.netty.buffer.Unpooled.buffer(), net.minecraft.registry.DynamicRegistryManager.EMPTY);
        try {
            RosterConfigS2C.CODEC.encode(buf, packet);
            assertEquals(packet, RosterConfigS2C.CODEC.decode(buf), "编出去再读回来要是同一包");
        } finally {
            buf.release();
        }
        List<String> tooMany = java.util.stream.IntStream.range(0, 40).mapToObj(i -> "c" + i).toList();
        assertThrows(IllegalArgumentException.class,
                () -> new RosterConfigS2C(1L, 6, tooMany, nine.subList(0, 6), nine.subList(0, 7), nine.subList(0, 8)),
                "装不下的目录要在造包这一刻就拒（编码是异步的，到那时才失败就接不住、会踢人）");
    }

    private static void assertPreset(List<String> catalog, List<String> preset, int players) {
        assertEquals(players, preset.size());
        assertEquals(preset.size(), Set.copyOf(preset).size(), players + " 人预设不得重复角色");
        assertTrue(catalog.containsAll(preset));
    }

    private static Path rosterFile() {
        for (Path candidate : List.of(
                Path.of("data", "roster", "default.json"),
                Path.of("..", "data", "roster", "default.json"))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到仓库 data/roster/default.json");
    }
}
