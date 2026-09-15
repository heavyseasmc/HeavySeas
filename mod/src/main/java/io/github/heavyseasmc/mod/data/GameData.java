package io.github.heavyseasmc.mod.data;

import io.github.heavyseasmc.engine.data.RosterData;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.navigation.NavigationCard;

import java.util.List;
import java.util.Objects;

/**
 * 一整套配平：角色表 · 物资 id 全集 · 航海牌堆，外加它们共同自称的变体 id。
 *
 * <h2>三份一起换，不单换</h2>
 * 三份数据互相点名（航海牌点角色 id 与物资 id），所以它们只有作为一套才有意义。
 * 本类刻意做成一个不可变整体：{@link GameDataLoader} 三份全读成功才换上去，
 * 任何一份坏了就一份都不换 —— 半套配平比旧的一整套更糟，而且坏在哪儿看不出来。
 *
 * @param variant    三份数据共同自称的 id，形如 {@code heavyseas:default}
 * @param roster     角色表与预设
 * @param provisions 物资目录：18 种的类别、张数与<b>效果</b>。
 *                   整副牌（按张数展开的 47 张）由它给出，不另存一份 —— 两份会漂
 * @param navigation 航海牌堆
 */
public record GameData(String variant, RosterData roster, Provisions provisions,
                       List<NavigationCard> navigation) {

    public GameData {
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(roster, "roster");
        Objects.requireNonNull(provisions, "provisions");
        navigation = List.copyOf(navigation);
    }
}
