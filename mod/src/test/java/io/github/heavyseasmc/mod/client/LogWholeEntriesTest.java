package io.github.heavyseasmc.mod.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 航海日志底边不切半条（2026-10-13 实拍 A10：战报最后一行「……防守方」被框底切掉，防守方的武器一个字都看不到）。
 */
class LogWholeEntriesTest {

    private static GameHud.LogRow heading(long seq) {
        return new GameHud.LogRow("g:" + seq, seq, "Day", new int[0], new boolean[0], -1, 0, true, "");
    }

    private static List<GameHud.LogRow> entry(long seq, int parts) {
        List<GameHud.LogRow> out = new ArrayList<>();
        for (int j = 0; j < parts; j++) {
            out.add(new GameHud.LogRow(seq + ":" + j, seq, "x", new int[1], new boolean[1], j, parts, false, ""));
        }
        return out;
    }

    /** 标题 · 一行的 · 一行的 · 两行的（战报那一条）· 一行的。 */
    private static List<GameHud.LogRow> rows() {
        List<GameHud.LogRow> rows = new ArrayList<>();
        rows.add(heading(9));
        rows.addAll(entry(9, 1));
        rows.addAll(entry(8, 1));
        rows.addAll(entry(7, 2));
        rows.addAll(entry(6, 1));
        return rows;
    }

    @Test
    @DisplayName("底下那条只放得下前半截：整条让到下面，不切")
    void partialEntryMovesDown() {
        assertEquals(3, GameHud.wholeEntries(rows(), 0, 4));
    }

    @Test
    @DisplayName("放得下整条、或者下面已经没有了：照旧")
    void wholeOrLastIsKept() {
        assertEquals(5, GameHud.wholeEntries(rows(), 0, 5));
        assertEquals(6, GameHud.wholeEntries(rows(), 0, 6));
        assertEquals(2, GameHud.wholeEntries(rows(), 4, 2));
    }

    @Test
    @DisplayName("末尾落单的分组标题不留；一条长过一屏时照旧按行切（钉住之后逐行翻得到）")
    void danglingHeadingAndOverlongEntry() {
        List<GameHud.LogRow> rows = new ArrayList<>(entry(5, 1));
        rows.add(heading(4));
        rows.addAll(entry(4, 1));
        assertEquals(1, GameHud.wholeEntries(rows, 0, 2));
        assertEquals(2, GameHud.wholeEntries(entry(3, 5), 0, 2));
    }
}
