package io.github.heavyseasmc.mod.world.liner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.MapCodec;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.data.SceneDataLoader;
import io.github.heavyseasmc.mod.data.VoyageLayout;
import io.github.heavyseasmc.mod.world.skiff.SkiffBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.structure.StructureLiquidSettings;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.structure.processor.StructureProcessor;
import net.minecraft.structure.processor.StructureProcessorType;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 北辰号进雾海（ADR-0080）：整船随 jar 发（{@code data/heavyseas/structure/liner/seg_*.nbt} 九段 + 清单
 * {@code data/heavyseas/liner/ship.json}），服务端起来时摆一次、一 tick 一步，之后就是存档里的真方块。
 *
 * <h2>船自己的东西在清单上，位置在地图的数据里（ADR-0081）</h2>
 * 清单只写船自己：尺寸 · 水线 · 每段的结构 · 八条艇与落脚点<b>相对船体西北下角</b>的位置，由生成器（{@code docs/tools/scene/liner_ship.py write-mod}）写。
 * 摆在哪是地图的事：默认航程布局的 {@code liner.origin}，维度就是那份布局的维度（「代码只认数据，不认坐标」，ADR-0034 §5.5）。
 * 换地图就是换那一份数据；船壳钢板与地毯的花纹按世界坐标排（{@link LinerHull.Rules}），摆下去时游戏对每一格问一遍
 * {@code getStateForNeighborUpdate}，它们按落下的位置自己重算（ADR-0081 §3 在 x、z 都差奇数格的地方实摆核过）。
 * 地图数据不归构建期管，所以起服时自己核：装得下 · 水线在水面上一格且落在钢板横缝上 · 离每份航程布局的船头够远；不对就不摆，日志里说哪一条。
 *
 * <h2>版本记进存档</h2>
 * 版本是清单、艇的结构文件与位置（维度 · 原点）的散列。存档里记着摆好的是哪一版、摆过哪几块；对不上（第一次 · 改过船 · 挪了位置 · 上次没摆完）
 * 就先把记下的每一块各自清掉再摆。范围在<b>动手之前</b>就写进存档、摆完才写版本：摆到一半停服，下一次起服照样知道要清哪几块、要重摆。
 * 记的是一串范围、各带维度，不是一个大并集：船从一处挪到很远的另一处时，并集是几千万格。
 * 存档挂在主世界（{@code data/heavyseas_liner.dat}）：船换了维度，旧维度里那一块照样清得到。
 *
 * <h2>为什么一 tick 一步</h2>
 * ADR-0064 实测一段 0.07–0.12 秒；一口气摆九段约 1 秒，服务端卡一下。分开摆，每个 tick 只多出一段的时间。
 */
public final class LinerShip {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    public static final Identifier MANIFEST = Identifier.of(HeavySeasMod.MOD_ID, "liner/ship.json");
    /**
     * 挂在两舷的艇：就是对局那条艇的结构（航程布局 {@code hull.structure} 同一份），摆的时候现改（ADR-0080 P5：只有一份源，
     * 改了对局那条艇，船上的艇跟着变）。
     */
    public static final Identifier SKIFF = Identifier.of(HeavySeasMod.MOD_ID, "boat_hull");
    private static final Identifier SKIFF_FILE = Identifier.of(HeavySeasMod.MOD_ID, "structure/boat_hull.nbt");
    private static final String STATE_ID = HeavySeasMod.MOD_ID + "_liner";
    /** 离对局足够远：最大视距 32 区块；客户端按整块区块画，再加一块（与构建期 {@code checkLinerShip} ⑤ 同一个数）。 */
    static final int FAR = 33 * 16;

    /** 清旧船时一步清多宽（x 向）：与一段差不多，一步的时间与摆一段同一个量级。 */
    static final int CLEAR_SLICE = 40;

    /**
     * 清旧船用的写法：不通知邻居、不做形状更新、不掉东西。从下往上清时钟与灯笼「失去支撑」会掉（{@code Hull#restore} 那一课），
     * 这里一步只清一片，隔壁那一片还是旧船 —— 干脆不让任何方块因为邻居变了而改自己。
     */
    private static final int QUIET = Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS;

    private LinerShip() {
    }

    // ------------------------------------------------------------------ 清单

    public record Segment(Identifier structure, int x0, int x1) {
    }

    /** @param drill 演习艇（1 号）：坐进它即报名（ADR-0054 D3 · ADR-0083） */
    public record Skiff(int no, BlockPos pos, BlockRotation rotation, boolean drill) {
    }

