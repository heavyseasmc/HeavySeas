package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationDeck;
import io.github.heavyseasmc.engine.play.NavigationReport;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.play.Table;
import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.data.GameData;
import io.github.heavyseasmc.mod.data.GameDataLoader;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * 对局流程：把玩家的指令变成 {@link Session} 上的调用，并把结果播给全场。
 *
 * <h2>规则不在这里</h2>
 * 这里一条规则都没有。阶段怎么推进、谁能行动、落海怎么算，全在引擎的 {@code play} 包里 ——
 * 模拟器跑几千局验的就是那一份。本类只做三件事：**问谁、调用、播报**。
 *
 * <h2>M1 的两处刻意缺席</h2>
 * <ul>
 *   <li><b>物资阶段是空转</b>：手牌与物资尚未建模，所以这一阶段只算出「该抽几张」就直接过。
 *       不装作发了牌 —— 装作发了牌，会让「物资没做」与「物资坏了」表现完全一样。</li>
 *   <li><b>没有人能喝水</b>：{@link Session.WaterChoice} 一律返回 0。没有手牌就没有水，
 *       让玩家「喝一张不存在的水」比直接说没有更坏。结果是口渴必定造成伤害，对局偏短。</li>
 * </ul>
 */
public final class GameFlow {

    /**
     * ❗播给玩家的话走 lang 键，而**专用服务端不加载客户端资源**，所以控制台日志里看到的是键名。
     * 验收脚本要能判定「这一局真的跑完了」，就得有几行与语言无关的日志。这就是那几行。
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private GameFlow() {
    }

    /** 开一局。座位顺序由角色决定（夫人永远在船头），与谁来占无关。 */
    public static void start(ServerWorld world, int players, List<ServerPlayerEntity> humans,
                             Set<CharacterId> reservedForDummies) {
        GameData data = GameDataLoader.require();
        Roster roster = data.roster().preset(players);

        List<CharacterId> seats = roster.survivors().stream().map(Survivor::id).toList();
        for (CharacterId reserved : reservedForDummies) {
            if (!seats.contains(reserved)) {
                throw new IllegalArgumentException(
                        "%d 人局里没有 %s 这个角色".formatted(players, reserved.value()));
            }
        }

        // 角色随机发（决策 ⑯）。dummy 预定的座位先扣掉，剩下的在真人之间随机分。
        List<CharacterId> open = new ArrayList<>(seats);
        open.removeAll(reservedForDummies);
        List<CharacterId> shuffled = new ArrayList<>(open);
        java.util.Collections.shuffle(shuffled, new Random(world.getRandom().nextLong()));

        Map<CharacterId, GameComponent.Occupant> occupants = new LinkedHashMap<>();
        for (CharacterId dummy : reservedForDummies) {
            occupants.put(dummy, new GameComponent.Occupant(null, "dummy"));
        }
        int taken = 0;
        for (ServerPlayerEntity human : humans) {
            if (taken >= shuffled.size()) {
                break;                       // 人比座位多：多出来的人这一局是旁观
            }
            occupants.put(shuffled.get(taken++),
                    new GameComponent.Occupant(human.getUuid(), human.getGameProfile().getName()));
        }
        // 没人占的座位一律是 dummy —— 一个人也能把整局跑完（决策 ② 的硬性前置）。
        for (int i = taken; i < shuffled.size(); i++) {
            occupants.put(shuffled.get(i), new GameComponent.Occupant(null, "dummy"));
        }

        Table table = new Table(new NavigationDeck(data.navigation(), new Random(world.getRandom().nextLong())));
        Session session = new Session("world=" + world.getRegistryKey().getValue(), roster, table);

        GameComponent component = GameComponents.of(world);
        component.begin(session, occupants);

        broadcast(world, Text.translatable("heavyseas.opening.line1").formatted(Formatting.GOLD));
        broadcast(world, Text.translatable("heavyseas.opening.line2").formatted(Formatting.GRAY));
        broadcast(world, Text.translatable("heavyseas.opening.line3").formatted(Formatting.GRAY));
        for (CharacterId id : session.state().bySeat()) {
            GameComponent.Occupant who = component.occupantOf(id).orElseThrow();
            broadcast(world, Text.translatable("heavyseas.game.seat",
                    session.state().stateOf(id).seat(), characterName(id),
                    who.isDummy() ? Text.translatable("heavyseas.game.dummy") : Text.literal(who.label())));
        }
        LOGGER.info("对局开始：{} 人局 · 座位 {}", players,
                session.state().bySeat().stream().map(CharacterId::value).toList());
        settleIntoActionPhase(world, component);
    }

