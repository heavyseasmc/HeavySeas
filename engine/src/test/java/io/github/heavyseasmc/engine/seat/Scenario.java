package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.data.LocalData;
import io.github.heavyseasmc.engine.data.TestProvisions;
import io.github.heavyseasmc.engine.model.Affinities;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.NavigationDeck;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.play.Table;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.Phase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 测试夹具：照真实规则一步一步摆出一个局面（真实 8 人阵容、真实航海牌、指定的物资牌堆）。
 *
 * <p>只走 {@link Session} 的公开入口：发牌用 {@code dealFromPile}（牌真的从牌堆里少一张），
 * 挨伤靠真的打一架（{@code applyFight}），然后把这一天走完把打架标记清掉 —— 不直接改伤害数。
 */
final class Scenario {

    static final CharacterId JEWELER = CharacterId.of("jeweler");
    static final CharacterId COLLECTOR = CharacterId.of("collector");
    static final CharacterId CAPTAIN = CharacterId.of("captain");
    static final CharacterId MATE = CharacterId.of("mate");
    static final CharacterId HOSTESS = CharacterId.of("hostess");
    static final CharacterId SAILOR = CharacterId.of("sailor");
    static final CharacterId DOCTOR = CharacterId.of("doctor");
    static final CharacterId KID = CharacterId.of("kid");

    final Session session;
    final Roster roster;

    /**
     * @param pile    物资牌堆的构成（真实效果、指定张数）
     * @param navSeed 航海牌堆洗牌的种子
     */
    Scenario(Map<String, Integer> pile, long navSeed) {
        this(pile, navSeed, LocalData.navigationDeck());
    }

    Scenario(Map<String, Integer> pile, long navSeed, List<NavigationCard> nav) {
        roster = LocalData.roster().preset(8);
        session = new Session("scenario", roster,
                new Table(new NavigationDeck(nav, new Random(navSeed)), TestProvisions.counting(pile),
                        new Random(navSeed)));
    }

    /** 发爱恨：{@code who} 爱 {@code love}、恨 {@code hate}；其余人的随机（按 {@code seed}），两张表都是置换。 */
    Scenario affinities(CharacterId who, CharacterId love, CharacterId hate, long seed) {
        session.dealAffinities(permutations(roster, who, love, hate, new Random(seed)));
        return this;
    }

    static Affinities permutations(Roster roster, CharacterId who, CharacterId love, CharacterId hate, Random rng) {
        return new Affinities(permutation(roster, who, love, rng), permutation(roster, who, hate, rng));
    }

    private static Map<CharacterId, CharacterId> permutation(Roster roster, CharacterId who, CharacterId target,
                                                             Random rng) {
        List<CharacterId> ids = new ArrayList<>();
        for (Survivor s : roster.survivors()) {
            ids.add(s.id());
        }
        List<CharacterId> rest = new ArrayList<>(ids);
        rest.remove(target);
        Collections.shuffle(rest, rng);
        Map<CharacterId, CharacterId> out = new LinkedHashMap<>();
        int k = 0;
        for (CharacterId id : ids) {
            out.put(id, id.equals(who) ? target : rest.get(k++));
        }
        return out;
    }

    /** 从牌堆里抽指定的几张发到他手上。 */
    Scenario deal(CharacterId who, String... cards) {
        for (String c : cards) {
            session.dealFromPile(who, c);
        }
        return this;
    }

    /** 推到行动阶段（不开补给箱）。 */
    Scenario toAction() {
        while (session.state().phase() != Phase.ACTION) {
            session.advancePhase();
        }
        return this;
    }

    /**
     * 让他挨 {@code points} 点伤：真的打几架（大副加船长两个人打他，或者他去打大副加船长），
     * 然后把这一天走完、清掉打架标记，回到下一天的行动阶段。
     */
    Scenario hurt(CharacterId who, int points) {
        toAction();
        for (int i = 0; i < points; i++) {
            List<CharacterId> strong = new ArrayList<>(List.of(MATE, CAPTAIN, SAILOR));
            strong.remove(who);
            Fight f = Fight.between(strong.get(0), who).join(strong.get(1), Fight.Side.ATTACK);
            session.applyFight(f);
        }
        nextDay();
        return this;
    }

    /** 把这一天走完（不翻航海牌、不结算口渴），回到下一天的行动阶段。 */
    Scenario nextDay() {
        session.advancePhase();          // ACTION → NAVIGATION
        session.advancePhase();          // NAVIGATION → 下一天
        return toAction();
    }

    Scenario reveal(CharacterId who, String card) {
        session.reveal(who, card);
        return this;
    }

    SeatView view(CharacterId who) {
        return SeatView.of(session, who);
    }

    /** 一张只点名、不带图示的航海牌。 */
    static NavigationCard card(String id, int gull, List<CharacterId> overboard, List<CharacterId> thirst) {
        return new NavigationCard(id, gull,
                overboard.isEmpty() ? new io.github.heavyseasmc.engine.navigation.Selector.Nobody()
                        : new io.github.heavyseasmc.engine.navigation.Selector.Only(java.util.Set.copyOf(overboard)),
                thirst.isEmpty() ? new io.github.heavyseasmc.engine.navigation.Selector.Nobody()
                        : new io.github.heavyseasmc.engine.navigation.Selector.Only(java.util.Set.copyOf(thirst)),
                false, false);
    }
}
