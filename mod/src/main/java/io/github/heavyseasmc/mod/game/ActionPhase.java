package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.ActionChoiceC2S;
import io.github.heavyseasmc.mod.net.RowDecisionC2S;
import io.github.heavyseasmc.mod.net.UseProvisionC2S;
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
 * 行动阶段「界面那一条路」：玩家在行动一面与划船一面上按下的那一下，变成 {@link Session} 上的调用。
 *
 * <h2>规则不在这里</h2>
 * 谁能行动、划船抽几张、行动完轮到谁，全在引擎里 —— 与 {@link ProvisionPhase} 同一条分工。
 * 本类只做三件事：<b>认人、调用、播报</b>，然后交给 {@link GameFlow#finishAction} 推进。
 *
 * <h2>这两面都不计时</h2>
 * 交互稿：行动一面「本身不计时」；划船一面不计时是用户 2026-09-15 定的。所以这里没有 tick、没有超时代选。
 * 代价说出来：真人在轮到自己时掉线，局面会停住 —— 决策 ⑧ 的 {@code offline} 标志还没做（ADR-0019 §3）。
 *
 * <h2>划船分两步</h2>
 * 点「划船」时服务端抽 2 张，只写进划船者那一包（{@code GameComponent#writeView}）；他一张一张定，
 * 两张都定完才算行动结束、才轮到下一个人。
 *
 * <h2>替身</h2>
 * 自动推进开着时，轮到替身由 {@link #autoPass} 替它「什么也不做」（ADR-0019）；关着时照旧由 {@code /seas} 驱动 ——
 * 出口验收（{@code playthrough-check.sh}）关着开关打的那一局靠的就是它。
 */
public final class ActionPhase {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private ActionPhase() {
    }

    /** 客户端来的一下。不是轮到他、不在行动阶段、包里的编码不认识 —— 一律当作没按。 */
    public static void onChoice(ServerPlayerEntity player, ActionChoiceC2S choice) {
        Optional<ActionChoiceC2S.Kind> kind = choice.kind();
        ServerWorld world = player.getServerWorld();
        GameComponent component = GameComponents.of(world);
        if (kind.isEmpty() || component.session().isEmpty()) {
            return;
        }
        Session session = component.requireSession();
        if (session.state().phase() != Phase.ACTION) {
            return;                       // 包与阶段擦肩而过（比如刚被指令推进了）：忽略
        }
        if (session.rower().isPresent()) {
            return;                       // 划船抽到的牌还没定完：行动一面上再按一次不作数（改过的客户端发得出来）
        }
        if (component.provisionTargeter().isPresent()) {
            return;                       // 医疗箱正在挑目标；旧界面或改过的客户端不能趁机再做一个行动
        }
        if (component.designating().isPresent()) {
            // 举着拳头时只认「取消」这一下；别的一律当作没按（改过的客户端发得出任何东西）。
            Optional<CharacterId> raised = component.seatOf(player.getUuid());
            if (kind.get() == ActionChoiceC2S.Kind.CANCEL && raised.isPresent()
                    && component.designating().get().equals(raised.get())) {
                DesignationPhase.cancel(world, component, raised.get());
            }
            return;
        }
        if (kind.get() == ActionChoiceC2S.Kind.CANCEL) {
            return;                       // 没在举着拳头，没什么可取消的
        }
        if (session.contest().isPresent()) {
            // ❗这一场还没收场：引擎照样会抛（requireNoContest），但抛出来的是一句堆栈 ——
            //   而这条路上没有「指令出错」那句人话可说，静默丢掉才是对的（ADR-0023）。
            return;
        }
        Optional<CharacterId> actor = session.nextActor();
        Optional<CharacterId> seat = component.seatOf(player.getUuid());
        // ❗只认正轮到的那个人本人。不校验的话，任何人都能替别人划船。
        if (actor.isEmpty() || seat.isEmpty() || !seat.get().equals(actor.get())) {
            return;
        }
        CharacterId who = actor.get();
        // 与语言无关的一行：验收要从日志里判「界面那条路真的走通了」（专用服务端不加载 lang）。
        LOGGER.info("行动（界面）：{} 选了 {}", who.value(), kind.get());
        switch (kind.get()) {
            case ROW -> beginRow(world, component, player, who);
            case PASS -> {
                GameFlow.broadcast(world, Text.translatable("heavyseas.command.passed", GameFlow.characterName(who)));
                GameFlow.finishAction(world, component, who);
            }
            // ❗这两件**不是当场生效**：进指定模式，回到世界里看着那个人右键（ADR-0025 · 决策 ⑦）。
            //   那个预告窗口本身就是谈判游戏的内容 —— GUI 里选目标是瞬发的，那一幕就没有了。
            case SWAP -> DesignationPhase.begin(world, component, who, Contest.Kind.SWAP);
            case STEAL -> DesignationPhase.begin(world, component, who, Contest.Kind.STEAL);
        }
    }

    /**
     * 手牌一面上的「打出」，以及医疗箱目标一面的第二步。
     *
     * <p>这条路不再借聊天指令：客户端只说「我按了什么」，服务端重新核对阶段、行动者、持牌与效果。
     * 医疗箱先进入一个只投影给本人的目标菜单；其余特殊行动仍是一键完成。
     */
    public static void onUseProvision(ServerPlayerEntity player, UseProvisionC2S action) {
        Optional<UseProvisionC2S.Kind> kind = action.kind();
        ServerWorld world = player.getServerWorld();
        GameComponent component = GameComponents.of(world);
        if (kind.isEmpty() || component.session().isEmpty()) {
            return;
        }
        Optional<CharacterId> seat = component.seatOf(player.getUuid());
        if (seat.isEmpty()) {
            return;
        }
        CharacterId actor = seat.get();
        if (kind.get() == UseProvisionC2S.Kind.CANCEL) {
            if (component.provisionTargeter().map(actor::equals).orElse(false)) {
                component.clearProvisionTarget();
                GameComponents.sync(world);
            }
            return;
        }

        Session session = component.requireSession();
        if (session.state().phase() != Phase.ACTION || session.rower().isPresent()
                || session.contest().isPresent() || component.designating().isPresent()
                || !session.nextActor().map(actor::equals).orElse(false)) {
            return;
        }

        try {
            switch (kind.get()) {
                case PLAY -> beginOrUse(world, component, player, actor, action.card());
                case TARGET -> finishTargetedUse(world, component, player, actor, action.card(), action.target());
                case CANCEL -> { /* handled above */ }
            }
        } catch (RuntimeException e) {
            // 与指令层同一条：规则拒绝要让真人知道，不把一次无效点击伪装成「包没到」。
            LOGGER.info("特殊物资（界面）：{} 的操作被拒绝：{}", actor.value(), e.getMessage());
            player.sendMessage(Text.literal(String.valueOf(e.getMessage())).formatted(Formatting.RED), true);
            if (component.provisionTargeter().map(actor::equals).orElse(false)) {
                component.clearProvisionTarget();
            }
            GameComponents.sync(world);
        }
    }

    private static void beginOrUse(ServerWorld world, GameComponent component, ServerPlayerEntity player,
                                   CharacterId actor, String cardId) {
        Session session = component.requireSession();
        if (component.provisionTargeter().isPresent()) {
            return;                           // 已经在挑目标；第一步的重包不能把它改成另一张牌
        }
        Provision card = session.provisions().get(cardId);
        if (!card.isSpecialAction()) {
            player.sendMessage(Text.translatable("heavyseas.command.not_special", provisionName(cardId))
                    .formatted(Formatting.RED), true);
            return;
        }
        if (!holds(session, actor, cardId)) {
            return;                           // 改过的客户端报了一张并不属于自己的牌
        }
        if (card.effect() instanceof ProvisionEffect.Heal) {
            boolean anyone = session.state().bySeat().stream().anyMatch(id ->
                    !session.state().isRemoved(id)
                            && session.state().conditionOf(id) != Condition.DEAD
                            && session.state().stateOf(id).damage() > 0);
            if (!anyone) {
                player.sendMessage(Text.translatable("heavyseas.command.nobody_wounded")
                        .formatted(Formatting.GRAY), true);
                return;
            }
            component.beginProvisionTarget(actor, cardId);
            LOGGER.info("特殊物资（界面）：{} 用 {}，等他挑治疗目标", actor.value(), cardId);
            GameComponents.sync(world);
            return;
        }
        useUntargeted(world, component, actor, cardId, card.effect());
    }

    private static void finishTargetedUse(ServerWorld world, GameComponent component, ServerPlayerEntity player,
                                          CharacterId actor, String cardId, String targetId) {
        if (!component.provisionTargeter().map(actor::equals).orElse(false)
                || !component.provisionTargetCard().equals(cardId)) {
            return;
        }
        Session session = component.requireSession();
        CharacterId target = CharacterId.of(targetId);
        boolean legal = session.state().bySeat().contains(target)
                && !session.state().isRemoved(target)
                && session.state().conditionOf(target) != Condition.DEAD
                && session.state().stateOf(target).damage() > 0;
        if (!legal) {
            component.clearProvisionTarget();
            player.sendMessage(Text.translatable("heavyseas.target.no_longer_valid")
                    .formatted(Formatting.GRAY), true);
            GameComponents.sync(world);
            return;
        }
        session.useMedicalKit(actor, target, cardId);
        component.clearProvisionTarget();
        GameFlow.broadcast(world, Text.translatable("heavyseas.command.healed",
                GameFlow.characterName(actor), GameFlow.characterName(target)));
        LOGGER.info("特殊物资（界面）：{} 用 {} 治了 {}", actor.value(), cardId, target.value());
        GameFlow.finishAction(world, component, actor);
    }

    private static void useUntargeted(ServerWorld world, GameComponent component, CharacterId actor,
                                      String cardId, ProvisionEffect effect) {
        Session session = component.requireSession();
        if (effect instanceof ProvisionEffect.PreventThirst) {
            session.openParasol(actor, cardId);
            GameFlow.broadcast(world, Text.translatable("heavyseas.command.opened",
                    GameFlow.characterName(actor), provisionName(cardId)));
        } else if (effect instanceof ProvisionEffect.HealAll) {
            // 绝境不是按一下就生效：其余清醒角色要逐个有机会反对，反对后复用完整战斗状态机。
            ContestPhase.beginRation(world, component, actor, cardId);
            return;
        } else if (effect instanceof ProvisionEffect.WeaponOrSpecial) {
            int before = session.state().gulls();
            session.fireSignal(actor, cardId);
            GameFlow.broadcast(world, Text.translatable("heavyseas.command.signalled",
                    GameFlow.characterName(actor), session.state().gulls() - before));
        } else {
            throw new IllegalArgumentException("这张特殊物资还没有行动阶段处理器: " + cardId);
        }
        LOGGER.info("特殊物资（界面）：{} 打出 {}", actor.value(), cardId);
        GameFlow.finishAction(world, component, actor);
    }

    private static boolean holds(Session session, CharacterId actor, String cardId) {
        return session.state().stateOf(actor).hasInHand(cardId)
                || session.state().stateOf(actor).hasInFront(cardId);
    }

    private static Text provisionName(String cardId) {
        return Text.translatable("heavyseas.provision." + cardId);
    }

    /**
     * 划船第一步：抽 2 张到他手上，推一次投影 —— 两张牌只进他那一包，划船一面由此弹出。
     *
     * <p>牌堆空到一张都抽不出来时，引擎当场结束这次划船（照样领标记），这里就直接算行动结束。
     */
    private static void beginRow(ServerWorld world, GameComponent component, ServerPlayerEntity player,
                                 CharacterId who) {
        try {
            Session session = component.requireSession();
            List<NavigationCard> drawn = session.beginRow(who);
            GameFlow.broadcast(world, Text.translatable("heavyseas.game.rowing", GameFlow.characterName(who)));
            if (session.rower().isEmpty()) {
                finishRow(world, component, who);
                return;
            }
            LOGGER.info("划船：{} 抽了 {} 张，等他一张一张定", who.value(), drawn.size());
            GameComponents.sync(world);
        } catch (RuntimeException failure) {
            LOGGER.info("划船（界面）：{} 的操作被拒绝：{}", who.value(), failure.getMessage());
            player.sendMessage(Text.literal(String.valueOf(failure.getMessage())).formatted(Formatting.RED), true);
        }
    }

    /** 划船一面上定下的一张。不是划船者本人、不是还没定的那张 —— 一律当作没按。 */
    public static void onRowDecision(ServerPlayerEntity player, RowDecisionC2S decision) {
        ServerWorld world = player.getServerWorld();
        GameComponent component = GameComponents.of(world);
        if (component.session().isEmpty()) {
            return;
        }
        Session session = component.requireSession();
        Optional<CharacterId> rower = session.rower();
        Optional<CharacterId> seat = component.seatOf(player.getUuid());
        // ❗只认划船者本人。不校验的话，任何人都能替别人决定哪张进划船堆。
        if (rower.isEmpty() || seat.isEmpty() || !seat.get().equals(rower.get())) {
            return;
        }
        List<Session.RowCard> cards = session.rowing();
        int index = decision.index();
        if (index < 0 || index >= cards.size() || cards.get(index).fate() != Session.RowFate.UNDECIDED) {
            return;                       // 同一张按了两下、包与状态擦肩而过：忽略
        }
        CharacterId who = rower.get();
        boolean done = session.decideRow(index, decision.keep());
        LOGGER.info("划船（界面）：{} 第 {} 张{}", who.value(), index + 1, decision.keep() ? "留进划船堆" : "塞回牌堆底");
        if (done) {
            finishRow(world, component, who);
        } else {
            GameComponents.sync(world);   // 划船堆多没多一张是公开的；他那一包里这一张也定了
        }
    }

    private static void finishRow(ServerWorld world, GameComponent component, CharacterId who) {
        Session session = component.requireSession();
        GameFlow.broadcast(world, Text.translatable("heavyseas.game.rowed", GameFlow.characterName(who),
                session.table().rowStack().size()));
        GameFlow.finishAction(world, component, who);
    }

    /**
     * 替身自动推进（ADR-0019）：轮到替身时「什么也不做」。由排程在下一 tick 调用。
     *
     * <p>❗先核对局面还是不是排下它时的样子：这中间指令可能已经替这个替身行动过了，
     * 不核对的话，它会替<b>下一个人</b>什么也不做；开关也可能刚被关掉，关了就该等指令。
     */
    public static void autoPass(ServerWorld world, GameComponent component, CharacterId dummy) {
        Session session = component.requireSession();
        if (!component.dummyAutoplay() || session.state().phase() != Phase.ACTION || session.rower().isPresent()
                || !session.nextActor().map(dummy::equals).orElse(false)) {
            return;
        }
        GameFlow.broadcast(world, Text.translatable("heavyseas.command.passed", GameFlow.characterName(dummy)));
        LOGGER.info("行动（替身自动）：{} 什么也不做", dummy.value());
        GameFlow.finishAction(world, component, dummy);
    }

    /**
     * 划船一次走完：抽 {@link Session#CARDS_DRAWN_WHEN_ROWING} 张，按给定的去留逐张决定。{@code /seas row} 用它。
     *
     * <p>界面那条路不走这里：真人要一张一张想，走的是 {@link Session#beginRow} 加 {@link Session#decideRow} 两步。
     * 两条路底下是同一份规则 —— {@link Session#row} 本身就是那两步的简写。
     */
    public static List<NavigationCard> rowKeeping(Session session, CharacterId rower,
                                                  boolean keepFirst, boolean keepSecond) {
        boolean[] wanted = {keepFirst, keepSecond};
        int[] seen = {0};
        return session.row(rower, (card, state, who) -> {
            int i = seen[0]++;
            return i < wanted.length && wanted[i];
        });
    }
}
