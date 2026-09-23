package io.github.heavyseasmc.mod.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.heavyseasmc.engine.data.NavigationLoader;
import io.github.heavyseasmc.engine.data.ProvisionLoader;
import io.github.heavyseasmc.engine.data.RosterData;
import io.github.heavyseasmc.engine.data.RosterLoader;
import io.github.heavyseasmc.engine.data.WeatherLoader;
import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.mod.state.NavCardView;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 牌面拼出来之后的两道闸门（ADR-0039 §6 第 1、2 条），以及它们各自的红测。
 *
 * <h2>① 每一档、每个槽位都够大</h2>
 * 前两档的下限是按槽位现算的（{@link CardLayout#thresholds}），所以「牌名够不够大」在那两档<b>按构造成立</b>
 * —— 这一条在那里是恒真的。闸门真正查的是：<b>最后一档</b>在形制声明的最窄宽度（{@code floor_px}）上、
 * 牌名以外的槽位、以及口渴排（头像多大取决于那张牌点了几个人，只能按每张真牌去查）。
 *
 * <h2>② 每一档两两分得开</h2>
 * 取代 ADR-0031 §6 那条（它对<b>数据</b>算，而且只查 LOD2）。指纹 = 这一档看得见的内容 + <b>插画的像素</b>。
 * ❗插画按像素算、不按 id 算：{@code nav_00} 与 {@code nav_01} 的 id 不同，画出来却是同一个船长 ——
 * 按 id 算的话，这一对永远「分得开」。
 *
 * <p>红测与判据在同一个类里：把缺陷装回去，核对报错点名的正是被装的那一处（不看它红没红，看红在哪）。
 */
class CardFaceGateTest {

    private static final Path LAYOUT = Path.of("src", "main", "resources", "assets", "heavyseas", "cards", "layout.json");
    private static final Path ART = Path.of("src", "main", "resources", "assets", "heavyseas", "textures", "gui", "cards", "art");
    private static final Path DATA = Path.of("..", "data");
    /** 每种牌至少这么多张；少于它就是没读到，不是都分得开。 */
    private static final Map<String, Integer> MIN_CARDS = Map.of("provision", 15, "character", 6, "weather", 8, "nav", 25);

    // ---------------------------------------------------------------- 判据本体

    /** 判据 ①：返回每一处不够大的槽位。空 = 通过。 */
    static List<String> sizeProblems(CardLayout layout, List<CardFace> faces) {
        List<String> out = new ArrayList<>();
        int checked = 0;
        for (String kind : layout.kinds().keySet()) {
            CardLayout.Shape s = layout.shapeOf(kind);
            // 角标槽位只对真有角标的牌型查：天候卡一张角标都没有，那一格从来不画。
            // 哪天某张天候牌有了角标，这一条自动开始查它 —— 不写死「天候不查」。
            boolean hasBadges = faces.stream().anyMatch(f -> f.kind().equals(kind) && !f.badges().isEmpty());
            for (String tier : layout.tiers()) {
                double narrow = layout.narrowest(kind, tier);
                for (CardLayout.Requirement r : layout.requirements(kind, tier)) {
                    if (r.slot().equals("badge") && !hasBadges) {
                        continue;
                    }
                    checked++;
                    double px = r.pxAt(s.masterW(), narrow);
                    if (px + 1e-9 < r.minPx()) {
                        out.add(String.format("%s · %s · %s：牌宽 %.0f px 时只有 %.1f px（要 %d）",
                                kind, tier, r.slot(), narrow, px, r.minPx()));
                    }
                }
                CardLayout.Roll roll = layout.kind(kind).roll();
                if (roll == null) {
                    continue;
                }
                for (CardFace face : faces) {
                    if (!face.kind().equals(kind)) {
                        continue;
                    }
                    for (CardLayout.Slot slot : layout.chips(kind, tier, face.roll().size())) {
                        checked++;
                        double px = slot.d() * narrow / s.masterW();
                        if (px + 1e-9 < roll.minPx()) {
                            out.add(String.format("%s · %s · 口渴排 %s：牌宽 %.0f px 时头像只有 %.1f px（要 %d）",
                                    kind, tier, face.id(), narrow, px, roll.minPx()));
                            break;
                        }
                    }
                }
            }
        }
        if (checked == 0) {
            out.add("一个槽位都没查 —— 判据坏了，不是都够大");
        }
        return out;
    }

    /** 判据 ②：返回每一组分不开的牌。空 = 通过。 */
    static List<String> duplicateProblems(CardLayout layout, List<CardFace> faces, Map<String, String> artPrints) {
        List<String> out = new ArrayList<>();
        for (String tier : layout.tiers()) {
            Map<String, List<String>> groups = new TreeMap<>();
            for (CardFace face : faces) {
                String art = artPrints.get(face.kind() + "/" + face.id());
                if (art == null) {
                    out.add("缺插画 " + face.kind() + "/" + face.id() + " —— 它的指纹算不出来");
                    continue;
                }
                String print = face.kind() + "|" + art + "|" + face.visible(layout, tier);
                groups.computeIfAbsent(print, k -> new ArrayList<>()).add(face.id());
            }
            for (List<String> ids : groups.values()) {
                if (ids.size() > 1) {
                    out.add(tier + " 分不开：" + String.join(" / ", ids));
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 真数据

    @Test
    @DisplayName("① 每一档、每个槽位在它最窄的宽度上都够大（含每张航海卡的口渴排）")
    void everySlotBigEnough() throws IOException {
        List<String> problems = sizeProblems(layout(), faces());
        assertEquals(List.of(), problems, String.join("\n", problems));
    }

    @Test
    @DisplayName("② 每一档里，同一种牌两两分得开（插画按像素算，不按 id）")
    void everyTierDistinguishable() throws IOException {
        List<CardFace> faces = faces();
        Map<String, Integer> perKind = new HashMap<>();
        faces.forEach(f -> perKind.merge(f.kind(), 1, Integer::sum));
        MIN_CARDS.forEach((kind, min) -> assertTrue(perKind.getOrDefault(kind, 0) >= min,
                kind + " 只拼出 " + perKind.getOrDefault(kind, 0) + " 张 —— 没在查，不是都分得开"));
        List<String> problems = duplicateProblems(layout(), faces, artPrints(faces));
        assertEquals(List.of(), problems, String.join("\n", problems));
    }

    @Test
    @DisplayName("各档的界线是算出来的：L0 由牌名 25 定在 192 px，L1 由牌名 33 定在约 145 px")
    void thresholdsAreDerived() throws IOException {
        double[] t = layout().thresholds("provision");
        assertEquals(192.0, t[0], 1e-6, "L0 的下限变了 —— 看看是哪个槽位的字号或 min_px 动了");
        assertEquals(16.0 * 300 / 33, t[1], 1e-6);
        assertEquals(0.0, t[2]);
    }

    // ---------------------------------------------------------------- 红测：装回缺陷，核对红在哪

    @Test
    @DisplayName("红测 ①：L2 角标只放大 1.2 倍 → 必须红在「l2 · badge」，而不是别处")
    void redTestBadgeTooSmall() throws IOException {
        CardLayout broken = layoutWith("\"scale\": {\"l0\": 1.0, \"l1\": 1.0, \"l2\": 1.6}",
                "\"scale\": {\"l0\": 1.0, \"l1\": 1.0, \"l2\": 1.2}");
        List<String> problems = sizeProblems(broken, faces());
        assertTrue(!problems.isEmpty(), "角标缩小了，判据却说都够大");
        assertTrue(problems.stream().allMatch(p -> p.contains("· l2 · badge")),
                "红了，但红在别处：\n" + String.join("\n", problems));
    }

    @Test
    @DisplayName("红测 ①：航海卡 L2 的口渴排只许排一行 → 必须红在口渴排，且点名那几张头像多的牌")
    void redTestRollOneRow() throws IOException {
        CardLayout broken = layoutWith("\"dmax\": 72, \"gap\": 6, \"max_rows\": 2", "\"dmax\": 72, \"gap\": 6, \"max_rows\": 1");
        List<String> problems = sizeProblems(broken, faces());
        assertTrue(!problems.isEmpty(), "口渴排挤成一行，判据却说都够大");
        assertTrue(problems.stream().allMatch(p -> p.startsWith("nav · l2 · 口渴排")),
                "红了，但红在别处：\n" + String.join("\n", problems));
        assertTrue(problems.stream().anyMatch(p -> p.contains("nav_08")), "nav_08 点了六样，它必须在名单里：\n" + problems);
    }

    @Test
    @DisplayName("红测 ②：L2 不画口渴排 → 必须红在 nav_00 / nav_01 这一对（同一个船长，只差口渴）")
    void redTestRollHiddenAtL2() throws IOException {
        CardLayout broken = layoutWith("\"tiers\": [\"l0\", \"l1\", \"l2\"],\n        \"min_px\": 14",
                "\"tiers\": [\"l0\", \"l1\"],\n        \"min_px\": 14");
        List<CardFace> faces = faces();
        List<String> problems = duplicateProblems(broken, faces, artPrints(faces));
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("l2 分不开：") && p.contains("nav_00") && p.contains("nav_01")),
                "L2 拿掉口渴排之后 nav_00 / nav_01 应当分不开：\n" + String.join("\n", problems));
        assertTrue(problems.stream().allMatch(p -> p.startsWith("l2 ")), "只该红在 L2：\n" + String.join("\n", problems));
    }

    @Test
    @DisplayName("红测 ②：插画若按 id 算指纹，nav_00 / nav_01 会被误判为分得开 —— 所以必须按像素")
    void redTestArtById() throws IOException {
        CardLayout broken = layoutWith("\"tiers\": [\"l0\", \"l1\", \"l2\"],\n        \"min_px\": 14",
                "\"tiers\": [\"l0\", \"l1\"],\n        \"min_px\": 14");
        List<CardFace> faces = faces();
        Map<String, String> byId = new HashMap<>();
        faces.forEach(f -> byId.put(f.kind() + "/" + f.id(), f.id()));
        assertTrue(duplicateProblems(broken, faces, byId).isEmpty(),
                "对照组：按 id 算时这一对「分得开」—— 这正是要避免的假绿");
        assertTrue(!duplicateProblems(broken, faces, artPrints(faces)).isEmpty(), "按像素算时必须分不开");
    }

    // ---------------------------------------------------------------- 取数

    private static CardLayout layout() throws IOException {
        return CardLayout.parse(Files.newBufferedReader(LAYOUT, StandardCharsets.UTF_8));
    }

    /** 造一份坏的版面描述：❗先确认替换真的发生了 —— 注入点过期时不报错，只是不再注入（证伪表）。 */
    private static CardLayout layoutWith(String from, String to) throws IOException {
        String text = Files.readString(LAYOUT, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(text.contains(from), "注入点过期了：版面描述里没有「" + from + "」");
        return CardLayout.parse(new StringReader(text.replace(from, to)));
    }

    /** 全部真牌：物资 · 角色 · 天候 · 航海，与客户端画牌走同一个 {@link CardFaces}。 */
    static List<CardFace> faces() {
        RosterData roster = RosterLoader.load(DATA.resolve("roster").resolve("default.json"));
        Path provPath = DATA.resolve("provisions").resolve("default.json");
        List<CardFace> out = new ArrayList<>();
        for (Provision p : ProvisionLoader.loadCatalog(provPath).all()) {
            out.add(CardFaces.provision(p.id(), CardFaces.provisionBadges(p)));
        }
        for (Survivor s : roster.characters()) {
            out.add(CardFaces.character(s.id().value(), CardFaces.characterBadges(s.size(), s.survival())));
        }
        WeatherLoader.load(DATA.resolve("weather").resolve("default.json"))
                .forEach(w -> out.add(CardFaces.weather(w.id())));
        NavigationLoader.load(DATA.resolve("navigation").resolve("default.json"), roster.ids(),
                        ProvisionLoader.loadIds(provPath))
                .forEach(card -> out.add(CardFaces.nav(NavCardView.of(card))));
        return out;
    }

    /** 插画的像素指纹：解码后逐像素散列，不看文件字节（PNG 编码差一点不代表画面不同）。 */
    private static Map<String, String> artPrints(List<CardFace> faces) {
        Map<String, String> out = new LinkedHashMap<>();
        for (CardFace f : faces) {
            Path png = ART.resolve(f.kind()).resolve(f.id() + ".png");
            if (!Files.isRegularFile(png)) {
                continue;
            }
            try {
                BufferedImage im = ImageIO.read(png.toFile());
                int[] px = im.getRGB(0, 0, im.getWidth(), im.getHeight(), null, 0, im.getWidth());
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                for (int v : px) {
                    md.update((byte) (v >>> 24));
                    md.update((byte) (v >>> 16));
                    md.update((byte) (v >>> 8));
                    md.update((byte) v);
                }
                out.put(f.kind() + "/" + f.id(), HexFormat.of().formatHex(md.digest()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }
}
