package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.data.VoyageLayout;
import io.github.heavyseasmc.mod.state.GameComponent;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity.ItemDisplayEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 补给箱实物（决策 ⑨ · ADR-0034 §5.4）：一个 item_display 挂木箱模型，沿座位轨滑到当前持有人身边。
 *
 * <h2>投影，不是状态</h2>
 * 箱子在谁手上由引擎的 {@code Session#provisionHolder()} 说了算；这里只在每次投影同步时照它把箱子摆对
 * （{@link #refresh}，与 {@code Seats.refresh} 同一形状：幂等，摆对了的一个字节都不动）。
 *
 * <h2>滑，不是传送</h2>
 * 实体位置固定在船头旁，箱子的移动是 display 变换里的平移插值（{@link #SLIDE_TICKS}，与 GUI 的「滑」同一时长）。
 * 理由与布景相同（§5.3.1）：位置包不参与，追踪器的补包打不断它。
 */
public final class Crate {

    /** 存盘时的指令标签：起服扫孤儿时认它。 */
    public static final String TAG = "heavyseas_crate";

    /** 与 GUI 的「滑」同一时长：550 ms ≈ 11 tick。 */
    public static final int SLIDE_TICKS = 11;

    /** 箱子在座位的右手边多远、多高（格）。 */
    static final double SIDE = 0.9;
    static final double LIFT = 0.9;

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private Crate() {
    }

    /** 开局在船头旁生成；随座位一起收（{@code Seats.clear}）。 */
    public static void place(ServerWorld world, GameComponent component, VoyageLayout layout) {
        clear(world, component);
        Vec3d at = restAt(layout);
        ItemDisplayEntity entity = new ItemDisplayEntity(EntityType.ITEM_DISPLAY, world);
        entity.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        entity.setItemStack(new ItemStack(SceneItems.SUPPLY_CRATE));
        entity.setTransformationMode(ModelTransformationMode.NONE);
        entity.setViewRange(2f);
        // 剔除盒要罩住整条座位轨：平移是变换，实体位置不动。
        entity.setDisplayWidth((float) (layout.boat().seatSpacing() * 8 + 2));
        entity.setDisplayHeight(3f);
        entity.setTeleportDuration(0);
        entity.setStartInterpolation(0);
        entity.setInterpolationDuration(0);
        entity.setTransformation(Backdrop.transformation(new Vector3f(), new Quaternionf(), 1f));
        entity.addCommandTag(TAG);
        if (!world.spawnEntity(entity)) {
            LOGGER.warn("补给箱实物生成失败");
            return;
        }
        component.setCrateId(entity.getUuid());
        component.setCrateSeat(0);
        LOGGER.info("补给箱实物已放在船头旁：{} {} {}", fmt(at.x), fmt(at.y), fmt(at.z));
    }

    /** 照现在的持有人把箱子滑过去。没在传（不在物资阶段）时停在原地。 */
    public static void refresh(ServerWorld world, GameComponent component, VoyageLayout layout) {
        Optional<UUID> id = component.crateId();
        if (id.isEmpty() || component.session().isEmpty()) {
            return;
        }
        Optional<CharacterId> holder = component.requireSession().provisionHolder();
        if (holder.isEmpty()) {
            return;
        }
        List<CharacterId> order = component.requireSession().state().bySeat();
        int seat = order.indexOf(holder.get());
        if (seat < 0 || seat == component.crateSeat()) {
            return;                                  // 已经在他身边 —— 绝大多数同步走到的分支
        }
        if (!(world.getEntity(id.get()) instanceof ItemDisplayEntity entity)) {
            return;
        }
        Vec3d shift = layout.boatForward().multiply(seat * layout.boat().seatSpacing());
        entity.setStartInterpolation(0);
        entity.setInterpolationDuration(SLIDE_TICKS);
        entity.setTransformation(Backdrop.transformation(
                new Vector3f((float) shift.x, (float) shift.y, (float) shift.z), new Quaternionf(), 1f));
        component.setCrateSeat(seat);
        // 与语言无关的一行：验收脚本按它数「箱子传到第几位」。
        LOGGER.info("补给箱：滑到第 {} 位（{}）", seat + 1, holder.get().value());
    }

    public static void clear(ServerWorld world, GameComponent component) {
        component.crateId().ifPresent(id -> {
            Entity entity = world.getEntity(id);
            if (entity != null) {
                entity.discard();
            }
        });
        component.setCrateId(null);
        component.setCrateSeat(-1);
    }

    /** 船头座位的右手边、座板之上。乘客面朝 {@code ridersFacing}，右手是它再转 90°。 */
    static Vec3d restAt(VoyageLayout layout) {
        Vec3d right = VoyageLayout.forward(layout.ridersFacing() + 90f);
        return layout.seatAt(0).add(right.multiply(SIDE)).add(0, LIFT, 0);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
