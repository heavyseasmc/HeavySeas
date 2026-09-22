package io.github.heavyseasmc.mod.world;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/** The persisted block is the authority; its hull interaction entity is a disposable projection. */
public final class LobbyBoatBlockEntity extends BlockEntity {

    private int ticks;

    public LobbyBoatBlockEntity(BlockPos pos, BlockState state) {
        super(LobbyBoatBlock.TYPE, pos, state);
    }

    public static void tick(World rawWorld, BlockPos pos, BlockState state, LobbyBoatBlockEntity anchor) {
        if (rawWorld instanceof ServerWorld world && anchor.ticks++ % 20 == 0) {
            LobbyBoat.maintain(world, pos, state.get(LobbyBoatBlock.FACING));
        }
    }

    /** Old saves contain the same block ID, but predate its block entity. */
    public static void restoreLegacyAnchors(ServerWorld world, WorldChunk chunk) {
        ChunkSection[] sections = chunk.getSectionArray();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            ChunkSection section = sections[sectionIndex];
            if (!section.hasAny(state -> state.isOf(LobbyBoatBlock.BLOCK))) {
                continue;
            }
            int bottom = chunk.getBottomY() + sectionIndex * 16;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 0; y < 16; y++) {
                        if (section.getBlockState(x, y, z).isOf(LobbyBoatBlock.BLOCK)) {
                            BlockPos pos = new BlockPos(chunk.getPos().getStartX() + x, bottom + y,
                                    chunk.getPos().getStartZ() + z);
                            if (chunk.getBlockEntity(pos, WorldChunk.CreationType.CHECK) == null
                                    && chunk.getBlockEntity(pos, WorldChunk.CreationType.IMMEDIATE) != null) {
                                chunk.setNeedsSaving(true);
                            }
                        }
                    }
                }
            }
        }
    }
}
