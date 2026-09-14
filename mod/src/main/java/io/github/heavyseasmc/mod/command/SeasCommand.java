package io.github.heavyseasmc.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.data.GameDataLoader;
import io.github.heavyseasmc.mod.game.GameFlow;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code /seas} —— M1 的全部操作入口。
 *
 * <h2>为什么是指令而不是界面</h2>
 * 实物操作与界面是 M3/M4（决策 ①⑥⑦⑨）。M1 的出口只要求「在主世界任意水面跑通完整一局」，
 * 用指令驱动就够，而且**指令能被一个人跑完** —— 配合 dummy，开发期不必凑 6 个真人。
 *
 * <h2>谁能替谁下指令</h2>
 * 真人占的座位只有他本人能动；dummy 占的座位要 2 级权限（它是测试夹具，不该让普通玩家随手替人行动）。
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
                                        .executes(guarded(SeasCommand::dummyAdd)))))
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
                .then(CommandManager.literal("navigate")
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
        return act(context, (world, component, actor) -> {
            context.getSource().sendFeedback(
                    () -> Text.translatable("heavyseas.command.passed", GameFlow.characterName(actor)), true);
            return true;
        });
    }

    private static int row(CommandContext<ServerCommandSource> context, boolean keepFirst, boolean keepSecond) {
        return act(context, (world, component, actor) -> {
            Session session = component.requireSession();
            boolean[] wanted = {keepFirst, keepSecond};
            int[] seen = {0};
            // 决策以参数一次给全：指令是同步的，没法抽一张、等玩家想想、再抽第二张。
            List<NavigationCard> drawn = session.row(actor, (card, state, rower) -> {
                int i = seen[0]++;
                return i < wanted.length && wanted[i];
            });
            context.getSource().sendFeedback(
                    () -> Text.translatable("heavyseas.command.rowed", GameFlow.characterName(actor), drawn.size()),
                    true);
            return true;
        });
    }

    private static int swap(CommandContext<ServerCommandSource> context) {
        return act(context, (world, component, actor) -> {
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
        return act(context, (world, component, actor) -> {
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
        NavigationCard pick = null;
        if (session.helmsmanMayPick()) {
            List<NavigationCard> stack = session.table().rowStack();
            if (cardId == null) {
                context.getSource().sendError(Text.translatable("heavyseas.command.pick_needed",
                        String.join("、", stack.stream().map(NavigationCard::id).toList())));
                return 0;
            }
            pick = stack.stream().filter(c -> c.id().equals(cardId)).findFirst().orElse(null);
            if (pick == null) {
                context.getSource().sendError(Text.translatable("heavyseas.command.not_in_row_stack", cardId));
                return 0;
            }
        }
        GameFlow.navigate(world, component, pick);
        return 1;
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

    /**
     * 行动阶段的共同外壳：确认轮到谁、确认下指令的人有资格、做事、然后推进。
     *
     * <p>把这层抽出来是因为四个动作里有三件事一模一样，而**漏掉「推进」那一步的表现是
     * 游戏卡住不动**，不是报错 —— 那种 bug 每个动作都得重犯一次才发现得了。
     */
    private static int act(CommandContext<ServerCommandSource> context, Action action) {
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
        GameFlow.finishAction(world, component, actor.get());
        return 1;
    }

    @FunctionalInterface
    private interface Action {
        /** @return 真的做成了才推进阶段；返回 false 表示已经报过错。 */
        boolean run(ServerWorld world, GameComponent component, CharacterId actor);
    }
}
