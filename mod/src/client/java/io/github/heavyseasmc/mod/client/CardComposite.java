package io.github.heavyseasmc.mod.client;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import io.github.heavyseasmc.mod.HeavySeasMod;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把牌的画层与牌名**合成进同一张纹理**，再整体采样（ADR-0037 §7.10 第 1 条）。
 *
 * <p>治的是用户 2026-09-22 说的「太塑料感了这个卡牌上的文字渲染 / 感觉和卡牌不在一个图层」。
 * 根因不是字丑：牌名按屏幕物理像素现画、边缘锐利，而牌是一张被多级纹理 + 线性过滤柔化过的图 ——
 * <b>锐压软，眼睛就读成贴纸</b>。把字画进牌自己的纹理里，它就与画层共享同一次缩放与同一次过滤。
 * 炉石与 MTG Arena 走的是同一路。
 *
 * <p>⚠️不能靠矩阵缩放文字绕过去（§7.3 三条硬规矩之一），也不能指望 SDF —— 它解决缩放清晰度，
 * 不解决贴纸感。
 *
 * <h2>为什么不在画那一帧里合成</h2>
 * 合成要切帧缓冲、换投影矩阵、分配 GL 纹理。在 {@code Screen} 的渲染中途做这些，等于在别人的
 * 渲染批次里动全局状态 —— 出问题时表现为「偶尔花屏」，最难查。所以这里只<b>排队</b>，
 * 真正的合成放在客户端 tick 里，一 tick 一张；没合成好的那几帧照旧走老路画（字仍然是锐的，
 * 只是还没变好），**绝不空着**。这也是 ADR-0037 §11 要求的「只加不删地接入」：
 * 老路一直留着，随时退得回去。
 *
 * <h2>多级纹理</h2>
 * 帧缓冲的颜色附件只分配了第 0 级。合成完要自己 {@code glGenerateMipmap} 并把过滤改成
 * {@code LINEAR_MIPMAP_LINEAR} —— 否则牌缩小时会闪烁，比合成之前更糟（§7.3：清晰度按物理像素判）。
 */
final class CardComposite {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 同时留几张合成好的。屏幕上最多同时摆 9 张牌，留 12 张够用，再多是白占显存。 */
    private static final int MAX_CACHED = 12;
    /** 一 tick 最多合成几张：合成一张要分配一块 600×840 的纹理，扎堆做会掉帧。 */
    private static final int PER_TICK = 1;
    /** 合成用的坐标系原点推到这么远 —— 与 Minecraft 画 GUI 时用的同一组值（GameRenderer）。 */
    private static final float NEAR = 1000.0F;
    private static final float FAR = 21000.0F;
    private static final float DEPTH = -11000.0F;

    private record Job(String key, Identifier art, String nameKey, String id, int texW, int texH,
                       CardTexture.NameLayout layout) {
    }

    private record Baked(Identifier id, Framebuffer framebuffer) {
    }

    /** 合成好的。按最近用过排序 —— 挤掉的那张连帧缓冲一起删。 */
    private static final Map<String, Baked> READY = new LinkedHashMap<>(16, 0.75f, true);
    private static final Deque<Job> QUEUE = new ArrayDeque<>();
    /** 上一次合成时用的是哪种语言。换了语言，合成好的全作废 —— 牌名是语言相关的。 */
    private static String language = "";
    /** 这一次运行里合成过几张。只增不减 —— 它回答的是「合成这条路到底有没有走通」。 */
    private static int composed;

    private CardComposite() {
    }

    /**
     * 要这张牌合成好的纹理。没有就排队，并返回 {@code null} —— 调用方这一帧照旧走老路画。
     *
     * @param key     缓存键：牌的种类 + id（语言变化由 {@link #forget} 统一作废）
     * @param art     无字画层那张贴图
     * @param nameKey 牌名的语言键前缀，例如 {@code heavyseas.provision.}
     */
    static Identifier of(String key, Identifier art, String nameKey, String id, int texW, int texH,
                         CardTexture.NameLayout layout) {
        MinecraftClient client = MinecraftClient.getInstance();
        String now = client.getLanguageManager().getLanguage();
        if (!now.equals(language)) {
            forget();
            language = now;
        }
        Baked baked = READY.get(key);
        if (baked != null) {
            return baked.id();
        }
        for (Job queued : QUEUE) {
            if (queued.key().equals(key)) {
                return null;
            }
        }
        QUEUE.addLast(new Job(key, art, nameKey, id, texW, texH, layout));
        return null;
    }

