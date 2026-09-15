package io.github.heavyseasmc.engine.data;

import com.google.gson.JsonObject;
import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.engine.model.Provisions;

import java.io.Reader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 读 {@code data/provisions/default.json}：<b>18 种物资的效果</b>，外加 id 全集与展开成 47 张的牌堆。
 *
 * <h2>这里曾经刻意不读效果，那条注释到 ADR-0021 为止</h2>
 * M1 期间本类只做两件能被验证的事（数 id、展开牌堆），效果字段一个都不碰 ——
 * 因为那时物资根本打不出来，读了也没人用。现在物资要真的生效，所以效果全部读进来。
 *
 * <h2>不认识的字段一律抛，不忽略</h2>
 * 数据包能覆盖这份文件（ADR-0015），所以它面对的是**玩家写的文件**。
 * 而 {@code effect} 下 38 个字段里有 26 个<b>只出现一次</b>（{@code bypasses}、{@code requires_corpse}、
 * {@code no_discard_for} …）—— 一张牌的关键机制常常只写在一个字段里。
 * 「忽略不认识的字段」与「这张牌的效果读全了」在输出上完全相同，
 * 所以这里对字段名（{@link JsonSupport#onlyKeys}）与枚举值（{@link JsonSupport#oneOf}）两头都严格。
 *
 * <p>代价说出来：以后数据加一个字段，必须同时改这里。这是要的 ——
 * 加了字段却没人读，才是要防的那一种。
 */
public final class ProvisionLoader {

    /** 本加载器读的 schema 版本。 */
    public static final int SCHEMA_VERSION = 1;

    private static final Set<String> CARD_KEYS = Set.of("id", "count", "category", "effect");

    private ProvisionLoader() {
    }

    /** 从文件读：测试与工具走这条路。文件由本方法打开并关闭。 */
    public static Set<String> loadIds(Path file) {
        return JsonSupport.fromFile(file, reader -> loadIds(file.toString(), reader));
    }

    /**
     * 从字符流读：数据包里的资源没有文件路径，只有一个标识和一条流。
     *
     * @param source 出错时报给人看的来源（文件路径或资源标识），不能为空
     * @param reader 由调用方打开、调用方关闭
     */
    public static Set<String> loadIds(String source, Reader reader) {
        return loadDocument(source, reader).value();
    }

    /**
     * 同上，但把文件自称的 {@code id} 一起带出来，供调用方做跨文件核对。见 {@link DataDocument}。
     */
    public static DataDocument<Set<String>> loadDocument(String source, Reader reader) {
        DataDocument<Provisions> doc = loadCatalog(source, reader);
        return new DataDocument<>(doc.id(), doc.value().ids());
    }

    /**
     * 整副物资牌：47 张，<b>按张数展开</b>（水会出现 16 次）。
     *
     * <p>发牌要的是这一份，不是 id 全集 —— 全集回答「有哪些东西」，
     * 这一份回答「牌堆里有几张」，两者混用就会发出 18 张牌的牌堆。
     */
    public static DataDocument<List<String>> loadDeckDocument(String source, Reader reader) {
        DataDocument<Provisions> doc = loadCatalog(source, reader);
        return new DataDocument<>(doc.id(), doc.value().deck());
    }

    /** 从文件读整份目录。 */
    public static Provisions loadCatalog(Path file) {
        return JsonSupport.fromFile(file, reader -> loadCatalog(file.toString(), reader).value());
    }

    /**
     * 整份目录：18 种物资的 id、类别、张数与效果。
     *
     * <p>上面三个入口都从这一份派生 —— 解析两遍就会有两处可以各自写错。
     */
    public static DataDocument<Provisions> loadCatalog(String source, Reader reader) {
        JsonObject root = JsonSupport.readObject(source, reader);
        JsonSupport.requireSchemaVersion(source, root, SCHEMA_VERSION);

        List<Provision> cards = new ArrayList<>();
        int printed = 0;
        var array = JsonSupport.array(source, "顶层", root, "cards");
        for (int i = 0; i < array.size(); i++) {
            String where = "cards[%d]".formatted(i);
            JsonObject card = JsonSupport.asObject(source, where, array.get(i));
            JsonSupport.onlyKeys(source, where, card, CARD_KEYS);
            String id = JsonSupport.string(source, where, card, "id");
            int count = JsonSupport.integer(source, where, card, "count");
            if (count < 1) {
                throw DataFormatException.at(source, where, "张数必须为正，实际: " + count);
            }
            Provision.Category category = category(source, where,
                    JsonSupport.string(source, where, card, "category"));
            ProvisionEffect effect = effect(source, where + ".effect",
                    JsonSupport.object(source, where, card, "effect"));
            cards.add(new Provision(id, category, count, effect));
            printed += count;
        }
        if (cards.isEmpty()) {
            throw DataFormatException.at(source, "cards", "一张物资牌都没有");
        }

        // 与文件自己写的张数对账。这一条同时是<b>正向对照</b>：
        // 它只有在真的把每张牌都读了一遍之后才可能通过，所以「加载器其实没在扫」会当场露馅。
        int declared = JsonSupport.integer(source, "顶层", root, "total");
        if (declared != printed) {
            throw DataFormatException.at(source, "total",
                    "写着 %d 张，按 count 数出来是 %d 张".formatted(declared, printed));
        }
        Provisions catalog;
        try {
            catalog = new Provisions(cards);
        } catch (IllegalArgumentException e) {
            throw DataFormatException.at(source, "cards", e.getMessage());
        }
        return new DataDocument<>(JsonSupport.string(source, "顶层", root, "id"), catalog);
    }

    private static Provision.Category category(String source, String where, String raw) {
        try {
            return Provision.Category.parse(raw);
        } catch (IllegalArgumentException e) {
            throw DataFormatException.at(source, where + ".category", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 效果

    /** 每种 kind 认哪些字段。少一个字段读不到，多一个字段当场抛 —— 两头都要有判据。 */
    private static final Map<String, Set<String>> EFFECT_KEYS = Map.ofEntries(
            Map.entry("prevent_thirst", Set.of("kind", "amount", "unit", "target", "may_target_others",
                    "resolved_during", "discard_on_use", "persistent", "requires_open",
                    "costs_action_to_open", "lost_when_overboard")),
            Map.entry("heal", Set.of("kind", "amount", "target", "costs_action", "discard_on_use",
                    "no_discard_for")),
            Map.entry("damage_in_water", Set.of("kind", "amount", "stacks", "per_overboard_phase", "bypasses")),
            Map.entry("heal_all", Set.of("kind", "amount", "targets", "requires_corpse", "contestable",
                    "costs_action", "discard_on_use")),
            Map.entry("buff_size", Set.of("kind", "amount", "duration", "side_effect", "persists_in_front",
                    "once_per_turn", "stacks")),
            Map.entry("navigator_extra_draw", Set.of("kind", "amount", "into", "timing")),
            Map.entry("prevent_overboard_damage", Set.of("kind", "transferable", "kept_by_receiver",
                    "survives_overboard", "receiver_must_be_conscious", "must_be_given_in_advance")),
            Map.entry("weapon_or_special", Set.of("kind", "power", "exclusive_choice", "special",
                    "discard_after_any_use")),
            Map.entry("weapon", Set.of("kind", "power")),
            Map.entry("weapon_and_row_bonus", Set.of("kind", "power", "row_extra_draw", "exclusive_choice",
                    "stacks")),
            Map.entry("score_flat", Set.of("kind", "points", "doubled_by")),
            Map.entry("score_set", Set.of("kind", "table", "doubled_by", "doubling_applies_to")));

    private static final Set<String> SIGNAL_KEYS =
            Set.of("kind", "draw", "resolve_only", "includes_gull_removal", "return_to");

    private static ProvisionEffect effect(String source, String where, JsonObject effect) {
        String kind = JsonSupport.string(source, where, effect, "kind");
        Set<String> allowed = EFFECT_KEYS.get(kind);
        if (allowed == null) {
            throw DataFormatException.at(source, where + ".kind",
                    "引擎不认识这种效果: %s —— 认识的是 %s".formatted(kind, EFFECT_KEYS.keySet().stream().sorted().toList()));
        }
        JsonSupport.onlyKeys(source, where, effect, allowed);
        try {
            return build(source, where, kind, effect);
        } catch (IllegalArgumentException e) {
            // record 的构造函数校验（数值为正之类）也算数据错误，转成带位置的那一种。
            throw DataFormatException.at(source, where, e.getMessage());
        }
    }

    private static ProvisionEffect build(String source, String where, String kind, JsonObject e) {
        return switch (kind) {
            case "prevent_thirst" -> new ProvisionEffect.PreventThirst(
                    JsonSupport.integer(source, where, e, "amount"),
                    JsonSupport.oneOf(source, where, e, "unit", Set.of("thirst_source")),
                    target(source, where, e),
                    JsonSupport.optionalBool(source, where, e, "may_target_others", false),
                    e.has("resolved_during")
                            ? JsonSupport.oneOf(source, where, e, "resolved_during", Set.of("thirst_resolution"))
                            : "",
                    JsonSupport.optionalBool(source, where, e, "discard_on_use", false),
                    JsonSupport.optionalBool(source, where, e, "persistent", false),
                    JsonSupport.optionalBool(source, where, e, "requires_open", false),
                    JsonSupport.optionalBool(source, where, e, "costs_action_to_open", false),
                    JsonSupport.optionalBool(source, where, e, "lost_when_overboard", false));
            case "heal" -> new ProvisionEffect.Heal(
                    JsonSupport.integer(source, where, e, "amount"),
                    target(source, where, e),
                    JsonSupport.optionalBool(source, where, e, "costs_action", false),
                    JsonSupport.optionalBool(source, where, e, "discard_on_use", false),
                    JsonSupport.optionalStrings(source, where, e, "no_discard_for"));
            case "damage_in_water" -> new ProvisionEffect.DamageInWater(
                    JsonSupport.integer(source, where, e, "amount"),
                    JsonSupport.optionalBool(source, where, e, "stacks", false),
                    JsonSupport.optionalBool(source, where, e, "per_overboard_phase", false),
                    JsonSupport.optionalStrings(source, where, e, "bypasses"));
            case "heal_all" -> new ProvisionEffect.HealAll(
                    JsonSupport.integer(source, where, e, "amount"),
                    JsonSupport.oneOf(source, where, e, "targets", Set.of("conscious_only")),
                    JsonSupport.optionalBool(source, where, e, "requires_corpse", false),
                    JsonSupport.optionalBool(source, where, e, "contestable", false),
                    JsonSupport.optionalBool(source, where, e, "costs_action", false),
                    JsonSupport.optionalBool(source, where, e, "discard_on_use", false));
            case "buff_size" -> new ProvisionEffect.BuffSize(
                    JsonSupport.integer(source, where, e, "amount"),
                    JsonSupport.oneOf(source, where, e, "duration", Set.of("turn")),
                    JsonSupport.oneOf(source, where, e, "side_effect",
                            Set.of(ProvisionEffect.BuffSize.THIRST_AT_END_OF_TURN)),
                    JsonSupport.optionalBool(source, where, e, "persists_in_front", false),
                    JsonSupport.optionalBool(source, where, e, "once_per_turn", false),
                    JsonSupport.optionalBool(source, where, e, "stacks", false));
            case "navigator_extra_draw" -> new ProvisionEffect.NavigatorExtraDraw(
                    JsonSupport.integer(source, where, e, "amount"),
                    JsonSupport.oneOf(source, where, e, "into", Set.of("row_stack")),
                    JsonSupport.oneOf(source, where, e, "timing", Set.of("before_navigator_chooses")));
            case "prevent_overboard_damage" -> new ProvisionEffect.PreventOverboardDamage(
                    JsonSupport.optionalBool(source, where, e, "transferable", false),
                    JsonSupport.optionalBool(source, where, e, "kept_by_receiver", false),
                    JsonSupport.optionalBool(source, where, e, "survives_overboard", false),
                    JsonSupport.optionalBool(source, where, e, "receiver_must_be_conscious", false),
                    JsonSupport.optionalBool(source, where, e, "must_be_given_in_advance", false));
            case "weapon_or_special" -> new ProvisionEffect.WeaponOrSpecial(
                    JsonSupport.integer(source, where, e, "power"),
                    JsonSupport.optionalBool(source, where, e, "exclusive_choice", false),
                    signal(source, where + ".special", JsonSupport.object(source, where, e, "special")),
                    JsonSupport.optionalBool(source, where, e, "discard_after_any_use", false));
            case "weapon" -> new ProvisionEffect.Weapon(JsonSupport.integer(source, where, e, "power"));
            case "weapon_and_row_bonus" -> new ProvisionEffect.WeaponAndRowBonus(
                    JsonSupport.integer(source, where, e, "power"),
                    JsonSupport.integer(source, where, e, "row_extra_draw"),
                    JsonSupport.optionalBool(source, where, e, "exclusive_choice", false),
                    JsonSupport.optionalBool(source, where, e, "stacks", false));
            case "score_flat" -> new ProvisionEffect.ScoreFlat(
                    JsonSupport.integer(source, where, e, "points"),
                    JsonSupport.string(source, where, e, "doubled_by"));
            case "score_set" -> new ProvisionEffect.ScoreSet(
                    setTotals(source, where, JsonSupport.object(source, where, e, "table")),
                    JsonSupport.string(source, where, e, "doubled_by"),
                    JsonSupport.oneOf(source, where, e, "doubling_applies_to",
                            Set.of("set_total", "face_value")));
            // EFFECT_KEYS 已经挡掉了不认识的 kind，走到这里说明那张表加了一行而这里没加。
            default -> throw new IllegalStateException("kind 白名单与解析分支不一致: " + kind);
        };
    }

    private static ProvisionEffect.Target target(String source, String where, JsonObject e) {
        String raw = JsonSupport.oneOf(source, where, e, "target",
                Set.of("self", "any_character", "any_character_including_self"));
        return switch (raw) {
            case "self" -> ProvisionEffect.Target.SELF;
            case "any_character" -> ProvisionEffect.Target.ANY_CHARACTER;
            default -> ProvisionEffect.Target.ANY_CHARACTER_INCLUDING_SELF;
        };
    }

    private static ProvisionEffect.WeaponOrSpecial.Signal signal(String source, String where, JsonObject s) {
        JsonSupport.onlyKeys(source, where, s, SIGNAL_KEYS);
        return new ProvisionEffect.WeaponOrSpecial.Signal(
                JsonSupport.oneOf(source, where, s, "kind", Set.of("draw_and_resolve_gulls")),
                JsonSupport.integer(source, where, s, "draw"),
                JsonSupport.oneOf(source, where, s, "resolve_only", Set.of("gulls")),
                JsonSupport.optionalBool(source, where, s, "includes_gull_removal", false),
                JsonSupport.oneOf(source, where, s, "return_to", Set.of("deck_bottom")));
    }

    /**
     * 套组表 {@code {"1":1,"2":4,"3":8}} → 列表，下标 0 对应持有 1 张。
     *
     * <p>❗<b>键必须从 1 起连续</b>：「有 1 张与 3 张的分、没有 2 张的分」这种表在映射里写得出来，
     * 而计分时会静默取到错的一档。{@code TreasureScoring} 用列表正是为此，这里在入口就把它拉直。
     */
    private static List<Integer> setTotals(String source, String where, JsonObject table) {
        List<Integer> out = new ArrayList<>();
        for (int n = 1; ; n++) {
            String key = Integer.toString(n);
            if (!table.has(key)) {
                break;
            }
            out.add(JsonSupport.integer(source, where + ".table", table, key));
        }
        if (out.isEmpty()) {
            throw DataFormatException.at(source, where + ".table", "套组表是空的，至少要有 \"1\"");
        }
        if (out.size() != table.size()) {
            throw DataFormatException.at(source, where + ".table",
                    "套组表的键必须从 1 起连续，实际有 %d 个键但只连到 %d".formatted(table.size(), out.size()));
        }
        return out;
    }
}