    /**
     * 物资阶段只算一遍抽牌数就过去。
     *
     * <p>❗写成一个会说话的步骤而不是悄悄跳过：**「这一阶段没做」与「这一阶段坏了」
     * 必须在输出上分得开**，否则以后接手的人会以为物资已经实现了。
     */
    public static void settleIntoActionPhase(ServerWorld world, GameComponent component) {
        Session session = component.requireSession();
        while (session.state().phase() == Phase.PROVISION && !session.state().isOver()) {
            int draws = session.provisionDraws();
            broadcast(world, Text.translatable("heavyseas.game.provision_stub", draws)
                    .formatted(Formatting.DARK_GRAY));
            session.advancePhase();
        }
        announceTurn(world, component);
    }

    /** 播报当前该谁动，或该谁挑牌。 */
    public static void announceTurn(ServerWorld world, GameComponent component) {
        Session session = component.requireSession();
        if (session.state().isOver()) {
            announceOutcome(world, component);
            return;
        }
        if (session.state().phase() == Phase.ACTION) {
            Optional<CharacterId> actor = session.nextActor();
            if (actor.isEmpty()) {
                session.advancePhase();       // 没人能行动是合法状态，直接进航海
                announceTurn(world, component);
                return;
            }
            broadcast(world, Text.translatable("heavyseas.game.your_turn",
                    characterName(actor.get()), occupantName(component, actor.get())));
            return;
        }
        if (session.state().phase() == Phase.NAVIGATION) {
            if (session.helmsmanMayPick()) {
                CharacterId helm = session.state().helmsman().orElseThrow();
                broadcast(world, Text.translatable("heavyseas.game.helmsman_picks",
                        characterName(helm), session.table().rowStack().size()));
            } else {
                broadcast(world, Text.translatable("heavyseas.game.top_card"));
            }
        }
        sync(world);
    }

    /**
     * 把投影推给局内玩家。
     *
     * <p>每次状态变化都推一次。**漏推的表现是 HUD 停在旧数字上** —— 不报错、不掉线，
     * 玩家只会觉得「这血怎么不掉」，而那时早已看不出是哪一步漏了。
     */
    private static void sync(ServerWorld world) {
        GameComponents.sync(world);
    }

    /** 一个人行动结束：记标记，然后看还有没有下一个。 */
    public static void finishAction(ServerWorld world, GameComponent component, CharacterId actor) {
        Session session = component.requireSession();
        session.markActed(actor);
        sync(world);
        if (session.state().isOver()) {
            announceOutcome(world, component);
            return;
        }
        if (session.nextActor().isEmpty()) {
            session.advancePhase();
        }
        announceTurn(world, component);
    }

    /** 航海结算，然后推进到下一回合。 */
    public static void navigate(ServerWorld world, GameComponent component,
                                io.github.heavyseasmc.engine.navigation.NavigationCard pick) {
        Session session = component.requireSession();
        var card = session.takeCardForNavigation(pick);
        // ❗没有手牌就没有水。返回 0 是「这件事还没做」的老实写法，不是平衡取舍。
        NavigationReport report = session.navigate(card, (who, effective, state) -> 0);

        broadcast(world, Text.translatable("heavyseas.game.card_played", card.id(), card.gull())
                .formatted(Formatting.AQUA));
        if (!report.overboardSelected().isEmpty()) {
            broadcast(world, Text.translatable("heavyseas.game.overboard", names(report.overboardSelected())));
        }
        if (!report.thirstSelected().isEmpty()) {
            broadcast(world, Text.translatable("heavyseas.game.thirst", names(report.thirstSelected())));
        }
        if (session.state().isOver()) {
            announceOutcome(world, component);
            return;
        }
        session.advancePhase();
        settleIntoActionPhase(world, component);
    }

