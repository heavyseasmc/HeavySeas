package io.github.heavyseasmc.engine.data;

import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.model.TreasureKind;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.Selector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 加载器。
 *
 * <p>这里的数据全是<b>合成的</b>，写在测试里、放进临时目录，所以 CI 上照样跑。
 * 读真实 {@code data/} 的那部分在 {@link RealDataTest}。
 *
 * <p>❗本类里几乎每个用例都是 {@code assertThrows}。这是有意的：加载器的<b>全部价值</b>
 * 就在于把「数据坏了」变成一声当场的响，而不是几千局之后的一个怪现象。
 * 每一条 assertThrows 就是那道闸门的红测 —— 把缺陷装回去、确认它真的红。
 */
class DataLoadingTest {

    @TempDir
    Path dir;

    private Path write(String name, String json) {
        try {
            Path path = dir.resolve(name);
            Files.createDirectories(path.getParent() == null ? dir : path.getParent());
            Files.writeString(path, json, StandardCharsets.UTF_8);
            return path;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ 定址

    @Nested
    @DisplayName("data/ 定址")
    class Addressing {

        @Test
        @DisplayName("目录不存在就抛 —— 不退回任何默认值")
        void missingDirectoryThrows() {
            Path absent = dir.resolve("没有这个目录");
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new DataDir(absent));
            assertTrue(e.getMessage().contains("没有这个目录"), e.getMessage());
        }

        @Test
        @DisplayName("没配系统属性也没配环境变量就抛，不去猜常见路径")
        void unconfiguredThrows() {
            String saved = System.getProperty(DataDir.PROPERTY);
            System.clearProperty(DataDir.PROPERTY);
            try {
                if (System.getenv(DataDir.ENV) != null) {
                    return;               // 这台机器设了环境变量，这一条测不了
                }
                IllegalStateException e = assertThrows(IllegalStateException.class, DataDir::fromEnvironment);
                assertTrue(e.getMessage().contains(DataDir.PROPERTY), e.getMessage());
                assertTrue(e.getMessage().contains(DataDir.ENV), e.getMessage());
            } finally {
                if (saved != null) {
                    System.setProperty(DataDir.PROPERTY, saved);
                }
            }
        }

        @Test
        @DisplayName("文件不在就抛，并报出解析到的绝对路径")
        void missingFile() {
            DataDir data = new DataDir(dir);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> data.file("navigation/default.json"));
            // 路径要出现在消息里：缺文件最常见的原因是 data.dir 指错了地方，
            // 而「指错了」与「真的没有」只有那条绝对路径分得开。
            assertTrue(e.getMessage().contains("navigation"), e.getMessage());

            write("navigation/default.json", "{}");
            assertTrue(Files.isRegularFile(data.file("navigation/default.json")));
        }

        @Test
        @DisplayName("绝对路径与 .. 都拒绝 —— resolve 会原样返回绝对路径，那是无声的越界")
        void escapeAttemptsRejected() {
            DataDir data = new DataDir(dir);
            Path absolute = dir.resolve("x.json").toAbsolutePath();
            assertThrows(IllegalArgumentException.class, () -> data.file(absolute.toString()));
            assertThrows(IllegalArgumentException.class, () -> data.file("../隔壁/x.json"));
        }
    }

    // ------------------------------------------------------------------ 角色表

    @Nested
    @DisplayName("角色表")
    class RosterFile {

        private static final String THREE_CHARACTERS = """
                {
                  "schema_version": 1,
                  "id": "heavyseas:test",
                  "characters": [
                    {"id": "jeweler", "seat": 1, "size": 4, "survival": 8, "expansion": "base",
                     "ability": {"kind": "score_multiplier", "target": "jewelry", "factor": 2,
                                 "applies_to": "set_total"}},
                    {"id": "mate", "seat": 4, "size": 8, "survival": 4, "expansion": "base",
                     "ability": {"kind": "none"}},
                    {"id": "sailor", "seat": 6, "size": 6, "survival": 6, "expansion": "base",
                     "ability": {"kind": "overboard_immune", "requires_conscious": true,
                                 "not_protected_from": ["bait_bucket"]}}
                  ],
                  "presets": {"2": ["jeweler", "mate"], "3": ["jeweler", "mate", "sailor"]}
                }""";

