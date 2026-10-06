package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.world.StandInEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 替身人形的画法：玩家模型 + Minecraft 自带的那几款默认皮肤，按角色 id 固定挑一款（同一个角色每局长一个样）。
 * 坐姿由 {@code LivingEntityRenderer} 按「骑着东西」自己摆，与坐在座位上的真人同一个姿势；头顶的名字是实体的自定义名。
 *
 * <p>默认皮肤有宽臂、细臂两种身形，两副模型都备着，按挑中的那款换。不画新贴图（美术 clean-room 那一条不碰）。
 */
public final class StandInRenderer extends LivingEntityRenderer<StandInEntity, PlayerEntityModel<StandInEntity>> {

    private final PlayerEntityModel<StandInEntity> wide;
    private final PlayerEntityModel<StandInEntity> slim;

    public StandInRenderer(EntityRendererFactory.Context context) {
        super(context, new PlayerEntityModel<>(context.getPart(EntityModelLayers.PLAYER), false), 0.5f);
        wide = getModel();
        slim = new PlayerEntityModel<>(context.getPart(EntityModelLayers.PLAYER_SLIM), true);
    }

    private static SkinTextures skin(StandInEntity entity) {
        String key = "heavyseas:stand_in:" + entity.character();
        return DefaultSkinHelper.getSkinTextures(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public void render(StandInEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light) {
        model = skin(entity).model() == SkinTextures.Model.SLIM ? slim : wide;
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
    }

    @Override
    public Identifier getTexture(StandInEntity entity) {
        return skin(entity).texture();
    }
}
