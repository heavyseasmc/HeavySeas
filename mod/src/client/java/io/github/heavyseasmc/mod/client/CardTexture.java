package io.github.heavyseasmc.mod.client;

import com.mojang.blaze3d.platform.TextureUtil;
import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.MipmapHelper;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * 卡面贴图：多级纹理（mipmap）加线性过滤。
 *
 * <h2>为什么不走 Minecraft 默认的贴图载入</h2>
 * 默认载入的 GUI 贴图是<b>最近邻采样、没有多级纹理</b>：给方块和图标用正合适，像素风就该是硬边；
 * 给印着小字的卡面用就坏了。2026-09-15 第一次在真实客户端上看：300×420 的贴图被拉到 324 宽时
 * 每十几行重复一行像素，缩到 210 宽时每三行丢一行，规则条上的字笔画断开 —— 玩家说「不太清晰」。
 *
 * <p>这里换成三线性过滤：贴图按 2 倍烘（600×840，下限由构建期的 checkCardTextures 守着），
 * 载入时生成 4 级缩小版，画多大由显卡在相邻两级之间插值。于是同一张卡在 854×480 的小窗口里、
 * 界面尺寸设成 1 的大窗口里都清楚，发牌时那点 0.94→1 的缩放也不会让字闪。
 *
 * <h2>航海卡分三档，按物理像素选</h2>
 * 航海卡上的字比物资卡多得多（谁落海 · 谁口渴 · 规则条），缩小之后不是「糊」而是「没了」。
 * 所以它不靠多级纹理缩，而是三档各烘一张：LOD0 全部内容、LOD1 去掉规则文本、LOD2 只留摘要与一行口渴
 * （ADR-0020 §8 按实测可读性分档，ADR-0031 让 LOD2 分得开）。选哪一档只看这张牌在屏幕上占多少<b>物理像素</b>，
 * 不看 GUI 单位 —— 界面尺寸设成 1 与 4 时同样的单位数差四倍像素。
 *
 * <h2>卡面只能经这里画</h2>
 * 直接拿标识去 {@code drawTexture}，找不到已注册的贴图时 Minecraft 会按默认方式自己载一份 ——
 * 照样有图，只是又糊回去了，而且不报错。所以标识不外露，对外只有这几个 {@code draw*}。
 */
public final class CardTexture extends AbstractTexture {

    /** 缩小版的级数：600×840 → 300×420 → 150×210 → 75×105 → 37×52。再小就没人看得清了。 */
    private static final int MIP_LEVELS = 4;

    private static final String PROVISION_DIR = "textures/gui/cards/provision";
    private static final String CHARACTER_DIR = "textures/gui/cards/character";
    private static final String BACK_DIR = "textures/gui/cards/back";
    private static final String WEATHER_DIR = "textures/gui/cards/weather";
    /** 航海卡三档：根目录是 LOD0（600×840），{@code lod1/} 480×672，{@code lod2/} 240×336。 */
    private static final String NAV_DIR = "textures/gui/cards/nav";
    private static final String[] NAV_LOD_DIRS = {NAV_DIR, NAV_DIR + "/lod1", NAV_DIR + "/lod2"};

    /**
     * 分档阈值（物理像素宽），取自 ADR-0020 §8 的实测：≥ 240 全部内容；120–240 底部规则文本已经糊，去掉；
     * < 120 只剩顶栏摘要与海鸥角标读得出，再加 ADR-0031 补的那一行口渴摘要。
     */
    private static final double NAV_LOD0_MIN_PX = 240;
    private static final double NAV_LOD1_MIN_PX = 120;

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

    // ---------------------------------------------------------------- 牌面上的字（ADR-0037 §7.1 第三刀）

    /**
     * 牌面母版的坐标系：竖版 300×420，天候是横版 420×300。**牌上的位置一律按母版单位写**，
     * 再按画出来的大小等比缩 —— 调用方给多大是它自己的版面问题，这里不必知道。
     */
    private static final int MASTER_W = 300;
    private static final int MASTER_H = 420;
    private static final int MASTER_W_LANDSCAPE = 420;
    private static final int MASTER_H_LANDSCAPE = 300;

