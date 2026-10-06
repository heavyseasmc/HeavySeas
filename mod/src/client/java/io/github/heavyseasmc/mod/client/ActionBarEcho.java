package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 动作栏那一句，在对局各面开着时照样看得见。
 *
 * <h2>为什么要它</h2>
 * 服务端拒绝一次操作时说的那句人话（「没有人受伤，用不上医疗箱」「现在不能打出这张牌」……）一律发在动作栏
 * （{@code sendMessage(text, true)}）。动作栏是 Minecraft 自带的 HUD 画的，比 Screen 先画 —— 而对局各面铺的是
 * 不透明的纸板（ADR-0037），<b>那一句恰好被盖在板底下</b>。于是「按了，被拒了」与「按了没反应」在屏幕上一模一样
 * （2026-10-07 用户实拍：手牌一面「按 Enter 没反应」）。
 *
 * <p>所以不去改每一处拒绝的发法，而是在客户端认下动作栏收到的每一句（{@code InGameHudMixin}），
 * 界面开着时在最上面一层再画一遍。界面没开时它照旧由 Minecraft 自己画，这里不画。
 */
public final class ActionBarEcho {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 与 Minecraft 自带动作栏同长（60 tick）。 */
    private static final long SHOW_MS = 3_000L;

    private static Text message;
    private static long at;

    private ActionBarEcho() {
    }

    /** 动作栏收到一句。客户端自己要说的（比如「没轮到你」）也走这里，与服务端那几句同一个位置。 */
    public static void record(Text text) {
        if (text == null || text.getString().isBlank()) {
            return;
        }
        message = text;
        at = System.currentTimeMillis();
        // 客户端回归靠这一行判「拒绝那句真的到了」（文字随语言变，前缀不变）。
        LOGGER.info("动作栏：{}", text.getString());
    }

    /** 在这一面的最上层画（{@code GameScreenSidebar} 的 afterRender 里，侧栏与悬停签之后）。 */
    static void draw(DrawContext context, GameScreen screen) {
        Text shown = message;
        if (shown == null || System.currentTimeMillis() - at > SHOW_MS) {
            return;
        }
        String line = shown.getString();
        int pad = 6;
        int w = Math.min(Math.max(60, screen.width - 2 * GameScreen.SIDE),
                GuiText.width(line, GuiText.BODY, false) + 2 * pad + 2);
        int h = GuiText.height(line, w - 2 * pad, GuiText.BODY, false, 6) + 2 * pad;
        int x = screen.width / 2 - w / 2;
        // 落在倒计时那一条之上：那一带是各面共用的，压不到牌，也不挡提示行里的键位。
        int bottom = screen.sheet().countBar().y() / screen.guiScale() - 4;
        int y = Math.max(pad, bottom - h);
        context.getMatrices().push();
        context.getMatrices().translate(0, 0, 450);
        GuiMaterial.plate(context, x, y, w, h, -1);
        GuiText.draw(context, line, x + pad, y + pad, w - 2 * pad, GuiText.BODY, false,
                GuiLanguage.onTag(GuiLanguage.ink()), GuiText.Align.CENTER, 6);
        context.getMatrices().pop();
    }
}
