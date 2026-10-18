package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.data.NavigationLoader;
import io.github.heavyseasmc.engine.data.ProvisionLoader;
import io.github.heavyseasmc.engine.data.RosterData;
import io.github.heavyseasmc.engine.data.RosterLoader;
import io.github.heavyseasmc.engine.data.WeatherLoader;
import io.github.heavyseasmc.engine.model.Affinities;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.NavigationDeck;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.play.Table;
import io.github.heavyseasmc.engine.seat.Legal;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.data.GameData;
import io.github.heavyseasmc.mod.llm.ChoicePrompt;
import io.github.heavyseasmc.mod.llm.ChoiceRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 大模型替身的问题（{@link SeatQuestions}）只从这一座看得见的东西拼出来。
 *
 * <h2>怎么证明「看不见的没漏进去」</h2>
 * 两局「双胞胎」：船长自己的牌、自己的爱恨、桌上公开的一切都一样；别人的手牌、别人的爱恨、两副牌堆的次序都不一样。
 * 同一个决定在两局里拼出来的请求（连同真正发出去的提示词）必须<b>一个字节都不差</b>。
 * 两条正向对照：双胞胎在看不见的那几项上真的不同（{@link #assertHiddenDiffers}）；看得见的一变，请求就跟着变 ——
 * 否则「一样」可能只是因为什么都没拼进去。红测（往 {@link SeatQuestions.Snapshot#take} 里漏一样东西）红在
 * {@link #hiddenInfoNeverReachesTheQuestion} 那一行。
 *
 * <p>用的是 {@code data/} 里的真数据（真阵容、真航海牌、真物资），名字从 jar 里那份 lang 取。
 */
class SeatQuestionsTest {

    private static final Path DATA = Path.of("..", "data");
    private static final CharacterId CAPTAIN = CharacterId.of("captain");
    private static final CharacterId MATE = CharacterId.of("mate");
    private static final CharacterId KID = CharacterId.of("kid");
    private static final Instant DEADLINE = Instant.parse("2026-10-07T12:00:00Z");
    private static final SeatQuestions.Names ZH = SeatQuestions.Names.of("zh_cn");
    private static final SeatQuestions.Names EN = SeatQuestions.Names.of("en_us");

    private static GameData data;

    private static synchronized GameData data() {
        if (data == null) {
            RosterData roster = RosterLoader.load(DATA.resolve("roster").resolve("default.json"));
            Path provisions = DATA.resolve("provisions").resolve("default.json");
            List<NavigationCard> navigation = NavigationLoader.load(DATA.resolve("navigation").resolve("default.json"),
                    roster.ids(), ProvisionLoader.loadIds(provisions));
            data = new GameData("test", roster, ProvisionLoader.loadCatalog(provisions), navigation,
                    WeatherLoader.load(DATA.resolve("weather").resolve("default.json")));
        }
        return data;
    }

    /** 船长的一局：他自己的两张牌与爱恨固定；别人的手牌、别人的爱恨、两副牌堆的次序由 {@code seed} 定。 */
    private static Session twin(long seed, List<String> mateHand, List<String> kidHand) {
        GameData d = data();
        Roster roster = d.roster().preset(8);
        Random rng = new Random(seed);
        Session s = new Session("twin", roster,
                new Table(new NavigationDeck(d.navigation(), rng), d.provisions(), rng));
        s.dealAffinities(new Affinities(permutation(roster, CAPTAIN, MATE, new Random(seed * 31)),
                permutation(roster, CAPTAIN, KID, new Random(seed * 37))));
        deal(s, CAPTAIN, List.of("water", "knife"));
        deal(s, MATE, mateHand);
        deal(s, KID, kidHand);
        while (s.state().phase() != Phase.ACTION) {
            s.advancePhase();
        }
        return s;
    }

    private static void deal(Session s, CharacterId who, List<String> cards) {
        cards.forEach(card -> s.dealFromPile(who, card));
    }

    /** {@code who} 的那一张固定指向 {@code target}，其余人随机（仍是一个置换）。 */
    private static Map<CharacterId, CharacterId> permutation(Roster roster, CharacterId who, CharacterId target,
                                                             Random rng) {
        List<CharacterId> ids = roster.survivors().stream().map(Survivor::id).toList();
        List<CharacterId> rest = new ArrayList<>(ids);
        rest.remove(target);
        Collections.shuffle(rest, rng);
        Map<CharacterId, CharacterId> out = new LinkedHashMap<>();
        int k = 0;
        for (CharacterId id : ids) {
            out.put(id, id.equals(who) ? target : rest.get(k++));
        }
        return out;
    }

    /** 正向对照：两局在看不见的那几项上真的不同，否则「问题一样」什么也证明不了。 */
    private static void assertHiddenDiffers(Session a, Session b) {
        assertNotEquals(a.state().stateOf(MATE).hand(), b.state().stateOf(MATE).hand(), "大副的手牌一样 —— 双胞胎没搭对");
        assertNotEquals(a.state().stateOf(KID).hand(), b.state().stateOf(KID).hand(), "小孩的手牌一样");
        assertEquals(a.state().stateOf(MATE).hand().size(), b.state().stateOf(MATE).hand().size(),
                "大副的手牌张数是公开的，双胞胎在这一项上应当一样");
        assertNotEquals(a.affinities().orElseThrow().love(), b.affinities().orElseThrow().love(), "别人的爱一样");
        assertNotEquals(a.affinities().orElseThrow().hate(), b.affinities().orElseThrow().hate(), "别人的恨一样");
        assertEquals(a.affinities().orElseThrow().loveOf(CAPTAIN), b.affinities().orElseThrow().loveOf(CAPTAIN));
        assertEquals(a.affinities().orElseThrow().hateOf(CAPTAIN), b.affinities().orElseThrow().hateOf(CAPTAIN));
        assertNotEquals(a.table().pile().order(), b.table().pile().order(), "航海牌堆次序一样");
        assertNotEquals(a.table().provisionPileOrder(), b.table().provisionPileOrder(), "物资牌堆次序一样");
    }

    /** 船长这一座的几种决定：每一种从照相开始走一遍，拼成请求。 */
    private static Map<String, Function<Session, ChoiceRequest>> questions() {
        List<NavigationCard> nav = data().navigation();
        Map<String, Function<Session, ChoiceRequest>> out = new LinkedHashMap<>();
        out.put("行动", s -> {
            SeatQuestions.Snapshot<List<io.github.heavyseasmc.engine.seat.ActionChoice>> snap =
                    SeatQuestions.Snapshot.take(s, CAPTAIN, Legal.actions(s, CAPTAIN), ZH);
            return SeatQuestions.action(snap.view(), snap.legal(), ZH).request(snap.seatLabel(), DEADLINE);
        });
        out.put("行动（英文）", s -> {
            var snap = SeatQuestions.Snapshot.take(s, CAPTAIN, Legal.actions(s, CAPTAIN), EN);
            return SeatQuestions.action(snap.view(), snap.legal(), EN).request(snap.seatLabel(), DEADLINE);
        });
        out.put("补给箱", s -> {
            var snap = SeatQuestions.Snapshot.take(s, CAPTAIN, List.of("water", "cash", "water"), ZH);
            return SeatQuestions.provision(snap.view(), snap.legal(), ZH).request(snap.seatLabel(), DEADLINE);
        });
        out.put("划船留牌", s -> {
            var snap = SeatQuestions.Snapshot.take(s, CAPTAIN, nav.subList(0, 2), ZH);
            return SeatQuestions.row(snap.view(), snap.legal(), ZH).request(snap.seatLabel(), DEADLINE);
        });
        out.put("舵手挑牌", s -> {
            var snap = SeatQuestions.Snapshot.take(s, CAPTAIN, nav.subList(2, 5), ZH);
            return SeatQuestions.helm(snap.view(), snap.legal(), ZH).request(snap.seatLabel(), DEADLINE);
        });
        out.put("押武器", s -> {
            var snap = SeatQuestions.Snapshot.take(s, CAPTAIN, List.of("knife"), ZH);
            return SeatQuestions.weapons(snap.view(), snap.legal(), ZH).request(snap.seatLabel(), DEADLINE);
        });
        out.put("口渴", s -> {
            var snap = SeatQuestions.Snapshot.take(s, CAPTAIN, 1, ZH);
            return SeatQuestions.thirst(snap.view(), snap.legal(), ZH).request(snap.seatLabel(), DEADLINE);
        });
        return out;
    }

    private static ChoicePrompt.Messages prompt(ChoiceRequest r) {
        return ChoicePrompt.build(r, "（规则摘录）", r.options().getFirst().matches(".*[A-Za-z].*") ? "en_us" : "zh_cn");
    }

    @Test
    @DisplayName("看不见的变了（别人的手牌 · 别人的爱恨 · 牌堆次序）：每一种决定拼出来的请求与提示词一个字节都不差")
    void hiddenInfoNeverReachesTheQuestion() {
        Session a = twin(1, List.of("water", "cash"), List.of("rum"));
        Session b = twin(2, List.of("flail", "oar"), List.of("water"));
        assertHiddenDiffers(a, b);
        for (var q : questions().entrySet()) {
            ChoiceRequest ra = q.getValue().apply(a);
            ChoiceRequest rb = q.getValue().apply(b);
            assertEquals(ra, rb, q.getKey() + "：看不见的变了，请求跟着变了 —— 有东西漏进了问题");
            assertEquals(prompt(ra), prompt(rb), q.getKey() + "：提示词不一样");
        }
    }

    @Test
    @DisplayName("当着他的面被抢（这一场的表态）：别人的牌与爱恨照样漏不进来")
    void hiddenInfoNeverReachesTheContestQuestion() {
        Session a = twin(3, List.of("water", "cash"), List.of("rum"));
        Session b = twin(4, List.of("flail", "oar"), List.of("water"));
        assertHiddenDiffers(a, b);
        for (Session s : List.of(a, b)) {
            while (!s.nextActor().orElseThrow().equals(MATE)) {
                s.markActed(s.nextActor().orElseThrow());
            }
            s.declare(MATE, Contest.Kind.STEAL, CAPTAIN);
        }
        var sa = SeatQuestions.Snapshot.take(a, CAPTAIN, null, ZH);
        var sb = SeatQuestions.Snapshot.take(b, CAPTAIN, null, ZH);
        ChoiceRequest ra = SeatQuestions.consent(sa.view(), ZH).request(sa.seatLabel(), DEADLINE);
        ChoiceRequest rb = SeatQuestions.consent(sb.view(), ZH).request(sb.seatLabel(), DEADLINE);
        assertTrue(ra.situation().contains("这一场"), "局面里没有这一场：表态的问题拼错了地方\n" + ra.situation());
        assertEquals(ra, rb, "表态：看不见的变了，请求跟着变了");
    }

    @Test
    @DisplayName("正向对照：看得见的一变（大副亮出一张），请求就跟着变；局面里有船长自己的牌与爱恨")
    void visibleChangesDoReachTheQuestion() {
        Session a = twin(1, List.of("water", "cash"), List.of("rum"));
        Session c = twin(1, List.of("water", "cash"), List.of("rum"));
        c.reveal(MATE, "cash");
        Function<Session, ChoiceRequest> action = questions().get("行动");
        ChoiceRequest ra = action.apply(a);
        assertNotEquals(ra, action.apply(c), "大副当众亮了一张，船长的问题却一点没变：比较的是个常数");
        String situation = ra.situation();
        assertTrue(situation.contains(ZH.provision("water")) && situation.contains(ZH.provision("knife")),
                "局面里没有船长自己的手牌\n" + situation);
        assertTrue(situation.contains("爱 " + ZH.character(MATE)) && situation.contains("恨 " + ZH.character(KID)),
                "局面里没有船长自己的爱恨\n" + situation);
        assertEquals(ZH.character(CAPTAIN), ra.seat());
    }

    @Test
    @DisplayName("同一个局面拼两次一模一样；选项与合法清单一一对应；名字与航海牌都译成了字（没有剩下键名或 %s）")
    void deterministicAndFullyRendered() {
        Session a = twin(5, List.of("water", "cash"), List.of("rum"));
        for (var q : questions().entrySet()) {
            ChoiceRequest first = q.getValue().apply(a);
            assertEquals(first, q.getValue().apply(a), q.getKey() + "：同一个局面拼了两种问题");
            for (String text : allText(first)) {
                assertFalse(text.contains("heavyseas."), q.getKey() + "：有没译出来的键名：" + text);
                assertFalse(text.contains("%s") || text.contains("%1$s"), q.getKey() + "：有没填上的参数：" + text);
            }
        }
        var snap = SeatQuestions.Snapshot.take(a, CAPTAIN, Legal.actions(a, CAPTAIN), ZH);
        assertEquals(snap.legal(), SeatQuestions.action(snap.view(), snap.legal(), ZH).choices(),
                "选项的次序与合法清单不一样：挑中第几项就会做成别的事");
    }

    private static List<String> allText(ChoiceRequest r) {
        List<String> out = new ArrayList<>(r.options());
        out.add(r.situation());
        out.add(r.seat());
        return out;
    }
}
