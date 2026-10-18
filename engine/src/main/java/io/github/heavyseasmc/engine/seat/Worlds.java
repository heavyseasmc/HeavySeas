package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.model.Affinities;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.state.SurvivorState;
import io.github.heavyseasmc.engine.thirst.ThirstTally;
import io.github.heavyseasmc.engine.weather.WeatherCard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * 照一个座位的视角，抽一局「可能的局面」：看得见的原样照搬，看不见的按这个座位知道的重抽一遍。
 *
 * <h2>只从视角拼，不碰那一局</h2>
 * 往前推演的替身要在「可能的局面」上把一个决定做下去、往后推几天看结果。那份局面<b>只能</b>从它自己的视角（加上公开的目录）拼：
 * 从真的那一局复制一份再改，哪一样忘了重抽，哪一样就漏了出去 —— 而漏出去的东西在几千局里不报错，只会让它「准」得不像话。
 * 所以这里的参数只有 {@link SeatView} 与一条随机流。
 *
 * <h2>重抽哪几样</h2>
 * <ul>
 *   <li><b>别人手里的牌、物资牌堆、补给箱里我看不见的那几张、随落水的人沉下去的那几张</b>：
 *       整副物资减去我看得见的（我的手牌、每个人面前、弃牌堆、我拿着的补给箱），剩下的洗匀了按张数分下去；</li>
 *   <li><b>别人押下的暗牌</b>：张数是公开的，所以先替他定下是哪几张武器（面前的武器，或者从剩下的牌里挑一张放进他手里），
 *       保证他真的押得出来；</li>
 *   <li><b>航海牌堆次序、划船堆、别人划船摸到的牌</b>：整副航海牌减去我看得见的（我划船摸到的、我当舵手看到的划船堆），洗匀了按张数放回去；</li>
 *   <li><b>天候牌堆次序</b>：还没翻的那几张洗匀；以后洗牌用的随机流也重新给一条；</li>
 *   <li><b>别人的爱恨</b>：我自己那两张照旧，别人的按「爱、恨各是一个置换」重新抽。</li>
 * </ul>
 * 抽法是「均匀地猜」：不从别人的举动里推断他手里有什么、他爱谁 —— 那是可以往上加的，但不会漏。
 *
 * <p>拼完由 {@link Session#rebuild} 对账：牌一张不少、状态自洽，不对就抛。
 */
public final class Worlds {

    private Worlds() {
    }

    /** 照 {@code view} 抽一局。同一个视角、同一条随机流（同一个种子）抽出来的局一模一样。 */
    public static Session sample(SeatView view, Random rng) {
        CharacterId me = view.self();
        List<SeatView.SeatInfo> seats = view.seats();

        // ---------------- 物资：没见过的牌洗匀了分下去
        List<String> pool = new ArrayList<>(view.catalog().deck());
        removeAll(pool, view.hand());
        for (SeatView.SeatInfo s : seats) {
            removeAll(pool, s.front());
        }
        removeAll(pool, view.table().discard());
        boolean holdingBox = view.box().map(b -> b.holder().equals(me)).orElse(false);
        if (holdingBox) {
            removeAll(pool, view.box().orElseThrow().offer());
        }
        Collections.shuffle(pool, rng);

        // 别人押下的暗牌：先替每个人定下是哪几张，保证他押得出来。
        // ❗只在押武器那一段：结算之后（挑牌那一步）押下的牌已经亮出来、信号枪已经弃了，那份记录只是旧账
        Map<CharacterId, List<String>> committed = new LinkedHashMap<>();
        Map<CharacterId, List<String>> reserved = new LinkedHashMap<>();
        view.contest().filter(c -> c.stage() == Contest.Stage.WEAPONS).ifPresent(c -> {
            // 先数一遍：每个人押了几张、面前有几张武器。面前不够的那几张只能出自没见过的武器 ——
            // 「非得从手里出」的总数（forced）要一直留得出来：前面的人若拿走了最后一张，后面的人就押不出来了。
            // （2026-10-07 实测：两人押牌、没见过的武器只剩一把鱼叉，排在前面的人面前有船桨也去「手里」拿了鱼叉，
            //  轮到排在后面、面前什么也没有的人时当场抛。）
            int poolWeapons = (int) pool.stream().filter(x -> view.catalog().get(x).weaponPower() > 0).count();
            int forced = 0;
            Map<CharacterId, List<String>> fronts = new LinkedHashMap<>();
            for (SeatView.SeatInfo s : seats) {
                if (s.id().equals(me) || c.committed(s.id()) == 0) {
                    continue;
                }
                List<String> front = new ArrayList<>();
                for (String card : s.front()) {
                    if (view.catalog().get(card).weaponPower() > 0) {
                        front.add(card);
                    }
                }
                fronts.put(s.id(), front);
                forced += Math.max(0, c.committed(s.id()) - front.size());
            }
            for (SeatView.SeatInfo s : seats) {
                if (s.id().equals(me)) {
                    if (!c.mine().isEmpty()) {
                        committed.put(me, c.mine());
                    }
                    continue;
                }
                List<String> front = fronts.get(s.id());
                if (front == null) {
                    continue;
                }
                List<String> mine = new ArrayList<>();
                List<String> hand = new ArrayList<>();
                for (int left = c.committed(s.id()); left > 0; left--) {
                    int handLeft = s.handCount() - hand.size();
                    boolean mustFromPool = left > front.size();         // 面前的不够这一张以后的
                    boolean poolOk = handLeft > 0 && poolWeapons > 0 && (mustFromPool || poolWeapons > forced);
                    double fromHand = poolOk ? handLeft * (double) poolWeapons / Math.max(1, pool.size()) : 0;
                    if (!front.isEmpty() && rng.nextDouble() * (front.size() + fromHand) < front.size()) {
                        mine.add(front.remove(rng.nextInt(front.size())));
                    } else if (poolOk) {
                        String w = takeWeapon(pool, view, rng);
                        hand.add(w);
                        mine.add(w);
                        poolWeapons--;
                        if (mustFromPool) {
                            forced--;
                        }
                    } else {
                        throw new IllegalStateException(s.id() + " 押了 " + c.committed(s.id())
                                + " 张，可他身上凑不出那么多武器 —— 视角自相矛盾");
                    }
                }
                committed.put(s.id(), List.copyOf(mine));
                reserved.put(s.id(), hand);
            }
        });

        Map<CharacterId, SurvivorState> states = new LinkedHashMap<>();
        Set<CharacterId> removed = new LinkedHashSet<>();
        Set<CharacterId> offline = new LinkedHashSet<>();
        for (SeatView.SeatInfo s : seats) {
            List<String> hand;
            if (s.id().equals(me)) {
                hand = view.hand();
            } else {
                hand = new ArrayList<>(reserved.getOrDefault(s.id(), List.of()));
                while (hand.size() < s.handCount()) {
                    hand.add(take(pool));
                }
            }
            states.put(s.id(), new SurvivorState(s.id(), s.seat(), s.damage(), new ThirstTally(s.thirst()), s.acted(),
                    hand, s.front(), s.opened(), s.used()));
            if (s.removed()) {
                removed.add(s.id());
            }
            if (s.offline()) {
                offline.add(s.id());
            }
        }
        List<String> offer = new ArrayList<>();
        if (view.box().isPresent()) {
            if (holdingBox) {
                offer.addAll(view.box().orElseThrow().offer());
            } else {
                for (int i = 0; i < view.box().orElseThrow().offerSize(); i++) {
                    offer.add(take(pool));
                }
            }
        }
        List<String> sunk = new ArrayList<>();
        for (int i = 0; i < view.table().sunk(); i++) {
            sunk.add(take(pool));
        }
        if (pool.size() != view.table().provisionsLeft()) {
            throw new IllegalStateException("没见过的牌剩 %d 张，牌堆却是 %d 张 —— 视角里的张数对不上"
                    .formatted(pool.size(), view.table().provisionsLeft()));
        }
        List<String> provisionPile = pool;                      // 已经洗过

        // ---------------- 航海牌：没见过的洗匀了放回去
        List<NavigationCard> unknownNav = new ArrayList<>(view.navDeck());
        List<NavigationCard> rowerHand = new ArrayList<>();
        Optional<SeatView.RowingInfo> rowing = view.rowing();
        boolean myRow = rowing.map(r -> r.rower().equals(me)).orElse(false);
        if (myRow) {
            rowerHand.addAll(rowing.orElseThrow().drawn());
            removeAllCards(unknownNav, rowerHand);
        }
        List<NavigationCard> rowStack = new ArrayList<>();
        boolean seeStack = !view.helmOptions().isEmpty();
        if (seeStack) {
            rowStack.addAll(view.helmOptions());
            removeAllCards(unknownNav, rowStack);
        }
        Collections.shuffle(unknownNav, rng);
        if (rowing.isPresent() && !myRow) {
            for (int i = 0; i < rowing.orElseThrow().drawnCount(); i++) {
                rowerHand.add(unknownNav.removeLast());
            }
        }
        if (!seeStack) {
            for (int i = 0; i < view.table().rowStack(); i++) {
                rowStack.add(unknownNav.removeLast());
            }
        }
        if (unknownNav.size() != view.table().navPile()) {
            throw new IllegalStateException("航海牌剩 %d 张，牌堆却是 %d 张 —— 视角里的张数对不上"
                    .formatted(unknownNav.size(), view.table().navPile()));
        }

        // ---------------- 天候：还没翻的那几张洗匀
        Optional<Session.WeatherParts> weather = Optional.empty();
        if (!view.weatherCards().isEmpty()) {
            List<WeatherCard> upcoming = new ArrayList<>(view.weatherCards());
            for (WeatherCard d : view.weatherDiscard()) {
                upcoming.remove(d);
            }
            view.weather().ifPresent(upcoming::remove);
            Collections.shuffle(upcoming, rng);
            weather = Optional.of(new Session.WeatherParts(upcoming, view.weatherDiscard(), view.weather(),
                    rng.nextLong()));
        }

        // ---------------- 别人的爱恨：我那两张照旧，其余按置换重抽
        Affinities affinities = affinities(view, rng);

        // ---------------- 进行中的这一场
        Optional<Contest> contest = view.contest().map(c -> {
            Optional<Fight> fight = Optional.empty();
            if (c.stage() == Contest.Stage.STANCES || c.stage() == Contest.Stage.WEAPONS) {
                Fight f = Fight.between(c.attacker(), c.target());
                for (CharacterId h : c.attackSide().subList(1, c.attackSide().size())) {
                    f = f.join(h, Fight.Side.ATTACK);
                }
                for (CharacterId h : c.defendSide().subList(1, c.defendSide().size())) {
                    f = f.join(h, Fight.Side.DEFEND);
                }
                fight = Optional.of(f);
            }
            return new Contest(c.kind(), c.attacker(), c.target(), c.stage(), fight, c.handOnly(), committed,
                    c.rationCard(), c.passed());
        });

        GameState state = GameState.assemble(view.roster(), states, view.phase(), view.turn(), view.gulls(), removed,
                offline);
        return Session.rebuild(new Session.Blueprint("推演 " + me.value() + " 第 " + view.turn() + " 天", state,
                view.catalog(), unknownNav, rowStack, rowerHand, provisionPile, view.table().discard(), sunk, weather,
                offer, rowing.map(SeatView.RowingInfo::rower), contest, view.progress(), affinities, view.deeds()));
    }

    /** 我自己的爱恨照旧；别人的按「爱、恨各是一个置换」均匀重抽。还没发爱恨时两张表都整个抽。 */
    static Affinities affinities(SeatView view, Random rng) {
        List<CharacterId> ids = new ArrayList<>();
        for (Survivor s : view.roster().survivors()) {
            ids.add(s.id());
        }
        return new Affinities(permutation(ids, view.self(), view.love(), rng),
                permutation(ids, view.self(), view.hate(), rng));
    }

    private static Map<CharacterId, CharacterId> permutation(List<CharacterId> ids, CharacterId me,
                                                             Optional<CharacterId> mine, Random rng) {
        List<CharacterId> rest = new ArrayList<>(ids);
        mine.ifPresent(rest::remove);
        Collections.shuffle(rest, rng);
        Map<CharacterId, CharacterId> out = new LinkedHashMap<>();
        int k = 0;
        for (CharacterId id : ids) {
            out.put(id, id.equals(me) && mine.isPresent() ? mine.get() : rest.get(k++));
        }
        return out;
    }

    private static String take(List<String> pool) {
        if (pool.isEmpty()) {
            throw new IllegalStateException("没见过的牌不够分 —— 视角里的张数对不上");
        }
        return pool.removeLast();
    }

    /** 从没见过的牌里随机挑一张武器拿出来。 */
    private static String takeWeapon(List<String> pool, SeatView view, Random rng) {
        List<Integer> at = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            if (view.catalog().get(pool.get(i)).weaponPower() > 0) {
                at.add(i);
            }
        }
        return pool.remove((int) at.get(rng.nextInt(at.size())));
    }

    private static void removeAll(List<String> from, List<String> cards) {
        for (String c : cards) {
            if (!from.remove(c)) {
                throw new IllegalStateException("视角里看得见的 " + c + " 在整副物资里多出来了 —— 张数对不上");
            }
        }
    }

    private static void removeAllCards(List<NavigationCard> from, List<NavigationCard> cards) {
        for (NavigationCard c : cards) {
            if (!from.remove(c)) {
                throw new IllegalStateException("视角里看得见的航海牌 " + c.id() + " 不在整副牌里");
            }
        }
    }
}
