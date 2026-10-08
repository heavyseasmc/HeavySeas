package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.data.FogTable;
import io.github.heavyseasmc.mod.data.SceneDataLoader;
import io.github.heavyseasmc.mod.data.VoyageLayout;
import io.github.heavyseasmc.mod.game.GameFlow;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.world.liner.LinerShip;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
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
    /** 演习艇敲钟开航（{@code DrillSkiff}）：房主定的阵容、默认布局；不是演示局（审查 2026-10-07 R5）。 */
    public static void startVoyage(MinecraftServer server, int players, List<ServerPlayerEntity> humans,
                                   Set<CharacterId> reservedForDummies, List<CharacterId> selectedRoster) {
        startVoyage(server, players, humans, reservedForDummies, selectedRoster, SceneDataLoader.DEFAULT, false);
    }

    /**
     * @param selectedRoster 房主定的阵容；{@code null} 用预设
     * @param layoutId       用哪份航程布局（{@code /seas start [players] [layout]}）
     * @param viaCommand     是 {@code /seas start} 开的：有替身的这种局是演示局（{@code GameComponent#isDemo}，审查 R5）
     */
    public static void startVoyage(MinecraftServer server, int players, List<ServerPlayerEntity> humans,
                                   Set<CharacterId> reservedForDummies, List<CharacterId> selectedRoster,
                                   Identifier layoutId, boolean viaCommand) {
        VoyageLayout layout = SceneDataLoader.require(layoutId);
        // 布局与雾表整份快照进这一局（审查 2026-10-07 C6）：之后 /reload 拿掉它，这一局照旧用开局那一份
        FogTable fog = SceneDataLoader.fogTableOf(layout);
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
        List<ServerPlayerEntity> crossed = new ArrayList<>();
        // 过魔镜时就托管过的人（ADR-0083：穿越即托管，ADR-0054 D12 第 3 条）：开局不再托管一次，散局回北辰号
        List<ServerPlayerEntity> boarded = new ArrayList<>();
        try {
            // ❗摆场景也在 try 里（审查 2026-10-07 U9）：放船抛了（模板不在 · 锚点下没水），走廊已经强加载、脚印已经记下 ——
            //   原先它在 try 外，没人调 cleanupScene，走廊一直强加载到下一次开局或重启
            prepareScene(sea, component, layout);
            for (ServerPlayerEntity player : humans) {
                if (mirrorEscrow(player).isPresent()) {
                    boarded.add(player);
                    continue;
                }
                escrow(component, player);
                crossed.add(player);
            }
            if (!crossed.isEmpty()) {
                checkpoint(sea, true, "进入雾海前的物品托管");
            }
            Vec3d bow = layout.boat().bow();
            for (ServerPlayerEntity player : humans) {
                player.stopRiding();
                player.teleport(sea, bow.x, bow.y + 1.0, bow.z, layout.ridersFacing(), 0f);
            }
            component.setLayout(layout, fog);
            if (selectedRoster == null) {
                GameFlow.start(sea, players, humans, reservedForDummies, layout, viaCommand);
            } else {
                GameFlow.start(sea, players, humans, reservedForDummies, layout, selectedRoster, viaCommand);
            }
            LOGGER.info("雾海：{} 名玩家已托管物品并进入 {}（布局 {}）", crossed.size(), layout.dimension(), layout.id());
        } catch (RuntimeException failure) {
            Gulls.clear(sea, component);
            Backdrop.clear(sea, component);
            Seats.clear(sea, component);
            // 名牌队伍在开局那一次同步里就建好了（审查 2026-10-07 U9）：不收的话，下一局他分到别的角色时
            //   mayJoin 不许换队，整局名牌挂着上一局的角色名和数值
            Nameplates.clear(sea);
            if (component.session().isPresent()) {
                component.end();
                GameComponents.sync(sea);
            }
            for (ServerPlayerEntity player : crossed) {
                restore(component, player);
            }
            for (ServerPlayerEntity player : boarded) {
                mirrorEscrow(player).ifPresent(escrow -> toLiner(player, escrow));
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
        component.clearLayoutId();
    }

    /*
     * 时刻与天气不再写给任何世界（ADR-0058 §4，2026-10-02）。此前写的是主世界那口钟与那场雨 ——
     * 主世界之外的 ServerWorld 拿到的是 UnmodifiableLevelProperties，写进去是空操作（ADR-0034 §10.4 实测），
     * 于是对局每天把全服的钟拨到当日天候那一行，大厅里的人也跟着变天。现在由 PlayerSky 按人发天色、客户端照画，
     * 主世界的钟与雨归主世界自己。
     */

    private static void escrow(GameComponent component, ServerPlayerEntity player) {
        requireNoEscrow(player);
        Vec3d returnPoint = player.getPos();
        put(component, player, returnPoint, player.getYaw(), player.getPitch(), Optional.empty());
    }

    /**
     * 过魔镜托管（ADR-0083）：背包与身体记下、背包清空；回程从这面镜子出来（{@code back} 是镜子前那一格、{@code yaw} 背对镜子）。
     *
     * @throws IllegalStateException 已有一份未恢复的托管
     */
    public static void escrowAtMirror(ServerWorld liner, ServerPlayerEntity player, GameComponent.MirrorAt mirror, Vec3d back, float yaw) {
        requireNoEscrow(player);
        put(GameComponents.of(liner), player, back, yaw, 0f, Optional.of(mirror));
        checkpoint(liner, true, "过魔镜时的物品托管");
    }

    private static void requireNoEscrow(ServerPlayerEntity player) {
        for (ServerWorld world : player.server.getWorlds()) {
            if (GameComponents.of(world).hasVoyageEscrow(player.getUuid())) {
                throw new IllegalStateException(player.getGameProfile().getName() + " 已有一份未恢复的雾海托管");
            }
        }
    }

    private static void put(GameComponent component, ServerPlayerEntity player, Vec3d back, float yaw, float pitch,
                            Optional<GameComponent.MirrorAt> mirror) {
        NbtList inventory = player.getInventory().writeNbt(new NbtList());
        component.putVoyageEscrow(new GameComponent.VoyageEscrow(player.getUuid(),
                player.getWorld().getRegistryKey().getValue().toString(),
                back.x, back.y, back.z, yaw, pitch, inventory, Optional.of(PlayerBodies.capture(player)), mirror));
        player.getInventory().clear();
        player.getInventory().markDirty();
        player.playerScreenHandler.sendContentUpdates();
    }

    /** 这个人有没有一份过魔镜的托管（在北辰号上，或在这一局里）。 */
    public static Optional<GameComponent.VoyageEscrow> mirrorEscrow(ServerPlayerEntity player) {
        for (ServerWorld world : player.server.getWorlds()) {
            Optional<GameComponent.VoyageEscrow> escrow = GameComponents.of(world).voyageEscrow(player.getUuid());
            if (escrow.isPresent()) {
                return escrow.filter(GameComponent.VoyageEscrow::viaMirror);
            }
        }
        return Optional.empty();
    }

    /**
     * 送回北辰号的落脚点（大楼梯平台、船上那面镜子前）：冒险模式、满血满饱（船上不受伤，{@link PlayerBodies}）。
     * 北辰号不在（清单读不出来、地图数据里没有）时没有船可回 —— 直接把托管还了送回家，不让人卡在海上。
     */
    public static void toLiner(ServerPlayerEntity player, GameComponent.VoyageEscrow escrow) {
        Optional<LinerShip.Manifest> manifest = LinerShip.manifest();
        ServerWorld liner = manifest.map(m -> player.server.getWorld(m.dimension())).orElse(null);
        if (liner == null) {
            LOGGER.warn("北辰号不在（{}）：{} 的托管直接还了、送回家", LinerShip.unavailableReason().orElse("?"),
                    player.getGameProfile().getName());
            goHome(player);
            return;
        }
        Vec3d at = manifest.get().arrival();
        player.stopRiding();
        player.teleport(liner, at.x, at.y, at.z, manifest.get().arrivalYaw(), 0f);
        PlayerBodies.aboard(player, escrow.body());
    }

    /** 回程的去向（{@link #goHome}）：镜子前 · 自己的重生点 · 世界出生点；后两种是那面镜子不在了。 */
    public enum Home {
        MIRROR, RESPAWN, WORLD_SPAWN, NO_ESCROW
    }

    /**
     * 从船上那面镜子回家（ADR-0065 §1 第 5 条 · 样张页第 8、9 步）：背包与身体还回来；那面镜子还在，回到镜子前（托管时记的那一格、背对镜子），
     * 不在了送自己的重生点（床或重生锚），没有才送世界出生点。查镜子之前先把那一格所在的区块载进来 —— 没载入的区块什么都查不到，
     * 会把「查不到」当成「不在了」。
     */
    public static Home goHome(ServerPlayerEntity player) {
        for (ServerWorld world : player.server.getWorlds()) {
            GameComponent component = GameComponents.of(world);
            Optional<GameComponent.VoyageEscrow> found = component.voyageEscrow(player.getUuid());
            if (found.isEmpty()) {
                continue;
            }
            GameComponent.VoyageEscrow escrow = found.get();
            Home home = Home.MIRROR;
            TeleportTarget elsewhere = null;
            if (escrow.viaMirror()) {
                GameComponent.MirrorAt at = escrow.mirror().orElseThrow();
                Identifier dim = Identifier.tryParse(at.dimension());
                ServerWorld there = dim == null ? null : player.server.getWorld(RegistryKey.of(RegistryKeys.WORLD, dim));
                boolean standing = there != null && there.getChunk(at.pos()) != null
                        && there.getBlockState(at.pos()).isOf(io.github.heavyseasmc.mod.world.liner.LinerProps.MIRROR);
                if (!standing) {
                    elsewhere = player.getRespawnTarget(true, TeleportTarget.NO_OP);
                    home = elsewhere.missingRespawnBlock() || player.getSpawnPointPosition() == null ? Home.WORLD_SPAWN : Home.RESPAWN;
                }
            }
            TeleportTarget redirect = elsewhere;
            if (restore(component, player, redirect)) {
                checkpoint(world, false, "从魔镜回家的物品恢复");
                return home;
            }
            throw new IllegalStateException("回家失败：" + player.getGameProfile().getName() + " 的托管没能还回来（日志里有原因），托管照旧留着");
        }
        TeleportTarget target = player.getRespawnTarget(true, TeleportTarget.NO_OP);
        player.stopRiding();
        player.teleportTo(target);
        return Home.NO_ESCROW;
    }

    /**
     * Normal end: online players return immediately; offline records remain until their next login.
     * 过魔镜托管的（ADR-0083）：刚才在这一局里的人回北辰号的落脚点、托管照旧留着（从船上那面镜子回家时才还）；
     * 开局时托管的老路（{@code /seas start}）照旧当场还、送回原处。
     */
    public static void restoreAll(ServerWorld sea, GameComponent component) {
        int restored = 0;
        Set<UUID> voyage = component.endedVoyagePlayers();
        for (UUID id : new ArrayList<>(component.voyageEscrowPlayers())) {
            ServerPlayerEntity player = sea.getServer().getPlayerManager().getPlayer(id);
            if (player == null) {
                continue;
            }
            GameComponent.VoyageEscrow escrow = component.voyageEscrow(id).orElse(null);
            if (escrow != null && escrow.viaMirror()) {
                if (voyage.contains(id)) {
                    toLiner(player, escrow);
                }
                continue;
            }
            if (restore(component, player)) {
                restored++;
            }
        }
        cleanupScene(sea, component);
        if (restored > 0) {
            checkpoint(sea, false, "雾海结束后的物品恢复");
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
        // The interrupted voyage may have used a custom dimension, not the current default layout.
        for (ServerWorld sea : player.server.getWorlds()) {
            GameComponent component = GameComponents.of(sea);
            if (!component.hasVoyageEscrow(player.getUuid())) {
                continue;
            }
            if (component.belongsToActiveVoyage(player.getUuid())) {
                VoyageLayout layout = component.layout().orElseGet(SceneDataLoader::defaultLayout);   // 开局快照（审查 C6）
                if (!player.getWorld().getRegistryKey().equals(sea.getRegistryKey())) {
                    Vec3d bow = layout.boat().bow();
                    player.teleport(sea, bow.x, bow.y + 1.0, bow.z, layout.ridersFacing(), 0f);
                }
                GameComponents.sync(sea);
            } else if (component.voyageEscrow(player.getUuid()).map(GameComponent.VoyageEscrow::viaMirror).orElse(false)) {
                // 过了魔镜、不在对局里：人该在北辰号上。不在船上（崩在对局里、存档里还停在对局那条艇边）就送回落脚点；在船上就不动
                GameComponent.VoyageEscrow escrow = component.voyageEscrow(player.getUuid()).orElseThrow();
                if (!onLiner(player)) {
                    toLiner(player, escrow);
                    LOGGER.info("雾海恢复：{} 过了魔镜、不在对局里，送回北辰号", player.getGameProfile().getName());
                }
            } else if (restore(component, player)) {
                checkpoint(sea, false, "雾海异常中断恢复");
                player.sendMessage(Text.translatable("heavyseas.mist_sea.recovered"), true);
                LOGGER.info("雾海恢复：{} 的物品与返回位置已恢复", player.getGameProfile().getName());
            }
            return;
        }
    }

    /** 人在北辰号那一块里（船体外扩一圈，含舷外的艇）。 */
    static boolean onLiner(ServerPlayerEntity player) {
        return LinerShip.manifest().map(m -> player.getWorld().getRegistryKey().equals(m.dimension())
                && Box.from(m.hullBox()).expand(8).contains(player.getPos())).orElse(false);
    }

    private static boolean restore(GameComponent component, ServerPlayerEntity player) {
        return restore(component, player, null);
    }

    /** @param elsewhere 不回托管里记的那个位置、改送这里（回程时家里那面镜子不在了）；{@code null} = 照托管里记的 */
    private static boolean restore(GameComponent component, ServerPlayerEntity player, TeleportTarget elsewhere) {
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
            if (elsewhere != null) {
                player.teleportTo(elsewhere);
            } else {
                player.teleport(destination, escrow.x(), escrow.y(), escrow.z(), escrow.yaw(), escrow.pitch());
            }
            escrow.body().ifPresent(body -> PlayerBodies.restore(player, body));
            return true;
        } catch (RuntimeException failure) {
            // Never consume the only recovery copy on a partial restore.
            component.putVoyageEscrow(escrow);
            LOGGER.error("雾海恢复失败：{}", player.getGameProfile().getName(), failure);
            return false;
        }
    }

    /**
     * 天候到世界（ADR-0034 §5.1.5）：每天翻出天候时调一次，{@code /seas debug weather now} 也调。
     *
     * <p>这里只记一行：昼夜与雨雷由 {@link PlayerSky} 按人发给这一局里的人，各自的客户端照画（ADR-0058 §4）——
     * 不再拨主世界的钟。雾本身也不在这里：客户端按投影里的 {@code HudView.Fog} 渲染（§5.1.2）。
     */
    public static void applyWeather(ServerWorld world, GameComponent component, String weatherId) {
        FogTable.Entry entry = component.fogFor(weatherId);   // 开局快照（审查 C6）
        // 与语言无关的一行（脚本按「天候到世界：<id>」认今天是什么天）。❗它只是参数回显：
        // 画没画成要看客户端那一行「天色：收到 …」与截图（sky_test.py），不看这里。
        LOGGER.info("天候到世界：{} · 雨 {} · 雷 {} · 时刻 {} · 雾 {}/{} · 按人画天色（主世界的钟不动）", weatherId,
                entry.rain(), entry.thunder(), entry.time(), entry.start(), entry.end());
    }

    /**
     * 托管的存档检查点：托管记录所在那个世界的持久状态（对局组件在里面）+ 全部玩家的存档，按方向排先后（审查 2026-10-07 P1）。
     *
     * <p>❗原先是 {@code server.save(false, true, false)}：主线程上把<b>所有维度</b>全量刷盘、等写完（北辰号那一维 32 万格，
     * 每过一次魔镜全服卡一下）—— 而且<b>不存玩家</b>：物品还回去、托管删了，玩家文件里还是空背包，下一次自动存档之前崩服就丢背包。
     * 现在只写这两样，区块不刷。
     *
     * @param escrowing {@code true} = 托管（背包清空、记录写进世界）：先存世界再存玩家 —— 两次之间崩了，记录在、背包也在，恢复时照记录覆盖，不丢不重；
     *                  {@code false} = 归还（背包还回、记录删掉）：先存玩家再存世界 —— 反过来的话，两次之间崩了，记录已删、玩家文件里还是空背包
     */
    static void checkpoint(ServerWorld escrowWorld, boolean escrowing, String reason) {
        MinecraftServer server = escrowWorld.getServer();
        try {
            if (escrowing) {
                escrowWorld.getPersistentStateManager().save();
                server.getPlayerManager().saveAllPlayerData();
            } else {
                server.getPlayerManager().saveAllPlayerData();
                escrowWorld.getPersistentStateManager().save();
            }
            LOGGER.info("雾海存档检查点：{}", reason);
        } catch (RuntimeException failure) {
            LOGGER.warn("雾海存档检查点没写成：{}（下一次自动存档会再写）", reason, failure);
        }
    }
}
