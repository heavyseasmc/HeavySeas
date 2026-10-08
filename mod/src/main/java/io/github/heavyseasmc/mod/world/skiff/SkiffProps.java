package io.github.heavyseasmc.mod.world.skiff;

import io.github.heavyseasmc.engine.weather.WeatherCard;
import io.github.heavyseasmc.engine.weather.WeatherEffect;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 艇上能摆弄、但不改胜负的东西（ADR-0057 §4）：灯油 · 帆 · 桨跟着划船动 · 舵跟着舵手动。
 *
 * <p>❗<b>引擎不知道它们。</b>这里只改世界里的方块状态、放声音、写一行日志；分数、口渴、行动、天候一概不碰，
 * 也不占回合、不弹界面。一局结束船体整个清回海水，下一局是新放的（灯油满、帆收着）。
 *
 * <p>纯规则（哪天掉油、什么天候能升帆、鼓几档、舵往哪边偏）在 {@link Rules} 里，单测不用起游戏就能测。
 */
public final class SkiffProps {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 同一个人两次摆弄之间至少隔这么久，免得连点刷声音、刷日志。 */
    static final long COOLDOWN_MS = 1000;
    /** 划一下桨、偏一下舵，各持续多少 tick 再回位。 */
    static final int STROKE_TICKS = 10;
    static final int TURN_TICKS = 60;
    /** 不在对局里时（北辰号上的演习艇），以点中的那一格为中心找帆与横桁的范围。 */
    private static final int SAIL_REACH = 7;

    private static final Map<UUID, Long> LAST_USE = new HashMap<>();

    private SkiffProps() {
    }

    /** 玩家离线后不保留这一秒的交互记录；只在服务端线程上调用。 */
    public static void forget(UUID player) {
        LAST_USE.remove(player);
    }

    /** 单人游戏换世界也会重用静态字段，停服时一起释放。 */
    public static void reset() {
        LAST_USE.clear();
    }

    static boolean acceptUse(UUID player, long now) {
        Long last = LAST_USE.get(player);
        if (last != null && now >= last && now - last < COOLDOWN_MS) {
            return false;
        }
        LAST_USE.put(player, now);
        return true;
    }

    /** 不碰 Minecraft 的那一半规则：单测直接测这里。 */
    public static final class Rules {

        /**
         * 暴风雨（划船的落海）、巨浪（打架的落海）那两天帆升不起来（大风浪收帆）。
         *
         * <p>❗按天候<b>效果</b>判、不按 id（审查 2026-10-07 Q4，与 90dde61 定的「按效果判」同一条）：数据包可以给任何 id 配这个效果，
         * 原先写死 {@code storm} / {@code huge_wave} 两个 id，换一套天候数据帆就不认了 —— 只影响样子，不报错。
         */
        public static final Set<WeatherEffect> TOO_ROUGH = Set.of(WeatherEffect.ROWERS_OVERBOARD, WeatherEffect.FIGHTERS_OVERBOARD);

        private Rules() {
        }

        /** 新的一天开始时灯油还剩几档：第一天是满的，之后每过一天掉一档，到 0 为止。 */
        public static int oilAtDayStart(int oilBefore, int turn) {
            return turn <= 1 ? oilBefore : Math.max(0, oilBefore - 1);
        }

        /** 升起来的帆鼓几档：无风（没有航海阶段）垂着（0）· 狂风（多翻一张航海牌）鼓满（2）· 其余（1）。{@code null} = 不在对局里。 */
        public static int bellyFor(WeatherEffect weather) {
            if (weather == null) {
                return 1;
            }
            return switch (weather) {
                case SKIP_NAVIGATION -> 0;
                case EXTRA_NAVIGATION -> 2;
                default -> 1;
            };
        }

        public static boolean mayRaise(WeatherEffect weather) {
            return weather == null || !TOO_ROUGH.contains(weather);
        }

        /** 舵手挑了哪张牌，舵就往哪边偏一下 —— 只是样子，按牌 id 定，同一张牌永远往同一边。 */
        public static SkiffTurn turnFor(String cardId) {
            return Math.floorMod(cardId == null ? 0 : cardId.hashCode(), 2) == 0 ? SkiffTurn.LEFT : SkiffTurn.RIGHT;
        }
    }

    // ---------------------------------------------------------------- 右键

