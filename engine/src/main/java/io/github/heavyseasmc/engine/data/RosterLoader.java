package io.github.heavyseasmc.engine.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.model.TreasureKind;

import java.io.Reader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 读 {@code data/roster/default.json}。
 *
 * <p>角色的技能是 sealed 接口 {@link Ability}，这里是它唯一的入口。
 * 认不出的 {@code kind} 直接抛 —— 退回 {@code None()} 会让「技能没实现」与「这人本来就没技能」
 * 变成同一个结果，而大副恰好真的没技能，于是没有任何办法发现漏了。
 */
public final class RosterLoader {

    /** 本加载器读的 schema 版本。数据文件的 {@code schema_version} 必须等于它。 */
    public static final int SCHEMA_VERSION = 1;

    private static final Set<String> READ_KEYS = Set.of("schema_version", "characters", "presets");

    /**
     * ❗<b>白名单放行、但引擎并不读</b>的顶层字段。列出来是为了让它成为一个<b>决定</b>而不是疏漏。
     *
     * <ul>
     *   <li>{@code id}：数据包标识，M1 的资源加载才用得上。</li>
     *   <li>{@code treasure_scoring}：那张表（珠宝 1/4/8、现金面值、美术品 2/3/3）现在
     *       <b>写死在 {@code scoring} 包里</b>，于是同一份数值有两个真相源。不是本次改动引入的，
     *       但沉默放行会让这种漂移永远没人发现。</li>
     * </ul>
     */
    private static final Set<String> PRESENT_BUT_NOT_CONSUMED = Set.of("id", "treasure_scoring");

    private static final Set<String> TOP_KEYS =
            java.util.stream.Stream.concat(READ_KEYS.stream(), PRESENT_BUT_NOT_CONSUMED.stream())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private static final Set<String> CHARACTER_KEYS =
            Set.of("id", "seat", "size", "survival", "expansion", "ability");

    private RosterLoader() {
    }

    /** 从文件读：测试与工具走这条路。文件由本方法打开并关闭。 */
    public static RosterData load(Path file) {
        return JsonSupport.fromFile(file, reader -> load(file.toString(), reader));
    }

    /**
     * 从字符流读：数据包里的资源没有文件路径，只有一个标识和一条流。
     *
     * @param source 出错时报给人看的来源（文件路径或资源标识），不能为空
     * @param reader 由调用方打开、调用方关闭
     */
    public static RosterData load(String source, Reader reader) {
        JsonObject root = JsonSupport.readObject(source, reader);
        JsonSupport.requireSchemaVersion(source, root, SCHEMA_VERSION);
        JsonSupport.onlyKeys(source, "顶层", root, TOP_KEYS);

        List<Survivor> characters = new ArrayList<>();
        var array = JsonSupport.array(source, "顶层", root, "characters");
        for (int i = 0; i < array.size(); i++) {
            characters.add(character(source, "characters[%d]".formatted(i),
                    JsonSupport.asObject(source, "characters[%d]".formatted(i), array.get(i))));
        }
        if (characters.isEmpty()) {
            throw DataFormatException.at(source, "characters", "一个角色都没有");
        }

        Map<Integer, List<CharacterId>> presets = new LinkedHashMap<>();
        JsonObject presetsJson = JsonSupport.object(source, "顶层", root, "presets");
        for (String key : presetsJson.keySet()) {
            int players;
            try {
                players = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                throw DataFormatException.at(source, "presets", "键应当是人数，实际是 " + key);
            }
            List<CharacterId> ids = new ArrayList<>();
            JsonSupport.strings(source, "presets", presetsJson, key)
                    .forEach(id -> ids.add(CharacterId.of(id)));
            presets.put(players, List.copyOf(ids));
        }

        try {
            return new RosterData(List.copyOf(characters), presets);
        } catch (IllegalArgumentException e) {
            // 角色表与预设之间的一致性由 RosterData 把关；它不知道自己是从哪个文件来的，
            // 这里补上文件名，否则报错人得自己猜是哪份数据。
            throw DataFormatException.at(source, "characters/presets", e.getMessage());
        }
    }