    /** 每 tick 合成队列里的一张。只在渲染线程上跑。 */
    static void tick(MinecraftClient client) {
        for (int i = 0; i < PER_TICK && !QUEUE.isEmpty(); i++) {
            Job job = QUEUE.pollFirst();
            if (READY.containsKey(job.key())) {
                continue;
            }
            try {
                READY.put(job.key(), compose(client, job));
                composed++;
                // ❗这一行是闸门的来源：合成失败时老路还在，屏幕上只是「回到从前」——
                //   而「从前」与「现在」在任何机器判据上都一样。不报数就没人知道它没生效
                //   （与「那行『字体 X』只是参数回显」同一个形状）。
                LOGGER.info("牌面已合成：{}（累计 {} 张，缓存 {}）", job.key(), composed, READY.size());
            } catch (RuntimeException e) {
                // 合成不出来不是致命的：老路还在。但要说一声，否则「一直是老样子」没人知道为什么。
                LOGGER.warn("牌面合成失败（{}），这张牌继续走老路画：{}", job.key(), e.toString());
            }
            while (READY.size() > MAX_CACHED) {
                String oldest = READY.keySet().iterator().next();
                Baked drop = READY.remove(oldest);
                if (drop != null) {
                    client.getTextureManager().destroyTexture(drop.id());
                    drop.framebuffer().delete();
                }
            }
        }
    }

    /** 语言变了、资源重载了：合成好的全作废。 */
    static void forget() {
        MinecraftClient client = MinecraftClient.getInstance();
        for (Baked baked : READY.values()) {
            client.getTextureManager().destroyTexture(baked.id());
            baked.framebuffer().delete();
        }
        READY.clear();
        QUEUE.clear();
    }

    private static Baked compose(MinecraftClient client, Job job) {
        RenderSystem.assertOnRenderThread();
        SimpleFramebuffer fb = new SimpleFramebuffer(job.texW(), job.texH(), false, false);
        fb.setClearColor(0f, 0f, 0f, 0f);
        fb.clear(false);
        fb.beginWrite(true);

        Matrix4f previous = RenderSystem.getProjectionMatrix();
        VertexSorter sorter = RenderSystem.getVertexSorting();
        // ❗上下是对调的（`0, texH` 而不是 Minecraft 画 GUI 时的 `texH, 0`）：
        //   帧缓冲的第 0 行在**下**，而 drawTexture 取样时把第 0 行当**上** —— 两边差一个翻转。
        //   不对调的话合成出来的牌整张上下颠倒、字也是反的（2026-09-24 第一次实拍就是这样）。
        RenderSystem.setProjectionMatrix(
                new Matrix4f().setOrtho(0f, job.texW(), 0f, job.texH(), NEAR, FAR), VertexSorter.BY_Z);
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        stack.translation(0f, 0f, DEPTH);
        RenderSystem.applyModelViewMatrix();

        DrawContext context = new DrawContext(client, client.getBufferBuilders().getEntityVertexConsumers());
        // ❗关剔除：上面把投影上下对调了，三角形的绕向跟着反过来，而这段代码跑在客户端 tick 里 ——
        //   那时 GL 的剔除是上一次世界渲染留下的「开」。不关的话整张牌被剔掉，
        //   屏幕上是**一张空白牌**（2026-09-24 第二次实拍就是这样：只剩金框）。
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        context.drawTexture(job.art(), 0, 0, job.texW(), job.texH(), 0f, 0f, 1, 1, 1, 1);
        // ❗在**贴图自己的像素空间**里排字：倍率固定给 1，与窗口多大、界面尺寸设成几无关。
        CardTexture.drawNameInto(context, job.nameKey(), job.id(), job.texW(), job.texH(), job.layout());
        context.draw();

        RenderSystem.enableCull();
        stack.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(previous, sorter);
        client.getFramebuffer().beginWrite(true);

        // 多级纹理：帧缓冲只给了第 0 级，缩小时会闪。自己生成，并把过滤改成线性 + 多级。
        GlStateManager._bindTexture(fb.getColorAttachment());
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_LINEAR_MIPMAP_LINEAR);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_LINEAR);
        GL30.glGenerateMipmap(GlConst.GL_TEXTURE_2D);
        GlStateManager._bindTexture(0);

        Identifier id = Identifier.of(HeavySeasMod.MOD_ID, "composite/" + job.key().toLowerCase(java.util.Locale.ROOT));
        client.getTextureManager().registerTexture(id, new FboTexture(fb));
        return new Baked(id, fb);
    }

    /**
     * 把帧缓冲的颜色附件包成一张贴图，好让 {@code drawTexture} 照常画它。
     *
     * <p>❗它<b>不拥有</b>那个纹理 —— 纹理是帧缓冲分配的，也由帧缓冲删。所以这里既不释放，
     * 也不在 {@code clearGlId} 里回收：那样会把还在用的帧缓冲纹理删掉，屏幕上表现为随机的黑块。
     */
    private static final class FboTexture extends AbstractTexture {

        private FboTexture(Framebuffer framebuffer) {
            this.glId = framebuffer.getColorAttachment();
            this.bilinear = true;
            this.mipmap = true;
        }

        @Override
        public void load(ResourceManager manager) {
        }

        @Override
        public void clearGlId() {
            this.glId = -1;                  // 只松手，不回收
        }

        @Override
        public void close() {
        }
    }
}
