package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.world.GullEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.util.Identifier;

/** 海鸥的渲染器（ADR-0034 §5.4）：自绘模型加 64×32 贴图，取代 M4 借用的鹦鹉渲染器。 */
public final class GullRenderer extends MobEntityRenderer<GullEntity, GullModel> {

    private static final Identifier TEXTURE = Identifier.of(HeavySeasMod.MOD_ID, "textures/entity/gull.png");

    public GullRenderer(EntityRendererFactory.Context context) {
        super(context, new GullModel(context.getPart(GullModel.LAYER)), 0.3f);
    }

    @Override
    public Identifier getTexture(GullEntity entity) {
        return TEXTURE;
    }
}
