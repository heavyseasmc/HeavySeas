package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.data.FogTable;
import io.github.heavyseasmc.mod.data.SceneDataLoader;
import io.github.heavyseasmc.mod.data.VoyageLayout;
import io.github.heavyseasmc.mod.game.GameFlow;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * M4's isolated all-ocean play space and the crash-safe boundary around player inventories.
 *
 * <h2>场景从布局取（ADR-0034 §5.5）</h2>
 * 维度 · 船头 · 朝向 · 走廊此前都写死在这里；现在全从 {@link VoyageLayout} 读。
 * 布局在开局那一刻校验：维度没加载、锚点下方没水、布景滑程超出服务端视距，一律拒绝开局并点名 —— 不静默退回。
 *
 * <h2>场景要收</h2>
 * 船体是真方块、走廊是强加载票，两者都进存档。三条退出路径（终局收场 · {@code /seas end} · 推进出错）
 * 都经 {@link #restoreAll} → {@link #cleanupScene}；起服时 {@link #resetScene} 清上一次崩在对局中留下的。
 */
public final class MistSea {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private MistSea() {
    }

    /**
     * 承载对局的世界：有对局时是那一局布局的维度，否则是默认布局的维度；维度没加载（或布局还没读到）时为 {@code null}。
     */
    public static ServerWorld world(MinecraftServer server) {
        VoyageLayout layout = SceneDataLoader.activeLayout(server);
        return layout == null ? null : server.getWorld(layout.dimensionKey());
    }

    /**
     * Crosses the only authoritative boundary into a match: escrow, empty inventory, teleport, then begin rules.
     * If any later step fails, every successfully escrowed player is restored before the exception escapes.
     */
    public static void startVoyage(MinecraftServer server, int players, List<ServerPlayerEntity> humans,
                                   Set<CharacterId> reservedForDummies) {
        startVoyage(server, players, humans, reservedForDummies, null, SceneDataLoader.DEFAULT);
    }

    public static void startVoyage(MinecraftServer server, int players, List<ServerPlayerEntity> humans,
                                   Set<CharacterId> reservedForDummies, List<CharacterId> selectedRoster) {
        startVoyage(server, players, humans, reservedForDummies, selectedRoster, SceneDataLoader.DEFAULT);
    }

    /**
     * @param selectedRoster 房主定的阵容；{@code null} 用预设
     * @param layoutId       用哪份航程布局（{@code /seas start [players] [layout]}）
     */
    public static void startVoyage(MinecraftServer server, int players, List<ServerPlayerEntity> humans,
                                   Set<CharacterId> reservedForDummies, List<CharacterId> selectedRoster,
                                   Identifier layoutId) {
        VoyageLayout layout = SceneDataLoader.require(layoutId);
        ServerWorld sea = server.getWorld(layout.dimensionKey());
        if (sea == null) {
            throw new IllegalStateException("布局 %s 的 dimension：维度 %s 未加载 —— 服务端没有这个维度，或数据包没装"
                    .formatted(layout.id(), layout.dimension()));
        }
        GameComponent component = GameComponents.of(sea);
        if (component.session().isPresent()) {
            throw new IllegalStateException("雾海中已有一局进行中");
        }
        // 上局离线玩家还没恢复的托管不影响开新局（协作者 965e383 去掉了那道全局守卫）；逐人那道「已有一份未恢复的托管」在 escrow() 里照旧。
        int viewBlocks = server.getPlayerManager().getViewDistance() * 16;
        if (layout.arrival().slideFrom() > viewBlocks) {
            throw new IllegalStateException("布局 %s 的 arrival.slide_from：%d 格超过服务端 view-distance %d chunk = %d 格，布景送不到客户端"
                    .formatted(layout.id(), layout.arrival().slideFrom(), server.getPlayerManager().getViewDistance(), viewBlocks));
        }
        prepareScene(sea, component, layout);
        List<ServerPlayerEntity> crossed = new ArrayList<>();
        try {
            for (ServerPlayerEntity player : humans) {
                escrow(component, player);
                crossed.add(player);
            }
            if (!crossed.isEmpty()) {
                checkpoint(server, "进入雾海前的物品托管");
            }
            Vec3d bow = layout.boat().bow();
            for (ServerPlayerEntity player : crossed) {
                player.stopRiding();
                player.teleport(sea, bow.x, bow.y + 1.0, bow.z, layout.ridersFacing(), 0f);
            }
            component.setLayoutId(layout.id());
            if (selectedRoster == null) {
                GameFlow.start(sea, players, humans, reservedForDummies, layout);
            } else {
                GameFlow.start(sea, players, humans, reservedForDummies, layout, selectedRoster);
            }
            LOGGER.info("雾海：{} 名玩家已托管物品并进入 {}（布局 {}）", crossed.size(), layout.dimension(), layout.id());
        } catch (RuntimeException failure) {
            Gulls.clear(sea, component);
            Backdrop.clear(sea, component);
            Seats.clear(sea, component);
            if (component.session().isPresent()) {
                component.end();
                GameComponents.sync(sea);
            }
            for (ServerPlayerEntity player : crossed) {
                restore(component, player);
            }
            cleanupScene(sea, component);
            throw failure;
        }
    }

    /**
     * 摆场景：强加载走廊、放船体，脚印记进组件。
     *
     * <p>先清上一次的残留：正常路径下这里应当什么都没有；有，说明上次没收干净，清掉并打一行 —— 别在旧船上再放一艘。
     */
    private static void prepareScene(ServerWorld sea, GameComponent component, VoyageLayout layout) {
        if (component.sceneLeftover().isPresent()) {
            LOGGER.warn("场景：上一局留下的船体或强加载还在，先清掉再摆");
            cleanupScene(sea, component);
        }
        List<Long> forced = new ArrayList<>();
        if (layout.arrival().forceload()) {
            for (ChunkPos chunk : layout.corridor()) {
                sea.setChunkForced(chunk.x, chunk.z, true);
                forced.add(chunk.toLong());
            }
        }
        // 先记走廊再放船：放船抛了（模板不在 · 没水），走廊已经在存档里，脚印必须已经记着才收得回。
        component.setSceneLeftover(new GameComponent.SceneLeftover(Optional.empty(), forced));
        Optional<GameComponent.HullFootprint> hull = Hull.place(sea, layout);
        component.setSceneLeftover(new GameComponent.SceneLeftover(hull, forced));
        LOGGER.info("场景已摆好：强加载 {} 个 chunk · 船体 {}", forced.size(), hull.isPresent() ? "已放" : "无");
    }

    /**
     * 收场景：船体清回海水、走廊解除强加载、忘掉布局。三条退出路径都经过这里，起服也经过。
     * 幂等：没有脚印时什么也不做。
     */
    public static void cleanupScene(ServerWorld sea, GameComponent component) {
        component.sceneLeftover().ifPresent(leftover -> {
            leftover.hull().ifPresent(footprint -> Hull.restore(sea, footprint));
            for (long packed : leftover.forcedChunks()) {
                ChunkPos chunk = new ChunkPos(packed);
                sea.setChunkForced(chunk.x, chunk.z, false);
            }
            component.clearSceneLeftover();
            LOGGER.info("场景已收：解除强加载 {} 个 chunk", leftover.forcedChunks().size());
        });
        clock(sea).resetWeather();                 // 雨与雷是这一局按天候开的，收场时放晴
        component.clearLayoutId();
    }

    /**
     * 时刻与天气该写给哪个世界：<b>主世界</b>，不是雾海那个维度。
     *
     * <p>2026-09-19 换地图演练实测：日志打着「时刻 13000」，维度里 {@code time query} 却是 19531 → 10 秒后 19731，
     * 钟一直在走。查 1.21.1 字节码：主世界之外的 {@code ServerWorld} 拿到的是 {@code UnmodifiableLevelProperties}，
     * 它的 {@code setTimeOfDay / setRaining / setThundering / setRainTime / setClearWeatherTime / setThunderTime}
     * 六个方法的方法体全是一句 {@code return}，读则转给主世界那份。全服只有一口钟、一场雨，
     * Minecraft 自己的 {@code /weather} 指令也是写 {@code getOverworld()}。于是大厅（主世界）里的人会同时看见雾海的昼夜与雨——这是游戏本身的形状，不是我们的选择。
     */
    private static ServerWorld clock(ServerWorld sea) {
        return sea.getServer().getOverworld();
    }

    private static void escrow(GameComponent component, ServerPlayerEntity player) {
        if (component.hasVoyageEscrow(player.getUuid())) {
            throw new IllegalStateException(player.getGameProfile().getName() + " 已有一份未恢复的雾海托管");
        }
        Vec3d returnPoint = player.getPos();
        if (player.getVehicle() instanceof SeatEntity seat && seat.ours() && seat.lobby()) {
            returnPoint = seat.lobbyLanding(player).orElseThrow(
                    () -> new IllegalStateException("大厅游轮附近没有安全的返回地面，无法开航"));
        }
        NbtList inventory = player.getInventory().writeNbt(new NbtList());
        component.putVoyageEscrow(new GameComponent.VoyageEscrow(player.getUuid(),
                player.getWorld().getRegistryKey().getValue().toString(),
                returnPoint.x, returnPoint.y, returnPoint.z, player.getYaw(), player.getPitch(), inventory));
        player.getInventory().clear();
        player.getInventory().markDirty();
        player.playerScreenHandler.sendContentUpdates();
    }

    /** Normal end: online players return immediately; offline records remain until their next login. */
    public static void restoreAll(ServerWorld sea, GameComponent component) {
        int restored = 0;
        for (UUID id : new ArrayList<>(component.voyageEscrowPlayers())) {
            ServerPlayerEntity player = sea.getServer().getPlayerManager().getPlayer(id);
            if (player != null && restore(component, player)) {
                restored++;
            }
        }
        cleanupScene(sea, component);
        if (restored > 0) {
            checkpoint(sea.getServer(), "雾海结束后的物品恢复");
        }
    }

    /**
     * 起服时：上一次崩在对局中留下的船体与强加载票。对局本身不持久化，所以「没有对局却有脚印」就是残留。
     *
     * <p>❗每个世界都看，不只看默认布局的维度 —— 非官方地图的那一局可能在别的维度里。
     */
    public static void resetScene(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            GameComponent component = GameComponents.of(world);
            if (component.session().isEmpty() && component.sceneLeftover().isPresent()) {
                LOGGER.info("场景：{} 里有上次留下的船体或强加载，清掉", world.getRegistryKey().getValue());
                cleanupScene(world, component);
            }
        }
    }

    /** Login recovery path for a server which stopped during a match. Safe and idempotent. */
    public static void recover(ServerPlayerEntity player) {
        ServerWorld sea = world(player.server);
        if (sea == null) {
            return;
        }
        GameComponent component = GameComponents.of(sea);
        if (!component.hasVoyageEscrow(player.getUuid())) {
            return;
        }
        if (component.session().isPresent()) {
            // This is a reconnect, not crash recovery. The escrow must remain sealed until the match ends.
            VoyageLayout layout = component.layoutId().map(SceneDataLoader::require)
                    .orElseGet(SceneDataLoader::defaultLayout);
            if (!player.getWorld().getRegistryKey().equals(sea.getRegistryKey())) {
                Vec3d bow = layout.boat().bow();
                player.teleport(sea, bow.x, bow.y + 1.0, bow.z, layout.ridersFacing(), 0f);
            }
            GameComponents.sync(sea);
            return;
        }
        if (restore(component, player)) {
            checkpoint(player.server, "雾海异常中断恢复");
            player.sendMessage(Text.translatable("heavyseas.mist_sea.recovered"), true);
            LOGGER.info("雾海恢复：{} 的物品与返回位置已恢复", player.getGameProfile().getName());
        }
    }

    private static boolean restore(GameComponent component, ServerPlayerEntity player) {
        GameComponent.VoyageEscrow escrow = component.removeVoyageEscrow(player.getUuid()).orElse(null);
        if (escrow == null) {
            return false;
        }
        Identifier id = Identifier.tryParse(escrow.dimension());
        ServerWorld destination = id == null ? null : player.server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id));
        if (destination == null) {
            destination = player.server.getOverworld();
        }
        try {
            player.stopRiding();
            player.getInventory().clear();
            player.getInventory().readNbt(escrow.inventory().copy());
            player.getInventory().markDirty();
            player.playerScreenHandler.sendContentUpdates();
            player.removeStatusEffect(StatusEffects.BLINDNESS);
            player.teleport(destination, escrow.x(), escrow.y(), escrow.z(), escrow.yaw(), escrow.pitch());
            return true;
        } catch (RuntimeException failure) {
            // Never consume the only recovery copy on a partial restore.
            component.putVoyageEscrow(escrow);
            LOGGER.error("雾海恢复失败：{}", player.getGameProfile().getName(), failure);
            return false;
        }
    }

    /** 雨与时刻钉多久：一天的对局远不到这个数，中途不会自己放晴。 */
    private static final int WEATHER_HOLD_TICKS = 6_000_000;

    /** 时刻每隔这么多 tick 钉一次：{@code doDaylightCycle} 是全服规则，不能只关这一个维度。 */
    private static final int TIME_PIN_INTERVAL = 20;

    /**
     * 天候到世界（ADR-0034 §5.1.5）：按雾表那一行开雨雷、钉时刻。每天翻出天候时调一次，{@code /seas dev weather} 也调。
     *
     * <p>雾本身不在这里：客户端按投影里的 {@code HudView.Fog} 渲染（§5.1.2）。M4 那版的 BLINDNESS 已经拿掉。
     */
    public static void applyWeather(ServerWorld world, GameComponent component, String weatherId) {
        FogTable.Entry entry = SceneDataLoader.fogFor(component.layoutId().orElse(SceneDataLoader.DEFAULT), weatherId);
        ServerWorld clock = clock(world);            // 写雾海那个维度是空操作，见 clock()
        clock.setWeather(entry.rain() ? 0 : WEATHER_HOLD_TICKS, entry.rain() ? WEATHER_HOLD_TICKS : 0,
                entry.rain(), entry.thunder());
        clock.setTimeOfDay(entry.time());
        // 与语言无关的一行。❗它只证明「写了」，不证明「落到了世界上」——2026-09-19 前这行照打、钟照走；
        // 要核对得在维度里 time query 两次（ADR-0034 §10.4）。
        LOGGER.info("天候到世界：{} · 雨 {} · 雷 {} · 时刻 {} · 雾 {}/{} · 钟 {}", weatherId, entry.rain(), entry.thunder(),
                entry.time(), entry.start(), entry.end(), clock.getRegistryKey().getValue());
    }

    /** 每 20 tick 把时刻钉回当日天候那一行；对局外什么都不做。 */
    public static void tick(MinecraftServer server) {
        if (server.getTicks() % TIME_PIN_INTERVAL != 0) {
            return;
        }
        ServerWorld sea = world(server);
        if (sea == null) {
            return;
        }
        GameComponent component = GameComponents.of(sea);
        if (component.session().isEmpty() || component.layoutId().isEmpty()) {
            return;
        }
        String weather = component.requireSession().currentWeather().map(card -> card.id()).orElse("");
        clock(sea).setTimeOfDay(SceneDataLoader.fogFor(component.layoutId().get(), weather).time());
    }

    private static void checkpoint(MinecraftServer server, String reason) {
        if (!server.save(false, true, false)) {
            LOGGER.warn("雾海存档检查点未报告成功：{}", reason);
        } else {
            LOGGER.info("雾海存档检查点：{}", reason);
        }
    }
}
