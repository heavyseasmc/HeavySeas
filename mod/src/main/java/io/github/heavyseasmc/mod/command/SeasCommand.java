package io.github.heavyseasmc.mod.command;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.engine.weather.WeatherCard;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.data.GameDataLoader;
import io.github.heavyseasmc.mod.data.SceneDataLoader;
import io.github.heavyseasmc.mod.game.ActionPhase;
import io.github.heavyseasmc.mod.game.ContestPhase;
import io.github.heavyseasmc.mod.game.DesignationPhase;
import io.github.heavyseasmc.mod.game.GameFlow;
import io.github.heavyseasmc.mod.game.NavigationPhase;
import io.github.heavyseasmc.mod.game.ThirstPhase;
import io.github.heavyseasmc.mod.net.RosterConfigS2C;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.world.Backdrop;
import io.github.heavyseasmc.mod.world.Nameplates;
import io.github.heavyseasmc.mod.world.MistSea;
import io.github.heavyseasmc.mod.world.Seats;
import io.github.heavyseasmc.mod.world.Gulls;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
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
        GameComponent component = GameComponents.of(gameWorld(context));
        component.session().ifPresentOrElse(
                session -> session.state().bySeat().forEach(id -> builder.suggest(id.value())),
                () -> GameDataLoader.require().roster().characters()
                        .forEach(s -> builder.suggest(s.id().value())));
        return builder.buildFuture();
    };

    /** 天候 id 直接来自本次加载的数据，不在命令里维护第二张白名单。 */
    private static final SuggestionProvider<ServerCommandSource> WEATHERS = (context, builder) -> {
        GameDataLoader.require().weather().forEach(card -> builder.suggest(card.id()));
        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        register(dispatcher, FabricLoader.getInstance().isDevelopmentEnvironment());
    }

    /** 这个重载只为机械证明生产命令树不含 {@code /seas dev}。 */
    static void register(CommandDispatcher<ServerCommandSource> dispatcher, boolean developmentEnvironment) {
        dispatcher.register(CommandManager.literal("seas")
                .then(CommandManager.literal("start")
                        .requires(source -> source.hasPermissionLevel(DEV_PERMISSION))
                        .executes(guarded(context -> start(context, 6, SceneDataLoader.DEFAULT)))
                        .then(CommandManager.argument("players", IntegerArgumentType.integer(6, 8))
                                .executes(guarded(context -> start(context,
                                        IntegerArgumentType.getInteger(context, "players"), SceneDataLoader.DEFAULT)))
                                // 用哪份航程布局（ADR-0034 §5.5）：换地图演练与非官方地图走这个口，缺省官方布局。
                                .then(CommandManager.argument("layout", IdentifierArgumentType.identifier())
                                        .suggests(LAYOUTS)
                                        .executes(guarded(context -> start(context,
                                                IntegerArgumentType.getInteger(context, "players"),
                                                IdentifierArgumentType.getIdentifier(context, "layout")))))))
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
                .then(CommandManager.literal("steal")
                        .then(CommandManager.argument("character", StringArgumentType.word())
                                .suggests(CHARACTERS)
                                .executes(guarded(SeasCommand::steal))))
                .then(CommandManager.literal("fight")
                        .then(CommandManager.argument("character", StringArgumentType.word())
                                .suggests(CHARACTERS)
                                .executes(guarded(SeasCommand::fight))))
                // 这一场进行中的几下（ADR-0023）。真人那条路要等四面 GUI，所以先只给 dev。
                .then(CommandManager.literal("consent")
                        .requires(source -> source.hasPermissionLevel(DEV_PERMISSION))
                        .then(CommandManager.literal("agree").executes(guarded(context -> consent(context, false))))
                        .then(CommandManager.literal("fight").executes(guarded(context -> consent(context, true)))))
                .then(CommandManager.literal("join")
                        .requires(source -> source.hasPermissionLevel(DEV_PERMISSION))
                        .then(CommandManager.argument("character", StringArgumentType.word())
                                .suggests(CHARACTERS)
                                .then(CommandManager.literal("attack")
                                        .executes(guarded(context -> join(context, Fight.Side.ATTACK))))
                                .then(CommandManager.literal("defend")
                                        .executes(guarded(context -> join(context, Fight.Side.DEFEND))))))
                .then(CommandManager.literal("stances")
                        .requires(source -> source.hasPermissionLevel(DEV_PERMISSION))
                        .executes(guarded(SeasCommand::stances)))
                .then(CommandManager.literal("weapon")
                        .requires(source -> source.hasPermissionLevel(DEV_PERMISSION))
                        .then(CommandManager.argument("character", StringArgumentType.word())
                                .suggests(CHARACTERS)
                                .then(CommandManager.argument("card", StringArgumentType.word())
                                        .suggests(HELD_CARDS)
                                        .executes(guarded(SeasCommand::weapon)))))
                .then(CommandManager.literal("strike")
                        .requires(source -> source.hasPermissionLevel(DEV_PERMISSION))
                        .executes(guarded(SeasCommand::strike)))
                .then(CommandManager.literal("pick")
                        .requires(source -> source.hasPermissionLevel(DEV_PERMISSION))
                        .executes(guarded(context -> pick(context, null)))
                        .then(CommandManager.argument("card", StringArgumentType.word())
                                .suggests(HELD_CARDS)
                                .executes(guarded(context -> pick(context,
                                        StringArgumentType.getString(context, "card"))))))
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
                .then(CommandManager.literal("land")
                        .requires(source -> source.hasPermissionLevel(DEV_PERMISSION))
                        .executes(guarded(SeasCommand::land)))
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
        if (developmentEnvironment) {
            dispatcher.register(CommandManager.literal("seas")
                    .then(CommandManager.literal("dev")
                            .requires(source -> source.hasPermissionLevel(DEV_PERMISSION))
                            .then(CommandManager.literal("roster")
                                    .executes(guarded(context -> devRoster(context, 6)))
                                    .then(CommandManager.argument("players", IntegerArgumentType.integer(6, 8))
                                            .executes(guarded(context -> devRoster(context,
                                                    IntegerArgumentType.getInteger(context, "players"))))))
                            .then(CommandManager.literal("weather")
                                    .then(CommandManager.argument("id", StringArgumentType.word())
                                            .suggests(WEATHERS)
                                            .executes(guarded(SeasCommand::devWeather))))));
        }
    }

    /** 用一个真实连接收到正式阵容包；确认按钮仍会走生产 {@code StartVoyageC2S} 校验。 */
    private static int devRoster(CommandContext<ServerCommandSource> context, int players) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendError(Text.literal("/seas dev roster 必须由游戏客户端玩家执行"));
            return 0;
        }
        RosterConfigS2C packet = RosterConfigS2C.from(player.getBlockPos().asLong(), players,
                GameDataLoader.require().roster());
        ServerPlayNetworking.send(player, packet);
        LOGGER.info("开发验收：向 {} 打开 {} 人阵容面板", player.getGameProfile().getName(), players);
        return 1;
    }

    /**
     * 把当前天候临时覆盖成指定数据牌，只用于客户端截图/断言。
     * 下一次正式抽天候时覆盖自动消失，牌堆、弃牌堆与随机次序均不改变。
     */
    private static int devWeather(CommandContext<ServerCommandSource> context) {
        ServerWorld world = gameWorld(context);
        GameComponent component = GameComponents.of(world);
        if (component.session().isEmpty()) {
            context.getSource().sendError(Text.translatable("heavyseas.command.no_game"));
            return 0;
        }
        String raw = StringArgumentType.getString(context, "id");
        WeatherCard weather = GameDataLoader.require().weather().stream()
                .filter(card -> card.id().equals(raw))
                .findFirst().orElse(null);
        if (weather == null) {
            context.getSource().sendError(Text.literal("未知天候：" + raw));
            return 0;
        }
        component.requireSession().table().weather()
                .orElseThrow(() -> new IllegalStateException("当前对局没有天候牌堆"))
                .overrideCurrentUntilNextDraw(weather);
        // 雨 · 雷 · 时刻也跟着换，否则截图里只有雾变了、天没变（ADR-0034 §5.1.5）。
        MistSea.applyWeather(world, component, weather.id());
        component.notify(Text.translatable("heavyseas.game.weather",
                Text.translatable("heavyseas.weather." + weather.id()),
                Text.translatable("heavyseas.weather.effect." + weather.id()))
                .formatted(Formatting.AQUA));
        GameComponents.sync(world);
        LOGGER.info("开发验收：天候临时覆盖为 {}（{}）", weather.id(), weather.effect().id());
        return 1;
    }

    /** 已加载的布局 id：从场景数据现取，不在命令里维护第二张表。 */
    private static final SuggestionProvider<ServerCommandSource> LAYOUTS = (context, builder) -> {
        SceneDataLoader.ids().forEach(id -> builder.suggest(id.toString()));
        return builder.buildFuture();
    };

    private static int start(CommandContext<ServerCommandSource> context, int players,
                             net.minecraft.util.Identifier layoutId) {
        ServerWorld lobby = context.getSource().getWorld();
        GameComponent lobbyComponent = GameComponents.of(lobby);
        ServerWorld sea = MistSea.world(lobby.getServer());
        if (sea == null) {
            context.getSource().sendError(Text.literal("雾海维度未加载（布局还没读到，或维度不在）"));
            return 0;
        }
        if (GameComponents.of(sea).session().isPresent()) {
            context.getSource().sendError(Text.translatable("heavyseas.command.already_running"));
            return 0;
        }
        List<ServerPlayerEntity> humans = new ArrayList<>(lobby.getServer().getPlayerManager().getPlayerList());
        try {
            MistSea.startVoyage(lobby.getServer(), players, humans, lobbyComponent.pendingDummies(), null, layoutId);
            lobbyComponent.clearPendingDummies();
        } catch (RuntimeException e) {
            context.getSource().sendError(Text.literal(String.valueOf(e.getMessage())));
            return 0;
        }
        return 1;
    }

    private static int end(CommandContext<ServerCommandSource> context) {
        ServerWorld world = gameWorld(context);
        GameComponent component = GameComponents.of(world);
        if (component.session().isEmpty()) {
            context.getSource().sendError(Text.translatable("heavyseas.command.no_game"));
            return 0;
        }
        DesignationPhase.clear(world, component);
        Nameplates.clear(world);
        Gulls.clear(world, component);
        Backdrop.clear(world, component);
        Seats.clear(world, component);
        component.end();
        GameComponents.sync(world);
        MistSea.restoreAll(world, component);
        context.getSource().sendFeedback(() -> Text.translatable("heavyseas.command.ended"), true);
        return 1;
    }

    private static int dummyAdd(CommandContext<ServerCommandSource> context) {
        GameComponent component = GameComponents.of(gameWorld(context));
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
        // M4 以后对局固定在雾海。两局之间雾海没有 session，gameWorld() 会退回主世界；
        // 若把开关写到主世界，下一局读到的仍是雾海组件默认值 true，出口验收的 B 局就会假装关着、
        // 实际仍由替身自动走。这个 dev 配置控制的是下一局，始终写到实际承载对局的世界。
        ServerWorld sea = MistSea.world(context.getSource().getServer());
        GameComponent component = GameComponents.of(sea != null ? sea : context.getSource().getWorld());
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
        GameComponent component = GameComponents.of(gameWorld(context));
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

    /**
     * 换座位：**宣告**，不是当场换（ADR-0023）。
     *
     * <p>❗<b>这条指令的语义在第四刀变了</b>：目标清醒时要由他表态（12 秒，超时算同意），
     * 拒绝就进站队与挂武器两段。行动的收尾也因此挪到了这一场结束的那一刻 ——
     * 所以它走的是不自己收尾的那条外壳，由 {@link ContestPhase} 负责 {@code finishAction}。
     */
    private static int swap(CommandContext<ServerCommandSource> context) {
        return declare(context, "SWAP", Contest.Kind.SWAP);
    }

    /** 抢夺：同样是宣告。小孩的偷窃不问也打不起来，引擎直接把它带进挑牌。 */
    private static int steal(CommandContext<ServerCommandSource> context) {
        return declare(context, "STEAL", Contest.Kind.STEAL);
    }

    private static int declare(CommandContext<ServerCommandSource> context, String what, Contest.Kind kind) {
        return act(context, what, (world, component, actor) -> {
            Session session = component.requireSession();
            Optional<CharacterId> target = resolve(context, session);
            if (target.isEmpty()) {
                return false;
            }
            if (target.get().equals(actor)) {
                context.getSource().sendError(Text.translatable("heavyseas.command.swap_self"));
                return false;
            }
            if (session.state().isRemoved(target.get())) {
                // 引擎也会拒绝，这里先给一句人话：他连座位牌一起被海水带走了。
                context.getSource().sendError(Text.translatable("heavyseas.command.removed",
                        GameFlow.characterName(target.get())));
                return false;
            }
            ContestPhase.declare(world, component, actor, kind, target.get());
            return true;
        }, false);
    }

    /**
     * 打一架（dev 捷径）：宣告换座位，并且替目标喊「战斗」。
     *
     * <p>规则上战斗只能从拒绝里来（规则 §9.1），所以这条指令<b>走的是同一条流程</b> ——
     * 它省掉的只是「问目标」那一步。出口验收的 B 局靠它把两段式真的跑一遍。
     */
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
            ContestPhase.declare(world, component, actor, Contest.Kind.SWAP, target.get());
            if (session.contest().map(c -> c.stage() == Contest.Stage.CONSENT).orElse(false)) {
                ContestPhase.consent(world, component, true);
            }
            return true;
        }, false);
    }

    /** 表态（dev）：被指定的那个人同意，还是喊战斗。 */
    private static int consent(CommandContext<ServerCommandSource> context, boolean fight) {
        return inContest(context, Contest.Stage.CONSENT, (world, component) -> {
            ContestPhase.consent(world, component, fight);
            return true;
        });
    }

    /**
     * 站队（dev）：清醒的人加入任意一边，加入之后不能反悔。
     *
     * <p>❗<b>先问清醒、先问在不在场上，再交给引擎</b>：引擎对这两种都抛，而抛出来就是一句堆栈 ——
     * 出口验收里那条 `must_not "/seas 出错"` 会当场红。「拒绝了你」与「有 bug」必须在日志上分得开。
     */
    private static int join(CommandContext<ServerCommandSource> context, Fight.Side side) {
        return inContest(context, Contest.Stage.STANCES, (world, component) -> {
            Session session = component.requireSession();
            Optional<CharacterId> who = named(context, session, "character");
            if (who.isEmpty()) {
                return false;
            }
            if (session.state().isRemoved(who.get()) || !session.state().conditionOf(who.get()).canAct()) {
                context.getSource().sendError(Text.translatable("heavyseas.command.cannot_join",
                        GameFlow.characterName(who.get())));
                return false;
            }
            if (session.contest().orElseThrow().fight().orElseThrow().combatants().contains(who.get())) {
                context.getSource().sendError(Text.translatable("heavyseas.command.already_fighting",
                        GameFlow.characterName(who.get())));
                return false;
            }
            ContestPhase.join(world, component, who.get(), side);
            return true;
        });
    }

    /** 站队段提前结束（dev）：不等那 15 秒。 */
    private static int stances(CommandContext<ServerCommandSource> context) {
        return inContest(context, Contest.Stage.STANCES, (world, component) -> {
            ContestPhase.closeStances(world, component);
            return true;
        });
    }

    /**
     * 挂武器（dev）：只有参战者能押，而且只能押手上或面前真有的那几张。
     *
     * <p>❗同样先问清楚再交给引擎（理由见 {@link #join}）：没参战、不是武器、押多了，引擎三种都抛。
     */
    private static int weapon(CommandContext<ServerCommandSource> context) {
        return inContest(context, Contest.Stage.WEAPONS, (world, component) -> {
            Session session = component.requireSession();
            Optional<CharacterId> who = named(context, session, "character");
            if (who.isEmpty()) {
                return false;
            }
            Contest contest = session.contest().orElseThrow();
            String card = StringArgumentType.getString(context, "card");
            if (!contest.fight().orElseThrow().combatants().contains(who.get())) {
                context.getSource().sendError(Text.translatable("heavyseas.command.not_fighting",
                        GameFlow.characterName(who.get())));
                return false;
            }
            // ❗判据只有一份（{@link ContestPhase#canCommitWeapon}）：界面那条路走的也是它。
            if (!ContestPhase.canCommitWeapon(session, contest, who.get(), card)) {
                context.getSource().sendError(Text.translatable("heavyseas.command.no_such_weapon",
                        GameFlow.characterName(who.get()), provisionName(card)));
                return false;
            }
            ContestPhase.commitWeapon(world, component, who.get(), card);
            return true;
        });
    }

    /** 结算（dev）：不等挂武器段的倒计时，现在就打。 */
    private static int strike(CommandContext<ServerCommandSource> context) {
        return inContest(context, Contest.Stage.WEAPONS, (world, component) -> {
            ContestPhase.resolve(world, component);
            return true;
        });
    }

    /** 挑牌（dev）：给牌名就挑面前那一张，不给就按手牌随机一张。 */
    private static int pick(CommandContext<ServerCommandSource> context, String cardId) {
        return inContest(context, Contest.Stage.PICK, (world, component) -> {
            if (cardId == null) {
                ContestPhase.pickFromHand(world, component);
            } else {
                ContestPhase.pickFromFront(world, component, cardId);
            }
            return true;
        });
    }

    /**
     * 这一场进行中那几下的共同外壳：要有对局、要有这一场、而且要在对的那一段。
     *
     * <p>❗这里<b>不记行动</b>：进攻方的行动在这一场收场的那一刻才结束（规则 §9.1），由 {@link ContestPhase} 收尾。
     *
     * <p>一律 2 级权限：真人自己表态、站队、押武器要走四面 GUI（还没做，见 CURRENT_STATUS），
     * 这几条是**开发脚手架**，与 {@code navigate} 同一条理由。
     */
    private static int inContest(CommandContext<ServerCommandSource> context, Contest.Stage stage,
                                 ContestAction action) {
        ServerCommandSource source = context.getSource();
        ServerWorld world = gameWorld(context);
        GameComponent component = GameComponents.of(world);
        if (component.session().isEmpty()) {
            source.sendError(Text.translatable("heavyseas.command.no_game"));
            return 0;
        }
        Optional<Contest> contest = component.requireSession().contest();
        if (contest.isEmpty() || contest.get().stage() != stage) {
            source.sendError(Text.translatable("heavyseas.command.no_contest"));
            return 0;
        }
        return action.run(world, component) ? 1 : 0;
    }

    /** Console commands still originate in the overworld after players cross into M4's match dimension. */
    private static ServerWorld gameWorld(CommandContext<ServerCommandSource> context) {
        ServerWorld sea = MistSea.world(context.getSource().getServer());
        if (sea != null && GameComponents.of(sea).session().isPresent()) {
            return sea;
        }
        return context.getSource().getWorld();
    }

    @FunctionalInterface
    private interface ContestAction {
        /** @return 真的做成了吗；false 表示已经报过错。 */
        boolean run(ServerWorld world, GameComponent component);
    }

    /**
     * 舵手挑牌窗口里提前定（dev）。
     *
     * <p>❗航海阶段已经不等这条指令了（ADR-0019）：没人划船时当场翻顶牌，替身开着自动推进时当场挑第一张，
     * 其余情况开 12 秒窗口、超时认高亮。这条指令只在窗口开着时有用。
     */
    private static int navigate(CommandContext<ServerCommandSource> context, String cardId) {
        ServerWorld world = gameWorld(context);
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
        GameComponent component = GameComponents.of(gameWorld(context));
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

    /** <b>夹具</b>（2 级权限）：海鸥直接置满，走正常的终局流程 —— 终局不摆出来就验不了（ADR-0022 §7.7）。 */
    private static int land(CommandContext<ServerCommandSource> context) {
        ServerWorld world = gameWorld(context);
        GameComponent component = GameComponents.of(world);
        if (component.session().isEmpty()) {
            context.getSource().sendError(Text.translatable("heavyseas.command.no_game"));
            return 0;
        }
        if (component.requireSession().state().isOver()) {
            context.getSource().sendError(Text.translatable("heavyseas.command.already_over"));
            return 0;
        }
        GameFlow.landForFixture(world, component);
        return 1;
    }

    /**
     * <b>夹具</b>（2 级权限）：从牌堆里取一张指定的牌发给某人。
     *
     * <p>与 {@code dummy add} 同一族 —— 它们都不是规则，是「不摆好局面就验不了」的那种东西。
     * 口渴那一面要有人既渴着又手里有水，等它自己出现的验收脚本一定会时灵时不灵。
     * 牌真的从牌堆里少一张，对账照样成立。
     */
    private static int grant(CommandContext<ServerCommandSource> context) {
        ServerWorld world = gameWorld(context);
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
        // ❗先问再发：牌堆里没有这张时引擎会抛，而抛出来是一句堆栈 ——「拒绝了你」与「有 bug」
        //   在日志上就分不开了（与 join、weapon 同一条）。喂料脚本正是靠这句礼貌的拒绝
        //   才敢一口气把五种武器都试一遍。
        if (session.table().provisionsLeft(card) <= 0) {
            context.getSource().sendError(Text.translatable("heavyseas.command.pile_empty_of",
                    provisionName(card), session.table().provisionsLeft()));
            return 0;
        }
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
        ServerWorld world = gameWorld(context);
        GameComponent component = GameComponents.of(world);
        if (!thirstWindowOpen(context, component)) {
            return 0;
        }
        ThirstPhase.chooseByCommand(world, component, cups);
        return 1;
    }

    /** 别人替他打一张水（规则 §5.2 的例外）。❗<b>昏迷者唯一的水源。</b> */
    private static int waterFrom(CommandContext<ServerCommandSource> context) {
        ServerWorld world = gameWorld(context);
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
        ServerWorld world = gameWorld(context);
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
        return act(context, what, action, true);
    }

    /**
     * @param finish 做完就记下行动吗。❗换座位与抢夺是 {@code false}：它们的行动要等这一场收场
     *               （表态 · 站队 · 挂武器可能跨好几秒），由 {@link ContestPhase} 收尾。
     *               这里照旧记 {@code false} 也要打那一行日志 —— 出口验收按它判「指令那条路真的走通了」
     */
    private static int act(CommandContext<ServerCommandSource> context, String what, Action action, boolean finish) {
        ServerCommandSource source = context.getSource();
        ServerWorld world = gameWorld(context);
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
        // ❗这一场还没收场：规则上进行中任何卡不得易手、谁的行动也记不了（ADR-0023 §7.4 · §7.7）。
        //   引擎照样会抛 —— 但抛出来的是一句堆栈，而喂料脚本在 12 秒的表态窗口里补发的那个 pass
        //   恰好会撞上它（实拍到三次）。这里先给一句人话，让「拒绝了你」与「有 bug」在日志上分得开。
        Optional<Contest> pending = session.contest();
        if (pending.isPresent()) {
            source.sendError(Text.translatable("heavyseas.command.contest_pending",
                    GameFlow.characterName(pending.get().attacker()),
                    GameFlow.characterName(pending.get().target())));
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
        if (finish) {
            GameFlow.finishAction(world, component, actor.get());
        }
        return 1;
    }

    @FunctionalInterface
    private interface Action {
        /** @return 真的做成了才推进阶段；返回 false 表示已经报过错。 */
        boolean run(ServerWorld world, GameComponent component, CharacterId actor);
    }
}