    /** {@link SkiffBlock#onUse} 转到这里：灯 = 添油，桅杆 = 升 / 降帆，其余不响应。 */
    static ActionResult use(SkiffBlock block, BlockState state, World world, BlockPos pos, PlayerEntity player) {
        if (block != SkiffBlocks.LANTERN && block != SkiffBlocks.MAST) {
            return ActionResult.PASS;
        }
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        if (player.isSpectator()) {
            return ActionResult.PASS;
        }
        if (!acceptUse(player.getUuid(), System.currentTimeMillis())) {
            return ActionResult.SUCCESS;
        }
        ServerWorld server = (ServerWorld) world;
        if (block == SkiffBlocks.LANTERN) {
            refill(server, pos, state, player);
        } else {
            toggleSail(server, pos, player);
        }
        return ActionResult.SUCCESS;
    }

    private static void refill(ServerWorld world, BlockPos pos, BlockState state, PlayerEntity player) {
        int before = state.get(SkiffBlocks.OIL);
        if (before < 4) {
            world.setBlockState(pos, state.with(SkiffBlocks.OIL, 4), Block.NOTIFY_ALL);
        }
        world.playSound(null, pos, SoundEvents.ITEM_BOTTLE_EMPTY, SoundCategory.BLOCKS, 0.8f, 0.8f);
        // 与语言无关的一行：验收靠它判「右键真的到了灯上」
        LOGGER.info("艇上：{} 给灯添油 {} → 4", player.getGameProfile().getName(), before);
    }

    private static void toggleSail(ServerWorld world, BlockPos pos, PlayerEntity player) {
        WeatherCard card = currentWeather(world).orElse(null);
        WeatherEffect weather = card == null ? null : card.effect();
        String weatherId = card == null ? null : card.id();
        List<BlockPos> sails = new ArrayList<>();
        List<BlockPos> yards = new ArrayList<>();
        find(world, searchBox(world, pos), sails, yards);
        if (sails.isEmpty()) {
            return;
        }
        boolean raised = world.getBlockState(sails.get(0)).get(SkiffBlocks.RAISED);
        if (!raised && !Rules.mayRaise(weather)) {
            player.sendMessage(Text.translatable("heavyseas.skiff.sail_too_rough"), true);
            LOGGER.info("艇上：{} 想升帆，天候 {} —— 风浪太大，没升", player.getGameProfile().getName(), weatherId);
            return;
        }
        setSail(world, sails, yards, !raised, Rules.bellyFor(weather));
        world.playSound(null, pos, raised ? SoundEvents.BLOCK_WOOL_PLACE : SoundEvents.BLOCK_WOOL_BREAK,
                SoundCategory.BLOCKS, 1.0f, 0.7f);
        LOGGER.info("艇上：{} {}帆 · 天候 {} · 鼓 {} · {} 块帆面", player.getGameProfile().getName(), raised ? "收" : "升",
                weatherId, Rules.bellyFor(weather), sails.size());
    }

    private static void setSail(ServerWorld world, List<BlockPos> sails, List<BlockPos> yards, boolean raise, int belly) {
        for (BlockPos p : sails) {
            BlockState s = world.getBlockState(p);
            world.setBlockState(p, s.with(SkiffBlocks.RAISED, raise).with(SkiffBlocks.BELLY, belly), Block.NOTIFY_LISTENERS);
        }
        for (BlockPos p : yards) {
            BlockState s = world.getBlockState(p);
            world.setBlockState(p, s.with(SkiffBlocks.FURLED, !raise), Block.NOTIFY_LISTENERS);
        }
    }

    // ---------------------------------------------------------------- 跟着游戏动

    /** 新的一天翻出天候之后：灯油烧掉一档；风浪太大就收帆，升着的帆按天候换鼓度。 */
    public static void onNewDay(ServerWorld world, GameComponent component, WeatherCard card, int turn) {
        WeatherEffect weather = card.effect();
        Optional<BlockBox> box = hullBox(component);
        if (box.isEmpty()) {
            return;
        }
        List<BlockPos> lanterns = new ArrayList<>();
        List<BlockPos> sails = new ArrayList<>();
        List<BlockPos> yards = new ArrayList<>();
        for (BlockPos p : BlockPos.iterate(box.get().getMinX(), box.get().getMinY(), box.get().getMinZ(),
                box.get().getMaxX(), box.get().getMaxY(), box.get().getMaxZ())) {
            Block b = world.getBlockState(p).getBlock();
            if (b == SkiffBlocks.LANTERN) {
                lanterns.add(p.toImmutable());
            } else if (b == SkiffBlocks.SAIL) {
                sails.add(p.toImmutable());
            } else if (b == SkiffBlocks.YARD) {
                yards.add(p.toImmutable());
            }
        }
        StringBuilder oil = new StringBuilder();
        for (BlockPos p : lanterns) {
            BlockState s = world.getBlockState(p);
            int after = Rules.oilAtDayStart(s.get(SkiffBlocks.OIL), turn);
            if (after != s.get(SkiffBlocks.OIL)) {
                world.setBlockState(p, s.with(SkiffBlocks.OIL, after), Block.NOTIFY_ALL);
            }
            oil.append(s.get(SkiffBlocks.OIL)).append(" → ").append(after);
        }
        String sail = "收着";
        if (!sails.isEmpty() && world.getBlockState(sails.get(0)).get(SkiffBlocks.RAISED)) {
            boolean keep = Rules.mayRaise(weather);
            setSail(world, sails, yards, keep, Rules.bellyFor(weather));
            sail = keep ? "升着 · 鼓 " + Rules.bellyFor(weather) : "风浪太大，自动收起";
        }
        LOGGER.info("艇上：第 {} 天 · 天候 {} · 灯油 {} · 帆 {}", turn, card.id(), oil.isEmpty() ? "（没有灯）" : oil, sail);
    }

