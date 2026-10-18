package io.github.heavyseasmc.mod.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.heavyseasmc.mod.ui.HudLayout;
import io.github.heavyseasmc.mod.ui.LogHeader;
import io.github.heavyseasmc.mod.ui.PaneBudget;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 日志栏头那串小字（{@link LogHeader}）：界面里钉住时那一栏窄，整句「未读 N · End 最新」排不下，实拍被截成「…End…」
 * （2026-10-13 验收）。窄栏里只写「未读 N」，宽栏（世界视图那一列）照旧写整句。
 *
 * <p>字宽拿字体文件本身量（AWT 读 {@code serif.ttf}，每个字的步进向上取整再相加），不经 Minecraft 的字体渲染 ——
 * 与客户端那一侧的量法正交；向上取整只会量得偏宽，判「放得下」偏严。
 */
final class LogHeaderStatusTest {

    private static final Path ASSETS = Path.of("src", "main", "resources", "assets", "heavyseas");
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    private static Font regular;
    private static Font bold;

    /** {物理宽, 物理高}：与 {@code PaneBudgetTest} 同一批窗口。 */
    private static final int[][] WINDOWS = {
            {HudLayout.MIN_WINDOW_W, HudLayout.MIN_WINDOW_H}, {1280, 720}, {1920, 1080}, {1366, 768}, {1280, 800},
            {1280, 1024}, {1600, 900}, {2560, 1440}, {3840, 2160}, {2560, 1080}};

    @BeforeAll
    static void loadFonts() throws Exception {
        regular = load("serif.ttf");
        bold = load("serif_bold.ttf");
    }

    private static Font load(String file) throws Exception {
        try (InputStream in = Files.newInputStream(ASSETS.resolve("font").resolve(file))) {
            return Font.createFont(Font.TRUETYPE_FONT, in);
        }
    }

    /** 一串字在 {@code px} 物理像素字号下多宽：每个字的步进向上取整再相加。 */
    private static int width(Font font, String s, int px) {
        Font f = font.deriveFont((float) px);
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            assertTrue(font.canDisplay(cp), "字体里没有「" + new String(Character.toChars(cp)) + "」—— 量出来的宽不作数");
            w += (int) Math.ceil(f.getStringBounds(new String(Character.toChars(cp)), FRC).getWidth());
            i += Character.charCount(cp);
        }
        return w;
    }

    private static JsonObject lang(String code) throws IOException {
        return JsonParser.parseString(Files.readString(ASSETS.resolve("lang").resolve(code + ".json"), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    /** 一栏宽 {@code logW} 的日志栏头：挑出来的那句与可用宽。 */
    private record Pick(String text, int width, int room) {
    }

    private static Pick pick(JsonObject lang, HudLayout l, int logW, boolean narrow, boolean large, boolean paused,
                             int unread) {
        int titlePx = GuiText.readingSizePx(l.len(HudLayout.LOG_HEAD_TEXT), false, large);
        int statusPx = GuiText.readingSizePx(l.len(GameHud.LOG_STATUS_PX), false, large);
        int titleW = width(regular, lang.get("heavyseas.hud.log.history").getAsString(), titlePx);
        // 日志键是一个字母（默认 L）：键帽至少 KEY 宽，字比它窄
        int keyW = l.len(HudLayout.KEY);
        assertTrue(width(bold, "L", l.len(14)) < keyW, "日志键的字比键帽宽：键帽宽另算");
        int room = LogHeader.statusRoom(l, logW, keyW, titleW);
        ToIntFunction<String> w = s -> width(regular, s, statusPx);
        String text = LogHeader.status(narrow, paused, unread, "End", room,
                (id, args) -> lang.get(id).getAsString().formatted(args), w);
        return new Pick(text, w.applyAsInt(text), room);
    }

    @Test
    @DisplayName("界面里钉住时的窄日志栏：栏头只写「未读 N」、不带按键提示，并且放得下（两档字号 × 中英 × 暂停 / 不暂停 × 未读 1–999，写到 99 为止）")
    void narrowColumnShowsUnreadOnly() throws IOException {
        List<String> p = new ArrayList<>();
        int narrow = 0;
        int bare = 0;
        for (String code : new String[]{"zh_cn", "en_us"}) {
            JsonObject lang = lang(code);
            String brief = lang.get("heavyseas.hud.log.unread_brief").getAsString();
            for (int[] win : WINDOWS) {
                HudLayout l = HudLayout.of(win[0], win[1]);
                for (boolean large : new boolean[]{false, true}) {
                    PaneBudget b = PaneBudget.of(win[0], win[1], true, PaneWindow.logTextPx(win[0], win[1], large));
                    if (!b.logShown()) {
                        continue;
                    }
                    for (boolean paused : new boolean[]{false, true}) {
                        for (int unread : new int[]{1, 12, 99, 999}) {
                            Pick k = pick(lang, l, b.logWidth(), true, large, paused, unread);
                            String at = code + " " + win[0] + "×" + win[1] + (large ? " 大字" : "") + (paused ? " 暂停" : "")
                                    + " 未读 " + unread + "：「" + k.text() + "」宽 " + k.width() + "，可用 " + k.room();
                            if (k.width() > k.room()) {
                                p.add(at + " —— 放不下");
                            }
                            if (k.text().contains("End")) {
                                p.add(at + " —— 窄栏里不该带按键提示");
                            }
                            int shown = Math.min(unread, LogHeader.UNREAD_MAX);
                            if (!paused && !k.text().equals(brief.formatted(shown)) && !k.text().equals(Integer.toString(shown))) {
                                p.add(at + " —— 不暂停时窄栏该只写未读");
                            }
                            if (!k.text().equals(brief.formatted(shown)) && !(paused && k.text().equals(
                                    lang.get("heavyseas.hud.log.unread_brief_paused").getAsString().formatted(shown)))) {
                                bare++;
                            }
                            narrow++;
                        }
                    }
                }
            }
        }
        assertTrue(narrow >= 40, "只查了 " + narrow + " 种窄栏 —— 没在查");
        // 只剩数字是最后一招：常见的窗口里不该落到这一步
        assertTrue(bare * 4 <= narrow, "落到只剩数字的有 " + bare + " / " + narrow + " 种");
        assertEquals(List.of(), p, String.join("\n", p));
    }

    @Test
    @DisplayName("宽栏照旧：世界视图那一列（DRAWER_W）写整句「未读 N · End 最新」")
    void wideColumnKeepsTheKeyHint() throws IOException {
        JsonObject lang = lang("zh_cn");
        for (int[] win : new int[][]{{1280, 720}, {1920, 1080}}) {
            HudLayout l = HudLayout.of(win[0], win[1]);
            Pick k = pick(lang, l, l.len(HudLayout.DRAWER_W), false, false, false, 12);
            assertEquals(lang.get("heavyseas.hud.log.unread").getAsString().formatted(12, "End"), k.text(),
                    win[0] + "×" + win[1] + " 世界视图那一列：可用 " + k.room());
        }
    }
}
