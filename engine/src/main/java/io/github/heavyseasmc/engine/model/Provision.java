package io.github.heavyseasmc.engine.model;

import java.util.Objects;

/**
 * 一种物资牌：id、类别、张数、效果。
 *
 * <p>❗<b>id 是主键</b>，与美术资产（{@code art/cards/provision.<id>.svg}）和 lang 键共用同一套。
 * 显示名不在这里 —— 它在 lang 文件里，由卡面 {@code <title>} 提取（O17）。
 * 引擎不认得任何显示名，所以它也写不错。
 *
 * @param id       主键
 * @param category 类别。只用于分类展示与「这是不是财宝」这类粗判断，<b>规则一律看 {@link #effect}</b>
 * @param count    整副牌里有几张
 * @param effect   效果
 */
public record Provision(String id, Category category, int count, ProvisionEffect effect) {

    public Provision {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(effect, "effect");
        if (id.isBlank()) {
            throw new IllegalArgumentException("物资 id 不能为空");
        }
        if (count < 1) {
            throw new IllegalArgumentException("张数必须为正，实际: " + count);
        }
    }

    /** 物资类别。 */
    public enum Category {
        CONSUMABLE,
        EQUIPMENT,
        WEAPON,
        TREASURE;

        /** 从数据里的写法（小写下划线）解析。 */
        public static Category parse(String raw) {
            for (Category c : values()) {
                if (c.name().toLowerCase(java.util.Locale.ROOT).equals(raw)) {
                    return c;
                }
            }
            throw new IllegalArgumentException("不认识的物资类别: " + raw);
        }
    }

    /** 战斗加值。不是武器时为 0。 */
    public int weaponPower() {
        return effect.weaponPower();
    }

    /** 亮在面前是不是一直生效。手牌里的救生圈挡不了落水，判据就是这一条（ADR-0021 决策 4）。 */
    public boolean isPassiveInFront() {
        return effect.timings().contains(ProvisionEffect.Timing.PASSIVE);
    }

    /** 能不能当特殊行动打出（占掉行动阶段那一个行动）。 */
    public boolean isSpecialAction() {
        return effect.timings().contains(ProvisionEffect.Timing.SPECIAL_ACTION);
    }

    /** 是不是财宝（终局计分用）。 */
    public boolean isTreasure() {
        return effect instanceof ProvisionEffect.ScoreFlat || effect instanceof ProvisionEffect.ScoreSet;
    }
}
