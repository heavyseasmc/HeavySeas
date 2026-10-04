package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.client.util.SpriteIdentifier;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.LightType;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 夜里亮着灯的舱室，从船外看窗是暖的（ADR-0082；ADR-0080 ★2 用户定 B，舷窗与大窗一起）。
 *
 * <p>舷窗与大窗的玻璃本来就被屋里的灯照亮，但只有两成多不透明，夜里从船外远看读不出「窗亮着」（实验 A：只把那张玻璃画成满亮度，
 * 前后几乎看不出差别）。这里把两种窗的模型包一层：建区块网格时屋里那一侧的方块光 ≥ {@link #THRESHOLD}，<b>朝船外那一面</b>的玻璃
 * 换成暖金、三分之二不透明的那一张（{@code …_lit}，生成器 {@code liner_hull.py} / {@code liner_glass.py} 出）、画成满亮度。
 * 灯一关，那一格的方块光掉下去，游戏因光照变化重建那一段网格，窗回到平常的样子 —— 不用另外跟踪灯的开关。
 * 朝屋里那一面不动：从亮着的舱室里看出去，玻璃照旧是透的。
 *
 * <ul>
 *   <li>舷窗：{@code facing} 朝船外；只从屋里那一面进光，所以看<b>这一格自己</b>的方块光（{@code LinerHull.Porthole}）。</li>
 *   <li>大窗：{@code facing}（正面）朝屋里；光两边都进，所以看<b>屋里那一格</b>（正面前面那一格）。</li>
 * </ul>
 *
 * <p>只认贴图认出玻璃，不改模型。没有渲染器（{@link RendererAccess} 拿不到）时不包，窗照旧只被屋里的光照亮 —— 走的是哪一条路，日志里说一行。
 */
public final class GlassGlow extends ForwardingBakedModel {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);
    /** 屋里那一侧的方块光到这个数才亮：客舱壁灯 12、吸顶灯 15，挨着窗那一格一般 9–13；屋里黑着是 0。 */
    static final int THRESHOLD = 6;
    private static final Direction[] FACES = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null};

    enum Kind {
        /** 船壳上的舷窗：facing 朝船外，看这一格自己的光。 */
        PORTHOLE,
        /** 大窗（无缝窗）：facing 朝屋里，看正面前面那一格的光。 */
        WINDOW
    }

    private final Kind kind;
    /** 平常那张玻璃的贴图 id → 亮着的那一张（烘焙时从这一块模型的面里认出来）。 */
    private final Map<Identifier, Sprite> lit;
    private final RenderMaterial plain;
    private final RenderMaterial glow;

    private GlassGlow(BakedModel wrapped, Kind kind, Map<Identifier, Sprite> lit, Renderer renderer) {
        this.wrapped = wrapped;
        this.kind = kind;
        this.lit = lit;
        this.plain = renderer.materialFinder().find();
        this.glow = renderer.materialFinder().emissive(true).disableDiffuse(true).ambientOcclusion(TriState.FALSE).find();
    }

    /** 哪几种方块的模型要包。舷窗屋里那一面的内衬（porthole_inner）不在船外，不包。 */
    @Nullable
    static Kind kindOf(@Nullable ModelIdentifier id) {
        if (id == null || !id.id().getNamespace().equals(HeavySeasMod.MOD_ID)) {
            return null;
        }
        return switch (id.id().getPath()) {
            case "liner_porthole_black", "liner_porthole_white" -> Kind.PORTHOLE;
            case "liner_window" -> Kind.WINDOW;
            default -> null;
        };
    }

    /** 平常那张玻璃 → 亮着那一张的 id：{@code hull/glass → hull/glass_lit} · {@code glass/window_glass_<压边> → glass/window_glass_lit_<压边>}；别的贴图 null。 */
    @Nullable
    static Identifier litOf(Identifier texture) {
        if (!texture.getNamespace().equals(HeavySeasMod.MOD_ID)) {
            return null;
        }
        String path = texture.getPath();
        if (path.equals("block/liner/hull/glass")) {
            return Identifier.of(HeavySeasMod.MOD_ID, "block/liner/hull/glass_lit");
        }
        String window = "block/liner/glass/window_glass_";
        if (path.startsWith(window) && !path.startsWith(window + "lit_")) {
            return Identifier.of(HeavySeasMod.MOD_ID, window + "lit_" + path.substring(window.length()));
        }
        return null;
    }

    /** 模型烘焙时才问渲染器（客户端初始化的先后不由本模组定，那时渲染器未必已经登记）。 */
    public static void register() {
        int[] counts = {0, 0};
        ModelLoadingPlugin.register(plugin -> plugin.modifyModelAfterBake().register((model, context) -> {
            Kind kind = kindOf(context.topLevelId());
            if (model == null || kind == null) {
                return model;
            }
            Renderer renderer = RendererAccess.INSTANCE.getRenderer();
            if (renderer == null) {
                if (counts[1]++ == 0) {
                    // 与语言无关的一行：有退路的地方必须说走的是哪一条（证伪表 09-24）
                    LOGGER.info("窗发光：没有渲染器，不包 —— 窗照旧只被屋里的光照亮");
                }
                return model;
            }
            Map<Identifier, Sprite> lit = litSprites(model, context.textureGetter());
            if (lit.isEmpty()) {
                LOGGER.warn("窗发光：{} 的模型里认不出玻璃，不包", context.topLevelId());
                return model;
            }
            if (counts[0]++ == 0) {
                LOGGER.info("窗发光：包上了 · 渲染器 {} · 阈值 {}", renderer.getClass().getSimpleName(), THRESHOLD);
            }
            return new GlassGlow(model, kind, lit, renderer);
        }));
    }

    private static Map<Identifier, Sprite> litSprites(BakedModel model, Function<SpriteIdentifier, Sprite> textures) {
        Map<Identifier, Sprite> out = new HashMap<>();
        Random r = Random.create(42L);
        for (Direction face : FACES) {
            for (BakedQuad quad : model.getQuads(null, face, r)) {
                Identifier id = quad.getSprite().getContents().getId();
                Identifier litId = litOf(id);
                if (litId != null && !out.containsKey(id)) {
                    out.put(id, textures.apply(new SpriteIdentifier(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE, litId)));
                }
            }
        }
        return out;
    }

    @Override
    public boolean isVanillaAdapter() {
        return false;
    }

    @Override
    public void emitBlockQuads(BlockRenderView view, BlockState state, BlockPos pos, Supplier<Random> random, RenderContext context) {
        Direction facing = state.get(Properties.HORIZONTAL_FACING);
        Direction outward = kind == Kind.PORTHOLE ? facing : facing.getOpposite();
        BlockPos room = kind == Kind.PORTHOLE ? pos : pos.offset(facing);
        if (view.getLightLevel(LightType.BLOCK, room) < THRESHOLD) {
            wrapped.emitBlockQuads(view, state, pos, random, context);
            return;
        }
        QuadEmitter emitter = context.getEmitter();
        Random r = random.get();
        for (Direction face : FACES) {
            for (BakedQuad quad : wrapped.getQuads(state, face, r)) {
                Sprite from = quad.getSprite();
                Sprite to = quad.getFace() == outward ? lit.get(from.getContents().getId()) : null;
                emitter.fromVanilla(quad, to != null ? glow : plain, face);
                if (to != null) {
                    // 换贴图：图集里两张 16 × 16 各占一块，按比例把 UV 从平常那一块搬到亮的那一块
                    for (int i = 0; i < 4; i++) {
                        float u = (emitter.u(i) - from.getMinU()) / (from.getMaxU() - from.getMinU());
                        float v = (emitter.v(i) - from.getMinV()) / (from.getMaxV() - from.getMinV());
                        emitter.uv(i, to.getMinU() + u * (to.getMaxU() - to.getMinU()), to.getMinV() + v * (to.getMaxV() - to.getMinV()));
                    }
                }
                emitter.emit();
            }
        }
    }
}
