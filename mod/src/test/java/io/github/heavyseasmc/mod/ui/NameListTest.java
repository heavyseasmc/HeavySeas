package io.github.heavyseasmc.mod.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 「全部落海者」底下的名单（2026-10-13 总截图 08：8 人全落海时只剩「珠宝商 · 收…」）。 */
class NameListTest {

    private static final List<String> EIGHT = List.of("珠宝商", "收藏家", "船长", "大副", "陪酒女", "水手", "医生", "小孩");
    private static final ToIntFunction<String> WIDTH = s -> s.length() * 10;
    private static final String SEP = " · ";

    @Test
    @DisplayName("放得下就整串一行；放不下按名字整个换行，每个人都写出来或算进「+N」，没有半截名字")
    void everyNameIsWholeOrCounted() {
        assertEquals(List.of("船长 · 大副"), NameList.pack(List.of("船长", "大副"), SEP, 3, 200, WIDTH));
        int checked = 0;
        for (int room = 40; room <= 400; room += 10) {
            for (int maxLines = 1; maxLines <= 4; maxLines++) {
                List<String> lines = NameList.pack(EIGHT, SEP, maxLines, room, WIDTH);
                String at = "宽 " + room + " · 至多 " + maxLines + " 行 → " + lines;
                assertTrue(lines.size() <= maxLines, "行数超了：" + at);
                int named = 0;
                int more = 0;
                for (String line : lines) {
                    String body = line;
                    int plus = line.startsWith("+") ? 0 : line.lastIndexOf(" +");
                    if (plus >= 0) {
                        more += Integer.parseInt(line.substring(line.indexOf('+', plus) + 1));
                        body = line.substring(0, plus);
                    }
                    for (String part : body.isEmpty() ? new String[0] : body.split(" · ")) {
                        assertTrue(EIGHT.contains(part), "半截名字「" + part + "」：" + at);
                        named++;
                    }
                    // 单个名字本身比一行还宽时只能由画字那一侧截，那一行不查宽
                    if (body.contains(" · ") || plus >= 0) {
                        assertTrue(WIDTH.applyAsInt(line) <= room, "一行超宽：" + at);
                    }
                }
                assertEquals(EIGHT.size(), named + more, "人数对不上：" + at);
                checked++;
            }
        }
        assertTrue(checked > 100);
    }

    @Test
    @DisplayName("总截图那一格的宽：8 人分三行写全")
    void eightSwimmersInThreeLines() {
        // 那一格约能放 10 个字宽：一行三人放不下，两人一行
        List<String> lines = NameList.pack(EIGHT, SEP, 4, 100, WIDTH);
        assertEquals(List.of("珠宝商 · 收藏家", "船长 · 大副", "陪酒女 · 水手", "医生 · 小孩"), lines);
        List<String> capped = NameList.pack(EIGHT, SEP, 3, 100, WIDTH);
        assertEquals("陪酒女 +3", capped.get(2), "第三行放不下「陪酒女 · 水手 +2」：退一个名字进 +N");
    }
}
