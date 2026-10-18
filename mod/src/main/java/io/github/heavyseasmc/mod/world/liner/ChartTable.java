package io.github.heavyseasmc.mod.world.liner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.world.SceneItems;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.Brightness;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.decoration.DisplayEntity.ItemDisplayEntity;
import net.minecraft.entity.decoration.DisplayEntity.TextDisplayEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * 平台上那张斜面海图桌的浮字与小铜船（ADR-0086 §2 第 5 条 · ADR-0054 §9.1 前两步）：演习艇每多坐一个人，小铜船往冰区挪一档；
 * 浮字写「1 号艇 · 已入座 n / 8」，有一局在走时写「已出航」、船停在冰区边上。
 *
 * <h2>谁管什么</h2>
 * 桌子是方块（{@link LinerProp}，结构里摆好）；浮字是一个文字展示实体、小铜船是一个物品展示实体，<b>都由这里生成、挪动、收走</b>。
 * 位置取清单里的锚点（{@link LinerShip.Manifest#chartTable}）；斜面上九档的位姿取 jar 里的 {@code data/heavyseas/liner/chart_table.json}
 * —— 由生成器从海图桌的模型算出来（docs 的 {@code liner_ship.chart_geometry}），斜面只在那一处量。
 *
 * <h2>收拾干净</h2>
 * 展示实体会进存档。这里不持久化它们是谁：每次运行只认自己这一次生成的两个（{@link #owns}），存档里留下的一律当孤儿清
 * （{@code Seats} 起服 / 区块载入时按 {@link #TAG} 认亲）。船挪了位置、桌子不在了（换版重摆的那几 tick 也算）、清单没了，都当场收走；
 * 区块（连实体）没载入时什么都不做 —— 不为了摆两个字把区块拉起来，也不在实体还没载入时多生一份。
 */
public final class ChartTable {

    /** 存盘时的指令标签：起服扫孤儿时认它。 */
    public static final String TAG = "heavyseas_chart";
    /** 小铜船有几档：0 … 8 = 演习艇坐了几个人（{@link DrillSkiff#SEATS}）。 */
    static final int STEPS = DrillSkiff.SEATS;
    /** 多久看一次（tick）：入座人数一秒内的变化不必立刻反映。 */
    private static final int PERIOD = 20;
    /** 小铜船挪一档滑多久（tick）。 */
    private static final int GLIDE_TICKS = 30;
    static final String GEOMETRY = "/data/heavyseas/liner/chart_table.json";
    /** 浮字：暖灰白的字、约 19 % 不透明的底、0.6 倍大（Codex 二评第 1 条）。 */
    private static final int TEXT_COLOR = 0xE8DCC0;
    private static final int TEXT_BACKGROUND = 0x30000000;
    private static final float TEXT_SCALE = 0.6f;

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 一档小铜船：船底正中 · 船头方向 · 斜面法向（整件模型像素，桌子正面朝北）。 */
    public record Pose(Vector3f bottom, Vector3f bow, Vector3f normal) {
    }

    /** 海图桌的几何：浮字的位置与九档小铜船（整件模型像素，正面朝北）。 */
    public record Geometry(Vector3f text, List<Pose> ship) {

        static Geometry parse(String json) {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (!o.has("schema") || o.get("schema").getAsInt() != 1) {
                throw new IllegalArgumentException(GEOMETRY + " 的 schema 不是 1");
            }
            List<Pose> ship = new ArrayList<>();
            for (var e : o.getAsJsonArray("ship")) {
                JsonObject p = e.getAsJsonObject();
                ship.add(new Pose(vec(p.getAsJsonArray("bottom")), vec(p.getAsJsonArray("bow")), vec(p.getAsJsonArray("normal"))));
            }
            if (ship.size() != STEPS + 1) {
                throw new IllegalArgumentException(GEOMETRY + " 里小铜船要 " + (STEPS + 1) + " 档，实际 " + ship.size());
            }
            return new Geometry(vec(o.getAsJsonArray("text")), List.copyOf(ship));
        }

        private static Vector3f vec(JsonArray a) {
            if (a == null || a.size() != 3) {
                throw new IllegalArgumentException(GEOMETRY + " 里有一个坐标不是 3 个数");
            }
            return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
        }
    }

    private static Geometry geometry;

    // ---- 这一次运行生成的那两个（不持久化）
    private static UUID textId;
    private static UUID shipId;
    private static BlockPos anchor;
    private static Direction front;
    private static String shownText;
    private static int shownStep = -1;
    private static int clock;

    private ChartTable() {
    }

    static Geometry geometry() {
        if (geometry == null) {
            try (InputStream in = ChartTable.class.getResourceAsStream(GEOMETRY)) {
                if (in == null) {
                    throw new IllegalStateException("jar 里没有 " + GEOMETRY + "（liner_ship.py write-mod 写的）");
                }
                geometry = Geometry.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return geometry;
    }

    /** 这个实体是不是这一次运行里海图桌那两个之一（{@code Seats} 扫孤儿时问）。 */
    public static boolean owns(UUID id) {
        return id.equals(textId) || id.equals(shipId);
    }

    /** 服务端停了：这一次运行的记忆清掉（实体留在存档里，下次起服当孤儿清）。 */
    public static void forget() {
        textId = shipId = null;
        anchor = null;
        front = null;
        shownText = null;
        shownStep = -1;
        clock = 0;
    }

    public static void tick(MinecraftServer server) {
        if (++clock % PERIOD != 0) {
            return;
        }
        Optional<LinerShip.Manifest> manifest = LinerShip.manifest();
        if (manifest.isEmpty()) {
            clear(server, "北辰号没摆");
            return;
        }
        LinerShip.Manifest m = manifest.get();
        ServerWorld world = server.getWorld(m.dimension());
        if (world == null) {
            return;
        }
        if (!m.chartTable().equals(anchor) || m.chartFacing() != front) {
            clear(server, "海图桌换了位置");
            anchor = m.chartTable();
            front = m.chartFacing();
        }
        if (!world.shouldTickEntity(anchor)) {
            return;                                                     // 区块连实体还没载入：等有人走近
        }
        BlockState here = world.getBlockState(anchor);
        if (!here.isOf(LinerProps.CHART_TABLE) || here.get(LinerProp.CHART) != LinerProp.Part.FRONT_EAST) {
            clear(server, "海图桌不在了");
            return;
        }
        boolean sailing = GameComponents.of(world).session().isPresent();
        OptionalInt seated = DrillSkiff.seatedCount(server);
        int n = Math.min(seated.orElse(0), STEPS);
        String key = sailing ? "sailing" : "seated " + n;
        int step = sailing ? STEPS : n;
        Geometry g = geometry();
        TextDisplayEntity text = textId != null && world.getEntity(textId) instanceof TextDisplayEntity t ? t : null;
        ItemDisplayEntity ship = shipId != null && world.getEntity(shipId) instanceof ItemDisplayEntity s ? s : null;
        if (text == null) {
            text = spawnText(world, g);
            shownText = null;
        }
        if (ship == null) {
            ship = spawnShip(world, g, step);
            shownStep = step;
        }
        if (text != null && !key.equals(shownText)) {
            text.setText((sailing ? Text.translatable("heavyseas.chart.sailing")
                    : Text.translatable("heavyseas.chart.seated", n, DrillSkiff.SEATS)).withColor(TEXT_COLOR));
            shownText = key;
        }
        if (ship != null && step != shownStep) {
            ship.setStartInterpolation(0);
            ship.setInterpolationDuration(GLIDE_TICKS);
            ship.setTransformation(shipTransformation(g, step));
            // 一行与语言无关的日志：验收靠它判「小铜船真的挪了」
            LOGGER.info("海图桌：小铜船 {} → {} 档（{}）", shownStep, step, key);
            shownStep = step;
        }
    }

    private static TextDisplayEntity spawnText(ServerWorld world, Geometry g) {
        TextDisplayEntity e = new TextDisplayEntity(EntityType.TEXT_DISPLAY, world);
        Vec3d at = toWorld(anchor, front, g.text());
        e.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        e.setBillboardMode(DisplayEntity.BillboardMode.VERTICAL);
        e.setBrightness(Brightness.FULL);                               // 夜里平台的灯不够亮也读得出
        e.setLineWidth(200);
        // Codex 二评（2026-10-05）：纯白大字配黑底读成操作面板、压过桌子 —— 缩到 0.6、底色只留约两成不透明（字色在 setText 那里）
        e.setBackground(TEXT_BACKGROUND);
        e.setTransformation(new AffineTransformation(new Vector3f(), new Quaternionf(), new Vector3f(TEXT_SCALE), new Quaternionf()));
        e.addCommandTag(TAG);
        textId = e.getUuid();                                           // 先记下再生成：载入事件会问 owns
        if (!world.spawnEntity(e)) {
            textId = null;
            LOGGER.warn("海图桌：浮字没生成出来");
            return null;
        }
        LOGGER.info("海图桌：浮字生成在 ({} {} {})", Math.round(at.x * 100) / 100.0, Math.round(at.y * 100) / 100.0,
                Math.round(at.z * 100) / 100.0);
        return e;
    }

    private static ItemDisplayEntity spawnShip(ServerWorld world, Geometry g, int step) {
        ItemDisplayEntity e = new ItemDisplayEntity(EntityType.ITEM_DISPLAY, world);
        Vec3d base = shipBase(g);
        e.refreshPositionAndAngles(base.x, base.y, base.z, 0f, 0f);
        e.setItemStack(new ItemStack(SceneItems.CHART_SHIP));
        e.setTransformationMode(ModelTransformationMode.NONE);
        e.setTeleportDuration(0);
        e.setStartInterpolation(0);
        e.setInterpolationDuration(0);
        e.setTransformation(shipTransformation(g, step));
        e.addCommandTag(TAG);
        shipId = e.getUuid();
        if (!world.spawnEntity(e)) {
            shipId = null;
            LOGGER.warn("海图桌：小铜船没生成出来");
            return null;
        }
        LOGGER.info("海图桌：小铜船生成在第 {} 档", step);
        return e;
    }

    /** 收走这一次运行生成的那两个（在哪个维度都找：清单可能已经换了）。 */
    static void clear(MinecraftServer server, String why) {
        int gone = 0;
        for (UUID id : new UUID[]{textId, shipId}) {
            if (id == null) {
                continue;
            }
            for (ServerWorld w : server.getWorlds()) {
                Entity e = w.getEntity(id);
                if (e != null) {
                    e.discard();
                    gone++;
                }
            }
        }
        textId = shipId = null;
        shownText = null;
        shownStep = -1;
        if (gone > 0) {
            LOGGER.info("海图桌：收走浮字与小铜船 {} 个（{}）", gone, why);
        }
    }

    // ---------------------------------------------------------------- 几何（纯函数，ChartTableTest 核）

    /** 小铜船实体固定站在中间那一档的船底；各档靠变换里的平移挪过去（变换走 metadata，能插值，见 {@code Backdrop}）。 */
    static Vec3d shipBase(Geometry g) {
        return toWorld(anchor, front, g.ship().get(STEPS / 2).bottom());
    }

    static AffineTransformation shipTransformation(Geometry g, int step) {
        Pose p = g.ship().get(step);
        Vec3d at = toWorld(anchor, front, p.bottom());
        Vec3d base = shipBase(g);
        return new AffineTransformation(new Vector3f((float) (at.x - base.x), (float) (at.y - base.y), (float) (at.z - base.z)),
                shipRotation(front, p), new Vector3f(1f, 1f, 1f), new Quaternionf());
    }

    /**
     * 整件模型里的一点（像素，正面朝北；锚点那一格 front_east 在 x 32–48 · z 0–16）→ 世界坐标。
     * 锚点那一格的中心是模型 (40, ·, 8)；朝向照 {@link LinerProp.Rules#offset} 那一套转（每顺时针一格 (x, z) → (−z, x)）。
     */
    static Vec3d toWorld(BlockPos anchor, Direction front, Vector3fc px) {
        float[] xz = turn(front, px.x() / 16f - 2.5f, px.z() / 16f - 0.5f);
        return new Vec3d(anchor.getX() + 0.5 + xz[0], anchor.getY() + px.y() / 16.0, anchor.getZ() + 0.5 + xz[1]);
    }

    static float[] turn(Direction front, float x, float z) {
        int k = switch (front) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> throw new IllegalArgumentException("海图桌朝向只能是水平的：" + front);
        };
        for (int i = 0; i < k; i++) {
            float t = x;
            x = -z;
            z = t;
        }
        return new float[]{x, z};
    }

    /**
     * 小铜船的转动：物品模型船头朝 +x、上是 +y；Minecraft 画物品展示时先绕 y 转半圈，所以模型的 −x 才是船头。
     * 矩阵三列 = (−船头, 法向, −(船头 × 法向))：把模型的 −x 转到船头、+y 转到斜面法向（都已按桌子朝向转到世界里）。
     */
    static Quaternionf shipRotation(Direction front, Pose p) {
        float[] b = turn(front, p.bow().x(), p.bow().z());
        float[] n = turn(front, p.normal().x(), p.normal().z());
        Vector3f bow = new Vector3f(b[0], p.bow().y(), b[1]).normalize();
        Vector3f up = new Vector3f(n[0], p.normal().y(), n[1]).normalize();
        Vector3f third = new Vector3f(bow).cross(up).negate();
        return new Matrix3f(new Vector3f(bow).negate(), up, third).getNormalizedRotation(new Quaternionf());
    }
}
