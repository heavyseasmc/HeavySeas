package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.sim.NavigationPolicy;
import io.github.heavyseasmc.engine.state.Fight;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * 随机席位：<b>搬家前那个模拟器的随机分布，一个随机数都不差</b>。
 *
 * <h2>它是对照组，不是「玩得蠢」的模型</h2>
 * 模拟器的随机策略刻意做蠢事（明明有水也可能不喝、能赢的架也可能不打），为的是把状态空间铺开（见 {@code Simulator}）。
 * 会动脑的替身要证明自己「更聪明」，比的就是它。
 *
 * <h2>❗每一步消费几次随机数是钉死的</h2>
 * 下面每个方法都照着原先 {@code Simulator} 里对应的那几行写：先摇什么、什么时候不摇、摇几次。
 * {@code RandomSeatsReproduceTest} 拿几千局的全部结果核对，差一步就红。改这里之前先读那个测试的注释。
 *
 * <p>划船留哪张、舵手挑哪张照旧交给 {@link NavigationPolicy}（O1 测量台的那几种取向），它拿到的是视角拼出来的
 * {@link SeatView#state()}：别人的手牌已经换成占位，而那几种取向本来就只读座位、伤势与自己的口渴标记。
 *
 * <p>没有按局的状态：一个实例全船共用、哪个线程都能调；随机数全从参数里的 {@code rng} 拿。
 */
public final class RandomSeatPolicy implements SeatPolicy {

    private final NavigationPolicy navigation;

    public RandomSeatPolicy(NavigationPolicy navigation) {
        this.navigation = Objects.requireNonNull(navigation, "navigation");
    }

    /** 划船留牌与舵手挑牌全看运气的那一种（对照组）。 */
    public static RandomSeatPolicy indifferent() {
        return new RandomSeatPolicy(NavigationPolicy.INDIFFERENT);
    }

    public NavigationPolicy navigation() {
        return navigation;
    }

    /** 原 {@code Simulator#provision}：箱子里随机留一张。 */
    @Override
    public String keepProvision(SeatView view, List<String> offer, Random rng) {
        return offer.get(rng.nextInt(offer.size()));
    }

    /** 原 {@code maybeReveal}：手里没牌不摇；有牌三分之一亮一张，亮哪张随机。 */
    @Override
    public Optional<String> reveal(SeatView view, List<String> revealable, Random rng) {
        if (revealable.isEmpty() || rng.nextInt(3) != 0) {
            return Optional.empty();
        }
        return Optional.of(revealable.get(rng.nextInt(revealable.size())));
    }

    /** 原 {@code maybeDrink}：<b>先摇再看有没有酒</b>（四分之一），喝第一瓶今天还没喝过的。 */
    @Override
    public Optional<String> drink(SeatView view, List<String> drinkable, Random rng) {
        if (rng.nextInt(4) != 0) {
            return Optional.empty();
        }
        return drinkable.isEmpty() ? Optional.empty() : Optional.of(drinkable.getFirst());
    }

    /**
     * 原 {@code Simulator#action} 的五选一：什么也不做 · 划船 · 换座位 · 打特殊牌（打不出就抢）· 抢。
     *
     * <p>❗没有对象时<b>不摇</b>「挑谁」那一次（原 {@code contest} 开头就返回）。
     * 风平浪静不能划船时退回什么也不做 —— 原模拟器没有天候，这一条只在带天候的局里走得到。
     */
    @Override
    public ActionChoice act(SeatView view, List<ActionChoice> legal, Random rng) {
        return switch (rng.nextInt(5)) {
            case 0 -> ActionChoice.PASS;
            case 1 -> legal.contains(ActionChoice.ROW) ? ActionChoice.ROW : ActionChoice.PASS;
            case 2 -> declare(legal, Contest.Kind.SWAP, rng);
            case 3 -> special(view, legal, rng).orElseGet(() -> declare(legal, Contest.Kind.STEAL, rng));
            default -> declare(legal, Contest.Kind.STEAL, rng);
        };
    }

    /** 原 {@code contest} 的开头：对象是艇上除自己以外的人（含昏迷的与尸体），随机挑一个。 */
    private static ActionChoice declare(List<ActionChoice> legal, Contest.Kind kind, Random rng) {
        List<ActionChoice> targets = legal.stream()
                .filter(c -> c instanceof ActionChoice.Declare d && d.kind() == kind).toList();
        if (targets.isEmpty()) {
            return ActionChoice.PASS;
        }
        return targets.get(rng.nextInt(targets.size()));
    }

    /**
     * 原 {@code maybeSpecial}：手里与面前每一张能花行动打出的牌（重复的照样各算一张）里随机抽，
     * 抽到打不出的就扔掉再抽，直到打出一张或抽完。医疗箱治船头起第一个能治的人。
     */
    private static Optional<ActionChoice> special(SeatView view, List<ActionChoice> legal, Random rng) {
        List<String> playable = new ArrayList<>();
        for (String card : view.hand()) {
            if (view.catalog().get(card).isSpecialAction()) {
                playable.add(card);
            }
        }
        for (String card : view.me().front()) {
            if (view.catalog().get(card).isSpecialAction()) {
                playable.add(card);
            }
        }
        while (!playable.isEmpty()) {
            String card = playable.remove(rng.nextInt(playable.size()));
            for (ActionChoice c : legal) {
                if (c instanceof ActionChoice.Play p && p.card().equals(card)) {
                    return Optional.of(c);           // 合法清单里医疗箱的对象按座位排，第一项就是原来那一个
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public int keepRowCard(SeatView view, List<NavigationCard> drawn, Random rng) {
        return navigation.chooseWhenRowing(drawn, view.state(), view.self(), rng);
    }

    /**
     * 原 {@code contest}：被指的人一半一半拒绝。
     * 分食的反对原模拟器从来不问（它走的是不问人的 {@code useRation}）—— 这里一律不反对，<b>不摇</b>。
     */
    @Override
    public boolean refuse(SeatView view, Random rng) {
        if (view.contest().map(c -> c.kind() == Contest.Kind.RATION).orElse(false)) {
            return false;
        }
        return rng.nextBoolean();
    }

    /** 原 {@code contest}：每个旁观的清醒者三分之一加入，哪边各一半。 */
    @Override
    public Optional<Fight.Side> joinStance(SeatView view, Random rng) {
        if (rng.nextInt(3) != 0) {
            return Optional.empty();
        }
        return Optional.of(rng.nextBoolean() ? Fight.Side.ATTACK : Fight.Side.DEFEND);
    }

    /** 原 {@code contest}：每一张武器三分之一押下。 */
    @Override
    public List<String> commitWeapons(SeatView view, List<String> weapons, Random rng) {
        List<String> out = new ArrayList<>();
        for (String card : weapons) {
            if (rng.nextInt(3) == 0) {
                out.add(card);
            }
        }
        return out;
    }

    /**
     * 原 {@code contest} 的挑牌：面前有牌、手里也有牌时一半一半；只有一边有牌就挑那一边。面前的随机指一张。
     * 小孩的偷窃只有「从手里摸」一项。
     */
    @Override
    public PickChoice pick(SeatView view, List<PickChoice> legal, Random rng) {
        List<PickChoice> front = legal.stream().filter(p -> p instanceof PickChoice.FromFront).toList();
        boolean hand = legal.contains(PickChoice.FROM_HAND);
        if (!front.isEmpty() && (!hand || rng.nextBoolean())) {
            return front.get(rng.nextInt(front.size()));
        }
        return PickChoice.FROM_HAND;
    }

    @Override
    public NavigationCard steer(SeatView view, List<NavigationCard> rowStack, Random rng) {
        return navigation.pick(rowStack, view.state(), view.self(), rng);
    }

    /**
     * 原 {@code Simulator#water}：自己有水就随机喝 0..全部（<b>先摇再按还差几次封顶</b>，所以「不差」时照样摇一次）；
     * 还差的话四分之一开口要水。
     */
    @Override
    public WaterPlan drinkWater(SeatView view, int ownUnits, Random rng) {
        int remaining = view.thirst().orElseThrow().remaining();
        int units = Math.min(remaining, ownUnits == 0 ? 0 : rng.nextInt(ownUnits + 1));
        boolean ask = units < remaining && rng.nextInt(4) == 0;
        return new WaterPlan(units, ask);
    }

    /** 原 {@code Simulator#water}：有人开口时，船头起第一个有水的清醒者递一张，其余人不递。不摇。 */
    @Override
    public int donateWater(SeatView view, CharacterId drinker, int shortUnits, int myUnits, boolean helpAsked,
                           int donatedSoFar, Random rng) {
        return helpAsked && donatedSoFar == 0 && myUnits > 0 && shortUnits > 0 ? 1 : 0;
    }

    @Override
    public String label() {
        return "随机（" + navigation.label() + "）";
    }
}
