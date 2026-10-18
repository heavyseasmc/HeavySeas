package io.github.heavyseasmc.mod.llm;

import java.util.concurrent.TimeUnit;

/**
 * 「服务商不健康」的熔断：全服连续 {@code threshold} 次<b>尝试</b>落在传输失败 / 单次超时 / 5xx 上，就熔断 {@code cooldown} 那么久 ——
 * 这段时间里的决定直接走退路（{@code CIRCUIT_OPEN}），不把窗口浪费在一个明知不通的服务上。
 *
 * <ul>
 *   <li>冷却过了就放行（半开）：再失败一次立刻重新熔断；成功一次就完全恢复。不只放一个「探路的」—— 那样探路的那一次被截止收掉时，
 *       熔断会卡在半开里再也出不来。</li>
 *   <li>429 与 4xx 不算失败：服务商是通的，只是嫌我们发得多，或者请求本身不对（后者由 {@code AUTH} 等另行处理）。</li>
 *   <li>任何一次 2xx 回包都算成功（哪怕回答认不出）：服务商是通的。</li>
 *   <li>{@code threshold} 为 0 时永远不熔断。</li>
 * </ul>
 * 时间是 {@link System#nanoTime()} 的读数，由调用方传进来。
 */
final class CircuitBreaker {

    enum Change { NONE, OPENED, RECOVERED }

    private final int threshold;
    private final long cooldownNanos;
    private int consecutive;
    private boolean open;
    private boolean halfOpen;
    private long openUntil;
    private String lastFailure = "";

    CircuitBreaker(int threshold, long cooldownMs) {
        this.threshold = threshold;
        this.cooldownNanos = TimeUnit.MILLISECONDS.toNanos(cooldownMs);
    }

    /** 这一刻熔断着吗。冷却过了就转成半开（放行，再失败一次立刻重新熔断）。 */
    synchronized boolean blocks(long now) {
        if (!open) {
            return false;
        }
        if (now - openUntil < 0) {
            return true;
        }
        open = false;
        halfOpen = true;
        consecutive = Math.max(0, threshold - 1);
        return false;
    }

    synchronized Change success() {
        consecutive = 0;
        if (halfOpen) {
            halfOpen = false;
            return Change.RECOVERED;
        }
        return Change.NONE;
    }

    synchronized Change failure(long now, String what) {
        if (threshold <= 0) {
            return Change.NONE;
        }
        lastFailure = what;
        consecutive++;
        if (!open && consecutive >= threshold) {
            open = true;
            halfOpen = false;
            openUntil = now + cooldownNanos;
            return Change.OPENED;
        }
        return Change.NONE;
    }

    /** 还要熔断多久（毫秒）；没熔断是 0。 */
    synchronized long remainingMs(long now) {
        return open ? Math.max(0, TimeUnit.NANOSECONDS.toMillis(openUntil - now)) : 0;
    }

    synchronized int consecutive() {
        return consecutive;
    }

    synchronized String lastFailure() {
        return lastFailure;
    }

    int threshold() {
        return threshold;
    }

    long cooldownMs() {
        return TimeUnit.NANOSECONDS.toMillis(cooldownNanos);
    }
}
