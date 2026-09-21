package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.ui.TextFit;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;

/**
 * GUI 的字：思源宋体（Noto Serif SC，OFL），与牌面同一个世界（ADR-0037）。对局界面里的字只从这里画。
 *
 * <p>三条规矩都来自 2026-09-21 的真客户端截图（ADR-0037 §7.3），不是推断：
 * <ul>
 *   <li><b>在物理像素空间里画</b>。Minecraft 的字形图按最近邻采样，只有 1 纹素 = 1 物理像素才清楚。
 *       所以字体定义按物理像素字号注册了一条梯子（{@code font/r16.json} 这类，{@code oversample} 恒为 1），
 *       这里把矩阵缩到 1 / 界面缩放倍数 再画 —— 与界面尺寸是几无关。</li>
 *   <li><b>不许用矩阵把字放大</b>：放大出来的边缘全是锯齿台阶。要大字就取梯子上更大的一级。</li>
 *   <li><b>有地板</b>：界面尺寸 1 下 11 单位的中文在任何字体里都读不出，所以字号不低于梯子最低一级。</li>
 * </ul>
 *
 * <p>「任何情况下不越界」由 {@link TextFit} 保证（缩 → 折 → 截，每一步都量）；这里只负责量与画。
 * 版面要按 {@link #lineHeight} 排，不要假设一行是 9 个单位 —— 地板会让小界面尺寸下的行比想的高。
 */
final class GuiText {

    /** 梯子。❗与 {@code assets/heavyseas/font/} 下的定义一一对应（管线的 build_gui_font.py 生成），另有测试核对。 */
    static final int[] REGULAR_PX = {12, 14, 16, 18, 20, 24, 28, 32, 40};
    static final int[] BOLD_PX = {14, 16, 18, 20, 24, 28, 32, 40, 48, 64};

    /** 1.21.1 的 TTF 字形顶边 = 绘制 y + (7 − ascent)：基线恒在绘制 y 往下 7，与字号无关（读的是 yarn 源码）。 */
    private static final int BASELINE_BELOW_DRAW_Y = 7;
    /** 行框高 = 字号 × 这个数；汉字的字面框在基线上 0.88、下 0.12（Noto Serif CJK 的度量）。 */
    private static final float LINE_RATIO = 1.25f;
    private static final float IDEOGRAPH_ASCENT = 0.88f;

    /** 字号档，GUI 单位（Minecraft 默认字体是 9）。只在这里定义，界面只许引用 —— 与版面常量只在 GameScreen 一处是同一条规矩。 */
    static final int CAPTION = 8;
    /** 与 Minecraft 默认字体等大：1280×720（界面尺寸 3）下是 24 物理像素。再大，240 个单位高的界面里牌就被字挤小了（实拍过）。 */
    static final int BODY = 9;
    static final int NAME = 12;
    static final int TITLE = 15;

    /** 阴影的颜色（不含不透明度）：牌面墨色那一路的近黑，不用纯黑。 */
    private static final int SHADOW = 0x00120E0B;

    enum Align { LEFT, CENTER, RIGHT }

    private GuiText() {
    }

    /** 画一行（放不下就缩字号，再不行截断）。返回占掉的高度，GUI 单位。 */
    static int line(DrawContext context, Text text, int x, int y, int boxW, int guiSize, boolean bold, int color, Align align) {
        return draw(context, text.getString(), x, y, boxW, guiSize, bold, color, align, 1);
    }

    /**
     * 把一段字画进宽 {@code boxW} 的框里，最多 {@code maxLines} 行。
     *
     * @param x,y     框的左上角，GUI 单位
     * @param guiSize 想要的字号，GUI 单位（Minecraft 默认字体是 9）；实际取梯子上不大于它的那一级，且不低于地板
     * @return 占掉的高度，GUI 单位
     */
    static int draw(DrawContext context, String text, int x, int y, int boxW, int guiSize, boolean bold,
                    int color, Align align, int maxLines) {
        return draw(context, text, x, y, boxW, guiSize, bold, color, align, maxLines, false);
    }

