package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 楼层指针盘的指针（2026-10-19，ADR-0109 §7「GeckoLib 推广」）：一根直的指针是 GeckoLib 的一根骨骼，挂在左块；
 * 客户端每一帧把它往方块状态 {@link LinerLift#FLOOR} 那一档的角度转过去（{@link #advance}），不是一换状态就跳过去。
 * 方块模板只剩盘面与转轴帽（任何一层都是同一块）。
 *
 * <p>指针的角度只活在客户端、不存档：第一次画直接停在那一档（{@link #advance} 收到 NaN 时）。
 */
public final class LinerLiftDialEntity extends BlockEntity implements GeoBlockEntity {

    /** 指针转多快（度 / 秒）：一层 20°，轿厢一层约走 1.2 秒 —— 比它快一点，跟得上又看得出在转。 */
    static final float SPEED = 30f;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private float shownAngle = Float.NaN;
    private long lastNanos;

    public LinerLiftDialEntity(BlockPos pos, BlockState state) {
        super(LinerLiftRigs.DIAL, pos, state);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 指针不靠关键帧：角度在画的那一刻算（LinerLiftDialRenderer 调 needleAngle）
    }

    /** 这一帧指针的角度（度，0 = 指向观者右手，逆时针为正）。客户端渲染线程调。 */
    public float needleAngle(long nanos) {
        BlockState s = getCachedState();
        float target = LinerLift.Rules.dialAngle(s.contains(LinerLift.FLOOR) ? s.get(LinerLift.FLOOR) : 0);
        float dt = lastNanos == 0 ? 0f : Math.max(0f, (nanos - lastNanos) / 1e9f);
        lastNanos = nanos;
        shownAngle = advance(shownAngle, target, dt);
        return shownAngle;
    }

    /** 往目标转一步（纯函数，单测用）：没画过（NaN）直接到位；一帧最多转 {@link #SPEED} × dt，不越过目标。 */
    static float advance(float shown, float target, float dt) {
        if (Float.isNaN(shown)) {
            return target;
        }
        float step = SPEED * Math.min(dt, 0.25f);                // 卡顿之后一帧不跳一大截
        float d = target - shown;
        return Math.abs(d) <= step ? target : shown + Math.signum(d) * step;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
