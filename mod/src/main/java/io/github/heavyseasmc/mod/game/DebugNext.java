package io.github.heavyseasmc.mod.game;

import java.util.Objects;
import java.util.Optional;

/**
 * 给<b>下一局</b>定的东西（ADR-0060，{@code /seas debug next seed | weather | clear}）。
 *
 * <p>存在服务端这一次运行里，不挂在哪个世界上：下一局可能开在别的布局、别的维度里。开局时取用，
 * 开局成功就清掉 —— 只管下一局，不会悄悄留到再下一局。停服就没了（与对局本身一样不持久化）。
 */
public final class DebugNext {

    /**
     * 一条设定。
     *
     * @param value   种子（十进制）或天候 id
     * @param who     谁定的
     * @param command 指令原文：开局时进那一局的调试记录
     */
    public record Setting(String value, String who, String command) {

        public Setting {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(who, "who");
            Objects.requireNonNull(command, "command");
        }
    }

    private static Setting seed;
    private static Setting weather;

    private DebugNext() {
    }

    public static synchronized void setSeed(long value, String who, String command) {
        seed = new Setting(Long.toString(value), who, command);
    }

    public static synchronized void setWeather(String id, String who, String command) {
        weather = new Setting(id, who, command);
    }

    public static synchronized Optional<Setting> seed() {
        return Optional.ofNullable(seed);
    }

    public static synchronized Optional<Setting> weather() {
        return Optional.ofNullable(weather);
    }

    public static synchronized void clear() {
        seed = null;
        weather = null;
    }
}
