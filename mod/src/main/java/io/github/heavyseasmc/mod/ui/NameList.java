package io.github.heavyseasmc.mod.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 一串人名排成几行（落海那一窗「全部落海者」底下的名单）：按名字整个换行，不在名字中间截；
 * 行数到顶还剩人，最后一行末尾写「+N」。原先一行写全、放不下由画字那一侧截断，8 人全落海时只剩「珠宝商 · 收…」（2026-10-13 总截图 08）。
 *
 * <p>纯逻辑：字宽由调用方量（客户端按字号梯子量，单测给一个假的）。
 */
public final class NameList {

    private NameList() {
    }

    /**
     * @param names    名字，按座位顺序
     * @param sep      名字之间的隔字（「 · 」）
     * @param maxLines 最多几行
     * @param room     一行多宽（与 {@code width} 同一个单位）
     * @param width    一串字画出来多宽
     * @return 每一行的字；每个名字要么完整出现在某一行，要么算进最后的「+N」
     */
    public static List<String> pack(List<String> names, String sep, int maxLines, int room, ToIntFunction<String> width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            String tryLine = line.isEmpty() ? name : line + sep + name;
            if (line.isEmpty() || width.applyAsInt(tryLine) <= room) {
                line.setLength(0);
                line.append(tryLine);
                continue;
            }
            if (lines.size() + 1 == maxLines) {
                lines.add(withMore(line.toString(), names.size() - i, sep, room, width));
                return lines;
            }
            lines.add(line.toString());
            line.setLength(0);
            line.append(name);
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    /** 最后一行末尾挂「+N」：挂不下就从尾巴上退名字，退出来的也算进 N。 */
    private static String withMore(String line, int more, String sep, int room, ToIntFunction<String> width) {
        List<String> parts = new ArrayList<>(List.of(line.split(java.util.regex.Pattern.quote(sep), -1)));
        int n = more;
        while (!parts.isEmpty() && width.applyAsInt(String.join(sep, parts) + " +" + n) > room) {
            parts.removeLast();
            n++;
        }
        return parts.isEmpty() ? "+" + n : String.join(sep, parts) + " +" + n;   // 一个名字都挂不下：只写人数
    }
}
