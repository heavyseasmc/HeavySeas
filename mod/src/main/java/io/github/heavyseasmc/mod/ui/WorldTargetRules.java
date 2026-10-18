package io.github.heavyseasmc.mod.ui;

/**
 * 世界里换人 / 换动作的滚轮怎么走。挑「指着谁」只认准星直接压在身上的那一个（{@code WorldTargets#underCrosshair}），
 * 不再有「大概方向」那一档（用户 2026-10-13：原先 12° 以内都算指着，坐成一排时随便一看就弹菜单）。
 */
public final class WorldTargetRules {
    private WorldTargetRules() { }

    /**
     * 按住换人键滚一格：只在<b>画面里看得见的人</b>之间换，按画面上从左到右的次序（往上滚往左、往下滚往右，两头接上）。
     *
     * <p>原先按座位次序换，下一个人常常坐在镜头外 —— 描边跟过去了却看不见，画面上看不出现在选的是谁（2026-10-13 验收 16/17）。
     *
     * @param bearing  每个人相对视线的水平夹角（度，右为正）
     * @param current  现在选的是第几个（在 {@code bearing} 里的下标；没有或不在画面里都行）
     * @param halfFov  画面水平视角的一半（度）
     * @return 换到第几个；画面里一个人都没有时原样返回 {@code current}
     */
    public static int cycleOnScreen(double[] bearing, int current, double halfFov, double wheel) {
        java.util.List<Integer> seen = new java.util.ArrayList<>();
        for (int i = 0; i < bearing.length; i++) {
            if (Math.abs(bearing[i]) <= halfFov) {
                seen.add(i);
            }
        }
        if (seen.isEmpty()) {
            return current;
        }
        seen.sort(java.util.Comparator.comparingDouble(i -> bearing[i]));
        int at = seen.indexOf(current);
        if (at < 0) {
            // 现在选的那个不在画面里：先落到离准星最近的那一个
            int best = seen.get(0);
            for (int i : seen) {
                if (Math.abs(bearing[i]) < Math.abs(bearing[best])) {
                    best = i;
                }
            }
            return best;
        }
        return seen.get(cycle(at, seen.size(), wheel));
    }

    /** 滚一格：往上滚（正数）是上一个，往下是下一个，两头接上。一个都没有是 -1。 */
    public static int cycle(int current, int count, double wheel) {
        return count <= 0 ? -1 : Math.floorMod(current + (wheel > 0 ? -1 : 1), count);
    }
}
