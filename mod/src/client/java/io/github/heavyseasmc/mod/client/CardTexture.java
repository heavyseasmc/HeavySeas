package io.github.heavyseasmc.mod.client;

import com.mojang.blaze3d.platform.TextureUtil;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.card.CardFace;
import io.github.heavyseasmc.mod.card.CardFaces;
import io.github.heavyseasmc.mod.card.CardLayout;
import io.github.heavyseasmc.mod.state.NavCardView;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.MipmapHelper;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * 卡面：对外的入口，以及卡面贴图的载入方式（多级纹理 + 线性过滤）。
 *
 * <h2>为什么不走 Minecraft 默认的贴图载入</h2>
 * 默认载入的 GUI 贴图是<b>最近邻采样、没有多级纹理</b>：给方块和图标用正合适，像素风就该是硬边；
 * 给印着小字的卡面用就坏了。2026-09-15 第一次在真实客户端上看：300×420 的贴图被拉到 324 宽时
 * 每十几行重复一行像素，缩到 210 宽时每三行丢一行，规则条上的字笔画断开 —— 玩家说「不太清晰」。
 *
 * <p>这里换成三线性过滤：贴图按 2 倍烘（600×840），载入时生成 4 级缩小版，画多大由显卡在相邻两级之间插值。
 *
 * <h2>牌面是运行时拼的（ADR-0039）</h2>
 * 边框（每牌型每档一张）+ 插画（每张牌一张，透明底）+ 按数据排的内容（牌名 · 角标 · 口渴排），
 * 按 {@code assets/heavyseas/cards/layout.json} 的槽位图拼。拼好的整张由 {@link CardComposite} 在 tick 里
 * 合成进一张纹理；没合成好的那几帧由 {@link CardPainter} 用<b>同一段画法</b>直接画上屏幕。
 *
 * <h2>三档按物理像素选</h2>
 * 同一张槽位图，换档只「拿掉」或「在原地放大」。选哪一档只看这张牌在屏幕上占多少<b>物理像素</b>，
 * 界线由各槽位的最小尺寸现算（{@link CardLayout#tierFor}）。❗按调用方给的版面尺寸算 —— 发牌那段放大走矩阵，
 * 不在 {@code w} 里，所以动画中途不会跨档。
 *
 * <h2>卡面只能经这里画</h2>
 * 直接拿标识去 {@code drawTexture}，找不到已注册的贴图时 Minecraft 会按默认方式自己载一份 ——
 * 照样有图，只是又糊回去了，而且不报错。所以标识不外露，对外只有这几个 {@code draw*}。
 */
public final class CardTexture extends AbstractTexture {

    /** 缩小版的级数：600×840 → 300×420 → 150×210 → 75×105 → 37×52。再小就没人看得清了。 */
    private static final int MIP_LEVELS = 4;

    private static final String BACK_DIR = "textures/gui/cards/back";
    /** 牌背三档：根目录 L0，{@code lod1/}、{@code lod2/}（字标原地放大 / 只剩圆章）。 */
    private static final String[] BACK_TIER_DIRS = {BACK_DIR, BACK_DIR + "/lod1", BACK_DIR + "/lod2"};
    /** 进服时预载的目录：拼牌要用的全部分层素材，外加座位轨那八枚头像（口渴排也用它）。 */
    private static final String[] PRELOAD_DIRS = {"textures/gui/cards", "textures/gui/portrait"};

    /** 已经换成本类载入的标识。只在渲染线程上碰。 */
    private static final Set<Identifier> REGISTERED = new HashSet<>();

    private final Identifier location;

    private CardTexture(Identifier location) {
        this.location = location;
    }

    @Override
    public void load(ResourceManager manager) throws IOException {
        Resource resource = manager.getResource(location)
                .orElseThrow(() -> new FileNotFoundException(location.toString()));
        NativeImage base;
        try (InputStream in = resource.getInputStream()) {
            base = NativeImage.read(in);
        }
        NativeImage[] levels = MipmapHelper.getMipmapLevelsImages(new NativeImage[]{base}, MIP_LEVELS);
        TextureUtil.prepareImage(getGlId(), MIP_LEVELS, base.getWidth(), base.getHeight());
        for (int level = 0; level <= MIP_LEVELS; level++) {
            NativeImage image = levels[level];
            // blur + mipmap = GL_LINEAR_MIPMAP_LINEAR；clamp 让卡边不去采对边的颜色。传完即释放。
            image.upload(level, 0, 0, 0, 0, image.getWidth(), image.getHeight(), true, true, true, true);
        }
    }

    // ---------------------------------------------------------------- 画一张牌

    /**
     * 画一张牌：选档 → 有合成好的就整体画它，没有就用同一段画法直接画上屏幕。
     *
     * <p>合成好的那一张里，字与画层共享同一次缩放与同一次过滤 —— 治的是「锐压软，读成贴纸」
     * （ADR-0037 §7.10 第 1 条）。合成是异步的，所以这里<b>一定要有另一条路</b>；而那条路与合成
     * 用的是同一个 {@link CardPainter#paint}，两边长得一样（ADR-0039 §8）。
     */
    private static void drawFace(DrawContext context, CardFace face, int x, int y, int w, int h) {
        CardLayout layout = CardPainter.layout();
        String tier = tierFor(layout, face.kind(), w);
        CardLayout.Shape shape = layout.shapeOf(face.kind());
        Identifier composed = CardComposite.of(face, tier, shape.texW(), shape.texH());
        if (composed != null) {
            context.drawTexture(composed, x, y, w, h, 0f, 0f, 1, 1, 1, 1);
            return;
        }
        CardPainter.paint(context, face, tier, x, y, w, h, 0);
    }

    /** 这张牌画成 {@code guiWidth} 个单位宽时用哪一档：按物理像素，不按 GUI 单位。 */
    private static String tierFor(CardLayout layout, String kind, int guiWidth) {
        double px = guiWidth * MinecraftClient.getInstance().getWindow().getScaleFactor();
        // 牌名实际用多大的字也算进界线：放不下时按梯子往下缩，这一档要够 16 px 的是缩小之后的那个（ADR-0039 §7.2）
        return layout.tierFor(kind, px, CardNameFit.titleUnits(kind));
    }

    public static void drawProvision(DrawContext context, String cardId, int x, int y, int w, int h) {
        drawFace(context, CardFaces.provision(cardId, Catalog.provisionBadges(cardId)), x, y, w, h);
    }

    /**
     * 画一张角色卡面：爱恨的目标、终局翻牌那一面的被点名者（ADR-0022）。
     *
     * <p>id 就是 {@code data/roster} 的角色 id —— 贴图与数据共用一套主键，中间没有映射表。
     */
    public static void drawCharacter(DrawContext context, String characterId, int x, int y, int w, int h) {
        drawFace(context, CardFaces.character(characterId, Catalog.characterBadges(characterId)), x, y, w, h);
    }

    /** 天候卡是横版 7:5；调用方负责按该比例排版。 */
    public static void drawWeather(DrawContext context, String weatherId, int x, int y, int w, int h) {
        drawFace(context, CardFaces.weather(weatherId), x, y, w, h);
    }

    /**
     * 画一张航海卡面（划船与舵手两面）。口渴排按牌上的点名现排 —— 内容随 {@link NavCardView} 下发，
     * 服务端换了一套配平，屏幕上画的就是新的那张。
     */
    public static void drawNav(DrawContext context, NavCardView card, int x, int y, int w, int h) {
        drawFace(context, CardFaces.nav(card), x, y, w, h);
    }

    /** 画一张牌背。终局翻牌的暗牌用 {@code secret} —— 爱恨卡的背面。牌背没有运行时内容，三档各是一张贴图。 */
    public static void drawBack(DrawContext context, String backId, int x, int y, int w, int h) {
        CardLayout layout = CardPainter.layout();
        int tier = layout.tiers().indexOf(tierFor(layout, "provision", w));
        context.drawTexture(ensure(Identifier.of(HeavySeasMod.MOD_ID, BACK_TIER_DIRS[tier] + "/" + backId + ".png")),
                x, y, w, h, 0f, 0f, 1, 1, 1, 1);
    }

    /**
     * 进服时把拼牌要用的分层素材先载好。
     *
     * <p>不预载的话，补给箱第一次打开的那一帧要现场解码、生成缩小版、上传十几张 ——
     * 那一帧会卡一下，而「发」的动画按墙钟算，卡掉的那几十毫秒会直接跳过去。
     * {@code findResources} 连子目录一起找。
     */
    public static void preload(MinecraftClient client) {
        CardPainter.reset();
        for (String dir : PRELOAD_DIRS) {
            client.getResourceManager()
                    .findResources(dir, id -> id.getPath().endsWith(".png"))
                    .keySet().stream()
                    .filter(id -> id.getNamespace().equals(HeavySeasMod.MOD_ID))
                    .forEach(CardTexture::ensure);
        }
    }

    /** 别的 GUI 贴图（材质 · 牌面的分层素材）也按这一套载入：多级纹理 + 线性过滤，缩放才平滑。 */
    static Identifier smooth(Identifier id) {
        return ensure(id);
    }

    private static Identifier ensure(Identifier id) {
        if (REGISTERED.add(id)) {
            // 同一标识若已被默认方式载过一份，注册会把那一份换下并释放。
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, new CardTexture(id));
        }
        return id;
    }
}
