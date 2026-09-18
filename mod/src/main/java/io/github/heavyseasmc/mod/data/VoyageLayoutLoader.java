package io.github.heavyseasmc.mod.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.heavyseasmc.engine.data.DataFormatException;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 严格读取一份航程布局（ADR-0034 §5.5.1 的字段与校验）。
 *
 * <h2>缺项即拒绝，不退回默认值</h2>
 * 证伪表里那条「找不到目标就退回默认值」：静默退回让「配错了」与「这台机器没有」结果相同。
 * 这里每一条校验都点名字段与原因，文案会原样落到发起开局的人面前。
 *
 * <h2>还没实现的字段先拒绝</h2>
 * {@code backdrops} · {@code fog} · {@code structures} 在 ADR 里已定了 schema，但各自要等它那一刀。
 * 写了却被静默忽略，与「生效了」在文件上分不开 —— 所以这一版对它们直接报「还不读」，而不是放行。
 */
public final class VoyageLayoutLoader {

    public static final int SCHEMA_VERSION = 1;

    /** 布局资源的路径前缀：{@code data/<ns>/voyage/<name>.json}。 */
    public static final String RESOURCE_DIR = "voyage";

    public static final double SPACING_MIN = 0.5;
    public static final double SPACING_MAX = 4.0;
    /** 8 座船长 7 个间距；岸不能压上船尾。 */
    public static final int SLIDE_TO_MIN = 12;
    /** {@code item_display} 追踪 10 chunk = 160 格，减一个 chunk 的余量。 */
    public static final int SLIDE_FROM_MAX = 144;

    private static final Set<String> TOP_KEYS = Set.of("schema_version", "id", "dimension", "boat", "hull", "arrival", "fog", "backdrops");
    private static final Map<String, String> RESERVED = Map.of(
            "structures", "M6 第 4 刀的后手（方案 D，还没做）");
    private static final Set<String> BOAT_KEYS = Set.of("bow", "yaw", "seat_spacing");
    private static final Set<String> HULL_KEYS = Set.of("structure", "anchor", "restore");
    private static final Set<String> ARRIVAL_KEYS = Set.of("bearing", "slide_from", "slide_to", "forceload");
    private static final Set<String> BACKDROP_KEYS = Set.of("item", "offset", "yaw", "scale", "view_range", "box");
    private static final Set<String> RESTORE_IDS = Set.of("water", "none");
    public static final double SCALE_MIN = 0.1;
    public static final double SCALE_MAX = 64.0;
    public static final float VIEW_RANGE_DEFAULT = 4f;
    /** 布景最多 3 格见方，乘 scale 就是它在世界里的尺寸；剔除盒的缺省按它算。 */
    private static final float MODEL_SPAN = 3f;

    private VoyageLayoutLoader() {
    }

