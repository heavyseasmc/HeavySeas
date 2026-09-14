package io.github.heavyseasmc.engine.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一局的参战阵容（船头 → 船尾）。
 *
 * <p>构造时校验两件事，它们错了会在很后面才炸得莫名其妙：
 * id 唯一、座位唯一。<b>体型不校验唯一</b>——8 人阵容里体型 3 与 4 本来就各有两个。
 */
public record Roster(List<Survivor> survivors) {

    public Roster {
        survivors = List.copyOf(survivors);
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
        }
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
