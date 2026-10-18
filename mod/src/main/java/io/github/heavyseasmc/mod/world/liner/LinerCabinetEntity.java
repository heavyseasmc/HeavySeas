package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 能开合的柜子（ADR-0109 §2 第二版，用户 2026-10-17 选 GeckoLib）：门 · 抽屉 · 箱盖是 GeckoLib 的骨骼，这一个方块实体挂在整件的「原点」那一格，
 * 客户端由 {@code LinerCabinetRenderer} 画。服务端只管方块状态 {@link LinerProp#OPEN}（开合、存档都还是它）；这里不存任何东西，
 * 也不往客户端发包 —— 动画按客户端看到的方块状态自己挑：
 * <ul>
 *   <li>刚进视野（第一次画）：开着就直接停在开着的样子（opened），关着停在关着的样子 —— 不在人眼前重播一次开门；</li>
 *   <li>之后方块状态一变：开（open）/ 合（close），慢慢转 / 拉；</li>
 *   <li>开的那一刻看前面（皮箱是上面）那几格空不空（{@link LinerProp.Rules#swingBoxes}）：空着开到底，不空只开到「虚掩」（_tight），
 *       不伸出这一件自己的格子。合的时候照开的那一档合回来。</li>
 * </ul>
 * 动画文件与模型文件由 docs 的 liner_props_cabinets.py · liner_props_rig.py 出（{@code geo/block/liner/<件>.geo.json} ·
 * {@code animations/block/liner/<件>.animation.json}）。
 */
public final class LinerCabinetEntity extends BlockEntity implements GeoBlockEntity {

    private static final RawAnimation OPEN = RawAnimation.begin().thenPlayAndHold("open");
    private static final RawAnimation CLOSE = RawAnimation.begin().thenPlayAndHold("close");
    private static final RawAnimation OPENED = RawAnimation.begin().thenPlayAndHold("opened");
    private static final RawAnimation CLOSED = RawAnimation.begin().thenPlayAndHold("closed");
    private static final RawAnimation OPEN_TIGHT = RawAnimation.begin().thenPlayAndHold("open_tight");
    private static final RawAnimation CLOSE_TIGHT = RawAnimation.begin().thenPlayAndHold("close_tight");
    private static final RawAnimation OPENED_TIGHT = RawAnimation.begin().thenPlayAndHold("opened_tight");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    /** 上一次挑动画时看到的开 / 合；{@code null} = 还没画过。 */
    private Boolean shown;
    /** 这一次开的是「虚掩」那一档（前面有东西）。 */
    private boolean tight;

    public LinerCabinetEntity(BlockPos pos, BlockState state) {
        super(LinerCabinets.TYPE, pos, state);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "open", 0, this::pose));
    }

    private PlayState pose(AnimationState<LinerCabinetEntity> state) {
        BlockState s = getCachedState();
        if (!s.contains(LinerProp.OPEN)) {
            return PlayState.STOP;
        }
        boolean open = s.get(LinerProp.OPEN);
        if (shown == null || open != shown) {
            boolean first = shown == null;
            shown = open;
            if (open) {
                tight = !roomToSwing();
            }
            state.getController().setAnimation(pick(first, open, tight));
        }
        return PlayState.CONTINUE;
    }

    /** 挑哪一段动画（纯函数，单测用）。 */
    static RawAnimation pick(boolean first, boolean open, boolean tight) {
        if (first) {
            return open ? (tight ? OPENED_TIGHT : OPENED) : CLOSED;
        }
        return open ? (tight ? OPEN_TIGHT : OPEN) : (tight ? CLOSE_TIGHT : CLOSE);
    }

    /** 开到底要伸进去的那几只盒子（{@link LinerProp.Rules#swingBoxes}）里，有没有别的方块的轮廓。 */
    boolean roomToSwing() {
        World world = getWorld();
        BlockState s = getCachedState();
        if (world == null || !(s.getBlock() instanceof LinerProp prop)) {
            return false;
        }
        for (double[] b : LinerProp.Rules.swingBoxes(prop.spec().kind())) {
            Box box = LinerProp.Rules.pieceBoxToWorld(b, pos, s.get(LinerProp.FACING)).contract(1.0e-4);
            for (BlockPos p : BlockPos.iterate(MathHelper.floor(box.minX), MathHelper.floor(box.minY), MathHelper.floor(box.minZ),
                    MathHelper.floor(box.maxX), MathHelper.floor(box.maxY), MathHelper.floor(box.maxZ))) {
                VoxelShape shape = world.getBlockState(p).getOutlineShape(world, p);
                if (shape.isEmpty()) {
                    continue;
                }
                for (Box part : shape.getBoundingBoxes()) {
                    if (part.offset(p).intersects(box)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
