package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.world.liner.LinerCabinetEntity;
import io.github.heavyseasmc.mod.world.liner.LinerProp;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * 柜子的门 · 抽屉 · 箱盖（ADR-0109 §2 第二版）：GeckoLib 画骨骼，朝向照方块的 facing（GeckoLib 自带的转法与方块状态的 y 转角同向，判据 f 核过）。
 * <p>明暗照方块的画法，不照生物的画法：生物的着色器按法线打两盏方向光，柜身（方块模型）是按面朝向乘一个固定系数（上 1.0 · 南北 0.8 ·
 * 东西 0.6 · 下 0.5），两种画法挨在一起，门与柜身会差一档明暗。这里每一面按转完之后的法线挑最近的轴、乘同一套系数进顶点色，
 * 法线一律写朝上 —— 生物着色器拿朝上的法线算出来是满亮度（两盏灯的系数加起来过 1，夹到 1），于是最终就只剩方块那一套系数。
 */
public final class LinerCabinetRenderer extends GeoBlockRenderer<LinerCabinetEntity> {

    public LinerCabinetRenderer() {
        super(new Model());
    }

    @Override
    public RenderLayer getRenderType(LinerCabinetEntity animatable, Identifier texture, @Nullable VertexConsumerProvider bufferSource,
                                     float partialTick) {
        return RenderLayer.getEntityCutout(texture);
    }

    @Override
    public boolean rendersOutsideBoundingBox(LinerCabinetEntity entity) {
        return true;                       // 衣柜两格高、餐边柜两格宽：原点那一格出了视野，另一格的门还在画面里
    }

    @Override
    public void createVerticesOfQuad(GeoQuad quad, Matrix4f poseState, Vector3f normal, VertexConsumer buffer, int packedLight,
                                     int packedOverlay, int colour) {
        int shaded = shade(colour, faceShade(normal.x(), normal.y(), normal.z()));
        for (GeoVertex vertex : quad.vertices()) {
            Vector3f p = vertex.position();
            Vector4f v = poseState.transform(new Vector4f(p.x(), p.y(), p.z(), 1.0f));
            buffer.vertex(v.x(), v.y(), v.z(), shaded, vertex.texU(), vertex.texV(), packedOverlay, packedLight, 0.0f, 1.0f, 0.0f);
        }
    }

    /** 方块模型的面明暗（游戏自带的 {@code ClientWorld#getBrightness} 不在下界时那四档）：按法线最近的轴。 */
    static float faceShade(float x, float y, float z) {
        float ax = Math.abs(x);
        float ay = Math.abs(y);
        float az = Math.abs(z);
        if (ay >= ax && ay >= az) {
            return y > 0 ? 1.0f : 0.5f;
        }
        return az >= ax ? 0.8f : 0.6f;
    }

    static int shade(int argb, float k) {
        int a = argb >>> 24;
        int r = Math.round(((argb >> 16) & 0xFF) * k);
        int g = Math.round(((argb >> 8) & 0xFF) * k);
        int b = Math.round((argb & 0xFF) * k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** 每件柜子一份模型 / 动画（按方块的词干），贴图是柜身那一张（rig_<贴图>：门与柜身同一张图，木纹接得上）。 */
    static final class Model extends GeoModel<LinerCabinetEntity> {

        private static LinerProp prop(LinerCabinetEntity e) {
            return (LinerProp) e.getCachedState().getBlock();
        }

        @Override
        public Identifier getModelResource(LinerCabinetEntity e) {
            return Identifier.of(HeavySeasMod.MOD_ID, "geo/block/liner/" + prop(e).spec().model() + ".geo.json");
        }

        @Override
        public Identifier getTextureResource(LinerCabinetEntity e) {
            return Identifier.of(HeavySeasMod.MOD_ID, "textures/block/liner/prop/rig_" + prop(e).spec().texture() + ".png");
        }

        @Override
        public Identifier getAnimationResource(LinerCabinetEntity e) {
            return Identifier.of(HeavySeasMod.MOD_ID, "animations/block/liner/" + prop(e).spec().model() + ".animation.json");
        }
    }
}
