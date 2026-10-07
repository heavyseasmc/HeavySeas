package io.github.heavyseasmc.engine.seat;

/**
 * 轮到我结算口渴：自己化解几次、要不要别人递水。
 *
 * <p>按<b>次数</b>算，不按张数：酷热那天每次要 2 张水（{@code waterPerSource}），由驱动者换算。
 * 按张数交的话，「交了 3 张、每次要 2 张」这种不合法的数就写得出来。
 *
 * @param units   自己化解几次（每次用掉 {@code waterPerSource} 张自己的水）
 * @param askHelp 还差的那几次，开口要别人递水。别人递不递是他们自己的决定
 */
public record WaterPlan(int units, boolean askHelp) {
    public WaterPlan {
        if (units < 0) {
            throw new IllegalArgumentException("化解次数不能为负: " + units);
        }
    }
}
