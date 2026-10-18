package io.github.heavyseasmc.mod.world.skiff;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 救生艇方块「长什么样」：模板模型、贴图、方块状态里的旋转，以及按同一个旋转算出来的轮廓形状。
 *
 * <p>模板模型与贴图由 {@code docs/tools/scene/decor_textures.py} 画好入库（{@code models/block/skiff/template/*} ·
 * {@code textures/block/skiff/*}）；每块模板都按「船头朝南（+Z）、右舷在西（−X）」作画。
 * 「右舷」= 面朝船头时的右手边；左舷那一件就是同一块模板转 180°。
 */
public final class SkiffLooks {

    /** 模型 id 的前缀：模板在 {@code block/skiff/template/}，贴图在 {@code block/skiff/}。 */
    public static final String TEMPLATE_DIR = "block/skiff/template/";
    public static final String TEXTURE_DIR = "block/skiff/";

    private SkiffLooks() {
    }

    /**
     * 一个方块状态的样子。
     *
     * @param template 模板名（{@code template/<名>.json}）；{@code empty} 是一块没有元件的空模型
     * @param textures 模板里的纹理变量 → 贴图名（{@code block/skiff/<名>.png}）；第一个兼作碎屑贴图
     * @param x        方块状态的 x 旋转（0 / 90 / 180 / 270，与 Minecraft 的 blockstate 同义）
     * @param y        方块状态的 y 旋转
     */
    public record Look(String template, Map<String, String> textures, int x, int y) {

        Look turned(int yaw) {
            return new Look(template, textures, x, Math.floorMod(y + yaw, 360));
        }
    }

    static Look look(String template, Map<String, String> textures) {
        return new Look(template, textures, 0, 0);
    }

    static Look look(String template, Map<String, String> textures, int x, int y) {
        return new Look(template, textures, x, y);
    }

    /** 有序的纹理表（{@code Map.of} 不保序，而第一项要当碎屑贴图）。 */
    static Map<String, String> tex(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** 朝向 → y 旋转：模板朝南，Minecraft 的 y 旋转是从上往下看顺时针（北 → 东 → 南 → 西）。 */
    public static int yawOf(Direction facing) {
        return switch (facing) {
            case WEST -> 90;
            case NORTH -> 180;
            case EAST -> 270;
            default -> 0;
        };
    }

    // ---------------------------------------------------------------- 轮廓形状（点得中、碰得到的那一块）

    /** 每块模板的轮廓（模板坐标，单位像素）：与模型元件大致重合的几个盒子，越出 0–16 的部分裁掉。 */
    private static final Map<String, List<double[]>> BOXES = Map.ofEntries(
            Map.entry("hull_block", List.of(box(0, 0, 0, 16, 16, 16))),
            Map.entry("hull_stairs", List.of(box(0, 0, 0, 16, 8, 16), box(0, 8, 0, 8, 16, 16))),
            Map.entry("gunwale", List.of(box(0, 0, 0, 16, 15, 16))),
            Map.entry("floor", List.of(box(0, 0, 0, 16, 16, 16))),
            Map.entry("thwart", List.of(box(0, 6, 3, 16, 8, 13))),
            Map.entry("oar_blade", List.of(box(0, 2.5, 5, 16, 4.5, 11))),
            Map.entry("oar_handle", List.of(box(0, 2.25, 6.75, 12, 4.75, 9.25))),
            Map.entry("rowlock", List.of(box(6, 0, 6, 10, 5, 10))),
            Map.entry("rowlock_oar", List.of(box(0, 0, 6, 16, 5, 10))),
            Map.entry("oar_blade_stroke_r", List.of(box(0, 2.5, 5, 16, 4.5, 11))),
            Map.entry("oar_blade_stroke_l", List.of(box(0, 2.5, 5, 16, 4.5, 11))),
            Map.entry("oar_handle_stroke_r", List.of(box(0, 2.25, 6.75, 12, 4.75, 9.25))),
            Map.entry("oar_handle_stroke_l", List.of(box(0, 2.25, 6.75, 12, 4.75, 9.25))),
            Map.entry("rowlock_oar_stroke_r", List.of(box(0, 0, 6, 16, 5, 10))),
            Map.entry("rowlock_oar_stroke_l", List.of(box(0, 0, 6, 16, 5, 10))),
            Map.entry("rudder_lower", List.of(box(7, 4, 5, 9, 16, 16))),
            Map.entry("rudder_blade", List.of(box(7, 0, 2, 9, 16, 16))),
            Map.entry("rudder_head", List.of(box(6.5, 0, 11, 9.5, 9, 16))),
            Map.entry("rudder_lower_left", List.of(box(7, 4, 5, 9, 16, 16))),
            Map.entry("rudder_lower_right", List.of(box(7, 4, 5, 9, 16, 16))),
            Map.entry("rudder_blade_left", List.of(box(7, 0, 2, 9, 16, 16))),
            Map.entry("rudder_blade_right", List.of(box(7, 0, 2, 9, 16, 16))),
            Map.entry("rudder_head_left", List.of(box(6.5, 0, 11, 9.5, 9, 16))),
            Map.entry("rudder_head_right", List.of(box(6.5, 0, 11, 9.5, 9, 16))),
            Map.entry("tiller_mid", List.of(box(7, 6.5, 0, 9, 8.5, 16))),
            Map.entry("tiller_end", List.of(box(6.75, 6.25, 0, 9.25, 8.75, 12))),
            Map.entry("cask", List.of(box(1, 0, 2.5, 15, 11, 13.5))),
            Map.entry("bailer", List.of(box(4, 0, 4, 12, 10, 12))),
            Map.entry("canvas", List.of(box(2, 0, 2.6, 14, 6.8, 13.4))),
            Map.entry("coil", List.of(box(0.5, 0, 0.5, 15.5, 2.5, 15.5))),
            Map.entry("lifebuoy_flat", List.of(box(0, 0, 0, 16, 3, 16))),
            Map.entry("grab_line", List.of(box(14, 10, 0, 16, 13, 16))),
            Map.entry("nameplate", List.of(box(15, 2, 0, 16, 14, 16))),
            Map.entry("emblem", List.of(box(15, 1, 1, 16, 15, 15))),
            Map.entry("binnacle", List.of(box(2.5, 0, 2.5, 13.5, 8, 13.5))),
            Map.entry("flarebox", List.of(box(2.75, 0, 4.75, 13.25, 5.25, 11.25))),
            Map.entry("lantern_hanging", List.of(box(5, 0, 5, 11, 12.5, 11))),
            Map.entry("lantern_standing", List.of(box(5, 0, 5, 11, 12, 11))),
            Map.entry("pole", List.of(box(6.5, 0, 6.5, 9.5, 16, 9.5))),
            Map.entry("mast_arm", List.of(box(6, 0, 6, 10, 16, 10), box(9.5, 12, 7.5, 16, 13, 8.5))),
            Map.entry("mast_top", List.of(box(6, 0, 6, 10, 16, 10))),
            Map.entry("yard", List.of(box(7, 0, 0, 9, 2, 16))),
            Map.entry("yard_fore", List.of(box(7, 0, 0, 9, 2, 16))),
            Map.entry("yard_furled", List.of(box(6.25, 0, 0, 9.75, 2, 16))),
            Map.entry("yard_furled_fore", List.of(box(6.25, 0, 0, 9.75, 2, 16))),
            Map.entry("sail_panel_x0", List.of(box(7.5, 0, 0, 8.5, 16, 16))),
            Map.entry("sail_panel_x1", List.of(box(8.5, 0, 0, 9.5, 16, 16))),
            Map.entry("sail_panel_x2", List.of(box(9.5, 0, 0, 10.5, 16, 16))),
            Map.entry("empty", List.of()));

    private static double[] box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new double[]{x0, y0, z0, x1, y1, z1};
    }

    /** 模板里有哪些模板名（批量生成工具与判据对得上号用）。 */
    public static java.util.Set<String> templates() {
        return BOXES.keySet();
    }

    /** 按样子算轮廓：模板的盒子，照方块状态的 x、y 旋转转过去（与 Minecraft 转模型同一个方向）。 */
    static VoxelShape shapeOf(Look look) {
        List<double[]> boxes = BOXES.get(look.template());
        if (boxes == null) {
            throw new IllegalStateException("救生艇模板 " + look.template() + " 没有登记轮廓");
        }
        VoxelShape shape = VoxelShapes.empty();
        for (double[] b : boxes) {
            double[] lo = rotate(b[0], b[1], b[2], look.x(), look.y());
            double[] hi = rotate(b[3], b[4], b[5], look.x(), look.y());
            Box box = new Box(clip(Math.min(lo[0], hi[0])), clip(Math.min(lo[1], hi[1])), clip(Math.min(lo[2], hi[2])),
                    clip(Math.max(lo[0], hi[0])), clip(Math.max(lo[1], hi[1])), clip(Math.max(lo[2], hi[2])));
            if (box.getLengthX() > 0 && box.getLengthY() > 0 && box.getLengthZ() > 0) {
                shape = VoxelShapes.union(shape, VoxelShapes.cuboid(box));
            }
        }
        return shape.simplify();
    }

    private static double clip(double px) {
        return Math.max(0, Math.min(16, px)) / 16.0;
    }

    /**
     * 绕方块中心转一个点（像素坐标）：先 x 后 y，都是 90° 的倍数。方向与 blockstate 的 x / y 相同 ——
     * y = 90 把北转到东，x = 90 把北转到下（与样张页的渲染器、游戏内实拍核过）。
     */
    static double[] rotate(double x, double y, double z, int rx, int ry) {
        double px = x - 8;
        double py = y - 8;
        double pz = z - 8;
        for (int i = 0; i < Math.floorMod(rx, 360) / 90; i++) {   // x = 90：(y, z) → (z, −y)
            double ny = pz;
            double nz = -py;
            py = ny;
            pz = nz;
        }
        for (int i = 0; i < Math.floorMod(ry, 360) / 90; i++) {   // y = 90：(x, z) → (−z, x)
            double nx = -pz;
            double nz = px;
            px = nx;
            pz = nz;
        }
        return new double[]{px + 8, py + 8, pz + 8};
    }
}
