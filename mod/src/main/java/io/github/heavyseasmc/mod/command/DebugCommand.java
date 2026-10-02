package io.github.heavyseasmc.mod.command;

import io.github.heavyseasmc.engine.model.Affinities;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.state.SurvivorState;
import io.github.heavyseasmc.engine.weather.WeatherCard;
import io.github.heavyseasmc.engine.weather.WeatherDeck;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.data.GameDataLoader;
import io.github.heavyseasmc.mod.game.DebugNext;
import io.github.heavyseasmc.mod.game.DebugTrace;
import io.github.heavyseasmc.mod.game.GameFlow;
import io.github.heavyseasmc.mod.net.RosterConfigS2C;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.world.MistSea;
import io.github.heavyseasmc.mod.world.PlayerSky;
import io.github.heavyseasmc.mod.world.skiff.SkiffProps;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * {@code /seas debug …} —— 只有管理员能用的调试指令（ADR-0060）。
 *
 * <h2>几条规矩</h2>
 * <ol>
 *   <li><b>只给管理员，生产服也注册</b>（D1 · D2）。整棵子树每一个节点都自己要 {@link #GAME} 级 ——
 *       不只靠根节点那一道：哪天根节点的门被改松了，这里每一层照样挡着（单测逐个节点核）。
 *       权限分档：对局与观感 2 级 · 动到局外玩家 3 级 · 改地图 4 级（D7）；这一批只有 2 级的。</li>
 *   <li><b>改局面只走引擎的合法入口</b>，照常过自检（{@code Invariants}）。世界上的东西（灯油、帆、天色）引擎不知道，
 *       直接改世界。「复活」与「倒带」故意不做（D5）：那几条自检专门抓真 bug，为调试开后门等于把它们一起关掉。</li>
 *   <li><b>用过就留痕</b>（D3，{@link DebugTrace}）：改了局面的，全船右栏一行「（调试）…」、计分面板盖章、日志一行。</li>
 *   <li><b>这一局里的人不能看底牌</b>（D4）：{@code inspect} 与 {@code dump} 对坐着的、落海旁观的、开局时一起上艇的人一律拒绝；
 *       控制台、RCON、不在这一局里的管理员可以。</li>
 *   <li>先问清楚再交给引擎，拒绝要是一句人话 —— 与 {@link SeasCommand} 同一条：「拒绝了你」与「有 bug」在日志上要分得开。</li>
 * </ol>
 */
public final class DebugCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 2 级：对局与观感（与 Minecraft 自带的 {@code /weather} {@code /time} 同一档）。 */
    static final int GAME = 2;

    private static final DateTimeFormatter DUMP_NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private DebugCommand() {
    }

    private static LiteralArgumentBuilder<ServerCommandSource> lit(String name, int level) {
        return CommandManager.literal(name).requires(source -> source.hasPermissionLevel(level));
    }

    private static <T> RequiredArgumentBuilder<ServerCommandSource, T> arg(String name, ArgumentType<T> type, int level) {
        return CommandManager.argument(name, type).requires(source -> source.hasPermissionLevel(level));
    }

    /** 整棵 {@code debug} 子树。由 {@link SeasCommand#register} 挂到 {@code /seas} 下。 */
    static LiteralArgumentBuilder<ServerCommandSource> tree() {
        return lit("debug", GAME)
                .then(lit("weather", GAME)
                        .then(lit("now", GAME).then(arg("id", StringArgumentType.word(), GAME)
                                .suggests(SeasCommand.WEATHERS).executes(SeasCommand.guarded(DebugCommand::weatherNow))))
                        .then(lit("next", GAME).then(arg("id", StringArgumentType.word(), GAME)
                                .suggests(SeasCommand.WEATHERS).executes(SeasCommand.guarded(DebugCommand::weatherNext)))))
                .then(lit("next", GAME)
                        .executes(SeasCommand.guarded(DebugCommand::nextShow))
                        .then(lit("seed", GAME).then(arg("seed", LongArgumentType.longArg(), GAME)
                                .executes(SeasCommand.guarded(DebugCommand::nextSeed))))
                        .then(lit("weather", GAME).then(arg("id", StringArgumentType.word(), GAME)
                                .suggests(SeasCommand.WEATHERS).executes(SeasCommand.guarded(DebugCommand::nextWeather))))
                        .then(lit("clear", GAME).executes(SeasCommand.guarded(DebugCommand::nextClear))))
                .then(lit("timer", GAME)
                        .then(lit("now", GAME).executes(SeasCommand.guarded(DebugCommand::timerNow))))
                .then(lit("inspect", GAME)
                        .executes(SeasCommand.guarded(context -> inspect(context, null)))
                        .then(arg("character", StringArgumentType.word(), GAME)
                                .suggests(SeasCommand.CHARACTERS)
                                .executes(SeasCommand.guarded(context -> inspect(context,
                                        StringArgumentType.getString(context, "character"))))))
                .then(lit("dump", GAME).executes(SeasCommand.guarded(DebugCommand::dump)))
                .then(lit("gulls", GAME)
                        .then(lit("set", GAME).then(arg("count", IntegerArgumentType.integer(0, GameState.GULLS_TO_LAND), GAME)
                                .executes(SeasCommand.guarded(context -> gulls(context, false)))))
                        .then(lit("add", GAME).then(arg("count", IntegerArgumentType.integer(1, GameState.GULLS_TO_LAND), GAME)
                                .executes(SeasCommand.guarded(context -> gulls(context, true))))))
                // grant 的新名字（D6）：grant 原样留着（十几支脚本在用），这一条多留一道痕
                .then(lit("deal", GAME)
                        .then(arg("character", StringArgumentType.word(), GAME).suggests(SeasCommand.CHARACTERS)
                                .then(arg("card", StringArgumentType.word(), GAME)
                                        .executes(SeasCommand.guarded(DebugCommand::deal)))))
                .then(lit("skiff", GAME)
                        // 0–4：与灯油那一格方块状态的取值范围相同（SkiffBlocks.OIL）
                        .then(lit("oil", GAME).then(arg("level", IntegerArgumentType.integer(0, 4), GAME)
                                .executes(SeasCommand.guarded(DebugCommand::skiffOil))))
                        .then(lit("sail", GAME)
                                .then(lit("up", GAME).executes(SeasCommand.guarded(context -> skiffSail(context, true))))
                                .then(lit("down", GAME).executes(SeasCommand.guarded(context -> skiffSail(context, false))))))
                .then(lit("sky", GAME)
                        .then(lit("time", GAME).then(arg("time", LongArgumentType.longArg(0L, 23_999L), GAME)
                                .executes(SeasCommand.guarded(DebugCommand::skyTime))))
                        .then(lit("rain", GAME).then(arg("rain", FloatArgumentType.floatArg(0f, 1f), GAME)
                                .executes(SeasCommand.guarded(DebugCommand::skyRain))))
                        .then(lit("reset", GAME).executes(SeasCommand.guarded(DebugCommand::skyReset))))
                .then(lit("log", GAME).executes(SeasCommand.guarded(DebugCommand::log)))
                // 原来的 /seas dev roster（只在开发环境注册）搬到这里，生产服也有（D2）
                .then(lit("ui", GAME)
                        .then(lit("roster", GAME)
                                .executes(SeasCommand.guarded(context -> uiRoster(context, 6)))
                                .then(arg("players", IntegerArgumentType.integer(6, 8), GAME)
                                        .executes(SeasCommand.guarded(context -> uiRoster(context,
                                                IntegerArgumentType.getInteger(context, "players")))))));
    }

    // ------------------------------------------------------------------ 共用

    private record Live(ServerWorld world, GameComponent component, Session session) {
    }

    /**
     * 要有对局。{@code changing} 为真时还要<b>没结束</b>：终局序列里不许再改局面（翻牌与计分已经按结局算了）。
     * 不满足就报一句人话、返回 {@code null}。
     */
    private static Live live(CommandContext<ServerCommandSource> context, boolean changing) {
        ServerWorld world = SeasCommand.gameWorld(context);
        GameComponent component = GameComponents.of(world);
        if (component.session().isEmpty()) {
            context.getSource().sendError(Text.translatable("heavyseas.command.no_game"));
            return null;
        }
        Session session = component.requireSession();
        if (changing && (session.state().isOver() || component.endgame().isPresent())) {
            context.getSource().sendError(Text.translatable("heavyseas.command.already_over"));
            return null;
        }
        return new Live(world, component, session);
    }

    private static String who(CommandContext<ServerCommandSource> context) {
        return context.getSource().getName();
    }

    private static String input(CommandContext<ServerCommandSource> context) {
        return context.getInput();
    }

    /** 改了局面：留痕，并把同一句话回给下指令的人。 */
    private static void changed(CommandContext<ServerCommandSource> context, Live g, Text what) {
        DebugTrace.changed(g.world(), g.component(), who(context), input(context), what);
        Text echo = Text.translatable("heavyseas.debug.trace", Text.literal(who(context)), what);
        context.getSource().sendFeedback(() -> echo, false);
    }

    private static WeatherCard weatherCard(CommandContext<ServerCommandSource> context) {
        String raw = StringArgumentType.getString(context, "id");
        WeatherCard weather = GameDataLoader.require().weather().stream()
                .filter(card -> card.id().equals(raw))
                .findFirst().orElse(null);
        if (weather == null) {
            context.getSource().sendError(Text.translatable("heavyseas.debug.unknown_weather", raw));
        }
        return weather;
    }

    private static Text weatherName(String id) {
        return Text.translatable("heavyseas.weather." + id);
    }

    private static Text conditionName(Condition condition) {
        return Text.translatable(switch (condition) {
            case CONSCIOUS -> "heavyseas.condition.conscious";
            case UNCONSCIOUS -> "heavyseas.condition.unconscious";
            case DEAD -> "heavyseas.condition.dead";
        });
    }

    private static Text ids(Collection<String> ids) {
        return Text.literal(ids.isEmpty() ? "-" : String.join(",", ids));
    }

    private static Text joined(List<Text> parts) {
        MutableText out = Text.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                out.append(Text.literal("、"));
            }
            out.append(parts.get(i));
        }
        return out;
    }

    // ------------------------------------------------------------------ 天候

    /** 换掉<b>今天</b>的天候（原来的 {@code /seas dev weather}）：下一次翻牌时失效，牌堆与随机次序都不动。 */
    private static int weatherNow(CommandContext<ServerCommandSource> context) {
        Live g = live(context, true);
        if (g == null) {
            return 0;
        }
        WeatherCard weather = weatherCard(context);
        if (weather == null) {
            return 0;
        }
        g.session().table().weather()
                .orElseThrow(() -> new IllegalStateException("当前对局没有天候牌堆"))
                .overrideCurrentUntilNextDraw(weather);
        // 雨 · 雷 · 时刻也跟着换：天色由 PlayerSky 按今天的天候发，这里只记一行（ADR-0058 §4）
        MistSea.applyWeather(g.world(), g.component(), weather.id());
        changed(context, g, Text.translatable("heavyseas.debug.did.weather_now", weatherName(weather.id())));
        g.component().notify(Text.translatable("heavyseas.game.weather", weatherName(weather.id()),
                Text.translatable("heavyseas.weather.effect." + weather.id())).formatted(Formatting.AQUA));
        GameComponents.sync(g.world());
        // ❗hud_shot.py 按这一行认「天候换成了」，措辞别改
        LOGGER.info("开发验收：天候临时覆盖为 {}（{}）", weather.id(), weather.effect().id());
        return 1;
    }

    /** 下一天翻出指定的那一张：只调顺序，不造牌（{@link WeatherDeck#stackNext}）。 */
    private static int weatherNext(CommandContext<ServerCommandSource> context) {
        Live g = live(context, true);
        if (g == null) {
            return 0;
        }
        WeatherCard weather = weatherCard(context);
        if (weather == null) {
            return 0;
        }
        WeatherDeck deck = g.session().table().weather()
                .orElseThrow(() -> new IllegalStateException("当前对局没有天候牌堆"));
        if (!deck.stackNext(weather.id())) {
            context.getSource().sendError(Text.translatable("heavyseas.debug.weather_not_next", weatherName(weather.id())));
            return 0;
        }
        changed(context, g, Text.translatable("heavyseas.debug.did.weather_next", weatherName(weather.id())));
        return 1;
    }

    // ------------------------------------------------------------------ 下一局

    private static int nextShow(CommandContext<ServerCommandSource> context) {
        Optional<DebugNext.Setting> seed = DebugNext.seed();
        Optional<DebugNext.Setting> weather = DebugNext.weather();
        if (seed.isEmpty() && weather.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.translatable("heavyseas.debug.next_none"), false);
            return 1;
        }
        seed.ifPresent(s -> context.getSource().sendFeedback(
                () -> Text.translatable("heavyseas.debug.next_seed", s.value()), false));
        weather.ifPresent(w -> context.getSource().sendFeedback(
                () -> Text.translatable("heavyseas.debug.next_weather", weatherName(w.value())), false));
        return 1;
    }

    private static int nextSeed(CommandContext<ServerCommandSource> context) {
        long seed = LongArgumentType.getLong(context, "seed");
        DebugNext.setSeed(seed, who(context), input(context));
        DebugTrace.outsideGame(who(context), input(context));
        context.getSource().sendFeedback(() -> Text.translatable("heavyseas.debug.next_seed", Long.toString(seed)), false);
        return 1;
    }

    private static int nextWeather(CommandContext<ServerCommandSource> context) {
        WeatherCard weather = weatherCard(context);
        if (weather == null) {
            return 0;
        }
        DebugNext.setWeather(weather.id(), who(context), input(context));
        DebugTrace.outsideGame(who(context), input(context));
        context.getSource().sendFeedback(() -> Text.translatable("heavyseas.debug.next_weather",
                weatherName(weather.id())), false);
        return 1;
    }

    private static int nextClear(CommandContext<ServerCommandSource> context) {
        DebugNext.clear();
        DebugTrace.outsideGame(who(context), input(context));
        context.getSource().sendFeedback(() -> Text.translatable("heavyseas.debug.next_cleared"), false);
        return 1;
    }

    // ------------------------------------------------------------------ 计时

    /** 此刻开着的倒计时一律立刻到点，超时那一步由各阶段自己的 tick 照本来的规矩走。 */
    private static int timerNow(CommandContext<ServerCommandSource> context) {
        Live g = live(context, true);
        if (g == null) {
            return 0;
        }
        List<String> expired = g.component().expireOpenWindows(System.currentTimeMillis());
        if (expired.isEmpty()) {
            context.getSource().sendError(Text.translatable("heavyseas.debug.no_window"));
            return 0;
        }
        List<Text> names = new ArrayList<>();
        expired.forEach(name -> names.add(Text.translatable(windowKey(name))));
        // 与语言无关的一行：回归脚本按它认「哪几面被拨到了点」
        LOGGER.info("调试：立刻到点 {}", expired);
        changed(context, g, Text.translatable("heavyseas.debug.did.timer_now", joined(names)));
        return 1;
    }

    /**
     * 窗口名 → lang 键。写成 switch 而不是拼字符串：拼出来的键静态扫不到（checkLangKeys 只认字面量），
     * 漏一个键的表现是右栏里出现一行键名原文，游戏照跑。
     */
    private static String windowKey(String name) {
        return switch (name) {
            case "action" -> "heavyseas.debug.window.action";
            case "provision" -> "heavyseas.debug.window.provision";
            case "helm" -> "heavyseas.debug.window.helm";
            case "thirst" -> "heavyseas.debug.window.thirst";
            case "contest" -> "heavyseas.debug.window.contest";
            case "designation" -> "heavyseas.debug.window.designation";
            case "overboard" -> "heavyseas.debug.window.overboard";
            default -> throw new IllegalArgumentException("没有这一面倒计时：" + name);
        };
    }

    // ------------------------------------------------------------------ 查看与导出（只读）

    /** D4 的判据本身：这一局里的人不能看底牌。纯函数，单测在这里。 */
    static boolean mayPeek(UUID executor, Predicate<UUID> inThisGame) {
        return executor == null || !inThisGame.test(executor);
    }

    private static boolean mayPeekNow(CommandContext<ServerCommandSource> context, GameComponent component) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        UUID executor = player == null ? null : player.getUuid();
        if (mayPeek(executor, id -> component.belongsToActiveVoyage(id) || component.seatOf(id).isPresent())) {
            return true;
        }
        context.getSource().sendError(Text.translatable("heavyseas.debug.in_game"));
        LOGGER.info("调试：{} 在这一局里，不给看底牌（{}）", who(context), input(context));
        return false;
    }

    private static int inspect(CommandContext<ServerCommandSource> context, String only) {
        Live g = live(context, false);
        if (g == null || !mayPeekNow(context, g.component())) {
            return 0;
        }
        Session session = g.session();
        GameState state = session.state();
        List<CharacterId> targets = state.bySeat();
        if (only != null) {
            Optional<CharacterId> found = SeasCommand.named(context, session, "character");
            if (found.isEmpty()) {
                return 0;
            }
            targets = List.of(found.get());
        }
        ServerCommandSource source = context.getSource();
        Text header = Text.translatable("heavyseas.debug.inspect.header", state.turn(),
                Text.literal(state.phase().name()), state.gulls(), GameState.GULLS_TO_LAND,
                Text.literal(session.currentWeather().map(WeatherCard::id).orElse("-")));
        source.sendFeedback(() -> header, false);
        Optional<Affinities> affinities = session.affinities();
        for (CharacterId id : targets) {
            SurvivorState s = state.stateOf(id);
            int size = state.roster().get(id).size();
            List<Text> flags = new ArrayList<>();
            if (state.isRemoved(id)) {
                flags.add(Text.translatable("heavyseas.debug.flag.removed"));
            }
            if (state.isOffline(id)) {
                flags.add(Text.translatable("heavyseas.debug.flag.offline"));
            }
            Text occupant = g.component().occupantOf(id)
                    .map(o -> o.isDummy() ? Text.translatable("heavyseas.game.dummy") : Text.literal(o.label()))
                    .orElse(Text.literal("?"));
            Text line = Text.translatable("heavyseas.debug.inspect.seat", s.seat(), GameFlow.characterName(id),
                    Text.literal(id.value()), occupant, size - s.damage(), size, conditionName(state.conditionOf(id)),
                    s.thirst().count(), ids(s.hand()), ids(s.front()),
                    Text.literal(affinities.map(a -> a.loveOf(id).value()).orElse("-")),
                    Text.literal(affinities.map(a -> a.hateOf(id).value()).orElse("-")), joined(flags));
            source.sendFeedback(() -> line, false);
        }
        List<String> nav = session.table().pile().order().stream().map(NavigationCard::id).limit(3).toList();
        List<String> weather = session.table().weather().map(d -> d.upcoming().stream().map(WeatherCard::id)
                .limit(3).toList()).orElse(List.of());
        List<String> provisions = session.table().provisionPileOrder().stream().limit(3).toList();
        int weatherLeft = session.table().weather().map(WeatherDeck::remaining).orElse(0);
        Text piles = Text.translatable("heavyseas.debug.inspect.piles", session.table().pile().size(), ids(nav),
                weatherLeft, ids(weather), session.table().provisionsLeft(), ids(provisions));
        source.sendFeedback(() -> piles, false);
        DebugTrace.readOnly(g.component(), who(context), input(context));
        return 1;
    }

    /** 整局状态写成一个 JSON 文件（{@code <服务端目录>/heavyseas-debug/<时刻>.json}），报 bug 用。 */
    private static int dump(CommandContext<ServerCommandSource> context) {
        Live g = live(context, false);
        if (g == null || !mayPeekNow(context, g.component())) {
            return 0;
        }
        JsonObject root = dumpJson(g);
        Path dir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize().resolve("heavyseas-debug");
        Path file = dir.resolve(LocalDateTime.now().format(DUMP_NAME) + ".json");
        try {
            Files.createDirectories(dir);
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("调试：导出失败 {}", file, e);
            context.getSource().sendError(Text.translatable("heavyseas.debug.dump_failed", e.toString()));
            return 0;
        }
        String path = file.toAbsolutePath().toString();
        // 与语言无关的一行：回归脚本按它找到文件
        LOGGER.info("调试：导出 → {}", path);
        DebugTrace.readOnly(g.component(), who(context), input(context));
        context.getSource().sendFeedback(() -> Text.translatable("heavyseas.debug.dumped", path), false);
        return 1;
    }

    private static JsonArray strings(Collection<String> values) {
        JsonArray out = new JsonArray();
        values.forEach(out::add);
        return out;
    }

    private static JsonObject dumpJson(Live g) {
        Session session = g.session();
        GameComponent component = g.component();
        GameState state = session.state();
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        root.addProperty("written_at", Instant.now().toString());
        root.addProperty("dimension", g.world().getRegistryKey().getValue().toString());
        root.addProperty("layout", component.layoutId().map(Object::toString).orElse(""));
        root.addProperty("turn", state.turn());
        root.addProperty("phase", state.phase().name());
        root.addProperty("gulls", state.gulls());
        root.addProperty("outcome", state.outcome().map(Enum::name).orElse(""));

        JsonObject weather = new JsonObject();
        weather.addProperty("today", session.currentWeather().map(WeatherCard::id).orElse(""));
        session.table().weather().ifPresent(deck -> {
            weather.add("upcoming", strings(deck.upcoming().stream().map(WeatherCard::id).toList()));
            weather.add("discard", strings(deck.discardedCards().stream().map(WeatherCard::id).toList()));
        });
        root.add("weather", weather);

        JsonObject navigation = new JsonObject();
        navigation.add("pile", strings(session.table().pile().order().stream().map(NavigationCard::id).toList()));
        navigation.add("row_stack", strings(session.table().rowStack().stream().map(NavigationCard::id).toList()));
        root.add("navigation", navigation);

        JsonObject provisions = new JsonObject();
        provisions.add("pile", strings(session.table().provisionPileOrder()));
        provisions.add("offer", strings(session.provisionOffer()));
        provisions.addProperty("holder", session.provisionHolder().map(CharacterId::value).orElse(""));
        provisions.add("discard", strings(session.table().provisionDiscard()));
        provisions.add("removed", strings(session.table().removedProvisions()));
        root.add("provisions", provisions);

        Optional<Affinities> affinities = session.affinities();
        JsonArray seats = new JsonArray();
        for (CharacterId id : state.bySeat()) {
            SurvivorState s = state.stateOf(id);
            JsonObject seat = new JsonObject();
            seat.addProperty("seat", s.seat());
            seat.addProperty("id", id.value());
            GameComponent.Occupant occupant = component.occupantOf(id).orElse(null);
            seat.addProperty("dummy", occupant == null || occupant.isDummy());
            seat.addProperty("occupant", occupant == null ? "" : occupant.label());
            seat.addProperty("size", state.roster().get(id).size());
            seat.addProperty("damage", s.damage());
            seat.addProperty("condition", state.conditionOf(id).name());
            seat.addProperty("removed", state.isRemoved(id));
            seat.addProperty("offline", state.isOffline(id));
            seat.addProperty("acted", s.actedThisTurn());
            seat.addProperty("thirst", s.thirst().count());
            seat.add("hand", strings(s.hand()));
            seat.add("front", strings(s.front()));
            seat.add("opened", strings(s.opened().stream().sorted().toList()));
            seat.addProperty("love", affinities.map(a -> a.loveOf(id).value()).orElse(""));
            seat.addProperty("hate", affinities.map(a -> a.hateOf(id).value()).orElse(""));
            seats.add(seat);
        }
        root.add("seats", seats);

        Optional<Contest> contest = session.contest();
        if (contest.isPresent()) {
            JsonObject c = new JsonObject();
            c.addProperty("kind", contest.get().kind().name());
            c.addProperty("attacker", contest.get().attacker().value());
            c.addProperty("target", contest.get().target().value());
            c.addProperty("stage", contest.get().stage().name());
            root.add("contest", c);
        } else {
            root.add("contest", JsonNull.INSTANCE);
        }

        JsonObject windows = new JsonObject();
        windows.addProperty("action", component.actionDeadline());
        windows.addProperty("provision", component.provisionDeadline());
        windows.addProperty("helm", component.helmDeadline());
        windows.addProperty("thirst", component.thirstDeadline());
        windows.addProperty("contest", component.contestDeadline());
        windows.addProperty("designation", component.designationDeadline());
        windows.addProperty("overboard", component.overboardDeadline());
        root.add("windows", windows);

        JsonObject debug = new JsonObject();
        debug.addProperty("changes", component.debugChanges());
        JsonArray entries = new JsonArray();
        for (GameComponent.DebugEntry e : component.debugEntries()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("who", e.who());
            entry.addProperty("turn", e.turn());
            entry.addProperty("command", e.command());
            entry.addProperty("changed", e.changed());
            entries.add(entry);
        }
        debug.add("entries", entries);
        JsonObject next = new JsonObject();
        next.addProperty("seed", DebugNext.seed().map(DebugNext.Setting::value).orElse(""));
        next.addProperty("weather", DebugNext.weather().map(DebugNext.Setting::value).orElse(""));
        debug.add("next", next);
        JsonObject sky = new JsonObject();
        PlayerSky.forced().ifPresent(f -> {
            if (f.time() != null) {
                sky.addProperty("time", f.time());
            }
            if (f.rain() != null) {
                sky.addProperty("rain", f.rain());
            }
        });
        debug.add("sky", sky);
        root.add("debug", debug);

        // 右栏此刻的那几条，按发给客户端的那一种写法（与语言无关的 JSON 文本）：全船看到的就是这些
        JsonArray feed = new JsonArray();
        for (Text line : component.notifications()) {
            feed.add(Text.Serialization.toJsonString(line, g.world().getRegistryManager()));
        }
        root.add("feed", feed);
        return root;
    }

    // ------------------------------------------------------------------ 海鸥

    /**
     * 海鸥定为 / 加上 n 只。凑够 {@value GameState#GULLS_TO_LAND} 只就走与 {@code /seas land} 同一条靠岸流程
     * （{@link GameFlow#afterFixtureLanding}）。
     */
    private static int gulls(CommandContext<ServerCommandSource> context, boolean add) {
        Live g = live(context, true);
        if (g == null) {
            return 0;
        }
        Session session = g.session();
        int before = session.state().gulls();
        int count = IntegerArgumentType.getInteger(context, "count");
        int target = add ? before + count : count;
        // ❗先问再交给引擎：要靠岸了，而划船 / 口渴还没定完，引擎会抛 —— 抛出来是一句堆栈，不是一次被拒绝的操作
        if (target >= GameState.GULLS_TO_LAND && (session.rower().isPresent() || session.thirstInProgress())) {
            context.getSource().sendError(Text.translatable("heavyseas.debug.gulls_wait"));
            return 0;
        }
        session.debugSetGulls(target);
        changed(context, g, Text.translatable("heavyseas.debug.did.gulls", before, target));
        if (session.state().isOver()) {
            LOGGER.info("调试：海鸥凑够 {} 只，靠岸（第 {} 回合）", target, session.state().turn());
            GameFlow.afterFixtureLanding(g.world(), g.component());
        } else {
            GameComponents.sync(g.world());
        }
        return 1;
    }

    // ------------------------------------------------------------------ 牌

    /** 从牌堆发一张指定的牌（{@code grant} 的新名字）：牌真的从牌堆里少一张，对账照样成立。 */
    private static int deal(CommandContext<ServerCommandSource> context) {
        Live g = live(context, true);
        if (g == null) {
            return 0;
        }
        Session session = g.session();
        Optional<CharacterId> who = SeasCommand.named(context, session, "character");
        if (who.isEmpty()) {
            return 0;
        }
        String card = StringArgumentType.getString(context, "card");
        if (session.provisions().all().stream().noneMatch(p -> p.id().equals(card))) {
            context.getSource().sendError(Text.translatable("heavyseas.debug.unknown_card", card));
            return 0;
        }
        // ❗先问再发（与 grant 同一条）：牌堆里没有这张时引擎会抛
        if (session.table().provisionsLeft(card) <= 0) {
            context.getSource().sendError(Text.translatable("heavyseas.command.pile_empty_of",
                    SeasCommand.provisionName(card), session.table().provisionsLeft()));
            return 0;
        }
        session.dealFromPile(who.get(), card);
        changed(context, g, Text.translatable("heavyseas.debug.did.deal", GameFlow.characterName(who.get()),
                SeasCommand.provisionName(card)));
        GameComponents.sync(g.world());
        return 1;
    }

    // ------------------------------------------------------------------ 艇上（只改世界）

    private static int skiffOil(CommandContext<ServerCommandSource> context) {
        Live g = live(context, false);
        if (g == null) {
            return 0;
        }
        int level = IntegerArgumentType.getInteger(context, "level");
        List<BlockPos> lanterns = SkiffProps.debugSetOil(g.world(), g.component(), level);
        if (lanterns.isEmpty()) {
            context.getSource().sendError(Text.translatable("heavyseas.debug.skiff_missing",
                    Text.translatable("heavyseas.debug.part.lantern")));
            return 0;
        }
        changed(context, g, Text.translatable("heavyseas.debug.did.oil", level));
        return 1;
    }

    private static int skiffSail(CommandContext<ServerCommandSource> context, boolean raise) {
        Live g = live(context, false);
        if (g == null) {
            return 0;
        }
        List<BlockPos> sails = SkiffProps.debugSetSail(g.world(), g.component(), raise);
        if (sails.isEmpty()) {
            context.getSource().sendError(Text.translatable("heavyseas.debug.skiff_missing",
                    Text.translatable("heavyseas.debug.part.sail")));
            return 0;
        }
        changed(context, g, Text.translatable(raise ? "heavyseas.debug.did.sail_up" : "heavyseas.debug.did.sail_down"));
        return 1;
    }

    // ------------------------------------------------------------------ 天色（只改「给谁看什么」）

    /** 有对局就记进这一局（全船看得见），没有就只打日志。 */
    private static void skyTrace(CommandContext<ServerCommandSource> context, Text what) {
        ServerWorld world = SeasCommand.gameWorld(context);
        GameComponent component = GameComponents.of(world);
        if (component.session().isPresent()) {
            changed(context, new Live(world, component, component.requireSession()), what);
        } else {
            DebugTrace.outsideGame(who(context), input(context));
            context.getSource().sendFeedback(() -> what, false);
        }
    }

    private static int skyTime(CommandContext<ServerCommandSource> context) {
        long time = LongArgumentType.getLong(context, "time");
        PlayerSky.forceTime(time, who(context), input(context));
        skyTrace(context, Text.translatable("heavyseas.debug.did.sky_time", Long.toString(time)));
        return 1;
    }

    private static int skyRain(CommandContext<ServerCommandSource> context) {
        float rain = FloatArgumentType.getFloat(context, "rain");
        PlayerSky.forceRain(rain, who(context), input(context));
        skyTrace(context, Text.translatable("heavyseas.debug.did.sky_rain", Float.toString(rain)));
        return 1;
    }

    private static int skyReset(CommandContext<ServerCommandSource> context) {
        if (!PlayerSky.resetForced()) {
            context.getSource().sendError(Text.translatable("heavyseas.debug.sky_not_forced"));
            return 0;
        }
        skyTrace(context, Text.translatable("heavyseas.debug.did.sky_reset"));
        return 1;
    }

    // ------------------------------------------------------------------ 记录

    /** 列出这一局（没有对局时是上一局）用过的调试指令：谁、第几天、什么，改没改局面。 */
    private static int log(CommandContext<ServerCommandSource> context) {
        ServerWorld world = SeasCommand.gameWorld(context);
        GameComponent component = GameComponents.of(world);
        if (component.session().isEmpty()) {
            // 两局之间：上一局的记录在承载对局的那个维度里（与 status 同一个坑）
            ServerWorld sea = MistSea.world(context.getSource().getServer());
            if (sea != null) {
                component = GameComponents.of(sea);
            }
        }
        List<GameComponent.DebugEntry> entries = component.debugEntries();
        ServerCommandSource source = context.getSource();
        if (entries.isEmpty()) {
            source.sendFeedback(() -> Text.translatable("heavyseas.debug.log_empty"), false);
            return 1;
        }
        int changes = component.debugChanges();
        boolean current = component.session().isPresent();
        source.sendFeedback(() -> Text.translatable(current ? "heavyseas.debug.log_header" : "heavyseas.debug.log_header_last",
                entries.size(), changes), false);
        for (GameComponent.DebugEntry e : entries) {
            Text line = Text.translatable("heavyseas.debug.log_line", e.turn(), e.who(), e.command(),
                    e.changed() ? Text.empty() : Text.translatable("heavyseas.debug.read_only"));
            source.sendFeedback(() -> line, false);
        }
        return 1;
    }

    // ------------------------------------------------------------------ 界面

    /** 给执行者自己弹阵容一面（原来的 {@code /seas dev roster}）；确认按钮仍走生产那条 {@code StartVoyageC2S} 校验。 */
    private static int uiRoster(CommandContext<ServerCommandSource> context, int players) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendError(Text.translatable("heavyseas.debug.player_only"));
            return 0;
        }
        RosterConfigS2C packet = RosterConfigS2C.from(player.getBlockPos().asLong(), players,
                GameDataLoader.require().roster());
        ServerPlayNetworking.send(player, packet);
        LOGGER.info("开发验收：向 {} 打开 {} 人阵容面板", player.getGameProfile().getName(), players);
        return 1;
    }
}