        @Test
        @DisplayName("正常读出角色、技能与预设")
        void loadsCharacters() {
            RosterData data = RosterLoader.load(write("roster.json", THREE_CHARACTERS));

            assertEquals(3, data.characters().size());
            assertEquals(Set.of(CharacterId.of("jeweler"), CharacterId.of("mate"), CharacterId.of("sailor")),
                    data.ids());

            Survivor jeweler = data.get(CharacterId.of("jeweler"));
            assertEquals(4, jeweler.size());
            assertEquals(8, jeweler.survival());
            Ability.ScoreMultiplier multiplier =
                    assertInstanceOf(Ability.ScoreMultiplier.class, jeweler.ability());
            assertEquals(TreasureKind.JEWELRY, multiplier.target());
            assertEquals(Ability.ScoreMultiplier.Scope.SET_TOTAL, multiplier.appliesTo());

            Ability.OverboardImmune immune = assertInstanceOf(Ability.OverboardImmune.class,
                    data.get(CharacterId.of("sailor")).ability());
            assertTrue(immune.requiresConscious());
            assertEquals(List.of("bait_bucket"), immune.notProtectedFrom());

            Roster two = data.preset(2);
            assertEquals(2, two.size());
            assertEquals(List.of(CharacterId.of("jeweler"), CharacterId.of("mate")),
                    two.survivors().stream().map(Survivor::id).toList());
            assertThrows(IllegalArgumentException.class, () -> data.preset(7));
        }

        @Test
        @DisplayName("❗认不出的技能直接抛，不退回「无技能」—— 大副本来就没技能，退回就永远发现不了")
        void unknownAbilityRejected() {
            String json = THREE_CHARACTERS.replace("\"kind\": \"none\"", "\"kind\": \"teleport\"");
            DataFormatException e = assertThrows(DataFormatException.class,
                    () -> RosterLoader.load(write("bad-ability.json", json)));
            assertTrue(e.getMessage().contains("teleport"), e.getMessage());
            assertTrue(e.getMessage().contains("characters[1]"), e.getMessage());
        }

        @Test
        @DisplayName("技能里多一个字段就抛 —— 导出器写了、引擎没读，是最难发现的一类漂移")
        void unknownAbilityFieldRejected() {
            String json = THREE_CHARACTERS.replace(
                    "\"kind\": \"overboard_immune\", \"requires_conscious\": true",
                    "\"kind\": \"overboard_immune\", \"requires_conscious\": true, \"heals\": 2");
            DataFormatException e = assertThrows(DataFormatException.class,
                    () -> RosterLoader.load(write("extra-field.json", json)));
            assertTrue(e.getMessage().contains("heals"), e.getMessage());
        }

        @Test
        @DisplayName("预设点了不存在的角色 / 人数与键对不上 / 没按座位升序，三样都抛")
        void brokenPresetsRejected() {
            String ghost = THREE_CHARACTERS.replace("\"2\": [\"jeweler\", \"mate\"]",
                    "\"2\": [\"jeweler\", \"ghost\"]");
            assertTrue(assertThrows(DataFormatException.class,
                    () -> RosterLoader.load(write("ghost.json", ghost))).getMessage().contains("ghost"));

            String miscount = THREE_CHARACTERS.replace("\"2\": [\"jeweler\", \"mate\"]",
                    "\"2\": [\"jeweler\"]");
            assertTrue(assertThrows(DataFormatException.class,
                    () -> RosterLoader.load(write("miscount.json", miscount))).getMessage().contains("2 人预设"));

            String unsorted = THREE_CHARACTERS.replace("\"2\": [\"jeweler\", \"mate\"]",
                    "\"2\": [\"mate\", \"jeweler\"]");
            assertTrue(assertThrows(DataFormatException.class,
                    () -> RosterLoader.load(write("unsorted.json", unsorted))).getMessage().contains("座位升序"));
        }

        @Test
        @DisplayName("schema 版本对不上就抛：字段含义变了，读出来的每个数都可能是错的")
        void schemaVersionChecked() {
            String json = THREE_CHARACTERS.replace("\"schema_version\": 1", "\"schema_version\": 2");
            DataFormatException e = assertThrows(DataFormatException.class,
                    () -> RosterLoader.load(write("v2.json", json)));
            assertTrue(e.getMessage().contains("schema_version"), e.getMessage());
        }

        @Test
        @DisplayName("对照：白名单确实放行「已知但不读」的 treasure_scoring，只拦真正陌生的字段")
        void knownButUnreadKeyPasses() {
            String withScoring = THREE_CHARACTERS.replace("\"presets\":",
                    "\"treasure_scoring\": {\"cash\": {\"kind\": \"face_value\", \"value\": 1}}, \"presets\":");
            assertEquals(3, RosterLoader.load(write("scoring.json", withScoring)).characters().size());

            String withJunk = THREE_CHARACTERS.replace("\"presets\":", "\"hate_table\": {}, \"presets\":");
            assertTrue(assertThrows(DataFormatException.class,
                    () -> RosterLoader.load(write("junk.json", withJunk))).getMessage().contains("hate_table"));
        }
    }

