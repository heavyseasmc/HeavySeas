package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Deed;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.seat.ActionChoice;
import io.github.heavyseasmc.engine.seat.PickChoice;
import io.github.heavyseasmc.engine.seat.SeatView;
import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.thirst.ThirstSource;
import io.github.heavyseasmc.mod.llm.ChoiceRequest;
import io.github.heavyseasmc.mod.llm.DecisionKind;
import io.github.heavyseasmc.mod.rulebook.RulebookData;
import io.github.heavyseasmc.mod.state.NavCardView;
import net.minecraft.text.PlainTextContent;
import net.minecraft.text.Text;
import net.minecraft.text.TextContent;
import net.minecraft.text.TranslatableTextContent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 大模型替身的问题：<b>只从这一座的视角</b>（{@link SeatView}）与合法选项拼出一个编了号的 {@link ChoiceRequest}。
 *
 * <h2>为什么只认视角</h2>
 * 视角在造出来的那一刻已经裁剪过（别人的手牌只剩张数、别人的爱恨不在里面）。这里只读它与合法选项 ——
 * 局面那一段、选项那一段写出来的每一个字都是这一座本来就看得见的。{@link Snapshot#take} 是唯一碰 {@link Session} 的地方
 * （在主线程上照相），{@code SeatQuestionsTest} 拿两局只差别人手牌与爱恨的局面核对：拼出来的请求一个字节都不差。
 *
 * <h2>说哪种话</h2>
 * 名字、牌名、天候都从 jar 里那份 lang 取（{@link Names}，与规则书同一个来源）；语言跟大模型的设置走。
 * 航海牌那一行用的是界面与播报那一份（{@link NavCardText}），这里只是把它写成纯文字 —— 不另写一份「这张牌今天会怎样」。
 */
public final class SeatQuestions {

    private SeatQuestions() {
    }

    // ---------------------------------------------------------------- 问题

    /**
     * 一个编了号的问题。{@code choices.get(i)} 就是第 {@code i + 1} 项选中时要做的那件事。
     *
     * @param kind      哪一种决定（决定规则摘录取哪几节）
     * @param choices   每一项对应的答案（与 {@code labels} 一一对应）
     * @param labels    每一项写给模型看的话
     * @param situation 这一座此刻看得到的局面
     */
    public record Question<T>(DecisionKind kind, List<T> choices, List<String> labels, String situation) {

        public Question {
            Objects.requireNonNull(kind, "kind");
            choices = Collections.unmodifiableList(new ArrayList<>(choices));
            labels = List.copyOf(labels);
            Objects.requireNonNull(situation, "situation");
            if (choices.size() != labels.size() || choices.isEmpty()) {
                throw new IllegalArgumentException("选项与说明对不上：" + choices.size() + " / " + labels.size());
            }
        }

        public ChoiceRequest request(String seatLabel, Instant deadline) {
            return new ChoiceRequest(seatLabel, kind, labels, situation, deadline);
        }
    }

    /**
     * 主线程上照的相：视角、合法选项、这一座叫什么。<b>只有这里碰 {@link Session}</b>，之后的一切都在工作线程上从它拼。
     *
     * @param legal     合法选项（随决定的种类而异：牌 id、{@link ActionChoice}、航海牌……）
     * @param seatLabel 这一座的名字（按大模型那一侧的语言）
     */
    public record Snapshot<L>(SeatView view, L legal, String seatLabel) {

        public static <L> Snapshot<L> take(Session session, CharacterId seat, L legal, Names names) {
            SeatView view = SeatView.of(session, seat);
            return new Snapshot<>(view, legal, names.character(seat));
        }
    }

    // ---------------------------------------------------------------- 名字

    /** 按一种语言取名字：jar 里那份 lang（服务端没有客户端的语言资源）。 */
    public static final class Names {

        private static final Map<String, Names> CACHE = new ConcurrentHashMap<>();

        private final String language;
        private final Map<String, String> lang;

        private Names(String language, Map<String, String> lang) {
            this.language = language;
            this.lang = Map.copyOf(lang);
        }

        public static Names of(String language) {
            return CACHE.computeIfAbsent(language, l -> new Names(l, RulebookData.langFile(l)));
        }

        public String language() {
            return language;
        }

        boolean en() {
            return language.equals("en_us");
        }

        /** lang 里那一条；没有就原样给键（看得见的错，不是安静的错）。 */
        String text(String key) {
            return lang.getOrDefault(key, key);
        }

        public String character(CharacterId id) {
            return text("heavyseas.character." + id.value());
        }

        String provision(String id) {
            return SeatView.HIDDEN.equals(id) ? (en() ? "a hidden card" : "一张暗牌") : text("heavyseas.provision." + id);
        }

        /** 把界面那一份 {@link Text} 写成纯文字：可翻译的按这份 lang 填，字面量原样留着。 */
        String render(Text text) {
            StringBuilder out = new StringBuilder();
            renderInto(text, out);
            return out.toString();
        }

        private static final Pattern ARG = Pattern.compile("%(?:(\\d+)\\$)?([sd%])");

        private void renderInto(Text text, StringBuilder out) {
            TextContent content = text.getContent();
            if (content instanceof TranslatableTextContent t) {
                String pattern = lang.get(t.getKey());
                if (pattern == null) {
                    pattern = t.getFallback() != null ? t.getFallback() : t.getKey();
                }
                Object[] args = t.getArgs();
                Matcher m = ARG.matcher(pattern);
                int next = 0;
                int last = 0;
                while (m.find()) {
                    out.append(pattern, last, m.start());
                    last = m.end();
                    if ("%".equals(m.group(2))) {
                        out.append('%');
                        continue;
                    }
                    int index = m.group(1) != null ? Integer.parseInt(m.group(1)) - 1 : next++;
                    Object arg = index >= 0 && index < args.length ? args[index] : "";
                    if (arg instanceof Text inner) {
                        renderInto(inner, out);
                    } else {
                        out.append(arg);
                    }
                }
                out.append(pattern, last, pattern.length());
            } else if (content instanceof PlainTextContent plain) {
                out.append(plain.string());
            }
            for (Text sibling : text.getSiblings()) {
                renderInto(sibling, out);
            }
        }
    }

    // ---------------------------------------------------------------- 每一种决定

    public static Question<String> provision(SeatView v, List<String> offer, Names n) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        offer.forEach(card -> counts.merge(card, 1, Integer::sum));
        List<String> choices = new ArrayList<>(counts.keySet());
        List<String> labels = choices.stream().map(card -> n.provision(card) + (counts.get(card) > 1
                ? (n.en() ? " (" + counts.get(card) + " in the crate)" : "（箱里 " + counts.get(card) + " 张）") : ""))
                .toList();
        return new Question<>(DecisionKind.PROVISION, choices, labels, situation(v, n));
    }

    public static Question<ActionChoice> action(SeatView v, List<ActionChoice> legal, Names n) {
        List<String> labels = legal.stream().map(choice -> switch (choice) {
            case ActionChoice.Pass pass -> n.en() ? "Do nothing" : "什么也不做";
            case ActionChoice.Row row -> n.en() ? "Row" : "划船";
            case ActionChoice.Declare d -> d.kind() == Contest.Kind.SWAP
                    ? (n.en() ? "Swap seats with " + n.character(d.target()) : "和" + n.character(d.target()) + "换座位")
                    : (n.en() ? "Steal from " + n.character(d.target()) : "抢" + n.character(d.target()));
            case ActionChoice.Play play -> play.target()
                    .map(t -> n.en() ? "Use " + n.provision(play.card()) + " on " + n.character(t)
                            : "用" + n.provision(play.card()) + "治" + n.character(t))
                    .orElse(n.en() ? "Play " + n.provision(play.card()) : "打出" + n.provision(play.card()));
        }).toList();
        return new Question<>(DecisionKind.ACTION, legal, labels, situation(v, n));
    }

    /** 划船摸到的牌：答案是下标。 */
    public static Question<Integer> row(SeatView v, List<NavigationCard> drawn, Names n) {
        List<Integer> choices = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < drawn.size(); i++) {
            choices.add(i);
            labels.add(navCard(v, drawn.get(i), n));
        }
        return new Question<>(DecisionKind.ROW, choices, labels, situation(v, n));
    }

    public static Question<NavigationCard> helm(SeatView v, List<NavigationCard> stack, Names n) {
        return new Question<>(DecisionKind.HELM, stack, stack.stream().map(c -> navCard(v, c, n)).toList(),
                situation(v, n));
    }

    /** 表态：答案 {@code true} = 拒绝 / 反对（打起来）。 */
    public static Question<Boolean> consent(SeatView v, Names n) {
        Contest.Kind kind = v.contest().map(SeatView.ContestInfo::kind).orElse(Contest.Kind.STEAL);
        String agree = switch (kind) {
            case SWAP -> n.en() ? "Agree (swap seats with them)" : "同意（和他换座位）";
            case STEAL -> n.en() ? "Agree (let them take a card)" : "同意（让他拿走一张）";
            case RATION -> n.en() ? "Do not object (everyone shares the ration)" : "不反对（大家分食）";
        };
        String fight = kind == Contest.Kind.RATION
                ? (n.en() ? "Object (fight over it)" : "反对，打一架")
                : (n.en() ? "Refuse (fight over it)" : "拒绝，打一架");
        return new Question<>(DecisionKind.CONTEST_ANSWER, List.of(false, true), List.of(agree, fight), situation(v, n));
    }

    public static Question<Optional<Fight.Side>> stance(SeatView v, Names n) {
        String attacker = v.contest().map(c -> n.character(c.attacker())).orElse("?");
        String target = v.contest().map(c -> n.character(c.target())).orElse("?");
        return new Question<>(DecisionKind.CONTEST_JOIN,
                List.of(Optional.empty(), Optional.of(Fight.Side.ATTACK), Optional.of(Fight.Side.DEFEND)),
                List.of(n.en() ? "Stay out" : "不加入",
                        n.en() ? "Join the attackers (" + attacker + "'s side)" : "加入进攻方（" + attacker + "那边）",
                        n.en() ? "Join the defenders (" + target + "'s side)" : "加入防守方（" + target + "那边）"),
                situation(v, n));
    }

    /** 押武器：每一种「押哪几张」各一项（同名的牌不分先后）。太多时只列「不押 · 各押一张 · 全押」。 */
    public static Question<List<String>> weapons(SeatView v, List<String> weapons, Names n) {
        List<List<String>> subsets = new ArrayList<>();
        subsets.add(List.of());
        Map<String, Integer> counts = new LinkedHashMap<>();
        weapons.forEach(card -> counts.merge(card, 1, Integer::sum));
        List<Map.Entry<String, Integer>> kinds = new ArrayList<>(counts.entrySet());
        int total = kinds.stream().mapToInt(e -> e.getValue() + 1).reduce(1, (a, b) -> a * b);
        if (total <= 16) {
            enumerate(kinds, 0, new ArrayList<>(), subsets);
        } else {
            new LinkedHashSet<>(weapons).forEach(card -> subsets.add(List.of(card)));
            subsets.add(List.copyOf(weapons));
        }
        List<String> labels = subsets.stream().map(s -> s.isEmpty() ? (n.en() ? "Commit nothing" : "不押")
                : (n.en() ? "Commit " : "押") + s.stream().map(n::provision)
                .collect(Collectors.joining(n.en() ? " + " : "、"))).toList();
        return new Question<>(DecisionKind.CONTEST_WEAPON, subsets, labels, situation(v, n));
    }

    private static void enumerate(List<Map.Entry<String, Integer>> kinds, int at, List<String> current,
                                  List<List<String>> out) {
        if (at == kinds.size()) {
            if (!current.isEmpty()) {
                out.add(List.copyOf(current));
            }
            return;
        }
        Map.Entry<String, Integer> kind = kinds.get(at);
        for (int k = 0; k <= kind.getValue(); k++) {
            List<String> next = new ArrayList<>(current);
            for (int i = 0; i < k; i++) {
                next.add(kind.getKey());
            }
            enumerate(kinds, at + 1, next, out);
        }
    }

    public static Question<PickChoice> pick(SeatView v, List<PickChoice> legal, Names n) {
        List<String> labels = legal.stream().map(p -> switch (p) {
            case PickChoice.FromFront f -> n.en() ? "Take the " + n.provision(f.card()) + " in front of them"
                    : "拿走他面前的" + n.provision(f.card());
            case PickChoice.FromHand h -> n.en() ? "Draw a random card from their hand" : "从他手里随机摸一张";
        }).toList();
        return new Question<>(DecisionKind.STEAL_PICK, legal, labels, situation(v, n));
    }

    public static Question<Optional<Session.OverboardPlay>> overboard(SeatView v, List<Session.OverboardPlay> plays,
                                                                       Names n) {
        List<Optional<Session.OverboardPlay>> choices = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        choices.add(Optional.empty());
        labels.add(n.en() ? "Play nothing" : "不出牌");
        for (Session.OverboardPlay play : plays) {
            choices.add(Optional.of(play));
            boolean bait = v.catalog().get(play.card()).effect() instanceof ProvisionEffect.DamageInWater;
            labels.add(bait
                    ? (n.en() ? "Play " + n.provision(play.card()) + " (everyone in this fall takes 1 more)"
                    : "打出" + n.provision(play.card()) + "（这一批落海的人各多挨 1 点）")
                    : (n.en() ? "Throw " + n.provision(play.card()) + " to " + n.character(play.target())
                    : "把" + n.provision(play.card()) + "扔给" + n.character(play.target())));
        }
        return new Question<>(DecisionKind.OVERBOARD, choices, labels, situation(v, n));
    }

    /** 口渴：自己化解几次（{@code 0..maxUnits}）。 */
    public static Question<Integer> thirst(SeatView v, int maxUnits, Names n) {
        int remaining = v.thirst().map(SeatView.ThirstInfo::remaining).orElse(maxUnits);
        int per = v.thirst().map(SeatView.ThirstInfo::waterPerSource).orElse(1);
        List<Integer> choices = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int k = 0; k <= maxUnits; k++) {
            choices.add(k);
            int hurt = Math.max(0, remaining - k);
            labels.add(n.en() ? "Drink " + k * per + " water (cancel " + k + ", take " + hurt + " damage)"
                    : "喝 " + k * per + " 张水（化解 " + k + " 次，挨 " + hurt + " 点）");
        }
        return new Question<>(DecisionKind.THIRST, choices, labels, situation(v, n));
    }

    /** 递水：替 {@code drinker} 化解几次（{@code 0..maxUnits}）。 */
    public static Question<Integer> donate(SeatView v, CharacterId drinker, int maxUnits, Names n) {
        int per = v.thirst().map(SeatView.ThirstInfo::waterPerSource).orElse(1);
        List<Integer> choices = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int k = 0; k <= maxUnits; k++) {
            choices.add(k);
            labels.add(k == 0 ? (n.en() ? "Give nothing" : "不递")
                    : n.en() ? "Hand " + n.character(drinker) + " " + k * per + " water"
                    : "递 " + k * per + " 张水给" + n.character(drinker));
        }
        return new Question<>(DecisionKind.GIVE_WATER, choices, labels, situation(v, n));
    }

    /** 一张航海牌今天会怎样（界面那一份，按今天的天候改写过），写成纯文字。 */
    static String navCard(SeatView v, NavigationCard card, Names n) {
        List<String> order = v.seats().stream().map(s -> s.id().value()).toList();
        String weather = v.weather().map(w -> w.effect().id()).orElse("");
        return n.render(NavCardText.describe(NavCardView.of(card), order, weather));
    }

    // ---------------------------------------------------------------- 局面

    /** 这一座此刻看得到的局面。只读视角。 */
    public static String situation(SeatView v, Names n) {
        boolean en = n.en();
        StringBuilder sb = new StringBuilder();
        String phase = n.text(switch (v.phase()) {
            case WEATHER -> "heavyseas.phase.weather";
            case PROVISION -> "heavyseas.phase.provision";
            case ACTION -> "heavyseas.phase.action";
            case NAVIGATION -> "heavyseas.phase.navigation";
        });
        String weather = v.weather().map(w -> n.text("heavyseas.weather." + w.id()) + (en ? " (" : "（")
                + n.text("heavyseas.weather.effect." + w.id()) + (en ? ")" : "）")).orElse(en ? "not turned yet" : "还没翻");
        sb.append(en ? "Day " + v.turn() + " · " + phase + " · Weather: " + weather
                : "第 " + v.turn() + " 天 · " + phase + " · 天候：" + weather).append('\n');
        sb.append(en ? "Gulls: " + v.gulls() + " (the boat reaches land at 4)" : "海鸥：" + v.gulls() + " 只（凑满 4 只就靠岸）")
                .append('\n');

        SeatView.SeatInfo me = v.me();
        int position = v.onBoat().indexOf(me) + 1;
        sb.append(en ? "You: " + n.character(v.self()) + ", seat " + position + " from the bow, health "
                + me.margin() + " (Size " + me.size() + ", Survival " + me.survival() + "), " + condition(me, n)
                : "你：" + n.character(v.self()) + "，从船头数第 " + position + " 个座位，体力 " + me.margin()
                + "（体型 " + me.size() + "，生存分 " + me.survival() + "），" + condition(me, n)).append('\n');
        sb.append(en ? "In your hand: " + cards(v.hand(), n) + "; in front of you: " + front(me, n)
                : "你手里：" + cards(v.hand(), n) + "；你面前：" + front(me, n)).append('\n');
        sb.append(en ? "In your heart: you love " + who(v.love(), n) + "; you hate " + who(v.hate(), n)
                : "你心里：爱 " + who(v.love(), n) + "；恨 " + who(v.hate(), n)).append('\n');

        sb.append(en ? "In the boat, bow to stern:" : "艇上从船头到船尾：").append('\n');
        List<String> gone = new ArrayList<>();
        for (SeatView.SeatInfo s : v.seats()) {
            if (s.removed()) {
                gone.add(n.character(s.id()));
                continue;
            }
            sb.append("- ").append(n.character(s.id())).append(s.id().equals(v.self()) ? (en ? " (you)" : "（你）") : "")
                    .append(en ? ": health " + s.margin() + "/" + s.size() + ", " : "：体力 " + s.margin() + "/" + s.size() + "，")
                    .append(condition(s, n));
            if (s.acted()) {
                sb.append(en ? ", has acted today" : "，今天行动过");
            }
            sb.append(en ? "; in front: " + front(s, n) + "; " + s.handCount() + " card(s) in hand"
                    : "；面前：" + front(s, n) + "；手里 " + s.handCount() + " 张");
            if (!s.thirst().isEmpty()) {
                sb.append(en ? "; today: " : "；今天：").append(s.thirst().stream().sorted().map(t -> mark(t, n))
                        .collect(Collectors.joining(en ? ", " : "、")));
            }
            sb.append('\n');
        }
        if (!gone.isEmpty()) {
            sb.append(en ? "Taken by the sea: " : "被大海带走的：").append(String.join(en ? ", " : "、", gone)).append('\n');
        }
        v.helmsman().ifPresent(h -> sb.append(en ? "Helmsman: " : "舵手：").append(n.character(h)).append('\n'));
        SeatView.TableInfo t = v.table();
        sb.append(en ? "Table: navigation deck " + t.navPile() + " · rowing pile " + t.rowStack() + " · provision deck "
                + t.provisionsLeft() : "桌面：航海牌堆 " + t.navPile() + " 张 · 划船堆 " + t.rowStack() + " 张 · 物资牌堆 "
                + t.provisionsLeft() + " 张").append('\n');

        v.box().ifPresent(box -> sb.append(en ? "Supply crate: with " + n.character(box.holder()) + " ("
                + (box.index() + 1) + " of " + box.chain().size() + "), " + box.offerSize() + " card(s) left"
                : "补给箱：传到" + n.character(box.holder()) + "手上（第 " + (box.index() + 1) + " 位，共 "
                + box.chain().size() + " 位），箱里还剩 " + box.offerSize() + " 张").append('\n'));
        v.rowing().ifPresent(r -> sb.append(en ? n.character(r.rower()) + " is rowing (drew " + r.drawnCount() + ")"
                : n.character(r.rower()) + "在划船（摸了 " + r.drawnCount() + " 张）").append('\n'));
        v.contest().ifPresent(c -> sb.append(contest(v, c, n)).append('\n'));
        v.overboard().ifPresent(o -> sb.append(en ? "Overboard now: " + names(o.swimmers(), n)
                + (o.bait() > 0 ? "; " + n.provision("bait_bucket") + " already played (+" + o.bait() + ")" : "")
                : "正在落海：" + names(o.swimmers(), n) + (o.bait() > 0 ? "；已经有人打出" + n.provision("bait_bucket") + "（+" + o.bait() + "）" : ""))
                .append('\n'));
        v.thirst().ifPresent(th -> sb.append(en ? n.character(th.who()) + " is settling thirst: " + th.remaining()
                + " left to cancel (" + th.waterPerSource() + " water each)"
                + (th.covered() > 0 ? ", parasol covered " + th.covered() : "")
                + (th.shared() > 0 ? ", shared " + th.shared() : "")
                : n.character(th.who()) + "在结算口渴：还要化解 " + th.remaining() + " 次（每次 " + th.waterPerSource() + " 张水）"
                + (th.covered() > 0 ? "，阳伞挡掉 " + th.covered() + " 次" : "")
                + (th.shared() > 0 ? "，蹭到 " + th.shared() + " 次" : "")).append('\n'));

        List<Deed> deeds = v.deeds();
        if (!deeds.isEmpty()) {
            List<Deed> recent = deeds.subList(Math.max(0, deeds.size() - 12), deeds.size());
            sb.append(en ? "What happened (latest " + recent.size() + "):" : "发生过（最近 " + recent.size() + " 件）：")
                    .append('\n');
            for (Deed d : recent) {
                sb.append("- ").append(en ? "Day " + d.turn() + ": " : "第 " + d.turn() + " 天：").append(deed(d, n))
                        .append('\n');
            }
        }
        return sb.toString().strip();
    }

    private static String contest(SeatView v, SeatView.ContestInfo c, Names n) {
        boolean en = n.en();
        String what = switch (c.kind()) {
            case SWAP -> en ? n.character(c.attacker()) + " wants to swap seats with " + n.character(c.target())
                    : n.character(c.attacker()) + "要和" + n.character(c.target()) + "换座位";
            case STEAL -> en ? n.character(c.attacker()) + " is stealing from " + n.character(c.target())
                    : n.character(c.attacker()) + "要抢" + n.character(c.target());
            case RATION -> en ? n.character(c.attacker()) + " played " + n.provision(c.rationCard())
                    + "; asking " + n.character(c.target()) + " whether they object"
                    : n.character(c.attacker()) + "打出" + n.provision(c.rationCard()) + "，在问" + n.character(c.target())
                    + "反不反对";
        };
        String stage = switch (c.stage()) {
            case CONSENT -> en ? "waiting for an answer" : "等表态";
            case STANCES -> en ? "taking sides" : "站队中";
            case WEAPONS -> en ? "committing weapons" : "押武器中";
            case PICK -> en ? "picking a card" : "挑牌中";
        };
        StringBuilder sb = new StringBuilder(en ? "Fight: " : "这一场：").append(what).append(en ? " — " : "，").append(stage);
        if (!c.attackSide().isEmpty() || !c.defendSide().isEmpty()) {
            sb.append(en ? "; attackers " : "；进攻方 ").append(side(v, c.attackSide(), n))
                    .append(en ? "; defenders " : "；防守方 ").append(side(v, c.defendSide(), n))
                    .append(en ? " (a tie goes to the defenders)" : "（平手算防守方赢）");
        }
        // 按座位排（不按那张表的次序：Map.copyOf 的次序每次起 JVM 都不同，同一个局面就会拼出两种问题）
        String committed = v.seats().stream().map(SeatView.SeatInfo::id)
                .filter(id -> c.committedCount().getOrDefault(id, 0) > 0)
                .map(id -> n.character(id) + " ×" + c.committedCount().get(id))
                .collect(Collectors.joining(en ? ", " : "、"));
        if (!committed.isEmpty()) {
            sb.append(en ? "; committed face down: " : "；暗押：").append(committed);
        }
        if (!c.mine().isEmpty()) {
            sb.append(en ? "; you committed " : "；你押了").append(cards(c.mine(), n));
        }
        return sb.toString();
    }

    private static String side(SeatView v, List<CharacterId> ids, Names n) {
        int strength = ids.stream().mapToInt(id -> v.info(id).strength()).sum();
        return (ids.isEmpty() ? (n.en() ? "nobody" : "没人") : names(ids, n))
                + (n.en() ? " (strength " + strength + ")" : "（力气合计 " + strength + "）");
    }

    private static String deed(Deed d, Names n) {
        String a = n.character(d.actor());
        String t = n.character(d.target());
        boolean en = n.en();
        return switch (d.kind()) {
            case STEAL_DECLARED -> en ? a + " declared a steal on " + t : a + "要抢" + t;
            case SWAP_DECLARED -> en ? a + " asked to swap seats with " + t : a + "要和" + t + "换座位";
            case REFUSED -> en ? a + " refused " + t : a + "拒绝了" + t;
            case CONCEDED -> en ? a + " agreed to " + t : a + "同意了" + t;
            case OBJECTED -> en ? a + " objected to " + t + "'s ration" : a + "反对" + t + "的分食";
            case SIDED_WITH -> en ? a + " sided with " + t : a + "站到" + t + "那边";
            case SIDED_AGAINST -> en ? a + " sided against " + t : a + "站到" + t + "的对面";
            case BEAT -> en ? a + "'s side won; " + t + " took " + d.amount() : a + "那边打赢了，" + t + "挨了 " + d.amount() + " 点";
            case TOOK -> en ? a + " took a card from " + t : a + "抢到了" + t + "一张牌";
            case SWAPPED -> en ? a + " swapped seats with " + t : a + "和" + t + "换了座位";
            case GAVE -> en ? a + " gave " + t + " a card" : a + "送了" + t + "一张牌";
            case WATERED -> en ? a + " handed " + t + " " + d.amount() + " water" : a + "给口渴的" + t + "递了 " + d.amount() + " 张水";
            case HEALED -> en ? a + " healed " + t + " by " + d.amount() : a + "给" + t + "回了 " + d.amount() + " 点体力";
            case RING -> en ? a + " threw a life preserver to " + t : a + "把救生圈扔给了" + t;
            case BAITED -> en ? a + " played " + n.provision("bait_bucket") + "; " + t + " took 1 more"
                    : a + "打出" + n.provision("bait_bucket") + "，" + t + "多挨一点";
            case STEERED -> en ? "helmsman " + a + "'s card sent " + t + " overboard" : "舵手" + a + "挑的牌把" + t + "送下了水";
        };
    }

    private static String condition(SeatView.SeatInfo s, Names n) {
        String c = n.text(switch (s.condition()) {
            case CONSCIOUS -> "heavyseas.condition.conscious";
            case UNCONSCIOUS -> "heavyseas.condition.unconscious";
            case DEAD -> "heavyseas.condition.dead";
        });
        return s.offline() && s.condition() != Condition.DEAD ? c + (n.en() ? ", offline" : "，掉线") : c;
    }

    private static String mark(ThirstSource source, Names n) {
        boolean en = n.en();
        return switch (source) {
            case ROWED -> en ? "rowed" : "划过船";
            case FOUGHT -> en ? "fought" : "打过架";
            case NAMED -> en ? "named by the card" : "牌上点了名";
            case DRANK_RUM -> en ? "drank rum" : "喝过酒";
            case WEATHER -> en ? "the weather" : "天候";
        };
    }

    private static String front(SeatView.SeatInfo s, Names n) {
        if (s.front().isEmpty()) {
            return n.en() ? "nothing" : "没有";
        }
        return s.front().stream().map(card -> n.provision(card) + (s.opened().contains(card)
                ? (n.en() ? " (open)" : "（撑开）") : s.used().contains(card) ? (n.en() ? " (used today)" : "（今天用过）") : ""))
                .collect(Collectors.joining(n.en() ? ", " : "、"));
    }

    private static String cards(List<String> cards, Names n) {
        return cards.isEmpty() ? (n.en() ? "nothing" : "没有")
                : cards.stream().map(n::provision).collect(Collectors.joining(n.en() ? ", " : "、"));
    }

    private static String names(List<CharacterId> ids, Names n) {
        return ids.stream().map(n::character).collect(Collectors.joining(n.en() ? ", " : "、"));
    }

    private static String who(Optional<CharacterId> id, Names n) {
        return id.map(n::character).orElse(n.en() ? "(not dealt yet)" : "（还没发）");
    }
}
