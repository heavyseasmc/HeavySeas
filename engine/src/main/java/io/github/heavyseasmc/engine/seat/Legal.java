package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.engine.state.SurvivorState;
import io.github.heavyseasmc.engine.weather.WeatherEffect;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 每个决定此刻<b>合法</b>的选项，由引擎算好再交给策略（策略自己拼不出也不该拼）。
 *
 * <h2>判据与 {@link Session} 同源</h2>
 * 每一条都照 {@code Session} 里对应动作的守卫写：它会抛的，这里就不列。
 * 两边各写一份判据迟早分家 —— 所以 {@code LegalTest} 在几千局随机局面里把每一个列出来的选项真的做一遍，
 * 引擎一次都不许抛；并在同一批局面里核对「没列出来的」确实做不了。
 *
 * <h2>「合法」还排掉了白做的事</h2>
 * 再撑一次已经撑开的伞、在没有伤员时打医疗箱，规则上要么抛、要么只是白花一个行动。
 * 模拟器一直把它们当「打不出来」（{@code Simulator#playSpecial} 返回 false），这里沿用同一个口径。
 *
 * <p>列出来的次序是<b>固定的</b>（船头 → 船尾、手里在前面前在后）：随机席位按下标挑，次序一变，同一个种子就跑出另一局。
 */
public final class Legal {

    private Legal() {
    }

    /** 轮到 {@code actor} 行动时能做的事：什么也不做 · 划船 · 换座位 · 抢 · 用物资。 */
    public static List<ActionChoice> actions(Session session, CharacterId actor) {
        GameState g = session.state();
        List<ActionChoice> out = new ArrayList<>();
        out.add(ActionChoice.PASS);
        if (!becalmed(session)) {
            out.add(ActionChoice.ROW);
        }
        List<CharacterId> targets = new ArrayList<>(g.onBoatBySeat());
        targets.remove(actor);
        for (CharacterId t : targets) {
            out.add(new ActionChoice.Declare(Contest.Kind.SWAP, t));
        }
        for (CharacterId t : targets) {
            out.add(new ActionChoice.Declare(Contest.Kind.STEAL, t));
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String card : available(g.stateOf(actor))) {
            Provision p = session.provisions().get(card);
            if (!p.isSpecialAction() || !seen.add(card)) {
                continue;
            }
            out.addAll(plays(session, actor, card));
        }
        return List.copyOf(out);
    }

    /** 这一张特殊牌现在打得出的全部用法（医疗箱每个能治的人一项）；打不出为空。 */
    static List<ActionChoice> plays(Session session, CharacterId actor, String card) {
        GameState g = session.state();
        ProvisionEffect effect = session.provisions().get(card).effect();
        List<ActionChoice> out = new ArrayList<>();
        if (effect instanceof ProvisionEffect.Heal) {
            // 医疗箱只能治「受了伤而且还活着」的人（治没受伤的人引擎会抛，治尸体也会）。被大海带走的人算死了。
            for (CharacterId t : g.bySeat()) {
                if (g.stateOf(t).damage() > 0 && g.conditionOf(t) != Condition.DEAD) {
                    out.add(new ActionChoice.Play(card, Optional.of(t)));
                }
            }
        } else if (effect instanceof ProvisionEffect.PreventThirst cover) {
            if (cover.requiresOpen() && !g.stateOf(actor).isOpen(card)) {
                out.add(new ActionChoice.Play(card, Optional.empty()));
            }
        } else if (effect instanceof ProvisionEffect.HealAll heal) {
            boolean corpse = g.onBoatBySeat().stream().anyMatch(id -> g.conditionOf(id) == Condition.DEAD);
            if (!heal.requiresCorpse() || corpse) {
                out.add(new ActionChoice.Play(card, Optional.empty()));
            }
        } else if (effect instanceof ProvisionEffect.WeaponOrSpecial) {
            out.add(new ActionChoice.Play(card, Optional.empty()));
        }
        return out;
    }

    /** 亮得出的牌：手里的每一张（可以重复：两张水是两项）。 */
    public static List<String> reveals(Session session, CharacterId who) {
        if (!session.state().canAct(who) || pickingFrom(session, who)) {
            return List.of();
        }
        return session.state().stateOf(who).hand();
    }

    /**
     * 喝得了的酒：手里与面前、今天还没喝过的那几张，手里在前。
     *
     * <p>「今天喝过的不列」照原模拟器的口径（{@code Simulator#maybeDrink}），不看 {@code once_per_turn}：
     * 数据里的酒都是一天一次，而列不列会改变随机席位挑的是哪一瓶。
     */
    public static List<String> drinks(Session session, CharacterId who) {
        if (!session.state().canAct(who)) {
            return List.of();
        }
        SurvivorState s = session.state().stateOf(who);
        List<String> out = new ArrayList<>();
        for (String card : available(s)) {
            if (session.provisions().get(card).effect() instanceof ProvisionEffect.BuffSize
                    && !s.usedThisTurn(card)) {
                out.add(card);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 送得出的牌：行动阶段、没有一场在进行、自己清醒，送给还在艇上的任何别人（昏迷的、尸体都行）。
     * 每张不同的牌、每个来源区、每个收的人一项。
     */
    public static List<Gift> gifts(Session session, CharacterId from) {
        GameState g = session.state();
        if (g.phase() != Phase.ACTION || session.contest().isPresent() || session.rower().isPresent()
                || !g.canAct(from) || g.isRemoved(from)) {
            return List.of();
        }
        SurvivorState s = g.stateOf(from);
        List<Gift> out = new ArrayList<>();
        for (CharacterId to : g.onBoatBySeat()) {
            if (to.equals(from)) {
                continue;
            }
            for (String card : new LinkedHashSet<>(s.hand())) {
                out.add(new Gift(card, false, to));
            }
            for (String card : new LinkedHashSet<>(s.front())) {
                out.add(new Gift(card, true, to));
            }
        }
        return List.copyOf(out);
    }

    /** 押得下的武器：手里与面前的每一张武器（两支船桨是两项），手里在前。只有参战的清醒者有。 */
    public static List<String> weapons(Session session, CharacterId who) {
        Optional<Contest> c = session.contest();
        if (c.isEmpty() || c.get().stage() != Contest.Stage.WEAPONS || !session.state().canAct(who)
                || !c.get().fight().orElseThrow().combatants().contains(who)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String card : available(session.state().stateOf(who))) {
            if (session.provisions().get(card).weaponPower() > 0) {
                out.add(card);
            }
        }
        return List.copyOf(out);
    }

    /** 抢到手之后挑得出的：他面前的每一张（小孩的偷窃没有这一种），外加「从手里摸一张」（他手里有牌时）。 */
    public static List<PickChoice> picks(Session session) {
        Contest c = session.contest().filter(x -> x.stage() == Contest.Stage.PICK).orElseThrow(
                () -> new IllegalStateException("现在不是挑牌的时候"));
        SurvivorState victim = session.state().stateOf(c.target());
        List<PickChoice> out = new ArrayList<>();
        if (!c.handOnly()) {
            for (String card : victim.front()) {
                out.add(new PickChoice.FromFront(card));
            }
        }
        if (!victim.hand().isEmpty()) {
            out.add(PickChoice.FROM_HAND);
        }
        return List.copyOf(out);
    }

    /** 落海那一刻 {@code who} 打得出的（救生圈给谁 · 血饵），照 {@link Session#overboardPlays} 并按座位排好。 */
    public static List<Session.OverboardPlay> overboard(Session session, CharacterId who) {
        // 身上没有救生圈也没有血饵就不必问：overboardPlays 对每一张打不出的牌都靠抛一个异常来判，
        // 一局里每一批落海都对每个座位问一遍的话，那几千个异常就是模拟器一半的时间。
        boolean holdsAny = false;
        for (String card : available(session.state().stateOf(who))) {
            ProvisionEffect e = session.provisions().get(card).effect();
            if (e instanceof ProvisionEffect.DamageInWater || e instanceof ProvisionEffect.PreventOverboardDamage) {
                holdsAny = true;
                break;
            }
        }
        if (!holdsAny) {
            return List.of();
        }
        List<Session.OverboardPlay> plays = new ArrayList<>(session.overboardPlays(who));
        // overboardPlays 按落海名单遍历，那份名单的次序每次起 JVM 都不同 —— 这里按目标座位排，免得「挑第一项」跟着变。
        List<CharacterId> order = session.state().bySeat();
        plays.sort((a, b) -> {
            int byCard = a.card().compareTo(b.card());
            return byCard != 0 ? byCard : Integer.compare(order.indexOf(a.target()), order.indexOf(b.target()));
        });
        return List.copyOf(plays);
    }

    /** 自己能拿出几次水（每次 {@code perSource} 张）：手里加面前；昏迷、掉线的人自己喝不了。 */
    public static int ownWaterUnits(Session session, CharacterId who, int perSource) {
        if (!session.state().canAct(who)) {
            return 0;
        }
        return session.watersOf(who) / perSource;
    }

    /** 今天风平浪静：不能划船（规则第七章）。 */
    public static boolean becalmed(Session session) {
        return session.currentWeather().map(w -> w.effect() == WeatherEffect.SKIP_NAVIGATION).orElse(false);
    }

    /** 他正在挨抢、到了挑牌那一刻 —— 这一刻不能亮牌（规则第五章）。 */
    private static boolean pickingFrom(Session session, CharacterId who) {
        return session.contest().map(c -> c.stage() == Contest.Stage.PICK && c.target().equals(who)).orElse(false);
    }

    /** 手里的在前、面前的在后，重复的照样列（与 {@code Simulator#available} 同一个次序）。 */
    static List<String> available(SurvivorState s) {
        List<String> all = new ArrayList<>(s.hand());
        all.addAll(s.front());
        return all;
    }
}
