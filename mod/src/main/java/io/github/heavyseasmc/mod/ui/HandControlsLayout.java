package io.github.heavyseasmc.mod.ui;

/**
 * 手牌一面说明签下那两枚按钮（打出 · 亮出 / 赠送）各占多宽、第二枚从哪儿起。
 *
 * <p>❗第一格<b>永远占位</b>（审查 2026-10-07 Z2 ①）：打不出的牌不画「打出」，但「亮出」不挪进第一格 ——
 * 不然鼠标点「原来打出的位置」就成了亮出，而亮出不可逆。所以 {@code first} 由调用方按「打出」的宽度给，画不画是另一回事。
 *
 * <p>放不下时不折行、不变高（2026-10-13 验收：折成两行的那一版换一张牌整排就上下跳）。第一枚（「Enter 打出」，短）
 * 先拿够它想要的宽，至多占一半；剩下的归第二枚，字由画字那一侧缩。原先两枚<b>等比</b>收窄：第二枚想要的宽按
 * 「再按一次亮在面前」那一句的长度算，英文下它比第一枚长三倍，第一枚被压到只剩一个 Enter 键帽、「Play」一个字母都画不下
 * （验收 L07）。整排不越过 {@code available}（说明签右边与私密目标区左边之间）。
 */
public record HandControlsLayout(int firstWidth, int secondX, int secondWidth) {

    /**
     * @param available 整排最宽多少（物理像素）
     * @param first     第一枚想要多宽（不画时照样占这么宽）
     * @param second    第二枚想要多宽；{@code 0} = 没有第二枚（赠给指定的人那一版）
     * @param gap       两枚之间
     */
    public static HandControlsLayout fit(int available, int first, int second, int gap) {
        if (available < 1 || first < 0 || second < 0 || gap < 0) {
            throw new IllegalArgumentException("Invalid control widths");
        }
        if (second == 0) {
            return new HandControlsLayout(Math.min(first, available), 0, 0);
        }
        if (first + gap + second <= available) {
            return new HandControlsLayout(first, first + gap, second);
        }
        // 放不下：缝先保住（至多占到只剩两个像素给按钮）；第一枚拿它想要的宽、至多一半，其余归第二枚
        int between = Math.min(gap, Math.max(0, available - 2));
        int room = Math.max(2, available - between);
        int firstWidth = Math.max(1, Math.min(room - 1, Math.min(first, room / 2)));
        int secondWidth = Math.max(1, room - firstWidth);
        return new HandControlsLayout(firstWidth, firstWidth + between, secondWidth);
    }
}
