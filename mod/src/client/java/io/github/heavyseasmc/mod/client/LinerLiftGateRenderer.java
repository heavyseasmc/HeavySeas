package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.world.liner.LinerLift;
import io.github.heavyseasmc.mod.world.liner.LinerLiftGateEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * 电梯栅门的栅格与中缝立梃（2026-10-19，GeckoLib）：一扇一份模型（左 / 右），挂在下半格、画上下两格。
 * <p>朝向：电梯的模板正面朝南、按 {@link LinerLift.Rules#yaw} 转（南 0°）；GeckoLib 按 facing 转时北是 0° —— 两者差 180°，
 * 所以交给 GeckoLib 的是 facing 的反方向。明暗照柜子那一套（{@link LinerCabinetRenderer#faceShade}）：与旁边的方块模型同一档。
 */
public final class LinerLiftGateRenderer extends GeoBlockRenderer<LinerLiftGateEntity> {

    public LinerLiftGateRenderer() {
        super(new Model());
    }

    @Override
    protected Direction getFacing(LinerLiftGateEntity entity) {
        return entity.getCachedState().get(LinerLift.FACING).getOpposite();
    }

    @Override
    public RenderLayer getRenderType(LinerLiftGateEntity animatable, Identifier texture, @Nullable VertexConsumerProvider bufferSource,
                                     float partialTick) {
        return RenderLayer.getEntityCutout(texture);
    }

    @Override
    public boolean rendersOutsideBoundingBox(LinerLiftGateEntity entity) {
        return true;                       // 两格高：下半格出了视野，上半格的栅格还在画面里
    }

    @Override
    public void createVerticesOfQuad(GeoQuad quad, Matrix4f poseState, Vector3f normal, VertexConsumer buffer, int packedLight,
                                     int packedOverlay, int colour) {
        LinerCabinetRenderer.blockShadedQuad(quad, poseState, normal, buffer, packedLight, packedOverlay, colour);
    }

    static final class Model extends GeoModel<LinerLiftGateEntity> {

        private static String side(LinerLiftGateEntity e) {
            return e.side() == LinerLift.Side.LEFT ? "left" : "right";
        }

        @Override
        public Identifier getModelResource(LinerLiftGateEntity e) {
            return Identifier.of(HeavySeasMod.MOD_ID, "geo/block/liner/lift_gate_" + side(e) + ".geo.json");
        }

        @Override
        public Identifier getTextureResource(LinerLiftGateEntity e) {
            return Identifier.of(HeavySeasMod.MOD_ID, "textures/block/liner/lift/gate.png");
        }

        @Override
        public Identifier getAnimationResource(LinerLiftGateEntity e) {
            return Identifier.of(HeavySeasMod.MOD_ID, "animations/block/liner/lift_gate_" + side(e) + ".animation.json");
        }
    }
}
