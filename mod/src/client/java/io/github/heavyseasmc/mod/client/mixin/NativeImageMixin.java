package io.github.heavyseasmc.mod.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.font.FreeTypeUtil;
import net.minecraft.client.texture.NativeImage;
import org.lwjgl.util.freetype.FT_Face;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 字形上传那一半的锁：渲染线程把字形栅格化进图集时同样无锁地调 {@code FT_Load_Glyph}，
 * 会与重载资源时工作线程上的字形探测撞在一起。理由与 {@link TrueTypeFontMixin} 同一条。
 */
@Mixin(NativeImage.class)
public abstract class NativeImageMixin {

    @WrapMethod(method = "makeGlyphBitmapSubpixel")
    private boolean heavyseas$lockGlyphBitmap(FT_Face face, int glyphIndex, Operation<Boolean> original) {
        synchronized (FreeTypeUtil.LOCK) {
            return original.call(face, glyphIndex);
        }
    }
}
