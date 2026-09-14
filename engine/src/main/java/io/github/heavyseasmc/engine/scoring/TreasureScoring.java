package io.github.heavyseasmc.engine.scoring;

import java.util.List;
import java.util.Objects;

/**
 * 财宝怎么计分。数值来自 {@code data/roster} 的 {@code treasure_scoring}，<b>引擎里不另写一份</b>。
 *
 * <p>此前珠宝的套组表写死在 {@link Scorer} 里，数据里那份改了不会有任何反应、也不会报错 ——
 * 同一份数值两个真相源，而只有代码那份在生效。
 *
 * <h2>珠宝表用列表而不是映射</h2>
 * 第 i 个元素是「持有 i+1 张」时的套组总分。列表天然没有缺档：「有 1 张与 3 张的分、没有 2 张的分」
 * 这种表用列表写不出来，用映射写得出来。珠宝的全局张数就是列表长度。
 *
 * @param cashFaceValue     每张现金的分值
 * @param fineArtFaceValues 每张美术品的面值。美术品是几张面值不同的牌，所以逐张列出
 * @param jewelrySetTotals  珠宝套组总分，按持有张数从 1 开始排列
 */
public record TreasureScoring(int cashFaceValue, List<Integer> fineArtFaceValues, List<Integer> jewelrySetTotals) {

    public TreasureScoring {
        if (cashFaceValue < 0) {
            throw new IllegalArgumentException("现金分值不能为负: " + cashFaceValue);
        }
        fineArtFaceValues = nonNegative("美术品面值", fineArtFaceValues);
        jewelrySetTotals = nonNegative("珠宝套组分", jewelrySetTotals);
    }

    /** 珠宝全局有几张。 */
    public int jewelryCards() {
        return jewelrySetTotals.size();
    }

    /** 全部美术品的面值之和 —— 一个人名下的美术品面值合计不可能超过它。 */
    public int fineArtFaceValueTotal() {
        return fineArtFaceValues.stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * 持有 {@code count} 张珠宝时的套组总分。
     *
     * @throws IllegalArgumentException 张数为负或超过珠宝的全局张数 —— 那说明终局状态本身就是错的
     */
    public int jewelrySetTotal(int count) {
        if (count < 0 || count > jewelryCards()) {
            throw new IllegalArgumentException("珠宝全局只有 %d 张，实际: %d".formatted(jewelryCards(), count));
        }
        return count == 0 ? 0 : jewelrySetTotals.get(count - 1);
    }

    private static List<Integer> nonNegative(String what, List<Integer> values) {
        List<Integer> copy = List.copyOf(Objects.requireNonNull(values, what));
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(what + "不能是空表");
        }
        for (int value : copy) {
            if (value < 0) {
                throw new IllegalArgumentException(what + "不能为负: " + copy);
            }
        }
        return copy;
    }
}
