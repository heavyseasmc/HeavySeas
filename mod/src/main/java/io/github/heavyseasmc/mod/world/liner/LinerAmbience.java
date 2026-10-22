package io.github.heavyseasmc.mod.world.liner;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 家具的小反馈（ADR-0109 ②）：不用新声音、不改世界，只给眼睛看的那几样 —— 壁炉亮着时冒火星与烟、落地钟右键报时刻、
 * 龙头 / 莲蓬右键开、再右键关（服务端发水滴，人人看得见）。
 * 声音（壁炉噼啪、整点敲钟）归音频会话，这里不接游戏自带的声音（ADR-0106 §2.6 同一条）。
 */
public final class LinerAmbience {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private LinerAmbience() {
    }

    /**
     * 壁炉炉膛里的那一格（正中下面那一块，{@link LinerProp.Part#WIDE_MID}）在那一格模型里的几个点（像素，正面朝北）：
     * 炭床 x 2.75–13.25 · z 11.5–14.5 · 顶 y 5，火苗高到 y 10，烟道口在 y 16（docs 的 liner_props.fireplace，整件坐标减 16）。
     */
    private static final double COALS_X0 = 3.5;
    private static final double COALS_X1 = 12.5;
    private static final double COALS_Z0 = 11.75;
    private static final double COALS_Z1 = 14.25;
    private static final double COALS_TOP = 5.0;
    private static final double FLAME_TOP = 9.0;

    /** 壁炉亮着时（客户端每 tick 随机挑格子调一次，同游戏自带的营火）：两三点火星往上蹿、偶尔一缕烟往烟道里飘。 */
    static void fireplace(BlockState state, World world, BlockPos pos, Random random) {
        Direction facing = state.get(LinerProp.FACING);
        int ry = LinerConnect.index(facing) * 90;
        for (int i = 0; i < 2; i++) {
            double x = COALS_X0 + random.nextDouble() * (COALS_X1 - COALS_X0);
            double z = COALS_Z0 + random.nextDouble() * (COALS_Z1 - COALS_Z0);
            double y = COALS_TOP + random.nextDouble() * (FLAME_TOP - COALS_TOP);
            double[] xz = LinerLooks.rotateY(x, z, ry);
            world.addParticle(ParticleTypes.SMALL_FLAME, pos.getX() + xz[0] / 16, pos.getY() + y / 16, pos.getZ() + xz[1] / 16,
                    0.0, 0.01 + random.nextDouble() * 0.01, 0.0);
        }
        if (random.nextInt(3) == 0) {
            double[] xz = LinerLooks.rotateY(8.0, 13.0, ry);
            world.addParticle(ParticleTypes.SMOKE, pos.getX() + xz[0] / 16, pos.getY() + FLAME_TOP / 16, pos.getZ() + xz[1] / 16,
                    0.0, 0.04, 0.0);
        }
        // 不再偶尔迸一颗游戏自带的熔岩火花：2026-10-17 实拍，它在画面里是一整块大橙方块，比炉膛里别的东西都大（ADR-0109 §4）
    }

    /**
     * 热室大理石台上的蒸汽（2026-10-22）：台面上方是空的才冒；三回里一回，一缕白汽慢慢往上飘（游戏自带的白烟粒子，速度压得很低）。
     */
    static void steam(World world, BlockPos pos, Random random) {
        if (!world.getBlockState(pos.up()).isAir() || random.nextInt(3) != 0) {
            return;
        }
        // 第一版用 CLOUD：实拍是一团团大白块，像噪点（2026-10-22）—— 换成小一号的白烟
        world.addParticle(ParticleTypes.WHITE_SMOKE, pos.getX() + 0.2 + random.nextDouble() * 0.6, pos.getY() + 1.05,
                pos.getZ() + 0.2 + random.nextDouble() * 0.6, 0.0, 0.01 + random.nextDouble() * 0.01, 0.0);
    }

    /**
     * 落地钟右键：动作栏只写此刻的时刻（如「23:40」）—— 界面文案只写状态（用户 10-01「揭晓阶段就行了」那一条）。
     * 雾海这一维读主世界的钟（ADR-0054 §1），对局里天候牌拨钟时这里照实跟着走。
     */
    static ActionResult tellTime(World world, PlayerEntity player) {
        if (!world.isClient) {
            player.sendMessage(Text.literal(clock(world.getTimeOfDay())), true);
        }
        return ActionResult.success(world.isClient);
    }

    /**
     * 柜子开 / 合（ADR-0109 §2）之后调一次：发游戏事件（开 / 关，同游戏自带的箱子、门 —— 幽匿感测体之类认得出），不放声音。
     * <b>声音的接口点</b>：柜门 · 抽屉 · 皮箱盖的开合声归音频会话（ADR-0106 §2.6：新声音不用游戏自带的），接上时在这里按 {@code kind} 挑。
     */
    static void cabinet(World world, BlockPos pos, PlayerEntity player, LinerProp.Kind kind, boolean open) {
        world.emitGameEvent(player, open ? net.minecraft.world.event.GameEvent.BLOCK_OPEN : net.minecraft.world.event.GameEvent.BLOCK_CLOSE, pos);
    }

