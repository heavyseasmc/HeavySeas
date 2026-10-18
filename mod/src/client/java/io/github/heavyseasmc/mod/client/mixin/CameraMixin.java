package io.github.heavyseasmc.mod.client.mixin;

import io.github.heavyseasmc.mod.client.LiftRideEffect;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 坐电梯时镜头轻轻上下晃（{@link LiftRideEffect#bob}）：注在 {@code Camera.update} 的 TAIL，镜头位置算完之后再往上挪一点。
 * 只挪画面，不挪人：人的位置、碰撞、发给服务端的坐标都不受影响。
 *
 * <p>目标签名是 1.21.1 的（{@code update(BlockView, Entity, boolean, boolean, float)} · {@code setPos(Vec3d)} · {@code getPos()}）；
 * {@code defaultRequire: 1}，升级 MC 后对不上时客户端启动即崩，而不是坐电梯时安静地不晃。
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    protected abstract void setPos(Vec3d pos);

    @Shadow
    public abstract Vec3d getPos();

    @Inject(method = "update", at = @At("TAIL"))
    private void heavyseas$liftBob(BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta,
                                   CallbackInfo ci) {
        double dy = LiftRideEffect.bob();
        if (dy != 0) {
            setPos(getPos().add(0, dy, 0));
        }
    }
}
