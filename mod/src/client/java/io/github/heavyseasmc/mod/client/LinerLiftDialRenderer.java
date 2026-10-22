package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.world.liner.LinerLift;
import io.github.heavyseasmc.mod.world.liner.LinerLiftDialEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * 楼层指针盘的指针（2026-10-19，GeckoLib）：一根直的指针，每一帧按 {@link LinerLiftDialEntity#needleAngle} 转到那一刻的角度。
 * 朝向与明暗同栅门（{@link LinerLiftGateRenderer}）。骨骼 z 转角与方块模型绕 z 同向（liner_lift_rig.check_rig 照 GeckoLib 的规则核过）。
 */
public final class LinerLiftDialRenderer extends GeoBlockRenderer<LinerLiftDialEntity> {

    public LinerLiftDialRenderer() {
        super(new Model());
    }

    @Override
    protected Direction getFacing(LinerLiftDialEntity entity) {
        return entity.getCachedState().get(LinerLift.FACING).getOpposite();
    }

    @Override
    public RenderLayer getRenderType(LinerLiftDialEntity animatable, Identifier texture, @Nullable VertexConsumerProvider bufferSource,
                                     float partialTick) {
        return RenderLayer.getEntityCutout(texture);
    }

    @Override
    public void createVerticesOfQuad(GeoQuad quad, Matrix4f poseState, Vector3f normal, VertexConsumer buffer, int packedLight,
                                     int packedOverlay, int colour) {
        LinerCabinetRenderer.blockShadedQuad(quad, poseState, normal, buffer, packedLight, packedOverlay, colour);
    }

    static final class Model extends GeoModel<LinerLiftDialEntity> {

        @Override
        public Identifier getModelResource(LinerLiftDialEntity e) {
            return Identifier.of(HeavySeasMod.MOD_ID, "geo/block/liner/lift_dial_needle.geo.json");
        }

        @Override
        public Identifier getTextureResource(LinerLiftDialEntity e) {
            return Identifier.of(HeavySeasMod.MOD_ID, "textures/block/liner/lift/dial.png");
        }

        @Override
        public Identifier getAnimationResource(LinerLiftDialEntity e) {
            return Identifier.of(HeavySeasMod.MOD_ID, "animations/block/liner/lift_dial.animation.json");
        }

        @Override
        public void setCustomAnimations(LinerLiftDialEntity e, long instanceId, AnimationState<LinerLiftDialEntity> state) {
            GeoBone needle = getAnimationProcessor().getBone("needle");
            if (needle != null) {
                needle.setRotZ((float) Math.toRadians(e.needleAngle(System.nanoTime())));
            }
        }
    }
}