    /**
     * 牌名那一行。三类牌（物资 · 角色 · 天候）共用同一份表头骨架：母版里 x=28、基线 58、字号 24、粗体。
     * {@code NAME_TOP} 是行框顶边 —— 基线减去一个字面框的高（{@code GuiText} 把基线放在行框里的同一个位置）。
     */
    private static final int NAME_X = 28;
    private static final int NAME_TOP = 34;
    private static final int NAME_SIZE = 24;
    /** 右上角那个分数 / 属性框占掉的宽（连同它与牌名之间的空）：牌名绝不许压到它。 */
    private static final int NAME_BOX_ROOM = 68;
    /** 表头那一行至少要有这么大才画；再小就换成下面那套放大牌名。 */
    private static final int NAME_HEADER_MIN = 7;
    /**
     * 牌小到表头读不出时，牌名放大居中 —— 可读性的解法不是把字缩小（ADR-0020 §8）。
     *
     * <p>❗它必须落在**画区下面那条留白带**里，不能按牌高取比例。母版的画区是 y 78–316、裁剪到 322，
     * 规则条被无字画层整条拿掉之后 324 以下是空的；而每张牌的物件画到多低并不一样
     * （`FILL` 从 0.70 到 1.00），所以按比例放的字一定会盖住其中一些
     * （用户 2026-09-22：「牌里的物品不等高，下方没有留白用来放文字，字会盖住卡牌里的物体」）。
     * 落在裁剪线以下就与物件多高无关了 —— 画区在 322 处被裁掉，字永远在它下面。
     */
    private static final int BIG_NAME_TOP = 324;
    private static final int BIG_NAME_BOTTOM = 374;
    private static final int BIG_NAME_SIZE = 44;
    private static final int BIG_NAME_SIDE = 24;
    /** 小到这个地步就不画：一两个像素高的字不是「小字」，是噪点。 */
    private static final int NAME_MIN_SIZE = 5;

    /**
     * 把牌名按当前语言排到牌面上。贴图是<b>无字画层</b>（管线 {@code --textless}：母版里每一处中文都不烘），
     * 牌上的字改由这里实时排 —— 于是同一批贴图对每种语言都成立，小尺寸下也按屏幕分辨率排、不会糊。
     *
     * <p>色用 {@link GuiLanguage#CARD_INK} 而不是主题的墨：牌面永远是纸，深色主题下也一样。
     */
    private static void drawName(DrawContext context, String key, String id,
                                 int x, int y, int w, int h, int masterW, int masterH) {
        String name = Text.translatable(key + id).getString();
        float fx = w / (float) masterW;
        float fy = h / (float) masterH;
        int header = Math.round(NAME_SIZE * fy);
        int boxW = Math.round((masterW - NAME_BOX_ROOM - NAME_X) * fx);
        if (header >= NAME_HEADER_MIN && boxW >= NAME_MIN_SIZE) {
            GuiText.draw(context, name, x + Math.round(NAME_X * fx), y + Math.round(NAME_TOP * fy),
                    boxW, header, true, GuiLanguage.CARD_INK, GuiText.Align.LEFT, 1, false, true);
            return;
        }
        // 行框高是字号的 1.25 倍（GuiText 的度量），所以字号还要被那条带的高卡一道 —— 名字绝不许探出带外。
        int big = Math.round(Math.min(BIG_NAME_SIZE, (BIG_NAME_BOTTOM - BIG_NAME_TOP) / 1.25f) * fy);
        int side = Math.round(BIG_NAME_SIDE * fx);
        if (big < NAME_MIN_SIZE || w - 2 * side < NAME_MIN_SIZE) {
            return;                      // 这张牌已经小到一枚图标，名字由界面自己在牌外写
        }
        GuiText.draw(context, name, x + side, y + Math.round(BIG_NAME_TOP * fy),
                w - 2 * side, big, true, GuiLanguage.CARD_INK, GuiText.Align.CENTER, 1, false, true);
    }

    /** 画一张物资卡面。 */
    public static void drawProvision(DrawContext context, String cardId, int x, int y, int w, int h) {
        // 区域取整张贴图：UV 只看比值，写 1/1 就不必知道贴图烘成了多大。
        context.drawTexture(ensure(provisionId(cardId)), x, y, w, h, 0f, 0f, 1, 1, 1, 1);
        drawName(context, "heavyseas.provision.", cardId, x, y, w, h, MASTER_W, MASTER_H);
    }

