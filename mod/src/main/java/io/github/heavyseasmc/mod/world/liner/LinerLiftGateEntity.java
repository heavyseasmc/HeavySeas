package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 电梯栅门的一扇（2026-10-19，ADR-0109 §7「GeckoLib 推广」）：栅格（竖条 + 斜条）与中缝立梃是 GeckoLib 的两根骨骼，
 * 挂在这一扇的下半格，客户端由 {@code LinerLiftGateRenderer} 画上下两格。开门时栅格以外侧门框那条边为心沿横向压扁到外侧那一叠
 * （斜条跟着变陡，就是折叠栅门折起来的样子），立梃跟着滑过去；方块模板只剩不动的门框 · 门楣 · 轨。
 *
 * <p>与柜子（{@link LinerCabinetEntity}）同一个做法：服务端只管方块状态 {@link LinerLift#OPEN}，这里不存东西、不发包；
 * 动画按客户端看到的状态挑 —— 第一次画直接停在那一档（不在人眼前重播），之后一变就慢慢开 / 合。
 * 模型与动画由 docs 的 {@code liner_lift_rig.py} 出（{@code geo/block/liner/lift_gate_<左右>.geo.json} 等）。
 */
public final class LinerLiftGateEntity extends BlockEntity implements GeoBlockEntity {

    static final RawAnimation OPEN = RawAnimation.begin().thenPlayAndHold("open");
    static final RawAnimation CLOSE = RawAnimation.begin().thenPlayAndHold("close");
    static final RawAnimation OPENED = RawAnimation.begin().thenPlayAndHold("opened");
    static final RawAnimation CLOSED = RawAnimation.begin().thenPlayAndHold("closed");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    /** 上一次挑动画时看到的开 / 合；{@code null} = 还没画过。 */
    private Boolean shown;

    public LinerLiftGateEntity(BlockPos pos, BlockState state) {
        super(LinerLiftRigs.GATE, pos, state);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "fold", 0, this::pose));
    }

    private PlayState pose(AnimationState<LinerLiftGateEntity> state) {
        BlockState s = getCachedState();
        if (!s.contains(LinerLift.OPEN)) {
            return PlayState.STOP;
        }
        boolean open = s.get(LinerLift.OPEN);
        if (shown == null || open != shown) {
            boolean first = shown == null;
            shown = open;
            state.getController().setAnimation(pick(first, open));
        }
        return PlayState.CONTINUE;
    }

    /** 挑哪一段动画（纯函数，单测用）。 */
    static RawAnimation pick(boolean first, boolean open) {
        if (first) {
            return open ? OPENED : CLOSED;
        }
        return open ? OPEN : CLOSE;
    }

    /** 这一扇是左扇还是右扇（模型与动画按它挑）。 */
    public LinerLift.Side side() {
        BlockState s = getCachedState();
        return s.contains(LinerLift.SIDE) ? s.get(LinerLift.SIDE) : LinerLift.Side.LEFT;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
