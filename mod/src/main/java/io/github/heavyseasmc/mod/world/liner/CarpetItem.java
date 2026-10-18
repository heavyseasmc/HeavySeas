package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;

/**
 * 地毯的物品（ADR-0093 B3 · B14，用户 2026-10-07「按倾向」）：地毯是地板的一层表面 —— 拿它点一格柚木甲板（哪一面都行），
 * 那一格就换成铺了毯的地板，板的走向记进 axis；点别的方块不放（不能当一般方块随处摆，也就不会有悬空的地毯）。
 * 揭掉用剪刀（{@link LinerBlock}）。
 */
final class CarpetItem extends BlockItem {

    CarpetItem(Block block, Settings settings) {
        super(block, settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        World world = ctx.getWorld();
        BlockPos pos = ctx.getBlockPos();
        BlockState floor = world.getBlockState(pos);
        if (!floor.isOf(LinerBlocks.TEAK_DECK)) {
            return ActionResult.FAIL;
        }
        if (!world.isClient) {
            BlockState carpet = LinerBlocks.CARPET.connect(
                    LinerBlocks.CARPET.getDefaultState().with(LinerBlocks.AXIS, floor.get(LinerBlocks.AXIS)), world, pos);
            world.setBlockState(pos, carpet, Block.NOTIFY_ALL);
            world.playSound(null, pos, SoundEvents.BLOCK_WOOL_PLACE, SoundCategory.BLOCKS, 1.0f, 0.8f);
            PlayerEntity player = ctx.getPlayer();
            world.emitGameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Emitter.of(player, carpet));
            if (player == null || !player.getAbilities().creativeMode) {
                ctx.getStack().decrement(1);
            }
        }
        return ActionResult.success(world.isClient);
    }
}