    /**
     * 同上，可带阴影。阴影只给<b>直接压在世界上</b>的字（主画面 HUD）：背后是天是海说不准，没有它亮处读不出。
     * 画在材质板上的字一律不带 —— 印在纸上的墨没有影子。
     */
    static int draw(DrawContext context, String text, int x, int y, int boxW, int guiSize, boolean bold,
                    int color, Align align, int maxLines, boolean shadow) {
        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer renderer = client.textRenderer;
        int scale = scale(client);
        TextFit.Result fit = fit(renderer, text, boxW, guiSize, bold, maxLines, scale);
        int linePx = linePx(fit.size());
        int baseline = Math.round((linePx - fit.size()) / 2f + fit.size() * IDEOGRAPH_ASCENT);

        MatrixStack matrices = context.getMatrices();
        matrices.push();
        snapToPixel(matrices, scale);
        matrices.scale(1f / scale, 1f / scale, 1f);
        int boxPx = boxW * scale;
        for (int i = 0; i < fit.lines().size(); i++) {
            TextFit.Line l = fit.lines().get(i);
            int dx = switch (align) {
                case LEFT -> 0;
                case CENTER -> (boxPx - l.width()) / 2;
                case RIGHT -> boxPx - l.width();
            };
            int px = x * scale + dx;
            int py = y * scale + i * linePx + baseline - BASELINE_BELOW_DRAW_Y;
            Text styled = styled(l.text(), bold, fit.size());
            if (shadow) {
                int off = Math.max(1, scale / 2);                  // 半个 GUI 单位：界面尺寸大了影子也跟着厚
                context.drawText(renderer, styled, px + off, py + off, SHADOW | (color & 0xFF000000), false);
            }
            context.drawText(renderer, styled, px, py, color, false);
        }
        matrices.pop();
        return ceilDiv(Math.max(1, fit.lines().size()) * linePx, scale);
    }

    /** 只量不画：这段字排进宽 {@code boxW} 的框里要占多高，GUI 单位。要先铺底再写字的地方用。 */
    static int height(String text, int boxW, int guiSize, boolean bold, int maxLines) {
        MinecraftClient client = MinecraftClient.getInstance();
        int scale = scale(client);
        TextFit.Result fit = fit(client.textRenderer, text, boxW, guiSize, bold, maxLines, scale);
        return ceilDiv(Math.max(1, fit.lines().size()) * linePx(fit.size()), scale);
    }

    private static TextFit.Result fit(TextRenderer renderer, String text, int boxW, int guiSize, boolean bold,
                                      int maxLines, int scale) {
        // 只许一行的是标签：只能缩。许多行的是段落：先折行，折不下才缩 —— 先缩的话长句会变成一行小字，
        // 同一栏里字号忽大忽小（航海日志实拍过）。
        return TextFit.fit(text, boxW * scale, maxLines, candidates(bold, guiSize * scale),
                (s, px) -> renderer.getWidth(styled(s, bold, px)),
                maxLines > 1 ? TextFit.Policy.WRAP_FIRST : TextFit.Policy.SHRINK_FIRST);
    }

    /** 这个字号的一行有多高，GUI 单位。版面按它排。 */
    static int lineHeight(int guiSize, boolean bold) {
        int scale = scale(MinecraftClient.getInstance());
        return ceilDiv(linePx(candidates(bold, guiSize * scale)[0]), scale);
    }

    /** 一行字（不折、不截）在想要的字号下有多宽，GUI 单位，向上取整。排按钮宽度这类要先知道宽度的场合用。 */
    static int width(String text, int guiSize, boolean bold) {
        MinecraftClient client = MinecraftClient.getInstance();
        int scale = scale(client);
        int px = candidates(bold, guiSize * scale)[0];
        return ceilDiv(client.textRenderer.getWidth(styled(text, bold, px)), scale);
    }

    private static int linePx(int px) {
        return (int) Math.ceil(px * LINE_RATIO);
    }

    /** 梯子上不大于 {@code wantPx} 的各级，从大到小；一级都没有就只剩地板。 */
    static int[] candidates(boolean bold, int wantPx) {
        int[] ladder = bold ? BOLD_PX : REGULAR_PX;
        List<Integer> out = new ArrayList<>();
        for (int i = ladder.length - 1; i >= 0; i--) {
            if (ladder[i] <= wantPx) {
                out.add(ladder[i]);
            }
        }
        if (out.isEmpty()) {
            out.add(ladder[0]);
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    static Identifier fontId(boolean bold, int px) {
        return Identifier.of(HeavySeasMod.MOD_ID, (bold ? "b" : "r") + px);
    }

    private static Text styled(String s, boolean bold, int px) {
        return Text.literal(s).setStyle(Style.EMPTY.withFont(fontId(bold, px)));
    }

    private static int scale(MinecraftClient client) {
        return Math.max(1, (int) Math.round(client.getWindow().getScaleFactor()));
    }

    /**
     * 调用方的矩阵若带着小数位移（「抬」的 9 像素是插值出来的），字形四边形就落在半个物理像素上，
     * 最近邻采样下笔画会一粗一细。只有平移、没有缩放时把它补到整像素；带缩放的（「发」的 0.94 → 1、「翻」）
     * 正在动，不补。
     */
    private static void snapToPixel(MatrixStack matrices, int scale) {
        Matrix4f m = matrices.peek().getPositionMatrix();
        if (Math.abs(m.m00() - 1f) > 1e-4f || Math.abs(m.m11() - 1f) > 1e-4f) {
            return;
        }
        float tx = m.m30() * scale;
        float ty = m.m31() * scale;
        matrices.translate((Math.round(tx) - tx) / scale, (Math.round(ty) - ty) / scale, 0f);
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
