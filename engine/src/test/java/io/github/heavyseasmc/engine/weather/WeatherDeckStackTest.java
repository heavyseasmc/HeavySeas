package io.github.heavyseasmc.engine.weather;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调试口「下一天翻出哪一张」（ADR-0060）：只调顺序，不造牌、不丢牌。
 *
 * <p>每条都配对照：不调的话下一张<b>不是</b>它（否则「调成功了」什么也说明不了）。
 */
class WeatherDeckStackTest {

    private static final List<WeatherCard> CARDS = List.of(
            new WeatherCard("rain", WeatherEffect.IGNORE_THIRST),
            new WeatherCard("gale", WeatherEffect.EXTRA_NAVIGATION),
            new WeatherCard("storm", WeatherEffect.ROWERS_OVERBOARD),
            new WeatherCard("sunday", WeatherEffect.EXTRA_PROVISION),
            new WeatherCard("clear", WeatherEffect.RESHUFFLE_DISCARD));

    private static List<String> ids(List<WeatherCard> cards) {
        return cards.stream().map(WeatherCard::id).toList();
    }

    private static List<String> sorted(List<WeatherCard> cards) {
        List<String> out = new ArrayList<>(ids(cards));
        out.sort(Comparator.naturalOrder());
        return out;
    }

    @Test
    @DisplayName("调到顶上的那一张就是下一天翻出来的；张数与成员不变")
    void stackedCardIsDrawnNext() {
        WeatherDeck deck = new WeatherDeck(CARDS, new Random(5));
        List<WeatherCard> before = deck.upcoming();
        String target = before.get(before.size() - 1).id();       // 最底下那一张：不调就轮不到它
        assertNotEquals(target, before.get(0).id(), "对照：不调的话下一张不是它");

        assertTrue(deck.stackNext(target));
        assertEquals(target, deck.upcoming().get(0).id());
        assertEquals(sorted(before), sorted(deck.upcoming()), "只调顺序：张数与成员都不变");
        assertEquals(0, deck.discarded());

        assertEquals(target, deck.draw().id());
        assertEquals(before.size() - 1, deck.remaining());
    }

    @Test
    @DisplayName("牌堆里没有那一张：拒绝，什么也不改")
    void unknownOrAlreadyDrawnIsRejected() {
        WeatherDeck deck = new WeatherDeck(CARDS, new Random(5));
        WeatherCard first = deck.draw();
        List<String> before = ids(deck.upcoming());
        assertFalse(deck.stackNext("no_such_weather"));
        assertFalse(deck.stackNext(first.id()), "今天这张已经翻出来了，牌堆还没空，下一天不可能是它");
        assertEquals(before, ids(deck.upcoming()));
        assertNotEquals(first.id(), deck.draw().id());
    }

    @Test
    @DisplayName("牌堆已空时：下一天会先把弃牌堆（连同今天这张）洗回来，那就在那几张里找")
    void emptyPileLooksAtWhatWillBeReshuffled() {
        WeatherDeck deck = new WeatherDeck(CARDS, new Random(9));
        List<String> drawn = new ArrayList<>();
        for (int i = 0; i < CARDS.size(); i++) {
            drawn.add(deck.draw().id());
        }
        assertEquals(0, deck.remaining());
        assertFalse(deck.stackNext("no_such_weather"));
        String today = drawn.get(drawn.size() - 1);
        String earlier = drawn.get(1);
        assertTrue(deck.stackNext(earlier));
        assertEquals(earlier, deck.draw().id());

        // 今天桌面上这张也会被洗回去：也翻得出来
        WeatherDeck again = new WeatherDeck(CARDS, new Random(9));
        for (int i = 0; i < CARDS.size(); i++) {
            again.draw();
        }
        assertTrue(again.stackNext(today));
        assertEquals(today, again.draw().id());
    }

    @Test
    @DisplayName("调完之后有人把弃牌堆洗回牌库（晴空）：翻牌那一刻仍是它")
    void survivesAReshuffle() {
        WeatherDeck deck = new WeatherDeck(CARDS, new Random(13));
        deck.draw();
        deck.draw();                                                // 弃牌堆里有一张，洗回时会打乱牌堆
        String target = deck.upcoming().get(deck.upcoming().size() - 1).id();
        assertTrue(deck.stackNext(target));
        deck.reshuffleDiscard();
        assertEquals(target, deck.draw().id());
    }
}
