package io.github.heavyseasmc.engine.data;

import com.google.gson.JsonObject;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.Selector;

import java.io.Reader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 读 {@code data/navigation/default.json}。
 *
 * <h2>点名一律按 id 全集校验</h2>
 * 角色 id 与物资 id 长得一模一样（小写下划线），而 {@link Selector} 的三种点名模式
 * 遇到不认识的 id <b>都不会报错</b>：{@code list} 取交集取出空集、{@code except} 减掉一个
 * 本来就不在的人、{@code conditional} 问一个永远为假的条件。三种的表现都是「这张牌好像没生效」，
 * 要到几千局之后才会以「某个角色从来不落水」的形式冒出来。
 *
 * <p>所以本加载器要求调用方把<b>角色 id 全集与物资 id 全集</b>一起交进来，
 * 任何一个点不到的 id 当场抛。这一层与生成这份数据的那一侧<b>各查各的</b>：
 * 这里对的是已落库的 {@code data/roster} 与 {@code data/provisions}，
 * 两边的实现与依据都不同，所以不会一起错。
 *
 * <h2>条件的文法是封闭的</h2>
 * 只认 {@code used_<物资 id>}。实测数据里只有一张牌用条件（喝过酒的人落海），
 * 但「条件」这一栏是整份数据里唯一不受 id 白名单保护的自由字符串 ——
 * 封闭文法是它唯一的闸门。
 */
public final class NavigationLoader {

    /** 本加载器读的 schema 版本。 */
    public static final int SCHEMA_VERSION = 1;

    /** 条件词的唯一文法：{@code used_} 加一个物资 id。 */
    public static final String CONDITION_PREFIX = "used_";

    private static final Set<String> TOP_KEYS = Set.of("schema_version", "id", "total", "cards");
    private static final Set<String> CARD_KEYS =
            Set.of("id", "gull", "overboard", "thirst", "thirst_fighters", "thirst_rowers");
    private static final Set<String> SELECTOR_KEYS = Set.of("mode", "characters", "condition");

    private NavigationLoader() {
    }

    /**
     * @param knownCharacters 角色 id 全集，来自 {@link RosterData#ids()}
     * @param knownProvisions 物资 id 全集，来自 {@link ProvisionLoader#loadIds}
     * @throws DataFormatException 任何一处不合 schema、点到不存在的 id、或张数与 {@code total} 对不上
     */
    public static List<NavigationCard> load(
            Path file, Set<CharacterId> knownCharacters, Set<String> knownProvisions) {
        return JsonSupport.fromFile(file, reader -> load(file.toString(), reader, knownCharacters, knownProvisions));
    }

    /**
     * 从字符流读：数据包里的资源没有文件路径，只有一个标识和一条流。其余约定同上。
     *
     * @param source 出错时报给人看的来源（文件路径或资源标识），不能为空
     * @param reader 由调用方打开、调用方关闭
     */
    public static List<NavigationCard> load(
            String source, Reader reader, Set<CharacterId> knownCharacters, Set<String> knownProvisions) {
        Objects.requireNonNull(knownCharacters, "knownCharacters");
        Objects.requireNonNull(knownProvisions, "knownProvisions");
        if (knownCharacters.isEmpty()) {
            // 空全集会让下面每一条 id 校验都变成「全部不通过」或「全部通过」，
            // 取决于实现细节 —— 两种都是假信号，不如当场拒绝。
            throw new IllegalArgumentException("角色 id 全集为空，点名校验会失去意义");
        }

        JsonObject root = JsonSupport.readObject(source, reader);
        JsonSupport.requireSchemaVersion(source, root, SCHEMA_VERSION);
        JsonSupport.onlyKeys(source, "顶层", root, TOP_KEYS);

        List<NavigationCard> cards = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        var array = JsonSupport.array(source, "顶层", root, "cards");
        for (int i = 0; i < array.size(); i++) {
            String where = "cards[%d]".formatted(i);
            JsonObject json = JsonSupport.asObject(source, where, array.get(i));
            JsonSupport.onlyKeys(source, where, json, CARD_KEYS);
            String id = JsonSupport.string(source, where, json, "id");
            if (!seenIds.add(id)) {
                throw DataFormatException.at(source, where, "牌 id 重复: " + id);
            }
            try {
                cards.add(new NavigationCard(
                        id,
                        JsonSupport.integer(source, where, json, "gull"),
                        selector(source, where + ".overboard",
                                JsonSupport.object(source, where, json, "overboard"),
                                knownCharacters, knownProvisions),
                        selector(source, where + ".thirst",
                                JsonSupport.object(source, where, json, "thirst"),
                                knownCharacters, knownProvisions),
                        JsonSupport.bool(source, where, json, "thirst_rowers"),
                        JsonSupport.bool(source, where, json, "thirst_fighters")));
            } catch (IllegalArgumentException e) {
                throw DataFormatException.at(source, where, e.getMessage());
            }
        }
        if (cards.isEmpty()) {
            throw DataFormatException.at(source, "cards", "一张航海牌都没有 —— 没有牌的牌堆永远结束不了一局");
        }

        // 与文件自己写的张数对账。同时是正向对照：只有真的逐张读过才可能通过。
        int declared = JsonSupport.integer(source, "顶层", root, "total");
        if (declared != cards.size()) {
            throw DataFormatException.at(source, "total",
                    "写着 %d 张，实际读到 %d 张".formatted(declared, cards.size()));
        }
        return List.copyOf(cards);
    }