    /** 有人划了一下船：插着的桨都往船尾扫一下，伴一声划水，{@value #STROKE_TICKS} tick 后回位。 */
    public static void onRow(ServerWorld world, GameComponent component) {
        Optional<BlockBox> box = hullBox(component);
        if (box.isEmpty()) {
            return;
        }
        int oars = 0;
        BlockPos sound = null;
        for (BlockPos p : BlockPos.iterate(box.get().getMinX(), box.get().getMinY(), box.get().getMinZ(),
                box.get().getMaxX(), box.get().getMaxY(), box.get().getMaxZ())) {
            BlockState s = world.getBlockState(p);
            boolean oar = s.isOf(SkiffBlocks.OAR_BLOCK) || (s.isOf(SkiffBlocks.ROWLOCK) && s.get(SkiffBlocks.OAR));
            if (oar && !s.get(SkiffBlocks.STROKE)) {
                BlockPos at = p.toImmutable();
                world.setBlockState(at, s.with(SkiffBlocks.STROKE, true), Block.NOTIFY_LISTENERS);
                world.scheduleBlockTick(at, s.getBlock(), STROKE_TICKS);
                oars++;
                if (s.isOf(SkiffBlocks.ROWLOCK)) {
                    sound = at;
                }
            }
        }
        if (sound != null) {
            world.playSound(null, sound, SoundEvents.ENTITY_BOAT_PADDLE_WATER, SoundCategory.BLOCKS, 1.0f, 1.0f);
        }
        LOGGER.info("艇上：划船 · {} 格桨扫了一下", oars);
    }

    /** 舵手挑了牌：舵往一边偏一下，{@value #TURN_TICKS} tick 后回正。 */
    public static void onHelm(ServerWorld world, GameComponent component, String cardId) {
        Optional<BlockBox> box = hullBox(component);
        if (box.isEmpty()) {
            return;
        }
        SkiffTurn turn = Rules.turnFor(cardId);
        int parts = 0;
        for (BlockPos p : BlockPos.iterate(box.get().getMinX(), box.get().getMinY(), box.get().getMinZ(),
                box.get().getMaxX(), box.get().getMaxY(), box.get().getMaxZ())) {
            BlockState s = world.getBlockState(p);
            if (s.isOf(SkiffBlocks.RUDDER)) {
                BlockPos at = p.toImmutable();
                world.setBlockState(at, s.with(SkiffBlocks.TURN, turn), Block.NOTIFY_LISTENERS);
                world.scheduleBlockTick(at, s.getBlock(), TURN_TICKS);
                parts++;
            }
        }
        LOGGER.info("艇上：舵手挑了 {} · 舵往{}偏（{} 格）", cardId, turn == SkiffTurn.LEFT ? "左" : "右", parts);
    }

    /** {@link SkiffBlock#scheduledTick} 转到这里：桨、舵回位。 */
    static void settle(BlockState state, ServerWorld world, BlockPos pos) {
        BlockState next = state;
        if (state.contains(SkiffBlocks.STROKE) && state.get(SkiffBlocks.STROKE)) {
            next = next.with(SkiffBlocks.STROKE, false);
        }
        if (state.contains(SkiffBlocks.TURN) && state.get(SkiffBlocks.TURN) != SkiffTurn.NONE) {
            next = next.with(SkiffBlocks.TURN, SkiffTurn.NONE);
        }
        if (next != state) {
            world.setBlockState(pos, next, Block.NOTIFY_LISTENERS);
        }
    }

