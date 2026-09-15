package io.github.heavyseasmc.engine.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 全部物资牌的目录：id → {@link Provision}，外加按张数展开的整副牌。
 *
 * <h2>为什么没有「查不到就当没效果」这条路</h2>
 * 查不到 id 一律抛。静默返回「没效果」会让「数据包少了一张牌」与「这张牌本来就没效果」
 * 在运行时完全相同 —— 本仓库的 {@code asset_root()} 已经为这个形状付过一次学费。
 *
 * <p>牌只从牌堆发出来，而牌堆由本目录展开，所以「手上有一张目录不认识的牌」只可能是代码写错了。
 */
public final class Provisions {

    private final Map<String, Provision> byId;
    private final List<String> deck;

    public Provisions(List<Provision> cards) {
        Objects.requireNonNull(cards, "cards");
        Map<String, Provision> map = new LinkedHashMap<>();
        List<String> expanded = new ArrayList<>();
        for (Provision card : cards) {
            if (map.put(card.id(), card) != null) {
                throw new IllegalArgumentException("物资 id 重复: " + card.id());
            }
            for (int i = 0; i < card.count(); i++) {
                expanded.add(card.id());
            }
        }
        if (map.isEmpty()) {
            throw new IllegalArgumentException("物资目录不能为空");
        }
        this.byId = Map.copyOf(map);
        this.deck = List.copyOf(expanded);
    }

    /**
     * 查一张。
     *
     * @throws IllegalArgumentException 目录里没有这个 id —— 见类注释，这只可能是代码写错了
     */
    public Provision get(String id) {
        Provision card = byId.get(id);
        if (card == null) {
            throw new IllegalArgumentException(
                    "物资目录里没有 %s（目录里有 %d 种：%s）".formatted(id, byId.size(), sortedIds()));
        }
        return card;
    }

    public boolean has(String id) {
        return byId.containsKey(id);
    }

    /** id 全集。 */
    public Set<String> ids() {
        return byId.keySet();
    }

    /** 全部牌，按目录顺序。 */
    public List<Provision> all() {
        return List.copyOf(byId.values());
    }

    /** 整副牌，按张数展开（水出现 16 次）。发牌要的是这一份。 */
    public List<String> deck() {
        return deck;
    }

    /** 整副共几张。 */
    public int total() {
        return deck.size();
    }

    private List<String> sortedIds() {
        return byId.keySet().stream().sorted().toList();
    }
}
