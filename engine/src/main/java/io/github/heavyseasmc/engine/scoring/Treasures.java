package io.github.heavyseasmc.engine.scoring;

/**
 * 终局时某个角色名下的财宝。
 *
 * <p>手牌与亮在面前的牌<b>都算</b>——终局时全部亮出，所以这里不区分两者。
 *
 * <p>这里只记「手里有多少」，不记「值多少分」：分值、珠宝套组表、珠宝的全局张数都在
 * {@link TreasureScoring} 里，数据来自 {@code data/roster}。所以「珠宝超过全局张数」
 * 这类检查也在那边做，本类不写死任何一个数。
 *
 * @param cash             现金张数
 * @param jewelry          珠宝张数
 * @param fineArtFaceValue 美术品的面值合计。美术品是几张面值不同的牌，而不是一张牌带数量，所以这里存合计
 */
public record Treasures(int cash, int jewelry, int fineArtFaceValue) {

    public static final Treasures NONE = new Treasures(0, 0, 0);

    public Treasures {
        if (cash < 0 || jewelry < 0 || fineArtFaceValue < 0) {
            throw new IllegalArgumentException("财宝数量不能为负");
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
