package io.github.heavyseasmc.mod.card;

import java.util.Locale;

/**
 * 行动 · 表态 · 站队三面上「选一件事」的那几张牌（ADR-0050，用户 2026-10-01 看样图定的 B 形态）。
 *
 * <p>它们不是物资：不在任何牌堆里，没有张数，不进引擎 —— 只是把原来的五枚 / 两枚 / 三枚按钮画成牌。
 * 所以 id 在这里而不在 {@code data/}；贴图的母版 {@code art/cards/action.<id>.svg} 与这里一一对应
 * （{@code ActionCardTest} 两头都查）。
 *
 * <p>牌名、说明、类别题头都在 lang 的 {@code heavyseas.actioncard.*} 一族里 —— 原先按钮上的
 * {@code heavyseas.action.row} · {@code heavyseas.consent.agree} · {@code heavyseas.stance.join_attack} 等
 * 已挪过来，不留两份。
 */
public enum ActionCard {
    ROW(Group.ACTION), SWAP(Group.ACTION), STEAL(Group.ACTION), USE(Group.ACTION), PASS(Group.ACTION),
    AGREE(Group.REPLY), FIGHT(Group.REPLY),
    ATTACK(Group.SIDE), DEFEND(Group.SIDE), WATCH(Group.SIDE);

    /** 哪一面上的牌：说明板题头的类别字（行动 / 表态 / 站队）。 */
    public enum Group {
        ACTION, REPLY, SIDE;

        public String captionKey() {
            return "heavyseas.actioncard.caption." + name().toLowerCase(Locale.ROOT);
        }
    }

    private final Group group;

    ActionCard(Group group) {
        this.group = group;
    }

    /** 贴图与 lang 共用的 id：常量名小写。 */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Group group() {
        return group;
    }

    public String titleKey() {
        return "heavyseas.actioncard." + id();
    }

    /** 按 U 看详情时说明板上的那一句（它是什么、超时会怎样）。 */
    public String effectKey() {
        return titleKey() + ".effect";
    }
}
