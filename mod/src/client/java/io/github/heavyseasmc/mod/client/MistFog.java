package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.game.EndgamePhase;
import io.github.heavyseasmc.mod.state.FogFade;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.HudView;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 雾海的雾（ADR-0034 §5.1）：投影里说今天能见度多少，这里按墙钟把它渐变出来，交给 mixin 写进着色器。
 *
 * <h2>客户端不推「雾还在不在」</h2>
 * 服务端在投影里直接给 {@link HudView.Fog}（起点 · 终点，0/0 = 不改），雾散那一刻它变成 0/0。
 * 客户端只做两件事：记住上一次的值与它变化的时刻，在 {@code ARRIVAL_MS} 内 smoothstep 过去。
 * 不用「滑」的过冲曲线 —— 雾不该弹一下。
 *
 * <h2>0/0 的含义</h2>
 * 「不改」不是「没有雾」：游戏自己按渲染距离画雾。所以往 0/0 渐变时，终点朝<b>渲染距离</b>去、起点朝游戏默认的起点去，
 * 渐变完了就整个交还，这里返回 {@code null}。
 */
public final class MistFog {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 一帧该写进着色器的雾。 */
    public record Frame(float start, float end) {
    }

    /** 上一次投影里的目标；变化的墙钟时刻；变化那一刻正显示着的值（渐变的起点）。 */
    private static HudView.Fog target = HudView.Fog.NONE;
    private static long changedAt;
    private static float fromStart;
    private static float fromEnd;
    private static boolean everSeen;
    /** 上一次看到的天候 id：两种天候雾值相同时也要打一行日志，验收脚本按天候数行。 */
    private static String lastWeather = "";

    private MistFog() {
    }

    /**
     * 这一帧的雾；{@code null} = 交给游戏默认（不在雾海 · 没有对局 · 晴空 · 雾散已完成）。
     *
     * @param viewDistance 游戏这一帧传给 applyFog 的渲染距离（格）
     */
    public static Frame frame(float viewDistance) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            reset();
            return null;
        }
        HudView view = GameComponents.of(client.world).hudView();
        HudView.Fog now = view.active() ? view.fog() : HudView.Fog.NONE;
        String weather = view.active() && !view.weather().isEmpty() ? view.weather() : "-";
        long clock = System.currentTimeMillis();
        boolean fogChanged = !now.equals(target) || !everSeen;
        if (fogChanged || !weather.equals(lastWeather)) {
            // 与语言无关的一行：验收脚本按它核对「今天的雾与雾表一致」。天候换了而雾值相同时也打，脚本按天候数行。
            LOGGER.info("雾：{} {}/{} → {}/{}", weather, target.start(), target.end(), now.start(), now.end());
            lastWeather = weather;
        }
        if (fogChanged) {
            float[] current = everSeen ? eased(clock, viewDistance) : vanilla(viewDistance);
            fromStart = current[0];
            fromEnd = current[1];
            target = now;
            changedAt = clock;
            everSeen = true;
        }
        if (target.vanilla() && clock - changedAt >= EndgamePhase.ARRIVAL_MS) {
            return null;                              // 渐变完了，整个交还给游戏
        }
        float[] value = eased(clock, viewDistance);
        return new Frame(value[0], Math.min(value[1], viewDistance));
    }

    private static float[] eased(long clock, float viewDistance) {
        float t = FogFade.progress(clock - changedAt, EndgamePhase.ARRIVAL_MS);
        float[] to = target.vanilla() ? vanilla(viewDistance) : new float[] {target.start(), target.end()};
        return new float[] {FogFade.lerp(fromStart, to[0], t), FogFade.lerp(fromEnd, to[1], t)};
    }

    private static float[] vanilla(float viewDistance) {
        return FogFade.vanilla(viewDistance);
    }

    private static void reset() {
        target = HudView.Fog.NONE;
        everSeen = false;
        lastWeather = "";
    }
}
