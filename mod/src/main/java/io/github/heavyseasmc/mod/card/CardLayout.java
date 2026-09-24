package io.github.heavyseasmc.mod.card;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 牌面的槽位图（ADR-0039）：{@code assets/heavyseas/cards/layout.json} 的内存形态。
 *
 * <h2>为什么只有这一份</h2>
 * 管线烘边框（双线 · 底栏 · 航海卡的分隔线与口渴图标）、客户端拼整张（牌名 · 角标 · 口渴排）、
 * 闸门查尺寸，三处读的是同一个文件。两边各写一份坐标，迟早一处挪了一处没挪 ——
 * 字压在线上，而没有任何机器会报错（证伪表「同一份工具复制两处」）。
 *
 * <h2>为什么不依赖 Minecraft</h2>
 * 闸门是单测，要在不起客户端的情况下读它、算它。这里只用 Gson，别的一概不碰。
 *
 * <h2>各档的界线是算出来的</h2>
 * 文件里<b>没有</b>「L1 从多少像素起」这种数。每个槽位写的是它最小的那一笔要多大（{@code min_px}）
 * 与它在母版上多大；一档的下限 = 这一档里所有常设槽位要求的最大值（ADR-0039 §7.2）。
 * 写死一个界线，改字号时它不会跟着动 —— 那是一颗定时器。
 */
public record CardLayout(List<String> tiers, Map<String, Shape> shapes, Map<String, Kind> kinds) {

    public CardLayout {
        tiers = List.copyOf(tiers);
        shapes = Map.copyOf(shapes);
        kinds = Map.copyOf(kinds);
        if (tiers.isEmpty()) {
            throw new IllegalArgumentException("版面描述里一档都没有");
        }
        for (Map.Entry<String, Kind> kind : kinds.entrySet()) {
            if (!shapes.containsKey(kind.getValue().shape())) {
                throw new IllegalArgumentException(kind.getKey() + " 指向不存在的形制 " + kind.getValue().shape());
            }
        }
    }

    /** 标题带：牌名排在 [{@code x}, {@code right}] × [{@code top}, {@code bottom}] 里，字号按档。 */
    public record Title(int x, int top, int bottom, int right, Map<String, Integer> size, Set<String> tiers, int minPx) {
    }

    /** 角标：从右上角 ({@code right}, {@code top}) 往左排，每枚 {@code w}×{@code h}。L2 以右上角为锚放大。 */
    public record Badges(int right, int top, int w, int h, int gap, int digitSize, int iconSize,
                         Set<String> iconTiers, Map<String, Double> scale, Set<String> tiers, int minPx) {

        public double scaleAt(String tier) {
            return scale.getOrDefault(tier, 1.0);
        }
    }

    /** 底栏：出处徽记（烘在边框里）。这里只为算下限。 */
    public record Footer(int y, int emblem, Set<String> tiers, int minPx) {
    }

    public record Shape(int masterW, int masterH, int texW, int texH, int floorPx,
                        Title title, Badges badges, Footer footer) {
    }

    /** 信息带的一条带：圆盘按「让圆盘最大」在 1..{@code maxRows} 行里挑。 */
    public record Band(int top, int bottom, int left, int right, int dmax, int gap, int maxRows) {
    }

    /**
     * 信息带：插画窗下面那一排圆盘。一张牌至多一条，排法与画法共用 ——
     * 航海卡上它是口渴排（版面描述里的 {@code roll}），天候卡上它是效果图示（{@code effect}，ADR-0040）。
     */
    public record Roll(Map<String, Band> band, Set<String> tiers, int minPx) {
    }

    /**
     * @param roll  这种牌的信息带；没有就是 {@code null}
     * @param label 它在这种牌上叫什么（闸门报错时点名用）
     */
    public record Kind(String shape, Roll roll, String label) {
    }

