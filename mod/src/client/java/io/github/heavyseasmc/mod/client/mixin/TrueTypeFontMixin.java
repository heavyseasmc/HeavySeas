package io.github.heavyseasmc.mod.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.client.font.FreeTypeUtil;
import net.minecraft.client.font.Glyph;
import net.minecraft.client.font.TrueTypeFont;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 给 TTF 字形查询补上 Minecraft 自己漏掉的那把锁（ADR-0037 §7.5）。
 *
 * <p>Minecraft 1.21.1 重载资源时，为<b>每个字体定义</b>各起一个异步任务，在工作线程上对每个字符调
 * {@code getGlyph} 探测字形（{@code FontManager.insertFont}）；而所有 TTF 共用同一个 FreeType 库实例，
 * {@code getGlyph} 里的 {@code FT_Load_Glyph} 没有任何同步 —— Minecraft 只在加载与关闭字体时拿 {@link FreeTypeUtil#LOCK}。
 * Minecraft 自带的资源里至多一份 TTF 定义，所以这个竞态从不发作；GUI 的字号梯子有十几份（每个物理像素字号一份），
 * 2026-09-21 实测客户端启动即原生崩溃：{@code EXCEPTION_ACCESS_VIOLATION}，崩溃栈在
 * {@code FT_Load_Glyph ← TrueTypeFont.getGlyph ← FontManager.insertFont}，同一刻另有一个工作线程也在原生代码里。
 *
 * <p>锁用 Minecraft 自己那一把，不另造：与加载、关闭互斥才完整。
 *
 * <p>❗<b>只针对 1.21.1。</b>Mojang 从 1.21.2 起自己给字形信息加了 {@code synchronized (face)}（对照过 1.21.2 – 26.2 的源码）。
 * 那是<b>按字体面</b>的锁；这里特意用<b>库级</b>的：{@code reference} 提供者在每个引用它的字体集里复用同一个实例
 * （粗体定义引用了同字号的常规定义），同一张面会被两个任务同时探测，而不同的面之间也共用一个库 —— 库级的锁两种情形都盖住。
 * 移植到 1.21.2 及以后时，这两个 mixin 要按版本关掉再重新验证。
 */
@Mixin(TrueTypeFont.class)
public abstract class TrueTypeFontMixin {

    @WrapMethod(method = "getGlyph")
    private Glyph heavyseas$lockGetGlyph(int codePoint, Operation<Glyph> original) {
        synchronized (FreeTypeUtil.LOCK) {
            return original.call(codePoint);
        }
    }

    @WrapMethod(method = "getProvidedGlyphs")
    private IntSet heavyseas$lockProvidedGlyphs(Operation<IntSet> original) {
        synchronized (FreeTypeUtil.LOCK) {
            return original.call();
        }
    }
}
