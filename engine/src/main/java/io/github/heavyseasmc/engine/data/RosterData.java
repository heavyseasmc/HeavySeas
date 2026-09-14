package io.github.heavyseasmc.engine.data;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code data/roster} 读出来的东西：全部角色，加上按人数分的预设阵容。
 *
 * <p>它<b>不是</b> {@link Roster}。{@code Roster} 是「这一局上场的这几个人」，
 * 而本记录是「这份数据里有哪些人、几人局各上谁」。一局只用得到其中一套预设，
 * 合成一个类型会让「全部角色」被当成阵容传进游戏 —— 那是 8 人局与 6 人局的差别。
 */
public record RosterData(List<Survivor> characters, Map<Integer, List<CharacterId>> presets) {

    public RosterData {
        characters = List.copyOf(Objects.requireNonNull(characters, "characters"));
        if (characters.isEmpty()) {
            throw new IllegalArgumentException("角色表不能为空");
        }
        Map<CharacterId, Survivor> byId = new LinkedHashMap<>();
        for (Survivor survivor : characters) {
            if (byId.put(survivor.id(), survivor) != null) {
                throw new IllegalArgumentException("角色 id 重复: " + survivor.id());
            }
        }

        Objects.requireNonNull(presets, "presets");
        Map<Integer, List<CharacterId>> checked = new LinkedHashMap<>();
        presets.forEach((players, ids) -> {
            List<CharacterId> copy = List.copyOf(ids);
            if (copy.size() != players) {
                // 键就是人数。对不上说明数据被手改过，而「6 人局其实有 7 个人」
                // 在对局里的表现是座位次序莫名其妙，不会有任何一行报错。
                throw new IllegalArgumentException(
                        "%d 人预设里有 %d 个角色".formatted(players, copy.size()));
            }
            Set<CharacterId> seen = new LinkedHashSet<>();
            int previousSeat = 0;
            for (CharacterId id : copy) {
                Survivor survivor = byId.get(id);
                if (survivor == null) {
                    throw new IllegalArgumentException(
                            "%d 人预设点了角色表里没有的 %s".formatted(players, id));
                }
                if (!seen.add(id)) {
                    throw new IllegalArgumentException("%d 人预设里 %s 出现了两次".formatted(players, id));
                }
                // 预设必须按 seat 升序 —— 从 8 人里摘掉两个人恰好得到 6 人局，
                // 这个嵌套是三套阵容平衡的基础（见 data/roster/default.json 的注释）。
                if (survivor.seat() <= previousSeat) {
                    throw new IllegalArgumentException(
                            "%d 人预设没有按座位升序：%s 的座位是 %d，排在座位 %d 之后"
                                    .formatted(players, id, survivor.seat(), previousSeat));
                }
                previousSeat = survivor.seat();
            }
            checked.put(players, copy);
        });
        presets = Map.copyOf(checked);
    }

    /** 全部角色的 id。航海牌加载器拿它做点名白名单。 */
    public Set<CharacterId> ids() {
        Set<CharacterId> out = new LinkedHashSet<>();
        characters.forEach(survivor -> out.add(survivor.id()));
        return Set.copyOf(out);
    }

    public Survivor get(CharacterId id) {
        for (Survivor survivor : characters) {
            if (survivor.id().equals(id)) {
                return survivor;
            }
        }
        throw new IllegalArgumentException("角色表里没有这个角色: " + id);
    }

    /**
     * 取一套预设阵容。
     *
     * @throws IllegalArgumentException 没有这个人数的预设。<b>不就近凑</b> ——
     *                                  「5 人局给你 6 人阵容」会让平衡悄悄跑偏
     */
    public Roster preset(int players) {
        List<CharacterId> ids = presets.get(players);
        if (ids == null) {
            throw new IllegalArgumentException(
                    "没有 %d 人的预设阵容，只有 %s".formatted(players, presets.keySet().stream().sorted().toList()));
        }
        List<Survivor> chosen = new ArrayList<>(ids.size());
        ids.forEach(id -> chosen.add(get(id)));
        return new Roster(List.copyOf(chosen));
    }
}
