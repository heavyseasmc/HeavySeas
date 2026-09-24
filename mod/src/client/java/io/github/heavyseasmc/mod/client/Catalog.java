package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.card.CardFace;
import io.github.heavyseasmc.mod.net.CatalogS2C;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 牌的目录：哪张牌属于哪一类、牌堆里一共几张、角标上印什么数。进服时由服务端发过来（{@link CatalogS2C}）。
 *
 * <p>❗这些是<b>牌自己的属性</b>，数据包说了算 —— 客户端只存不算。把张数写死在客户端等于把
 * 数据包的规则抄了一份，换数据包时两边分家，而分家之后屏幕上照样有数字，只是错的。
 *
 * <p>包还没到（或者根本没发来）时，提示签上那一栏与牌面的角标就<b>空着</b> —— 不编一个数字顶上。
 */
final class Catalog {

    private static final Map<String, CatalogS2C.Provisions> PROVISIONS = new HashMap<>();
    private static final Map<String, List<CardFace.Badge>> CHARACTERS = new HashMap<>();
    /** 天候 id → 信息带里那一排效果图示（ADR-0040）。 */
    private static final Map<String, List<CardFace.Chip>> WEATHERS = new HashMap<>();
    /** 每收到一次目录加一。合成好的牌面用它做键的一部分：目录晚到时，先前合成的那张（角标空着）要作废。 */
    private static int generation;

    private Catalog() {
    }

    static void accept(CatalogS2C payload) {
        PROVISIONS.clear();
        CHARACTERS.clear();
        WEATHERS.clear();
        for (CatalogS2C.Provisions card : payload.provisions()) {
            PROVISIONS.put(card.id(), card);
        }
        for (CatalogS2C.Characters c : payload.characters()) {
            CHARACTERS.put(c.id(), c.badges().stream().map(CatalogS2C.Badge::face).toList());
        }
        for (CatalogS2C.Weathers w : payload.weathers()) {
            WEATHERS.put(w.id(), w.glyph().stream().map(CatalogS2C.Chip::face).toList());
        }
        generation++;
    }

    /** 天候牌信息带里的效果图示；没收到目录时是空的（信息带空着，不编一排顶上）。 */
    static List<CardFace.Chip> weatherGlyph(String id) {
        return WEATHERS.getOrDefault(id, List.of());
    }

    /** 收到几种天候。给日志与 catalog_test 用 —— 「一种都没有」与「没在查」要分得开。 */
    static int weathers() {
        return WEATHERS.size();
    }

    /** 其中信息带里真有图示的几种。与上一个分开数：「条目到了、图示是空的」与「都到了」要分得开。 */
    static int weathersWithGlyph() {
        return (int) WEATHERS.values().stream().filter(g -> !g.isEmpty()).count();
    }

    /** 这张物资牌的目录条目；没收到目录就返回 {@code null}。 */
    static CatalogS2C.Provisions provision(String id) {
        return PROVISIONS.get(id);
    }

    /** 物资牌的角标；没收到目录时是空的（角标空着，不编数）。 */
    static List<CardFace.Badge> provisionBadges(String id) {
        CatalogS2C.Provisions p = PROVISIONS.get(id);
        return p == null ? List.of() : p.badges().stream().map(CatalogS2C.Badge::face).toList();
    }

    /** 角色牌的角标；没收到目录时是空的。 */
    static List<CardFace.Badge> characterBadges(String id) {
        return CHARACTERS.getOrDefault(id, List.of());
    }

    static int generation() {
        return generation;
    }

    /** 收到几种物资。给闸门与日志用 —— 「一种都没有」与「没在查」要分得开。 */
    static int size() {
        return PROVISIONS.size();
    }

    /** 收到几个角色。同上。 */
    static int characters() {
        return CHARACTERS.size();
    }
}
