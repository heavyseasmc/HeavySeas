package io.github.heavyseasmc.mod.world.liner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.block.enums.StairShape;
import net.minecraft.state.property.Property;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.EmptyBlockView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 五种材料的楼梯 / 台阶（ADR-0093 第 5 批 5d）：画出来的与游戏认的是同一个形状。判据的两半来自两个来源 ——
 * 模板文件里的元件（{@code liner_shapes.py} 写的，照方块状态的 y 旋转转过去，游戏画的）· 游戏楼梯 / 台阶方块自己给的轮廓（碰撞、选中框、剔面用的）。
 * 两边对不上时游戏不报错：只是看见的台阶与踩上去的台阶不是同一个，或者拐角那一格缺一块。
 */
final class LinerShapesTest {

    private static final Path RES = Path.of("src", "main", "resources", "assets", "heavyseas", "models", "block", "liner", "template");
    private static final Path GEN = Path.of("src", "main", "generated", "assets", "heavyseas");

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
    }

    @Test
    @DisplayName("每个状态：模板元件照 y 旋转拼起来 = 游戏给的轮廓（楼梯 10 块模板 × 4 朝向、台阶上下两块与双层）")
    void everyStateDrawsTheShapeTheGameUses() throws IOException {
        List<String> problems = new ArrayList<>();
        Map<String, List<double[]>> cache = new HashMap<>();
        Set<String> templates = new TreeSet<>();
        int states = 0;
        for (Map.Entry<String, Block> e : LinerShapes.all().entrySet()) {
            Block block = e.getValue();
            for (BlockState s : block.getStateManager().getStates()) {
                LinerLooks.Look look = ((LinerLooks.Styled) block).look(s);
                List<double[]> els = cache.get(look.template());
                if (els == null) {
                    els = elements(RES.resolve(look.template() + ".json"));
                    cache.put(look.template(), els);
                }
                templates.add(look.template());
                VoxelShape drawn = drawn(els, look.y());
                VoxelShape game = s.getOutlineShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN, ShapeContext.absent());
                if (VoxelShapes.matchesAnywhere(drawn, game, BooleanBiFunction.NOT_SAME)) {
                    problems.add(e.getKey() + s.getEntries() + "：画的 " + drawn.getBoundingBoxes() + " ≠ 游戏的 " + game.getBoundingBoxes());
                }
                states++;
            }
        }
        // 正向对照：16 种都在、每块模板都用到了、状态数对得上（楼梯 4 × 2 × 5 × 2 · 船壳板楼梯再 × 2 · 台阶 3 × 2 · 柚木 × 2 · 船壳板 × 18）
        assertEquals(16, LinerShapes.all().size());
        int want = 2 * 80 + 5 * 160 + 3 * 6 + 12 + 5 * 108;
        assertEquals(want, states, "状态数");
        assertTrue(templates.containsAll(Set.of("shape/stairs_straight", "shape/stairs_outer_right_top", "shape/slab_bottom", "shape/slab_top",
                "cube", "floor", "hull/plate")) && templates.size() == 10 + 2 + 3, "用到的模板：" + templates);
        assertEquals(List.of(), problems);
    }

    /**
     * 红测（判据本身）：拿真的模板与真的状态，各装一个缺陷 —— 楼梯转错 90°、内角楼梯用了直楼梯的模板、上半台阶用了下半的模板 ——
     * 判据都得红；原样的不红。
     */
    @Test
    @DisplayName("判据红得起来：转错 90° · 拐角用了直的模板 · 上半用了下半")
    void theJudgeRedsOnWrongYawWrongShapeWrongHalf() throws IOException {
        BlockState east = LinerShapes.WALL_WHITE_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.EAST);
        LinerLooks.Look look = LinerShapes.WALL_WHITE_STAIRS.look(east);
        List<double[]> straight = elements(RES.resolve(look.template() + ".json"));
        VoxelShape game = east.getOutlineShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN, ShapeContext.absent());
        assertFalse(VoxelShapes.matchesAnywhere(drawn(straight, look.y()), game, BooleanBiFunction.NOT_SAME), "原样的不该红");
        assertTrue(VoxelShapes.matchesAnywhere(drawn(straight, look.y() + 90), game, BooleanBiFunction.NOT_SAME), "转错 90° 必须红");

        BlockState inner = east.with(StairsBlock.SHAPE, StairShape.INNER_LEFT);
        VoxelShape innerGame = inner.getOutlineShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN, ShapeContext.absent());
        assertTrue(VoxelShapes.matchesAnywhere(drawn(straight, look.y()), innerGame, BooleanBiFunction.NOT_SAME), "内角用了直楼梯的模板必须红");

        BlockState top = LinerShapes.TEAK_SLAB.getDefaultState().with(net.minecraft.block.SlabBlock.TYPE, SlabType.TOP);
        List<double[]> bottomTpl = elements(RES.resolve("shape/slab_bottom.json"));
        VoxelShape topGame = top.getOutlineShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN, ShapeContext.absent());
        assertTrue(VoxelShapes.matchesAnywhere(drawn(bottomTpl, 0), topGame, BooleanBiFunction.NOT_SAME), "上半用了下半的模板必须红");
        assertEquals("shape/stairs_inner_left_top",
                LinerShapes.TEAK_STAIRS.look(inner.with(StairsBlock.HALF, BlockHalf.TOP)).template());
    }

    @Test
    @DisplayName("物品模型 = 默认状态那一块的模型（生成物：models/item 的 parent 与方块状态文件里默认状态那一项）")
    void itemModelIsTheDefaultStatesModel() throws IOException {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, Block> e : LinerShapes.all().entrySet()) {
            String name = e.getKey();
            JsonObject item = read(GEN.resolve("models/item/" + name + ".json"));
            JsonObject variants = read(GEN.resolve("blockstates/" + name + ".json")).getAsJsonObject("variants");
            String key = variantKey(e.getValue().getDefaultState());
            JsonElement v = variants.get(key);
            if (v == null) {
                problems.add(name + "：方块状态文件里没有默认状态 " + key);
                continue;
            }
            String model = v.getAsJsonObject().get("model").getAsString();
            String parent = item.get("parent").getAsString();
            if (!model.equals(parent)) {
                problems.add(name + "：物品模型指着 " + parent + "，默认状态是 " + model);
            }
            // 默认状态那一块的模板就是 look() 说的那一块（生成物没跟上源码时这里红）
            JsonObject m = read(GEN.resolve("models/" + model.substring(model.indexOf(':') + 1) + ".json"));
            String tpl = m.get("parent").getAsString();
            String want = "heavyseas:" + LinerLooks.TEMPLATE_DIR + ((LinerLooks.Styled) e.getValue()).look(e.getValue().getDefaultState()).template();
            if (!tpl.equals(want)) {
                problems.add(name + "：默认状态的模型以 " + tpl + " 为父，look() 说是 " + want);
            }
            checked++;
        }
        assertEquals(16, checked);
        assertEquals(List.of(), problems);
    }

    // ---------------------------------------------------------------- 工具

    /** 模板文件里每个元件的 from / to（像素）；这一族的模板没有转角度的元件。 */
    private static List<double[]> elements(Path f) throws IOException {
        assertTrue(Files.isRegularFile(f), "模板 " + f + " 不在");
        List<double[]> out = new ArrayList<>();
        for (JsonElement el : read(f).getAsJsonArray("elements")) {
            JsonObject o = el.getAsJsonObject();
            assertFalse(o.has("rotation"), f + "：有转角度的元件，这个判据不认");
            JsonArray from = o.getAsJsonArray("from");
            JsonArray to = o.getAsJsonArray("to");
            out.add(new double[]{from.get(0).getAsDouble(), from.get(1).getAsDouble(), from.get(2).getAsDouble(),
                    to.get(0).getAsDouble(), to.get(1).getAsDouble(), to.get(2).getAsDouble()});
        }
        assertFalse(out.isEmpty(), f + "：一个元件都没有");
        return out;
    }

    /** 元件照方块状态的 y 旋转转过去、拼成一个形状（{@link LinerLooks#rotateY} 与游戏转模型同一个方向）。 */
    private static VoxelShape drawn(List<double[]> els, int y) {
        VoxelShape shape = VoxelShapes.empty();
        for (double[] b : els) {
            double[] lo = LinerLooks.rotateY(b[0], b[2], y);
            double[] hi = LinerLooks.rotateY(b[3], b[5], y);
            shape = VoxelShapes.union(shape, VoxelShapes.cuboid(Math.min(lo[0], hi[0]) / 16, b[1] / 16, Math.min(lo[1], hi[1]) / 16,
                    Math.max(lo[0], hi[0]) / 16, b[4] / 16, Math.max(lo[1], hi[1]) / 16));
        }
        return shape.simplify();
    }

    private static JsonObject read(Path f) throws IOException {
        return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** 与 Minecraft 写方块状态文件同一种键：属性按名字排序，名=值，逗号隔开。 */
    private static String variantKey(BlockState state) {
        List<Property<?>> props = new ArrayList<>(state.getProperties());
        props.sort(Comparator.comparing(Property::getName));
        StringBuilder key = new StringBuilder();
        for (Property<?> p : props) {
            if (!key.isEmpty()) {
                key.append(',');
            }
            key.append(p.getName()).append('=').append(name(state, p));
        }
        return key.toString();
    }

    private static <T extends Comparable<T>> String name(BlockState state, Property<T> p) {
        return p.name(state.get(p));
    }
}
