package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.SurvivorState;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.ContestActionC2S;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * 换座位与抢夺的服务端一侧（ADR-0023）：宣告 → 表态 → 站队 → 挂武器 → 结算 → 生效 / 挑牌。
 *
 * <h2>规则不在这里</h2>
 * 「谁能拒绝」「谁能加入」「押的武器是不是真在手上」「进行中不得易手」全在 {@link Session} 里，
 * 模拟器与模组共用同一份。本类只管三件事：<b>计时 · 问人 · 播报</b>。
 *
 * <h2>两段软倒计时（决策 ④）</h2>
 * 站队 {@link #STANCE_MILLIS}，有人加入重置为 {@link #STANCE_BUMP_MILLIS}；
 * 挂武器 {@link #WEAPON_MILLIS}，有人押下重置为 {@link #WEAPON_BUMP_MILLIS}。
 * 表态与挑牌各一个固定窗口。这几个常量是默认值；这一局实际用的在 {@link GameTiming}（ADR-0099 D8，开局快照）。
 *
 * <p>❗<b>表态超时按「同意」</b>：默认「战斗」会给挂机的人凭空制造伤害 —— 那不是他的默认答案，
 * 那是替他做了一个没人会做的选择（与口渴那一面同一条理由）。
 *
 * <h2>没人要等的时候不空等</h2>
 * 每一段开窗口之前先问「这一段有真人要动吗」。全是替身（或者开着自动推进）时直接排下一 tick 推进 ——
 * 出口验收里没人要看，空等 15 秒只是让每一局多花一分钟。❗<b>排下一 tick 而不是当场递归</b>：
 * 当场递归的话整场会落在同一个 tick 里，客户端一帧都看不到（与 ADR-0019 那条同一个坑）。
 */
public final class ContestPhase {

    /** 被指定的人表态：同意还是战斗。超时 = 同意。12 → 20 秒（用户 2026-10-07：决策窗口一律至少 20 秒，「有的时候决策时间太短」）。 */
    public static final long CONSENT_MILLIS = 20_000L;

    /** 站队段。15 → 20 秒（用户 2026-10-07：决策窗口一律至少 20 秒，「有的时候决策时间太短」）。 */
    public static final long STANCE_MILLIS = 20_000L;

    /** 有人加入之后剩下的时间。 */
    public static final long STANCE_BUMP_MILLIS = 8_000L;

    /** 挂武器段。10 → 20 秒（用户 2026-10-07：决策窗口一律至少 20 秒，「有的时候决策时间太短」）。 */
    public static final long WEAPON_MILLIS = 20_000L;

    /** 有人押下之后剩下的时间。 */
    public static final long WEAPON_BUMP_MILLIS = 6_000L;

    /** 抢夺方挑牌。超时 = 手牌随机一张（手上没有就取面前第一张）。12 → 20 秒（用户 2026-10-07：决策窗口一律至少 20 秒，「有的时候决策时间太短」）。 */
    public static final long PICK_MILLIS = 20_000L;

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private ContestPhase() {
    }

    /**
     * 宣告一场换座位或抢夺。规则上这一下就用掉了进攻方的行动，无论后面怎么收场。
     *
     * <p>目标不清醒、或者是小孩的偷窃时，引擎当场就把它办完了（或者直接进挑牌）—— 这里只负责接着往下走。
     */
    public static void declare(ServerWorld world, GameComponent component, CharacterId actor,
                               Contest.Kind kind, CharacterId target) {
        component.clearActionWindow();
        Session session = component.requireSession();
        session.declare(actor, kind, target);
        boolean steal = kind == Contest.Kind.STEAL;
        // 与语言无关的一行：验收脚本从这一行起数一场。
        LOGGER.info("宣告：{} 对 {} {}", actor.value(), target.value(), steal ? "抢夺" : "换座位");
        GameFlow.broadcast(world, Text.translatable(steal ? "heavyseas.contest.declared_steal"
                        : "heavyseas.contest.declared_swap",
                GameFlow.characterName(actor), GameFlow.characterName(target)).formatted(Formatting.AQUA));
        after(world, component, actor);
    }

    /** 打出绝境：无论有没有人反对都先弃牌；有反对资格的人按座位逐个表态。 */
    public static void beginRation(ServerWorld world, GameComponent component, CharacterId actor, String cardId) {
        component.clearActionWindow();
        Session session = component.requireSession();
        Optional<List<CharacterId>> immediate = session.beginRation(actor, cardId);
        LOGGER.info("绝境：{} 打出 {}{}", actor.value(), cardId, immediate.isPresent() ? "，无人能反对" : "，等待反对");
        if (immediate.isPresent()) {
            announceRationed(world, actor, immediate.get().size());
            GameFlow.finishAction(world, component, actor);
            return;
        }
        CharacterId asked = session.contest().orElseThrow().target();
        GameFlow.broadcast(world, Text.translatable("heavyseas.contest.declared_ration",
                GameFlow.characterName(actor), GameFlow.characterName(asked)).formatted(Formatting.AQUA));
        after(world, component, actor);
    }

    /**
     * 表态那一句播报用的 lang 键。绝境不是「同意被换座位 / 被抢」，是「不反对你吃」—— 文案借了另一件事的前提
     * 会在日志里长得一模一样，只有人看得出来（ADR-0032 #6）。抽成函数是为了能单测，不必起世界。
     */
    static String responseKey(Contest.Kind kind, boolean fight) {
        if (kind == Contest.Kind.RATION) {
            return fight ? "heavyseas.contest.ration_objected" : "heavyseas.contest.ration_passed";
        }
        return fight ? "heavyseas.contest.refused" : "heavyseas.contest.agreed";
    }

    /** 被指定的人表态。{@code fight = false} 是同意（超时也走这一条）。 */
    public static void consent(ServerWorld world, GameComponent component, boolean fight) {
        Session session = component.requireSession();
        Contest contest = session.contest().orElseThrow();
        CharacterId attacker = contest.attacker();
        CharacterId target = contest.target();
        session.consent(fight);
        LOGGER.info("表态：{} {}", target.value(), fight ? "战斗" : "同意");
        GameFlow.broadcast(world, Text.translatable(responseKey(contest.kind(), fight),
                GameFlow.characterName(target)).formatted(fight ? Formatting.RED : Formatting.GRAY));
        if (!fight && contest.kind() == Contest.Kind.RATION && session.contest().isEmpty()) {
            announceRationed(world, attacker, session.lastRationHealed());
        }
        after(world, component, attacker);
    }

    /** 站队：加入一边。加入之后不可退出，所以倒计时只重置、不延长到超过一次。 */
    public static void join(ServerWorld world, GameComponent component, CharacterId who, Fight.Side side) {
        Session session = component.requireSession();
        session.join(who, side);
        LOGGER.info("站队：{} 加入{}方", who.value(), side == Fight.Side.ATTACK ? "进攻" : "防守");
        GameFlow.broadcast(world, Text.translatable("heavyseas.contest.joined", GameFlow.characterName(who),
                Text.translatable(side == Fight.Side.ATTACK
                        ? "heavyseas.contest.side_attack" : "heavyseas.contest.side_defend")));
        component.markDecided(who);
        if (!endIfAllDecided(world, component)) {
            bump(component, component.humanWindow(component.timing().stanceBumpMs()), System.currentTimeMillis());
        }
        GameComponents.sync(world);
    }

    /**
     * 有人加入 / 押下之后的软倒计时：<b>只延不缩</b> —— 剩下的比追加时长短才补到追加时长，长的照旧。
     *
     * <p>❗审查 2026-10-07 R11：原先一律重设成追加时长（站队 8 秒 · 押武器 6 秒）。替身会在窗口里陆续站出来之后，
     * 替身 1.5 秒一加入，真人那 20 秒就被砍到 8 秒，与「决策窗口一律至少 20 秒」（用户 2026-10-07）相悖。
     * 没开窗的那一段（只有替身，{@code contestDeadline = 0}）照旧开一扇追加时长的窗，与原先一样。
     */
    static void bump(GameComponent component, long bumpMs, long now) {
        if (component.contestDeadline() - now < bumpMs) {
            component.openContestWindow(bumpMs);
        }
    }

    /**
     * 押下一张武器。❗<b>暗牌</b>：只播「押下了一张」，不播是哪一张 ——
     * 播出来的话这一段就白做了（决策 ④ 把「打出即亮出」改成了暗牌）。
     */
    public static void commitWeapon(ServerWorld world, GameComponent component, CharacterId who, String cardId) {
        Session session = component.requireSession();
        session.commitWeapon(who, cardId);
        LOGGER.info("挂武器：{} 押下一张", who.value());          // ❗日志也不写是哪一张：开服的人往往也是玩家
        GameFlow.broadcast(world, Text.translatable("heavyseas.contest.weapon_committed",
                GameFlow.characterName(who)).formatted(Formatting.GRAY));
        if (!endIfAllDecided(world, component)) {
            bump(component, component.humanWindow(component.timing().weaponBumpMs()), System.currentTimeMillis());
        }
        GameComponents.sync(world);
    }

    /**
     * 这一段该答的真人都答完了吗（ADR-0095 D1）。站队：每个还能加入的清醒真人都加入了或选了旁观；
     * 挂武器：每个还押得出武器的真人参战者都说了「押完了」。没有人要答时也算答完。
     */
    static boolean allHumansDecided(GameComponent component, Contest contest) {
        Session session = component.requireSession();
        for (var entry : component.occupants().entrySet()) {
            CharacterId who = entry.getKey();
            if (entry.getValue().isDummy() || session.state().isOffline(who) || component.hasDecided(who)) {
                continue;
            }
            boolean owes = switch (contest.stage()) {
                case STANCES -> canJoin(session, contest, who);
                case WEAPONS -> session.state().canAct(who)
                        && contest.fight().map(f -> f.combatants().contains(who)).orElse(false)
                        && hasCommittableWeapon(session, contest, who);
                default -> false;
            };
            if (owes) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasCommittableWeapon(Session session, Contest contest, CharacterId who) {
        var state = session.state().stateOf(who);
        List<String> all = new java.util.ArrayList<>(state.hand());
        all.addAll(state.front());
        return all.stream().anyMatch(card -> canCommitWeapon(session, contest, who, card));
    }

    /**
     * 该答的都答了就把这一段收短（ADR-0095 D1）：留一小截尾巴让排着的替身动作露个面（真人局 2 秒 · 替身随机慢档 7 秒、
     * 盖得住替身 1.5–6.5 秒的站队延迟 · 快档 0.6 秒），
     * 不再让全船空等满 20 秒；演示局不限时的窗口也靠它收场。
     *
     * @return 收短了没有
     */
    static boolean endIfAllDecided(ServerWorld world, GameComponent component) {
        Optional<Contest> contest = component.requireSession().contest();
        if (contest.isEmpty() || component.contestDeadline() <= 0
                || (contest.get().stage() != Contest.Stage.STANCES && contest.get().stage() != Contest.Stage.WEAPONS)
                || !allHumansDecided(component, contest.get())) {
            return false;
        }
        long tail = !component.standInsAct() ? 2_000L : component.dummyFast() ? 600L : 7_000L;
        tail = StandInMinds.windowTailMs(component, tail);   // 大模型替身在窗口里想：留够它想完的一截
        if (component.contestDeadline() - System.currentTimeMillis() > tail) {
            component.openContestWindow(tail);
            LOGGER.info("这一场：{} 该答的真人都答了，{} 秒后收", contest.get().stage(), tail / 1000.0);
        }
        return true;
    }

    /** 站队段结束，进挂武器段。 */
    public static void closeStances(ServerWorld world, GameComponent component) {
        Session session = component.requireSession();
        CharacterId attacker = session.contest().orElseThrow().attacker();
        session.closeStances();
        after(world, component, attacker);
    }

    /** 挂武器段结束：结算这一场。押下的牌在这一刻才亮出、加上战力。 */
    public static void resolve(ServerWorld world, GameComponent component) {
        Session session = component.requireSession();
        Contest contest = session.contest().orElseThrow();
        CharacterId attacker = contest.attacker();
        CharacterId target = contest.target();
        Fight.Outcome outcome = session.resolveContest();
        int rationHealed = contest.kind() == Contest.Kind.RATION ? session.lastRationHealed() : 0;
        boolean won = outcome.attackerGetsWhatTheyWanted();
        LOGGER.info("战斗结算：{} 对 {} —— {}方胜，败方每人 {} 点", attacker.value(), target.value(),
                won ? "进攻" : "防守", outcome.damagePerLoser());
        GameFlow.broadcast(world, Text.translatable(won ? "heavyseas.contest.attacker_won"
                        : "heavyseas.contest.defender_won",
                GameFlow.characterName(won ? attacker : target)).formatted(Formatting.RED));
        if (outcome.damagePerLoser() > 0) {
            GameFlow.broadcast(world, Text.translatable("heavyseas.contest.damage", outcome.damagePerLoser())
                    .formatted(Formatting.RED));
        }
        if (contest.kind() == Contest.Kind.RATION) {
            if (won) {
                announceRationed(world, attacker, rationHealed);
            } else {
                GameFlow.broadcast(world, Text.translatable("heavyseas.contest.ration_blocked",
                        GameFlow.characterName(target)).formatted(Formatting.GRAY));
            }
        } else if (won && !session.contest().isPresent()) {
            // 换座位当场生效；抢夺时被抢方身上一张都没有，挑牌那一步跳过。
            announceTaken(world, contest, session);
        }
        after(world, component, attacker);
    }

    /** 挑牌：拿被抢方面前亮出的一张。 */
    public static void pickFromFront(ServerWorld world, GameComponent component, String cardId) {
        Session session = component.requireSession();
        Contest contest = session.contest().orElseThrow();
        session.pickFromFront(cardId);
        LOGGER.info("抢夺：{} 从 {} 面前抢走 {}", contest.attacker().value(), contest.target().value(), cardId);
        GameFlow.broadcast(world, Text.translatable("heavyseas.contest.stolen_front",
                GameFlow.characterName(contest.attacker()), GameFlow.characterName(contest.target()),
                Text.translatable("heavyseas.provision." + cardId)).formatted(Formatting.GOLD));
        after(world, component, contest.attacker());
    }

    /**
     * 挑牌：从被抢方手牌里随机抽一张。
     *
     * <p>❗<b>下标由这里给</b>，而且必须是均匀随机的：引擎不持有随机源，它只校验范围。
     * 界面上也不能让抢夺方看着牌挑 —— 手牌是暗的。
     */
    public static void pickFromHand(ServerWorld world, GameComponent component) {
        Session session = component.requireSession();
        Contest contest = session.contest().orElseThrow();
        int size = session.state().stateOf(contest.target()).hand().size();
        // 这一局自己的随机源（开局时由种子派生，ADR-0060）：指定了种子的一局，抢到哪一张也照样可复现
        session.pickFromHand(component.gameRandom().nextInt(size));
        LOGGER.info("抢夺：{} 从 {} 手上随机抢走一张", contest.attacker().value(), contest.target().value());
        GameFlow.broadcast(world, Text.translatable("heavyseas.contest.stolen_hand",
                GameFlow.characterName(contest.attacker()),
                GameFlow.characterName(contest.target())).formatted(Formatting.GOLD));
        after(world, component, contest.attacker());
    }

    /**
     * 每 tick 检查超时。**服务端超时，客户端不参与判定。**
     *
     * <p>四段各有各的默认答案：表态默认同意 · 站队到点就结束 · 挂武器到点就结算 · 挑牌默认手牌随机一张。
     */
    public static void tick(ServerWorld world, GameComponent component, long now) {
        long deadline = component.contestDeadline();
        if (component.session().isEmpty() || deadline <= 0 || now < deadline) {
            return;
        }
        Optional<Contest> contest = component.requireSession().contest();
        if (contest.isEmpty()) {
            component.clearContest();          // 这一场已经收场了，窗口是残影
            return;
        }
        component.clearContest();
        switch (contest.get().stage()) {
            case CONSENT -> {
                LOGGER.info("表态超时：{} 按同意算", contest.get().target().value());
                consent(world, component, false);
            }
            case STANCES -> {
                LOGGER.info("站队段结束（超时）");
                closeStances(world, component);
            }
            case WEAPONS -> {
                LOGGER.info("挂武器段结束（超时）");
                resolve(world, component);
            }
            case PICK -> {
                LOGGER.info("挑牌超时：按手牌随机一张算");
                autoPick(world, component, contest.get());
            }
        }
    }

    /**
     * 四面 GUI 上按下的那一下（ADR-0023）。
     *
     * <h2>只认该按这一下的那个人本人</h2>
     * 与另外几面同一条：改过的客户端发得出任何字节，所以「谁能表态」「谁能加入」「押的牌是不是真在手上」
     * 在这里全部重问一遍，规则那一层还有引擎的守卫。
     *
     * <p>❗不合时宜的一律<b>当作没按</b>，不抛异常：抛出来是一句堆栈，而「拒绝了你」与「有 bug」
     * 在日志上就分不开了 —— 出口验收那条 {@code must_not "/seas 出错"} 记的正是这件事。
     *
     * <p>❗还要问 {@link GameComponent#contestDeadline()}：没开窗口的那几段由排程按默认答案推，
     * 这时候到的包是一个已经没意义的决定（或者是想抢在排程前面插队的客户端）。
     */
    public static void onAction(ServerPlayerEntity player, ContestActionC2S action) {
        Optional<ContestActionC2S.Kind> kind = action.kind();
        ServerWorld world = player.getServerWorld();
        GameComponent component = GameComponents.of(world);
        if (kind.isEmpty() || component.session().isEmpty() || component.contestDeadline() <= 0) {
            return;
        }
        Session session = component.requireSession();
        Optional<Contest> pending = session.contest();
        Optional<CharacterId> seat = component.seatOf(player.getUuid());
        if (pending.isEmpty() || seat.isEmpty() || !session.state().canAct(seat.get())) {
            return;
        }
        Contest contest = pending.get();
        CharacterId who = seat.get();
        switch (kind.get()) {
            case CONSENT_AGREE, CONSENT_FIGHT -> {
                if (contest.stage() != Contest.Stage.CONSENT || !contest.target().equals(who)) {
                    return;                   // 只有被指定的那个人能表态
                }
                boolean fight = kind.get() == ContestActionC2S.Kind.CONSENT_FIGHT;
                LOGGER.info("表态（界面）：{} {}", who.value(), fight ? "战斗" : "同意");
                consent(world, component, fight);
            }
            case JOIN_ATTACK, JOIN_DEFEND -> {
                if (contest.stage() != Contest.Stage.STANCES || !canJoin(session, contest, who)) {
                    return;
                }
                Fight.Side side = kind.get() == ContestActionC2S.Kind.JOIN_ATTACK
                        ? Fight.Side.ATTACK : Fight.Side.DEFEND;
                LOGGER.info("站队（界面）：{} 加入{}方", who.value(), side == Fight.Side.ATTACK ? "进攻" : "防守");
                join(world, component, who, side);
            }
            case COMMIT_WEAPON -> {
                if (contest.stage() != Contest.Stage.WEAPONS
                        || !canCommitWeapon(session, contest, who, action.card())) {
                    return;
                }
                // ❗这一行也不写是哪一张：暗牌（决策 ④），而开服的人往往也是玩家。
                LOGGER.info("挂武器（界面）：{} 押下一张", who.value());
                commitWeapon(world, component, who, action.card());
            }
            case PICK_FRONT -> {
                if (contest.stage() != Contest.Stage.PICK || !contest.attacker().equals(who)
                        || contest.handOnly()
                        || !session.state().stateOf(contest.target()).hasInFront(action.card())) {
                    return;
                }
                LOGGER.info("挑牌（界面）：{} 挑了面前的一张", who.value());
                pickFromFront(world, component, action.card());
            }
            case STAND_ASIDE -> {
                // 站队一面选「旁观」（ADR-0095 D1）：原先不发包，窗口只能空等到时
                // ❗已经表过态的再发一次就当作没按（审查 2026-10-07 L5）：原先每一包都记一行、给全船推一次投影，没有上限
                if (contest.stage() != Contest.Stage.STANCES || !canJoin(session, contest, who)
                        || component.hasDecided(who)) {
                    return;
                }
                LOGGER.info("站队（界面）：{} 旁观", who.value());
                component.markDecided(who);
                endIfAllDecided(world, component);
                GameComponents.sync(world);
            }
            case WEAPONS_DONE -> {
                if (contest.stage() != Contest.Stage.WEAPONS
                        || !contest.fight().map(f -> f.combatants().contains(who)).orElse(false)
                        || component.hasDecided(who)) {
                    return;                   // 已经说过「押完了」：同上（审查 L5）
                }
                LOGGER.info("挂武器（界面）：{} 押完了", who.value());
                component.markDecided(who);
                endIfAllDecided(world, component);
                GameComponents.sync(world);
            }
            case PICK_HAND -> {
                // ❗手上一张都没有时 pickFromHand 会对 0 求随机数 —— 那是个异常，不是一次被拒绝的操作。
                if (contest.stage() != Contest.Stage.PICK || !contest.attacker().equals(who)
                        || session.state().stateOf(contest.target()).hand().isEmpty()) {
                    return;
                }
                LOGGER.info("挑牌（界面）：{} 从手牌里随机抽一张", who.value());
                pickFromHand(world, component);
            }
        }
    }

    /** 他能不能加入这一场：清醒、还在游戏里、而且还没在场上（规则 §9.3；加入之后不可退出）。 */
    public static boolean canJoin(Session session, Contest contest, CharacterId who) {
        if (!session.state().canAct(who)) {
            return false;
        }
        return !contest.fight().map(f -> f.combatants().contains(who)).orElse(true);
    }

    /**
     * 他还押得出这张吗：手上或面前真有、是武器、而且这个 id 还没押满（两支船桨能押两次）。
     *
     * <p>❗<b>先问「有没有」，再问「是不是武器」</b>：这条路上的 id 来自客户端，
     * 而拿一个乱发来的 id 去问牌库会抛。
     *
     * <p>指令那条路（{@code SeasCommand#weapon}）走的也是这一份。两套判据一旦分家，
     * 表现是界面上摆着的牌押不出去 —— 屏幕上只是「按了没反应」，没有任何人会报错。
     */
    public static boolean canCommitWeapon(Session session, Contest contest, CharacterId who, String cardId) {
        if (!session.state().canAct(who)
                || !contest.fight().map(f -> f.combatants().contains(who)).orElse(false)) {
            return false;
        }
        SurvivorState state = session.state().stateOf(who);
        long owned = state.countInHand(cardId) + state.front().stream().filter(cardId::equals).count();
        if (owned <= 0) {
            return false;
        }
        if (session.provisions().get(cardId).weaponPower() <= 0) {
            return false;
        }
        return contest.committedBy(who).stream().filter(cardId::equals).count() < owned;
    }

    /** 这一场走到哪了就开哪一段的窗口；已经收场就把进攻方的行动记掉（规则 §9.1：无论胜负）。 */
    private static void after(ServerWorld world, GameComponent component, CharacterId attacker) {
        Session session = component.requireSession();
        Optional<Contest> contest = session.contest();
        if (contest.isEmpty()) {
            component.clearContest();
            GameFlow.finishAction(world, component, attacker);
            return;
        }
        GameTiming timing = component.timing();   // 开局快照（ADR-0099 D8）
        switch (contest.get().stage()) {
            case CONSENT -> open(world, component, timing.consentMs(), "heavyseas.contest.consent_wait",
                    GameFlow.characterName(contest.get().target()),
                    waiting(component, contest.get().target()));
            case STANCES -> open(world, component, timing.stanceMs(), "heavyseas.contest.stances",
                    null, driven(component));
            case WEAPONS -> open(world, component, timing.weaponMs(), "heavyseas.contest.weapons",
                    null, anyHumanCombatant(component, contest.get()) || !component.dummyAutoplay());
            case PICK -> open(world, component, timing.contestPickMs(), "heavyseas.contest.pick_wait",
                    GameFlow.characterName(contest.get().attacker()),
                    waiting(component, contest.get().attacker()));
        }
    }

    /**
     * 开一段窗口；没有真人要动时不开，直接排下一 tick 按默认答案推进。
     *
     * @param human 这一段有没有真人要动
     */
    private static void open(ServerWorld world, GameComponent component, long millis, String key,
                             Text who, boolean human) {
        if (!human) {
            component.clearContest();
            if (StandInMinds.thinks(component)) {
                // 动脑 / 大模型：替身这就开始想，答完由它往下推；做出来不早于随机替身那一拍
                StandInMinds.contestStage(world, component, StandInPlay.step(component));
                return;
            }
            // 随机行动开着时这一步也停一拍（慢档 1.5 秒）：表态、挑牌一步一步看得见（用户 2026-10-07「demo 玩家不要出牌太快」）
            GameFlow.schedule(component, randomStandIns(component) ? StandInPlay.step(component) : 0L,
                    "这一场：没人要等，按默认往下走", () -> advanceWithoutHumans(world, component));
            return;
        }
        component.clearDecided();
        component.openContestWindow(component.humanWindow(millis));   // 演示局里等真人不限时（用户 2026-10-07）
        Object seconds = component.demoNoTimeout() ? Text.translatable("heavyseas.hud.unlimited")
                : String.valueOf(millis / 1000);   // 演示局不限时
        GameFlow.broadcast(world, who == null
                ? Text.translatable(key, seconds)
                : Text.translatable(key, who, seconds));
        Contest.Stage stage = component.requireSession().contest().orElseThrow().stage();
        LOGGER.info("这一场：{} 开窗口 {} 秒", stage, millis / 1000);
        GameComponents.sync(world);
        if (randomStandIns(component)) {
            // 真人在等这一段：替身在窗口里陆续站出来 / 押下，看得见有人动（StandInPlay）
            switch (stage) {
                case STANCES -> StandInPlay.joinStances(world, component, true);
                case WEAPONS -> StandInPlay.commitWeapons(world, component, true);
                default -> { }
            }
        } else if (StandInMinds.thinks(component)) {
            StandInMinds.contestWindow(world, component, stage);   // 同上，只是每一个都想过
        }
        // 一开窗就没有哪个真人要答（他本人就在场上 · 一张武器都押不出）：直接收短，不空等（ADR-0095 D1）
        endIfAllDecided(world, component);
    }

    /** 替身随机行动开着（{@code /seas dummy random on}）：这一场里替身的那几下随机做，不再一律同意 / 不加入 / 不押。 */
    private static boolean randomStandIns(GameComponent component) {
        return component.dummyAutoplay() && component.dummyRandom();
    }

    /** 全是替身的那一段：按默认答案往下走一步。 */
    private static void advanceWithoutHumans(ServerWorld world, GameComponent component) {
        Optional<Contest> contest = component.requireSession().contest();
        if (contest.isEmpty()) {
            return;                               // 这一步排下来之前已经收场了
        }
        if (StandInMinds.thinks(component)) {
            StandInMinds.contestStage(world, component, 0L);   // 掉线收窗之后：替身照自己的脑子答完这一段
            return;
        }
        boolean random = randomStandIns(component);
        switch (contest.get().stage()) {
            // 替身一律同意（ADR-0023 §7.8）；随机开着时一半一半
            case CONSENT -> consent(world, component, random && StandInPlay.consent(component.gameRandom()));
            case STANCES -> {
                if (random) {
                    StandInPlay.joinStances(world, component, false);
                }
                closeStances(world, component);                     // 关着时替身不加入
            }
            case WEAPONS -> {
                if (random) {
                    StandInPlay.commitWeapons(world, component, false);
                }
                resolve(world, component);                          // 关着时替身不押武器
            }
            case PICK -> autoPick(world, component, contest.get());
        }
    }

    /**
     * 默认挑牌之前先问一句（审查 2026-10-08 C1 的兜底）：被抢方身上此刻已经一张能挑的都没有了，就照「没牌可挑」收尾。
     *
     * <p>按规则走不到这里 —— 挑牌那一刻被抢方亮牌、喝酒都拦着（引擎 {@code Session#reveal} · {@code #drinkRum}）。
     * 原先没有这一问：被抢方喝掉手里唯一那瓶酒之后，默认挑牌去拿面前那瓶，引擎抛「偷窃只能拿手牌」，
     * 这一下在服务端 tick 里没人接住 —— 服务端崩溃。万一哪天又多出一条把牌挪走的路，这一场照样收得了。
     *
     * @return 收了场为 true（调用方不必再挑）
     */
    static boolean endPickIfNothingToTake(ServerWorld world, GameComponent component) {
        Session session = component.requireSession();
        Contest contest = session.contest().orElseThrow();
        if (!session.endPickIfNothingToTake()) {
            return false;
        }
        LOGGER.warn("抢夺：{} 挑牌时 {} 身上已经没有能挑的牌，这一场照「没牌可挑」收场",
                contest.attacker().value(), contest.target().value());
        announceTaken(world, contest, session);
        after(world, component, contest.attacker());
        return true;
    }

    /** 默认的挑牌：手上有就随机一张，手上没有就取面前第一张。 */
    private static void autoPick(ServerWorld world, GameComponent component, Contest contest) {
        Session session = component.requireSession();
        if (endPickIfNothingToTake(world, component)) {
            return;
        }
        if (randomStandIns(component)) {
            String front = StandInPlay.pickFront(session, contest, component.gameRandom());
            if (front != null) {
                pickFromFront(world, component, front);
                return;
            }
        }
        List<String> hand = session.state().stateOf(contest.target()).hand();
        if (hand.isEmpty()) {
            pickFromFront(world, component, session.state().stateOf(contest.target()).front().get(0));
            return;
        }
        pickFromHand(world, component);
    }

    /** 抢夺方赢了却没有牌可挑，或者换座位当场生效 —— 都由这一句收尾。 */
    private static void announceTaken(ServerWorld world, Contest contest, Session session) {
        if (contest.kind() == Contest.Kind.SWAP) {
            GameFlow.broadcast(world, Text.translatable("heavyseas.command.swapped",
                    GameFlow.characterName(contest.attacker()), GameFlow.characterName(contest.target())));
            return;
        }
        GameFlow.broadcast(world, Text.translatable("heavyseas.contest.nothing_to_take",
                GameFlow.characterName(contest.target())).formatted(Formatting.GRAY));
    }

    private static void announceRationed(ServerWorld world, CharacterId actor, int healed) {
        GameFlow.broadcast(world, Text.translatable("heavyseas.command.rationed",
                GameFlow.characterName(actor), healed));
    }

    /**
     * 站队这一段有没有人可能动手。
     *
     * <p>❗<b>不能只问「有没有真人坐着」</b>：替身自动推进关掉时，驱动者是 dev 指令（出口验收的 B 局就是这样）。
     * 只认真人的话，那一局里站队窗口一次都不开，{@code /seas join} 与 {@code /seas weapon} 根本没机会被跑到 ——
     * 而「没开窗口」与「没人加入」在日志上长得一样。
     */
    private static boolean driven(GameComponent component) {
        return component.anyHumanSeated() || !component.dummyAutoplay();
    }

    /** 这一段等的是不是一个真人（替身开着自动推进就不算）。 */
    private static boolean waiting(GameComponent component, CharacterId who) {
        return component.requireSession().state().canAct(who)
                && component.occupantOf(who).map(o -> !o.isDummy() || !component.dummyAutoplay()).orElse(false);
    }

    static void connectionChanged(ServerWorld world, GameComponent component) {
        Optional<Contest> pending = component.requireSession().contest();
        if (pending.isEmpty() || component.contestDeadline() <= 0) {
            return;
        }
        Contest c = pending.get();
        boolean waiting = switch (c.stage()) {
            case CONSENT -> waiting(component, c.target());
            case PICK -> waiting(component, c.attacker());
            case STANCES -> driven(component);
            case WEAPONS -> anyHumanCombatant(component, c) || !component.dummyAutoplay();
        };
        if (!waiting) {
            component.clearContest();
            GameFlow.schedule(component, 0L, "connection changed during contest",
                    () -> advanceWithoutHumans(world, component));
            return;
        }
        // 还有真人要等，但掉线的那一位也许正是最后一个没表态的（审查 2026-10-07 K1）：演示局里这一段不限时，
        //   掉线这条路原先没人问「该答的都答了没有」，窗口就一直开着
        endIfAllDecided(world, component);
    }

    /** 挂武器段只开放给参战者 —— 参战的全是替身时不必开窗口。 */
    private static boolean anyHumanCombatant(GameComponent component, Contest contest) {
        return contest.fight().orElseThrow().combatants().stream().anyMatch(who -> waiting(component, who));
    }
}