    /**
     * 正在出水的那几件：键是出水那一块所在的格（一件一个键），值是出水点。只活在内存里，起服 / 换船之后就没了（都关着），不需要存。
     * 开关（用户 2026-10-17「做成开关」；此前是出 5 秒水、再点从头计时 —— 标注「花洒的水只能关不能开」）：开着一直出，再点一下关。
     */
    private static final Map<WaterKey, Running> RUNNING = new HashMap<>();

    private record WaterKey(RegistryKey<World> world, BlockPos cell) {
    }

    private static final class Running {
        final LinerProp block;
        final List<double[]> points;
        final List<Double> spreads;

        Running(LinerProp block, List<double[]> points, List<Double> spreads) {
            this.block = block;
            this.points = points;
            this.spreads = spreads;
        }
    }

    /** 服务端每 tick 发一次水滴（发给附近的人，人人看得见）。HeavySeasMod 初始化时挂上。 */
    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(LinerAmbience::tickWater);
    }

    /** 右键出水的件（{@link LinerProp.Rules#spouts}）：关着就开、开着就关（点中哪一格都一样，整件一个开关）。 */
    static ActionResult runWater(LinerProp block, BlockState state, World world, BlockPos pos) {
        if (!(world instanceof ServerWorld sw)) {
            return ActionResult.SUCCESS;
        }
        Direction facing = state.get(LinerProp.FACING);
        LinerProp.Part here = block.partOf(state);
        List<LinerProp.Rules.Spout> spouts = LinerProp.Rules.spouts(block.spec().kind());
        BlockPos cell = (here == null ? pos : LinerProp.Rules.offset(pos, here, spouts.get(0).cell(), facing)).toImmutable();
        WaterKey key = new WaterKey(sw.getRegistryKey(), cell);
        boolean on = RUNNING.remove(key) == null;
        LOGGER.info("出水：{} {}（{}）", block.spec().kind(), on ? "开" : "关", cell.toShortString());   // 实测按这一行认开关（水滴本身 RCON 看不见）
        if (on) {
            List<double[]> points = new ArrayList<>();
            List<Double> spreads = new ArrayList<>();
            for (LinerProp.Rules.Spout s : spouts) {
                BlockPos c = here == null ? pos : LinerProp.Rules.offset(pos, here, s.cell(), facing);
                double[] xz = LinerLooks.rotateY(s.x(), s.z(), LinerConnect.index(facing) * 90);
                points.add(new double[]{c.getX() + xz[0] / 16, c.getY() + s.y() / 16, c.getZ() + xz[1] / 16});
                spreads.add(s.spread() / 16);
            }
            RUNNING.put(key, new Running(block, points, spreads));
        }
        return ActionResult.SUCCESS;
    }

    /**
     * 这一件此刻在不在出水（服务端 GameTest 用，2026-10-19）：pos 是这一件的任意一格；那一格已经不是这一件（被拆了）就按出水点那一格认。
     * 认法与 {@link #runWater} 同一套（键是第一个出水点所在的格）。
     */
    static boolean running(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        BlockPos cell = pos;
        if (state.getBlock() instanceof LinerProp block && !LinerProp.Rules.spouts(block.spec().kind()).isEmpty()) {
            LinerProp.Part here = block.partOf(state);
            if (here != null) {
                cell = LinerProp.Rules.offset(pos, here, LinerProp.Rules.spouts(block.spec().kind()).get(0).cell(), state.get(LinerProp.FACING));
            }
        }
        return RUNNING.containsKey(new WaterKey(world.getRegistryKey(), cell.toImmutable()));
    }

    private static void tickWater(ServerWorld world) {
        if (RUNNING.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<WaterKey, Running>> it = RUNNING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<WaterKey, Running> e = it.next();
            if (!e.getKey().world().equals(world.getRegistryKey())) {
                continue;
            }
            Running r = e.getValue();
            if (!world.getBlockState(e.getKey().cell()).isOf(r.block)) {     // 那件被拆了 / 换船了：关掉
                it.remove();
                continue;
            }
            for (int i = 0; i < r.points.size(); i++) {
                double[] p = r.points.get(i);
                double s = r.spreads.get(i);
                int n = s > 0 ? 4 : 1;
                world.spawnParticles(ParticleTypes.FALLING_WATER, p[0], p[1], p[2], n, s, 0.0, s, 0.0);
            }
        }
    }

    /** 游戏刻 → 「时:分」：刻 0 是早上 6 点，一天 24000 刻。 */
    static String clock(long timeOfDay) {
        long t = Math.floorMod(timeOfDay, 24000L);
        long hours = (t / 1000 + 6) % 24;
        long minutes = (t % 1000) * 60 / 1000;
        return String.format(Locale.ROOT, "%02d:%02d", hours, minutes);
    }
}
