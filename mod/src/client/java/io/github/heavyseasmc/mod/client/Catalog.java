package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.net.CatalogS2C;
import java.util.HashMap;
import java.util.Map;

/**
 * 牌的目录：哪张牌属于哪一类、牌堆里一共几张。进服时由服务端发过来（{@link CatalogS2C}）。
 *
 * <p>❗这些是<b>牌自己的属性</b>，数据包说了算 —— 客户端只存不算。把张数写死在这边等于把
 * 数据包的规则抄了一份，换数据包时两边分家，而分家之后屏幕上照样有数字，只是错的。
 *
 * <p>包还没到（或者根本没发来）时，提示签上那一栏就<b>空着</b> —— 不编一个数字顶上。
 */
final class Catalog {

    private static final Map<String, CatalogS2C.Provisions> PROVISIONS = new HashMap<>();

    private Catalog() {
    }

    static void accept(CatalogS2C payload) {
        PROVISIONS.clear();
        for (CatalogS2C.Provisions card : payload.provisions()) {
            PROVISIONS.put(card.id(), card);
        }
    }

    /** 这张物资牌的目录条目；没收到目录就返回 {@code null}。 */
    static CatalogS2C.Provisions provision(String id) {
        return PROVISIONS.get(id);
    }

    /** 收到几种物资。给闸门与日志用 —— 「一种都没有」与「没在查」要分得开。 */
    static int size() {
        return PROVISIONS.size();
    }
}
