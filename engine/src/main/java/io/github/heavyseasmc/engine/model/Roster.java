package io.github.heavyseasmc.engine.model;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 一局的参战阵容（船头 → 船尾）。
 *
 * <p>构造时校验两件事，它们错了会在很后面才炸得莫名其妙：
 * id 唯一、座位唯一。<b>体型不校验唯一</b>——8 人阵容里体型 3 与 4 本来就各有两个。
 *
 * <h2>为什么还带着一张「谁加倍哪一类财宝」</h2>
 * 现金与美术品在物资数据里同为 {@code score_flat}，分得开它们的只有「谁让它翻倍」（现金 → 船长，美术品 → 收藏家），
 * 而那写在<b>角色</b>那一份数据里。原先计分时去这一局的阵容里找那个人 —— 房主自选阵容把船长或收藏家剔掉之后，
 * 牌堆里照样有现金和美术品，终局一计分就抛（审查 2026-10-08 C2，无船长 200 局里 151 局抛）。
 * 所以这张表取自<b>整张角色表</b>（{@code RosterData} 在拼阵容时给），不随这一局上场的是谁而变。
 *
 * @param survivors 上场的人，船头 → 船尾
 * @param doublers  整张角色表里每个加倍者（角色 id）加倍的是哪一类财宝 —— 不在场的也在里面
 */
public record Roster(List<Survivor> survivors, Map<CharacterId, TreasureKind> doublers) {

    /**
     * 只给上场的人：「谁加倍哪一类」就从这几个人身上取。合成阵容与测试夹具用 ——
     * 那时阵容里没有船长就分不出现金，计分照旧当场抛（不猜）。真实对局的阵容一律从 {@code RosterData} 拼。
     */
    public Roster(List<Survivor> survivors) {
        this(survivors, doublersOf(survivors));
    }

    public Roster {
        survivors = List.copyOf(survivors);
        // 保序：toString 与报错里的次序跟着角色表走，不随每次起 JVM 的哈希盐变
        doublers = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(doublers, "doublers")));
        Map<CharacterId, Survivor> byId = new LinkedHashMap<>();
        Map<Integer, CharacterId> bySeat = new LinkedHashMap<>();
        for (Survivor s : survivors) {
            if (byId.put(s.id(), s) != null) {
                throw new IllegalArgumentException("角色 id 重复: " + s.id());
            }
            CharacterId prev = bySeat.put(s.seat(), s.id());
            if (prev != null) {
                throw new IllegalArgumentException(
                        "座位 " + s.seat() + " 被 " + prev + " 与 " + s.id() + " 同时占用");
            }
            // 在场的加倍者与表里说的必须是同一件事：两边不一致时，计分按表、加倍按人，两处各算各的
            if (s.ability() instanceof Ability.ScoreMultiplier mult && doublers.get(s.id()) != mult.target()) {
                throw new IllegalArgumentException("%s 加倍 %s，而财宝分类表里写的是 %s"
                        .formatted(s.id(), mult.target(), doublers.get(s.id())));
            }
        }
    }

    /** 从一批角色里取出「谁加倍哪一类财宝」（{@link Ability.ScoreMultiplier}），按给的次序。 */
    public static Map<CharacterId, TreasureKind> doublersOf(Collection<Survivor> characters) {
        Map<CharacterId, TreasureKind> out = new LinkedHashMap<>();
        for (Survivor s : characters) {
            if (s.ability() instanceof Ability.ScoreMultiplier mult) {
                out.put(s.id(), mult.target());
            }
        }
        return out;
    }

    /** 这个角色（不论这一局在不在场）加倍的是哪一类财宝；他没有加倍技能、或者角色表里没有他时为空。 */
    public Optional<TreasureKind> treasureDoubledBy(CharacterId who) {
        return Optional.ofNullable(doublers.get(who));
    }

    public Survivor get(CharacterId id) {
        for (Survivor s : survivors) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        throw new IllegalArgumentException("阵容中没有这个角色: " + id);
    }

    public int size() {
        return survivors.size();
    }
}
