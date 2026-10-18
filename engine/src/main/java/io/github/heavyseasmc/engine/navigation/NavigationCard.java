package io.github.heavyseasmc.engine.navigation;

import java.util.Objects;

/**
 * 一张航海牌。
 *
 * <p>结算顺序<b>固定为 海鸥 → 落海 → 口渴，不可颠倒</b>：先把所有人的落水全部结算完，
 * 再统一结算口渴。这不是风格问题 —— 撑开的阳伞可能在落水那一步先被冲走，
 * 而落水者只要没死就已经回到艇上、赶得上本回合的口渴。顺序换了这两件事都会错。
 *
 * <p>❗<b>第 4 只海鸥出现时，该牌的落海与口渴一律不再结算</b>，直接计分。
 * 所以「结算完整张牌」这个说法本身就是错的，结算必须能在海鸥那一步中断。
 *
 * @param id              牌的 id
 * @param gull            海鸥增减：+1 添一只、-1 移除一只、0 无海鸥
 * @param overboard       落海点名。候选集<b>含尸体</b> —— 死者落海会彻底退出游戏
 * @param thirst          口渴点名。候选集<b>不含死者</b>，但<b>含昏迷者</b>（他仍会口渴）
 * @param thirstRowers    本牌是否让划船者口渴（船桨图示）
 * @param thirstFighters  本牌是否让参战者口渴（战斗图示）
 */
public record NavigationCard(
        String id,
        int gull,
        Selector overboard,
        Selector thirst,
        boolean thirstRowers,
        boolean thirstFighters
) {

    public NavigationCard {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(overboard, "overboard");
        Objects.requireNonNull(thirst, "thirst");
        if (id.isBlank()) {
            throw new IllegalArgumentException("航海牌 id 不能为空");
        }
        // 实测数据里 gull 只有 -1 / 0 / +1 三种。超出这个范围多半是解析错了，
        // 而「一张牌加两只海鸥」会直接改变整局长度，属于必须早炸的那类错误。
        if (gull < -1 || gull > 1) {
            throw new IllegalArgumentException("海鸥增减只能是 -1 / 0 / +1，实际: " + gull);
        }
    }
}