    private static void announceOutcome(ServerWorld world, GameComponent component) {
        Session session = component.requireSession();
        GameState end = session.state();
        broadcast(world, Text.translatable(outcomeKey(end.outcome().orElseThrow()),
                end.turn(), session.aliveCount()).formatted(Formatting.GOLD));
        for (CharacterId id : end.bySeat()) {
            broadcast(world, Text.translatable("heavyseas.game.final_line", characterName(id),
                    conditionName(end.conditionOf(id))));
        }
        // ❗M1 不计分：财宝、爱恨都还没建模，算出来的分会是假的。
        broadcast(world, Text.translatable("heavyseas.game.no_score_yet").formatted(Formatting.DARK_GRAY));
        LOGGER.info("对局结束：{} · 第 {} 回合 · 存活 {} 人",
                end.outcome().orElseThrow(), end.turn(), session.aliveCount());
        component.end();
        sync(world);                      // 对局结束也要推，否则 HUD 会一直挂着最后一帧
    }

    /** 一行状态：回合、阶段、海鸥、每个人的伤势。纯文字 HUD 的服务端那一半。 */
    public static List<Text> statusLines(GameComponent component) {
        Session session = component.requireSession();
        GameState g = session.state();
        List<Text> out = new ArrayList<>();
        out.add(Text.translatable("heavyseas.status.header", g.turn(), phaseName(g.phase()),
                g.gulls(), GameState.GULLS_TO_LAND).formatted(Formatting.GOLD));
        for (CharacterId id : g.bySeat()) {
            Survivor s = g.roster().get(id);
            Condition condition = g.conditionOf(id);
            out.add(Text.translatable("heavyseas.status.line",
                    g.stateOf(id).seat(), characterName(id), occupantName(component, id),
                    s.size() - g.stateOf(id).damage(), s.size(),
                    conditionName(condition), g.stateOf(id).thirst().count()));
        }
        return out;
    }

    /**
     * 全场只有一套识别词：职业（方案 §4.1，称呼层已取消）。
     *
     * <p>❗这是唯一一处**拼出来**的 lang 键，因为角色 id 来自数据、代码里没有那张表。
     * 构建期的 {@code checkLangKeys} 因此单独按 {@code data/roster} 核对这一族。
     */
    public static Text characterName(CharacterId id) {
        return Text.translatable("heavyseas.character." + id.value());
    }

    /**
     * 下面三个用 switch 而不是拼字符串。
     *
     * <p>拼出来的键**静态扫不到**，而漏一个键的表现是界面上出现一行
     * {@code heavyseas.condition.dead} 这样的原文 —— 游戏照跑，没有任何报错。
     * 写成 switch 之后，加一个枚举值编译期就红，键漏没漏也由构建期的闸门查得到。
     */
    private static Text conditionName(Condition condition) {
        return Text.translatable(switch (condition) {
            case CONSCIOUS -> "heavyseas.condition.conscious";
            case UNCONSCIOUS -> "heavyseas.condition.unconscious";
            case DEAD -> "heavyseas.condition.dead";
        });
    }

    private static Text phaseName(Phase phase) {
        return Text.translatable(switch (phase) {
            case PROVISION -> "heavyseas.phase.provision";
            case ACTION -> "heavyseas.phase.action";
            case NAVIGATION -> "heavyseas.phase.navigation";
        });
    }

    private static String outcomeKey(GameState.Outcome outcome) {
        return switch (outcome) {
            case LANDED -> "heavyseas.game.over.landed";
            case ALL_DEAD -> "heavyseas.game.over.all_dead";
        };
    }

    private static Text occupantName(GameComponent component, CharacterId id) {
        return component.occupantOf(id)
                .map(o -> o.isDummy() ? Text.translatable("heavyseas.game.dummy") : Text.literal(o.label()))
                .orElse(Text.literal("?"));
    }

    private static Text names(List<CharacterId> ids) {
        Text joined = Text.empty();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                joined = joined.copy().append(Text.literal("、"));
            }
            joined = joined.copy().append(characterName(ids.get(i)));
        }
        return joined;
    }

    private static void broadcast(ServerWorld world, Text message) {
        MinecraftServer server = world.getServer();
        server.getPlayerManager().broadcast(message, false);
    }
}