    private static Survivor character(String source, String where, JsonObject json) {
        JsonSupport.onlyKeys(source, where, json, CHARACTER_KEYS);
        try {
            return new Survivor(
                    CharacterId.of(JsonSupport.string(source, where, json, "id")),
                    JsonSupport.integer(source, where, json, "seat"),
                    JsonSupport.integer(source, where, json, "size"),
                    JsonSupport.integer(source, where, json, "survival"),
                    JsonSupport.string(source, where, json, "expansion"),
                    ability(source, where + ".ability",
                            JsonSupport.object(source, where, json, "ability")));
        } catch (IllegalArgumentException e) {
            throw DataFormatException.at(source, where, e.getMessage());
        }
    }

    private static Ability ability(String source, String where, JsonObject json) {
        String kind = JsonSupport.string(source, where, json, "kind");
        return switch (kind) {
            case "none" -> {
                JsonSupport.onlyKeys(source, where, json, Set.of("kind"));
                yield new Ability.None();
            }
            case "score_multiplier" -> {
                JsonSupport.onlyKeys(source, where, json, Set.of("kind", "target", "factor", "applies_to"));
                String appliesTo = JsonSupport.string(source, where, json, "applies_to");
                yield new Ability.ScoreMultiplier(
                        treasureKind(source, where, JsonSupport.string(source, where, json, "target")),
                        JsonSupport.integer(source, where, json, "factor"),
                        switch (appliesTo) {
                            case "set_total" -> Ability.ScoreMultiplier.Scope.SET_TOTAL;
                            case "face_value" -> Ability.ScoreMultiplier.Scope.FACE_VALUE;
                            default -> throw DataFormatException.at(source, where + ".applies_to",
                                    "只认 set_total / face_value，实际是 " + appliesTo);
                        });
            }
            case "share_effect" -> {
                JsonSupport.onlyKeys(source, where, json,
                        Set.of("kind", "sources", "requires_conscious", "resolves_last_in_thirst", "stacking"));
                yield new Ability.ShareEffect(
                        JsonSupport.strings(source, where, json, "sources"),
                        JsonSupport.bool(source, where, json, "requires_conscious"),
                        JsonSupport.bool(source, where, json, "resolves_last_in_thirst"),
                        flags(source, where + ".stacking", JsonSupport.object(source, where, json, "stacking")));
            }
            case "overboard_immune" -> {
                JsonSupport.onlyKeys(source, where, json,
                        Set.of("kind", "requires_conscious", "not_protected_from"));
                yield new Ability.OverboardImmune(
                        JsonSupport.bool(source, where, json, "requires_conscious"),
                        JsonSupport.strings(source, where, json, "not_protected_from"));
            }
            case "no_discard" -> {
                JsonSupport.onlyKeys(source, where, json, Set.of(
                        "kind", "target", "still_costs_action", "stays_in_front", "cannot_return_to_hand"));
                yield new Ability.NoDiscard(
                        JsonSupport.string(source, where, json, "target"),
                        JsonSupport.bool(source, where, json, "still_costs_action"),
                        JsonSupport.bool(source, where, json, "stays_in_front"),
                        JsonSupport.bool(source, where, json, "cannot_return_to_hand"));
            }
            case "steal_uncontested" -> {
                JsonSupport.onlyKeys(source, where, json,
                        Set.of("kind", "zone", "triggers_fight", "no_flash_reveal"));
                yield new Ability.StealUncontested(
                        JsonSupport.string(source, where, json, "zone"),
                        JsonSupport.bool(source, where, json, "triggers_fight"),
                        JsonSupport.bool(source, where, json, "no_flash_reveal"));
            }
            default -> throw DataFormatException.at(source, where + ".kind",
                    "引擎不认识的技能 " + kind + " —— 新技能要先在 Ability 里加一种，不能退回「无技能」");
        };
    }

    private static TreasureKind treasureKind(String source, String where, String id) {
        try {
            return TreasureKind.fromId(id);
        } catch (IllegalArgumentException e) {
            throw DataFormatException.at(source, where + ".target", e.getMessage());
        }
    }

    private static Map<String, Boolean> flags(String source, String where, JsonObject json) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            out.put(entry.getKey(), JsonSupport.bool(source, where, json, entry.getKey()));
        }
        return Map.copyOf(out);
    }
}