    // ------------------------------------------------------------------ 物资表

    @Nested
    @DisplayName("物资表")
    class ProvisionFile {

        private static final String THREE_PROVISIONS = """
                {
                  "schema_version": 1,
                  "id": "heavyseas:test",
                  "total": 6,
                  "cards": [
                    {"id": "water", "count": 3, "category": "consumable", "effect": {"kind": "prevent_thirst"}},
                    {"id": "rum", "count": 2, "category": "consumable", "effect": {"kind": "drink"}},
                    {"id": "parasol", "count": 1, "category": "equipment", "effect": {"kind": "cover"}}
                  ]
                }""";

        @Test
        @DisplayName("读出 id 全集")
        void loadsIds() {
            assertEquals(Set.of("water", "rum", "parasol"),
                    ProvisionLoader.loadIds(write("provisions.json", THREE_PROVISIONS)));
        }

        @Test
        @DisplayName("count 合计与 total 对不上就抛 —— 这条同时证明它真的逐张读了")
        void totalReconciled() {
            String json = THREE_PROVISIONS.replace("\"total\": 6", "\"total\": 7");
            DataFormatException e = assertThrows(DataFormatException.class,
                    () -> ProvisionLoader.loadIds(write("miscount.json", json)));
            assertTrue(e.getMessage().contains("7"), e.getMessage());
            assertTrue(e.getMessage().contains("6"), e.getMessage());
        }

        @Test
        @DisplayName("物资 id 重复就抛")
        void duplicateIdRejected() {
            String json = THREE_PROVISIONS.replace("\"id\": \"rum\"", "\"id\": \"water\"");
            assertTrue(assertThrows(DataFormatException.class,
                    () -> ProvisionLoader.loadIds(write("dup.json", json))).getMessage().contains("重复"));
        }
    }

    // ------------------------------------------------------------------ 航海牌

    @Nested
    @DisplayName("航海牌")
    class NavigationFile {

        private static final Set<CharacterId> CHARACTERS =
                Set.of(CharacterId.of("captain"), CharacterId.of("mate"), CharacterId.of("kid"));
        private static final Set<String> PROVISIONS = Set.of("water", "rum", "parasol");

        private static final String FIVE_CARDS = """
                {
                  "schema_version": 1,
                  "id": "heavyseas:test",
                  "total": 5,
                  "cards": [
                    {"id": "t_00", "gull": 0,
                     "overboard": {"mode": "list", "characters": ["captain"]},
                     "thirst": {"mode": "list", "characters": ["mate", "kid"]},
                     "thirst_rowers": true, "thirst_fighters": false},
                    {"id": "t_01", "gull": 1,
                     "overboard": {"mode": "all"},
                     "thirst": {"mode": "none"},
                     "thirst_rowers": false, "thirst_fighters": true},
                    {"id": "t_02", "gull": -1,
                     "overboard": {"mode": "except", "characters": ["mate"]},
                     "thirst": {"mode": "all"},
                     "thirst_rowers": false, "thirst_fighters": false},
                    {"id": "t_03", "gull": 0,
                     "overboard": {"mode": "conditional", "condition": "used_rum"},
                     "thirst": {"mode": "except", "characters": ["captain", "kid"]},
                     "thirst_rowers": true, "thirst_fighters": true},
                    {"id": "t_04", "gull": 0,
                     "overboard": {"mode": "none"},
                     "thirst": {"mode": "list", "characters": ["captain"]},
                     "thirst_rowers": false, "thirst_fighters": false}
                  ]
                }""";

        private List<NavigationCard> load(String name, String json) {
            return NavigationLoader.load(write(name, json), CHARACTERS, PROVISIONS);
        }

        @Test
        @DisplayName("五种点名模式全部读对，海鸥三种取值全部读对")
        void loadsAllModes() {
            List<NavigationCard> deck = load("nav.json", FIVE_CARDS);
            assertEquals(5, deck.size());

            assertEquals(new Selector.Only(Set.of(CharacterId.of("captain"))), deck.get(0).overboard());
            assertEquals(new Selector.Only(Set.of(CharacterId.of("mate"), CharacterId.of("kid"))),
                    deck.get(0).thirst());
            assertEquals(new Selector.Everyone(), deck.get(1).overboard());
            assertEquals(new Selector.Nobody(), deck.get(1).thirst());
            assertEquals(new Selector.Except(Set.of(CharacterId.of("mate"))), deck.get(2).overboard());
            assertEquals(new Selector.Conditional("used_rum"), deck.get(3).overboard());

            assertEquals(0, deck.get(0).gull());
            assertEquals(1, deck.get(1).gull());
            assertEquals(-1, deck.get(2).gull());
        }

