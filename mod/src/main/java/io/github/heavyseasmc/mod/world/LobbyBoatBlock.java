package io.github.heavyseasmc.mod.world;

import com.mojang.serialization.MapCodec;
import io.github.heavyseasmc.mod.HeavySeasMod;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
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

/** Placeable/recoverable overworld cruise-ship lobby anchor. The eight registrations are mounted seats. */
public final class LobbyBoatBlock extends BlockWithEntity {

    public static final Identifier ID = Identifier.of(HeavySeasMod.MOD_ID, "lobby_boat");
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final MapCodec<LobbyBoatBlock> CODEC = createCodec(LobbyBoatBlock::new);
    public static final LobbyBoatBlock BLOCK = new LobbyBoatBlock(
            AbstractBlock.Settings.copy(Blocks.DARK_OAK_PLANKS).strength(2.5f).nonOpaque());
    public static final BlockItem ITEM = new BlockItem(BLOCK, new Item.Settings());
    public static final BlockEntityType<LobbyBoatBlockEntity> TYPE =
            BlockEntityType.Builder.create(LobbyBoatBlockEntity::new, BLOCK).build(null);

    private LobbyBoatBlock(AbstractBlock.Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    public static void register() {
        Registry.register(Registries.BLOCK, ID, BLOCK);
        Registry.register(Registries.ITEM, ID, ITEM);
        Registry.register(Registries.BLOCK_ENTITY_TYPE, ID, TYPE);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> entries.add(ITEM));
    }

    @Override
    protected MapCodec<? extends LobbyBoatBlock> getCodec() {
        return CODEC;
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new LobbyBoatBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state,
                                                                BlockEntityType<T> type) {
        return world.isClient ? null : validateTicker(type, TYPE, LobbyBoatBlockEntity::tick);
    }

    @Override
    protected BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.ENTITYBLOCK_ANIMATED;
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
