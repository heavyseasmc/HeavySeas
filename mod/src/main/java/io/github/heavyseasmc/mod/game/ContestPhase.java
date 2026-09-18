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
import net.minecraft.server.MinecraftServer;
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
 * 表态与挑牌各一个固定窗口。
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

    /** 被指定的人表态：同意还是战斗。超时 = 同意。 */
    public static final long CONSENT_MILLIS = 12_000L;

    /** 站队段。 */
    public static final long STANCE_MILLIS = 15_000L;

    /** 有人加入之后剩下的时间。 */
    public static final long STANCE_BUMP_MILLIS = 8_000L;

    /** 挂武器段。 */
    public static final long WEAPON_MILLIS = 10_000L;

    /** 有人押下之后剩下的时间。 */
    public static final long WEAPON_BUMP_MILLIS = 6_000L;

    /** 抢夺方挑牌。超时 = 手牌随机一张（手上没有就取面前第一张）。 */
    public static final long PICK_MILLIS = 12_000L;

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

    /** 被指定的人表态。{@code fight = false} 是同意（超时也走这一条）。 */
    public static void consent(ServerWorld world, GameComponent component, boolean fight) {
        Session session = component.requireSession();
        Contest contest = session.contest().orElseThrow();
        CharacterId attacker = contest.attacker();
        CharacterId target = contest.target();
        session.consent(fight);
        LOGGER.info("表态：{} {}", target.value(), fight ? "战斗" : "同意");
        String responseKey = contest.kind() == Contest.Kind.RATION
                ? (fight ? "heavyseas.contest.ration_objected" : "heavyseas.contest.ration_passed")
                : (fight ? "heavyseas.contest.refused" : "heavyseas.contest.agreed");
        GameFlow.broadcast(world, Text.translatable(responseKey,
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
        component.openContestWindow(STANCE_BUMP_MILLIS);
        GameComponents.sync(world);
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
        component.openContestWindow(WEAPON_BUMP_MILLIS);
        GameComponents.sync(world);
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
        session.pickFromHand(world.getRandom().nextInt(size));
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
    public static void tick(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            GameComponent component = GameComponents.of(world);
            long deadline = component.contestDeadline();
            if (component.session().isEmpty() || deadline <= 0 || System.currentTimeMillis() < deadline) {
                continue;
            }
            Optional<Contest> contest = component.requireSession().contest();
            if (contest.isEmpty()) {
                component.clearContest();          // 这一场已经收场了，窗口是残影
                continue;
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
        if (pending.isEmpty() || seat.isEmpty()) {
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
        if (session.state().isRemoved(who) || !session.state().conditionOf(who).canAct()) {
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
        if (!contest.fight().map(f -> f.combatants().contains(who)).orElse(false)) {
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
        switch (contest.get().stage()) {
            case CONSENT -> open(world, component, CONSENT_MILLIS, "heavyseas.contest.consent_wait",
                    GameFlow.characterName(contest.get().target()),
                    waiting(component, contest.get().target()));
            case STANCES -> open(world, component, STANCE_MILLIS, "heavyseas.contest.stances",
                    null, driven(component));
            case WEAPONS -> open(world, component, WEAPON_MILLIS, "heavyseas.contest.weapons",
                    null, anyHumanCombatant(component, contest.get()) || !component.dummyAutoplay());
            case PICK -> open(world, component, PICK_MILLIS, "heavyseas.contest.pick_wait",
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
            GameFlow.schedule(component, 0L, "这一场：没人要等，按默认往下走",
                    () -> advanceWithoutHumans(world, component));
            return;
        }
        component.openContestWindow(millis);
        GameFlow.broadcast(world, who == null
                ? Text.translatable(key, millis / 1000)
                : Text.translatable(key, who, millis / 1000));
        LOGGER.info("这一场：{} 开窗口 {} 秒", component.requireSession().contest().orElseThrow().stage(),
                millis / 1000);
        GameComponents.sync(world);
    }

    /** 全是替身的那一段：按默认答案往下走一步。 */
    private static void advanceWithoutHumans(ServerWorld world, GameComponent component) {
        Optional<Contest> contest = component.requireSession().contest();
        if (contest.isEmpty()) {
            return;                               // 这一步排下来之前已经收场了
        }
        switch (contest.get().stage()) {
            case CONSENT -> consent(world, component, false);       // 替身一律同意（ADR-0023 §7.8）
            case STANCES -> closeStances(world, component);         // 替身不加入
            case WEAPONS -> resolve(world, component);              // 替身不押武器
            case PICK -> autoPick(world, component, contest.get());
        }
    }

    /** 默认的挑牌：手上有就随机一张，手上没有就取面前第一张。 */
    private static void autoPick(ServerWorld world, GameComponent component, Contest contest) {
        Session session = component.requireSession();
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
        return component.occupantOf(who).map(o -> !o.isDummy() || !component.dummyAutoplay()).orElse(false);
    }

    /** 挂武器段只开放给参战者 —— 参战的全是替身时不必开窗口。 */
    private static boolean anyHumanCombatant(GameComponent component, Contest contest) {
        return contest.fight().orElseThrow().combatants().stream().anyMatch(who -> waiting(component, who));
    }
}
