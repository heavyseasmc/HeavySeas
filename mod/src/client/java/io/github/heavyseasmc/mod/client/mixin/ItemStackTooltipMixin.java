package io.github.heavyseasmc.mod.client.mixin;

import io.github.heavyseasmc.mod.client.item.ItemNotes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 物品提示框：名字下面那一行灰字与搜索别名（ADR-0093 B13 · B8，{@link ItemNotes}）。
 * 玩家为 null 就是创造物品栏在建搜索索引（{@code SearchManager} 这么调），别名只在那时加。
 */
@Mixin(ItemStack.class)
public abstract class ItemStackTooltipMixin {

    @Inject(method = "getTooltip", at = @At("RETURN"), cancellable = true)
    private void heavyseas$notes(Item.TooltipContext context, @Nullable PlayerEntity player, TooltipType type,
                                 CallbackInfoReturnable<List<Text>> cir) {
        List<Text> lines = cir.getReturnValue();
        List<Text> out = ItemNotes.apply((ItemStack) (Object) this, lines, player == null);
        if (out != lines) {
            cir.setReturnValue(out);
        }
    }
}
