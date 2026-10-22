package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.mod.state.HudView;
import io.github.heavyseasmc.mod.state.TableView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 批 3 的两处纯逻辑（ADR-0043 §11）：座位签哪几行在什么时候出现（第一组）· 受伤那一下认的是体力变少（第八组）。
 * 没有语言包时 {@code Text.translatable(...).getString()} 是键本身 —— 这里就按键认行。
 */
class InformationSignsTest {

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
    }

    private static TableView.Seat seat(String id, int health, int size, int strength, List<TableView.Today> today,
                                       List<String> front) {
        return new TableView.Seat(id, health, size, Condition.CONSCIOUS, false, false, front, "", strength, 9, today, false);
    }

    private static HudView viewOf(String me, String love, String hate) {
        HudView idle = HudView.IDLE;
        return new HudView(true, 1, idle.phase(), 0, "", true, idle.fog(), List.of(), 0L, List.of(me, "hostess", "kid"),
                List.of(), "", idle.sea(), idle.thirstPrompt(), idle.endgame(), idle.contest(), true, me,
                8, 8, Condition.CONSCIOUS, 0, love, hate, false, 0L, 0L, false, 0L, "", List.of(), 0,
                List.of(), List.of(), idle.myScore());
    }

    @Test
    @DisplayName("座位签：喝了酒写力气 · 今天做过的事 · 面前的牌 · 你恨这个人；本事接在后面，右键提示在最后")
    void seatSignShowsWhatMatters() {
        TableView.Seat hostess = seat("hostess", 1, 3, 6, List.of(TableView.Today.FOUGHT, TableView.Today.DRANK), List.of("life_preserver"));
        List<String> lines = GameScreen.seatSignLines(hostess, viewOf("mate", "kid", "hostess"), List.of("（本事）"));
        assertEquals("heavyseas.seat.sign.head", lines.getFirst());
        assertTrue(lines.contains("heavyseas.seat.sign.strength_rum"), lines.toString());
        assertTrue(lines.contains("heavyseas.seat.sign.front"), lines.toString());
        assertTrue(lines.contains("heavyseas.seat.sign.today"), "打过架那一条要写（喝酒已在力气那一行说了）");
        assertTrue(lines.contains("heavyseas.seat.sign.hate"));
        assertFalse(lines.contains("heavyseas.seat.sign.love"));
        assertEquals(List.of("（本事）", "heavyseas.seat.sign.inspect"), lines.subList(lines.size() - 2, lines.size()));
    }

    @Test
    @DisplayName("座位签：满体力、没喝酒不写力气；受了伤写「还是算几点」；自己那一座不写爱恨")
    void seatSignStaysQuietWhenNothingDiffers() {
        List<String> fresh = GameScreen.seatSignLines(seat("kid", 3, 3, 3, List.of(), List.of()), viewOf("mate", "kid", "kid"), List.of());
        assertFalse(fresh.stream().anyMatch(l -> l.startsWith("heavyseas.seat.sign.strength")), fresh.toString());
        assertTrue(fresh.contains("heavyseas.seat.sign.love") && fresh.contains("heavyseas.seat.sign.hate"), "爱恨同一个人：两行都写");
        List<String> hurt = GameScreen.seatSignLines(seat("kid", 1, 3, 3, List.of(), List.of()), viewOf("mate", "", ""), List.of());
        assertTrue(hurt.contains("heavyseas.seat.sign.strength"), hurt.toString());
        List<String> self = GameScreen.seatSignLines(seat("mate", 8, 8, 8, List.of(), List.of()), viewOf("mate", "mate", "mate"), List.of());
        assertFalse(self.contains("heavyseas.seat.sign.love") || self.contains("heavyseas.seat.sign.hate"), self.toString());
    }

    @Test
    @DisplayName("受伤那一下：只认体力变少；第一次看到的那一份不算；被大海带走的不在这里闪")
    void hurtCueCountsOnlyDrops() {
        List<TableView.Seat> now = List.of(
                seat("mate", 6, 8, 8, List.of(), List.of()),
                seat("kid", 3, 3, 3, List.of(), List.of()),
                new TableView.Seat("hostess", 0, 3, Condition.UNCONSCIOUS, false, true, List.of(), "", 3, 9, List.of(), false));
        assertEquals(Map.of(), HurtCue.drops(null, now), "这一局第一次看：一个都不闪");
        assertEquals(Map.of("mate", 2), HurtCue.drops(Map.of("mate", 8, "kid", 2, "hostess", 3), now),
                "大副掉了 2；小孩回了血不闪；陪酒女被大海带走（落海那一下的事）不闪");
    }
}