        @Test
        @DisplayName("❗船桨与战斗两个图示不能读反 —— 调换了照样解析成功，只有逐张核对能发现")
        void rowersAndFightersNotSwapped() {
            List<NavigationCard> deck = load("nav.json", FIVE_CARDS);
            // t_00 只有船桨，t_01 只有战斗。两张一起看才分得出「读反了」与「读对了」。
            assertTrue(deck.get(0).thirstRowers(), "t_00 的船桨图示丢了或读到了战斗那一栏");
            assertTrue(!deck.get(0).thirstFighters(), "t_00 不该有战斗图示");
            assertTrue(!deck.get(1).thirstRowers(), "t_01 不该有船桨图示");
            assertTrue(deck.get(1).thirstFighters(), "t_01 的战斗图示丢了或读到了船桨那一栏");
        }

        @Test
        @DisplayName("点了角色表里没有的人就抛 —— 三种点名模式对陌生 id 全都不会自己报错")
        void unknownCharacterRejected() {
            String json = FIVE_CARDS.replace("[\"captain\"]", "[\"ghost\"]");
            DataFormatException e = assertThrows(DataFormatException.class, () -> load("ghost.json", json));
            assertTrue(e.getMessage().contains("ghost"), e.getMessage());
            assertTrue(e.getMessage().contains("cards[0]"), e.getMessage());
        }

        @Test
        @DisplayName("认不出的点名模式就抛")
        void unknownModeRejected() {
            String json = FIVE_CARDS.replace("\"mode\": \"all\"", "\"mode\": \"everyone\"");
            assertTrue(assertThrows(DataFormatException.class,
                    () -> load("mode.json", json)).getMessage().contains("everyone"));
        }

        @Test
        @DisplayName("空名单要求写成 none —— 否则「不点人」与「名单导丢了」长得一样")
        void emptyListRejected() {
            String json = FIVE_CARDS.replace("\"characters\": [\"captain\"]", "\"characters\": []");
            assertTrue(assertThrows(DataFormatException.class,
                    () -> load("empty.json", json)).getMessage().contains("none"));
        }

        @Test
        @DisplayName("条件只认 used_<物资 id>：文法不对、或指向不存在的物资，都抛")
        void conditionVocabularyClosed() {
            String wrongGrammar = FIVE_CARDS.replace("\"used_rum\"", "\"drank_something\"");
            assertTrue(assertThrows(DataFormatException.class,
                    () -> load("grammar.json", wrongGrammar)).getMessage().contains("used_"));

            String ghostItem = FIVE_CARDS.replace("\"used_rum\"", "\"used_ghost_item\"");
            assertTrue(assertThrows(DataFormatException.class,
                    () -> load("ghost-item.json", ghostItem)).getMessage().contains("ghost_item"));
        }

        @Test
        @DisplayName("模式与字段必须配套：list 带 condition、conditional 带 characters，都抛")
        void modeFieldMismatchRejected() {
            String listWithCondition = FIVE_CARDS.replace(
                    "{\"mode\": \"list\", \"characters\": [\"captain\"]}",
                    "{\"mode\": \"list\", \"characters\": [\"captain\"], \"condition\": \"used_rum\"}");
            assertTrue(assertThrows(DataFormatException.class,
                    () -> load("mix1.json", listWithCondition)).getMessage().contains("condition"));

            String conditionalWithNames = FIVE_CARDS.replace(
                    "{\"mode\": \"conditional\", \"condition\": \"used_rum\"}",
                    "{\"mode\": \"conditional\", \"condition\": \"used_rum\", \"characters\": [\"mate\"]}");
            assertTrue(assertThrows(DataFormatException.class,
                    () -> load("mix2.json", conditionalWithNames)).getMessage().contains("characters"));
        }

        @Test
        @DisplayName("牌 id 重复、张数与 total 对不上，都抛")
        void bookkeepingChecked() {
            String duplicate = FIVE_CARDS.replace("\"id\": \"t_04\"", "\"id\": \"t_00\"");
            assertTrue(assertThrows(DataFormatException.class,
                    () -> load("dup.json", duplicate)).getMessage().contains("重复"));

            String miscount = FIVE_CARDS.replace("\"total\": 5", "\"total\": 31");
            assertTrue(assertThrows(DataFormatException.class,
                    () -> load("miscount.json", miscount)).getMessage().contains("31"));
        }

