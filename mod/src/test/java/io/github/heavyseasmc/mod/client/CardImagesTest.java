package io.github.heavyseasmc.mod.client;

import net.minecraft.client.texture.MipmapHelper;
import net.minecraft.client.texture.NativeImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardImagesTest {
    private static NativeImage image(int w, int h, boolean transparent) {
        NativeImage image = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int alpha = transparent && (x + y) % 3 == 0 ? 0 : 255;
                image.setColor(x, y, alpha << 24 | ((x * 31) & 255) << 16 | ((y * 47) & 255) << 8 | 91);
            }
        }
        return image;
    }

    @Test
    void pixelsMatchMinecraftForOpaqueTransparentAndOddSizedImages() {
        for (boolean transparent : new boolean[]{false, true}) {
            for (int width : new int[]{1, 13, 32}) {
                int height = 27;
                int last = CardTexture.mipLevels(width, height, 4);
                NativeImage[] expected = MipmapHelper.getMipmapLevelsImages(
                        new NativeImage[]{image(width, height, transparent)}, last);
                try (CardImages actual = CardImages.take(image(width, height, transparent), 4,
                        (w, h) -> new NativeImage(w, h, false))) {
                    assertEquals(last, actual.maxLevel());
                    for (int level = 0; level <= last; level++) {
                        NativeImage got = actual.level(level);
                        assertEquals(expected[level].getWidth(), got.getWidth());
                        assertEquals(expected[level].getHeight(), got.getHeight());
                        for (int y = 0; y < got.getHeight(); y++) {
                            for (int x = 0; x < got.getWidth(); x++) {
                                assertEquals(expected[level].getColor(x, y), got.getColor(x, y));
                            }
                        }
                    }
                } finally {
                    for (NativeImage level : expected) {
                        level.close();
                    }
                }
            }
        }
    }

    @Test
    void partialGenerationFailureClosesTheBaseAndEveryCompletedAllocation() {
        NativeImage base = image(32, 32, true);
        var allocated = new ArrayList<NativeImage>();
        assertThrows(IllegalStateException.class, () -> CardImages.take(base, 4, (w, h) -> {
            if (allocated.size() == 2) {
                throw new IllegalStateException("injected allocation failure");
            }
            NativeImage next = new NativeImage(w, h, false);
            allocated.add(next);
            return next;
        }));
        assertEquals(2, allocated.size(), "故障要发生在已经生成部分图像之后");
        assertThrows(IllegalStateException.class, () -> base.getColor(0, 0));
        for (NativeImage level : allocated) {
            assertThrows(IllegalStateException.class, () -> level.getColor(0, 0));
        }
    }

    @Test
    void closingTheOwnerReleasesAllLevelsAndCanBeRepeated() {
        CardImages images = CardImages.take(image(16, 16, false), 4,
                (w, h) -> new NativeImage(w, h, false));
        assertEquals(4, images.maxLevel());
        images.close();
        images.close();
        for (int level = 0; level <= 4; level++) {
            NativeImage image = images.level(level);
            assertThrows(IllegalStateException.class, () -> image.getColor(0, 0));
        }
    }

    @Test
    @DisplayName("从流读入：底图读进来了、生成到一半分配失败 —— 底图与已经生成的每一级都关掉，流也关掉，异常照原样抛")
    void readFailureAfterDecodingClosesTheBaseAndEveryCompletedLevel() throws Exception {
        // 守的缺陷：CardImages.read 失败路径里的 images.close() 被删掉。
        // 原先只有 unreadableInputIsClosed 走 read：那一条在解码就失败，底图根本没造出来，有没有 close 都一样绿；
        // 关流那一下 NativeImage.read 自己也会做，同样分不出来。要分得出，失败必须发生在「底图已读进、部分级已分配」之后，
        // 而且要拿得到这几张图的引用 —— 所以经包内接缝换上记账的解码与分配（解码照样走 NativeImage.read，读的是真 PNG）。
        NativeImage source = image(32, 32, true);
        byte[] png;
        try {
            png = source.getBytes();
        } finally {
            source.close();
        }
        boolean[] streamClosed = {false};
        var input = new ByteArrayInputStream(png) {
            @Override
            public void close() throws IOException {
                streamClosed[0] = true;
                super.close();
            }
        };
        NativeImage[] decoded = {null};
        var allocated = new ArrayList<NativeImage>();
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> CardImages.read(input, 4,
                stream -> decoded[0] = NativeImage.read(stream),
                (w, h) -> {
                    if (allocated.size() == 2) {
                        throw new IllegalStateException("injected allocation failure");
                    }
                    NativeImage next = new NativeImage(w, h, false);
                    allocated.add(next);
                    return next;
                }));
        assertEquals("injected allocation failure", thrown.getMessage(), "抛出来的不是注入的那一个：失败路径把异常换掉了");
        assertTrue(decoded[0] != null, "前提：底图已经读进来了");
        assertEquals(2, allocated.size(), "前提：故障发生在已经生成两级之后");
        assertTrue(streamClosed[0], "失败之后流没关");
        assertThrows(IllegalStateException.class, () -> decoded[0].getColor(0, 0),
                "读入失败后底图还开着：read 的失败路径没关已经读进来的图（每失败一张卡图漏一整张原生内存）");
        for (NativeImage level : allocated) {
            assertThrows(IllegalStateException.class, () -> level.getColor(0, 0),
                    "读入失败后已经生成的一级还开着：read 的失败路径没关它");
        }
    }

    @Test
    void unreadableInputIsClosed() {
        boolean[] closed = {false};
        var input = new ByteArrayInputStream(new byte[]{1, 2, 3}) {
            @Override
            public void close() throws IOException {
                closed[0] = true;
                super.close();
            }
        };
        assertThrows(IOException.class, () -> CardImages.read(input, 4));
        assertTrue(closed[0]);
    }
}
