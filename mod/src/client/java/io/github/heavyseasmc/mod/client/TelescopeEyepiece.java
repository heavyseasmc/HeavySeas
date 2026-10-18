package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.ui.ShapeMesh;
import io.github.heavyseasmc.mod.ui.TelescopeSight;
import io.github.heavyseasmc.mod.world.liner.LinerProps;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 望远镜柜的望远镜视角（ADR-0109 §2 ③）在客户端这一半：进、每 tick 判退、拉近（{@code TelescopeFovMixin}）、
 * 灵敏度（{@code TelescopeMouseMixin}）、暗角与不画准星（{@code TelescopeHudMixin}）、不画手（{@code TelescopeFovMixin}）、
 * 右键再按一下退出（{@code TelescopeUseMixin}）。规则都在 {@link TelescopeSight}，这里只把游戏里的处境拍成一帧交给它。
 *
 * <p>视角只是这台客户端自己的画面：服务端不知道它，没有包来收它，所以每一种「该出去了」都由这里每 tick 自己问一遍。
 * 暗角是自己画的（同心三道圆环，顶点由 {@link ShapeMesh#annulus} 给、绕向在那里测过），不用 Minecraft 自带的望远镜贴图。
 */
public final class TelescopeEyepiece {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);
    /** 暗角的颜色（不含透明度）：近黑、偏一点海图墨的蓝。 */
    private static final int RIM_RGB = 0x07090C;

    private static final TelescopeSight.UseGate USE = new TelescopeSight.UseGate();

    private static boolean active;
    private static BlockPos cabinet;
    private static ClientWorld world;
    private static ClientPlayerEntity player;
    private static Vec3d lastPos;
    private static double limit;
    /** 进来之前是第几人称：退出时还在第一人称就换回去。 */
    private static Perspective before;
    /** 拉近的进度（0–1）：这一 tick 与上一 tick，画面按 tickDelta 在两者之间插值。 */
    private static double progress;
    private static double lastProgress;

    private TelescopeEyepiece() {
    }

    /** 柜子的 {@code onUse} 在客户端那一侧经 {@code TelescopeView} 调到这里（渲染线程）。 */
    public static void look(BlockPos at) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null) {
            return;
        }
        boolean sneaking = client.options.sneakKey.isPressed();
        if (!TelescopeSight.canEnter(sneaking, client.currentScreen != null, inVoyage(client))) {
            LOGGER.info("望远镜：不进（潜行 {} · 界面 {} · 对局 {}）", sneaking, client.currentScreen != null, inVoyage(client));
            return;
        }
        if (!active) {
            before = client.options.getPerspective();
        }
        active = true;
        cabinet = at;
        world = client.world;
        player = p;
        lastPos = p.getPos();
        double d = p.getEyePos().distanceTo(Vec3d.ofCenter(at));
        limit = TelescopeSight.leaveLimit(d);
        if (!client.options.getPerspective().isFirstPerson()) {
            client.options.setPerspective(Perspective.FIRST_PERSON);
        }
        USE.entered();
        sound(true, null);
        LOGGER.info("望远镜：进入 {} {} {}（距离 {}，界线 {}）", at.getX(), at.getY(), at.getZ(),
                String.format("%.2f", d), String.format("%.2f", limit));
    }

    /** 每 tick（{@code END_CLIENT_TICK}）：判退、走拉近的进度、记右键松没松开。 */
    public static void tick(MinecraftClient client) {
        if (active) {
            TelescopeSight.Exit why = TelescopeSight.exit(frame(client), limit);
            if (why != null) {
                leave(client, why);
            }
        }
        USE.tick(client.options.useKey.isPressed(), active);
        lastProgress = progress;
        progress = TelescopeSight.approach(progress, active);
        if (client.player != null) {
            lastPos = client.player.getPos();
        }
    }

    private static TelescopeSight.Frame frame(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        ClientWorld w = client.world;
        if (p == null || w == null) {
            return new TelescopeSight.Frame(false, false, false, false, false, false, false, false, 0, 0, true);
        }
        Vec3d pos = p.getPos();
        return new TelescopeSight.Frame(true, w == world, p == player, p.isAlive(), client.currentScreen != null,
                client.options.sneakKey.isPressed(), inVoyage(client),
                w.getBlockState(cabinet).isOf(LinerProps.TELESCOPE_CABINET),
                p.getEyePos().distanceTo(Vec3d.ofCenter(cabinet)),
                lastPos == null ? 0 : pos.distanceTo(lastPos),
                client.options.getPerspective().isFirstPerson());
    }

    /** 由 {@code TelescopeUseMixin} 在游戏要「用」一次时问：{@code true} = 这一下吞掉（退出了，或还没松开右键）。 */
    public static boolean swallowUse() {
        TelescopeSight.Verdict v = USE.use(active);
        if (v == TelescopeSight.Verdict.EXIT) {
            leave(MinecraftClient.getInstance(), TelescopeSight.Exit.USE_KEY);
        }
        return v != TelescopeSight.Verdict.PASS;
    }

    private static void leave(MinecraftClient client, TelescopeSight.Exit why) {
        if (!active) {
            return;
        }
        active = false;
        if (why != TelescopeSight.Exit.USE_KEY) {
            USE.exitedOtherwise();
        }
        if (before != null && !before.isFirstPerson() && client.options.getPerspective().isFirstPerson()
                && why != TelescopeSight.Exit.PERSPECTIVE) {
            client.options.setPerspective(before);
        }
        before = null;
        cabinet = null;
        world = null;
        player = null;
        sound(false, why);
        LOGGER.info("望远镜：退出（{}）", why);
    }

    /** 断线 / 换服务器：不把视角带过去（下一 tick 的帧也会判 {@code DISCONNECTED}，这里是保险）。 */
    public static void reset() {
        leave(MinecraftClient.getInstance(), TelescopeSight.Exit.DISCONNECTED);
        progress = 0;
        lastProgress = 0;
    }

    /**
     * ❗进 / 出望远镜视角的声音接口点（归音频会话：ADR-0052 Q4 · ADR-0106 §2.6，新声音不用游戏自带的）。
     * 现在什么都不放。{@code why} 进来时是 {@code null}；退出时分得出是自己按的（{@code USE_KEY} · {@code SNEAK}）还是被打断的。
     */
    private static void sound(boolean entering, TelescopeSight.Exit why) {
    }

    private static boolean inVoyage(MinecraftClient client) {
        return client.world != null && GameComponents.of(client.world).hudView().active();
    }

    /** 正在看（不画手、不画准星、右键归这里）。 */
    public static boolean active() {
        return active;
    }

    /** 这一帧视野乘多少（{@code TelescopeFovMixin}）。 */
    public static double fovFactor(float tickDelta) {
        double p = lastProgress + (progress - lastProgress) * tickDelta;
        return TelescopeSight.fovFactor(p);
    }

    /** 鼠标灵敏度乘多少（{@code TelescopeMouseMixin}）：按此刻的视野算。 */
    public static double sensitivityScale() {
        if (progress <= 0) {
            return 1;
        }
        int base = MinecraftClient.getInstance().options.getFov().getValue();
        return TelescopeSight.sensitivityScale(base, TelescopeSight.fovFactor(progress));
    }

    /**
     * 暗角（{@code TelescopeHudMixin}：画在 Minecraft 自带的那几层屏幕覆盖之后、准星与快捷栏之前 —— 同游戏自带望远镜的位置）。
     * 三道同心圆环：清晰的圆往外渐暗、再到全黑、再一道全黑铺到屏幕四角。透明度跟着拉近的进度，进出都是淡入淡出。
     */
    public static void render(DrawContext context, float tickDelta) {
        double p = lastProgress + (progress - lastProgress) * tickDelta;
        if (p <= 0) {
            return;
        }
        float w = context.getScaledWindowWidth();
        float h = context.getScaledWindowHeight();
        float s = Math.min(w, h);
        float cx = w / 2f;
        float cy = h / 2f;
        float r0 = s * TelescopeSight.CLEAR_RADIUS;
        float r1 = s * TelescopeSight.RIM_INNER;
        float r2 = s * TelescopeSight.RIM_OUTER;
        float far = (float) Math.hypot(w, h);
        float k = (float) Math.min(1, p);
        context.draw();
        ring(context, cx, cy, r0, r1, 0f, TelescopeSight.RIM_INNER_ALPHA * k);
        ring(context, cx, cy, r1, r2, TelescopeSight.RIM_INNER_ALPHA * k, k);
        ring(context, cx, cy, r2, far, k, k);
    }

    /** 一道圆环，里圈与外圈各一个透明度（顶点之间插值成渐变）。{@link ShapeMesh#annulus} 的点是「先内后外」交替的。 */
    private static void ring(DrawContext context, float cx, float cy, float r0, float r1, float a0, float a1) {
        float[] xy = ShapeMesh.annulus(cx, cy, r0, r1);
        int c0 = (Math.round(a0 * 255) << 24) | RIM_RGB;
        int c1 = (Math.round(a1 * 255) << 24) | RIM_RGB;
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.render.GameRenderer::getPositionColorProgram);
        org.joml.Matrix4f mat = context.getMatrices().peek().getPositionMatrix();
        net.minecraft.client.render.BufferBuilder buffer = net.minecraft.client.render.Tessellator.getInstance().begin(
                net.minecraft.client.render.VertexFormat.DrawMode.TRIANGLE_STRIP,
                net.minecraft.client.render.VertexFormats.POSITION_COLOR);
        for (int i = 0; i < xy.length; i += 2) {
            buffer.vertex(mat, xy[i], xy[i + 1], 0f).color((i / 2) % 2 == 0 ? c0 : c1);
        }
        net.minecraft.client.render.BufferRenderer.drawWithGlobalProgram(buffer.end());
    }
}
