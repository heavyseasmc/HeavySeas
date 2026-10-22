package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.TableView;
import io.github.heavyseasmc.mod.ui.HudLayout.Rect;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 船上有人受伤的那一下（ADR-0043 A6 · §11 第八组）：座位轨上那一格外圈一闪朱砂，头像右上飘一个「−N」，0.9 秒退掉。
 *
 * <p>原先体力印章的数字悄悄变了、轨上一动不动 —— 打架输了、口渴没喝、被鲨鱼咬了，旁人都要自己去比数字。
 * 与落海那一下（{@link OverboardCue}，四边一闪 + 溅水）是一套：那一下管「人没了」，这一下管「人少了几点」。
 *
 * <p>认的是 {@link TableView} 里每一座的体力<b>变少</b>（公开信息），不认某一个包。被大海带走的不在这里闪（那是落海那一下的事）；
 * 每局第一次看到的那一份只记下、不闪（中途进服、换了一局，不该补闪一串旧伤）。
 */
final class HurtCue {

    private static final Logger LOGGER = LoggerFactory.getLogger("heavyseas");

    /** 一共多久；前 80 ms 涌上来，其余慢慢退。 */
    static final long SHOW_MS = 900;
    private static final long RISE_MS = 80;

    private static Map<String, Integer> seen;
    private static Object seenWorld;
    private static java.util.UUID seenEpoch;
    /** 角色 → {什么时候, 掉了几点}。 */
    private static final Map<String, long[]> FLASHES = new HashMap<>();

    private HurtCue() {
    }

    /**
     * 这一次比上一次谁的体力少了、少了几点（纯函数，单测直接测它）。
     *
     * @param before 上一次看到的体力；{@code null} = 这一局还没看过（一个都不算）
     */
    static Map<String, Integer> drops(Map<String, Integer> before, List<TableView.Seat> seats) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (before == null) {
            return out;
        }
        for (TableView.Seat seat : seats) {
            Integer was = before.get(seat.id());
            if (!seat.removed() && was != null && seat.health() < was) {
                out.put(seat.id(), was - seat.health());
            }
        }
        return out;
    }

    /** 每 tick 看一次投影。 */
    static void tick(MinecraftClient client) {
        if (client.world == null) {
            seen = null;
            seenWorld = null;
            seenEpoch = null;
            FLASHES.clear();
            return;
        }
        var component = GameComponents.of(client.world);
        if (seenWorld != client.world || !component.notificationEpoch().equals(seenEpoch)) {
            seen = null;
            FLASHES.clear();
            seenWorld = client.world;
            seenEpoch = component.notificationEpoch();
        }
        List<TableView.Seat> seats = component.tableView().seats();
        if (seats.isEmpty()) {
            seen = null;
            return;
        }
        long now = System.currentTimeMillis();
        drops(seen, seats).forEach((id, amount) -> {
            FLASHES.put(id, new long[]{now, amount});
            LOGGER.debug("Hurt cue: {} -{}", id, amount);
        });
        Map<String, Integer> next = new HashMap<>();
        for (TableView.Seat seat : seats) {
            next.put(seat.id(), seat.health());
        }
        seen = next;
    }

    /** 这一座此刻那一圈的不透明度（0 = 不画）。 */
    static float alpha(String id, long nowMs) {
        long[] f = FLASHES.get(id);
        if (f == null) {
            return 0f;
        }
        long t = nowMs - f[0];
        if (t < 0 || t >= SHOW_MS) {
            return 0f;
        }
        return t < RISE_MS ? t / (float) RISE_MS : 1f - (t - RISE_MS) / (float) (SHOW_MS - RISE_MS);
    }

    /**
     * 画在那一座的头像上（物理像素坐标，调用方已在 px 模式）：外圈一道朱砂、右上「−N」往上飘一点。
     * 减弱动效 / 柔和闪烁时只留一道淡圈、字不飘。
     */
    static void draw(DrawContext context, Rect token, String id, double k) {
        long now = System.currentTimeMillis();
        float a = alpha(id, now);
        if (a <= 0f) {
            return;
        }
        boolean calm = ClientPrefs.reducedMotion() || ClientPrefs.softFlash();
        if (calm) {
            a = Math.min(a, 0.35f);
        }
        float cx = token.x() + token.w() / 2f;
        float cy = token.y() + token.h() / 2f;
        float r = token.w() / 2f;
        int ring = ((int) (a * 230) << 24) | (SeatMarks.CINNABAR & 0xFFFFFF);
        GuiMaterial.annulus(context, cx, cy, r + (float) (1.5 * k), r + (float) (4.5 * k), ring);
        long[] f = FLASHES.get(id);
        String text = "–" + f[1];
        int px = (int) Math.round(15 * k);
        float rise = calm ? 0f : (float) (6 * k) * Math.min(1f, (now - f[0]) / (float) SHOW_MS);
        int textColor = ((int) (a * 255) << 24) | (SeatMarks.CINNABAR & 0xFFFFFF);
        GuiText.drawPx(context, text, Math.round(cx + r * 0.55f), Math.round(token.y() - px * 0.9f - rise),
                px * 3, px, true, textColor, GuiText.Align.LEFT, 0);
    }
}
