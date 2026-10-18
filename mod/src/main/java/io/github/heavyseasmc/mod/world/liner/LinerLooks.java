package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.block.BlockState;
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

    /**
     * 大邮轮一族的方块都能回答「这个状态长什么样」：装饰方块（{@link LinerBlock}）与灯 / 家具（{@link LinerProp}）。
     * 批量生成工具与运行时的轮廓用的是同一个回答。
     */
    public interface Styled {

        /** 这个状态的样子，朝向已经算进 y 旋转里。 */
        Look look(BlockState state);

        /** 物品的样子：{@code null} = 用默认状态那一格的方块模型；跨几格的件给一块整件缩小的模板。 */
        default Look itemLook() {
            return null;
        }

        /**
         * 叠在 {@link #look} 上面的几层（多部件模型，格架上的常春藤）：空 = 一个状态一块模型（方块状态文件写 variants）；
         * 不空 = 批量生成工具写 multipart —— 底层只按 {@link #baseProperties} 那几个属性分，每一层只按它自己那几个属性分，
         * 不用把「框条 × 内角 × 藤」的每一种组合各做一块模板。
         */
        default List<Layer> layers() {
            return List.of();
        }

        /** 有叠层时，底层（{@link #look}）只看这几个属性；别的属性改了底层不许变（批量生成工具核对，变了就抛）。 */
        default List<net.minecraft.state.property.Property<?>> baseProperties() {
            return List.of();
        }
    }

    /**
     * 多部件模型的一层：只看 {@code properties} 那几个属性；{@code look} 回答这一层画哪块模板（朝向已经算进 y 旋转），
     * {@code null} = 这个状态这一层不画。
     */
    public record Layer(List<net.minecraft.state.property.Property<?>> properties, java.util.function.Function<BlockState, Look> look) {
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

    /**
     * 居中整根的桃花心木壁柱与高檐顶帽（ADR-0093 B7）用的是原「主景立框」「主景横楣」的模板（{@code stairs/} 下）：
     * 轮廓照它们原来的（{@code LinerStairPiece} 里那两块，一字不改）。
     */
    private static Map<String, List<double[]>> withFeature(Map<String, List<double[]>> base) {
        Map<String, List<double[]>> out = new java.util.HashMap<>(base);
        for (String part : new String[]{"single", "bottom", "middle", "top"}) {
            out.put("stairs/feature_post_" + part, List.of(box(4, 0, 0, 12, 16, 4)));
        }
        for (String end : new String[]{"none", "left", "right", "both"}) {
            out.put("stairs/feature_lintel_" + end, List.of(box(0, 0, 0, 16, 14.5, 4.5)));
        }
        return out;
    }

    /** 大框的 15 块模板（{@code frame_<哪几边>}，ADR-0069 §1a）：轮廓都是整块 —— 前出的 1 像素框条不进轮廓。 */
    private static Map<String, List<double[]>> withFrames(Map<String, List<double[]>> base) {
        Map<String, List<double[]>> out = new java.util.HashMap<>(base);
        for (String m : new String[]{"t", "b", "l", "r", "tb", "tl", "tr", "bl", "br", "lr", "tbl", "tbr", "tlr", "blr", "tblr"}) {
            out.put("frame_" + m, List.of(box(0, 0, 0, 16, 16, 16)));
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /**
     * 贴附件第一组（ADR-0069 §2 ②）的轮廓：与 {@code liner_decor.py} 的 {@code attach_templates()} 同一套尺寸
     * （LinerLooksTest 拿模板文件逐块对照）。踢脚条与薄檐口的拐角照檐口那一套算（内角 = 直段 + 沿侧墙那一段，外角 = 小方墩）。
     */
    private static Map<String, List<double[]>> withAttach(Map<String, List<double[]>> base) {
        Map<String, List<double[]>> out = new java.util.HashMap<>(base);
        List<double[]> skirting = List.of(box(0, 0, 0, 16, 3, 1), box(0, 3, 0, 16, 4, 0.5));
        List<double[]> corniceThin = List.of(box(0, 15, 0, 16, 16, 2), box(0, 13, 0, 16, 15, 1));
        withCorners(out, "skirting", skirting);
        withCorners(out, "cornice_thin", corniceThin);
        // 门套 4 宽（第二轮：压边 1 · 平板 2 · 靠门洞的窄唇 1），门楣与上角 4 高
        List<double[]> jamb = List.of(box(12, 0, 0, 16, 16, 1));
        List<double[]> jambLow = List.of(box(12, 0, 0, 16, 13, 1));
        List<double[]> plinth = List.of(box(11.5, 0, 0, 16, 4.5, 1.5), box(12, 4.5, 0, 16, 16, 1),
                box(0, 0, 0, 11.5, 3, 1), box(0, 3, 0, 11.5, 4, 0.5));
        List<double[]> rail = List.of(box(12, 0, 0, 16, 16, 1), box(0, 0, 0, 12, 2, 1));
        List<double[]> head = List.of(box(0, 0, 0, 16, 4, 1));
        List<double[]> corner = List.of(box(12, 0, 0, 16, 4, 1));
        Map<String, List<double[]>> left = new java.util.LinkedHashMap<>();
        left.put("jamb_left", jamb);
        left.put("jamb_left_skirting", plinth);
        left.put("jamb_left_chair_rail", rail);
        left.put("jamb_left_cornice", concat(jambLow, corniceThin));
        left.put("corner_left", corner);
        left.put("corner_left_cornice", concat(corner, corniceThin));
        left.forEach((name, boxes) -> {
            out.put("casing_" + name, boxes);
            out.put("casing_" + name.replace("left", "right"), mirrored(boxes));
        });
        out.put("casing_head", head);
        out.put("casing_head_cornice", concat(head, corniceThin));
        // 柱脚：墩 4 高前出 3 · 过渡 1 高前出 2 · 柱身从 5 起；两边有踢脚时加两侧那一截踢脚
        List<double[]> white = List.of(box(5.5, 5, 0, 10.5, 16, 1.5), box(4.5, 4, 0, 11.5, 5, 2), box(3.5, 0, 0, 12.5, 4, 3));
        out.put("pilaster_plinth", white);
        out.put("pilaster_plinth_skirting", concat(white, List.of(box(0, 0, 0, 3.5, 4, 1), box(12.5, 0, 0, 16, 4, 1))));
        List<double[]> wood = List.of(box(0, 5, 0, 5, 16, 1.5), box(0, 4, 0, 5.5, 5, 2), box(0, 0, 0, 6.5, 4, 3));
        List<double[]> woodSkirting = concat(wood, List.of(box(6.5, 0, 0, 16, 4, 1)));
        out.put("pilaster_left_plinth", wood);
        out.put("pilaster_left_plinth_skirting", woodSkirting);
        out.put("pilaster_right_plinth", mirrored(wood));
        out.put("pilaster_right_plinth_skirting", mirrored(woodSkirting));
        return out;
    }

    /**
     * A 甲板新贴附件（ADR-0080 §7）的轮廓，与 {@code liner_decor.py} 的 {@code adeck_mod_templates()} 同一套尺寸（LinerLooksTest 拿模板文件逐块对照
     * 宽门套与拱；格架的模板在 {@code trellis/} 子目录里、有斜件，不在那条判据里）：
     * <ul>
     *   <li>宽门套 7 宽：压边 x 9–11 前出 2 · 平板 + 窄唇 x 11–16 前出 ≤ 1.5；门楣上一道檐（横板 1 · 承托线 2.5 · 退进的台 4.5 ·
     *       檐板 5.5 · 冠线 5，第三轮打磨后），上角那一格檐往外挑过压边（承托线从 8、台与冠线从 7.5、檐板从 7 起）。</li>
     *   <li>半圆拱：全拱 48 宽、拱墩顶 2 + 半椭圆（半宽 24、矢高 22），每一像素列取整 —— 按拱腹最低的中间那一层逐列取盒子（墙面那两层更高，
     *       包在里面）；拱心石 x 22–26 垂下 2.5；拱墩 1.5 宽 2 高。这里自己算一遍曲线，不读 Python 的结果（判据的两半各算各的）。</li>
     *   <li>格架：轮廓是贴墙一片 2.5 厚（木条与框条都在里面；常春藤不进轮廓）；内角那一格再加旁边那面墙上的一片。</li>
     * </ul>
     */
    private static Map<String, List<double[]>> withAdeck(Map<String, List<double[]>> base) {
        Map<String, List<double[]>> out = new java.util.HashMap<>(base);
        List<double[]> jamb = List.of(box(9, 0, 0, 11, 16, 2), box(11, 0, 0, 16, 16, 1.5));
        List<double[]> jambUp = List.of(box(9, 6, 0, 11, 16, 2), box(11, 6, 0, 16, 16, 1.5));
        List<double[]> skirting = List.of(box(8.5, 0, 0, 16, 6, 2.5), box(0, 0, 0, 8.5, 3, 1), box(0, 3, 0, 8.5, 4, 0.5));
        // 门头（第三轮打磨）：横板 7–10 前出 1 · 承托线 10–11 前出 2.5 · 檐板底下那道台 11–12 前出 4.5 · 檐板 12–14 前出 5.5 · 冠线 14–15 前出 5
        List<double[]> head = List.of(box(0, 0, 0, 16, 5, 1.5), box(0, 5, 0, 16, 7, 2), box(0, 7, 0, 16, 10, 1),
                box(0, 10, 0, 16, 11, 2.5), box(0, 11, 0, 16, 12, 4.5), box(0, 12, 0, 16, 14, 5.5), box(0, 14, 0, 16, 15, 5));
        List<double[]> corner = List.of(box(9, 0, 0, 11, 7, 2), box(11, 5, 0, 16, 7, 2), box(11, 0, 0, 16, 5, 1.5),
                box(9, 7, 0, 16, 10, 1), box(8, 10, 0, 16, 11, 2.5), box(7.5, 11, 0, 16, 12, 4.5), box(7, 12, 0, 16, 14, 5.5),
                box(7.5, 14, 0, 16, 15, 5));
        Map<String, List<double[]>> left = new java.util.LinkedHashMap<>();
        left.put("jamb_left", jamb);
        left.put("jamb_left_skirting", concat(jambUp, skirting));
        left.put("jamb_left_chair_rail", concat(jamb, List.of(box(0, 0, 0, 9, 2, 1))));
        left.put("corner_left", corner);
        left.forEach((name, boxes) -> {
            out.put("casing_tall_" + name, boxes);
            out.put("casing_tall_" + name.replace("left", "right"), mirrored(boxes));
        });
        out.put("casing_tall_head", head);
        // 半圆拱：拱腹（中间那一层）逐列 → 同高的相邻列并成一个盒子
        int[] h = new int[48];
        for (int k = 0; k < 48; k++) {
            double t = (k + 0.5 - 24) / 24;
            h[k] = (int) Math.floor(2 + 22 * Math.sqrt(Math.max(0, 1 - t * t)) + 0.5);
        }
        double keyBottom = h[24] - 2.5;
        String[] parts = {"l", "c", "r"};
        for (int p = 0; p < 3; p++) {
            for (String row : new String[]{"lower", "upper"}) {
                int gx0 = 16 * p;
                int gy0 = row.equals("upper") ? 16 : 0;
                boolean key = gx0 <= 22 && 26 <= gx0 + 16 && keyBottom >= gy0 && keyBottom < gy0 + 16;
                List<double[]> boxes = new java.util.ArrayList<>();
                int runStart = -1;
                int runH = -1;
                for (int gk = gx0; gk <= gx0 + 16; gk++) {
                    int eff = -1;                                          // 这一列在这一排里从哪儿起是实的；-1 = 这一排里全空 / 拱心石那几列
                    if (gk < gx0 + 16 && !(key && gk >= 22 && gk < 26)) {
                        int e = Math.max(h[gk], gy0);
                        eff = e >= gy0 + 16 ? -1 : e;
                    }
                    if (eff != runH && runH >= 0) {
                        boxes.add(box(runStart - gx0, runH - gy0, 0, gk - gx0, 16, 16));
                    }
                    if (eff != runH) {
                        runStart = gk;
                        runH = eff;
                    }
                }
                if (key) {
                    boxes.add(box(22 - gx0, keyBottom - gy0, 0, 26 - gx0, 16, 16));
                }
                if (row.equals("lower") && p != 1) {
                    boxes.add(p == 0 ? box(0, 0, 0, 1.5, 2, 16) : box(14.5, 0, 0, 16, 2, 16));
                }
                out.put("arch_" + parts[p] + "_" + row, boxes);
            }
        }
        // 格架：16 种框条 × （没有内角 · 屋角那一边画着竖框条时的内角）
        for (int m = 0; m < 16; m++) {
            String mask = LinerConnect.Rules.frameMask((m & 1) != 0, (m & 2) != 0, (m & 4) != 0, (m & 8) != 0);
            out.put("trellis/base_" + mask, List.of(box(0, 0, 0, 16, 16, 2.5)));
            if (mask.contains("r")) {
                out.put("trellis/base_" + mask + "_inner_right", List.of(box(0, 0, 0, 16, 16, 2.5), box(13.5, 0, 2.5, 16, 16, 16)));
            }
            if (mask.contains("l")) {
                out.put("trellis/base_" + mask + "_inner_left", List.of(box(0, 0, 0, 16, 16, 2.5), box(0, 0, 2.5, 2.5, 16, 16)));
            }
        }
        return out;
    }

    /** 直段 + 四种拐角（同 {@code liner_decor.py} 的 {@code corner_variants}）：每个盒子按自己的进深 d 转过去。 */
    private static void withCorners(Map<String, List<double[]>> out, String name, List<double[]> straight) {
        List<double[]> innerL = new java.util.ArrayList<>(straight);
        List<double[]> innerR = new java.util.ArrayList<>(straight);
        List<double[]> outerL = new java.util.ArrayList<>();
        List<double[]> outerR = new java.util.ArrayList<>();
        for (double[] b : straight) {
            double d = b[5];
            innerL.add(box(0, b[1], d, d, b[4], 16));
            innerR.add(box(16 - d, b[1], d, 16, b[4], 16));
            outerL.add(box(0, b[1], 0, d, b[4], d));
            outerR.add(box(16 - d, b[1], 0, 16, b[4], d));
        }
        out.put(name, straight);
        out.put(name + "_inner_left", innerL);
        out.put(name + "_inner_right", innerR);
        out.put(name + "_outer_left", outerL);
        out.put(name + "_outer_right", outerR);
    }

    private static List<double[]> concat(List<double[]> a, List<double[]> b) {
        List<double[]> out = new java.util.ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** 左右镜像（x → 16 − x）。 */
    private static List<double[]> mirrored(List<double[]> boxes) {
        return boxes.stream().map(b -> box(16 - b[3], b[1], b[2], 16 - b[0], b[4], b[5])).toList();
    }

    /** 每块模板的轮廓（模板坐标，单位像素）：与模型元件大致重合的几个盒子。整块的三种是满格。 */
    private static final Map<String, List<double[]>> BOXES = withFeature(withAdeck(withAttach(withFrames(Map.ofEntries(
            Map.entry("cube", List.of(box(0, 0, 0, 16, 16, 16))),
            Map.entry("cube_front", List.of(box(0, 0, 0, 16, 16, 16))),
            Map.entry("floor", List.of(box(0, 0, 0, 16, 16, 16))),
            Map.entry("chair_rail", List.of(box(0, 0, 0, 16, 2, 1))),
            Map.entry("chair_rail_inner_left", List.of(box(0, 0, 0, 16, 2, 1), box(0, 0, 1, 1, 2, 16))),
            Map.entry("chair_rail_inner_right", List.of(box(0, 0, 0, 16, 2, 1), box(15, 0, 1, 16, 2, 16))),
            Map.entry("chair_rail_outer_left", List.of(box(0, 0, 0, 1, 2, 1))),
            Map.entry("chair_rail_outer_right", List.of(box(15, 0, 0, 16, 2, 1))),
            Map.entry("cornice", List.of(box(0, 11, 0, 16, 16, 2.5), box(0, 8, 0, 16, 11, 1.25))),
            Map.entry("cornice_inner_left", List.of(box(0, 11, 0, 16, 16, 2.5), box(0, 8, 0, 16, 11, 1.25),
                    box(0, 11, 2.5, 2.5, 16, 16), box(0, 8, 1.25, 1.25, 11, 16))),
            Map.entry("cornice_inner_right", List.of(box(0, 11, 0, 16, 16, 2.5), box(0, 8, 0, 16, 11, 1.25),
                    box(13.5, 11, 2.5, 16, 16, 16), box(14.75, 8, 1.25, 16, 11, 16))),
            Map.entry("cornice_outer_left", List.of(box(0, 11, 0, 2.5, 16, 2.5), box(0, 8, 0, 1.25, 11, 1.25))),
            Map.entry("cornice_outer_right", List.of(box(13.5, 11, 0, 16, 16, 2.5), box(14.75, 8, 0, 16, 11, 1.25))),
            Map.entry("pilaster", List.of(box(5.5, 0, 0, 10.5, 16, 1.5))),
            Map.entry("pilaster_left", List.of(box(0, 0, 0, 5, 16, 1.5))),
            Map.entry("pilaster_right", List.of(box(11, 0, 0, 16, 16, 1.5))),
            Map.entry("capping", List.of(box(0, 0, 0, 16, 3, 2))),
            Map.entry("capping_left", List.of(box(0, 0, 0, 16, 3, 2), box(0, 0, 0, 5, 16, 1.5))),
            Map.entry("capping_right", List.of(box(0, 0, 0, 16, 3, 2), box(11, 0, 0, 16, 16, 1.5))),
            Map.entry("capping_both", List.of(box(0, 0, 0, 16, 3, 2), box(0, 0, 0, 5, 16, 1.5), box(11, 0, 0, 16, 16, 1.5))))))));

    private static double[] box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new double[]{x0, y0, z0, x1, y1, z1};
    }

    /** 模板里有哪些模板名（判据对得上号用）。 */
    public static Set<String> templates() {
        return BOXES.keySet();
    }

    /** 一块模板的轮廓盒子（模板坐标，没转过）；LinerLooksTest 拿它与模板文件里的元件对照。 */
    static List<double[]> boxesOf(String template) {
        return BOXES.get(template);
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
