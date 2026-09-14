package io.github.heavyseasmc.engine.navigation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 牌堆：从顶抽、放回进底、同一个种子洗出同一副牌。 */
class NavigationDeckTest {

    private static List<NavigationCard> cards(int n) {
        List<NavigationCard> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new NavigationCard("c" + i, 0, new Selector.Nobody(), new Selector.Nobody(), false, false));
        }
        return out;
    }

    private static List<String> drawAll(NavigationDeck deck) {
        List<String> ids = new ArrayList<>();
        while (!deck.isEmpty()) {
            ids.add(deck.draw().id());
        }
        return ids;
    }

    @Test
    @DisplayName("同一个种子洗出同一副牌 —— 否则一局复现不了")
    void shuffleIsReproducible() {
        assertEquals(drawAll(new NavigationDeck(cards(20), new Random(42))),
                drawAll(new NavigationDeck(cards(20), new Random(42))));
    }

    @Test
    @DisplayName("真的洗过：不同种子给出不同顺序，且都是原来那 20 张")
    void shuffleActuallyShuffles() {
        List<String> a = drawAll(new NavigationDeck(cards(20), new Random(1)));
        List<String> b = drawAll(new NavigationDeck(cards(20), new Random(2)));
        assertNotEquals(a, b, "两个种子洗出同样的顺序，洗牌多半没生效");
        assertEquals(20, a.size());
        assertTrue(a.containsAll(drawAll(new NavigationDeck(cards(20), new Random(3)))),
                "洗牌把牌洗没了或洗出了新牌");
    }

    @Test
    @DisplayName("放回一律进底：刚放回的那张要等一整圈才会再出现")
    void returnedCardGoesToTheBottom() {
        NavigationDeck deck = new NavigationDeck(cards(5), new Random(7));
        NavigationCard first = deck.draw();
        deck.bottom(first);
        assertEquals(5, deck.size());

        List<String> order = drawAll(deck);
        assertEquals(first.id(), order.get(order.size() - 1), "放回的牌没进底部");
    }

    @Test
    @DisplayName("抽空了就抛，不自动重洗 —— 牌堆空了说明有牌没放回，那是要立刻知道的事")
    void emptyDrawThrows() {
        NavigationDeck deck = new NavigationDeck(cards(1), new Random(0));
        deck.draw();
        IllegalStateException e = assertThrows(IllegalStateException.class, deck::draw);
        assertTrue(e.getMessage().contains("共 1 张"), e.getMessage());
    }

    @Test
    @DisplayName("空牌堆拒绝构造")
    void emptyDeckRejected() {
        assertThrows(IllegalArgumentException.class, () -> new NavigationDeck(List.of(), new Random(0)));
    }
}
