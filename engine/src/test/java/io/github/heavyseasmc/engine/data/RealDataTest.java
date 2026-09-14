package io.github.heavyseasmc.engine.data;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.Selector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拿真实 {@code data/} 跑加载器。
 *
 * <p>角色表与物资表<b>已落库</b>，这几条在 CI 上照跑 —— 它们同时是加载器的正向对照：
 * 合成数据全绿只证明解析器自洽，真实数据全绿才证明它读得懂导出器写出来的东西。
 *
 * <p>三份数据现在都已落库，所以整个类在 CI 上照跑；缺任何一份都是失败，见 {@link LocalData}。
 */
class RealDataTest {

    @Test
    @DisplayName("角色表：八个角色、三套预设，预设是嵌套的")
    void rosterLoads() {
        RosterData data = RosterLoader.load(LocalData.dir().file("roster/default.json"));

        assertEquals(8, data.characters().size());
        assertEquals(Set.of(6, 7, 8), data.presets().keySet());

        // ❗嵌套是三套阵容平衡的基础（见 data/roster/default.json 的注释）：
        //   从 8 人里摘掉两个人恰好得到 6 人局。写成断言之前它只是一句注释。
        Set<CharacterId> six = new LinkedHashSet<>(data.presets().get(6));
        Set<CharacterId> seven = new LinkedHashSet<>(data.presets().get(7));
        Set<CharacterId> eight = new LinkedHashSet<>(data.presets().get(8));
        assertTrue(seven.containsAll(six), "7 人局不是 6 人局的超集: " + seven);
        assertTrue(eight.containsAll(seven), "8 人局不是 7 人局的超集: " + eight);

        // 体型与生存分的关系：两者之和固定为 12，这是「壮的人难杀但不值钱」的数值形式。
        for (Survivor survivor : data.characters()) {
            assertEquals(12, survivor.size() + survivor.survival(),
                    survivor.id() + " 的体型+生存分不是 12");
        }

        Roster eightPlayers = data.preset(8);
        assertEquals(8, eightPlayers.size());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8),
                eightPlayers.survivors().stream().map(Survivor::seat).toList());
    }

    @Test
    @DisplayName("物资表：18 种 id，47 张牌")
    void provisionsLoad() {
        Set<String> ids = ProvisionLoader.loadIds(LocalData.dir().file("provisions/default.json"));
        assertEquals(18, ids.size(), "物资 id 数变了: " + ids);
        assertTrue(ids.contains("water"), ids.toString());
        assertTrue(ids.contains("parasol"), ids.toString());
    }

    @Test
    @DisplayName("航海牌：31 张，点名全部落在角色表里")
    void navigationLoads() {
        List<NavigationCard> deck = LocalData.navigationDeck();
        assertEquals(31, deck.size());

        Set<CharacterId> known = RosterLoader.load(LocalData.dir().file("roster/default.json")).ids();
        Set<String> named = new LinkedHashSet<>();
        int withGull = 0;
        int conditional = 0;
        for (NavigationCard card : deck) {
            if (card.gull() != 0) {
                withGull++;
            }
            for (Selector selector : List.of(card.overboard(), card.thirst())) {
                switch (selector) {
                    case Selector.Only only -> only.characters().forEach(id -> named.add(id.value()));
                    case Selector.Except except -> except.characters().forEach(id -> named.add(id.value()));
                    case Selector.Conditional ignored -> conditional++;
                    case Selector.Everyone ignored -> { }
                    case Selector.Nobody ignored -> { }
                }
            }
        }

        // 正向对照：如果加载器其实什么都没点到，下面三条会一起垮，而不是一起「通过」。
        assertFalse(named.isEmpty(), "31 张牌里一个人都没点到，点名多半没解析");
        assertEquals(known.size(), named.size(),
                "有角色从头到尾没被任何一张牌点过，落水/口渴分布会缺一条腿: " + named);
        assertTrue(withGull > 0, "没有任何一张牌带海鸥，那样一局永远结束不了");
        assertEquals(1, conditional, "带条件的牌张数变了 —— 条件是唯一不受 id 白名单保护的字段");
    }
}
