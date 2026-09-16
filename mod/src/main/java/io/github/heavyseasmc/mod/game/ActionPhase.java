package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.ActionChoiceC2S;
import io.github.heavyseasmc.mod.net.RowDecisionC2S;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
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
            case ROW -> beginRow(world, component, who);
            case PASS -> {
                GameFlow.broadcast(world, Text.translatable("heavyseas.command.passed", GameFlow.characterName(who)));
                GameFlow.finishAction(world, component, who);
            }
        }
    }

    /**
     * 划船第一步：抽 2 张到他手上，推一次投影 —— 两张牌只进他那一包，划船一面由此弹出。
     *
     * <p>牌堆空到一张都抽不出来时，引擎当场结束这次划船（照样领标记），这里就直接算行动结束。
     */
    private static void beginRow(ServerWorld world, GameComponent component, CharacterId who) {
        Session session = component.requireSession();
        List<NavigationCard> drawn = session.beginRow(who);
        GameFlow.broadcast(world, Text.translatable("heavyseas.game.rowing", GameFlow.characterName(who)));
        if (session.rower().isEmpty()) {
            finishRow(world, component, who);
            return;
        }
        LOGGER.info("划船：{} 抽了 {} 张，等他一张一张定", who.value(), drawn.size());
        GameComponents.sync(world);
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
