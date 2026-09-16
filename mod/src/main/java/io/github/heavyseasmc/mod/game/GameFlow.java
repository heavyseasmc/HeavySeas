package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.Affinities;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
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
import io.github.heavyseasmc.mod.state.NavCardView;
import io.github.heavyseasmc.mod.world.Seats;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
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
 * 对局流程：把玩家的决定变成 {@link Session} 上的调用，并把结果播给全场。
 *
 * <h2>规则不在这里</h2>
 * 这里一条规则都没有。阶段怎么推进、谁能行动、落海怎么算，全在引擎的 {@code play} 包里 ——
 * 模拟器跑几千局验的就是那一份。本类只做三件事：**问谁、调用、播报**。
 *
 * <h2>M1 还缺的那一处，说出来</h2>
 * <b>没有人能喝水</b>：{@link Session.WaterChoice} 一律返回 0。牌现在真的进了手里，
 * 但「喝几张」是个<b>决策</b>（水同时是谈判筹码，自动替人喝掉就把那条筹码抹了），
 * 需要一面自己的界面与一份超时规则，都还没有。结果是口渴必定造成伤害，对局偏短。
 * 写 0 是「这件事还没做」的老实写法，不是平衡取舍。
 *
 * <h2>谁来推下一步（ADR-0019）</h2>
 * 真人的决定从界面来；替身的决定在自动推进开着时由排程在下一 tick 做（{@link #tick}），关着时等指令；
 * 航海阶段不等任何指令 —— {@link NavigationPhase} 要么当场翻顶牌，要么开舵手的 12 秒窗口。
 */
public final class GameFlow {

    /**
     * ❗播给玩家的话走 lang 键，而**专用服务端不加载客户端资源**，所以控制台日志里看到的是键名。
     * 验收脚本要能判定「这一局真的跑完了」，就得有几行与语言无关的日志。这就是那几行。
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /**
     * 航海结算之后，有真人在座时停多久再进下一回合（ADR-0019）。
     *
     * <p>下一回合的补给箱可能当场弹出来，而底色 {@code 0xE4} 下聊天只剩淡影 —— 刚执行的那张牌就没人看得到了。
     * 没有真人时不停：验收脚本里没人要看。节奏参数，属 ADR-0018 §8「实现中打磨」那一列。
     */
    public static final long REVEAL_HOLD_MS = 3000L;

    private GameFlow() {
    }

    /** 开一局。座位顺序由角色决定（夫人永远在船头），与谁来占无关。 */
    public static void start(ServerWorld world, int players, List<ServerPlayerEntity> humans,
                             Set<CharacterId> reservedForDummies, Vec3d boatAt, float boatYaw) {
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

        // ❗两副牌都要给。只给航海牌的话物资阶段会「牌堆已空」直接跳过 ——
        //   而对局照样能打到终局，所以这个漏接在别处一点痕迹都没有。
        Table table = new Table(
                new NavigationDeck(data.navigation(), new Random(world.getRandom().nextLong())),
                data.provisions(), new Random(world.getRandom().nextLong()));
        Session session = new Session("world=" + world.getRegistryKey().getValue(), roster, table);
        // 开局发爱恨（ADR-0022）：两个独立置换，全程保密。
        // ❗日志里不打谁爱谁、谁恨谁 —— 开服的人往往也是玩家。终局翻牌时才一张张写进日志。
        session.dealAffinities(Affinities.random(roster, new Random(world.getRandom().nextLong())));

        GameComponent component = GameComponents.of(world);
        component.begin(session, occupants);
        // ❗开局也是一次状态变化，先把投影推出去。各面的包（补给箱、划船、舵手）都在这之后发，
        //   而那几面要靠投影判「对局还在不在」—— 投影还没到就收到包的话，界面开出来又当场自己收掉。
        //   2026-09-16 实拍：船头那一位的补给箱一闪即没，然后干等 16 秒超时，屏幕上没有任何报错。
        sync(world);

        broadcast(world, Text.translatable("heavyseas.opening.line1").formatted(Formatting.GOLD));
        broadcast(world, Text.translatable("heavyseas.opening.line2").formatted(Formatting.GRAY));
        broadcast(world, Text.translatable("heavyseas.opening.line3").formatted(Formatting.GRAY));
        for (CharacterId id : session.state().bySeat()) {
            GameComponent.Occupant who = component.occupantOf(id).orElseThrow();
            broadcast(world, Text.translatable("heavyseas.game.seat",
                    session.state().stateOf(id).seat(), characterName(id),
                    who.isDummy() ? Text.translatable("heavyseas.game.dummy") : Text.literal(who.label())));
        }
        LOGGER.info("对局开始：{} 人局 · 座位 {} · 替身自动推进{}", players,
                session.state().bySeat().stream().map(CharacterId::value).toList(),
                component.dummyAutoplay() ? "开" : "关");
        // 位次摆进世界（ADR-0024）。放在播报之后：摆船会再推一次投影，而开局那一帧已经推过了。
        Seats.place(world, component, boatAt, boatYaw, session.state().bySeat().size());
        enterProvision(world, component);
    }

    /**
     * 进入物资阶段：补给箱从船头传起。
     *
     * <p>❗本方法**不推进阶段**。传递是异步的（要等人选牌或等超时），
     * 推进由 {@link #afterProvision} 在传完之后做 —— 在这里推进会让整轮传递被跳过，
     * 而表现只是「物资阶段一闪而过」，不报错。
     */
    public static void enterProvision(ServerWorld world, GameComponent component) {
        Session session = component.requireSession();
        if (session.state().phase() != Phase.PROVISION || session.state().isOver()) {
            announceTurn(world, component);
            return;
        }
        if (session.table().provisionsLeft() == 0) {
            // 牌堆抽完即止，不洗回重用 —— 之后每回合都会走到这里。
            broadcast(world, Text.translatable("heavyseas.game.provision_empty")
                    .formatted(Formatting.DARK_GRAY));
            afterProvision(world, component);
            return;
        }
        // ❗先播报再发牌：全是替身时整轮传递会**同步**走完，
        //   先 begin 的话「开始」那句会排在「结束」后面。
        broadcast(world, Text.translatable("heavyseas.game.provision_begin",
                session.provisionDraws()).formatted(Formatting.DARK_GRAY));
        if (!ProvisionPhase.begin(world, component)) {
            afterProvision(world, component);      // 一个清醒的人都没有：别把局面卡在这儿
        }
    }

    /** 一轮传递结束之后才推进阶段。由 {@link ProvisionPhase} 回调。 */
    public static void afterProvision(ServerWorld world, GameComponent component) {
        Session session = component.requireSession();
        if (session.state().isOver()) {
            announceOutcome(world, component);
            return;
        }
        session.advancePhase();
        announceTurn(world, component);
    }

    /** 播报当前该谁动；航海阶段则交给 {@link NavigationPhase}。 */
    public static void announceTurn(ServerWorld world, GameComponent component) {
        Session session = component.requireSession();
        if (session.state().isOver()) {
            announceOutcome(world, component);
            return;
        }
        // ❗推投影要在分支**之前**。原先只有航海那一支推得到，于是物资→行动之后
        //   HUD 与手牌界面停在「物资」阶段，直到有人行动才跟上 —— 不报错、不掉线，
        //   只是看起来「这阶段怎么不变」。下面几条 return 各自补一行迟早会漏掉一条。
        sync(world);
        if (session.state().phase() == Phase.ACTION) {
            Optional<CharacterId> actor = session.nextActor();
            if (actor.isEmpty()) {
                session.advancePhase();       // 没人能行动是合法状态，直接进航海
                announceTurn(world, component);
                return;
            }
            GameComponent.Occupant who = component.occupantOf(actor.get()).orElseThrow();
            broadcast(world, Text.translatable("heavyseas.game.your_turn",
                    characterName(actor.get()), occupantName(component, actor.get())));
            // 与语言无关的一行：playthrough-check.sh 关着开关打的那一局，按这一行的节奏替替身发指令。
            LOGGER.info("轮到 {} 行动（第 {} 回合 · {}）", actor.get().value(), session.state().turn(),
                    who.isDummy() ? "替身" : "真人");
            if (who.isDummy() && component.dummyAutoplay()) {
                CharacterId dummy = actor.get();
                // ❗排到下一 tick，不当场做：当场做的话，全是替身的一局会在这一次调用里递归打完（ADR-0019 §1）。
                schedule(component, 0L, "替身 " + dummy.value() + " 什么也不做",
                        () -> ActionPhase.autoPass(world, component, dummy));
            }
            return;
        }
        if (session.state().phase() == Phase.NAVIGATION) {
            NavigationPhase.begin(world, component);
        }
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

    /**
     * 航海结算：执行这张牌、把它公开、停一下，然后进下一回合。由 {@link NavigationPhase} 调用。
     *
     * @param pick 舵手挑的那张；{@code null} 表示翻顶牌
     */
    public static void navigate(ServerWorld world, GameComponent component, NavigationCard pick) {
        Session session = component.requireSession();
        NavigationCard card = session.takeCardForNavigation(pick);
        component.clearHelm();
        // 海鸥与落海当场算完；口渴逐个问（ADR-0021）——「喝几张水」是决策，真人答不了同步的问题。
        NavigationReport report = session.beginNavigate(card);

        // 结算后只公开被执行的那一张（决策 ⑭）。播的是它印着什么，不是它的 id —— id 不是给人读的。
        List<String> seats = session.state().bySeat().stream().map(CharacterId::value).toList();
        broadcast(world, Text.translatable("heavyseas.game.card_played",
                NavCardText.describe(NavCardView.of(card), seats)).formatted(Formatting.AQUA));
        if (!report.overboardSelected().isEmpty()) {
            broadcast(world, Text.translatable("heavyseas.game.overboard", names(report.overboardSelected())));
        }
        if (!report.thirstSelected().isEmpty()) {
            broadcast(world, Text.translatable("heavyseas.game.thirst", names(report.thirstSelected())));
        }
        // 死在水里的人连人带牌离场（ADR-0022）。说出来：座位条上他那一格空了，没人说的话看起来像是界面坏了。
        for (CharacterId gone : report.removed()) {
            broadcast(world, Text.translatable("heavyseas.game.removed", characterName(gone))
                    .formatted(Formatting.DARK_AQUA));
        }
        if (!report.removed().isEmpty()) {
            LOGGER.info("移出游戏：{}", ids(report.removed()));
        }
        LOGGER.info("航海牌 {}：海鸥 {} · 落海 {} · 口渴 {}", card.id(), card.gull(),
                ids(report.overboardSelected()), ids(report.thirstSelected()));
        sync(world);                          // 执行的那张进投影：HUD 这时才拿得到
        if (session.state().isOver()) {
            announceOutcome(world, component);
            return;
        }
        ThirstPhase.begin(world, component);
    }

    /**
     * 口渴全部结算完之后：停一下让人看清刚才那张牌，然后进下一回合。由 {@link ThirstPhase} 调用。
     *
     * <p>❗这一段原先在 {@link #navigate} 的末尾。口渴拆成逐个问之后，
     * 「什么时候算完」不再是同一次调用里的事 —— 写在原处的话，下一回合会在还有人没决定时就开始。
     */
    public static void afterNavigation(ServerWorld world, GameComponent component) {
        Session session = component.requireSession();
        // ❗与语言无关的一行，而且是**这一回合真的走完了**的唯一标志：
        //   「航海牌 …」只说明牌结算到了口渴那一步，口渴要逐个问，问完才算完（ADR-0021）。
        //   演示脚本与验收脚本判「可以进下一步了」认的是这一行，不是那一行。
        LOGGER.info("航海阶段结束：第 {} 回合（口渴已结算完）", session.state().turn());
        sync(world);
        if (session.state().isOver()) {
            announceOutcome(world, component);
            return;
        }
        long hold = component.anyHumanSeated() ? REVEAL_HOLD_MS : 0L;
        schedule(component, hold, "航海结算后进下一回合", () -> {
            session.advancePhase();
            enterProvision(world, component);
        });
    }

    /** 把一步排到之后的 tick（ADR-0019）。见 {@link GameComponent.Step}。 */
    static void schedule(GameComponent component, long delayMs, String what, Runnable step) {
        component.schedule(System.currentTimeMillis() + delayMs, what, step);
    }

    /** 每 tick 执行到期的一步。 */
    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        for (ServerWorld world : server.getWorlds()) {
            GameComponent component = GameComponents.of(world);
            Optional<GameComponent.Step> due = component.pollDueStep(now);
            if (due.isEmpty()) {
                continue;
            }
            try {
                due.get().action().run();
            } catch (RuntimeException e) {
                // ❗排程里的一步抛了，局面就停在那儿 —— 不会再有下一步来推它。
                //   与其留一局永远不动的对局，不如当场结束并点名：日志里有这一行，验收脚本才判得出来。
                LOGGER.error("对局推进出错（{}），这一局到此为止", due.get().what(), e);
                component.end();
                sync(world);
                broadcast(world, Text.translatable("heavyseas.game.crashed").formatted(Formatting.RED));
            }
        }
    }

    private static void announceOutcome(ServerWorld world, GameComponent component) {
        if (component.endgame().isPresent()) {
            return;                       // 结束可能从好几条路先后到达；终局序列已经开始了，别再播一遍
        }
        Session session = component.requireSession();
        GameState end = session.state();
        broadcast(world, Text.translatable(outcomeKey(end.outcome().orElseThrow()),
                end.turn(), session.aliveCount()).formatted(Formatting.GOLD));
        for (CharacterId id : end.bySeat()) {
            broadcast(world, Text.translatable(end.isRemoved(id)
                            ? "heavyseas.game.final_line_removed" : "heavyseas.game.final_line",
                    characterName(id), conditionName(end.conditionOf(id))));
        }
        LOGGER.info("对局结束：{} · 第 {} 回合 · 存活 {} 人",
                end.outcome().orElseThrow(), end.turn(), session.aliveCount());
        // ❗不再当场收起会话：终局序列（翻恨 → 翻爱 → 计分）走完才收（ADR-0022）。
        //   原先这里是 component.end() —— 「本阶段不计分」那句占位也一起拿掉了。
        sync(world);
        EndgamePhase.begin(world, component);
    }

    /**
     * <b>夹具</b>（{@code /seas land}）：海鸥直接置满，走正常的终局流程（ADR-0022 §7.7）。
     *
     * <p>各面的计时一并停掉：补给箱、舵手、口渴的窗口若还开着，它们的超时会在终局序列里再推一下已经结束的对局。
     */
    public static void landForFixture(ServerWorld world, GameComponent component) {
        Session session = component.requireSession();
        session.landForFixture();
        component.clearProvision();
        component.clearHelm();
        component.clearThirst();
        LOGGER.info("夹具：直接靠岸（第 {} 回合）", session.state().turn());
        announceOutcome(world, component);
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

    private static List<String> ids(List<CharacterId> ids) {
        return ids.stream().map(CharacterId::value).toList();
    }

    /** 播给全场。包内可见：{@link ActionPhase} 与 {@link NavigationPhase} 要播同样的话。 */
    static void broadcast(ServerWorld world, Text message) {
        MinecraftServer server = world.getServer();
        server.getPlayerManager().broadcast(message, false);
    }
}