    /**
     * jar 里的清单：船自己的东西，位置都相对船体西北下角。
     *
     * @param waterline 水线那一道（{@code liner_hull_waterline}）的局部 y；它下面紧挨的那一格是水面
     * @param skiffs     艇挂在哪（局部坐标）
     * @param arrival    过了魔镜站在哪（局部坐标；A 甲板大楼梯平台、船上那面镜子前，ADR-0083）；调试指令 {@code tp} 也落这儿
     * @param arrivalYaw 落脚时面朝哪（度，Minecraft 的约定）
     * @param mirror     船上那面镜子下面一层正中那一格（局部坐标；镜子 3 × 3，WIDE_MID）
     * @param chartTable 平台上那张斜面海图桌的锚点（front_east，局部坐标）与正面朝向：浮字与小铜船按它摆（{@link ChartTable}，ADR-0086 §2 第 5 条）
     */
    public record Ship(Vec3i size, int waterline, List<Segment> segments, List<Skiff> skiffs, Vec3d arrival, float arrivalYaw,
                       BlockPos mirror, Direction mirrorFacing, BlockPos chartTable, Direction chartFacing,
                       byte[] manifestBytes, byte[] skiffBytes) {

        /** 摆到 {@code dimension} 的 {@code origin}：位置换成世界坐标，版本把位置也算进去。 */
        public Manifest at(RegistryKey<World> dimension, BlockPos origin) {
            List<Skiff> world = skiffs.stream().map(k -> new Skiff(k.no(), origin.add(k.pos()), k.rotation(), k.drill())).toList();
            return new Manifest(dimension, origin, size, waterline, segments, world,
                    arrival.add(origin.getX(), origin.getY(), origin.getZ()), arrivalYaw, origin.add(mirror), mirrorFacing,
                    origin.add(chartTable), chartFacing, version(manifestBytes, skiffBytes, dimension, origin));
        }
    }

    /**
     * 摆到某个位置上的船：清单 + 地图数据里的位置，坐标都是世界坐标。
     *
     * @param waterline 水线那一道（{@code liner_hull_waterline}）的局部 y；它下面紧挨的那一格是水面
     * @param mirror    船上那面镜子下面一层正中那一格（世界坐标）
     * @param chartTable 斜面海图桌的锚点（世界坐标）
     * @param version   清单 · 艇的结构 · 位置的散列（存档里认的就是它）
     */
    public record Manifest(RegistryKey<World> dimension, BlockPos origin, Vec3i size, int waterline,
                           List<Segment> segments, List<Skiff> skiffs, Vec3d arrival, float arrivalYaw,
                           BlockPos mirror, Direction mirrorFacing, BlockPos chartTable, Direction chartFacing, String version) {

        /** 演习艇（1 号）；清单里没有标演习艇时为空。 */
        public Optional<Skiff> drill() {
            return skiffs.stream().filter(Skiff::drill).findFirst();
        }

        /** 船体那一块（不含挂在舷外的艇）。 */
        public BlockBox hullBox() {
            return BlockBox.create(origin, origin.add(size).add(-1, -1, -1));
        }

        /** 清旧船时这一格往下回成水（水线那一道下面紧挨的那一格）。 */
        public int waterTop() {
            return origin.getY() + waterline - 1;
        }
    }