    // ---------------------------------------------------------------- 调试口（ADR-0060）

    /**
     * {@code /seas debug skiff oil}：这一局船体里每一盏灯的油一律定为 {@code oil} 档（0–4）。只改方块状态，不碰规则。
     *
     * @return 灯在哪几格；空 = 这一局没有船体，或船体里没有灯
     */
    public static List<BlockPos> debugSetOil(ServerWorld world, GameComponent component, int oil) {
        List<BlockPos> lanterns = new ArrayList<>();
        hullBox(component).ifPresent(box -> {
            for (BlockPos p : BlockPos.iterate(box.getMinX(), box.getMinY(), box.getMinZ(),
                    box.getMaxX(), box.getMaxY(), box.getMaxZ())) {
                if (world.getBlockState(p).isOf(SkiffBlocks.LANTERN)) {
                    lanterns.add(p.toImmutable());
                }
            }
        });
        for (BlockPos p : lanterns) {
            BlockState s = world.getBlockState(p);
            if (s.get(SkiffBlocks.OIL) != oil) {
                world.setBlockState(p, s.with(SkiffBlocks.OIL, oil), Block.NOTIFY_ALL);
            }
        }
        // 与语言无关的一行，带上灯在哪几格：回归脚本拿它去世界里逐格核对（判据取自世界，不取自这一行）
        LOGGER.info("艇上：调试 · 灯油定为 {} 档 · {} 盏 · {}", oil, lanterns.size(), positions(lanterns));
        return List.copyOf(lanterns);
    }

    /**
     * {@code /seas debug skiff sail up | down}：升 / 收帆。❗调试口<b>不问风浪</b>（右键那条路问）——
     * 要看的正是「暴风雨天升着帆」这种局面；第二天开头照常按天候自动收（{@link #onNewDay}）。
     *
     * @return 帆面在哪几格；空 = 这一局没有船体，或船体里没有帆
     */
    public static List<BlockPos> debugSetSail(ServerWorld world, GameComponent component, boolean raise) {
        List<BlockPos> sails = new ArrayList<>();
        List<BlockPos> yards = new ArrayList<>();
        hullBox(component).ifPresent(box -> find(world, box, sails, yards));
        WeatherCard card = currentWeather(world).orElse(null);
        WeatherEffect weather = card == null ? null : card.effect();
        if (!sails.isEmpty()) {
            setSail(world, sails, yards, raise, Rules.bellyFor(weather));
        }
        LOGGER.info("艇上：调试 · {}帆 · 天候 {} · 鼓 {} · {} 块帆面 · {}", raise ? "升" : "收",
                card == null ? null : card.id(), Rules.bellyFor(weather), sails.size(), positions(sails));
        return List.copyOf(sails);
    }

    private static String positions(List<BlockPos> at) {
        StringBuilder out = new StringBuilder();
        for (BlockPos p : at) {
            if (!out.isEmpty()) {
                out.append("; ");
            }
            out.append(p.getX()).append(' ').append(p.getY()).append(' ').append(p.getZ());
        }
        return out.isEmpty() ? "（无）" : out.toString();
    }

    // ---------------------------------------------------------------- 小工具

    private static Optional<BlockBox> hullBox(GameComponent component) {
        return component.sceneLeftover().flatMap(GameComponent.SceneLeftover::hull).map(GameComponent.HullFootprint::box);
    }

    /** 对局里按船体脚印找；不在对局里（演习艇）以点中的那一格为中心找。 */
    private static BlockBox searchBox(ServerWorld world, BlockPos pos) {
        return hullBox(GameComponents.of(world))
                .filter(b -> b.contains(pos))
                .orElse(BlockBox.create(pos.add(-SAIL_REACH, -SAIL_REACH, -SAIL_REACH), pos.add(SAIL_REACH, SAIL_REACH, SAIL_REACH)));
    }

    private static void find(ServerWorld world, BlockBox box, List<BlockPos> sails, List<BlockPos> yards) {
        for (BlockPos p : BlockPos.iterate(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ())) {
            Block b = world.getBlockState(p).getBlock();
            if (b == SkiffBlocks.SAIL) {
                sails.add(p.toImmutable());
            } else if (b == SkiffBlocks.YARD) {
                yards.add(p.toImmutable());
            }
        }
    }

    private static Optional<WeatherCard> currentWeather(ServerWorld world) {
        GameComponent component = GameComponents.of(world);
        return component.session().flatMap(s -> s.currentWeather());
    }
}
