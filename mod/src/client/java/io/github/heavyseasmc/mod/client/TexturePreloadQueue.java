package io.github.heavyseasmc.mod.client;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** 后台解码、调用线程上传。成品队列有界，重载/离线使旧代次失效并释放它的每个结果。 */
final class TexturePreloadQueue<K, V> implements AutoCloseable {
    @FunctionalInterface
    interface Decoder<K, V> {
        V read(K key) throws Exception;
    }

    private record Ready<K, V>(long generation, K key, V value, Throwable failure) { }

    private final ArrayBlockingQueue<Ready<K, V>> ready;
    private final ThreadPoolExecutor worker;
    private final Consumer<V> release;
    private volatile long generation;
    private volatile boolean closed;
    private Future<?> task;

    TexturePreloadQueue(int readyCapacity, Consumer<V> release) {
        this.ready = new ArrayBlockingQueue<>(readyCapacity);
        this.release = release;
        this.worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), runnable -> {
                    Thread thread = new Thread(runnable, "heavyseas-texture-preload");
                    thread.setDaemon(true);
                    return thread;
                });
        worker.allowCoreThreadTimeOut(true);
    }

    synchronized void start(Collection<K> keys, Decoder<K, V> decoder) {
        if (closed) {
            throw new IllegalStateException("Texture preloader is closed");
        }
        clear();
        long ticket = generation;
        List<K> work = List.copyOf(keys);
        if (!work.isEmpty()) {
            task = worker.submit(() -> decode(ticket, work, decoder));
        }
    }

    private boolean current(long ticket) {
        return !closed && ticket == generation && !Thread.currentThread().isInterrupted();
    }

    private void decode(long ticket, List<K> keys, Decoder<K, V> decoder) {
        for (K key : keys) {
            if (!current(ticket)) {
                return;
            }
            V value = null;
            boolean transferred = false;
            try {
                value = decoder.read(key);
                if (!current(ticket)) {
                    return;
                }
                ready.put(new Ready<>(ticket, key, value, null));
                transferred = true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable failure) {
                if (!current(ticket)) {
                    return;
                }
                try {
                    ready.put(new Ready<>(ticket, key, null, failure));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (failure instanceof Error) {
                    return;
                }
            } finally {
                if (!transferred && value != null) {
                    release.accept(value);
                }
            }
        }
    }

    /** use 不取得所有权：无论上传成功、失败还是任务作废，结果都由队列关闭。 */
    int drain(int maximum, BiConsumer<K, V> use, BiConsumer<K, Throwable> failed) {
        int count = 0;
        Ready<K, V> result;
        while (count < maximum && (result = ready.poll()) != null) {
            try {
                if (!closed && result.generation() == generation) {
                    if (result.failure() != null) {
                        failed.accept(result.key(), result.failure());
                    } else {
                        try {
                            use.accept(result.key(), result.value());
                        } catch (RuntimeException failure) {
                            failed.accept(result.key(), failure);
                        }
                    }
                }
            } finally {
                if (result.value() != null) {
                    release.accept(result.value());
                }
            }
            count++;
        }
        return count;
    }

    synchronized void clear() {
        generation++;
        if (task != null) {
            task.cancel(true);
            task = null;
        }
        worker.purge();
        Ready<K, V> result;
        while ((result = ready.poll()) != null) {
            if (result.value() != null) {
                release.accept(result.value());
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        clear();
        worker.shutdownNow();
    }

    boolean awaitTermination(long time, TimeUnit unit) throws InterruptedException {
        return worker.awaitTermination(time, unit);
    }
}
