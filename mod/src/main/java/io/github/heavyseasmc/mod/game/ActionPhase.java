package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.ActionChoiceC2S;
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
 * 行动阶段「界面那一条路」：玩家在行动一面上按下的那一下，变成 {@link Session} 上的调用。
 *
 * <h2>规则不在这里</h2>
 * 谁能行动、划船抽几张、行动完轮到谁，全在引擎里 —— 与 {@link ProvisionPhase} 同一条分工。
 * 本类只做三件事：<b>认人、调用、播报</b>，然后交给 {@link GameFlow#finishAction} 推进。
 *
 * <h2>这一面不计时</h2>
 * 交互稿：「这一面本身不计时」。所以这里没有 tick、没有超时代选 ——
 * 计时属于「指定模式」（决策 ⑦：15 秒不选人退回重选，再超时算 Pass），那一半还没做。
 * 替身照旧由 {@code /seas} 驱动：出口验收（{@code playthrough-check.sh}）靠的就是它。
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
        Optional<CharacterId> actor = session.nextActor();
        Optional<CharacterId> seat = component.seatOf(player.getUuid());
        // ❗只认正轮到的那个人本人。不校验的话，任何人都能替别人划船。
        if (actor.isEmpty() || seat.isEmpty() || !seat.get().equals(actor.get())) {
            return;
        }
        CharacterId who = actor.get();
        Text name = GameFlow.characterName(who);
        switch (kind.get()) {
            case ROW -> {
                int seen = rowKeeping(session, who, true, true).size();
                GameFlow.broadcast(world, Text.translatable("heavyseas.command.rowed", name, seen));
            }
            case PASS -> GameFlow.broadcast(world, Text.translatable("heavyseas.command.passed", name));
        }
        // 与语言无关的一行：验收要从日志里判「界面那条路真的走通了」（专用服务端不加载 lang）。
        LOGGER.info("行动（界面）：{} 选了 {}", who.value(), kind.get());
        GameFlow.finishAction(world, component, who);
    }

    /**
     * 划船：抽 {@link Session#CARDS_DRAWN_WHEN_ROWING} 张，按给定的去留逐张决定。指令与界面共用这一段。
     *
     * <p>去留以参数一次给全：划船那一面（决策 ⑭：抽一张、想一想、再决定下一张）还没做。
     * 界面这条路此刻给的是「两张都留」，与 {@code /seas row} 不带参数时同一个默认。
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
