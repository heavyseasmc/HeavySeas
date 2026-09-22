package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.world.LobbyBoatBlockEntity;
import io.github.heavyseasmc.mod.world.LobbyBoatGeometry;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;

/** World-only scale: inventory and held item transforms keep their existing usable size. */
public final class LobbyBoatRenderer implements BlockEntityRenderer<LobbyBoatBlockEntity> {

    private final BlockRenderManager blocks;

    public LobbyBoatRenderer(BlockEntityRendererFactory.Context context) {
        blocks = context.getRenderManager();
    }

    @Override
    public void render(LobbyBoatBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertices, int light, int overlay) {
        BlockState state = entity.getCachedState();
        matrices.push();
        matrices.translate(0.5, LobbyBoatGeometry.MODEL_LIFT, 0.5);
        matrices.scale(LobbyBoatGeometry.WORLD_SCALE, LobbyBoatGeometry.WORLD_SCALE,
                LobbyBoatGeometry.WORLD_SCALE);
        matrices.translate(-0.5, 0, -0.5);
        blocks.getModelRenderer().render(matrices.peek(),
                vertices.getBuffer(RenderLayers.getEntityBlockLayer(state, false)),
                state, blocks.getModel(state), 1f, 1f, 1f, light, overlay);
        matrices.pop();
    }

    @Override
    public boolean rendersOutsideBoundingBox(LobbyBoatBlockEntity entity) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return LobbyBoatGeometry.RENDER_DISTANCE;
    }
}
