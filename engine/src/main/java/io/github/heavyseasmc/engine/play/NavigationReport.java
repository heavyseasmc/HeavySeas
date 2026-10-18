package io.github.heavyseasmc.engine.play;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.navigation.NavigationCard;

import java.util.List;
import java.util.Objects;

/**
 * 航海阶段结算了什么。
 *
 * <h2>为什么要有它</h2>
 * 两个调用方都需要「这一步点到了谁」，而且需要的是同一份：
 * 模拟器拿它累计曝光率（O1 的分布曲线），模组拿它写给玩家看。
 * 若各自在结算过程中埋钩子，两边看到的就可能不是同一件事。
 *
 * <h2>分母与点名必须同一时刻取</h2>
 * {@code overboardCandidates} 是**这一步真正执行时还活着的人**。分母取早了或取晚了，
 * 「落水少」与「早就死了」会算成同一件事。
 *
 * <p>海鸥凑够 4 只时一局当场结束，该牌的落海与口渴<b>一律不再结算</b> ——
 * 那种情况下两份名单都是空的，{@link #endedOnGulls} 为真。
 *
 * @param card                执行的是哪张牌
 * @param endedOnGulls        海鸥当场结束了这一局
 * @param overboardCandidates 落海的分母（还活着的人）
 * @param overboardSelected   牌面点到、且当时还活着的人
 * @param thirstCandidates    口渴的分母（会口渴的人，含昏迷者，不含死者）
 * @param thirstSelected      牌面点名口渴的人（不含划船与战斗带来的口渴）
 * @param removed             这一步被移出游戏的人：死在水里，连人带牌离场（ADR-0022）
 */
public record NavigationReport(NavigationCard card, boolean endedOnGulls,
                               List<CharacterId> overboardCandidates,
                               List<CharacterId> overboardSelected,
                               List<CharacterId> thirstCandidates,
                               List<CharacterId> thirstSelected,
                               List<CharacterId> removed) {

    public NavigationReport {
        Objects.requireNonNull(card, "card");
        overboardCandidates = List.copyOf(overboardCandidates);
        overboardSelected = List.copyOf(overboardSelected);
        thirstCandidates = List.copyOf(thirstCandidates);
        thirstSelected = List.copyOf(thirstSelected);
        removed = List.copyOf(removed);
    }
}