    /** 读清单。缺字段、类型不对一律抛，并点名是哪一个 —— 不退回默认值（落脚点退回 0 就是落在船体一角外面）。 */
    static Ship parse(byte[] manifest, byte[] skiff) {
        JsonObject o = JsonParser.parseString(new String(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
        for (String moved : List.of("origin", "dimension")) {
            if (o.has(moved)) {
                // 两处都写就有两个来源：地图换了位置，清单里这一份还在，下一个读它的人不知道该信哪一个
                throw new IllegalArgumentException("清单里不该再有 " + moved + "：位置写在地图数据里（默认航程布局的 liner，ADR-0081）"
                        + "—— 重跑 liner_ship.py write-mod");
            }
        }
        int[] size = ints(o, "size", 3);
        List<Segment> segments = new ArrayList<>();
        for (JsonElement e : array(o, "segments")) {
            JsonObject s = e.getAsJsonObject();
            segments.add(new Segment(Identifier.of(string(s, "structure")), integer(s, "x0"), integer(s, "x1")));
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("清单 segments 是空的");
        }
        List<Skiff> skiffs = new ArrayList<>();
        for (JsonElement e : array(o, "skiffs")) {
            JsonObject k = e.getAsJsonObject();
            int[] p = ints(k, "pos", 3);
            skiffs.add(new Skiff(integer(k, "no"), new BlockPos(p[0], p[1], p[2]),
                    BlockRotation.valueOf(string(k, "rotation").toUpperCase(Locale.ROOT)), field(k, "drill").getAsBoolean()));
        }
        if (skiffs.stream().filter(Skiff::drill).count() != 1) {
            throw new IllegalArgumentException("清单里演习艇（drill: true）要正好一条，实际 " + skiffs.stream().filter(Skiff::drill).count());
        }
        JsonArray a = array(o, "arrival");
        if (a.size() != 3) {
            throw new IllegalArgumentException("清单 arrival 要 3 个数");
        }
        int[] mirror = ints(o, "mirror", 3);
        Direction facing = Direction.byName(string(o, "mirror_facing"));
        if (facing == null || facing.getAxis().isVertical()) {
            throw new IllegalArgumentException("清单 mirror_facing 要是 north / south / east / west，实际 " + string(o, "mirror_facing"));
        }
        int[] chart = ints(o, "chart_table", 3);
        Direction chartFacing = Direction.byName(string(o, "chart_table_facing"));
        if (chartFacing == null || chartFacing.getAxis().isVertical()) {
            throw new IllegalArgumentException("清单 chart_table_facing 要是 north / south / east / west，实际 " + string(o, "chart_table_facing"));
        }
        return new Ship(new Vec3i(size[0], size[1], size[2]), integer(o, "waterline"), List.copyOf(segments), List.copyOf(skiffs),
                new Vec3d(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()), field(o, "arrival_yaw").getAsFloat(),
                new BlockPos(mirror[0], mirror[1], mirror[2]), facing, new BlockPos(chart[0], chart[1], chart[2]), chartFacing,
                manifest, skiff);
    }

    static String version(byte[] manifest, byte[] skiff, RegistryKey<World> dimension, BlockPos origin) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            sha.update(manifest);
            sha.update(skiff);
            sha.update("%s %d %d %d".formatted(dimension.getValue(), origin.getX(), origin.getY(), origin.getZ())
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest()).substring(0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static Ship load(ResourceManager resources) throws IOException {
        return parse(read(resources, MANIFEST), read(resources, SKIFF_FILE));
    }

    private static byte[] read(ResourceManager resources, Identifier id) throws IOException {
        Resource resource = resources.getResource(id).orElseThrow(() -> new IOException("数据包里没有 " + id));
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        }
    }

    private static JsonElement field(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            throw new IllegalArgumentException("清单缺字段 " + key);
        }
        return e;
    }

    private static String string(JsonObject o, String key) {
        return field(o, key).getAsString();
    }

    private static int integer(JsonObject o, String key) {
        return field(o, key).getAsInt();
    }

    private static JsonArray array(JsonObject o, String key) {
        return field(o, key).getAsJsonArray();
    }

    private static int[] ints(JsonObject o, String key, int n) {
        JsonArray a = array(o, key);
        if (a.size() != n) {
            throw new IllegalArgumentException("清单 " + key + " 要 " + n + " 个数，实际 " + a.size());
        }
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = a.get(i).getAsInt();
        }
        return out;
    }

    // ------------------------------------------------------------------ 位置对不对（地图数据不归构建期管，起服时核）

    /**
     * 不碰世界就核得出来的三条。
     *
     * @param box    船占的那一块（船体 + 艇）
     * @param bottom 维度最低一格
     * @param top    维度最高一格（含）
     * @param bows   同一维度里每份航程布局的船头
     * @return 每条一句；空 = 没问题
     */
    static List<String> siteProblems(Manifest m, BlockBox box, int bottom, int top, Collection<Vec3d> bows) {
        List<String> problems = new ArrayList<>();
        if (box.getMinY() < bottom || box.getMaxY() > top) {
            problems.add("维度 %s 装不下：船占 y %d–%d，维度只有 y %d–%d".formatted(m.dimension().getValue(),
                    box.getMinY(), box.getMaxY(), bottom, top));
        }
        int band = m.origin().getY() + m.waterline();
        if (LinerHull.Rules.row(band + 1) != 0) {
            problems.add("原点 y %d：黑板从 y %d 起，那一格不是一列钢板的下格，水线会切在一块板的正中（y 要是双数）"
                    .formatted(m.origin().getY(), band + 1));
        }
        for (Vec3d bow : bows) {
            double d = horizontalDistance(box, bow);
            if (d <= FAR) {
                problems.add("离对局船头 (%.1f, %.1f) 只有 %d 格，最大视距是 %d 格：对局里看得见它".formatted(bow.x, bow.z, Math.round(d), FAR));
            }
        }
        return problems;
    }

