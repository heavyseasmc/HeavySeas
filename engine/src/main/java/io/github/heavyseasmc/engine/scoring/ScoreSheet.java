package io.github.heavyseasmc.engine.scoring;

/**
 * 一个角色的计分明细。四项<b>相互独立</b>地累加，没有互斥、没有前置条件。
 *
 * <p>保留分项而不是只给总分，有两个实在的用途：终局计分面板要逐项展示，
 * 单测失败时能一眼看出错在哪一项。
 *
 * @param selfSurvival 自己存活 → 自己的生存分（厌世者此项恒为 0）
 * @param treasure     自己在艇上（不论生死）→ 财宝分
 * @param loved        所爱者存活 → 其<b>生存分</b>
 * @param hated        所恨者死亡 → 其<b>体型分</b>（厌世者改为遍历艇上死者）
 */
public record ScoreSheet(int selfSurvival, int treasure, int loved, int hated) {

    public int total() {
        return selfSurvival + treasure + loved + hated;
    }

    @Override
    public String toString() {
        return "存活 %d + 财宝 %d + 所爱 %d + 所恨 %d = %d"
                .formatted(selfSurvival, treasure, loved, hated, total());
    }
}
