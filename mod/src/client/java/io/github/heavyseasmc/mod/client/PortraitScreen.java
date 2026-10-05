package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.state.HudView;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 肖像画框上那一位的人物牌（C3 第二轮 · ADR-0086 §3：画像右键打开看牌界面；客户端一面，没有对局也能开）。
 *
 * <p>舞台上只有一张大牌：就是对局里那张人物牌，经 {@link CardTexture#drawCharacter} 画 —— 插画、牌名（身份）、体型与生存两枚角标，
 * 与爱恨、终局翻牌同一段画法，不另画一套。角标的数来自进服时的目录（{@link Catalog}）；目录还没到时角标空着，不编数。
 * 上带写这一位的名字；座位轨那一条不画 —— 看的是墙上的一幅画，不是这一局的座位。最下面一栏只有「Esc 收起」：
 * 不倒计时、不发包、不读对局状态（对局开着时一样能看，决策面要弹时照样把它顶掉）。
 *
 * <p>由方块那一侧的钩子 {@code PortraitView} 打开（{@link HeavySeasClient} 初始化时登记）。
 */
public final class PortraitScreen extends GameScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private final String sitter;

    /** @param sitter 角色 id（同 {@code data/roster}；方块属性 sitter 的值名） */
    public PortraitScreen(String sitter) {
        super(Text.translatable("heavyseas.portrait.title"));
        if (sitter == null || sitter.isEmpty()) {
            throw new IllegalArgumentException("肖像画框没说画的是谁");
        }
        this.sitter = sitter;
    }

    /** 画的是谁（单测与日志用）。 */
    String sitter() {
        return sitter;
    }

    @Override
    protected void init() {
        super.init();
        // 与语言无关的一行：实拍时据此判「右键开的是哪一位」（「界面：打开 PortraitScreen」只说开了，不说是谁）
        LOGGER.info("画像：看 {}", sitter);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackdrop(context, mouseX, mouseY, delta);
        Bands b = drawChrome(context, projection());
        // 一张牌：按舞台的高取（上下各留出金框那一圈），再不超过「像素上还清楚」与屏幕高的上限（cardHeightFor）
        int h = cardHeightFor(1, b.stageH() - 2 * BORDER_ROOM);
        int w = GuiLanguage.cardWidth(h);
        int x = (width - w) / 2;
        int y = cardsTopIn(b.stageTop(), b.stageBottom(), h, BORDER_ROOM);
        context.getMatrices().push();
        context.getMatrices().translate(x, y, 0);
        drawCardShadow(context, w, h, 0f);
        CardTexture.drawCharacter(context, sitter, 0, 0, w, h);
        context.getMatrices().pop();
        drawFootBand(context, b, List.of(), List.of(keys("close", "Esc")), System.currentTimeMillis(), null);
    }

    /** 上带写这一位的名字（牌名是身份 —— 船长 · 大副…）。 */
    @Override
    protected void drawTopBand(DrawContext context, HudView view, Bands b) {
        drawLine(context, nameOf(sitter), width / 2, b.topY(), GuiLanguage.ink());
    }

    /** 不画座位轨：这一面与对局无关。 */
    @Override
    protected void drawRailBand(DrawContext context, HudView view, Bands b) {
    }
}