        @Test
        @DisplayName("牌上多一个引擎不认识的字段就抛 —— M5 加天候字段时这条会先红")
        void unknownCardFieldRejected() {
            String json = FIVE_CARDS.replace("\"gull\": 1,", "\"gull\": 1, \"weather\": \"squall\",");
            assertTrue(assertThrows(DataFormatException.class,
                    () -> load("weather.json", json)).getMessage().contains("weather"));
        }

        @Test
        @DisplayName("海鸥越界由 NavigationCard 自己拦，加载器补上是哪张牌")
        void gullRangeChecked() {
            String json = FIVE_CARDS.replace("\"gull\": 1", "\"gull\": 2");
            DataFormatException e = assertThrows(DataFormatException.class, () -> load("gull.json", json));
            assertTrue(e.getMessage().contains("cards[1]"), e.getMessage());
            assertTrue(e.getMessage().contains("海鸥"), e.getMessage());
        }

        @Test
        @DisplayName("空角色全集拒绝加载 —— 那会让点名校验变成一句废话")
        void emptyUniverseRejected() {
            Path file = write("nav.json", FIVE_CARDS);
            assertThrows(IllegalArgumentException.class,
                    () -> NavigationLoader.load(file, Set.of(), PROVISIONS));
        }

        @Test
        @DisplayName("不是 JSON、或顶层不是对象，报的是文件名而不是一句「解析失败」")
        void brokenFileReportsPath() {
            DataFormatException e = assertThrows(DataFormatException.class,
                    () -> load("broken.json", "[1, 2, 3]"));
            assertTrue(e.getMessage().contains("broken.json"), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 字符流入口

    @Nested
    @DisplayName("从字符流读（数据包里的资源没有文件路径）")
    class ReaderEntry {

        @Test
        @DisplayName("同一份数据，从字符流读与从文件读，结果一模一样")
        void readerMatchesFile() {
            assertEquals(RosterLoader.load(write("roster.json", RosterFile.THREE_CHARACTERS)),
                    RosterLoader.load("heavyseas:roster/test", new StringReader(RosterFile.THREE_CHARACTERS)));
            assertEquals(ProvisionLoader.loadIds(write("provisions.json", ProvisionFile.THREE_PROVISIONS)),
                    ProvisionLoader.loadIds("heavyseas:provisions/test",
                            new StringReader(ProvisionFile.THREE_PROVISIONS)));
            assertEquals(
                    NavigationLoader.load(write("nav.json", NavigationFile.FIVE_CARDS),
                            NavigationFile.CHARACTERS, NavigationFile.PROVISIONS),
                    NavigationLoader.load("heavyseas:navigation/test", new StringReader(NavigationFile.FIVE_CARDS),
                            NavigationFile.CHARACTERS, NavigationFile.PROVISIONS));
        }

        @Test
        @DisplayName("出错时报调用方给的来源名 —— 数据包里出错，人要知道是哪个资源")
        void errorsNameTheSource() {
            String broken = RosterFile.THREE_CHARACTERS.replace("none", "teleport");
            DataFormatException e = assertThrows(DataFormatException.class,
                    () -> RosterLoader.load("heavyseas:roster/broken", new StringReader(broken)));
            assertTrue(e.getMessage().contains("heavyseas:roster/broken"), e.getMessage());
            assertTrue(e.getMessage().contains("characters[1]"), e.getMessage());
        }

        @Test
        @DisplayName("❗流读到一半坏了，要报成「读不了」而不是「不是合法 JSON」—— 两者要改的地方完全不同")
        void readFailureIsNotReportedAsBadJson() {
            Reader failing = new Reader() {
                @Override
                public int read(char[] buffer, int offset, int length) throws IOException {
                    throw new IOException("读到一半断了");
                }

                @Override
                public void close() {
                }
            };
            DataFormatException e = assertThrows(DataFormatException.class,
                    () -> ProvisionLoader.loadIds("heavyseas:provisions/flaky", failing));
            assertTrue(e.getMessage().contains("读不了"), e.getMessage());
            assertTrue(e.getMessage().contains("heavyseas:provisions/flaky"), e.getMessage());
            assertInstanceOf(IOException.class, e.getCause());
        }

        @Test
        @DisplayName("来源名为空就拒绝 —— 数据出错时，它是唯一能说明「是哪份数据」的东西")
        void blankSourceRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> RosterLoader.load(" ", new StringReader(RosterFile.THREE_CHARACTERS)));
        }
    }
}
