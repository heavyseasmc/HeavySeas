package io.github.heavyseasmc.engine.data;

import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 读 {@code data/provisions/default.json} 的<b>物资 id 全集</b>。
 *
 * <h2>只读 id，这是有意的</h2>
 * 物资的效果字段（谁能用、用后弃不弃、抵几次口渴）属于 M1 的手牌与物资系统，
 * 那时会有一个完整的加载器。现在唯一的用处是给航海牌的 {@code conditional} 条件做白名单：
 * 条件写的是 {@code used_<物资 id>}，而物资 id 与角色 id<b>长得一模一样</b>
 * （都是小写下划线），传错不会报错，只会静默选出空集合。
 *
 * <p>所以本类刻意<b>不</b>做字段白名单 —— 它没有声称自己读懂了这份文件。
 * 它只做一件能被验证的事：把 id 数出来，并与文件自己写的 {@code total} 对账。
 */
public final class ProvisionLoader {

    /** 本加载器读的 schema 版本。 */
    public static final int SCHEMA_VERSION = 1;

    private ProvisionLoader() {
    }

    public static Set<String> loadIds(Path file) {
        JsonObject root = JsonSupport.readObject(file);
        JsonSupport.requireSchemaVersion(file, root, SCHEMA_VERSION);

        Set<String> ids = new LinkedHashSet<>();
        int printed = 0;
        var cards = JsonSupport.array(file, "顶层", root, "cards");
        for (int i = 0; i < cards.size(); i++) {
            String where = "cards[%d]".formatted(i);
            JsonObject card = JsonSupport.asObject(file, where, cards.get(i));
            String id = JsonSupport.string(file, where, card, "id");
            if (!ids.add(id)) {
                throw DataFormatException.at(file, where, "物资 id 重复: " + id);
            }
            printed += JsonSupport.integer(file, where, card, "count");
        }
        if (ids.isEmpty()) {
            throw DataFormatException.at(file, "cards", "一张物资牌都没有");
        }

        // 与文件自己写的张数对账。这一条同时是<b>正向对照</b>：
        // 它只有在真的把每张牌都读了一遍之后才可能通过，所以「加载器其实没在扫」会当场露馅。
        int declared = JsonSupport.integer(file, "顶层", root, "total");
        if (declared != printed) {
            throw DataFormatException.at(file, "total",
                    "写着 %d 张，按 count 数出来是 %d 张".formatted(declared, printed));
        }
        return Set.copyOf(ids);
    }
}
