package io.github.heavyseasmc.engine.data;

import com.google.gson.JsonObject;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 读 {@code data/provisions/default.json}：<b>id 全集</b>与<b>展开成 47 张的牌堆</b>。
 *
 * <h2>仍然不读效果，这是有意的</h2>
 * 物资的效果字段（谁能用、用后弃不弃、抵几次口渴）属于 M1 的手牌与物资系统，
 * 那时会有一个完整的加载器。现在唯一的用处是给航海牌的 {@code conditional} 条件做白名单：
 * 条件写的是 {@code used_<物资 id>}，而物资 id 与角色 id<b>长得一模一样</b>
 * （都是小写下划线），传错不会报错，只会静默选出空集合。
 *
 * <p>所以本类刻意<b>不</b>做字段白名单 —— 它没有声称自己读懂了这份文件。
 * 它只做两件能被验证的事：把 id 数出来与 {@code total} 对账；按张数把牌堆展开。
 * <b>效果字段一个都不碰</b> —— 那要等物资真的能被打出来的时候（M2+）。
 */
public final class ProvisionLoader {

    /** 本加载器读的 schema 版本。 */
    public static final int SCHEMA_VERSION = 1;

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
        Parsed p = parse(source, reader);
        return new DataDocument<>(p.id(), p.ids());
    }

    /**
     * 整副物资牌：47 张，<b>按张数展开</b>（水会出现 16 次）。
     *
     * <p>发牌要的是这一份，不是 id 全集 —— 全集回答「有哪些东西」，
     * 这一份回答「牌堆里有几张」，两者混用就会发出 18 张牌的牌堆。
     */
    public static DataDocument<List<String>> loadDeckDocument(String source, Reader reader) {
        Parsed p = parse(source, reader);
        return new DataDocument<>(p.id(), p.deck());
    }

    /** 一次解析同时给出两种视图 —— 解析两遍就会有两处可以各自写错。 */
    private record Parsed(String id, Set<String> ids, List<String> deck) {
    }

    private static Parsed parse(String source, Reader reader) {
        JsonObject root = JsonSupport.readObject(source, reader);
        JsonSupport.requireSchemaVersion(source, root, SCHEMA_VERSION);

        Set<String> ids = new LinkedHashSet<>();
        List<String> deck = new ArrayList<>();
        int printed = 0;
        var cards = JsonSupport.array(source, "顶层", root, "cards");
        for (int i = 0; i < cards.size(); i++) {
            String where = "cards[%d]".formatted(i);
            JsonObject card = JsonSupport.asObject(source, where, cards.get(i));
            String id = JsonSupport.string(source, where, card, "id");
            if (!ids.add(id)) {
                throw DataFormatException.at(source, where, "物资 id 重复: " + id);
            }
            int count = JsonSupport.integer(source, where, card, "count");
            if (count < 1) {
                throw DataFormatException.at(source, where, "张数必须为正，实际: " + count);
            }
            for (int n = 0; n < count; n++) {
                deck.add(id);
            }
            printed += count;
        }
        if (ids.isEmpty()) {
            throw DataFormatException.at(source, "cards", "一张物资牌都没有");
        }

        // 与文件自己写的张数对账。这一条同时是<b>正向对照</b>：
        // 它只有在真的把每张牌都读了一遍之后才可能通过，所以「加载器其实没在扫」会当场露馅。
        int declared = JsonSupport.integer(source, "顶层", root, "total");
        if (declared != printed) {
            throw DataFormatException.at(source, "total",
                    "写着 %d 张，按 count 数出来是 %d 张".formatted(declared, printed));
        }
        return new Parsed(JsonSupport.string(source, "顶层", root, "id"),
                Set.copyOf(ids), List.copyOf(deck));
    }
}
