package io.github.heavyseasmc.mod.client;

import net.minecraft.client.texture.MipmapHelper;
import net.minecraft.client.texture.NativeImage;
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
