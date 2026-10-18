package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.world.GullEntity;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/**
 * 海鸥的模型（ADR-0034 §5.4）：身 6×4×10 · 头 4×4×4 · 两翼各 8×1×4 · 尾 4×1×5，贴图 64×32。
 *
 * <p>展开图的位置与 {@code docs/tools/scene/scene_textures.py} 里画 gull.png 的那几段一一对应：
 * 身 (0,0) · 头 (0,14) · 左翼 (32,0) · 右翼 (32,6) · 尾 (32,12)。改一边要改另一边。
 * 海鸥永远在飞（位置由服务端算），所以只有一个动作：翅膀按年龄扇。
 */
public final class GullModel extends EntityModel<GullEntity> {

    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of(HeavySeasMod.MOD_ID, "gull"), "main");

    private final ModelPart root;
    private final ModelPart head;
    private final ModelPart leftWing;
    private final ModelPart rightWing;

    public GullModel(ModelPart root) {
        this.root = root;
        this.head = root.getChild("head");
        this.leftWing = root.getChild("left_wing");
        this.rightWing = root.getChild("right_wing");
    }

    public static TexturedModelData getTexturedModelData() {
        ModelData data = new ModelData();
        ModelPartData root = data.getRoot();
        root.addChild("body", ModelPartBuilder.create().uv(0, 0).cuboid(-3f, -2f, -5f, 6f, 4f, 10f),
                ModelTransform.pivot(0f, 18f, 0f));
        root.addChild("head", ModelPartBuilder.create().uv(0, 14).cuboid(-2f, -2f, -2f, 4f, 4f, 4f),
                ModelTransform.pivot(0f, 17f, -6f));
        root.addChild("left_wing", ModelPartBuilder.create().uv(32, 0).cuboid(0f, 0f, -2f, 8f, 1f, 4f),
                ModelTransform.pivot(3f, 16.5f, 0f));
        root.addChild("right_wing", ModelPartBuilder.create().uv(32, 6).cuboid(-8f, 0f, -2f, 8f, 1f, 4f),
                ModelTransform.pivot(-3f, 16.5f, 0f));
        root.addChild("tail", ModelPartBuilder.create().uv(32, 12).cuboid(-2f, 0f, 0f, 4f, 1f, 5f),
                ModelTransform.pivot(0f, 17f, 5f));
        return TexturedModelData.of(data, 64, 32);
    }

    @Override
    public void setAngles(GullEntity entity, float limbAngle, float limbDistance, float animationProgress,
                          float headYaw, float headPitch) {
        float flap = MathHelper.sin(animationProgress * 0.6f) * 0.7f;
        leftWing.roll = -flap;
        rightWing.roll = flap;
        head.yaw = headYaw * ((float) Math.PI / 180f) * 0.5f;
    }

    @Override
    public void render(MatrixStack matrices, VertexConsumer vertices, int light, int overlay, int color) {
        root.render(matrices, vertices, light, overlay, color);
    }
}
