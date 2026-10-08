package io.github.heavyseasmc.mod.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TexturePreloadQueueTest {
    private static final class Image {
        final int id;
        final AtomicInteger closed = new AtomicInteger();
        Image(int id) { this.id = id; }
    }

    private static void until(BooleanSupplier done, Runnable step) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            step.run();
            Thread.sleep(1);
        }
        assertTrue(done.getAsBoolean(), "异步任务未在测试期限内完成");
    }

    @Test
    void decodingIsOffThreadAndBackpressureBoundsLiveImages() throws Exception {
        Thread main = Thread.currentThread();
        var allocated = new ConcurrentLinkedQueue<Image>();
        AtomicInteger live = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch two = new CountDownLatch(2);
        List<Integer> uploaded = new ArrayList<>();
        TexturePreloadQueue<Integer, Image> queue = new TexturePreloadQueue<>(1, image -> {
            image.closed.incrementAndGet();
            live.decrementAndGet();
        });
        try (queue) {
            queue.start(java.util.stream.IntStream.range(0, 20).boxed().toList(), id -> {
                assertNotSame(main, Thread.currentThread());
                Image image = new Image(id);
                allocated.add(image);
                peak.accumulateAndGet(live.incrementAndGet(), Math::max);
                two.countDown();
                return image;
            });
            assertTrue(two.await(5, TimeUnit.SECONDS));
            assertEquals(2, allocated.size(), "一张等待上传、一张生产者持有，不能继续全量解码");
            until(() -> uploaded.size() == 20, () -> queue.drain(4, (id, image) -> {
                assertSame(main, Thread.currentThread());
                assertEquals(0, image.closed.get(), "上传前不能先释放");
                uploaded.add(id);
            }, (id, failure) -> fail(failure)));
        }
        assertTrue(queue.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(peak.get() <= 3, "最多一张在上传、一张在队列、一张在解码");
        assertEquals(0, live.get());
        assertTrue(allocated.stream().allMatch(image -> image.closed.get() == 1));
    }

    @Test
    void reloadDiscardsEvenADecoderThatDoesNotImmediatelyHonorInterrupts() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        Image old = new Image(1);
        Image fresh = new Image(2);
        List<Integer> uploaded = new ArrayList<>();
        TexturePreloadQueue<Integer, Image> queue = new TexturePreloadQueue<>(1, image -> image.closed.incrementAndGet());
        try (queue) {
            queue.start(List.of(1), id -> {
                started.countDown();
                while (finish.getCount() != 0) {
                    try { finish.await(); } catch (InterruptedException ignored) { /* 模拟尚未返回的原生解码 */ }
                }
                return old;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            queue.start(List.of(2), id -> fresh);
            assertEquals(0, old.closed.get(), "解码还在用，不能在另一个线程提前释放");
            finish.countDown();
            until(() -> uploaded.size() == 1, () -> queue.drain(4, (id, image) -> uploaded.add(id),
                    (id, failure) -> fail(failure)));
            assertEquals(List.of(2), uploaded, "旧资源代次不能回到新一轮的纹理管理器");
        } finally {
            finish.countDown();
        }
        assertTrue(queue.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1, old.closed.get());
        assertEquals(1, fresh.closed.get());
    }

    @Test
    void uploadFailureStillReleasesItsImage() throws Exception {
        Image image = new Image(1);
        List<Throwable> failures = new ArrayList<>();
        TexturePreloadQueue<Integer, Image> queue = new TexturePreloadQueue<>(1, value -> value.closed.incrementAndGet());
        try (queue) {
            queue.start(List.of(1), id -> image);
            until(() -> !failures.isEmpty(), () -> queue.drain(1, (id, value) -> {
                throw new IllegalStateException("upload failed");
            }, (id, failure) -> failures.add(failure)));
        }
        assertTrue(queue.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1, image.closed.get());
        assertEquals("upload failed", failures.getFirst().getMessage());
    }

    @Test
    void disconnectClosesBufferedAndBlockedResults() throws Exception {
        var allocated = new ConcurrentLinkedQueue<Image>();
        CountDownLatch two = new CountDownLatch(2);
        TexturePreloadQueue<Integer, Image> queue = new TexturePreloadQueue<>(1, image -> image.closed.incrementAndGet());
        queue.start(List.of(1, 2, 3), id -> {
            Image image = new Image(id);
            allocated.add(image);
            two.countDown();
            return image;
        });
        assertTrue(two.await(5, TimeUnit.SECONDS));
        queue.close();
        assertTrue(queue.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(2, allocated.size());
        assertTrue(allocated.stream().allMatch(image -> image.closed.get() == 1));
        assertThrows(IllegalStateException.class, () -> queue.start(List.of(4), Image::new));
    }
}
