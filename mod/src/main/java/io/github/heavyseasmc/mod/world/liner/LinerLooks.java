package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 大邮轮装饰方块「长什么样」：模板模型、贴图、方块状态里的 y 旋转，以及按同一个旋转算出来的轮廓（ADR-0062）。
 *
 * <p>模板与贴图由 {@code docs/tools/scene/liner_decor.py --write} 画好入库（{@code models/block/liner/template/*} ·
 * {@code textures/block/liner/*}）。约定与救生艇那一族相同：挂在墙上的件画在这一格的北面、正面朝南；
 * 整块的「正面」也朝南 —— 方块状态按朝向转过去。
 */
public final class LinerLooks {

    public static final String TEMPLATE_DIR = "block/liner/template/";
    public static final String TEXTURE_DIR = "block/liner/";

    private LinerLooks() {
    }

    /**
     * 一个方块状态的样子。
     *
     * @param template 模板名（{@code template/<名>.json}）
     * @param textures 模板里的纹理变量 → 贴图名（{@code block/liner/<名>.png}）；第一个兼作碎屑贴图
     * @param y        方块状态的 y 旋转（0 / 90 / 180 / 270）
     */
    public record Look(String template, Map<String, String> textures, int y) {

        Look turned(int yaw) {
            return new Look(template, textures, Math.floorMod(y + yaw, 360));
        }
    }

    static Look look(String template, Map<String, String> textures) {
        return new Look(template, textures, 0);
    }

    static Look look(String template, Map<String, String> textures, int y) {
        return new Look(template, textures, y);
    }

    /** 有序的纹理表（{@code Map.of} 不保序，而第一项要当碎屑贴图）。 */
    static Map<String, String> tex(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** 每块模板的轮廓（模板坐标，单位像素）：与模型元件大致重合的几个盒子。整块的三种是满格。 */
    private static final Map<String, List<double[]>> BOXES = Map.ofEntries(
            Map.entry("cube", List.of(box(0, 0, 0, 16, 16, 16))),
            Map.entry("cube_front", List.of(box(0, 0, 0, 16, 16, 16))),
            Map.entry("floor", List.of(box(0, 0, 0, 16, 16, 16))),
            Map.entry("chair_rail", List.of(box(0, 0, 0, 16, 2, 1))),
            Map.entry("cornice", List.of(box(0, 11, 0, 16, 16, 2.5), box(0, 8, 0, 16, 11, 1.25))),
            Map.entry("pilaster", List.of(box(5.5, 0, 0, 10.5, 16, 1.5))),
            Map.entry("pilaster_left", List.of(box(0, 0, 0, 5, 16, 1.5))),
            Map.entry("pilaster_right", List.of(box(11, 0, 0, 16, 16, 1.5))),
            Map.entry("capping", List.of(box(0, 0, 0, 16, 3, 2))),
            Map.entry("capping_left", List.of(box(0, 0, 0, 16, 3, 2), box(0, 0, 0, 5, 16, 1.5))),
            Map.entry("capping_right", List.of(box(0, 0, 0, 16, 3, 2), box(11, 0, 0, 16, 16, 1.5))),
            Map.entry("capping_both", List.of(box(0, 0, 0, 16, 3, 2), box(0, 0, 0, 5, 16, 1.5), box(11, 0, 0, 16, 16, 1.5))));

    private static double[] box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new double[]{x0, y0, z0, x1, y1, z1};
    }

    /** 模板里有哪些模板名（判据对得上号用）。 */
    public static Set<String> templates() {
        return BOXES.keySet();
    }

    /** 按样子算轮廓：模板的盒子照方块状态的 y 旋转转过去（与 Minecraft 转模型同一个方向：y = 90 把北转到东）。 */
    static VoxelShape shapeOf(Look look) {
        List<double[]> boxes = BOXES.get(look.template());
        if (boxes == null) {
            throw new IllegalStateException("大邮轮模板 " + look.template() + " 没有登记轮廓");
        }
        VoxelShape shape = VoxelShapes.empty();
        for (double[] b : boxes) {
            double[] lo = rotateY(b[0], b[2], look.y());
            double[] hi = rotateY(b[3], b[5], look.y());
            shape = VoxelShapes.union(shape, VoxelShapes.cuboid(new Box(
                    Math.min(lo[0], hi[0]) / 16, b[1] / 16, Math.min(lo[1], hi[1]) / 16,
                    Math.max(lo[0], hi[0]) / 16, b[4] / 16, Math.max(lo[1], hi[1]) / 16)));
        }
        return shape.simplify();
    }

    /** 绕方块中心转 (x, z)：y = 90 一次 (x, z) → (16 − z, x)，与救生艇那一族的 {@code SkiffLooks.rotate} 同一个方向。 */
    static double[] rotateY(double x, double z, int ry) {
        double px = x - 8;
        double pz = z - 8;
        for (int i = 0; i < Math.floorMod(ry, 360) / 90; i++) {
            double nx = -pz;
            double nz = px;
            px = nx;
            pz = nz;
        }
        return new double[]{px + 8, pz + 8};
    }
}