    private static Selector selector(String source, String where, JsonObject json,
                                     Set<CharacterId> knownCharacters, Set<String> knownProvisions) {
        JsonSupport.onlyKeys(source, where, json, SELECTOR_KEYS);
        String mode = JsonSupport.string(source, where, json, "mode");
        return switch (mode) {
            case "list" -> new Selector.Only(names(source, where, json, mode, knownCharacters));
            case "except" -> new Selector.Except(names(source, where, json, mode, knownCharacters));
            case "all" -> {
                requireAbsent(source, where, json, mode, "characters", "condition");
                yield new Selector.Everyone();
            }
            case "none" -> {
                requireAbsent(source, where, json, mode, "characters", "condition");
                yield new Selector.Nobody();
            }
            case "conditional" -> {
                requireAbsent(source, where, json, mode, "characters");
                yield new Selector.Conditional(
                        condition(source, where, JsonSupport.string(source, where, json, "condition"), knownProvisions));
            }
            default -> throw DataFormatException.at(source, where + ".mode",
                    "引擎不认识的点名模式 " + mode + "（只认 list / except / all / none / conditional）");
        };
    }

    private static Set<CharacterId> names(String source, String where, JsonObject json,
                                          String mode, Set<CharacterId> knownCharacters) {
        requireAbsent(source, where, json, mode, "condition");
        List<String> raw = JsonSupport.strings(source, where, json, "characters");
        if (raw.isEmpty()) {
            // 空名单必须由 none 表示。留着空的 list 会让「这张牌不点人」与
            // 「这张牌的名单导丢了」看起来一模一样 —— 见 Selector.Nobody 的说明。
            throw DataFormatException.at(source, where + ".characters",
                    "名单是空的 —— 「一个人也不点」要写成 mode=none");
        }
        Set<CharacterId> out = new LinkedHashSet<>();
        for (String id : raw) {
            CharacterId character = CharacterId.of(id);
            if (!knownCharacters.contains(character)) {
                throw DataFormatException.at(source, where + ".characters",
                        "点了角色表里没有的 %s（角色表里有 %s）"
                                .formatted(id, knownCharacters.stream().map(CharacterId::value).sorted().toList()));
            }
            if (!out.add(character)) {
                throw DataFormatException.at(source, where + ".characters", id + " 在同一份名单里出现了两次");
            }
        }
        return Set.copyOf(out);
    }

    private static String condition(String source, String where, String raw, Set<String> knownProvisions) {
        if (!raw.startsWith(CONDITION_PREFIX)) {
            throw DataFormatException.at(source, where + ".condition",
                    "只认 %s<物资 id> 这一种条件，实际是 %s".formatted(CONDITION_PREFIX, raw));
        }
        String provision = raw.substring(CONDITION_PREFIX.length());
        if (!knownProvisions.contains(provision)) {
            throw DataFormatException.at(source, where + ".condition",
                    "条件指向物资表里没有的 %s".formatted(provision));
        }
        return raw;
    }

    private static void requireAbsent(String source, String where, JsonObject json, String mode, String... keys) {
        for (String key : keys) {
            if (json.has(key)) {
                throw DataFormatException.at(source, where,
                        "%s 模式不该带 %s 字段 —— 带了说明导出器与引擎对这张牌的理解不一致"
                                .formatted(mode, key));
            }
        }
    }
}