    /** 点到一块的水平距离（在那一块的 x、z 范围里就是 0）。 */
    static double horizontalDistance(BlockBox box, Vec3d p) {
        double dx = Math.max(0d, Math.max(box.getMinX() - p.x, p.x - (box.getMaxX() + 1)));
        double dz = Math.max(0d, Math.max(box.getMinZ() - p.z, p.z - (box.getMaxZ() + 1)));
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * 水线对不对：船尾外两格那一列，水面那一格要是水、上面一格不是水。
     * 那一格若在存档记着的某一块旧船里（船只挪了几格），旧船清掉时它回成什么由那一块记的水面定 —— 就比那个数，不读世界。
     */
    @Nullable
    static String waterProblem(ServerWorld world, Manifest m, List<Area> old) {
        BlockPos probe = new BlockPos(m.origin().getX() - 2, m.waterTop(), m.origin().getZ() + m.size().getZ() / 2);
        for (Area a : old) {
            if (a.dimension().equals(m.dimension()) && a.box().contains(probe)) {
                return a.waterTop() == m.waterTop() ? null
                        : "水线那一道在 y %d，可这一片原来的水面在 y %d".formatted(m.waterTop() + 1, a.waterTop());
            }
        }
        boolean water = world.getFluidState(probe).isIn(FluidTags.WATER);
        boolean above = world.getFluidState(probe.up()).isIn(FluidTags.WATER);
        if (water && !above) {
            return null;
        }
        return "水线那一道在 y %d，船尾外 (%d %d %d) 那一格%s、上面一格%s：水面不在 y %d（龙骨要让水线那一道正好在水面上一格）".formatted(
                m.waterTop() + 1, probe.getX(), probe.getY(), probe.getZ(), water ? "是水" : "不是水", above ? "也是水" : "不是水", m.waterTop());
    }

    // ------------------------------------------------------------------ 存档里记着的

    /** 摆过（或正要摆）的一块：哪个维度 · 哪一块 · 清的时候哪一格往下回成水。 */
    record Area(RegistryKey<World> dimension, BlockBox box, int waterTop) {

        NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.putString("dimension", dimension.getValue().toString());
            nbt.putIntArray("box", new int[]{box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ()});
            nbt.putInt("water_top", waterTop);
            return nbt;
        }

        @Nullable
        static Area fromNbt(NbtCompound nbt) {
            int[] b = nbt.getIntArray("box");
            Identifier dim = Identifier.tryParse(nbt.getString("dimension"));
            if (b.length != 6 || dim == null) {
                return null;
            }
            return new Area(RegistryKey.of(RegistryKeys.WORLD, dim), new BlockBox(b[0], b[1], b[2], b[3], b[4], b[5]), nbt.getInt("water_top"));
        }
    }

    /** 世界里摆着的是哪一版、摆过哪几块。挂在主世界的存档里（{@code data/heavyseas_liner.dat}）：船换了维度，旧的那一块照样找得到。 */
    static final class State extends PersistentState {

        /** {@code null} 的数据修复类型：Fabric 的对象构建 API 把它当成「不修」（PersistentStateManagerMixin）。 */
        static final Type<State> TYPE = new Type<>(State::new, State::fromNbt, null);

        /** 摆好的是哪一版；空串 = 没有，或者上次没摆完。 */
        String placed = "";
        /** 要清的那几块；空 = 世界里从没摆过（或者已经清干净了）。 */
        final List<Area> areas = new ArrayList<>();

        static State fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
            State state = new State();
            state.placed = nbt.getString("placed");
            NbtList list = nbt.getList("areas", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < list.size(); i++) {
                Area a = Area.fromNbt(list.getCompound(i));
                if (a != null) {
                    state.areas.add(a);
                }
            }
            return state;
        }

