package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 替身随机行动（{@code /seas dummy random on}；用户 2026-10-07 定「随机行动」）。
 *
 * <h2>为什么要它</h2>
 * 替身自动推进原先只会「什么也不做」（ADR-0019）：不划船、不抢、不换座、不打牌、不站队、不押武器、表态一律同意。
 * 演示局里只有一个真人，于是抢夺、打架、被换座、落海这些<b>一次都轮不到他身上</b>，整局测不到几样东西
 * （用户 2026-10-07：「在 demo 局里交互太少了，无法测完整游戏内容」）。
 *
 * <h2>这不是 AI</h2>
 * 决策 ② 不变：本作不给玩家做对手。这里照搬引擎模拟器（{@code engine.sim.Simulator}）跑过几万局的那套随机分布，
 * 只是一个<b>测试开关</b>，默认关、不持久化 —— 回归脚本都按「替身什么也不做」写的，开关默认开就全红了。
 *
 * <h2>每一步都走真流程</h2>
 * 与模拟器不同，这里<b>不直接调引擎</b>：划船、宣告、站队、押武器、打特殊牌都调 {@link ActionPhase} /
 * {@link ContestPhase} 里真人那条路，播报、窗口、超时、HUD 同步一样不少 —— 替身走过的路就是真人会走的路。
 *
 * <p>随机源用这一局自己的（{@link GameComponent#gameRandom}）：指定了种子的一局，替身怎么动也照样可复现。
 */
public final class StandInPlay {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /**
     * 轮到替身后停多久再动：一个替身接一个替身当场做完的话，真人一眼都跟不上（用户 2026-10-07「demo 玩家不要出牌太快」）。
     * 快档（{@code fast}）给调试用。
     */
    static long beat(GameComponent component) {
        return component.dummyFast() ? 300L : 4_000L;
    }

    /** 这一场里只有替身的那一步（表态 · 挑牌 · 站队收尾）停多久：慢档让人看得见一步一步，快档当场走。 */
    static long step(GameComponent component) {
        return component.dummyFast() ? 0L : 1_500L;
    }

    /** 抢夺 / 换座位挑目标时，有真人可挑就有这么大的机会挑真人 —— 演示局要测的正是「真人被卷进来」。 */
    private static final double PREFER_HUMAN = 0.5;

    private StandInPlay() {
    }

    /**
     * 轮到替身行动，随机做一件事。分布照模拟器（{@code Simulator#action}）：先随机亮一张、随机喝口酒（都不占行动），
     * 再五选一 —— 什么也不做 · 划船 · 换座位 · 打特殊牌（打不出就抢夺）· 抢夺。
     *
     * <p>任何一步被规则拒了（风平浪静不能划船、没有对象……）都退回「什么也不做」：替身卡住比替身做错更糟，
     * 卡住的局面不会再有下一步来推它。
     */
    static void takeTurn(ServerWorld world, GameComponent component, CharacterId dummy) {
        Session session = component.requireSession();
        Random rng = component.gameRandom();
        maybeReveal(world, component, session, dummy, rng);
        maybeDrink(session, dummy, rng);
        int roll = rng.nextInt(5);
        try {
            boolean done = switch (roll) {
                case 1 -> row(world, component, session, dummy, rng);
                case 2 -> contest(world, component, session, dummy, Contest.Kind.SWAP, rng);
                case 3 -> special(world, component, session, dummy, rng)
                        || contest(world, component, session, dummy, Contest.Kind.STEAL, rng);
                case 4 -> contest(world, component, session, dummy, Contest.Kind.STEAL, rng);
                default -> false;
            };
            if (done) {
                return;
            }
        } catch (RuntimeException rejected) {
            LOGGER.info("替身随机：{} 那一步被规则拒了（{}），改为什么也不做", dummy.value(), rejected.getMessage());
            if (session.contest().isPresent() || session.rower().isPresent()) {
                throw rejected;                   // 已经走到一半：不能假装什么也没发生，交给排程那一层收场并点名
            }
        }
        ActionPhase.passForStandIn(world, component, dummy);
    }

    /** 随机亮出一张手牌（三分之一）。不占行动、不可逆。 */
    private static void maybeReveal(ServerWorld world, GameComponent component, Session session, CharacterId dummy,
                                    Random rng) {
        List<String> hand = session.state().stateOf(dummy).hand();
        if (hand.isEmpty() || rng.nextInt(3) != 0) {
            return;
        }
        String card = hand.get(rng.nextInt(hand.size()));
        session.reveal(dummy, card);
        LOGGER.info("替身随机：{} 亮出 {}", dummy.value(), card);
        GameComponents.sync(world);
    }

    /** 随机喝一口酒（四分之一）：本回合体型加值，代价是回合结束时口渴。 */
    private static void maybeDrink(Session session, CharacterId dummy, Random rng) {
        if (rng.nextInt(4) != 0) {
            return;
        }
        var state = session.state().stateOf(dummy);
        for (String card : available(session, dummy)) {
            if (session.provisions().get(card).effect() instanceof ProvisionEffect.BuffSize && !state.usedThisTurn(card)) {
                session.drinkRum(dummy, card);
                LOGGER.info("替身随机：{} 喝了 {}", dummy.value(), card);
                return;
            }
        }
    }

    private static boolean row(ServerWorld world, GameComponent component, Session session, CharacterId dummy,
                               Random rng) {
        return ActionPhase.rowForStandIn(world, component, dummy, rng);
    }

    /** 手上（或面前）有花行动打出的牌就随机打一张；前提都不满足就返回 {@code false}。 */
    private static boolean special(ServerWorld world, GameComponent component, Session session, CharacterId dummy,
                                   Random rng) {
        List<String> playable = new ArrayList<>();
        for (String card : available(session, dummy)) {
            Provision p = session.provisions().get(card);
            if (p.isSpecialAction() && !playable.contains(card)) {
                playable.add(card);
            }
        }
        while (!playable.isEmpty()) {
            String card = playable.remove(rng.nextInt(playable.size()));
            if (ActionPhase.playForStandIn(world, component, dummy, card)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 换座位或抢夺：挑一个对象当场宣告。
     *
     * <p>替身没有「站起来举着拳头」那一段（决策 ⑦ 的预告窗口是给真人临场谈判的，替身不会谈判）——
     * 停顿已经在轮到它之前给过了（{@link #beat}），宣告那一句播报就是预告。
     */
    private static boolean contest(ServerWorld world, GameComponent component, Session session, CharacterId dummy,
                                   Contest.Kind kind, Random rng) {
        List<CharacterId> targets = new ArrayList<>(session.state().onBoatBySeat());
        targets.remove(dummy);
        if (targets.isEmpty()) {
            return false;
        }
        List<CharacterId> humans = targets.stream()
                .filter(id -> component.occupantOf(id).map(o -> !o.isDummy()).orElse(false)).toList();
        List<CharacterId> pool = !humans.isEmpty() && rng.nextDouble() < PREFER_HUMAN ? humans : targets;
        CharacterId target = pool.get(rng.nextInt(pool.size()));
        LOGGER.info("替身随机：{} 要{} {}", dummy.value(), kind == Contest.Kind.STEAL ? "抢夺" : "换座位", target.value());
        ContestPhase.declare(world, component, dummy, kind, target);
        return true;
    }

    // ---- 这一场里替身的那几下（ContestPhase 在随机开关开着时调） ----

    /** 被指定的替身表态：一半一半（模拟器同一个分布）。关着时替身一律同意（ADR-0023 §7.8）。 */
    static boolean consent(Random rng) {
        return rng.nextBoolean();
    }

    /**
     * 站队：每个还能加入的替身三分之一加入、哪边各一半。
     *
     * @param delayed 真人在场时排到之后几秒、一个一个加入（看得见有人站出来）；没有真人时当场加入
     */
    static void joinStances(ServerWorld world, GameComponent component, boolean delayed) {
        Session session = component.requireSession();
        Random rng = component.gameRandom();
        Contest contest = session.contest().orElseThrow();
        for (CharacterId who : session.state().consciousBySeat()) {
            if (!isDummy(component, who) || !ContestPhase.canJoin(session, contest, who) || rng.nextInt(3) != 0) {
                continue;
            }
            Fight.Side side = rng.nextBoolean() ? Fight.Side.ATTACK : Fight.Side.DEFEND;
            if (!delayed) {
                ContestPhase.join(world, component, who, side);
                continue;
            }
            long at = component.dummyFast() ? rng.nextInt(500) : 1_500L + rng.nextInt(5_000);
            GameFlow.schedule(component, at, "替身随机：" + who.value() + " 站队", () -> {
                Session now = component.requireSession();
                if (now.contest().map(c -> c.stage() == Contest.Stage.STANCES
                        && ContestPhase.canJoin(now, c, who)).orElse(false)) {
                    ContestPhase.join(world, component, who, side);
                }
            });
        }
    }

    /** 押武器：每个参战的替身，手上与面前的每张武器三分之一押下（暗牌：播报不说是哪张）。 */
    static void commitWeapons(ServerWorld world, GameComponent component, boolean delayed) {
        Session session = component.requireSession();
        Random rng = component.gameRandom();
        Contest contest = session.contest().orElseThrow();
        for (CharacterId who : contest.fight().orElseThrow().combatants()) {
            if (!isDummy(component, who)) {
                continue;
            }
            for (String card : available(session, who)) {
                if (session.provisions().get(card).weaponPower() <= 0 || rng.nextInt(3) != 0) {
                    continue;
                }
                if (!delayed) {
                    if (ContestPhase.canCommitWeapon(session, contest, who, card)) {
                        ContestPhase.commitWeapon(world, component, who, card);
                    }
                    continue;
                }
                long at = component.dummyFast() ? rng.nextInt(500) : 1_000L + rng.nextInt(4_000);
                GameFlow.schedule(component, at, "替身随机：" + who.value() + " 押武器", () -> {
                    Session now = component.requireSession();
                    if (now.contest().map(c -> c.stage() == Contest.Stage.WEAPONS
                            && ContestPhase.canCommitWeapon(now, c, who, card)).orElse(false)) {
                        ContestPhase.commitWeapon(world, component, who, card);
                    }
                });
            }
        }
    }

    /** 替身抢赢了挑牌：面前有亮出的、而且（手上没有或掷到一半）就从面前挑一张，否则手牌随机一张。 */
    static String pickFront(Session session, Contest contest, Random rng) {
        GameState g = session.state();
        var victim = g.stateOf(contest.target());
        if (!contest.handOnly() && !victim.front().isEmpty() && (victim.hand().isEmpty() || rng.nextBoolean())) {
            return victim.front().get(rng.nextInt(victim.front().size()));
        }
        return null;
    }

    private static boolean isDummy(GameComponent component, CharacterId who) {
        return component.occupantOf(who).map(GameComponent.Occupant::isDummy).orElse(false);
    }

    /** 他现在能用的牌：手上的 + 面前的（酒与伞在面前照样能用）。 */
    private static List<String> available(Session session, CharacterId who) {
        var state = session.state().stateOf(who);
        List<String> all = new ArrayList<>(state.hand());
        all.addAll(state.front());
        return all;
    }
}
