package io.github.heavyseasmc.engine.scoring;

/**
 * 终局时某个角色名下的财宝。
 *
 * <p>手牌与亮在面前的牌<b>都算</b>——终局时全部亮出，所以这里不区分两者。
 *
 * @param cash             现金张数，每张 1 分
 * @param jewelry          珠宝张数，套组计分 1/4/8（全局只有 3 张）
 * @param fineArtFaceValue 美术品的面值合计。三张美术品面值分别是 2 / 3 / 3，
 *                         是三张不同的牌而不是一张牌带数量，所以这里存合计而非张数。
 */
public record Treasures(int cash, int jewelry, int fineArtFaceValue) {

    public static final Treasures NONE = new Treasures(0, 0, 0);

    public Treasures {
        if (cash < 0 || jewelry < 0 || fineArtFaceValue < 0) {
            throw new IllegalArgumentException("财宝数量不能为负");
        }
        if (jewelry > 3) {
            throw new IllegalArgumentException("珠宝全局只有 3 张，实际: " + jewelry);
        }
    }

    public static Treasures cash(int n) {
        return new Treasures(n, 0, 0);
    }

    public static Treasures jewelry(int n) {
        return new Treasures(0, n, 0);
    }

    public static Treasures fineArt(int faceValue) {
        return new Treasures(0, 0, faceValue);
    }
}
