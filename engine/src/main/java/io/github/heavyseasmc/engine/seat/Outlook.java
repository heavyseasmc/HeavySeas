package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.Selector;
import io.github.heavyseasmc.engine.play.Deed;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.thirst.ThirstSource;
import io.github.heavyseasmc.engine.weather.WeatherEffect;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按处境估一局的走向：每个人活到最后的可能有多大、我最后大概拿几分（第一层的全部判断都从这里来）。
 *
 * <h2>为什么要一个「估分」，而不是一条条规矩</h2>
 * 规矩（「口渴就喝水」「别打爱的人」）各管各的，互相打架时没人裁决：手里只剩一张水，
 * 是自己喝、还是留给落在后面的爱人？两条规矩给出两个答案。换成「我最后大概拿几分」这一个数，
 * 每个选项都折算成它的增减，裁决就是比大小 —— 而计分规则（第十一章）原样写在 {@link #ev()} 里：
 * 自己活着拿生存分（恨自己的不拿）、爱的人活着拿他的生存分、恨的人死了拿他的<b>体型</b>、
 * 恨自己的改拿艇上别人的尸体、财宝看身体在不在艇上。
 *
 * <h2>危险度是一条估出来的曲线，不是概率论</h2>
 * 「他还剩几天、每天大概挨几下、手里的水能挡掉几下」折成一个「预计还要挨几点伤」，
 * 再与他离昏迷还差几点比，过一条 S 形曲线。它只要<b>方向对、形状对</b>：
 * 体力越少、离靠岸越远、水越少，越危险；离死越近，再挨一点越要命。
 * 下面那些常数是设计常数，不给服主调（{@link SeatPolicySettings} 的类注释）。
 *
 * <h2>只读视角</h2>
 * 构造参数只有 {@link SeatView}：别人手里有几张水只能按「没见过的牌里水占多少」估。
 */
final class Outlook {

    // ---------------------------------------------------------------- 设计常数

    /**
     * 还差一只海鸥，大约还要再过几天。
     *
     * <p>❗下面这几个数是<b>对着结果校准过的</b>（2026-10-07，全船按处境打分的 400 局）：把每一位每天行动时自估的
     * 「我会死」按十档分箱，与他最后到底死没死比。初版（2.4 / 0.15 / 0.25、门槛写反了半点）系统性地悲观三成 ——
     * 估 0.66 的实际只死了 0.32 —— 于是体力低的人一律被估成「反正要死」，救他、递他水都显得不值。
     * 校准后每一档的偏差在一成以内。改这几个数之前先重跑那张表（{@code SeatDistributionTest} 印着）。
     */
    static final double DAYS_PER_GULL = 2.2;
    /** 平均每天在打架里挨的伤。 */
    static final double FIGHT_DAMAGE_PER_DAY = 0.08;
    /** 平均每天因为划船 · 打架 · 喝酒多出来的口渴（牌面点名另算）。 */
    static final double OTHER_THIRST_PER_DAY = 0.15;
    /**
     * 危险度曲线的宽度：基础值，加上「预计挨几下」本身的散布（按次数算，约等于它的平方根），再加上每多一天的不确定
     * （这一局还剩几天本来就说不准，快的四五天就靠岸）。宽度太窄的话，体力低的人一律被估成「反正要死」，
     * 救他一下、递他一张水就都显得不值 —— 2026-10-07 的常识测试照出来的。
     */
    static final double SPREAD = 1.0;
    static final double SPREAD_PER_HIT = 0.35;
    static final double SPREAD_PER_DAY = 0.1;
    /** 恩怨最多折合几分（盼帮过我的人活、盼害过我的人死）。约一张财宝的分量：压不过爱恨，但够改变递不递水、站哪边。 */
    static final double SOCIAL = 2.0;
    /** 别人藏着的武器，估他会押出几成。 */
    static final double COMMIT_RATE = 0.5;
    /** 打架胜负的不确定（站队之前，帮手还没来）。 */
    static final double FIGHT_SIGMA_OPEN = 3.0;
    /** 打架胜负的不确定（站完队、只差暗牌）。 */
    static final double FIGHT_SIGMA_CLOSED = 1.6;

    // ---------------------------------------------------------------- 不变的

    final SeatView view;
    final int n;
    final CharacterId[] ids;
    final int me;
    /** 我爱的人的下标；没发爱恨时为 -1。 */
    final int love;
    /** 我恨的人的下标；没发爱恨时为 -1。 */
    final int hate;
    final int[] size;
    final int[] survival;
    /** 每执行一张航海牌，他被点名落海 / 口渴的机会（按整副牌的构成数）。 */
    final double[] pOverboard;
    final double[] pThirst;
    /** 恩怨折成的分：&gt;0 盼他活，&lt;0 盼他死。只是倾向，不进计分。 */
    final double[] social;
    /** 整副牌里印着船桨图示 / 打架图示的比例。 */
    final double oarIcon;
    final double fightIcon;
    /** 没见过的牌（别人手里、牌堆里）里，一张平均有几张水、多少武器加值。 */
    final double waterDensity;
    final double weaponDensity;
    /** 以后每天从补给箱里还能拿到几张水（牌堆没抽完时每人每天一张，按没见过的牌里水的比例）。 */
    final double waterIncomePerDay;
    /** 补给箱还能开几天。 */
    final double provisionDays;
    final Objective objective;

    // ---------------------------------------------------------------- 会变的（推想时改的是副本）

    double[] margin;
    boolean[] alive;
    boolean[] onBoat;
    double[] water;
    boolean[] ring;
    double myTreasure;
    int gulls;
    boolean ended;

    // ---------------------------------------------------------------- 一个决定里反复要的（只记在原件上，副本不继承）

    /** 第一层「拿到这张值几分」按牌记下来（{@code HeuristicSeatPolicy#hold}）。 */
    java.util.Map<String, Double> holdMemo;
    double holdBase;
    /** 没见过的牌各几张（{@code HeuristicSeatPolicy#unseenPool}）。 */
    java.util.Map<String, Integer> unseenMemo;

    /** 这一份估值替谁说话：照常、爱恨对调、整个反过来（后两种只给红测用）。 */
    enum Objective {
        NORMAL, SWAPPED, INVERTED
    }

    Outlook(SeatView view, SeatPolicySettings settings, Objective objective) {
        this.view = view;
        this.objective = objective;
        List<SeatView.SeatInfo> seats = view.seats();
        n = seats.size();
        ids = new CharacterId[n];
        size = new int[n];
        survival = new int[n];
        margin = new double[n];
        alive = new boolean[n];
        onBoat = new boolean[n];
        water = new double[n];
        ring = new boolean[n];
        int self = -1;
        for (int i = 0; i < n; i++) {
            SeatView.SeatInfo s = seats.get(i);
            ids[i] = s.id();
            size[i] = s.size();
            survival[i] = s.survival();
            margin[i] = s.margin();
            alive[i] = s.alive();
            onBoat[i] = !s.removed();
            if (s.id().equals(view.self())) {
                self = i;
            }
        }
        me = self;
        int l = view.love().map(this::index).orElse(-1);
        int h = view.hate().map(this::index).orElse(-1);
        if (objective == Objective.SWAPPED) {
            int t = l;
            l = h;
            h = t;
        }
        love = l;
        hate = h;

        // 没见过的牌：整副 − 我的手牌 − 每个人面前 − 弃牌堆 − 我看得见的补给箱
        Map<String, Integer> unseen = new HashMap<>();
        for (String card : view.catalog().deck()) {
            unseen.merge(card, 1, Integer::sum);
        }
        view.hand().forEach(c -> unseen.merge(c, -1, Integer::sum));
        for (SeatView.SeatInfo s : seats) {
            s.front().forEach(c -> unseen.merge(c, -1, Integer::sum));
        }
        view.table().discard().forEach(c -> unseen.merge(c, -1, Integer::sum));
        view.box().ifPresent(b -> b.offer().forEach(c -> unseen.merge(c, -1, Integer::sum)));
        int total = 0;
        int waters = 0;
        double power = 0;
        for (Map.Entry<String, Integer> e : unseen.entrySet()) {
            int k = Math.max(0, e.getValue());
            total += k;
            Provision p = view.catalog().get(e.getKey());
            if (isWater(p)) {
                waters += k;
            }
            power += k * p.weaponPower();
        }
        waterDensity = total == 0 ? 0 : (double) waters / total;
        weaponDensity = total == 0 ? 0 : power / total;
        long active = seats.stream().filter(SeatView.SeatInfo::canAct).count();
        provisionDays = active == 0 ? 0 : (double) view.table().provisionsLeft() / active;
        waterIncomePerDay = waterDensity;

        for (int i = 0; i < n; i++) {
            SeatView.SeatInfo s = seats.get(i);
            int frontWater = 0;
            for (String c : s.front()) {
                if (isWater(view.catalog().get(c))) {
                    frontWater++;
                }
                if (view.catalog().get(c).effect() instanceof ProvisionEffect.PreventOverboardDamage) {
                    ring[i] = true;
                }
            }
            if (i == me) {
                int handWater = 0;
                for (String c : view.hand()) {
                    if (isWater(view.catalog().get(c))) {
                        handWater++;
                    }
                }
                water[i] = handWater + frontWater;
            } else {
                water[i] = frontWater + s.handCount() * waterDensity;
            }
        }
        myTreasure = treasureOf(view, view.hand(), view.me().front());
        gulls = view.gulls();

        DeckStats stats = DECK_STATS.computeIfAbsent(new DeckKey(view.navDeck(), boardOf(seats)),
                key -> deckStats(key.deck(), key.board()));
        pOverboard = new double[n];
        pThirst = new double[n];
        for (int i = 0; i < n; i++) {
            pOverboard[i] = stats.overboard().getOrDefault(ids[i], 0.0);
            pThirst[i] = stats.thirst().getOrDefault(ids[i], 0.0);
        }
        oarIcon = stats.oarIcon();
        fightIcon = stats.fightIcon();
        social = social(view, settings);
    }

    /** 整副牌的统计只取决于牌的构成与艇上还有谁：按这两样缓存（一局里每个决定都要用，每次现数要几十微秒）。 */
    private record DeckKey(List<NavigationCard> deck, Set<CharacterId> board) {
    }

    private record DeckStats(Map<CharacterId, Double> overboard, Map<CharacterId, Double> thirst, double oarIcon,
                             double fightIcon) {
    }

    private static final Map<DeckKey, DeckStats> DECK_STATS = new java.util.concurrent.ConcurrentHashMap<>();

    private static Set<CharacterId> boardOf(List<SeatView.SeatInfo> seats) {
        Set<CharacterId> board = new LinkedHashSet<>();
        for (SeatView.SeatInfo s : seats) {
            if (!s.removed()) {
                board.add(s.id());
            }
        }
        return Set.copyOf(board);
    }

    private static DeckStats deckStats(List<NavigationCard> deck, Set<CharacterId> board) {
        Map<CharacterId, Double> over = new HashMap<>();
        Map<CharacterId, Double> thirst = new HashMap<>();
        double oars = 0;
        double fights = 0;
        for (NavigationCard card : deck) {
            Set<CharacterId> named = card.overboard().select(board, (cond, who) -> true);
            Set<CharacterId> thirsty = card.thirst().select(board, (cond, who) -> true);
            // 「喝过酒的人落海」那一族：不知道哪天谁喝，按一成算
            double overWeight = card.overboard() instanceof Selector.Conditional ? 0.1 : 1.0;
            double thirstWeight = card.thirst() instanceof Selector.Conditional ? 0.1 : 1.0;
            for (CharacterId id : board) {
                if (named.contains(id)) {
                    over.merge(id, overWeight, Double::sum);
                }
                if (thirsty.contains(id)) {
                    thirst.merge(id, thirstWeight, Double::sum);
                }
            }
            oars += card.thirstRowers() ? 1 : 0;
            fights += card.thirstFighters() ? 1 : 0;
        }
        int cards = Math.max(1, deck.size());
        over.replaceAll((k, v) -> v / cards);
        thirst.replaceAll((k, v) -> v / cards);
        return new DeckStats(Map.copyOf(over), Map.copyOf(thirst), oars / cards, fights / cards);
    }

    private Outlook(Outlook o) {
        view = o.view;
        n = o.n;
        ids = o.ids;
        me = o.me;
        love = o.love;
        hate = o.hate;
        size = o.size;
        survival = o.survival;
        pOverboard = o.pOverboard;
        pThirst = o.pThirst;
        social = o.social;
        oarIcon = o.oarIcon;
        fightIcon = o.fightIcon;
        waterDensity = o.waterDensity;
        weaponDensity = o.weaponDensity;
        waterIncomePerDay = o.waterIncomePerDay;
        provisionDays = o.provisionDays;
        objective = o.objective;
        margin = o.margin.clone();
        alive = o.alive.clone();
        onBoat = o.onBoat.clone();
        water = o.water.clone();
        ring = o.ring.clone();
        myTreasure = o.myTreasure;
        gulls = o.gulls;
        ended = o.ended;
    }

    /** 推想用的副本：改它不动原来那一份。 */
    Outlook copy() {
        return new Outlook(this);
    }

    int index(CharacterId id) {
        for (int i = 0; i < n; i++) {
            if (ids[i].equals(id)) {
                return i;
            }
        }
        throw new IllegalArgumentException("阵容中没有这个角色: " + id);
    }

    // ---------------------------------------------------------------- 估

    /** 还剩几天（按海鸥估）。靠岸了就是 0。 */
    double days() {
        if (ended) {
            return 0;
        }
        return Math.max(0.6, DAYS_PER_GULL * (4 - gulls));
    }

    /** 他此刻起到终局之前死掉的可能（已经死了是 1）。 */
    double pDie(int j) {
        if (!alive[j]) {
            return 1;
        }
        if (ended) {
            return 0;
        }
        double r = days();
        boolean protectedFromSea = ring[j] || immune(j);
        double ob = protectedFromSea ? 0 : pOverboard[j];
        double thirstNeed = r * (pThirst[j] + OTHER_THIRST_PER_DAY);
        double usable = margin[j] > 0 ? water[j] + income(r) : 0;   // 昏迷的人喝不了自己的水，也拿不到补给箱
        double unmet = Math.max(0, thirstNeed - usable);
        double expected = r * (ob + FIGHT_DAMAGE_PER_DAY) + unmet;
        double spread = SPREAD + SPREAD_PER_HIT * Math.sqrt(expected) + SPREAD_PER_DAY * r;
        // 船上要伤得「超过」体力才死（正好用完只是昏迷）：门槛是体力再多半点
        double p = logistic((expected - margin[j] - 0.5) / spread);
        if (!ring[j] && margin[j] <= 1 + 1e-9) {
            // 水里：体力只剩一点（或已经昏迷）、面前没有救生圈 —— 再被点一次落海就淹死，水手昏迷了也一样
            double named = immune(j) ? 0 : pOverboard[j];
            p = Math.max(p, 1 - Math.pow(1 - named, r));
        }
        return p;
    }

    /** 他死在水里（尸体连财宝一起沉下去）的那一部分。 */
    double pDrown(int j) {
        if (!alive[j] || ended || ring[j]) {
            return 0;
        }
        double r = days();
        double ob = immune(j) ? 0 : pOverboard[j];
        if (ob == 0) {
            return 0;
        }
        double thirstNeed = r * (pThirst[j] + OTHER_THIRST_PER_DAY);
        double unmet = Math.max(0, thirstNeed - (margin[j] > 0 ? water[j] + income(r) : 0));
        double share = (r * ob) / (r * ob + unmet + r * FIGHT_DAMAGE_PER_DAY + 1e-9);
        double die = pDie(j);
        if (margin[j] <= 1 + 1e-9) {
            return Math.max(die * share, 1 - Math.pow(1 - ob, r));
        }
        return die * share;
    }

    /** 剩下的 {@code r} 天里还能从补给箱拿到几张水。 */
    private double income(double r) {
        return Math.min(r, provisionDays) * waterIncomePerDay;
    }

    /** 水手清醒时落海不受伤（他的本事要清醒才管用）。 */
    boolean immune(int j) {
        return view.seats().get(j).ability() instanceof Ability.OverboardImmune && margin[j] > 0;
    }

    /**
     * 我最后大概拿几分（加上恩怨折的那一点倾向）—— 计分规则第十一章，四项各算各的。
     */
    double ev() {
        return ev(social);
    }

    /**
     * 同 {@link #ev()}，只是恩怨按给定的那一份算（下标与 {@link #ids} 相同）。
     *
     * <p>推演层用它：恩怨按<b>拿主意那一刻</b>的算，不按推演里新发生的 —— 推演里别人「帮了我」是抽出来的，不是真的。
     */
    double ev(double[] regard) {
        double total = 0;
        double[] a = new double[n];
        double[] drown = new double[n];
        for (int j = 0; j < n; j++) {
            a[j] = alive[j] ? 1 - pDie(j) : 0;
            drown[j] = alive[j] ? pDrown(j) : 0;
        }
        boolean selfHate = hate == me;
        if (!selfHate) {
            total += survival[me] * a[me];                       // ① 自己活着
        }
        if (onBoat[me]) {
            total += myTreasure * (1 - drown[me]);               // ② 身体还在艇上，财宝算分
        }
        if (love >= 0) {
            total += survival[love] * a[love];                   // ③ 爱的人活着：他的生存分
        }
        if (hate >= 0) {
            if (!selfHate) {
                total += size[hate] * (1 - a[hate]);             // ④ 恨的人死了：他的体型（死在哪都算）
            } else {
                for (int j = 0; j < n; j++) {                    // ④' 恨自己：艇上别人的尸体，不算自己、不算爱的人
                    if (j == me || j == love) {
                        continue;
                    }
                    double corpse = alive[j] ? Math.max(0, (1 - a[j]) - drown[j]) : (onBoat[j] ? 1 : 0);
                    total += size[j] * corpse;
                }
            }
        }
        for (int j = 0; j < n; j++) {
            if (j != me) {
                total += regard[j] * a[j];
            }
        }
        return objective == Objective.INVERTED ? -total : total;
    }

    // ---------------------------------------------------------------- 推想：改副本

    /** 挨 {@code d} 点伤（船上：体力落到 0 以下就死）。 */
    void hurt(int j, double d) {
        if (!alive[j] || d <= 0) {
            return;
        }
        margin[j] -= d;
        if (margin[j] < -1e-9) {
            alive[j] = false;
        }
    }

    void heal(int j, double h) {
        if (alive[j]) {
            margin[j] = Math.min(size[j], margin[j] + h);
        }
    }

    /**
     * 掉下水：挨一点（清醒的水手、面前有救生圈的人不挨）再加血饵；面前的牌被卷走（救生圈留下）；
     * 体力落到 0 以下、或者正好 0 又没有救生圈 —— 淹死，连人带牌被大海带走。
     */
    void overboard(int j, int bait) {
        if (!onBoat[j]) {
            return;
        }
        double d = (immune(j) || ring[j] ? 0 : 1) + bait;
        washFront(j);
        if (!alive[j]) {
            onBoat[j] = false;                                   // 尸体被点到落海：连人带牌没了
            return;
        }
        margin[j] -= d;
        if (margin[j] < -1e-9 || (Math.abs(margin[j]) < 1e-9 && !ring[j])) {
            alive[j] = false;
            onBoat[j] = false;
        }
    }

    /** 面前的牌被浪卷走：水少了；我自己面前的财宝也没了。救生圈留下。 */
    private void washFront(int j) {
        SeatView.SeatInfo s = view.seats().get(j);
        int frontWater = 0;
        for (String c : s.front()) {
            if (isWater(view.catalog().get(c))) {
                frontWater++;
            }
        }
        water[j] = Math.max(0, water[j] - frontWater);
        if (j == me) {
            myTreasure -= treasureOf(view, view.hand(), s.front()) - treasureOf(view, view.hand(), List.of());
        }
    }

    void gull(int delta) {
        gulls = Math.max(0, gulls + delta);
        if (gulls >= GameState.GULLS_TO_LAND) {
            ended = true;
        }
    }

    /**
     * 这张航海牌若执行，此刻立刻会发生什么：海鸥 → 落海 → 口渴，照规则第九章的次序（天候的几种改动也算上）。
     *
     * <p>口渴按「有水就喝」估：我知道自己有几张水；别人按面前的水加上手里的估值。
     */
    void apply(NavigationCard card) {
        WeatherEffect weather = view.weather().map(w -> w.effect()).orElse(null);
        int gull = weather == WeatherEffect.IGNORE_GULLS ? 0 : card.gull();
        gull(gull);
        if (ended) {
            return;
        }
        Set<CharacterId> board = new LinkedHashSet<>();
        for (int j = 0; j < n; j++) {
            if (onBoat[j]) {
                board.add(ids[j]);
            }
        }
        Selector.ConditionResolver used = (cond, who) -> cond.startsWith("used_")
                && view.info(who).used().contains(cond.substring("used_".length()));
        Set<CharacterId> over = card.overboard().select(board, used);
        int bait = 0;
        for (CharacterId id : over) {
            for (String c : view.info(id).front()) {
                if (view.catalog().get(c).effect() instanceof ProvisionEffect.DamageInWater) {
                    bait = 1;
                }
            }
        }
        for (int j = 0; j < n; j++) {
            if (over.contains(ids[j])) {
                overboard(j, bait);
            }
        }
        boolean wave = weather == WeatherEffect.FIGHTERS_OVERBOARD && card.thirstFighters();
        boolean storm = weather == WeatherEffect.ROWERS_OVERBOARD && card.thirstRowers();
        if (wave || storm) {
            for (int j = 0; j < n; j++) {
                Set<ThirstSource> marks = view.seats().get(j).thirst();
                if ((wave && marks.contains(ThirstSource.FOUGHT)) || (storm && marks.contains(ThirstSource.ROWED))) {
                    overboard(j, 0);
                }
            }
        }
        if (weather == WeatherEffect.IGNORE_THIRST) {
            return;
        }
        Set<CharacterId> named = card.thirst().select(board, used);
        int per = weather == WeatherEffect.DOUBLE_WATER ? 2 : 1;
        for (int j = 0; j < n; j++) {
            if (!alive[j] || !onBoat[j]) {
                continue;
            }
            Set<ThirstSource> marks = view.seats().get(j).thirst();
            int sources = 0;
            if (named.contains(ids[j])) {
                sources++;
            }
            if (card.thirstRowers() && marks.contains(ThirstSource.ROWED) && !storm) {
                sources++;
            }
            if (card.thirstFighters() && marks.contains(ThirstSource.FOUGHT) && !wave) {
                sources++;
            }
            if (marks.contains(ThirstSource.DRANK_RUM)) {
                sources++;
            }
            if (weather == WeatherEffect.ALL_THIRST) {
                sources++;
            }
            if (view.seats().get(j).opened().size() > 0) {
                sources = Math.max(0, sources - 1);              // 撑开的伞挡一次
            }
            thirst(j, sources, per);
        }
    }

    /** 口渴 {@code sources} 次：能喝就喝（每次 {@code per} 张），喝不上的挨伤。 */
    void thirst(int j, int sources, int per) {
        if (sources <= 0 || !alive[j]) {
            return;
        }
        double units = margin[j] > 0 ? water[j] / per : 0;
        double drunk = Math.min(sources, units);
        water[j] -= drunk * per;
        hurt(j, sources - drunk);
    }

    // ---------------------------------------------------------------- 打架

    /** 他今天打架的力气（体型 + 喝过的酒），外加估他会押出来的暗牌。我自己的暗牌由调用方另加。 */
    double strength(int j) {
        return view.seats().get(j).strength();
    }

    /** 估一个别人押得出的武器加值：面前的武器，加上手里按「没见过的牌」估的那部分，乘上他会押出来的成数。 */
    double hiddenPower(int j) {
        SeatView.SeatInfo s = view.seats().get(j);
        double front = 0;
        for (String c : s.front()) {
            front += view.catalog().get(c).weaponPower();
        }
        return COMMIT_RATE * (front + s.handCount() * weaponDensity);
    }

    /** 我自己手里与面前的武器加值合计。 */
    double myWeaponPower() {
        double p = 0;
        for (String c : view.hand()) {
            p += view.catalog().get(c).weaponPower();
        }
        for (String c : view.me().front()) {
            p += view.catalog().get(c).weaponPower();
        }
        return p;
    }

    /** 进攻方赢的机会（平手算被指的一方赢，所以门槛是多出半点）。 */
    static double pAttackWins(double attack, double defend, double sigma) {
        return logistic((attack - defend - 0.5) / sigma);
    }

    // ---------------------------------------------------------------- 恩怨

    /**
     * 恩怨：公开记事里别人对我（与我爱的人、我恨的人）做过的事，按种类折分、按天数衰减。
     *
     * <p>帮我、帮我爱的人、害我恨的人算恩；害我、害我爱的人、帮我恨的人算仇。
     * 折成「盼他活 / 盼他死」的一点倾向，最多 ±{@value #SOCIAL} 分 —— 它是倾向，不是计分，压不过爱恨。
     */
    static double[] social(SeatView view, SeatPolicySettings settings) {
        List<SeatView.SeatInfo> seats = view.seats();
        double[] regard = new double[seats.size()];
        CharacterId me = view.self();
        CharacterId love = view.love().orElse(null);
        CharacterId hate = view.hate().orElse(null);
        for (Deed d : view.deeds()) {
            if (d.actor().equals(me) || d.actor().equals(d.target())) {
                continue;
            }
            double relevance;
            if (d.target().equals(me)) {
                relevance = 1.0;
            } else if (d.target().equals(love)) {
                relevance = 0.5;
            } else if (d.target().equals(hate)) {
                relevance = -0.5;
            } else {
                continue;
            }
            double signed = relevance * weight(d);
            if (signed == 0) {
                continue;
            }
            double scaled = signed > 0 ? signed * settings.gratitude() : signed * settings.resentment();
            double decay = Math.pow(0.5, (view.turn() - d.turn()) / settings.memoryDays());
            for (int i = 0; i < seats.size(); i++) {
                if (seats.get(i).id().equals(d.actor())) {
                    regard[i] += scaled * decay;
                }
            }
        }
        double[] out = new double[regard.length];
        for (int i = 0; i < regard.length; i++) {
            out[i] = SOCIAL * Math.tanh(regard[i] / 2);
        }
        return out;
    }

    /** 一件事对被做的人是好是坏、多重：正 = 帮，负 = 害。 */
    static double weight(Deed d) {
        return switch (d.kind()) {
            case GAVE -> 1.0;
            case WATERED -> 1.0 * d.amount();
            case HEALED -> 1.5 * d.amount();
            case RING -> 2.0;
            case SIDED_WITH -> 1.0;
            case CONCEDED -> 0.5;
            case STEAL_DECLARED -> -1.0;
            case SWAP_DECLARED -> -0.4;
            case TOOK -> -1.0;
            case BEAT -> -1.0 * d.amount();
            case SIDED_AGAINST -> -1.0;
            case BAITED -> -1.5;
            case STEERED -> d.amount() >= 2 ? -0.6 : -0.2;
            case REFUSED -> -0.3;
            case OBJECTED -> -0.5;
            case SWAPPED -> 0.0;
        };
    }

    // ---------------------------------------------------------------- 牌

    static boolean isWater(Provision p) {
        return p.id().equals(Session.WATER);
    }

    /**
     * 这几张牌在我身上值几分财宝（照 {@code Scorer}：现金按张、美术品按面值、珠宝按套组表，替我翻倍的那一类乘上倍数）。
     */
    static double treasureOf(SeatView view, List<String> hand, List<String> front) {
        int jewels = 0;
        List<Integer> table = null;
        int jewelFactor = 1;
        double flat = 0;
        for (List<String> zone : List.of(hand, front)) {
            for (String c : zone) {
                Provision p = view.catalog().get(c);
                if (p.effect() instanceof ProvisionEffect.ScoreFlat f) {
                    flat += f.points() * factor(view, f.doubledBy());
                } else if (p.effect() instanceof ProvisionEffect.ScoreSet set) {
                    jewels++;
                    table = set.setTotals();
                    jewelFactor = factor(view, set.doubledBy());
                }
            }
        }
        double jewelry = 0;
        if (jewels > 0 && table != null) {
            jewelry = table.get(Math.min(jewels, table.size()) - 1) * jewelFactor;
        }
        return flat + jewelry;
    }

    private static int factor(SeatView view, String doubledBy) {
        if (!view.self().value().equals(doubledBy)) {
            return 1;
        }
        return view.me().ability() instanceof Ability.ScoreMultiplier m ? m.factor() : 1;
    }

    static double logistic(double x) {
        return 1 / (1 + Math.exp(-x));
    }
}