    /**
     * @param source     出错时报给人看的来源名（资源 id）
     * @param expectedId 这份文件按路径应当自称的 id；写成别的就拒绝，与四份配平「自称同一变体」同一规矩
     */
    public static VoyageLayout parse(String source, Reader reader, Identifier expectedId) {
        JsonObject root = SceneJson.readObject(source, reader);
        SceneJson.requireSchemaVersion(source, root, SCHEMA_VERSION);
        for (Map.Entry<String, String> reserved : RESERVED.entrySet()) {
            if (root.has(reserved.getKey())) {
                throw DataFormatException.at(source, "顶层",
                        "字段 %s 这一版还不读（%s）—— 先删掉它".formatted(reserved.getKey(), reserved.getValue()));
            }
        }
        SceneJson.onlyKeys(source, "顶层", root, TOP_KEYS);

        Identifier id = SceneJson.identifier(source, "顶层", root, "id");
        if (!id.equals(expectedId)) {
            throw DataFormatException.at(source, "id",
                    "自称 %s，但按路径它应当是 %s".formatted(id, expectedId));
        }
        Identifier dimension = SceneJson.identifier(source, "顶层", root, "dimension");

        JsonObject boatJson = SceneJson.object(source, "顶层", root, "boat");
        SceneJson.onlyKeys(source, "boat", boatJson, BOAT_KEYS);
        double[] bow = SceneJson.numbers3(source, "boat", boatJson, "bow");
        float yaw = angle(source, "boat", boatJson, "yaw");
        double spacing = SceneJson.number(source, "boat", boatJson, "seat_spacing");
        if (spacing < SPACING_MIN || spacing > SPACING_MAX) {
            throw DataFormatException.at(source, "boat.seat_spacing",
                    "%s 不在 %s–%s 格之间".formatted(spacing, SPACING_MIN, SPACING_MAX));
        }
        VoyageLayout.Boat boat = new VoyageLayout.Boat(new Vec3d(bow[0], bow[1], bow[2]), yaw, spacing);

        Optional<VoyageLayout.Hull> hull = SceneJson.optionalObject(source, "顶层", root, "hull").map(hullJson -> {
            SceneJson.onlyKeys(source, "hull", hullJson, HULL_KEYS);
            Identifier structure = SceneJson.identifier(source, "hull", hullJson, "structure");
            int[] anchor = SceneJson.integers3(source, "hull", hullJson, "anchor");
            for (int i = 0; i < 3; i++) {
                if (anchor[i] < 0) {
                    throw DataFormatException.at(source, "hull.anchor[%d]".formatted(i),
                            "模板内的局部坐标不能是负数：" + anchor[i]);
                }
            }
            VoyageLayout.Restore restore = VoyageLayout.Restore.fromId(
                    SceneJson.oneOf(source, "hull", hullJson, "restore", RESTORE_IDS));
            // 结构模板只能按 90° 旋转；写了船体却给一个斜的朝向，船会放歪而座位不歪。
            if (Math.abs(yaw) % 90f != 0f) {
                throw DataFormatException.at(source, "boat.yaw",
                        "%s 不是 90 的倍数 —— 带船体结构的布局只能朝正东南西北".formatted(yaw));
            }
            return new VoyageLayout.Hull(structure, new Vec3i(anchor[0], anchor[1], anchor[2]), restore);
        });

        JsonObject arrivalJson = SceneJson.object(source, "顶层", root, "arrival");
        SceneJson.onlyKeys(source, "arrival", arrivalJson, ARRIVAL_KEYS);
        float bearing = angle(source, "arrival", arrivalJson, "bearing");
        int slideFrom = SceneJson.integer(source, "arrival", arrivalJson, "slide_from");
        int slideTo = SceneJson.integer(source, "arrival", arrivalJson, "slide_to");
        if (slideTo < SLIDE_TO_MIN) {
            throw DataFormatException.at(source, "arrival.slide_to",
                    "%d 小于 %d 格 —— 岸会压上船尾".formatted(slideTo, SLIDE_TO_MIN));
        }
        if (slideFrom <= slideTo) {
            throw DataFormatException.at(source, "arrival.slide_from",
                    "%d 不大于 slide_to %d —— 布景没有可滑的距离".formatted(slideFrom, slideTo));
        }
        if (slideFrom > SLIDE_FROM_MAX) {
            throw DataFormatException.at(source, "arrival.slide_from",
                    "%d 超过 %d 格 —— 超出 display 实体的追踪范围".formatted(slideFrom, SLIDE_FROM_MAX));
        }
        boolean forceload = SceneJson.optionalBool(source, "arrival", arrivalJson, "forceload", true);
        // 雾表存不存在要等两类资源都读完才核得了（SceneDataLoader 做），这里只认语法。
        Identifier fog = SceneJson.identifier(source, "顶层", root, "fog");

        // 布景：物品存不存在要问注册表（SceneDataLoader 做），这里只认语法与范围。
        List<VoyageLayout.Backdrop> backdrops = new ArrayList<>();
        JsonArray backdropsJson = SceneJson.array(source, "顶层", root, "backdrops");
        for (int i = 0; i < backdropsJson.size(); i++) {
            String where = "backdrops[%d]".formatted(i);
            JsonObject item = SceneJson.asObject(source, where, backdropsJson.get(i));
            SceneJson.onlyKeys(source, where, item, BACKDROP_KEYS);
            Identifier itemId = SceneJson.identifier(source, where, item, "item");
            double[] offset = SceneJson.numbers3(source, where, item, "offset");
            float backdropYaw = item.has("yaw") ? angle(source, where, item, "yaw") : 0f;
            double scale = SceneJson.number(source, where, item, "scale");
            if (scale < SCALE_MIN || scale > SCALE_MAX) {
                throw DataFormatException.at(source, where + ".scale", "%s 不在 %s–%s 之间".formatted(scale, SCALE_MIN, SCALE_MAX));
            }
            float viewRange = item.has("view_range") ? (float) SceneJson.number(source, where, item, "view_range") : VIEW_RANGE_DEFAULT;
            if (viewRange <= 0f) {
                throw DataFormatException.at(source, where + ".view_range", "必须大于 0");
            }
            float boxWidth;
            float boxHeight;
            if (item.has("box")) {
                JsonArray box = SceneJson.array(source, where, item, "box");
                if (box.size() != 2) {
                    throw DataFormatException.at(source, where + ".box", "应当是 [宽, 高] 两个数字");
                }
                boxWidth = box.get(0).getAsFloat();
                boxHeight = box.get(1).getAsFloat();
                if (boxWidth <= 0f || boxHeight <= 0f) {
                    throw DataFormatException.at(source, where + ".box", "宽高都要大于 0");
                }
            } else {
                // 缺省：罩住滑动全程 —— 平移是变换、不改实体位置，剔除盒得从停靠点一直罩到起滑点。
                boxWidth = 2f * (slideFrom - slideTo) + MODEL_SPAN * (float) scale;
                boxHeight = MODEL_SPAN * (float) scale;
            }
            backdrops.add(new VoyageLayout.Backdrop(itemId, new Vec3d(offset[0], offset[1], offset[2]),
                    backdropYaw, (float) scale, viewRange, boxWidth, boxHeight));
        }

        return new VoyageLayout(id, dimension, boat, hull,
                new VoyageLayout.Arrival(bearing, slideFrom, slideTo, forceload), fog, backdrops);
    }

    /** 资源 id → 布局自称的 id：{@code heavyseas:voyage/default.json} → {@code heavyseas:default}。 */
    public static Identifier layoutIdOf(Identifier resource) {
        String path = resource.getPath();
        String prefix = RESOURCE_DIR + "/";
        String suffix = ".json";
        if (!path.startsWith(prefix) || !path.endsWith(suffix) || path.length() <= prefix.length() + suffix.length()) {
            throw new IllegalArgumentException("不是布局资源的路径: " + resource);
        }
        return Identifier.of(resource.getNamespace(), path.substring(prefix.length(), path.length() - suffix.length()));
    }

    private static float angle(String source, String where, JsonObject object, String key) {
        double raw = SceneJson.number(source, where, object, key);
        if (raw < -180 || raw > 180) {
            throw DataFormatException.at(source, where + "." + key, "%s 不在 −180…180 度之间".formatted(raw));
        }
        return (float) raw;
    }
}
