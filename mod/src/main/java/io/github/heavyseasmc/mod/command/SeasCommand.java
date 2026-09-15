package io.github.heavyseasmc.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.data.GameDataLoader;
import io.github.heavyseasmc.mod.game.ActionPhase;
import io.github.heavyseasmc.mod.game.GameFlow;
import io.github.heavyseasmc.mod.game.NavigationPhase;
import io.github.heavyseasmc.mod.game.ThirstPhase;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code /seas} —— M1 的全部操作入口；M2 起是开发脚手架（ADR-0017：玩家侧指令作废，GUI 是唯一路径）。
 *
 * <h2>为什么还留着</h2>
 * 指令能被一个人跑完 —— 配合 dummy，开发期不必凑 6 个真人；出口验收 {@code playthrough-check.sh}
 * 关着替身自动推进打的那一局，靠的就是这里的 {@code pass · row · swap · fight}（ADR-0019）。
 * 玩家侧的几条等 GUI 能驱动整局之后再删（O18）。
 *
 * <h2>谁能替谁下指令</h2>
 * 真人占的座位只有他本人能动；dummy 占的座位要 2 级权限（它是测试夹具，不该让普通玩家随手替人行动）。
 * {@code navigate} 与 {@code dummy auto} 一律 2 级：舵手挑牌走界面，指令只是开发者提前定的口子。
 */
public final class SeasCommand {

    private static final int DEV_PERMISSION = 2;

