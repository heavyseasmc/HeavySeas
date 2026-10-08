package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LinerNeighborUpdateTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
    }

    private static WorldAccess world(Map<BlockPos, BlockState> blocks, AtomicInteger reads) {
        return (WorldAccess) Proxy.newProxyInstance(WorldAccess.class.getClassLoader(), new Class<?>[]{WorldAccess.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getBlockState")) {
                        reads.incrementAndGet();
                        return blocks.getOrDefault(args[0], Blocks.AIR.getDefaultState());
                    }
                    throw new AssertionError("Unexpected world access: " + method.getName());
                });
    }

    @Test
    void directionalFilteringMatchesAFullRecomputeForEveryRegisteredState() {
        AtomicInteger reads = new AtomicInteger();
        WorldAccess empty = world(Map.of(), reads);
        int checked = 0;
        int changed = 0;
        for (LinerBlock block : LinerBlocks.all().values()) {
            for (BlockState state : block.getStateManager().getStates()) {
                BlockState stable = block.connect(state, empty, BlockPos.ORIGIN);
                for (Direction direction : Direction.values()) {
                    BlockPos at = BlockPos.ORIGIN.offset(direction);
                    BlockState neighbor = block.getDefaultState();
                    WorldAccess update = world(Map.of(at, neighbor), reads);
                    BlockState expected = block.connect(stable, update, BlockPos.ORIGIN);
                    BlockState actual = block.getStateForNeighborUpdate(stable, direction, neighbor,
                            update, BlockPos.ORIGIN, at);
                    assertEquals(expected, actual, block + " / " + direction + " / " + state);
                    checked++;
                    if (expected != stable) {
                        changed++;
                    }
                }
            }
        }
        assertTrue(checked > 100, "必须覆盖真实登记的状态，不能空跑");
        assertTrue(changed > 10, "正向对照：相邻同类方块确实改变了连接状态");
    }

    @Test
    void irrelevantDirectionsDoNotReadNeighbors() {
        AtomicInteger reads = new AtomicInteger();
        WorldAccess empty = world(Map.of(), reads);
        LinerBlock carpet = LinerBlocks.CARPET;
        BlockState stable = carpet.connect(carpet.getDefaultState(), empty, BlockPos.ORIGIN);
        reads.set(0);
        assertSame(stable, carpet.getStateForNeighborUpdate(stable, Direction.UP, Blocks.AIR.getDefaultState(),
                empty, BlockPos.ORIGIN, BlockPos.ORIGIN.up()));
        assertEquals(0, reads.get());
        carpet.getStateForNeighborUpdate(stable, Direction.NORTH, Blocks.AIR.getDefaultState(),
                empty, BlockPos.ORIGIN, BlockPos.ORIGIN.north());
        assertTrue(reads.get() > 0, "正向对照：相关方向仍重算");
    }
}
