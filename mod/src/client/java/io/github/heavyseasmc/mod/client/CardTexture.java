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
 * <h2>卡面只能经这里画</h2>
 * 直接拿标识去 {@code drawTexture}，找不到已注册的贴图时 Minecraft 会按默认方式自己载一份 ——
 * 照样有图，只是又糊回去了，而且不报错。所以标识不外露，对外只有 {@link #drawProvision}。
 */
public final class CardTexture extends AbstractTexture {

    /** 缩小版的级数：600×840 → 300×420 → 150×210 → 75×105 → 37×52。再小就没人看得清了。 */
    private static final int MIP_LEVELS = 4;

    private static final String PROVISION_DIR = "textures/gui/cards/provision";

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

    /** 画一张物资卡面。 */
    public static void drawProvision(DrawContext context, String cardId, int x, int y, int w, int h) {
        // 区域取整张贴图：UV 只看比值，写 1/1 就不必知道贴图烘成了多大。
        context.drawTexture(ensure(provisionId(cardId)), x, y, w, h, 0f, 0f, 1, 1, 1, 1);
    }

    /**
     * 进服时把全部物资卡面先载好。
     *
     * <p>不预载的话，补给箱第一次打开的那一帧要现场解码、生成缩小版、上传最多 8 张 ——
     * 那一帧会卡一下，而「发」的动画按墙钟算，卡掉的那几十毫秒会直接跳过去。
     */
    public static void preloadProvisions(MinecraftClient client) {
        client.getResourceManager()
                .findResources(PROVISION_DIR, id -> id.getPath().endsWith(".png"))
                .keySet().stream()
                .filter(id -> id.getNamespace().equals(HeavySeasMod.MOD_ID))
                .forEach(CardTexture::ensure);
    }

    private static Identifier provisionId(String cardId) {
        return Identifier.of(HeavySeasMod.MOD_ID, PROVISION_DIR + "/" + cardId + ".png");
    }

    private static Identifier ensure(Identifier id) {
        if (REGISTERED.add(id)) {
            // 同一标识若已被默认方式载过一份，注册会把那一份换下并释放。
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, new CardTexture(id));
        }
        return id;
    }
}
