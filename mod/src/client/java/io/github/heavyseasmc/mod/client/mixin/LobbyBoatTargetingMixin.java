package io.github.heavyseasmc.mod.client.mixin;

import io.github.heavyseasmc.mod.world.LobbyBoatEntity;
import io.github.heavyseasmc.mod.world.LobbyBoatGeometry;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds only our oversized hull to vanilla's nearby-section candidates without extending reach. */
@Mixin(GameRenderer.class)
public abstract class LobbyBoatTargetingMixin {

    @Inject(method = "findCrosshairTarget", at = @At("RETURN"), cancellable = true)
    private void heavyseas$targetCruiseHull(Entity camera, double blockRange, double entityRange, float tickDelta,
                                          CallbackInfoReturnable<HitResult> result) {
        if (camera.isSpectator()) {
            return;
        }
        Vec3d eye = camera.getCameraPosVec(tickDelta);
        Vec3d end = eye.add(camera.getRotationVec(tickDelta).multiply(entityRange));
        HitResult nearest = result.getReturnValue();
        // Vanilla keeps an occluding wall's position when it is outside block reach and becomes MISS.
        double distance = Math.min(entityRange * entityRange, eye.squaredDistanceTo(nearest.getPos()));
        // SectionedEntityCache pads entity origins by only two blocks, not by each entity's size.
        Box origins = new Box(eye, end).expand(LobbyBoatGeometry.LENGTH,
                LobbyBoatGeometry.HEIGHT, LobbyBoatGeometry.LENGTH);
        for (LobbyBoatEntity hull : camera.getWorld().getEntitiesByType(LobbyBoatEntity.TYPE,
                origins, Entity::canHit)) {
            Vec3d hit = hull.getBoundingBox().contains(eye) ? eye
                    : hull.getBoundingBox().raycast(eye, end).orElse(null);
            if (hit != null && eye.squaredDistanceTo(hit) < distance) {
                distance = eye.squaredDistanceTo(hit);
                nearest = new EntityHitResult(hull, hit);
            }
        }
        result.setReturnValue(nearest);
    }
}
