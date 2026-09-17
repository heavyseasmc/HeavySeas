package io.github.heavyseasmc.mod.world;

import com.mojang.serialization.MapCodec;
import io.github.heavyseasmc.mod.HeavySeasMod;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/** Placeable/recoverable overworld lobby anchor. The eight actual registrations are mounted seats. */
public final class LobbyBoatBlock extends Block {

    public static final Identifier ID = Identifier.of(HeavySeasMod.MOD_ID, "lobby_boat");
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final MapCodec<LobbyBoatBlock> CODEC = createCodec(LobbyBoatBlock::new);
    public static final LobbyBoatBlock BLOCK = new LobbyBoatBlock(
            AbstractBlock.Settings.copy(Blocks.DARK_OAK_PLANKS).strength(2.5f).nonOpaque());
    public static final BlockItem ITEM = new BlockItem(BLOCK, new Item.Settings());

    private LobbyBoatBlock(AbstractBlock.Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    public static void register() {
        Registry.register(Registries.BLOCK, ID, BLOCK);
        Registry.register(Registries.ITEM, ID, ITEM);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> entries.add(ITEM));
    }

    @Override
    protected MapCodec<? extends Block> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return getDefaultState().with(FACING, context.getHorizontalPlayerFacing());
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                                 BlockHitResult hit) {
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        return LobbyBoat.use(world, pos, state, player);
    }

    @Override
    protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock()) && !world.isClient) {
            LobbyBoat.clear(world, pos);
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }
}
