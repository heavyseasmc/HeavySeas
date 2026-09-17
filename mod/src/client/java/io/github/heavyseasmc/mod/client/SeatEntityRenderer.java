package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.world.SeatEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/**
 * 座位的渲染器：<b>什么都不画</b>（ADR-0024 §5）。
 *
 * <h2>为什么要有这么一个类</h2>
 * 实体类型注册了却没有渲染器，客户端在第一次看见它时会崩 —— 而<b>专用服务端测不出来</b>
 * （那里根本不渲染）。这与 ADR-0013 那条「客户端专用的代码只能写在 client 源码集」是同一个形状：
 * 缺的那一半只在真人进服时才炸。
 *
 * <h2>为什么不画点什么</h2>
 * M4 的船体是独立的 block display 组，会在靠岸演出里与座位一起移动。
 * 这里再画一层只会与船体重叠；大厅座位同样由锚点救生艇提供视觉本体。
 *
 * <p>❗{@link #render} 也要覆盖成空：父类默认会画名牌与阴影，而座位既没有名字也不该有影子。
 */
public final class SeatEntityRenderer extends EntityRenderer<SeatEntity> {

    /** 父类要一个贴图，但这里一笔都不画 —— 给一个不存在的路径是安全的，因为它永远不被取。 */
    private static final Identifier NONE = Identifier.of(HeavySeasMod.MOD_ID, "textures/entity/seat.png");

    public SeatEntityRenderer(EntityRendererFactory.Context context) {
        super(context);
    }

    @Override
    public Identifier getTexture(SeatEntity entity) {
        return NONE;
    }

    @Override
    public void render(SeatEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light) {
        // 故意什么都不画：连父类的名牌与阴影都不要。
    }
}
