package io.github.heavyseasmc.engine.model;

import java.util.Objects;

/**
 * 一名幸存者的静态定义（来自 roster 数据，整局不变）。
 *
 * <p>命名为 Survivor 而非 Character，是为了避开 {@link java.lang.Character}——
 * 那个冲突会逼着每处都写全限定名。
 *
 * <p><b>体型的四个用途</b>（这是本作最核心的设计，任何改动都要想清楚四处）：
 * <ol>
 *   <li>战斗力（受伤<b>不</b>降低，永远按满值算）</li>
 *   <li>血量上限：伤害 = 体型 → 昏迷；伤害 &gt; 体型 → 死亡</li>
 *   <li>别人恨你、而你死了时，他拿到的分数</li>
 *   <li>航海牌上的落水频率与体型正相关</li>
 * </ol>
 *
 * <p><b>生存分只有两个用途</b>：自己存活得分、被爱者存活时爱他的人得分。
 *
 * <p>于是「壮的人难杀但不值钱」：大副 8/4 几乎杀不死却只值 4 分，
 * 小孩 3/9 一碰就死却值 9 分。记法：<b>爱看生存分，恨看体型分。</b>
 */
public record Survivor(
        CharacterId id,
        int seat,
        int size,
        int survival,
        String expansion,
        Ability ability
) {
    public Survivor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ability, "ability");
        if (seat < 1) {
            throw new IllegalArgumentException("座位号从 1 起（船头），实际: " + seat);
        }
        if (size < 1) {
            throw new IllegalArgumentException("体型必须为正，实际: " + size);
        }
        if (survival < 0) {
            throw new IllegalArgumentException("生存分不能为负，实际: " + survival);
        }
    }
}
