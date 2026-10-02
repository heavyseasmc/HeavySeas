package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.StairShape;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

import java.util.Locale;
import java.util.Map;

import static io.github.heavyseasmc.mod.world.liner.LinerLooks.tex;

/**
 * 北辰号的桃花心木楼梯（ADR 草稿 stairs）。继承游戏的楼梯方块，拿它的朝向 · 上下半 · 内外转角的规则（结构放下去时也照它重算）、
 * 碰撞与半透光；模型与贴图全是自己的（{@code liner_stairs.py}）。
 *
 * <ul>
 *   <li>正放（half=bottom）：一级两步，踏面、踢面、前出 1 像素的前缘（有厚度、下面一道凹线）分开画；铺地毯的那一种每道折角压一根黄铜杆。</li>
 *   <li>倒扣（half=top）<b>当楼梯底用</b>：一块斜 45° 的白平顶板（游戏允许元件绕一个轴转 45°，楼梯坡度正好 1:1），从下面看是一道顺的斜面；
 *       轮廓与碰撞也照这道斜面（8 块 2 像素的台阶），人从楼梯底下走过时头碰到的就是看见的那一面。</li>
 * </ul>
 * 模板「朝北」作画（高的那一边在北），方块状态的 y 旋转 北 0 · 东 90 · 南 180 · 西 270；转角形状各有一块模板，不靠旋转凑。
 */
public final class LinerStairBlock extends StairsBlock implements LinerLooks.Styled {

    private final boolean carpet;

    LinerStairBlock(BlockState base, AbstractBlock.Settings settings, boolean carpet) {
        super(base, settings);
        this.carpet = carpet;
    }

    public boolean carpet() {
        return carpet;
    }

    @Override
    public LinerLooks.Look look(BlockState state) {
        String shape = state.get(SHAPE).asString().toLowerCase(Locale.ROOT);
        Map<String, String> textures;
        String template;
        if (state.get(HALF) == BlockHalf.TOP) {
            template = "stairs/soffit_" + shape;
            textures = tex("s", "stairs/string", "u", "stairs/soffit");
        } else if (carpet) {
            template = "stairs/step_rod_" + shape;
            textures = tex("s", "stairs/string", "t", "stairs/tread_carpet", "r", "stairs/riser_carpet", "n", "stairs/nosing",
                    "u", "stairs/soffit", "b", "stairs/rod");
        } else {
            template = "stairs/step_" + shape;
            textures = tex("s", "stairs/string", "t", "stairs/tread_mahogany", "r", "stairs/riser_mahogany", "n", "stairs/nosing",
                    "u", "stairs/soffit");
        }
        return LinerLooks.look(template, textures, LinerProp.yawOf(state.get(FACING)));
    }

    /** 楼梯底（倒扣、直的那一种）的轮廓与碰撞照那道斜面；别的照游戏的楼梯。 */
    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (state.get(HALF) == BlockHalf.TOP && state.get(SHAPE) == StairShape.STRAIGHT) {
            return SOFFIT[LinerConnect.index(state.get(FACING))];
        }
        return super.getOutlineShape(state, world, pos, context);
    }

    /** 楼梯底的斜面：朝北时「背」（整高的那一边）在北，斜面 y = z（像素）；按 2 像素一级拼 8 块，每块从斜面以上到顶。 */
    private static final VoxelShape[] SOFFIT = new VoxelShape[4];

    static {
        for (int f = 0; f < 4; f++) {
            VoxelShape s = VoxelShapes.empty();
            for (int i = 0; i < 8; i++) {
                double z0 = 2 * i;
                double z1 = 2 * i + 2;
                double[] lo = LinerLooks.rotateY(0, z0, f * 90);
                double[] hi = LinerLooks.rotateY(16, z1, f * 90);
                s = VoxelShapes.union(s, VoxelShapes.cuboid(Math.min(lo[0], hi[0]) / 16, z0 / 16, Math.min(lo[1], hi[1]) / 16,
                        Math.max(lo[0], hi[0]) / 16, 1.0, Math.max(lo[1], hi[1]) / 16));
            }
            SOFFIT[f] = s.simplify();
        }
    }
}
