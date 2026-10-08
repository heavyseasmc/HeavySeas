package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.Affinities;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Deed;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.engine.state.SurvivorState;
import io.github.heavyseasmc.engine.thirst.ThirstSource;
import io.github.heavyseasmc.engine.thirst.ThirstTally;
import io.github.heavyseasmc.engine.weather.WeatherCard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * <b>一个座位知道的全部</b>：自己的爱恨与手牌，加上全船都看得见的东西。席位策略只拿得到它。
 *
 * <h2>为什么要有它</h2>
 * 原先唯一可替换的策略（{@code NavigationPolicy}）拿到的是整份 {@link GameState} —— 里面有每个人的手牌，
 * 注释写着「只能用自己的视角」，可没有任何东西拦着。替身要做真决定（抢谁、站哪边、押不押武器），
 * 偷看一眼别人手牌就是作弊，而作弊在几千局里不会报错，只会让它「聪明」得不像话。
 *
 * <p>所以视角在<b>造出来的那一刻</b>就已经裁剪过：别人的手牌只剩张数（{@link #HIDDEN} 占位），
 * 别人的爱恨根本不在里面，别人押下的武器只剩张数。策略拿不到 {@link Session}，也就够不着那些。
 *
 * <h2>看得见什么</h2>
 * 照规则第五章「别人看得见你什么」：体力、面前的每一张、手里有几张；外加公开的过程 ——
 * 谁行动过、谁划过船、谁打过架、谁喝过酒（口渴标记）、海鸥、天候、补给箱传到第几位、
 * 这一场谁站哪边、押了几张、落海的是谁、谁在结算口渴，以及公开记事（{@link Deed}）。
 *
 * <h2>只有自己看得见的，只在该看的时候给</h2>
 * <ul>
 *   <li>补给箱里的牌：只有拿着箱子的人；</li>
 *   <li>划船摸到的牌：只有划船的人；</li>
 *   <li>划船堆：只有舵手，而且只在他要挑牌的时候；</li>
 *   <li>押下的是哪几张：只有押的人自己那几张。</li>
 * </ul>
 *
 * <h2>❗它是一张快照：造完就与那一局无关</h2>
 * 视角里的每一样都是造的那一刻<b>抄下来</b>的不可变值（记录、{@code List.copyOf}、不可变的目录与阵容），
 * 不持有 {@link Session}、桌面、牌堆，也不持有任何会变的集合。所以：
 * <ul>
 *   <li>{@link #of} 要在<b>拥有那一局的线程</b>上调（模组里是服务端线程）—— 它读 {@code Session}；</li>
 *   <li>造出来的视角可以交给任何线程（替身在工作线程上想），那一局之后怎么变都与它无关；</li>
 *   <li>策略手里只有视角与合法选项（也是不可变的），够不着、也改不了那一局。</li>
 * </ul>
 * {@code SeatViewTest} 钉着这三条：造完之后把那一局接着打下去，视角一个字不变；顺着视角的对象图走一遍，
 * 碰不到 {@code Session} 与可变集合；工作线程上的决定与当场算的一模一样。
 */
public final class SeatView {

    /** 别人手里那一张在视角里的样子：只知道「有一张」。故意不是任何物资 id —— 拿它去查效果会当场抛。 */
    public static final String HIDDEN = "?";

    private final CharacterId self;
    private final Optional<CharacterId> love;
    private final Optional<CharacterId> hate;
    private final Roster roster;
    private final Provisions catalog;
    private final int turn;
    private final Phase phase;
    private final int gulls;
    private final Optional<WeatherCard> weather;
    private final List<SeatInfo> seats;
    private final List<String> hand;
    private final List<Deed> deeds;
    private final TableInfo table;
    private final Optional<ProvisionBox> box;
    private final Optional<RowingInfo> rowing;
    private final Optional<ContestInfo> contest;
    private final List<NavigationCard> helmOptions;
    private final Optional<NavigationCard> navigated;
    private final boolean navigationDone;
    private final Optional<OverboardInfo> overboard;
    private final Optional<ThirstInfo> thirst;
    private final List<NavigationCard> navDeck;
    private final Session.Progress progress;
    private final List<WeatherCard> weatherCards;
    private final List<WeatherCard> weatherDiscard;

    /**
     * 裁剪过的状态，第一次要时才拼（只给还按 {@link GameState} 写的老策略用）。
     * 只从视角自己的不可变字段拼，与那一局无关；{@code volatile}：几个线程同时要时最多各拼一份，拼出来的都一样。
     */
    private volatile GameState masked;

    private SeatView(Builder b) {
        this.self = b.self;
        this.love = b.love;
        this.hate = b.hate;
        this.roster = b.roster;
        this.catalog = b.catalog;
        this.turn = b.turn;
        this.phase = b.phase;
        this.gulls = b.gulls;
        this.weather = b.weather;
        this.seats = List.copyOf(b.seats);
        this.hand = List.copyOf(b.hand);
        this.deeds = List.copyOf(b.deeds);
        this.table = b.table;
        this.box = b.box;
        this.rowing = b.rowing;
        this.contest = b.contest;
        this.helmOptions = List.copyOf(b.helmOptions);
        this.navigated = b.navigated;
        this.navigationDone = b.navigationDone;
        this.overboard = b.overboard;
        this.thirst = b.thirst;
        this.navDeck = List.copyOf(b.navDeck);
        this.progress = Objects.requireNonNull(b.progress, "progress");
        this.weatherCards = List.copyOf(b.weatherCards);
        this.weatherDiscard = List.copyOf(b.weatherDiscard);
    }

    /**
     * 照这一局此刻的样子，造出 {@code self} 那一个座位的视角。
     *
     * <p>❗<b>裁剪就在这里，也只在这里</b>：下面每一处读 {@link Session} 的地方，要么是公开信息，
     * 要么是 {@code self} 自己的东西，要么按「此刻该不该给他看」判过。往视角里加一项之前，先问它属于哪一种。
     */
    public static SeatView of(Session session, CharacterId self) {
        return of(session, self, new Cache());
    }

    /**
     * 同上，复用 {@code cache} 里上一次算好的公开部分（驱动者一局里要造上千个视角）。
     *
     * <p>每个座位看得见的样子只取决于 {@link GameState} 这一个不可变的值（打架的力气还要加上蹭到的酒，
     * 而蹭酒只在喝酒那一下与换天时变，那两下都会换出一个新的 {@code GameState}）—— 所以按它的身份缓存，不会拿到旧的。
     * 弃牌堆只会变长、已有的那几张不会变，按张数缓存。
     */
    static SeatView of(Session session, CharacterId self, Cache cache) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(self, "self");
        GameState g = session.state();
        g.stateOf(self);                               // 阵容里没有这个人就在这里抛
        Builder b = new Builder();
        b.self = self;
        Optional<Affinities> dealt = session.affinities();
        b.love = dealt.map(a -> a.loveOf(self));
        b.hate = dealt.map(a -> a.hateOf(self));
        b.roster = g.roster();
        b.catalog = session.provisions();
        b.turn = g.turn();
        b.phase = g.phase();
        b.gulls = g.gulls();
        b.weather = session.currentWeather();
        if (cache.state != g) {
            List<SeatInfo> seats = new ArrayList<>();
            for (CharacterId id : g.bySeat()) {
                // 一个人的那一半（SurvivorState）没换、移出与离线没变，他看得见的样子就没变 —— 一步只动一两个人
                SurvivorState st = g.stateOf(id);
                SeatInfo prev = cache.infos.get(id);
                if (prev == null || cache.sources.get(id) != st || prev.removed() != g.isRemoved(id)
                        || prev.offline() != g.isOffline(id)) {
                    prev = SeatInfo.of(g, id, session.fightingSize(id));
                    cache.infos.put(id, prev);
                    cache.sources.put(id, st);
                }
                seats.add(prev);
            }
            cache.state = g;
            cache.seats = List.copyOf(seats);
        }
        b.seats = cache.seats;
        b.hand = g.stateOf(self).hand();
        b.deeds = session.deeds();
        int discarded = session.table().provisionDiscardSize();
        if (cache.discard == null || cache.discard.size() != discarded) {
            cache.discard = session.table().provisionDiscard();
        }
        if (cache.navDeck == null) {
            // 整副航海牌有哪几张是公开的（规则书第十四章印着）；次序不是 —— 所以按 id 排，不按牌堆
            List<NavigationCard> all = new ArrayList<>(session.table().pile().order());
            all.addAll(session.table().rowStack());
            all.addAll(session.table().rowerHand());
            all.sort(java.util.Comparator.comparing(NavigationCard::id));
            cache.navDeck = List.copyOf(all);
        }
        b.navDeck = cache.navDeck;
        b.progress = session.progress();
        session.table().weather().ifPresent(deck -> {
            if (cache.weatherCards == null) {
                // 天候牌有哪几张是公开的（规则书第十三章）；还没翻的那几张的次序不是 —— 按 id 排
                List<WeatherCard> all = new ArrayList<>(deck.upcoming());
                all.addAll(deck.discardedCards());
                session.currentWeather().ifPresent(all::add);
                all.sort(java.util.Comparator.comparing(WeatherCard::id));
                cache.weatherCards = List.copyOf(all);
            }
            b.weatherCards = cache.weatherCards;
            b.weatherDiscard = deck.discardedCards();
        });
        b.table = new TableInfo(session.table().pile().size(), session.table().rowStackSize(),
                session.table().provisionsLeft(), cache.discard,
                session.table().removedProvisionCount(), session.provisionRoundsToday());

        // 补给箱：传到第几位、还剩几张是公开的；里面是什么只有拿着它的人看得见。
        b.box = session.provisionHolder().map(holder -> new ProvisionBox(
                session.provisionChainIds().stream().map(CharacterId::of).toList(), session.provisionIndex(),
                session.provisionOffer().size(), holder.equals(self) ? session.provisionOffer() : List.of()));

        // 划船：谁在划是公开的；摸到的牌只有他自己看得见。
        b.rowing = session.rower().map(rower -> new RowingInfo(rower, session.rowing().size(),
                rower.equals(self)
                        ? session.rowing().stream().map(Session.RowCard::card).toList()
                        : List.of()));

        b.contest = session.contest().map(c -> ContestInfo.of(c, self));

        // 划船堆：只有舵手、只在他要挑牌的那一刻（这一回合还没执行、前面没有落海与口渴在等）。
        boolean helmPickPending = g.phase() == Phase.NAVIGATION && !session.navigationComplete()
                && !session.weatherNavigationPending() && session.overboardPending().isEmpty()
                && !session.thirstInProgress() && session.helmsmanMayPick()
                && g.helmsman().map(self::equals).orElse(false);
        b.helmOptions = helmPickPending ? session.table().rowStack() : List.of();
        b.navigated = session.navigatedThisTurn();
        b.navigationDone = session.navigationComplete();
        b.overboard = session.overboardPending().map(p -> new OverboardInfo(p.token(),
                inSeatOrder(g, p.swimmers()), session.overboardBait()));
        // 口渴：账是公开的（来源、阳伞挡掉几次、蹭到几次、还差几次）；他自己有几张水不是 —— 那是手牌。
        Optional<Session.ThirstPrompt> pending = cache.prompt != null ? Optional.of(cache.prompt)
                : session.thirstPending();
        b.thirst = pending.map(p -> new ThirstInfo(p.who(), p.effective().sources(),
                p.covered(), p.shared(), p.remaining(), p.waterPerSource()));
        return new SeatView(b);
    }

    /** 视角里一律按座位排，不依赖名单自己的次序（落海名单曾经来自 {@code Set.copyOf}，每次起 JVM 都不同；审查 Z3）。 */
    private static List<CharacterId> inSeatOrder(GameState g, Collection<CharacterId> ids) {
        return g.bySeat().stream().filter(ids::contains).toList();
    }

    // ---------------------------------------------------------------- 读

    public CharacterId self() {
        return self;
    }

    /** 我爱的人；这一局还没发爱恨时为空。 */
    public Optional<CharacterId> love() {
        return love;
    }

    /** 我恨的人；这一局还没发爱恨时为空。 */
    public Optional<CharacterId> hate() {
        return hate;
    }

    public Roster roster() {
        return roster;
    }

    /** 物资的效果目录（公开：每种牌干什么、各有几张，规则书上都印着）。 */
    public Provisions catalog() {
        return catalog;
    }

    public int turn() {
        return turn;
    }

    public Phase phase() {
        return phase;
    }

    public int gulls() {
        return gulls;
    }

    public Optional<WeatherCard> weather() {
        return weather;
    }

    /** 每个座位看得见的样子，船头 → 船尾，含被大海带走的人。 */
    public List<SeatInfo> seats() {
        return seats;
    }

    /** 我自己的手牌。 */
    public List<String> hand() {
        return hand;
    }

    /** 公开记事，按发生先后。 */
    public List<Deed> deeds() {
        return deeds;
    }

    public TableInfo table() {
        return table;
    }

    public Optional<ProvisionBox> box() {
        return box;
    }

    public Optional<RowingInfo> rowing() {
        return rowing;
    }

    public Optional<ContestInfo> contest() {
        return contest;
    }

    /** 整副航海牌（公开的构成，按 id 排；<b>不是</b>牌堆次序）。 */
    public List<NavigationCard> navDeck() {
        return navDeck;
    }

    /**
     * 这一局走到哪儿了：补给箱传到第几位、落海还剩几批、口渴排到谁、今天谁蹭到了酒……（{@link Session.Progress}，只有公开的东西）。
     * 往前推演的替身照它与视角里的其余部分重拼一局。
     */
    public Session.Progress progress() {
        return progress;
    }

    /** 整副天候牌（公开的构成，按 id 排；<b>不是</b>牌堆次序）。不翻天候的局里为空。 */
    public List<WeatherCard> weatherCards() {
        return weatherCards;
    }

    /** 天候的弃牌堆（公开：翻过的那几天全船都看见了），先进的在前。 */
    public List<WeatherCard> weatherDiscard() {
        return weatherDiscard;
    }

    /** 划船堆里的牌：只在我是舵手、正要挑牌时非空。 */
    public List<NavigationCard> helmOptions() {
        return helmOptions;
    }

    /** 这一回合已经执行的那张航海牌（公开）。 */
    public Optional<NavigationCard> navigated() {
        return navigated;
    }

    /** 这一回合的航海牌结算过了。 */
    public boolean navigationDone() {
        return navigationDone;
    }

    public Optional<OverboardInfo> overboard() {
        return overboard;
    }

    public Optional<ThirstInfo> thirst() {
        return thirst;
    }

    /** 某个座位看得见的样子。 */
    public SeatInfo info(CharacterId id) {
        for (SeatInfo s : seats) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        throw new IllegalArgumentException("阵容中没有这个角色: " + id);
    }

    /** 我自己。 */
    public SeatInfo me() {
        return info(self);
    }

    /** 还在艇上的人（活着的与尸体），船头 → 船尾。 */
    public List<SeatInfo> onBoat() {
        return seats.stream().filter(s -> !s.removed()).toList();
    }

    /** 清醒而且在线、能做事的人，船头 → 船尾。 */
    public List<SeatInfo> active() {
        return seats.stream().filter(SeatInfo::canAct).toList();
    }

    /** 舵手：最靠船尾的清醒在线的人（规则第九章）。 */
    public Optional<CharacterId> helmsman() {
        List<SeatInfo> active = active();
        return active.isEmpty() ? Optional.empty() : Optional.of(active.getLast().id());
    }

    /** 我手里与面前一共有几张这种牌。 */
    public int held(String cardId) {
        int n = 0;
        for (String c : hand) {
            if (c.equals(cardId)) {
                n++;
            }
        }
        for (String c : me().front()) {
            if (c.equals(cardId)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 拼成老策略要的那种 {@link GameState}：别人的手牌换成 {@link #HIDDEN}（张数不变），其余照公开的样子。
     *
     * <p>只给还按 {@code GameState} 写的老取向（{@code NavigationPolicy}）用：它们只读座位、伤势与自己的口渴标记，
     * 裁剪前后一模一样。新写的策略直接读视角。
     */
    public GameState state() {
        GameState cached = masked;
        if (cached != null) {
            return cached;
        }
        Map<CharacterId, SurvivorState> states = new LinkedHashMap<>();
        java.util.Set<CharacterId> removed = new java.util.LinkedHashSet<>();
        java.util.Set<CharacterId> offline = new java.util.LinkedHashSet<>();
        for (SeatInfo s : seats) {
            List<String> h = s.id().equals(self) ? hand : java.util.Collections.nCopies(s.handCount(), HIDDEN);
            states.put(s.id(), new SurvivorState(s.id(), s.seat(), s.damage(), new ThirstTally(s.thirst()),
                    s.acted(), h, s.front(), s.opened(), s.used()));
            if (s.removed()) {
                removed.add(s.id());
            }
            if (s.offline()) {
                offline.add(s.id());
            }
        }
        cached = GameState.assemble(roster, states, phase, turn, gulls, removed, offline);
        masked = cached;
        return cached;
    }

    /**
     * 这个视角的全部内容压成一行字，集合一律排好序。
     *
     * <p>给「看不见的东西变了，视角不变」那条判据用：两局只差别人的手牌、牌堆次序、别人的爱恨时，
     * 同一个座位的这一行必须一个字不差。
     */
    public String fingerprint() {
        StringBuilder out = new StringBuilder();
        out.append("self=").append(self).append(" love=").append(love.map(CharacterId::value).orElse("-"))
                .append(" hate=").append(hate.map(CharacterId::value).orElse("-"))
                .append(" turn=").append(turn).append(' ').append(phase).append(" gulls=").append(gulls)
                .append(" weather=").append(weather.map(WeatherCard::id).orElse("-"))
                .append(" hand=").append(hand).append('\n');
        for (SeatInfo s : seats) {
            out.append("  ").append(s.id()).append(" seat=").append(s.seat()).append(" dmg=").append(s.damage())
                    .append(' ').append(s.condition()).append(s.removed() ? " removed" : "")
                    .append(s.offline() ? " offline" : "").append(s.acted() ? " acted" : "")
                    .append(" thirst=").append(new TreeSet<>(s.thirst())).append(" front=").append(s.front())
                    .append(" opened=").append(new TreeSet<>(s.opened())).append(" used=")
                    .append(new TreeSet<>(s.used())).append(" hand#=").append(s.handCount())
                    .append(" str=").append(s.strength()).append('\n');
        }
        out.append("deeds=").append(deeds).append('\n');
        out.append("table=").append(table).append('\n');
        out.append("box=").append(box).append(" rowing=").append(rowing).append('\n');
        out.append("contest=").append(contest.map(ContestInfo::fingerprint).orElse("-")).append('\n');
        out.append("helm=").append(helmOptions.stream().map(NavigationCard::id).toList())
                .append(" navigated=").append(navigated.map(NavigationCard::id).orElse("-"))
                .append(" done=").append(navigationDone).append('\n');
        out.append("overboard=").append(overboard).append(" thirst=")
                .append(thirst.map(ThirstInfo::fingerprint).orElse("-")).append('\n');
        Session.Progress p = progress;
        out.append("progress=weather:").append(p.weatherDrawn()).append(" chain:").append(p.provisionChain())
                .append('@').append(p.provisionAt()).append(" rounds:").append(p.provisionRoundsToday())
                .append(" nav:").append(p.navigated().map(NavigationCard::id).orElse("-")).append('/')
                .append(p.standardNavigation()).append('/').append(p.extraNavigation()).append('/')
                .append(p.resolvingExtra()).append('/').append(p.rowStackPrepared())
                .append(" overboard:").append(p.overboard()).append(" seq:").append(p.overboardSequence())
                .append(" thirst:").append(p.thirstCard().map(NavigationCard::id).orElse("-")).append(p.thirstQueue())
                .append('@').append(p.thirstAt()).append('+').append(p.thirstWatersSpent())
                .append(" recipients:").append(new java.util.TreeMap<>(p.thirstWatersByRecipient()))
                .append(" sharedUsed:").append(new java.util.TreeMap<>(p.sharedWaterUsed()))
                .append(" settled:").append(sortedSources(p.thirstSettledToday()))
                .append(" cover:").append(new java.util.TreeMap<>(p.coverUsedToday()))
                .append(" rum:").append(new TreeSet<>(p.sharedRum())).append(" healed:")
                .append(p.healedSincePhaseStart()).append('\n');
        out.append("weather=").append(weatherCards.stream().map(WeatherCard::id).toList()).append(" discard=")
                .append(weatherDiscard.stream().map(WeatherCard::id).toList()).append('\n');
        return out.toString();
    }

    private static String sortedSources(Map<CharacterId, Set<ThirstSource>> m) {
        java.util.TreeMap<CharacterId, TreeSet<ThirstSource>> out = new java.util.TreeMap<>();
        m.forEach((id, s) -> out.put(id, new TreeSet<>(s)));
        return out.toString();
    }

    @Override
    public String toString() {
        return "SeatView[" + self + " · 第 " + turn + " 天 " + phase + "]";
    }

    /**
     * 一个驱动者造视角时复用的那一份（见 {@link #of(Session, CharacterId, Cache)}）。不跨局、不跨驱动者共用。
     *
     * <p>❗它记着每个人上一次的 {@link SurvivorState}（含手牌）只为比身份，<b>从不交给策略</b>：
     * 它只在驱动者手里，视角里只有算出来的 {@link SeatInfo}。
     */
    static final class Cache {
        private GameState state;
        private List<SeatInfo> seats;
        private final Map<CharacterId, SurvivorState> sources = new java.util.HashMap<>();
        private final Map<CharacterId, SeatInfo> infos = new java.util.HashMap<>();
        private List<String> discard;
        private List<NavigationCard> navDeck;
        private List<WeatherCard> weatherCards;

        /**
         * 驱动者手里刚取到的那一份口渴的账：问递水的人时局面没变，不必每造一个视角就重算一遍。
         * ❗只在「取到之后、结算之前」放在这里，结算完立刻清掉 —— 放过了头就是一份旧账。
         */
        Session.ThirstPrompt prompt;
    }

    private static final class Builder {
        CharacterId self;
        Optional<CharacterId> love = Optional.empty();
        Optional<CharacterId> hate = Optional.empty();
        Roster roster;
        Provisions catalog;
        int turn;
        Phase phase;
        int gulls;
        Optional<WeatherCard> weather = Optional.empty();
        List<SeatInfo> seats = List.of();
        List<String> hand = List.of();
        List<Deed> deeds = List.of();
        TableInfo table;
        Optional<ProvisionBox> box = Optional.empty();
        Optional<RowingInfo> rowing = Optional.empty();
        Optional<ContestInfo> contest = Optional.empty();
        List<NavigationCard> helmOptions = List.of();
        Optional<NavigationCard> navigated = Optional.empty();
        boolean navigationDone;
        Optional<OverboardInfo> overboard = Optional.empty();
        Optional<ThirstInfo> thirst = Optional.empty();
        List<NavigationCard> navDeck = List.of();
        Session.Progress progress;
        List<WeatherCard> weatherCards = List.of();
        List<WeatherCard> weatherDiscard = List.of();
    }

    // ---------------------------------------------------------------- 组成部分

    /**
     * 一个座位看得见的样子（规则第五章「别人看得见你什么」）。
     *
     * @param id        角色
     * @param seat      现在坐几号位
     * @param size      体型（公开：印在角色卡上）
     * @param survival  生存分（公开）
     * @param ability   本事（公开）
     * @param damage    受了几点伤
     * @param condition 清醒 · 昏迷 · 死亡
     * @param removed   被大海带走了
     * @param offline   掉线了
     * @param acted     今天行动过了
     * @param thirst    今天背着的口渴标记（划过船 · 打过架 · 喝过酒……全船都看得见做过这些事）
     * @param front     面前的牌
     * @param opened    面前撑开的（阳伞）
     * @param used      他今天用过的（喝过的酒；按人记，瓶子不在面前了也算）
     * @param handCount 手里有几张 —— 只有张数
     * @param strength  今天打架的力气：体型 + 喝过的酒（含陪酒女蹭到的），不含没押的武器
     */
    public record SeatInfo(CharacterId id, int seat, int size, int survival, Ability ability, int damage,
                           Condition condition, boolean removed, boolean offline, boolean acted,
                           Set<ThirstSource> thirst, List<String> front, Set<String> opened, Set<String> used,
                           int handCount, int strength) {

        public SeatInfo {
            Objects.requireNonNull(id, "id");
            thirst = Set.copyOf(thirst);
            front = List.copyOf(front);
            opened = Set.copyOf(opened);
            used = Set.copyOf(used);
        }

        static SeatInfo of(GameState g, CharacterId id, int strength) {
            Survivor s = g.roster().get(id);
            SurvivorState st = g.stateOf(id);
            return new SeatInfo(id, st.seat(), s.size(), s.survival(), s.ability(), st.damage(), g.conditionOf(id),
                    g.isRemoved(id), g.isOffline(id), st.actedThisTurn(), st.thirst().sources(), st.front(),
                    st.opened(), st.usedThisTurn(), st.hand().size(), strength);
        }

        /** 离昏迷还差几点伤（0 = 已经昏迷，负数 = 死了）。 */
        public int margin() {
            return size - damage;
        }

        /** 清醒而且在线。 */
        public boolean canAct() {
            return condition == Condition.CONSCIOUS && !offline;
        }

        public boolean alive() {
            return condition != Condition.DEAD;
        }

        /** 面前有几张这种牌。 */
        public int inFront(String cardId) {
            int n = 0;
            for (String c : front) {
                if (c.equals(cardId)) {
                    n++;
                }
            }
            return n;
        }
    }

    /**
     * 桌面上看得见的数。
     *
     * @param navPile          航海牌堆还有几张
     * @param rowStack         划船堆里有几张（公开；是什么只有舵手挑牌时看得见）
     * @param provisionsLeft   物资牌堆还有几张
     * @param discard          物资弃牌堆（公开：喝掉的水、用掉的医疗箱……都是当着全船用的）
     * @param sunk             随落水的人沉下去的物资张数
     * @param provisionRounds  今天开过几轮补给箱
     */
    public record TableInfo(int navPile, int rowStack, int provisionsLeft, List<String> discard, int sunk,
                            int provisionRounds) {
        public TableInfo {
            discard = List.copyOf(discard);
        }
    }

    /**
     * 补给箱：传的次序与传到第几位是公开的。
     *
     * @param chain     传的次序（开箱那一刻定死）
     * @param index     传到第几位
     * @param offerSize 箱子里还剩几张
     * @param offer     箱子里是什么 —— 只在我拿着箱子时非空
     */
    public record ProvisionBox(List<CharacterId> chain, int index, int offerSize, List<String> offer) {
        public ProvisionBox {
            chain = List.copyOf(chain);
            offer = List.copyOf(offer);
        }

        /** 拿着箱子的人。 */
        public CharacterId holder() {
            return chain.get(index);
        }

        /** 我之后还有几个人等着拿（不含我）。 */
        public int remainingAfterHolder() {
            return chain.size() - index - 1;
        }
    }

    /**
     * 正在划船。
     *
     * @param rower      谁在划
     * @param drawnCount 摸了几张
     * @param drawn      摸到的牌 —— 只在我就是划船的人时非空
     */
    public record RowingInfo(CharacterId rower, int drawnCount, List<NavigationCard> drawn) {
        public RowingInfo {
            drawn = List.copyOf(drawn);
        }
    }

    /**
     * 进行中的这一场（换座位 · 抢 · 分食反对）看得见的样子。
     *
     * @param committedCount 每个参战者押下了几张（公开：全船只听到「押下 1 张」）
     * @param mine           我自己押下的是哪几张
     * @param rationCard     分食那张牌的 id；另外两种为空串
     * @param passed         分食：已经表示不反对的人
     */
    public record ContestInfo(Contest.Kind kind, CharacterId attacker, CharacterId target, Contest.Stage stage,
                              List<CharacterId> attackSide, List<CharacterId> defendSide,
                              Map<CharacterId, Integer> committedCount, List<String> mine, boolean handOnly,
                              String rationCard, Set<CharacterId> passed) {

        public ContestInfo {
            attackSide = List.copyOf(attackSide);
            defendSide = List.copyOf(defendSide);
            committedCount = Map.copyOf(committedCount);
            mine = List.copyOf(mine);
            passed = Set.copyOf(passed);
        }

        static ContestInfo of(Contest c, CharacterId self) {
            List<CharacterId> attack = c.fight().map(f -> List.copyOf(f.attackSide())).orElse(List.of());
            List<CharacterId> defend = c.fight().map(f -> List.copyOf(f.defendSide())).orElse(List.of());
            // ❗押下的暗牌只在押武器那一段有意义：结算之后它们已经亮在面前（信号枪已经弃了），引擎里那份记录只是旧账，
            //   照搬进视角的话，拿视角重拼的一局就对不上了（WorldsTest 在挑牌那一步抓到的）
            boolean weapons = c.stage() == Contest.Stage.WEAPONS;
            Map<CharacterId, Integer> counts = new LinkedHashMap<>();
            if (weapons) {
                c.committed().forEach((who, cards) -> counts.put(who, cards.size()));
            }
            return new ContestInfo(c.kind(), c.attacker(), c.target(), c.stage(), attack, defend, counts,
                    weapons ? c.committedBy(self) : List.of(), c.handOnly(), c.provision(), c.passed());
        }

        /** 某人在这一场里站哪边；没上场为空。 */
        public Optional<Fight.Side> sideOf(CharacterId id) {
            if (attackSide.contains(id)) {
                return Optional.of(Fight.Side.ATTACK);
            }
            if (defendSide.contains(id)) {
                return Optional.of(Fight.Side.DEFEND);
            }
            return Optional.empty();
        }

        /** 某人押下了几张（看得见的只有张数）。 */
        public int committed(CharacterId id) {
            return committedCount.getOrDefault(id, 0);
        }

        String fingerprint() {
            return kind + " " + attacker + "→" + target + " " + stage + " atk=" + attackSide + " def=" + defendSide
                    + " committed=" + new java.util.TreeMap<>(committedCount) + " mine=" + mine
                    + " handOnly=" + handOnly + " ration=" + rationCard + " passed=" + new TreeSet<>(passed);
        }
    }

    /**
     * 落海那一刻。
     *
     * @param token    这一批的编号（打牌时要带上，批次换了旧编号就作废）
     * @param swimmers 这一批落海的人，船头 → 船尾
     * @param bait     这一批已经打出的血饵加了几点
     */
    public record OverboardInfo(int token, List<CharacterId> swimmers, int bait) {
        public OverboardInfo {
            swimmers = List.copyOf(swimmers);
        }
    }

    /**
     * 正在结算口渴的那个人的账（公开）。他自己有几张水不在这里 —— 那是他的手牌。
     *
     * @param who            轮到谁
     * @param sources        今天真正生效的口渴来源
     * @param covered        撑开的阳伞挡掉几次
     * @param shared         蹭到别人喝的水抵掉几次（陪酒女）
     * @param remaining      还要化解几次：每次喝 {@code waterPerSource} 张水，不喝就挨 1 点
     * @param waterPerSource 每次要几张水（酷热天是 2）
     */
    public record ThirstInfo(CharacterId who, Set<ThirstSource> sources, int covered, int shared, int remaining,
                             int waterPerSource) {
        public ThirstInfo {
            sources = Set.copyOf(sources);
        }

        String fingerprint() {
            return who + " sources=" + new TreeSet<>(sources) + " covered=" + covered + " shared=" + shared
                    + " remaining=" + remaining + " per=" + waterPerSource;
        }
    }
}