    /**
     * ❗Minecraft 接住指令里抛出的异常，只对来源打一句「An unexpected error occurred」，
     * <b>堆栈要 debug 级才打</b>。于是「指令有 bug」与「指令拒绝了你」在日志上长得一样。
     * 每条 /seas 都经这里包一层，异常连堆栈一起进日志。
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private static com.mojang.brigadier.Command<ServerCommandSource> guarded(
            com.mojang.brigadier.Command<ServerCommandSource> inner) {
        return context -> {
            try {
                return inner.run(context);
            } catch (RuntimeException e) {
                LOGGER.error("/seas 执行失败", e);
                context.getSource().sendError(Text.literal("/seas 出错：" + e));
                return 0;
            }
        };
    }

    private SeasCommand() {
    }

    /** 角色 id 的补全：只补当前这一局里真的存在的角色，避免补出一个 7 人局才有的名字。 */
    private static final SuggestionProvider<ServerCommandSource> CHARACTERS = (context, builder) -> {
        GameComponent component = GameComponents.of(context.getSource().getWorld());
        component.session().ifPresentOrElse(
                session -> session.state().bySeat().forEach(id -> builder.suggest(id.value())),
                () -> GameDataLoader.require().roster().characters()
                        .forEach(s -> builder.suggest(s.id().value())));
        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("seas")
                .then(CommandManager.literal("start")
                        .requires(source -> source.hasPermissionLevel(DEV_PERMISSION))
                        .executes(guarded(context -> start(context, 6)))
                        .then(CommandManager.argument("players", IntegerArgumentType.integer(6, 8))
                                .executes(guarded(context -> start(context,
                                        IntegerArgumentType.getInteger(context, "players"))))))
                .then(CommandManager.literal("end")
                        .requires(source -> source.hasPermissionLevel(DEV_PERMISSION))
                        .executes(guarded(SeasCommand::end)))
                .then(CommandManager.literal("dummy")
                        .requires(source -> source.hasPermissionLevel(DEV_PERMISSION))
                        .then(CommandManager.literal("add")
                                .then(CommandManager.argument("character", StringArgumentType.word())
                                        .suggests(CHARACTERS)
                                        .executes(guarded(SeasCommand::dummyAdd))))
                        .then(CommandManager.literal("auto")
                                .executes(guarded(context -> dummyAuto(context, null)))
                                .then(CommandManager.literal("on")
                                        .executes(guarded(context -> dummyAuto(context, true))))
                                .then(CommandManager.literal("off")
                                        .executes(guarded(context -> dummyAuto(context, false))))))
                .then(CommandManager.literal("status").executes(guarded(SeasCommand::status)))
                .then(CommandManager.literal("pass").executes(guarded(SeasCommand::pass)))
                .then(CommandManager.literal("row")
                        .executes(guarded(context -> row(context, true, true)))
                        .then(CommandManager.argument("keepFirst", BoolArgumentType.bool())
                                .then(CommandManager.argument("keepSecond", BoolArgumentType.bool())
                                        .executes(guarded(context -> row(context,
                                                BoolArgumentType.getBool(context, "keepFirst"),
                                                BoolArgumentType.getBool(context, "keepSecond")))))))
                .then(CommandManager.literal("swap")
                        .then(CommandManager.argument("character", StringArgumentType.word())
                                .suggests(CHARACTERS)
                                .executes(guarded(SeasCommand::swap))))
                .then(CommandManager.literal("fight")
                        .then(CommandManager.argument("character", StringArgumentType.word())
                                .suggests(CHARACTERS)
                                .executes(guarded(SeasCommand::fight))))
                .then(CommandManager.literal("reveal")
                        .then(CommandManager.argument("character", StringArgumentType.word())
                                .suggests(CHARACTERS)
                                .then(CommandManager.argument("card", StringArgumentType.word())
                                        .suggests(HELD_CARDS)
                                        .executes(guarded(SeasCommand::reveal)))))
                .then(CommandManager.literal("give")
                        .then(CommandManager.argument("character", StringArgumentType.word())
                                .suggests(CHARACTERS)
                                .then(CommandManager.argument("to", StringArgumentType.word())
                                        .suggests(CHARACTERS)
                                        .then(CommandManager.argument("card", StringArgumentType.word())
                                                .suggests(HELD_CARDS)
                                                .executes(guarded(SeasCommand::give))))))
                .then(CommandManager.literal("drink")
                        .then(CommandManager.argument("character", StringArgumentType.word())
                                .suggests(CHARACTERS)
                                .then(CommandManager.argument("card", StringArgumentType.word())
                                        .suggests(HELD_CARDS)
                                        .executes(guarded(SeasCommand::drink)))))
                .then(CommandManager.literal("use")
                        .then(CommandManager.argument("card", StringArgumentType.word())
                                .suggests(HELD_CARDS)
                                .executes(guarded(context -> use(context, null)))
                                .then(CommandManager.argument("target", StringArgumentType.word())
                                        .suggests(CHARACTERS)
                                        .executes(guarded(context -> use(context,
                                                StringArgumentType.getString(context, "target")))))))
                .then(CommandManager.literal("water")
                        .then(CommandManager.argument("cups", IntegerArgumentType.integer(0, 9))
                                .executes(guarded(context -> water(context,
                                        IntegerArgumentType.getInteger(context, "cups")))))
                        .then(CommandManager.literal("from")
                                .then(CommandManager.argument("character", StringArgumentType.word())
                                        .suggests(CHARACTERS)
                                        .executes(guarded(SeasCommand::waterFrom)))))
                .then(CommandManager.literal("grant")
                        .requires(source -> source.hasPermissionLevel(DEV_PERMISSION))
                        .then(CommandManager.argument("character", StringArgumentType.word())
                                .suggests(CHARACTERS)
                                .then(CommandManager.argument("card", StringArgumentType.word())
                                        .executes(guarded(SeasCommand::grant)))))
                .then(CommandManager.literal("navigate")
                        .requires(source -> source.hasPermissionLevel(DEV_PERMISSION))
                        .executes(guarded(context -> navigate(context, null)))
                        .then(CommandManager.argument("card", StringArgumentType.word())
                                .executes(guarded(context -> navigate(context,
                                        StringArgumentType.getString(context, "card")))))));
    }

    private static int start(CommandContext<ServerCommandSource> context, int players) {
        ServerWorld world = context.getSource().getWorld();
        GameComponent component = GameComponents.of(world);
        if (component.session().isPresent()) {
            context.getSource().sendError(Text.translatable("heavyseas.command.already_running"));
            return 0;
        }
        List<ServerPlayerEntity> humans = new ArrayList<>(world.getServer().getPlayerManager().getPlayerList());
        try {
            GameFlow.start(world, players, humans, component.pendingDummies());
        } catch (RuntimeException e) {
            context.getSource().sendError(Text.literal(String.valueOf(e.getMessage())));
            return 0;
        }
        return 1;
    }

    private static int end(CommandContext<ServerCommandSource> context) {
        GameComponent component = GameComponents.of(context.getSource().getWorld());
        if (component.session().isEmpty()) {
            context.getSource().sendError(Text.translatable("heavyseas.command.no_game"));
            return 0;
        }
        component.end();
        GameComponents.sync(context.getSource().getWorld());
        context.getSource().sendFeedback(() -> Text.translatable("heavyseas.command.ended"), true);
        return 1;
    }

    private static int dummyAdd(CommandContext<ServerCommandSource> context) {
        GameComponent component = GameComponents.of(context.getSource().getWorld());
        if (component.session().isPresent()) {
            context.getSource().sendError(Text.translatable("heavyseas.command.already_running"));
            return 0;
        }
        String raw = StringArgumentType.getString(context, "character");
        boolean known = GameDataLoader.require().roster().characters().stream()
                .anyMatch(s -> s.id().value().equals(raw));
        if (!known) {
            context.getSource().sendError(Text.translatable("heavyseas.command.unknown_character", raw));
            return 0;
        }
        CharacterId id = CharacterId.of(raw);
        if (!component.reserveForDummy(id)) {
            context.getSource().sendError(Text.translatable("heavyseas.command.dummy_exists", raw));
            return 0;
        }
        context.getSource().sendFeedback(
                () -> Text.translatable("heavyseas.command.dummy_added", GameFlow.characterName(id),
                        component.pendingDummies().size()), true);
        return 1;
    }

    /**
     * 替身自动推进的开关（ADR-0019）。不带参数时只报当前值。
     *
     * <p>对局中途切换时，从下一次「轮到谁」起生效：已经排下的那一步执行前会自己再看一眼开关。
     * 那一行日志是 {@code playthrough-check.sh} 分开两局的界线，别改措辞。
     */
    private static int dummyAuto(CommandContext<ServerCommandSource> context, Boolean on) {
        GameComponent component = GameComponents.of(context.getSource().getWorld());
        if (on != null) {
            component.setDummyAutoplay(on);
            LOGGER.info("替身自动推进：{}", on ? "开" : "关");
        }
        boolean now = component.dummyAutoplay();
        context.getSource().sendFeedback(() -> Text.translatable("heavyseas.command.autoplay",
                Text.translatable(now ? "heavyseas.command.autoplay_on" : "heavyseas.command.autoplay_off")),
                on != null);
        return 1;
    }

    private static int status(CommandContext<ServerCommandSource> context) {
        GameComponent component = GameComponents.of(context.getSource().getWorld());
        if (component.session().isEmpty()) {
            int interrupted = component.interruptedTurn();
            context.getSource().sendFeedback(() -> interrupted > 0
                    ? Text.translatable("heavyseas.command.interrupted", interrupted)
                    : Text.translatable("heavyseas.command.no_game"), false);
            return 0;
        }
        GameFlow.statusLines(component).forEach(line -> context.getSource().sendFeedback(() -> line, false));
        return 1;
    }

    private static int pass(CommandContext<ServerCommandSource> context) {
        return act(context, "PASS", (world, component, actor) -> {
            context.getSource().sendFeedback(
                    () -> Text.translatable("heavyseas.command.passed", GameFlow.characterName(actor)), true);
            return true;
        });
    }

    private static int row(CommandContext<ServerCommandSource> context, boolean keepFirst, boolean keepSecond) {
        return act(context, "ROW", (world, component, actor) -> {
            Session session = component.requireSession();
            // 一次走完两步：指令没有「想一想」这回事。底下与划船一面是同一份规则（Session#row 就是那两步）。
            List<NavigationCard> drawn = ActionPhase.rowKeeping(session, actor, keepFirst, keepSecond);
            context.getSource().sendFeedback(
                    () -> Text.translatable("heavyseas.command.rowed", GameFlow.characterName(actor), drawn.size()),
                    true);
            return true;
        });
    }

    private static int swap(CommandContext<ServerCommandSource> context) {
        return act(context, "SWAP", (world, component, actor) -> {
            Session session = component.requireSession();
            Optional<CharacterId> target = resolve(context, session);
            if (target.isEmpty()) {
                return false;
            }
            if (target.get().equals(actor)) {
                context.getSource().sendError(Text.translatable("heavyseas.command.swap_self"));
                return false;
            }
            session.swapSeats(actor, target.get());
            context.getSource().sendFeedback(() -> Text.translatable("heavyseas.command.swapped",
                    GameFlow.characterName(actor), GameFlow.characterName(target.get())), true);
            return true;
        });
    }

    private static int fight(CommandContext<ServerCommandSource> context) {
        return act(context, "FIGHT", (world, component, actor) -> {
            Session session = component.requireSession();
            Optional<CharacterId> target = resolve(context, session);
            if (target.isEmpty()) {
                return false;
            }
            // ❗只有**清醒**的人才能拒绝，所以昏迷与死亡者打不起来（可以被随意搜刮）。
            if (!session.fightTargets(actor).contains(target.get())) {
                context.getSource().sendError(Text.translatable("heavyseas.command.cannot_fight",
                        GameFlow.characterName(target.get())));
                return false;
            }
            Fight.Outcome outcome = session.applyFight(Fight.between(actor, target.get()));
            context.getSource().sendFeedback(() -> Text.translatable("heavyseas.command.fought",
                    GameFlow.characterName(actor), GameFlow.characterName(target.get()),
                    outcome.damagePerLoser()), true);
            return true;
        });
    }

    /**
     * 舵手挑牌窗口里提前定（dev）。
     *
     * <p>❗航海阶段已经不等这条指令了（ADR-0019）：没人划船时当场翻顶牌，替身开着自动推进时当场挑第一张，
     * 其余情况开 12 秒窗口、超时认高亮。这条指令只在窗口开着时有用。
     */
    private static int navigate(CommandContext<ServerCommandSource> context, String cardId) {
        ServerWorld world = context.getSource().getWorld();
        GameComponent component = GameComponents.of(world);
        if (component.session().isEmpty()) {
            context.getSource().sendError(Text.translatable("heavyseas.command.no_game"));
            return 0;
        }
        Session session = component.requireSession();
        if (session.state().phase() != Phase.NAVIGATION) {
            context.getSource().sendError(Text.translatable("heavyseas.command.not_navigation"));
            return 0;
        }
        if (component.helmDeadline() <= 0) {
            context.getSource().sendError(Text.translatable("heavyseas.command.no_pick_window"));
            return 0;
        }
        List<NavigationCard> stack = session.table().rowStack();
        if (cardId == null) {
            context.getSource().sendError(Text.translatable("heavyseas.command.pick_needed",
                    String.join("、", stack.stream().map(NavigationCard::id).toList())));
            return 0;
        }
        for (int i = 0; i < stack.size(); i++) {
            if (stack.get(i).id().equals(cardId)) {
                NavigationPhase.pickByCommand(world, component, i);
                return 1;
            }
        }
        context.getSource().sendError(Text.translatable("heavyseas.command.not_in_row_stack", cardId));
        return 0;
    }

    private static Optional<CharacterId> resolve(CommandContext<ServerCommandSource> context, Session session) {
        String raw = StringArgumentType.getString(context, "character");
        Optional<CharacterId> found = session.state().bySeat().stream()
                .filter(id -> id.value().equals(raw)).findFirst();
        if (found.isEmpty()) {
            context.getSource().sendError(Text.translatable("heavyseas.command.unknown_character", raw));
        }
        return found;
    }

    /** 物资 id 的补全：只补这一局里真的在谁手上/面前的牌。补出一张没人有的牌毫无用处。 */
    private static final SuggestionProvider<ServerCommandSource> HELD_CARDS = (context, builder) -> {
        GameComponent component = GameComponents.of(context.getSource().getWorld());
        component.session().ifPresent(session -> session.state().bySeat().forEach(id -> {
            session.state().stateOf(id).hand().forEach(builder::suggest);
            session.state().stateOf(id).front().forEach(builder::suggest);
        }));
        return builder.buildFuture();
    };

    // ------------------------------------------------------------------ 物资（ADR-0021）

    /** 亮出：不占行动、不可逆。规则上任何时候都可以，所以这里不挑阶段。 */
    private static int reveal(CommandContext<ServerCommandSource> context) {
        return onSeat(context, "REVEAL", (world, component, who) -> {
            String card = StringArgumentType.getString(context, "card");
            component.requireSession().reveal(who, card);
            GameComponents.sync(world);
            context.getSource().sendFeedback(() -> Text.translatable("heavyseas.command.revealed",
                    GameFlow.characterName(who), provisionName(card)), true);
            return true;
        });
    }

    /** 送一张给别人。只在行动阶段（规则 §5.2），由引擎把关。 */
    private static int give(CommandContext<ServerCommandSource> context) {
        return onSeat(context, "GIVE", (world, component, who) -> {
            String card = StringArgumentType.getString(context, "card");
            Optional<CharacterId> to = named(context, component.requireSession(), "to");
            if (to.isEmpty()) {
                return false;
            }
            component.requireSession().giveCard(who, to.get(), card);
            GameComponents.sync(world);
            context.getSource().sendFeedback(() -> Text.translatable("heavyseas.command.gave",
                    GameFlow.characterName(who), GameFlow.characterName(to.get()), provisionName(card)), true);
            return true;
        });
    }

    /** 喝一口酒：不占行动，每回合一次。 */
    private static int drink(CommandContext<ServerCommandSource> context) {
        return onSeat(context, "DRINK", (world, component, who) -> {
            String card = StringArgumentType.getString(context, "card");
            component.requireSession().drinkRum(who, card);
            GameComponents.sync(world);
            context.getSource().sendFeedback(() -> Text.translatable("heavyseas.command.drank",
                    GameFlow.characterName(who), provisionName(card)), true);
            return true;
        });
    }

    /**
     * 特殊行动：<b>占掉这一个行动</b>，所以走 {@link #act} 那层外壳（认人、做事、推进）。
     *
     * <p>四张：医疗箱 · 撑伞 · 信号枪当信号 · 绝境。哪张属于哪种由效果决定，不在这里写一张表。
     */
    private static int use(CommandContext<ServerCommandSource> context, String targetId) {
        return act(context, "USE", (world, component, actor) -> {
            Session session = component.requireSession();
            String card = StringArgumentType.getString(context, "card");
            if (!session.provisions().get(card).isSpecialAction()) {
                context.getSource().sendError(
                        Text.translatable("heavyseas.command.not_special", provisionName(card)));
                return false;
            }
            ProvisionEffect effect = session.provisions().get(card).effect();
            if (effect instanceof ProvisionEffect.Heal) {
                // 不指定目标时治「伤得最重、而且还没死」的那个 —— 医疗箱主要就是用来救醒昏迷者的。
                // ❗这是<b>指令层的便利</b>，不是规则：规则里目标由打牌的人指定（界面那条路将来要给他挑）。
                CharacterId target = targetId == null ? mostWounded(session) : CharacterId.of(targetId);
                if (target == null) {
                    context.getSource().sendError(Text.translatable("heavyseas.command.nobody_wounded"));
                    return false;
                }
                session.useMedicalKit(actor, target, card);
                context.getSource().sendFeedback(() -> Text.translatable("heavyseas.command.healed",
                        GameFlow.characterName(actor), GameFlow.characterName(target)), true);
            } else if (effect instanceof ProvisionEffect.PreventThirst) {
                session.openParasol(actor, card);
                context.getSource().sendFeedback(() -> Text.translatable("heavyseas.command.opened",
                        GameFlow.characterName(actor), provisionName(card)), true);
            } else if (effect instanceof ProvisionEffect.HealAll) {
                List<CharacterId> healed = session.useRation(actor, card);
                context.getSource().sendFeedback(() -> Text.translatable("heavyseas.command.rationed",
                        GameFlow.characterName(actor), healed.size()), true);
            } else {
                int before = session.state().gulls();
                session.fireSignal(actor, card);
                context.getSource().sendFeedback(() -> Text.translatable("heavyseas.command.signalled",
                        GameFlow.characterName(actor), session.state().gulls() - before), true);
            }
            return true;
        });
    }

    /**
     * <b>夹具</b>（2 级权限）：从牌堆里取一张指定的牌发给某人。
     *
     * <p>与 {@code dummy add} 同一族 —— 它们都不是规则，是「不摆好局面就验不了」的那种东西。
     * 口渴那一面要有人既渴着又手里有水，等它自己出现的验收脚本一定会时灵时不灵。
     * 牌真的从牌堆里少一张，对账照样成立。
     */
    private static int grant(CommandContext<ServerCommandSource> context) {
        ServerWorld world = context.getSource().getWorld();
        GameComponent component = GameComponents.of(world);
        if (component.session().isEmpty()) {
            context.getSource().sendError(Text.translatable("heavyseas.command.no_game"));
            return 0;
        }
        Session session = component.requireSession();
        Optional<CharacterId> who = named(context, session, "character");
        if (who.isEmpty()) {
            return 0;
        }
        String card = StringArgumentType.getString(context, "card");
        session.dealFromPile(who.get(), card);
        GameComponents.sync(world);
        LOGGER.info("夹具：{} 从牌堆里拿到 {}", who.get().value(), card);
        context.getSource().sendFeedback(() -> Text.translatable("heavyseas.command.granted",
                GameFlow.characterName(who.get()), provisionName(card)), true);
        return 1;
    }

    /** 伤得最重、而且还没死的那个；全场都没受伤时返回 null。 */
    private static CharacterId mostWounded(Session session) {
        CharacterId worst = null;
        int worstDamage = 0;
        for (CharacterId id : session.state().bySeat()) {
            if (session.state().conditionOf(id) == io.github.heavyseasmc.engine.state.Condition.DEAD) {
                continue;
            }
            int damage = session.state().stateOf(id).damage();
            if (damage > worstDamage) {
                worst = id;
                worstDamage = damage;
            }
        }
        return worst;
    }

    /** 口渴窗口里替自己定：喝几张。 */
    private static int water(CommandContext<ServerCommandSource> context, int cups) {
        ServerWorld world = context.getSource().getWorld();
        GameComponent component = GameComponents.of(world);
        if (!thirstWindowOpen(context, component)) {
            return 0;
        }
        ThirstPhase.chooseByCommand(world, component, cups);
        return 1;
    }

    /** 别人替他打一张水（规则 §5.2 的例外）。❗<b>昏迷者唯一的水源。</b> */
    private static int waterFrom(CommandContext<ServerCommandSource> context) {
        ServerWorld world = context.getSource().getWorld();
        GameComponent component = GameComponents.of(world);
        if (!thirstWindowOpen(context, component)) {
            return 0;
        }
        Optional<CharacterId> donor = named(context, component.requireSession(), "character");
        if (donor.isEmpty()) {
            return 0;
        }
        return ThirstPhase.donateByCommand(world, component, donor.get()) ? 1 : 0;
    }

    private static boolean thirstWindowOpen(CommandContext<ServerCommandSource> context,
                                            GameComponent component) {
        if (component.session().isEmpty()) {
            context.getSource().sendError(Text.translatable("heavyseas.command.no_game"));
            return false;
        }
        if (component.requireSession().thirstPending().isEmpty()) {
            context.getSource().sendError(Text.translatable("heavyseas.command.no_thirst_window"));
            return false;
        }
        return true;
    }

    /** 显示名走 lang 键，与卡面同一套（O17：文案从卡面提取，不另写一遍）。 */
    private static Text provisionName(String cardId) {
        return Text.translatable("heavyseas.provision." + cardId);
    }

    /**
     * 「这件事由某个座位做」的共同外壳：认人、做事、<b>不推进阶段</b>。
     *
     * <p>❗与 {@link #act} 的差别只有一处，而那一处是规则：亮出 · 赠送 · 喝酒<b>都不占行动</b>
     * （规则 §5.2 与 §11.2）。走 {@code act} 的话它们会白白吃掉一个行动，
     * 而表现只是「怎么轮到下一个人了」—— 不报错。
     */
    private static int onSeat(CommandContext<ServerCommandSource> context, String what, Action action) {
        ServerCommandSource source = context.getSource();
        ServerWorld world = source.getWorld();
        GameComponent component = GameComponents.of(world);
        if (component.session().isEmpty()) {
            source.sendError(Text.translatable("heavyseas.command.no_game"));
            return 0;
        }
        Optional<CharacterId> who = named(context, component.requireSession(), "character");
        if (who.isEmpty()) {
            return 0;
        }
        GameComponent.Occupant occupant = component.occupantOf(who.get()).orElseThrow();
        ServerPlayerEntity player = source.getPlayer();
        // ❗2 级权限（控制台 / op）可以驱动任何座位，包括真人的。这几条是**开发脚手架**（ADR-0017：
        //   玩家侧指令作废，GUI 是唯一路径），而验收脚本要从控制台摆局面 —— 比如「让他手里有水」。
        boolean allowed = source.hasPermissionLevel(DEV_PERMISSION)
                || (!occupant.isDummy() && player != null && occupant.player().equals(player.getUuid()));
        if (!allowed) {
            source.sendError(Text.translatable("heavyseas.command.not_your_seat",
                    GameFlow.characterName(who.get())));
            return 0;
        }
        if (!action.run(world, component, who.get())) {
            return 0;
        }
        LOGGER.info("物资（指令）：{} {}", who.get().value(), what);
        return 1;
    }

    /** 取一个角色参数并核对它在这一局里。 */
    private static Optional<CharacterId> named(CommandContext<ServerCommandSource> context,
                                               Session session, String argument) {
        String raw = StringArgumentType.getString(context, argument);
        Optional<CharacterId> found = session.state().bySeat().stream()
                .filter(id -> id.value().equals(raw)).findFirst();
        if (found.isEmpty()) {
            context.getSource().sendError(Text.translatable("heavyseas.command.unknown_character", raw));
        }
        return found;
    }

    /**
     * 行动阶段的共同外壳：确认轮到谁、确认下指令的人有资格、做事、然后推进。
     *
     * <p>把这层抽出来是因为四个动作里有三件事一模一样，而**漏掉「推进」那一步的表现是
     * 游戏卡住不动**，不是报错 —— 那种 bug 每个动作都得重犯一次才发现得了。
     *
     * @param what 与语言无关的动作名，进日志：验收脚本靠它判「指令那条路真的走通了」
     */
    private static int act(CommandContext<ServerCommandSource> context, String what, Action action) {
        ServerCommandSource source = context.getSource();
        ServerWorld world = source.getWorld();
        GameComponent component = GameComponents.of(world);
        if (component.session().isEmpty()) {
            source.sendError(Text.translatable("heavyseas.command.no_game"));
            return 0;
        }
        Session session = component.requireSession();
        if (session.state().phase() != Phase.ACTION) {
            source.sendError(Text.translatable("heavyseas.command.not_action"));
            return 0;
        }
        // ❗有人在划船一面上还没定完：他的行动还没结束，谁都不能插进来（引擎也会抛，这里先给一句人话）。
        Optional<CharacterId> rower = session.rower();
        if (rower.isPresent()) {
            source.sendError(Text.translatable("heavyseas.command.rowing_pending", GameFlow.characterName(rower.get())));
            return 0;
        }
        Optional<CharacterId> actor = session.nextActor();
        if (actor.isEmpty()) {
            source.sendError(Text.translatable("heavyseas.command.nobody_can_act"));
            return 0;
        }
        GameComponent.Occupant occupant = component.occupantOf(actor.get()).orElseThrow();
        ServerPlayerEntity player = source.getPlayer();
        boolean allowed = occupant.isDummy()
                ? source.hasPermissionLevel(DEV_PERMISSION)
                : player != null && occupant.player().equals(player.getUuid());
        if (!allowed) {
            source.sendError(Text.translatable("heavyseas.command.not_your_turn",
                    GameFlow.characterName(actor.get())));
            return 0;
        }
        if (!action.run(world, component, actor.get())) {
            return 0;                        // 动作自己报过错了，不推进
        }
        LOGGER.info("行动（指令）：{} {}", actor.get().value(), what);
        GameFlow.finishAction(world, component, actor.get());
        return 1;
    }

    @FunctionalInterface
    private interface Action {
        /** @return 真的做成了才推进阶段；返回 false 表示已经报过错。 */
        boolean run(ServerWorld world, GameComponent component, CharacterId actor);
    }
}
