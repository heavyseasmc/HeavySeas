package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.Selector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeckStatsCacheTest {
    private static final Set<CharacterId> BOARD = Set.of(CharacterId.of("sailor"));

    private static List<NavigationCard> deck(int id) {
        return List.of(new NavigationCard("cache_" + id, 0, new Selector.Nobody(), new Selector.Everyone(), false, false));
    }

    @Test
    void equalDecksReuseStatsAndNewDataCannotGrowTheCacheForever() {
        Object first = Outlook.cachedDeckStats(deck(-1), BOARD);
        assertSame(first, Outlook.cachedDeckStats(new ArrayList<>(deck(-1)), BOARD));
        for (int i = 0; i < 2 * Outlook.MAX_DECK_STATS; i++) {
            Outlook.cachedDeckStats(deck(i), BOARD);
        }
        assertTrue(Outlook.cachedDeckCount() <= Outlook.MAX_DECK_STATS);
        Object renewed = Outlook.cachedDeckStats(deck(-1), BOARD);
        assertSame(renewed, Outlook.cachedDeckStats(deck(-1), BOARD));
    }

    @Test
    void concurrentSearchesKeepTheLimitAndReturnUsableEntries() throws Exception {
        try (var workers = Executors.newFixedThreadPool(4)) {
            var jobs = new ArrayList<java.util.concurrent.Callable<Void>>();
            for (int worker = 0; worker < 4; worker++) {
                int offset = worker * Outlook.MAX_DECK_STATS;
                jobs.add(() -> {
                    for (int i = 0; i < Outlook.MAX_DECK_STATS; i++) {
                        Object result = Outlook.cachedDeckStats(deck(offset + i), BOARD);
                        org.junit.jupiter.api.Assertions.assertNotNull(result);
                    }
                    return null;
                });
            }
            for (var result : workers.invokeAll(jobs)) {
                result.get();
            }
        }
        assertTrue(Outlook.cachedDeckCount() <= Outlook.MAX_DECK_STATS);
    }
}