    /** 版面描述里信息带可能叫的名字 → 它在报错里叫什么。一种牌只许有其中一个。 */
    private static final Map<String, String> ROW_KEYS = Map.of("roll", "口渴排", "effect", "效果图示");

    // ---------------------------------------------------------------- 读

    public static CardLayout parse(Reader in) {
        JsonObject root = JsonParser.parseReader(in).getAsJsonObject();
        int schema = root.get("schema_version").getAsInt();
        if (schema != 1) {
            throw new IllegalArgumentException("不认识的版面描述 schema_version: " + schema);
        }
        List<String> tiers = strings(root.getAsJsonArray("tiers"));
        Map<String, Shape> shapes = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("shapes").entrySet()) {
            JsonObject s = e.getValue().getAsJsonObject();
            JsonArray master = s.getAsJsonArray("master");
            JsonArray tex = s.getAsJsonArray("texture");
            JsonObject t = s.getAsJsonObject("title");
            JsonObject b = s.getAsJsonObject("badges");
            JsonObject f = s.getAsJsonObject("footer");
            shapes.put(e.getKey(), new Shape(
                    master.get(0).getAsInt(), master.get(1).getAsInt(),
                    tex.get(0).getAsInt(), tex.get(1).getAsInt(),
                    s.get("floor_px").getAsInt(),
                    new Title(t.get("x").getAsInt(), t.get("top").getAsInt(), t.get("bottom").getAsInt(),
                            t.get("right").getAsInt(), ints(t.getAsJsonObject("size")), set(t, "tiers"),
                            t.get("min_px").getAsInt()),
                    new Badges(b.get("right").getAsInt(), b.get("top").getAsInt(), b.get("w").getAsInt(),
                            b.get("h").getAsInt(), b.get("gap").getAsInt(), b.get("digit_size").getAsInt(),
                            b.get("icon_size").getAsInt(), set(b, "icon_tiers"), doubles(b.getAsJsonObject("scale")),
                            set(b, "tiers"), b.get("min_px").getAsInt()),
                    new Footer(f.get("y").getAsInt(), f.get("emblem").getAsInt(), set(f, "tiers"),
                            f.get("min_px").getAsInt())));
        }
        Map<String, Kind> kinds = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("kinds").entrySet()) {
            JsonObject k = e.getValue().getAsJsonObject();
            Roll roll = null;
            String label = null;
            List<String> rows = ROW_KEYS.keySet().stream().filter(k::has).sorted().toList();
            if (rows.size() > 1) {
                throw new IllegalArgumentException(e.getKey() + " 同时有 " + rows + " —— 一种牌至多一条信息带");
            }
            if (!rows.isEmpty()) {
                label = ROW_KEYS.get(rows.get(0));
                JsonObject r = k.getAsJsonObject(rows.get(0));
                Map<String, Band> bands = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> be : r.getAsJsonObject("band").entrySet()) {
                    JsonObject o = be.getValue().getAsJsonObject();
                    bands.put(be.getKey(), new Band(o.get("top").getAsInt(), o.get("bottom").getAsInt(),
                            o.get("left").getAsInt(), o.get("right").getAsInt(), o.get("dmax").getAsInt(),
                            o.get("gap").getAsInt(), o.get("max_rows").getAsInt()));
                }
                roll = new Roll(Map.copyOf(bands), set(r, "tiers"), r.get("min_px").getAsInt());
            }
            kinds.put(e.getKey(), new Kind(k.get("shape").getAsString(), roll, label));
        }
        return new CardLayout(tiers, shapes, kinds);
    }

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        a.forEach(x -> out.add(x.getAsString()));
        return out;
    }

    private static Set<String> set(JsonObject o, String key) {
        return Set.copyOf(new LinkedHashSet<>(strings(o.getAsJsonArray(key))));
    }

    private static Map<String, Integer> ints(JsonObject o) {
        Map<String, Integer> out = new LinkedHashMap<>();
        o.entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsInt()));
        return Map.copyOf(out);
    }

    private static Map<String, Double> doubles(JsonObject o) {
        Map<String, Double> out = new LinkedHashMap<>();
        o.entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsDouble()));
        return Map.copyOf(out);
    }

    // ---------------------------------------------------------------- 问

    public Shape shapeOf(String kind) {
        Kind k = Objects.requireNonNull(kinds.get(kind), () -> "版面描述里没有这种牌：" + kind);
        return shapes.get(k.shape());
    }

    public Kind kind(String kind) {
        return Objects.requireNonNull(kinds.get(kind), () -> "版面描述里没有这种牌：" + kind);
    }

    /** 一个槽位在某一档的要求：它在母版上最小的那一笔有多大、要到多少物理像素。 */
    public record Requirement(String slot, double masterUnits, int minPx) {

        /** 这张牌至少要多宽（物理像素），这一笔才够 {@code minPx}。 */
        public double widthNeeded(int masterW) {
            return minPx * masterW / masterUnits;
        }

        /** 牌画成 {@code cardPx} 宽时，这一笔有多少物理像素。 */
        public double pxAt(int masterW, double cardPx) {
            return masterUnits * cardPx / masterW;
        }
    }

    /** 标题带在这一档、右上角有 {@code badges} 枚角标时的可用宽（母版单位）：牌名让开角标，再空 6 个单位。 */
    public double titleBox(String kind, String tier, int badges) {
        Shape s = shapeOf(kind);
        Badges b = s.badges();
        double right = s.title().right();
        if (badges > 0 && b.tiers().contains(tier)) {
            double k = b.scaleAt(tier);
            right = Math.min(right, b.right() - badges * b.w() * k - (badges - 1) * b.gap() * k - 6);
        }
        return right - s.title().x();
    }

    /** 同 {@link #requirements(String, String, Map)}，牌名按版面描述里的字号算（不看名字多长）。 */
    public List<Requirement> requirements(String kind, String tier) {
        return requirements(kind, tier, Map.of());
    }

    /**
     * 这一档里<b>常设</b>槽位的要求（牌名 · 角标数字 · 底栏徽记）。口渴排的头像大小取决于那张牌点了几个人，
     * 不在这里 —— 它由闸门按每一张真牌去查（{@link #chips}）。
     *
     * <p>牌名的要求看的是<b>实际画出来的字号</b>：名字放不进标题带时字会缩（{@code TextFit} 先缩），
     * 而字号梯子是一级一级跳的 —— 要 55 px 只挑得到 48。所以这个数只能由客户端照梯子把每个名字挑一遍得出
     * （{@code titleUnits}：档 → 这一档里最小的那个实际字号，母版单位），这里不自己估。
     * 界线因此<b>按语言算</b>：英文名字长，它的 L1 窄一些，更小的牌直接进 L2、不画牌名 —— 不画截断的名字。
     */
    public List<Requirement> requirements(String kind, String tier, Map<String, Double> titleUnits) {
        Shape s = shapeOf(kind);
        List<Requirement> out = new ArrayList<>();
        if (s.title().tiers().contains(tier)) {
            double size = Math.min(s.title().size().get(tier), titleUnits.getOrDefault(tier, Double.MAX_VALUE));
            out.add(new Requirement("title", size, s.title().minPx()));
        }
        if (s.badges().tiers().contains(tier)) {
            out.add(new Requirement("badge", s.badges().digitSize() * s.badges().scaleAt(tier), s.badges().minPx()));
        }
        if (s.footer().tiers().contains(tier)) {
            out.add(new Requirement("footer", s.footer().emblem(), s.footer().minPx()));
        }
        return out;
    }

    /** 同 {@link #thresholds(String, Map)}，牌名按版面描述里的字号算。 */
    public double[] thresholds(String kind) {
        return thresholds(kind, Map.of());
    }

    /**
     * 每一档的物理宽度下限；最后一档没有下限（0）—— 它是兜底。
     * 下限往前传：前一档不许比后一档要求更窄（否则会出现「牌变大了反而进了更小的那一档」）。
     */
    public double[] thresholds(String kind, Map<String, Double> titleUnits) {
        Shape s = shapeOf(kind);
        double[] out = new double[tiers.size()];
        for (int i = 0; i < tiers.size() - 1; i++) {
            double need = 0;
            for (Requirement r : requirements(kind, tiers.get(i), titleUnits)) {
                need = Math.max(need, r.widthNeeded(s.masterW()));
            }
            out[i] = need;
        }
        for (int i = tiers.size() - 2; i > 0; i--) {
            out[i - 1] = Math.max(out[i - 1], out[i]);
        }
        return out;
    }

    /** 牌画成 {@code px} 物理像素宽时用哪一档（牌名按版面描述里的字号算）。 */
    public String tierFor(String kind, double px) {
        return tierFor(kind, px, Map.of());
    }

    /** 牌画成 {@code px} 物理像素宽时用哪一档；{@code titleUnits} 见 {@link #requirements(String, String, Map)}。 */
    public String tierFor(String kind, double px, Map<String, Double> titleUnits) {
        double[] t = thresholds(kind, titleUnits);
        for (int i = 0; i < t.length; i++) {
            if (px >= t[i]) {
                return tiers.get(i);
            }
        }
        return tiers.get(tiers.size() - 1);
    }

    /** 一档最窄画到多宽：前几档是算出来的下限，最后一档是形制声明的 {@code floor_px}。 */
    public double narrowest(String kind, String tier) {
        return narrowest(kind, tier, Map.of());
    }

    /** 同上，按实际字号算的界线。 */
    public double narrowest(String kind, String tier, Map<String, Double> titleUnits) {
        int i = tiers.indexOf(tier);
        double[] t = thresholds(kind, titleUnits);
        return i < t.length - 1 ? t[i] : shapeOf(kind).floorPx();
    }

    // ---------------------------------------------------------------- 口渴排

    /** 口渴排里一枚的位置（母版单位，左上角 + 直径）。 */
    public record Slot(double x, double y, double d) {
    }

    /**
     * {@code n} 枚在这一档的带里怎么排：在 1..maxRows 行里挑让直径最大的那种，每行居中。
     * 行数一样大时取行少的 —— 能一行放下就不折。
     */
    public List<Slot> chips(String kind, String tier, int n) {
        Roll roll = kind(kind).roll();
        if (roll == null || n == 0 || !roll.tiers().contains(tier)) {
            return List.of();
        }
        Band b = roll.band().get(tier);
        int bestRows = 1;
        double bestD = -1;
        for (int rows = 1; rows <= Math.min(b.maxRows(), n); rows++) {
            int perRow = (n + rows - 1) / rows;
            double byW = (b.right() - b.left() - b.gap() * (perRow - 1)) / (double) perRow;
            double byH = (b.bottom() - b.top() - b.gap() * (rows - 1)) / (double) rows;
            double d = Math.min(b.dmax(), Math.min(byW, byH));
            if (d > bestD + 1e-9) {
                bestD = d;
                bestRows = rows;
            }
        }
        List<Slot> out = new ArrayList<>(n);
        int perRow = (n + bestRows - 1) / bestRows;
        double totalH = bestRows * bestD + (bestRows - 1) * b.gap();
        double y0 = (b.top() + b.bottom()) / 2.0 - totalH / 2;
        for (int i = 0; i < n; i++) {
            int row = i / perRow;
            int inRow = Math.min(perRow, n - row * perRow);
            double rowW = inRow * bestD + (inRow - 1) * b.gap();
            double x0 = (b.left() + b.right()) / 2.0 - rowW / 2;
            out.add(new Slot(x0 + (i - row * perRow) * (bestD + b.gap()), y0 + row * (bestD + b.gap()), bestD));
        }
        return out;
    }
}
