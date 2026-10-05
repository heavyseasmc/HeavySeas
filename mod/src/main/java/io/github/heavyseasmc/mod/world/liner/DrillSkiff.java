package io.github.heavyseasmc.mod.world.liner;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.data.GameDataLoader;
import io.github.heavyseasmc.mod.data.SceneDataLoader;
import io.github.heavyseasmc.mod.data.VoyageLayout;
import io.github.heavyseasmc.mod.net.RosterConfigS2C;
import io.github.heavyseasmc.mod.net.StartVoyageC2S;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.world.MistSea;
import io.github.heavyseasmc.mod.world.SeatEntity;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * 北辰号艇甲板上的演习艇（1 号艇）：坐进去就是报名，人齐了坐在艇里敲艇旁那口钟（或再右键艇），房主定阵容、开局（ADR-0054 D3 · ADR-0083 ·
 * ADR-0084 第三轮）。
 *
 * <p>接替主世界的大厅游轮（{@code LobbyBoat}，用户 2026-10-03 定拿掉）：同一套座位实体（大厅座位 {@link SeatEntity#lobby()}，
 * 锚点 = 这条艇的结构摆在哪一格）、同一个阵容面板（{@link RosterConfigS2C} / {@link StartVoyageC2S}，锚点就是那一格）。
 * 不同的两处：<b>只带坐在艇里的人</b>（大厅游轮还把铃边 16 格内的人拉进去当观众 —— 用户定：船上闲逛的人不拉）；
 * 座位按对局那条艇的规矩排（航程布局的 {@code hull.anchor} 是船头那一座、{@code seat_spacing} 往船尾），转到这条艇挂的方向。
 */
public final class DrillSkiff {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);
    public static final int SEATS = 8;
    /** 座位实体在那一格里的高度（与对局那条艇的船头 {@code bow} 的 y 小数同一个数：坐在横座板上）。 */
    private static final double SEAT_HEIGHT = 0.15;
    /**
     * 开局的钟认哪一口（{@link #ringBell}）：演习艇的结构占的那一块（{@link Rig#area}，模板整块 11 × 9 × 25 转过去）往外放宽这么多格之内的钟。
     * 钟的位置不在清单里，由生成器（docs 的 liner_props_deck.gear_blocks）按演习艇摆在艏柱那一头里侧；这里不照那条规则再算一遍
     * （两份规则会各走各的），而是只认「挨着演习艇的钟」—— 生成器那一侧的判据（deck_shots.check）核对它摆的钟每一格都在这个范围里，
     * 并且读的就是这个常数。钟的最下面一层落在甲板上，比艇的那一块低一格，所以至少要 1；取 2 留一格余量。
     */
    static final int BELL_MARGIN = 2;

    private DrillSkiff() {
    }

    /**
     * 这一次运行里演习艇在哪：世界 · 锚点（艇的结构摆在哪一格，座位实体认它）· 八个座位 · 人朝哪坐 · 艇占的那一块 · 起身落在哪。
     */
    public record Rig(ServerWorld world, BlockPos anchor, List<Vec3d> seats, float seatYaw, BlockBox area, Vec3d landing) {
    }

    public static Optional<Rig> rig(MinecraftServer server) {
        Optional<LinerShip.Manifest> manifest = LinerShip.manifest();
        if (manifest.isEmpty() || manifest.get().drill().isEmpty()) {
            return Optional.empty();
        }
        LinerShip.Manifest m = manifest.get();
        ServerWorld world = server.getWorld(m.dimension());
        VoyageLayout layout;
        try {
            layout = SceneDataLoader.defaultLayout();
        } catch (RuntimeException notLoaded) {
            return Optional.empty();
        }
        if (world == null || layout.hull().isEmpty()) {
            return Optional.empty();
        }
        Optional<StructureTemplate> template = world.getStructureTemplateManager().getTemplate(LinerShip.SKIFF);
        if (template.isEmpty()) {
            return Optional.empty();
        }
        LinerShip.Skiff k = m.drill().get();
        var placement = LinerShip.skiffPlacement(k);
        BlockPos anchorLocal = new BlockPos(layout.hull().get().anchor());
        int step = (int) Math.round(layout.boat().seatSpacing());
        List<Vec3d> seats = new ArrayList<>();
        for (int i = 0; i < SEATS; i++) {
            // 模板自己的方向里（对局那条艇 yaw 180 = 不转）：船头那一座在锚点，往船尾是 −z
            BlockPos cell = StructureTemplate.transform(placement, anchorLocal.add(0, 0, -i * step)).add(k.pos());
            seats.add(new Vec3d(cell.getX() + 0.5, cell.getY() + SEAT_HEIGHT, cell.getZ() + 0.5));
        }
        // 对局里人朝船头坐（yaw 180 的布局，乘客面朝 +Z = 南）；挂起来的艇转了多少，人跟着转多少
        float seatYaw = k.rotation().rotate(Direction.SOUTH).asRotation();
        BlockBox area = template.get().calculateBoundingBox(placement, k.pos());
        // 起身落在艇甲板上、艇里侧两格（艇骑在舷墙上：往船中线那边走两格是甲板），x 取艇正中
        int inward = Integer.signum((m.origin().getZ() + m.size().getZ() / 2) - k.pos().getZ());
        Vec3d mid = seats.get(SEATS / 2);
        double deckY = k.pos().getY() - 1;
        Vec3d landing = new Vec3d(mid.x, deckY, inward > 0 ? area.getMaxZ() + 2.5 : area.getMinZ() - 1.5);
        return Optional.of(new Rig(world, k.pos(), List.copyOf(seats), seatYaw, area, landing));
    }

    /**
     * 右键演习艇的任何一格（{@code UseBlockCallback}）：没坐下就坐一个空座；坐在这条艇里就开阵容面板（6–8 人）。
     * 艇的那一块里也有别的东西 —— 开局的钟、船尾那一架吊艇架（模板后 5 排是空的）：这些道具（{@link LinerProp}）放过去，
     * 交给它们自己的右键（{@code UseBlockCallback} 先于方块的 {@code onUse}，不放过钟就永远敲不响）。
     */
    public static ActionResult useBlock(PlayerEntity rawPlayer, World rawWorld, BlockPos pos) {
        if (!(rawWorld instanceof ServerWorld world) || !(rawPlayer instanceof ServerPlayerEntity player)) {
            return ActionResult.PASS;
        }
        Optional<Rig> found = rig(world.getServer());
        if (found.isEmpty() || found.get().world() != world || !found.get().area().contains(pos)
                || world.getBlockState(pos).getBlock() instanceof LinerProp) {
            return ActionResult.PASS;
        }
        Rig rig = found.get();
        ActionResult refused = refuse(player, world);
        if (refused != null) {
            return refused;
        }
        List<SeatEntity> seats = ensureSeats(rig);
        if (seatedHere(player, rig)) {
            return openRoster(player, rig, seats);
        }
        SeatEntity open = seats.stream().filter(seat -> !seat.hasPassengers()).findFirst().orElse(null);
        if (open == null) {
            player.sendMessage(Text.translatable("heavyseas.lobby.full"), true);
            return ActionResult.FAIL;
        }
        player.stopRiding();
        player.teleport(world, open.getX(), open.getY(), open.getZ(), open.getYaw(), 0f);
        if (!player.startRiding(open, true)) {
            return ActionResult.FAIL;
        }
        player.sendMessage(Text.translatable("heavyseas.lobby.registered", registered(seats).size()), true);
        return ActionResult.SUCCESS;
    }

    /**
     * 敲开局的钟（{@link LinerProp} 的 {@code DRILL_BELL} 右键）：都响一声；是演习艇旁那一口（{@link #nearDrillSkiff}）时 ——
     * 敲钟的人坐在演习艇里，与「坐在艇里再右键艇」同一条路（{@link #openRoster}：6–8 人开阵容面板，不够人报已入座几人）；
     * 没坐着就提示一行「先坐进演习艇」。别处的钟（玩家自己摆的）只响，不做别的。
     */
    public static ActionResult ringBell(World rawWorld, BlockPos pos, PlayerEntity rawPlayer) {
        if (!(rawWorld instanceof ServerWorld world) || !(rawPlayer instanceof ServerPlayerEntity player)) {
            return ActionResult.SUCCESS;                                            // 客户端：挥一下手，钟声由服务端发
        }
        world.playSound(null, pos, SoundEvents.BLOCK_BELL_USE, SoundCategory.BLOCKS, 1f, 1f);
        // 每一下都报走的是哪一条（只响 · 拦下 · 没坐着 · 开阵容）：四条路在屏幕上只差一行字，回归脚本认这一行
        String who = player.getGameProfile().getName();
        Optional<Rig> found = rig(world.getServer());
        if (found.isEmpty() || found.get().world() != world || !nearDrillSkiff(found.get().area(), pos)) {
            LOGGER.info("开航钟：{} 敲了 {} · 不是演习艇旁那一口，只响", who, pos.toShortString());
            return ActionResult.SUCCESS;
        }
        Rig rig = found.get();
        ActionResult refused = refuse(player, world);
        if (refused != null) {
            LOGGER.info("开航钟：{} 敲了 · 拦下（旁观 / 已有一局）", who);
            return refused;
        }
        List<SeatEntity> seats = ensureSeats(rig);
        if (!seatedHere(player, rig)) {
            LOGGER.info("开航钟：{} 敲了 · 没坐在演习艇里", who);
            player.sendMessage(Text.translatable("heavyseas.lobby.sit_first"), true);
            return ActionResult.SUCCESS;
        }
        LOGGER.info("开航钟：{} 敲了 · 坐在艇里 → 阵容（已入座 {} 人）", who, registered(seats).size());
        return openRoster(player, rig, seats);
    }

    /** 这一格离演习艇的那一块不超过 {@link #BELL_MARGIN} 格（钟认不认，见常数的说明）。 */
    static boolean nearDrillSkiff(BlockBox area, BlockPos pos) {
        return pos.getX() >= area.getMinX() - BELL_MARGIN && pos.getX() <= area.getMaxX() + BELL_MARGIN
                && pos.getY() >= area.getMinY() - BELL_MARGIN && pos.getY() <= area.getMaxY() + BELL_MARGIN
                && pos.getZ() >= area.getMinZ() - BELL_MARGIN && pos.getZ() <= area.getMaxZ() + BELL_MARGIN;
    }

    /** 右键艇与敲钟共用的拦法：旁观者不理；已有一局在进行就说一声。→ 拦下来的结果，{@code null} = 放行。 */
    private static ActionResult refuse(ServerPlayerEntity player, ServerWorld world) {
        if (player.isSpectator()) {
            return ActionResult.FAIL;
        }
        if (GameComponents.of(world).session().isPresent()) {
            player.sendMessage(Text.translatable("heavyseas.command.already_running"), true);
            return ActionResult.FAIL;
        }
        return null;
    }

    /** 坐在演习艇里的人要开航（再右键艇 · 敲钟）：6–8 人就给他开阵容面板，不够 / 太多就报已入座几人。 */
    private static ActionResult openRoster(ServerPlayerEntity player, Rig rig, List<SeatEntity> seats) {
        List<ServerPlayerEntity> registered = registered(seats);
        if (registered.size() < 6 || registered.size() > 8) {
            player.sendMessage(Text.translatable("heavyseas.lobby.need_players", registered.size()), true);
            return ActionResult.FAIL;
        }
        ServerPlayNetworking.send(player, RosterConfigS2C.from(rig.anchor().asLong(), registered.size(),
                GameDataLoader.require().roster()));
        return ActionResult.SUCCESS;
    }

    /** 阵容面板点了「开航」：重新核对艇、座位、人数与阵容，再敲钟开局。只带坐在艇里的人（用户 2026-10-03 定）。 */
    public static void launch(ServerPlayerEntity player, StartVoyageC2S request) {
        if (player.isSpectator()) {
            return;
        }
        Optional<Rig> found = rig(player.server);
        if (found.isEmpty() || found.get().anchor().asLong() != request.anchor() || !seatedHere(player, found.get())) {
            return;
        }
        Rig rig = found.get();
        List<ServerPlayerEntity> registered = registered(ensureSeats(rig));
        if (registered.size() < 6 || registered.size() > 8) {
            player.sendMessage(Text.translatable("heavyseas.lobby.need_players", registered.size()), true);
            return;
        }
        try {
            List<CharacterId> selected = request.characters().stream().map(CharacterId::of).toList();
            GameDataLoader.require().roster().select(selected);
            if (selected.size() != registered.size()) {
                throw new IllegalArgumentException("阵容人数必须与已报名人数一致");
            }
            ServerWorld sea = MistSea.world(player.server);
            if (sea == null || GameComponents.of(sea).session().isPresent()) {
                throw new IllegalStateException("雾海不可用或已有一局进行中");
            }
            rig.world().playSound(null, BlockPos.ofFloored(rig.seats().getFirst()), SoundEvents.BLOCK_BELL_USE, SoundCategory.BLOCKS, 1f, 1f);
            LOGGER.info("演习艇：{} 人入座，敲钟开局", registered.size());
            MistSea.startVoyage(player.server, registered.size(), registered, Set.of(), selected);
        } catch (RuntimeException failure) {
            player.sendMessage(Text.literal(String.valueOf(failure.getMessage())), true);
        }
    }

    /** 这个人坐在演习艇的座位上（{@code /seas start} 据此判「船上闲逛的人」与「报了名的人」）。 */
    public static boolean seated(ServerPlayerEntity player) {
        return rig(player.server).map(rig -> seatedHere(player, rig)).orElse(false);
    }

    /**
     * 演习艇里现在坐了几个人（海图桌的浮字与小铜船读它，ADR-0086 §2 第 5 条）；船没摆好时为空。
     * 只问在线的人坐在哪，不碰座位实体 —— 区块没加载时也答得出，也不会顺手把座位生出来。
     */
    public static OptionalInt seatedCount(MinecraftServer server) {
        Optional<Rig> found = rig(server);
        if (found.isEmpty()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of((int) server.getPlayerManager().getPlayerList().stream()
                .filter(p -> !p.isSpectator() && seatedHere(p, found.get())).count());
    }

    private static boolean seatedHere(ServerPlayerEntity player, Rig rig) {
        return player.getVehicle() instanceof SeatEntity seat && seat.lobby() && seat.lobbyAnchor().equals(rig.anchor());
    }

    /** 座位实体认不认得：锚点是这一次运行里演习艇的那一格（Seats 扫孤儿时问）。 */
    public static boolean isAnchor(ServerWorld world, BlockPos anchor) {
        return rig(world.getServer()).map(rig -> rig.world() == world && rig.anchor().equals(anchor)).orElse(false);
    }

    /** 从演习艇的座位上起身落在哪（{@link SeatEntity#updatePassengerForDismount}）。 */
    public static Optional<Vec3d> landing(ServerWorld world, BlockPos anchor) {
        return rig(world.getServer()).filter(rig -> rig.world() == world && rig.anchor().equals(anchor)).map(Rig::landing);
    }

    private static List<SeatEntity> ensureSeats(Rig rig) {
        List<SeatEntity> found = new ArrayList<>(rig.world().getEntitiesByType(SeatEntity.TYPE, Box.from(rig.area()).expand(2),
                seat -> seat.lobby() && seat.lobbyAnchor().equals(rig.anchor())));
        found.sort(Comparator.comparing((SeatEntity seat) -> !seat.hasPassengers()));
        boolean[] taken = new boolean[SEATS];
        List<SeatEntity> kept = new ArrayList<>();
        for (SeatEntity seat : found) {
            int i = seat.index();
            if (i < 0 || i >= SEATS || taken[i]) {
                seat.removeAllPassengers();
                seat.discard();
                continue;
            }
            taken[i] = true;
            Vec3d at = rig.seats().get(i);
            if (seat.squaredDistanceTo(at) > 0.0001 || seat.getYaw() != rig.seatYaw()) {
                seat.refreshPositionAndAngles(at.x, at.y, at.z, rig.seatYaw(), 0f);
            }
            kept.add(seat);
        }
        for (int i = 0; i < SEATS; i++) {
            if (taken[i]) {
                continue;
            }
            Vec3d at = rig.seats().get(i);
            SeatEntity seat = new SeatEntity(SeatEntity.TYPE, rig.world());
            seat.setIndex(i);
            seat.markLobby(rig.anchor());
            seat.refreshPositionAndAngles(at.x, at.y, at.z, rig.seatYaw(), 0f);
            if (rig.world().spawnEntity(seat)) {
                kept.add(seat);
            }
        }
        kept.sort(Comparator.comparingInt(SeatEntity::index));
        return kept;
    }

    private static List<ServerPlayerEntity> registered(List<SeatEntity> seats) {
        List<ServerPlayerEntity> players = new ArrayList<>();
        for (SeatEntity seat : seats) {
            for (Entity passenger : seat.getPassengerList()) {
                if (passenger instanceof ServerPlayerEntity p && !p.isSpectator()) {
                    players.add(p);
                }
            }
        }
        return players;
    }
}
