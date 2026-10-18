package io.github.heavyseasmc.engine.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * 爱恨牌：每个角色各抽一张喜爱牌、一张憎恨牌。
 *
 * <h2>两个置换，不是独立随机（设计决策 §3.1，硬性正确性约束）</h2>
 * 喜爱牌与憎恨牌各自的张数恰好等于角色数，每人各抽一张 —— 于是<b>每个角色恰好被一个人爱、恰好被一个人恨</b>。
 * 写成「每人独立随机选一个目标」会出现两个人爱同一个人、而某人没人爱：规则上不可能发生，
 * 还会毁掉一条演绎线索（「我知道 A 爱 B，那就没有别人爱 B」）——
 * 终局「每轮最后一张不翻、让全船自己推出来」靠的正是这一条。
 *
 * <p>所以构造时核对两张表<b>都是双射</b>：键集合 = 值集合 = 同一批人。
 *
 * <h2>整局不变，所以不进 SurvivorState</h2>
 * 那一半每回合都在复制；爱恨开局发一次、终局才揭，放进去只会多一个构造参数，外加一个「改爱恨」的口子。
 *
 * @param love 谁爱谁
 * @param hate 谁恨谁
 */
public record Affinities(Map<CharacterId, CharacterId> love, Map<CharacterId, CharacterId> hate) {

    public Affinities {
        love = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(love, "love")));
        hate = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(hate, "hate")));
        requirePermutation("喜爱牌", love);
        requirePermutation("憎恨牌", hate);
        if (!love.keySet().equals(hate.keySet())) {
            throw new IllegalArgumentException(
                    "喜爱牌与憎恨牌发给的不是同一批人：%s vs %s".formatted(love.keySet(), hate.keySet()));
        }
    }

    /**
     * 发牌：同一份阵容洗两次，得到两个<b>各自独立</b>的置换。
     *
     * <p>❗两次洗牌用同一条随机流、先爱后恨 —— 次序是可复现性的一部分，模拟器按种子重放要靠它。
     */
    public static Affinities random(Roster roster, Random rng) {
        Objects.requireNonNull(roster, "roster");
        Objects.requireNonNull(rng, "rng");
        List<CharacterId> ids = new ArrayList<>();
        for (Survivor s : roster.survivors()) {
            ids.add(s.id());
        }
        List<CharacterId> loveTargets = new ArrayList<>(ids);
        Collections.shuffle(loveTargets, rng);
        List<CharacterId> hateTargets = new ArrayList<>(ids);
        Collections.shuffle(hateTargets, rng);
        Map<CharacterId, CharacterId> love = new LinkedHashMap<>();
        Map<CharacterId, CharacterId> hate = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            love.put(ids.get(i), loveTargets.get(i));
            hate.put(ids.get(i), hateTargets.get(i));
        }
        return new Affinities(love, hate);
    }

    /** 他爱的人。 */
    public CharacterId loveOf(CharacterId who) {
        return require(love, who, "喜爱牌");
    }

    /** 他恨的人。 */
    public CharacterId hateOf(CharacterId who) {
        return require(hate, who, "憎恨牌");
    }

    /**
     * 核对这副爱恨牌发给的正是这一局的阵容。
     *
     * @throws IllegalArgumentException 多了或少了人 —— 6 人局拿着 8 人的爱恨牌，终局会有人指着一个不在场的角色
     */
    public void requireCovers(Roster roster) {
        Set<CharacterId> ids = new LinkedHashSet<>();
        for (Survivor s : roster.survivors()) {
            ids.add(s.id());
        }
        if (!ids.equals(love.keySet())) {
            throw new IllegalArgumentException(
                    "爱恨牌发给的是 %s，这一局的阵容是 %s".formatted(love.keySet(), ids));
        }
    }

    private static void requirePermutation(String what, Map<CharacterId, CharacterId> m) {
        if (m.isEmpty()) {
            throw new IllegalArgumentException(what + "一张都没有");
        }
        Set<CharacterId> targets = new HashSet<>(m.values());
        if (targets.size() != m.size()) {
            throw new IllegalArgumentException(
                    "%s不是置换：有人被指了不止一次（%s）—— 规则上每人恰好被一个人指".formatted(what, m));
        }
        if (!targets.equals(m.keySet())) {
            throw new IllegalArgumentException(
                    "%s不是置换：指向了不在这批人里的角色（%s）".formatted(what, m));
        }
    }

    private static CharacterId require(Map<CharacterId, CharacterId> m, CharacterId who, String what) {
        CharacterId target = m.get(who);
        if (target == null) {
            throw new IllegalArgumentException("%s里没有 %s".formatted(what, who));
        }
        return target;
    }
}