    /**
     * 画一张角色卡面：爱恨的目标、终局翻牌那一面的被点名者（ADR-0022）。
     *
     * <p>id 就是 {@code data/roster} 的角色 id —— 贴图与数据共用一套主键，中间没有映射表。
     */
    public static void drawCharacter(DrawContext context, String characterId, int x, int y, int w, int h) {
        context.drawTexture(ensure(Identifier.of(HeavySeasMod.MOD_ID, CHARACTER_DIR + "/" + characterId + ".png")),
                x, y, w, h, 0f, 0f, 1, 1, 1, 1);
        drawName(context, "heavyseas.character.", characterId, x, y, w, h, MASTER_W, MASTER_H);
    }

    /** 画一张牌背。终局翻牌的暗牌用 {@code secret} —— 爱恨卡的背面。 */
    public static void drawBack(DrawContext context, String backId, int x, int y, int w, int h) {
        context.drawTexture(ensure(Identifier.of(HeavySeasMod.MOD_ID, BACK_DIR + "/" + backId + ".png")),
                x, y, w, h, 0f, 0f, 1, 1, 1, 1);
    }

    /** 天候卡是横版 7:5；调用方负责按该比例排版。 */
    public static void drawWeather(DrawContext context, String weatherId, int x, int y, int w, int h) {
        context.drawTexture(ensure(Identifier.of(HeavySeasMod.MOD_ID, WEATHER_DIR + "/" + weatherId + ".png")),
                x, y, w, h, 0f, 0f, 1, 1, 1, 1);
        drawName(context, "heavyseas.weather.", weatherId, x, y, w, h, MASTER_W_LANDSCAPE, MASTER_H_LANDSCAPE);
    }

    /**
     * 画一张航海卡面（划船与舵手两面，ADR-0020）。id 就是 {@code data/navigation} 的 id（{@code nav_07}）。
     *
     * @param w 这张牌画多宽（GUI 单位）—— 选档按它乘上界面缩放之后的物理像素
     */
    public static void drawNav(DrawContext context, String cardId, int x, int y, int w, int h) {
        context.drawTexture(ensure(navId(cardId, navLod(w))), x, y, w, h, 0f, 0f, 1, 1, 1, 1);
    }

    /** 这张牌画成 {@code guiWidth} 个单位宽时该用第几档（0 / 1 / 2）。 */
    private static int navLod(int guiWidth) {
        double px = guiWidth * MinecraftClient.getInstance().getWindow().getScaleFactor();
        return px >= NAV_LOD0_MIN_PX ? 0 : px >= NAV_LOD1_MIN_PX ? 1 : 2;
    }

    private static Identifier navId(String cardId, int lod) {
        return Identifier.of(HeavySeasMod.MOD_ID, NAV_LOD_DIRS[lod] + "/" + cardId + ".png");
    }

    /**
     * 进服时把全部卡面（物资 · 角色 · 牌背 · 天候 · 航海三档）先载好。
     *
     * <p>不预载的话，补给箱第一次打开的那一帧要现场解码、生成缩小版、上传最多 8 张 ——
     * 那一帧会卡一下，而「发」的动画按墙钟算，卡掉的那几十毫秒会直接跳过去。
     * {@code findResources} 连子目录一起找，所以 nav 只写根目录就把 lod1 / lod2 都带上了。
     */
    public static void preload(MinecraftClient client) {
        for (String dir : new String[]{PROVISION_DIR, CHARACTER_DIR, BACK_DIR, WEATHER_DIR, NAV_DIR}) {
            client.getResourceManager()
                    .findResources(dir, id -> id.getPath().endsWith(".png"))
                    .keySet().stream()
                    .filter(id -> id.getNamespace().equals(HeavySeasMod.MOD_ID))
                    .forEach(CardTexture::ensure);
        }
    }

    private static Identifier provisionId(String cardId) {
        return Identifier.of(HeavySeasMod.MOD_ID, PROVISION_DIR + "/" + cardId + ".png");
    }

    /** 别的 GUI 贴图（材质）也按牌面这一套载入：多级纹理 + 线性过滤，缩放才平滑。 */
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
