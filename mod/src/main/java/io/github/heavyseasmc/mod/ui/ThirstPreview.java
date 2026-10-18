package io.github.heavyseasmc.mod.ui;

import io.github.heavyseasmc.engine.state.Condition;

/** 喝水选择的即时后果。生死沿用引擎的船上判据，显示的体力不低于零。 */
public record ThirstPreview(int damage, int health, Condition condition) {

    /** 旁人给的水与自己选的水合并，完整的一组才抵掉一次口渴。 */
    public static ThirstPreview of(int currentHealth, int maxHealth, int remaining,
                                   int waterPerSource, int donated, int chosen) {
        int damage = Math.max(0, remaining - (donated + chosen) / waterPerSource);
        int health = currentHealth - damage;
        Condition condition = Condition.onBoat(maxHealth - health, maxHealth);
        return new ThirstPreview(damage, Math.max(0, health), condition);
    }
}
