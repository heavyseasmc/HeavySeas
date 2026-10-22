package io.github.heavyseasmc.mod.world.liner;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Predicate;

/**
 * 电梯栅门 / 指针盘的方块实体类型（2026-10-19，GeckoLib 画动的那几块），以及「旧存档补方块实体」。
 *
 * <p>❗旧存档：方块实体只在方块状态变的那一刻才补建（1.21.1 {@code WorldChunk#setBlockState} 里那一段；读档时只读存了的）。
 * 换 jar 之前就摆在世界里的栅门、指针盘、柜子没有方块实体 —— 栅格、指针、柜门在开合一次之前都画不出来。
 * 所以区块载入时查一遍：这一节里有这几种方块（调色板一查，几乎不花时间）才逐格看，缺的补上（{@link #repair}）。
 */
public final class LinerLiftRigs {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    public static BlockEntityType<LinerLiftGateEntity> GATE;
    public static BlockEntityType<LinerLiftDialEntity> DIAL;

    private LinerLiftRigs() {
    }

    static void register() {
        GATE = Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of(HeavySeasMod.MOD_ID, "liner_lift_gate"),
                BlockEntityType.Builder.create(LinerLiftGateEntity::new, LinerLift.GATE).build(null));
        DIAL = Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of(HeavySeasMod.MOD_ID, "liner_lift_dial"),
                BlockEntityType.Builder.create(LinerLiftDialEntity::new, LinerLift.DIAL).build(null));
    }

    /** 这一格该不该挂方块实体（三种：栅门下半 · 指针盘左块 · 能开合的柜子原点那一格）。 */
    static boolean carries(BlockState s) {
        if (s.getBlock() instanceof LinerLift.LiftGate || s.getBlock() instanceof LinerLift.LiftDial) {
            return LinerLift.Rules.carriesRig(s);
        }
        return s.getBlock() instanceof LinerProp prop && prop.carriesBlockEntity(s);
    }

    private static final Predicate<BlockState> CANDIDATE = s -> s.getBlock() instanceof LinerLift.LiftGate
            || s.getBlock() instanceof LinerLift.LiftDial || s.getBlock() instanceof LinerProp;

    /** 区块载入（服务端）：缺方块实体的补上。→ 补了几个。 */
    public static int repair(ServerWorld world, WorldChunk chunk) {
        int made = 0;
        ChunkSection[] sections = chunk.getSectionArray();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty() || !section.hasAny(CANDIDATE)) {
                continue;
            }
            int y0 = chunk.sectionIndexToCoord(i) << 4;
            BlockPos.Mutable p = new BlockPos.Mutable();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState s = section.getBlockState(x, y, z);
                        if (!CANDIDATE.test(s) || !carries(s)) {
                            continue;
                        }
                        p.set(chunk.getPos().getStartX() + x, y0 + y, chunk.getPos().getStartZ() + z);
                        if (chunk.getBlockEntity(p, WorldChunk.CreationType.CHECK) == null
                                && chunk.getBlockEntity(p, WorldChunk.CreationType.IMMEDIATE) != null) {
                            made++;
                        }
                    }
                }
            }
        }
        if (made > 0) {
            chunk.setNeedsSaving(true);
            LOGGER.debug("Repaired {} missing liner block entities in chunk {}", made, chunk.getPos());
        }
        return made;
    }
}