        @Override
        public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
            nbt.putString("placed", placed);
            NbtList list = new NbtList();
            areas.forEach(a -> list.add(a.toNbt()));
            nbt.put("areas", list);
            return nbt;
        }
    }

    static State state(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(State.TYPE, STATE_ID);
    }

    // ------------------------------------------------------------------ 摆

    /** 这一次运行认的那一份（清单 + 地图数据里的位置）；没有、或位置不对时为 {@code null}，原因在 {@link #unavailable}。 */
    @Nullable
    private static Manifest loaded;
    @Nullable
    private static String unavailable;
    /** 正在进行的那一次摆放；服务端停了就扔掉（存档里记着要清的范围，下一次起服照样清）。 */
    @Nullable
    private static Placement running;

    private record Step(String label, Runnable action) {
    }

    private static final class Placement {
        /** {@code null} = 只清不摆（地图数据里没有北辰号了）。 */
        @Nullable
        final Manifest manifest;
        final List<Step> steps = new ArrayList<>();
        int done;
        long longestNanos;
        String longestLabel = "";
        long cleared;
        final int startTick;

        Placement(MinecraftServer server, @Nullable Manifest manifest) {
            this.manifest = manifest;
            this.startTick = server.getTicks();
        }
    }

    /**
     * 起服：读清单与默认航程布局里的位置，核过位置、世界里不是这一版就排上摆放。
     * 读不出来、维度不在、位置不对都只记一行，不拦起服（大厅没船，对局照常）；位置不对时世界里原来的船不动。
     * 地图数据里没有北辰号（默认布局没写 {@code liner}）而存档记着摆过：清回海（用户 2026-10-03 定）。
     */
    public static void onServerStarted(MinecraftServer server) {
        loaded = null;
        unavailable = null;
        running = null;
        Ship ship;
        VoyageLayout layout;
        try {
            ship = load(server.getResourceManager());
            layout = SceneDataLoader.defaultLayout();
        } catch (IOException | RuntimeException failure) {
            unavailable = "清单或地图数据读不出来：" + failure.getMessage();
            LOGGER.error("北辰号：{}，不摆", unavailable);
            return;
        }
        State state = state(server);
        if (layout.liner().isEmpty()) {
            unavailable = "默认航程布局 " + layout.id() + " 没写 liner：这张地图没有北辰号";
            if (state.areas.isEmpty()) {
                LOGGER.info("北辰号：{}，不摆", unavailable);
            } else {
                beginOrLog(server, null, state, "地图数据里没有北辰号了");
            }
            return;
        }
        Manifest m = ship.at(layout.dimensionKey(), layout.liner().get().origin());
        ServerWorld world = server.getWorld(m.dimension());
        if (world == null) {
            unavailable = "维度 " + m.dimension().getValue() + " 没加载";
            LOGGER.error("北辰号：{}，不摆", unavailable);
            return;
        }
        List<String> problems = new ArrayList<>();
        try {
            problems.addAll(siteProblems(m, footprint(m, skiffTemplate(world)), world.getBottomY(), world.getTopY() - 1, bows(m.dimension())));
        } catch (IllegalStateException missing) {
            problems.add(missing.getMessage());
        }
        String water = waterProblem(world, m, state.areas);
        if (water != null) {
            problems.add(water);
        }
        if (!problems.isEmpty()) {
            unavailable = "地图数据里的位置不对（原点 " + m.origin().toShortString() + "）：" + String.join("；", problems);
            LOGGER.error("北辰号：{} —— 不摆，世界里原来的不动", unavailable);
            return;
        }
        loaded = m;
        if (m.version().equals(state.placed)) {
            LOGGER.info("北辰号：世界里已是这一版（{}），不动", m.version());
            return;
        }
        beginOrLog(server, m, state, state.placed.isEmpty() ? (state.areas.isEmpty() ? "世界里还没有" : "上次没摆完") : "换了一版");
    }

    /**
     * 起服时排上摆放；排不上（缺了某一段 {@code seg_*.nbt}、艇的结构不在）只记一行，不拦起服 —— 照类注释那一条。
     *
     * <p>❗审查 2026-10-07 C5：原先 try 只包住了艇的模板那一段，这一下在 try 外 —— 数据包里缺一段船体、世界里的版本又对不上，
     * {@link #begin} 的 {@code orElseThrow} 一抛，SERVER_STARTED 回调冒到 {@code runServer} 的总 catch，起服就崩。
     * {@link #begin} 在动存档之前就把模板全取齐，取不齐时存档与世界都没动过：世界里原来的那一版照旧在。
     */
    private static void beginOrLog(MinecraftServer server, @Nullable Manifest manifest, State state, String why) {
        try {
            begin(server, manifest, state, why);
        } catch (RuntimeException failure) {
            loaded = null;                    // 这一版没摆上：魔镜、演习艇不认它（世界里还是旧的那一版，或者没有）
            running = null;
            unavailable = "排不上摆放（" + why + "）：" + failure.getMessage();
            LOGGER.error("北辰号：{} —— 不摆，世界里原来的不动", unavailable, failure);
        }
    }

    public static void onServerStopped() {
        running = null;
        loaded = null;
        unavailable = null;
    }

    /** 同一维度里每份航程布局的船头（官方地图只有一份；地图作者加的也算 —— 哪一份开局都不许看见北辰号）。 */
    private static List<Vec3d> bows(RegistryKey<World> dimension) {
        List<Vec3d> bows = new ArrayList<>();
        for (Identifier id : SceneDataLoader.ids()) {
            VoyageLayout layout = SceneDataLoader.require(id);
            if (layout.dimensionKey().equals(dimension)) {
                bows.add(layout.boat().bow());
            }
        }
        return bows;
    }

    private static StructureTemplate skiffTemplate(ServerWorld world) {
        return world.getStructureTemplateManager().getTemplate(SKIFF).orElseThrow(() ->
                new IllegalStateException("艇的结构 " + SKIFF + " 不在数据包里"));
    }

    /** 船占的那一块：船体 + 舷外的艇。 */
    static BlockBox footprint(Manifest m, StructureTemplate skiff) {
        BlockBox box = m.hullBox();
        for (Skiff k : m.skiffs()) {
            box = union(box, skiff.calculateBoundingBox(skiffPlacement(k), k.pos()));
        }
        return box;
    }

    static boolean sameArea(Area a, Area b) {
        return a.dimension().equals(b.dimension()) && a.waterTop() == b.waterTop() && fmt(a.box()).equals(fmt(b.box()));
    }

    /**
     * 排上一次摆放：先把记下的每一块各自清掉，再一段一段摆、挂艇、记版本。
     *
     * @param manifest {@code null} = 只清不摆（地图数据里没有北辰号了）
     * @throws IllegalStateException 结构模板缺了 —— 缺一段就整个不摆，摆半条船比不摆更糟
     */
    static void begin(MinecraftServer server, @Nullable Manifest manifest, State state, String why) {
        Placement p = new Placement(server, manifest);
        List<Area> old = List.copyOf(state.areas);
        Area fresh = null;
        ServerWorld world = null;
        List<StructureTemplate> segments = new ArrayList<>();
        StructureTemplate skiff = null;
        if (manifest != null) {
            world = server.getWorld(manifest.dimension());
            if (world == null) {
                throw new IllegalStateException("维度 " + manifest.dimension().getValue() + " 没加载");
            }
            StructureTemplateManager templates = world.getStructureTemplateManager();
            for (Segment segment : manifest.segments()) {
                segments.add(templates.getTemplate(segment.structure()).orElseThrow(() ->
                        new IllegalStateException("北辰号的结构 " + segment.structure() + " 不在数据包里")));
            }
            skiff = skiffTemplate(world);
            fresh = new Area(manifest.dimension(), footprint(manifest, skiff), manifest.waterTop());
        }
        // 动手之前先记下：摆到一半停服，下一次起服照样知道要清哪几块
        state.placed = "";
        Area placedArea = fresh;
        if (placedArea != null && old.stream().noneMatch(a -> sameArea(a, placedArea))) {
            state.areas.add(placedArea);
        }
        state.markDirty();

        for (Area a : old) {
            ServerWorld w = server.getWorld(a.dimension());
            if (w == null) {
                LOGGER.warn("北辰号：记着的一块 {} 在维度 {}，那个维度不在了，清不了 —— 摆完就不再记它", fmt(a.box()), a.dimension().getValue());
                continue;
            }
            BlockBox box = a.box();
            for (int x = box.getMinX(); x <= box.getMaxX(); x += CLEAR_SLICE) {
                BlockBox slice = new BlockBox(x, box.getMinY(), box.getMinZ(),
                        Math.min(x + CLEAR_SLICE - 1, box.getMaxX()), box.getMaxY(), box.getMaxZ());
                p.steps.add(new Step("清 x " + slice.getMinX() + "–" + slice.getMaxX(), () -> p.cleared += clear(w, slice, a.waterTop())));
            }
        }
        if (manifest != null) {
            ServerWorld at0 = world;
            StructurePlacementData data = hullPlacement();
            for (int i = 0; i < segments.size(); i++) {
                Segment segment = manifest.segments().get(i);
                StructureTemplate template = segments.get(i);
                BlockPos at = manifest.origin().add(segment.x0(), 0, 0);
                p.steps.add(new Step("段 " + segment.structure().getPath(), () -> {
                    loadChunks(at0, template.calculateBoundingBox(data, at));
                    if (!template.place(at0, at, at, data, at0.getRandom(), Block.NOTIFY_LISTENERS)) {
                        throw new IllegalStateException("结构 " + segment.structure() + " 是空的");
                    }
                }));
            }
            StructureTemplate k0 = skiff;
            p.steps.add(new Step("艇 " + manifest.skiffs().size() + " 条", () -> {
                for (Skiff k : manifest.skiffs()) {
                    StructurePlacementData kd = skiffPlacement(k);
                    loadChunks(at0, k0.calculateBoundingBox(kd, k.pos()));
                    k0.place(at0, k.pos(), k.pos(), kd, at0.getRandom(), Block.NOTIFY_LISTENERS);
                }
            }));
        }
        p.steps.add(new Step(manifest == null ? "记下清完了" : "记版本", () -> {
            state.areas.clear();
            if (placedArea != null) {
                state.areas.add(placedArea);
            }
            state.placed = manifest == null ? "" : manifest.version();
            state.markDirty();
        }));
        running = p;
        LOGGER.info("北辰号：开始{}（{}）· 版本 {} · {} 步，一 tick 一步 · 先清 {}", manifest == null ? "清" : "摆", why,
                manifest == null ? "-" : manifest.version(), p.steps.size(),
                old.isEmpty() ? "无" : String.join("、", old.stream().map(a -> a.dimension().getValue() + " " + fmt(a.box())).toList()));
    }

    public static void tick(MinecraftServer server) {
        Placement p = running;
        if (p == null) {
            return;
        }
        Step step = p.steps.get(p.done);
        long t0 = System.nanoTime();
        try {
            step.action().run();
        } catch (RuntimeException failure) {
            running = null;
            LOGGER.error("北辰号：第 {} / {} 步（{}）出错，停下 —— 存档里记着要清的范围，下一次起服清掉重摆",
                    p.done + 1, p.steps.size(), step.label(), failure);
            return;
        }
        long nanos = System.nanoTime() - t0;
        if (nanos > p.longestNanos) {
            p.longestNanos = nanos;
            p.longestLabel = step.label();
        }
        p.done++;
        if (p.done == p.steps.size()) {
            running = null;
            Manifest m = p.manifest;
            if (m == null) {
                // 与语言无关的一行：验收脚本按「北辰号已清掉」认它清完了
                LOGGER.info("北辰号已清掉：清了 {} 格 · 用了 {} tick · 最长一步 {} ms（{}）",
                        p.cleared, server.getTicks() - p.startTick, p.longestNanos / 1_000_000, p.longestLabel);
                return;
            }
            // 与语言无关的一行：验收脚本按「北辰号已摆好」认它摆完了
            LOGGER.info("北辰号已摆好：版本 {} · {} 段 · 艇 {} 条 · 清掉旧的 {} 格 · 用了 {} tick · 最长一步 {} ms（{}）· 船体 {} · 落脚 {}",
                    m.version(), m.segments().size(), m.skiffs().size(), p.cleared,
                    server.getTicks() - p.startTick, p.longestNanos / 1_000_000, p.longestLabel, fmt(m.hullBox()), m.arrival());
        }
    }

    /** 船体那几段：不取世界里原有的水（水线下船舱里写明了空气，船里不该有任何一格泡水）；不带实体。其余与 {@code /place template} 相同。 */
    static StructurePlacementData hullPlacement() {
        return new StructurePlacementData().setIgnoreEntities(true).setLiquidSettings(StructureLiquidSettings.IGNORE_WATERLOGGING);
    }

    static StructurePlacementData skiffPlacement(Skiff k) {
        return new StructurePlacementData().setRotation(k.rotation()).setIgnoreEntities(true)
                .setLiquidSettings(StructureLiquidSettings.IGNORE_WATERLOGGING).addProcessor(SkiffOnDavits.INSTANCE);
    }

    /** 从上往下把一块清回海：水面及以下是水，上面是空气。返回清了几格。 */
    static long clear(ServerWorld world, BlockBox box, int waterTop) {
        loadChunks(world, box);
        BlockState water = Blocks.WATER.getDefaultState();
        BlockState air = Blocks.AIR.getDefaultState();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        long n = 0;
        for (int y = box.getMaxY(); y >= box.getMinY(); y--) {
            for (int z = box.getMinZ(); z <= box.getMaxZ(); z++) {
                for (int x = box.getMinX(); x <= box.getMaxX(); x++) {
                    world.setBlockState(pos.set(x, y, z), y <= waterTop ? water : air, QUIET);
                    n++;
                }
            }
        }
        return n;
    }

    /** 摆之前把那几个区块载进来（{@code /place} 要求区块已载入；这里当场同步载，雾海是平坦生成，几十个区块不费事）。外扩一圈：边上的形状更新会碰到隔壁。 */
    private static void loadChunks(ServerWorld world, BlockBox box) {
        for (int cx = (box.getMinX() >> 4) - 1; cx <= (box.getMaxX() >> 4) + 1; cx++) {
            for (int cz = (box.getMinZ() >> 4) - 1; cz <= (box.getMaxZ() >> 4) + 1; cz++) {
                world.getChunk(cx, cz);
            }
        }
    }

    static BlockBox union(BlockBox a, BlockBox b) {
        return new BlockBox(Math.min(a.getMinX(), b.getMinX()), Math.min(a.getMinY(), b.getMinY()), Math.min(a.getMinZ(), b.getMinZ()),
                Math.max(a.getMaxX(), b.getMaxX()), Math.max(a.getMaxY(), b.getMaxY()), Math.max(a.getMaxZ(), b.getMaxZ()));
    }

    private static String fmt(BlockBox b) {
        return "(%d %d %d)..(%d %d %d)".formatted(b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ());
    }

    // ------------------------------------------------------------------ 给调试指令

    /** 这一次运行认的那一份（清单 + 地图数据里的位置）；没有时为空（原因见 {@link #unavailableReason}）。 */
    public static Optional<Manifest> manifest() {
        return Optional.ofNullable(loaded);
    }

    public static Optional<String> unavailableReason() {
        return Optional.ofNullable(unavailable);
    }

    /** 世界里摆好的那一版；空串 = 没有或没摆完。 */
    public static String placedVersion(MinecraftServer server) {
        return state(server).placed;
    }

    /** 正在摆时 {@code [做完几步, 共几步]}。 */
    public static Optional<int[]> progress() {
        Placement p = running;
        return p == null ? Optional.empty() : Optional.of(new int[]{p.done, p.steps.size()});
    }

    /**
     * {@code /seas debug liner replace}：不管版本，清掉重摆。
     *
     * @return 共几步；已经在摆时为空
     */
    public static Optional<Integer> replace(MinecraftServer server, Manifest manifest) {
        if (running != null) {
            return Optional.empty();
        }
        begin(server, manifest, state(server), "调试指令");
        return Optional.of(running.steps.size());
    }

    // ------------------------------------------------------------------ 艇挂上吊艇架

    /**
     * 对局那条艇挂到邮轮舷外：去掉桅 · 横桁 · 帆 · 灯（挂着的艇不扬帆；灯挂在桅杆上，桅去掉了它就悬在半空），
     * 泡水的一律改成不泡水（舵的下半截在对局里是泡水的，挂在半空时水顺着船壳流到海里，侧面看是几道蓝色竖条）。
     */
    static final class SkiffOnDavits extends StructureProcessor {

        static final SkiffOnDavits INSTANCE = new SkiffOnDavits();
        /**
         * 不登记进注册表：这个处理器只在代码里当场用、从不序列化（数据包与结构文件里不会出现它），
         * 类型只为满足 {@link #getType()} 这个抽象方法。
         */
        private static final MapCodec<SkiffOnDavits> CODEC = MapCodec.unit(INSTANCE);
        private static final StructureProcessorType<SkiffOnDavits> TYPE = () -> CODEC;
        /** 这个类第一次用到是在起服摆船时，方块早已登记完。 */
        private static final Set<Block> STRIPPED = Set.of(SkiffBlocks.MAST, SkiffBlocks.YARD, SkiffBlocks.SAIL, SkiffBlocks.LANTERN);

        private SkiffOnDavits() {
        }

        @Override
        public StructureTemplate.StructureBlockInfo process(WorldView world, BlockPos pos, BlockPos pivot,
                                                            StructureTemplate.StructureBlockInfo original,
                                                            StructureTemplate.StructureBlockInfo current,
                                                            StructurePlacementData data) {
            BlockState state = current.state();
            if (STRIPPED.contains(state.getBlock())) {
                return null;
            }
            if (state.contains(Properties.WATERLOGGED) && state.get(Properties.WATERLOGGED)) {
                return new StructureTemplate.StructureBlockInfo(current.pos(), state.with(Properties.WATERLOGGED, false), current.nbt());
            }
            return current;
        }

        @Override
        protected StructureProcessorType<?> getType() {
            return TYPE;
        }
    }
}
