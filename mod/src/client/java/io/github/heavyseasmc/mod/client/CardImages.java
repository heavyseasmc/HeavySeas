package io.github.heavyseasmc.mod.client;

import net.minecraft.client.texture.MipmapHelper;
import net.minecraft.client.texture.NativeImage;

import java.io.IOException;
import java.io.InputStream;

/** 一张卡图的 CPU 图像链；开始读入就取得所有权，任何一步失败都释放已经分配的图像。 */
final class CardImages implements AutoCloseable {
    private final NativeImage[] levels;

    private CardImages(int count) {
        levels = new NativeImage[count];
    }

    @FunctionalInterface
    interface Allocator {
        NativeImage create(int width, int height);
    }

    static CardImages read(InputStream stream, int maxLevels) throws IOException {
        // 先有所有者，再读取图像；流关闭失败也经过同一条释放路径。
        CardImages images = null;
        try {
            try (stream) {
                images = new CardImages(maxLevels + 1);
                images.levels[0] = NativeImage.read(stream);
                images.generate(maxLevels, (w, h) -> new NativeImage(w, h, false));
            }
            return images;
        } catch (IOException | RuntimeException | Error failure) {
            if (images != null) {
                images.close();
            }
            throw failure;
        }
    }

    /** 接管 base，供从内存生成的图像及分配失败回归共用。 */
    static CardImages take(NativeImage base, int maxLevels, Allocator allocator) {
        CardImages images;
        try {
            images = new CardImages(maxLevels + 1);
        } catch (RuntimeException | Error failure) {
            base.close();
            throw failure;
        }
        images.levels[0] = base;
        try {
            images.generate(maxLevels, allocator);
            return images;
        } catch (RuntimeException | Error failure) {
            images.close();
            throw failure;
        }
    }

    private void generate(int maxLevels, Allocator allocator) {
        NativeImage base = levels[0];
        int last = CardTexture.mipLevels(base.getWidth(), base.getHeight(), maxLevels);
        boolean alpha = MipmapHelper.hasAlpha(base);
        for (int level = 1; level <= last; level++) {
            NativeImage previous = levels[level - 1];
            NativeImage next = allocator.create(previous.getWidth() >> 1, previous.getHeight() >> 1);
            levels[level] = next;
            for (int y = 0; y < next.getHeight(); y++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException("Texture generation cancelled");
                }
                for (int x = 0; x < next.getWidth(); x++) {
                    next.setColor(x, y, MipmapHelper.blend(previous.getColor(x * 2, y * 2),
                            previous.getColor(x * 2 + 1, y * 2), previous.getColor(x * 2, y * 2 + 1),
                            previous.getColor(x * 2 + 1, y * 2 + 1), alpha));
                }
            }
        }
    }

    NativeImage level(int level) {
        return levels[level];
    }

    int maxLevel() {
        int last = 0;
        while (last + 1 < levels.length && levels[last + 1] != null) {
            last++;
        }
        return last;
    }

    @Override
    public void close() {
        for (NativeImage image : levels) {
            if (image != null) {
                image.close();
            }
        }
    }
}
