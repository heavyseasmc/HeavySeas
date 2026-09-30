package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.TableView;
import io.github.heavyseasmc.mod.ui.HudPart;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 有人落海的那一瞬：每个客户端一声溅水，屏幕四边一闪朱砂（ADR-0048；ADR-0045 §5.2 B4 最低成本那一格，
 * 感受目标 G1「打完一局，你记得是谁把你推下海的」）。停顿本来就有（协作者 {@code 2adbbe4} 的落海窗口），这里补上声与色。
 *
 * <p>认的是 {@link TableView} 里「移出游戏」那一栏<b>多出来</b>的人 —— 移出只由落海结算调用（{@code GameState#withRemoved}）。
 * 界面与提示的生死认投影，不认某一个包（证伪表：「结束了」那个包有的路径不发）。
 *
 * <p>每局第一次看到的那一批不响：中途进服、或者换了一局，不该补听一串旧的溅水。
 */
final class OverboardCue {

    private static final Logger LOGGER = LoggerFactory.getLogger("heavyseas");

    /** 朱砂一闪一共多久：60 ms 涌上来，其余慢慢退。 */
    static final long FLASH_MS = 700;
    private static final long RISE_MS = 60;

    /** 这一局已经看到的移出者；{@code null} = 这一局还没看过（下一次看到的那一批只记下、不响）。 */
    private static Set<String> seen;
    private static long flashAt = Long.MIN_VALUE / 2;

    private OverboardCue() {
    }

    /** 每 tick 看一次投影。 */
    static void tick(MinecraftClient client) {
        if (client.world == null) {
            seen = null;
            return;
        }
        TableView table = GameComponents.of(client.world).tableView();
        if (table.seats().isEmpty()) {
            seen = null;                     // 没有对局：下一局重新起算
            return;
        }
        Set<String> now = new LinkedHashSet<>();
        for (TableView.Seat seat : table.seats()) {
            if (seat.removed()) {
                now.add(seat.id());
            }
        }
        if (seen != null) {
            for (String id : now) {
                if (!seen.contains(id)) {
                    cue(client, id);
                }
            }
        }
        seen = now;
    }

    private static void cue(MinecraftClient client, String id) {
        flashAt = System.currentTimeMillis();
        client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.9f, 1f));
        LOGGER.info("落海提示：{} —— 溅水一声 · 四边朱砂一闪", id);
    }

    /** 此刻那一圈朱砂的不透明度（0 = 不画）。 */
    static float alpha(long nowMs) {
        long t = nowMs - flashAt;
        if (t < 0 || t >= FLASH_MS) {
            return 0f;
        }
        if (t < RISE_MS) {
            return t / (float) RISE_MS;
        }
        return 1f - (t - RISE_MS) / (float) (FLASH_MS - RISE_MS);
    }

    /** 盖在这一帧最上面：主画面在 HUD 的最后画，对局界面开着时在那一面的最后一层画（与侧栏同一处）。 */
    static void drawFlash(DrawContext context) {
        float a = alpha(System.currentTimeMillis());
        if (a <= 0f) {
            return;
        }
        var window = MinecraftClient.getInstance().getWindow();
        int s = Math.max(1, (int) Math.round(window.getScaleFactor()));
        context.getMatrices().push();
        context.getMatrices().translate(0f, 0f, 400f);      // 压在界面里的悬停签与侧栏之上
        context.getMatrices().scale(1f / s, 1f / s, 1f);
        context.setShaderColor(1f, 1f, 1f, a);
        GuiMaterial.hudPart(context, HudPart.FLASH, 0, 0, window.getFramebufferWidth(), window.getFramebufferHeight(), 1.0);
        context.setShaderColor(1f, 1f, 1f, 1f);
        context.getMatrices().pop();
    }
}
