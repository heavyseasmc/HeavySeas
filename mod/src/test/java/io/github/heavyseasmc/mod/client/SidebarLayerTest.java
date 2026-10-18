package io.github.heavyseasmc.mod.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 航海日志与对局界面的关系（用户 2026-10-13，第二轮真机截图 51 · 56 · 71 · 74 · 75 · 91 · L05）：
 * <ul>
 *   <li>对局界面开着、日志钉住时，界面<b>让出右栏</b>、日志画在那一栏里；让不出（界面那一块低于 ADR-0088 的下限）就收起，
 *       <b>绝不盖在界面上</b>。挂在对局界面最后一层的那一支（{@link GameScreenSidebar}）只经 {@code GameHud#renderInScreen} 画，
 *       而它先问 {@code PaneWindow.current().logShown()} 再画（同一天早些时候是「一律不画」，被取代）；</li>
 *   <li>世界里右下角那一句按键提示压在右栏之上：由 {@code GameHud#render} 在右栏之后画，{@code ActionBarEcho#drawWorld} 只认下。</li>
 * </ul>
 * 扫源码而不是跑界面：这两条都是「哪个函数里调了谁」的形状，屏幕上要真机才看得见（证伪表：靠调用顺序的约定迟早有人写反）。
 * 让出来之后板与日志栏的几何由 {@link PaneBudgetTest} 核对。
 */
final class SidebarLayerTest {

    private static final Path CLIENT = Path.of("src", "client", "java", "io", "github", "heavyseasmc", "mod", "client");

    @Test
    @DisplayName("对局界面最后一层只经 renderInScreen 画日志，而它先问让没让出那一栏")
    void gameScreensDrawTheLogOnlyInTheYieldedColumn() throws IOException {
        String sidebar = read("GameScreenSidebar.java");
        assertTrue(sidebar.contains("GameHud.renderInScreen(context, client)"),
                "正向对照：对局界面最后一层得真的经 renderInScreen 走（扫到的是那一支）");
        int at = sidebar.indexOf("instanceof GameScreen");
        assertTrue(at > 0, "正向对照：认得出对局界面那一支");
        for (String draw : new String[]{"renderSidebar", "drawDrawer", "drawRight", "drawLog", "renderOverChat"}) {
            assertFalse(sidebar.substring(at).contains(draw), "对局界面那一支绕过 renderInScreen 画了日志（" + draw + "）");
        }
        String inScreen = body(read("GameHud.java"), "static void renderInScreen(");
        int gate = inScreen.indexOf("budget.logShown()");
        int draw = inScreen.indexOf("drawScreenLog(");
        assertTrue(draw > 0, "正向对照：renderInScreen 里画日志的那一行扫到了");
        assertTrue(inScreen.contains("PaneBudget budget = PaneWindow.current();") && gate > 0 && gate < draw,
                "renderInScreen 没先问界面让没让出日志那一栏就画了");
        // 用户 2026-10-13 第二次定：让出来的那一栏只放航海日志，不照搬世界视图那一整列（天候卡 + 只有你看得到 + 日志）
        for (String whole : new String[]{"drawDrawer(", "drawSecret(", "drawWeatherPx("}) {
            assertFalse(inScreen.contains(whole), "界面里那一栏又画了整列（" + whole + "）—— 只该有航海日志");
        }
        assertTrue(inScreen.indexOf("SidebarReveal.observe(") < inScreen.indexOf("PaneWindow.current()"), "让不出时也要照数播报：observe 要在判「让没让」之前");
        assertTrue(read("GameScreen.java").contains("return budget().sheet();"), "骨架不是从界面那一块的预算取的");
    }

    @Test
    @DisplayName("世界里右下角的按键提示在右栏之后画：drawWorld 只认下，GameHud#render 收尾时画")
    void cornerHintDrawsAfterTheLog() throws IOException {
        String echo = body(read("ActionBarEcho.java"), "public static boolean drawWorld(");
        assertTrue(echo.contains("pendingWorld"), "正向对照：drawWorld 那一段扫到了");
        assertFalse(echo.contains("drawCorner("), "drawWorld 又自己画了：会被之后画的右栏盖住");
        String render = body(read("GameHud.java"), "public static void render(");
        int hud = render.indexOf("renderHud(");
        int hint = render.indexOf("ActionBarEcho.drawPendingWorld(");
        assertTrue(hud >= 0 && hint > hud, "右下角那一句要在主画面（含右栏）之后画");
    }

    private static String read(String file) throws IOException {
        Path p = CLIENT.resolve(file);
        assertTrue(Files.isRegularFile(p), "找不到 " + p.toAbsolutePath());
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** 从签名起到配对的右花括号为止。 */
    private static String body(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "找不到 " + signature);
        int open = source.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(at, i + 1);
            }
        }
        throw new AssertionError("花括号没配上：" + signature);
    }
}
