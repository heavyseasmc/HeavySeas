package io.github.heavyseasmc.mod.llm;

import java.util.ArrayDeque;

/**
 * 全服共用的在途请求名额（{@code maxConcurrent}）：<b>不阻塞任何线程</b>。拿不到名额的排进队里，
 * 有人还名额时直接交给队头 —— 主线程调 {@link LlmService#choose} 永远当场返回。
 *
 * <p>队里的人可能已经不要名额了（排队时就到了截止时间、服务关了）：交名额时跳过它们（{@link Waiter#abandoned()}），
 * 所以「排队时超时」的那一位<b>不会</b>拿到名额、也就不会被发出去。
 *
 * <p>{@link Waiter#granted()} 一律在锁外调：拿到名额之后做什么是调用方的事，不该占着这把锁。
 */
final class AsyncPermits {

    interface Waiter {
        /** 已经不要名额了（有了结果）：交名额时跳过。 */
        boolean abandoned();

        /** 拿到了一个名额。之后必须且只能还一次（{@link #release()}）。 */
        void granted();
    }

    private final int max;
    private final int maxQueued;
    private final ArrayDeque<Waiter> queue = new ArrayDeque<>();
    private int inUse;

    AsyncPermits(int max, int maxQueued) {
        if (max < 1 || maxQueued < 0) {
            throw new IllegalArgumentException("名额至少 1 个、队长不能为负：" + max + " / " + maxQueued);
        }
        this.max = max;
        this.maxQueued = maxQueued;
    }

    /**
     * 要一个名额：有空就当场给（在调用线程上调 {@code granted}），没空就排队。
     *
     * @return {@code false} = 队已经排满，没排上（调用方回 QUEUE_FULL）
     */
    boolean acquire(Waiter waiter) {
        synchronized (this) {
            if (inUse < max) {
                inUse++;
            } else {
                queue.removeIf(Waiter::abandoned);
                if (queue.size() >= maxQueued) {
                    return false;
                }
                queue.addLast(waiter);
                return true;
            }
        }
        waiter.granted();
        return true;
    }

    /** 还一个名额：队里还有要的人就直接交给他（名额数不变），没有就放回池子。 */
    void release() {
        Waiter next;
        synchronized (this) {
            do {
                next = queue.pollFirst();
            } while (next != null && next.abandoned());
            if (next == null) {
                if (inUse <= 0) {
                    throw new IllegalStateException("名额还多了：没有人占着名额时又还了一次");
                }
                inUse--;
            }
        }
        if (next != null) {
            next.granted();
        }
    }

    synchronized int inUse() {
        return inUse;
    }

    synchronized int queued() {
        return (int) queue.stream().filter(w -> !w.abandoned()).count();
    }
}
