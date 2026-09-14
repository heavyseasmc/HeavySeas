package io.github.heavyseasmc.engine.scoring;

import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.model.TreasureKind;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 终局计分。
 *
 * <h2>模型</h2>
 * 四项<b>相互独立</b>地累加。这不是简化，是规则本身的形状：死者只要尸体还在艇上，
 * 财宝照算；而所恨者死亡、所爱者存活的分数，与自己的生死无关。
 *
 * <h2>只有一个真特例</h2>
 * 规则里有三种特殊身份，但其中两种<b>不需要任何特例代码</b>，它们从四项独立里涌现：
 *
 * <ul>
 *   <li><b>自恋者</b>（爱=自己）：第一项付自己的生存分，第三项"所爱者存活"也付自己的
 *       生存分 —— 自然 ×2，不用写。</li>
 *   <li><b>矛盾者</b>（爱=恨=同一人）：目标活着则第三项付其生存分、第四项因未死亡付 0；
 *       目标死了则反过来。"二者择一"是<b>涌现的结果，不是一条要实现的规则</b>。</li>
 *   <li><b>自恋兼厌世</b>：厌世把第一项清零，第三项照付 —— 净结果是生存分只算一次。
 *       同样不用写特例。</li>
 * </ul>
 *
 * 真正的特例只有一个：<b>厌世者的第四项</b>改为遍历艇上的死者。
 *
 * <h2>两个谓词不共用</h2>
 * 憎恨卡看 {@link FinalState#died()}——一个<b>事件</b>：他死了就给分，不问死在哪、
 * 尸体还在不在；厌世者看 {@link FinalState#corpseOnBoat()}——一个<b>状态</b>：只清点艇上的尸体。
 *
 * <p>共用一个谓词的话，两种口径<b>只在有人落水死亡时才分叉</b> —— 那种低频分叉能一路
 * 活到发布之后。
 */
public final class Scorer {

    private Scorer() {
    }

    public static Map<CharacterId, ScoreSheet> scoreAll(Roster roster, Map<CharacterId, FinalState> states) {
        Map<CharacterId, ScoreSheet> out = new LinkedHashMap<>();
        for (Survivor s : roster.survivors()) {
            out.put(s.id(), score(roster, states, s.id()));
        }
        return out;
    }

    public static ScoreSheet score(Roster roster, Map<CharacterId, FinalState> states, CharacterId who) {
        Survivor self = roster.get(who);
        FinalState st = require(states, who);

        boolean misanthrope = who.equals(st.hate());

        // ① 自己存活 → 自己的生存分。厌世者自身生死不计分，此项清零。
        int selfSurvival = (st.alive() && !misanthrope) ? self.survival() : 0;

        // ② 自己在艇上（不论生死）→ 财宝分。落水死亡被移出者，财宝随之退出游戏。
        int treasure = st.onBoat() ? treasureScore(self, st.treasures()) : 0;

        // ③ 所爱者存活 → 其生存分。爱=自己时此项照付，自恋者的 ×2 由此涌现。
        int loved = require(states, st.love()).alive() ? roster.get(st.love()).survival() : 0;

        // ④ 所恨者死亡 → 其体型分。唯一的特例在这里。
        int hated = misanthrope
                ? misanthropeSum(roster, states, who, st.love())
                : (require(states, st.hate()).died() ? roster.get(st.hate()).size() : 0);

        return new ScoreSheet(selfSurvival, treasure, loved, hated);
    }

    /**
     * 厌世者：艇上<b>其他</b>每一个死者各给其体型分。
     *
     * <p>两处排除：排除自己（自身生死不计分），
     * <b>以及排除所爱之人</b>——所爱之人的死亡不给厌世者加分。
     *
     * <p>这里用 {@link FinalState#corpseOnBoat()} 而非 {@code died()}：落水死亡者已被移出
     * 游戏，不在艇上，不计入。
     */
    private static int misanthropeSum(Roster roster, Map<CharacterId, FinalState> states,
                                      CharacterId self, CharacterId loved) {
        int sum = 0;
        for (Survivor other : roster.survivors()) {
            CharacterId id = other.id();
            if (id.equals(self) || id.equals(loved)) {
                continue;
            }
            if (require(states, id).corpseOnBoat()) {
                sum += other.size();
            }
        }
        return sum;
    }

    private static int treasureScore(Survivor self, Treasures t) {
        int cash = t.cash() * multiplier(self, TreasureKind.CASH);
        int fineArt = t.fineArtFaceValue() * multiplier(self, TreasureKind.FINE_ART);

        // 珠宝是套组计分，且加倍作用于<b>套组总分</b>而非单张面值：
        // 先查表再翻倍，3 张 = 8 × 2 = 16。
        int jewelry = jewelrySetTotal(t.jewelry()) * multiplier(self, TreasureKind.JEWELRY);

        return cash + fineArt + jewelry;
    }

    private static int jewelrySetTotal(int count) {
        return switch (count) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 4;
            case 3 -> 8;
            default -> throw new IllegalArgumentException("珠宝全局只有 3 张，实际: " + count);
        };
    }

    private static int multiplier(Survivor self, TreasureKind kind) {
        return self.ability() instanceof Ability.ScoreMultiplier m && m.target() == kind
                ? m.factor()
                : 1;
    }

    private static FinalState require(Map<CharacterId, FinalState> states, CharacterId id) {
        FinalState st = states.get(id);
        if (st == null) {
            throw new IllegalArgumentException("缺少终局状态: " + id);
        }
        return st;
    }
}
