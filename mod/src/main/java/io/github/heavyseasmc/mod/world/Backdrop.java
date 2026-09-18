package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.data.VoyageLayout;
import io.github.heavyseasmc.mod.state.GameComponent;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity.ItemDisplayEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 岸的布景（ADR-0034 §5.3）：靠岸那一刻生成在停靠点、模型先平移到远处，再用 display 的变换插值滑回来。
 *
 * <h2>为什么是变换插值，不是传送</h2>
 * 1.21.1 的传送插值是对的（按 teleport_duration），但实体追踪器每 60 tick 补一个位置包、每 400 tick 一个绝对包，
 * 一段 80 tick 的滑入有 1/5 的概率被绝对包打断（§1.3 读源码得到的）。变换走 metadata，实体位置从头到尾不变，
 * 追踪器那两条与它无关 —— 顺带也把「靠岸期间没有位置包」这条判据变成了结构上的事。
 *
 * <h2>坐标</h2>
 * 实体 yaw 一律 0，于是变换里的平移就是世界坐标（billboard FIXED 且 yaw 0 时那一步旋转是单位阵）；
 * 模型朝向靠变换里的左旋转：模型的北面（−Z）转到朝船。数学都是纯函数，{@code BackdropMathTest} 核过四个岸向。
 *
 * <h2>生成与起滑不能在同一 tick</h2>
 * 出生包带的是那一刻的 metadata，同 tick 改完再发，客户端收到的已经是终点。{@code EndgamePhase} 先生成、隔一步再起滑。
 */
public final class Backdrop {

    /** 存盘时的指令标签：起服扫孤儿时认它（display 实体会进存档，与座位同一条理由）。 */
    public static final String TAG = "heavyseas_backdrop";

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private Backdrop() {
    }

    /** 在停靠点生成全部布景，模型平移到起滑点。布局没有布景时什么也不做。 */
    public static void spawn(ServerWorld world, GameComponent component, VoyageLayout layout) {
        clear(world, component);
        if (layout.backdrops().isEmpty()) {
            return;
        }
        Vector3f far = farTranslation(layout);
        List<UUID> ids = new ArrayList<>();
        for (VoyageLayout.Backdrop backdrop : layout.backdrops()) {
            ItemDisplayEntity entity = new ItemDisplayEntity(EntityType.ITEM_DISPLAY, world);
            Vec3d rest = layout.backdropRest(backdrop);
            entity.refreshPositionAndAngles(rest.x, rest.y, rest.z, 0f, 0f);
            entity.setItemStack(new ItemStack(Registries.ITEM.get(backdrop.item())));
            entity.setTransformationMode(ModelTransformationMode.NONE);
            entity.setViewRange(backdrop.viewRange());
            entity.setDisplayWidth(backdrop.boxWidth());
            entity.setDisplayHeight(backdrop.boxHeight());
            entity.setTeleportDuration(0);
            entity.setStartInterpolation(0);
            entity.setInterpolationDuration(0);
            entity.setTransformation(transformation(far, facing(layout, backdrop), backdrop.scale()));
            entity.addCommandTag(TAG);
            if (!world.spawnEntity(entity)) {
                LOGGER.warn("布景生成失败：{}", backdrop.item());
                continue;
            }
            ids.add(entity.getUuid());
        }
        component.setBackdropIds(ids);
        // 与语言无关的一行：验收靠它判「布景真的生成了」。
        LOGGER.info("布景已生成：{} 件 · 停靠点船头外 {} 格 · 起滑点 {} 格 · 岸向 {}", ids.size(),
                layout.arrival().slideTo(), layout.arrival().slideFrom(), layout.arrival().bearing());
    }

    /** 起滑：把平移在 {@code ticks} 个 tick 里插到 0。 */
    public static void slide(ServerWorld world, GameComponent component, VoyageLayout layout, int ticks) {
        int moved = 0;
        List<UUID> ids = component.backdropIds();
        for (int i = 0; i < ids.size() && i < layout.backdrops().size(); i++) {
            if (!(world.getEntity(ids.get(i)) instanceof ItemDisplayEntity entity)) {
                continue;
            }
            VoyageLayout.Backdrop backdrop = layout.backdrops().get(i);
            entity.setStartInterpolation(0);
            entity.setInterpolationDuration(ticks);
            entity.setTransformation(transformation(new Vector3f(), facing(layout, backdrop), backdrop.scale()));
            moved++;
        }
        LOGGER.info("终局：布景起滑 · {} 件 · {} tick", moved, ticks);
    }

    public static void clear(ServerWorld world, GameComponent component) {
        int gone = 0;
        for (UUID id : component.backdropIds()) {
            Entity entity = world.getEntity(id);
            if (entity != null) {
                entity.discard();
                gone++;
            }
        }
        component.setBackdropIds(List.of());
        if (gone > 0) {
            LOGGER.info("布景已收：清掉 {} 件", gone);
        }
    }

    /** 起滑点相对停靠点的平移（世界坐标）：沿岸向再往外 {@code slide_from − slide_to} 格。 */
    public static Vector3f farTranslation(VoyageLayout layout) {
        Vec3d v = layout.bearingVector().multiply(layout.arrival().slideFrom() - layout.arrival().slideTo());
        return new Vector3f((float) v.x, (float) v.y, (float) v.z);
    }

    /**
     * 模型朝向：绕 Y 转 {@code −(bearing + yaw)} 度，让模型的北面（−Z）朝船（岸向的反方向）。
     * 岸向 0（+Z）时是单位旋转；{@code BackdropMathTest} 对四个岸向核过「北面真的朝船」。
     */
    public static Quaternionf facing(VoyageLayout layout, VoyageLayout.Backdrop backdrop) {
        return new Quaternionf().rotationY((float) Math.toRadians(-(layout.arrival().bearing() + backdrop.yaw())));
    }

    public static AffineTransformation transformation(Vector3f translation, Quaternionf leftRotation, float scale) {
        return new AffineTransformation(translation, leftRotation, new Vector3f(scale, scale, scale), new Quaternionf());
    }
}
