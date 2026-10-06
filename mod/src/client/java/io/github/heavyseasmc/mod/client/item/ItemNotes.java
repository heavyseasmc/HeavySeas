package io.github.heavyseasmc.mod.client.item;

import net.minecraft.client.resource.language.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * 物品名之外的两样东西（ADR-0093 B13 · B8），都从 lang 读：
 * <ul>
 *   <li>{@code <名字的键>.desc}：名字下面那一行灰字，只给名字分不开的（整扇窗的尺寸 —— 同 Minecraft 自带的画那一行尺寸）；</li>
 *   <li>{@code <名字的键>.search}：搜索别名，建筑师常用的叫法（搜「踢脚线」「baseboard」找得到踢脚条）。<b>不显示</b>，
 *       只在创造物品栏建搜索索引时加进去 —— Minecraft 建索引时取提示框文字、玩家传的是 null（{@code SearchManager}），
 *       屏幕上的提示框则一定带着玩家。</li>
 * </ul>
 *
 * <p>不在 {@code client} 包里：那里是本模组自己的界面，颜色只许用 GuiLanguage 那三个（GuiConsistencyTest）；
 * 这里加的是 Minecraft 自带提示框里的一行，照它的规矩用灰色（自带的画那一行尺寸就是灰的）。
 */
public final class ItemNotes {

    private ItemNotes() {
    }

    /** 提示框的行加上灰字与（建索引时）别名；不是本模组的物品、或提示框被藏起来（空表）时原样返回。 */
    public static List<Text> apply(ItemStack stack, List<Text> lines, boolean indexing) {
        String key = stack.getItem().getTranslationKey();
        if (lines.isEmpty() || !key.contains(".heavyseas.")) {
            return lines;
        }
        boolean desc = I18n.hasTranslation(key + ".desc");
        boolean search = indexing && I18n.hasTranslation(key + ".search");
        if (!desc && !search) {
            return lines;
        }
        List<Text> out = new ArrayList<>(lines);
        if (desc) {
            out.add(1, Text.translatable(key + ".desc").formatted(Formatting.GRAY));
        }
        if (search) {
            out.add(Text.translatable(key + ".search"));
        }
        return out;
    }
}
