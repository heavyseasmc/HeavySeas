package io.github.heavyseasmc.mod.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.card.CardFaces;
import io.github.heavyseasmc.mod.card.CardLayout;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 牌名与标题带（ADR-0039 §6 第 3 条）。两件事：
 *
 * <ol>
 *   <li><b>量</b>（{@link #titleUnits}）：每种语言、每种牌、每一档，牌名<b>实际</b>用多大的字。
 *       牌是在贴图自己的像素空间里合成的（{@link CardComposite}），名字放不进标题带时字按梯子往下缩 ——
 *       梯子一级一级跳，要 55 px 只挑得到 48。所以这里照梯子把每个名字真的挑一遍，取每档最小的那个，
 *       交给 {@link CardLayout#thresholds} 算界线。英文名字长，它的 L1 因此窄一些：更小的牌直接进 L2、
 *       不画牌名，而不是画一个截断的。</li>
 *   <li><b>查</b>（{@link #check}）：在每一档<b>最窄</b>的宽度上，两条路各模拟一遍 —— 合成（贴图像素里挑字号，
 *       再随牌缩到屏幕）与合成好之前直接画上屏幕（物理像素里挑字号）—— 每个名字都必须放得下、且不小于地板。
 *       量与查若对不上（粗细、字体、语言、舍入），这里会红。</li>
 * </ol>
 *
 * <p>❗量宽用的是 <b>Minecraft 自己的 TextRenderer</b>（经 {@link GuiText#widthAtPx}），不是另写一份度量。
 * <p>❗两种语言都查，而客户端一次只加载一种。所以这里<b>直接读语言文件</b>，不走 {@code Text.translatable}。
 * <p>❗只有<b>真有插画</b>的牌才算「牌名」：界面标题 {@code heavyseas.provision.title}（Supply Crate）与牌名同一个前缀，
 * 按前缀认会把它也当成一张牌。
 */
final class CardNameFit {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 语言文件里，哪些前缀是「牌名」，属于哪种牌。{@code .effect} 那一类不是名字，不查。 */
    private static final Map<String, String> PREFIXES = Map.of(
            "heavyseas.provision.", "provision",
            "heavyseas.character.", "character",
            "heavyseas.weather.", "weather");
    /** 航海卡的牌名模板：{@code %s} 填落海的那一位。每个模板 × 每个角色名都拼一遍。 */
    private static final String NAV_TITLE = "heavyseas.nav.title.";
    /** 浮点容差：界线正好卡在 16.0 px 时，乘除回来是 15.9999 —— 取整会掉一级（2026-09-24 实测天候卡就这样红过）。 */
    private static final double EPS = 1e-6;
    /** 每种语言至少要有这么多个牌名；少于它就是没读到，不是都放得下。 */
    private static final int MIN_NAMES = 30;
    /** 按语言缓存：语言 → 牌型 → 档 → 这一档里最小的实际字号（母版单位）。 */
    private static final Map<String, Map<String, Map<String, Double>>> UNITS = new HashMap<>();

    private CardNameFit() {
    }

    /** 当前语言下，这种牌每一档牌名实际用的最小字号（母版单位）。量不到时是空的 —— 只按版面描述里的字号选档。 */
    static Map<String, Double> titleUnits(String kind) {
        String lang = MinecraftClient.getInstance().getLanguageManager().getLanguage();
        return UNITS.computeIfAbsent(lang, CardNameFit::measure).getOrDefault(kind, Map.of());
    }

    /** 这种牌最多几枚角标 —— 牌名要给它们让位。取自拼牌那一处，不在这里另数。 */
    static int maxBadges(String kind) {
        return kind.equals("character") ? CardFaces.characterBadges(0, 0).size()
                : kind.equals("weather") ? 0 : 1;
    }

    static void reset() {
        UNITS.clear();
    }

    private static Map<String, Map<String, Double>> measure(String lang) {
        Map<String, Map<String, Double>> out = new HashMap<>();
        Map<String, Named> all = names(lang);
        if (all == null) {
            return out;
        }
        CardLayout layout = CardPainter.layout();
        for (Named n : all.values()) {
            CardLayout.Shape s = layout.shapeOf(n.kind());
            double tex = s.texW() / (double) s.masterW();          // 母版单位 → 贴图像素
            for (String tier : layout.tiers()) {
                if (!s.title().tiers().contains(tier)) {
                    continue;
                }
                int got = fit(n.text(), s.title().size().get(tier) * tex,
                        layout.titleBox(n.kind(), tier, maxBadges(n.kind())) * tex);
                double units = (got < 0 ? GuiText.BOLD_PX[0] : got) / tex;
                out.computeIfAbsent(n.kind(), k -> new HashMap<>()).merge(tier, units, Math::min);
            }
        }
        return out;
    }

    /** 照字号梯子挑：不大于 {@code want} 的各级里，第一级宽度放得进 {@code box} 的。一级都放不下返回 -1（要截断）。 */
    private static int fit(String text, double want, double box) {
        for (int px : GuiText.candidates(true, (int) Math.floor(want + EPS))) {
            if (GuiText.widthAtPx(text, px, true) <= Math.floor(box + EPS)) {
                return px;
            }
        }
        return -1;
    }

    /** 查一遍，把结果打进客户端日志。回归脚本读那几行。 */
    static void check() {
        reset();
        CardLayout layout = CardPainter.layout();
        int names = 0;
        int overflow = 0;
        for (String lang : List.of("zh_cn", "en_us")) {
            Map<String, Named> all = names(lang);
            if (all == null) {
                LOGGER.warn("牌名核对：读不到语言文件 {} —— 这一趟没查它", lang);
                continue;
            }
            if (all.size() < MIN_NAMES) {
                LOGGER.warn("牌名核对：{} 只读到 {} 个牌名 —— 没在查，不是都放得下", lang, all.size());
            }
            Map<String, Map<String, Double>> units = measure(lang);
            for (String kind : units.keySet()) {
                double[] t = layout.thresholds(kind, units.get(kind));
                LOGGER.info("牌名界线：{} {} L0 ≥ {} px · L1 ≥ {} px", lang, kind, Math.round(t[0]), Math.round(t[1]));
            }
            for (Map.Entry<String, Named> e : all.entrySet()) {
                overflow += report(layout, lang, e.getKey(), e.getValue(),
                        units.getOrDefault(e.getValue().kind(), Map.of()));
            }
            names += all.size();
        }
        LOGGER.info("牌名核对：{} 个名字 × {} 级字号，越界 {} 处", names, GuiText.BOLD_PX.length, overflow);
    }

    /**
     * 一个名字，在出现牌名的每一档<b>最窄</b>的宽度上，两条路各挑一遍字号：
     * <ul>
     *   <li>合成：在贴图像素里照梯子挑，再随整张牌缩到这个宽度 —— 缩完的物理字号不许低于地板；</li>
     *   <li>直接画上屏幕（合成好之前那几帧）：在物理像素里照梯子挑 —— 同样不许低于地板。</li>
     * </ul>
     * 任何一条路上梯子一级都放不下（要截断），或挑到的低于地板，就是越界。
     */
    private static int report(CardLayout layout, String lang, String key, Named named, Map<String, Double> units) {
        CardLayout.Shape s = layout.shapeOf(named.kind());
        int badges = maxBadges(named.kind());
        int bad = 0;
        for (String tier : layout.tiers()) {
            if (!s.title().tiers().contains(tier)) {
                continue;
            }
            double w = layout.narrowest(named.kind(), tier, units);
            if (w <= 0) {
                continue;                      // 兜底那一档没有下限；牌名只在前两档出现
            }
            double size = s.title().size().get(tier);
            double box = layout.titleBox(named.kind(), tier, badges);
            double tex = s.texW() / (double) s.masterW();
            int gotTex = fit(named.text(), size * tex, box * tex);
            double composed = gotTex < 0 ? -1 : gotTex * w / s.texW();
            double k = w / s.masterW();
            int gotScreen = fit(named.text(), size * k, box * k);
            String why = null;
            if (gotTex < 0 || gotScreen < 0) {
                why = "梯子上哪一级都放不下（要截断）";
            } else if (composed + EPS < s.title().minPx()) {
                why = String.format("合成后只有 %.1f px（地板 %d）", composed, s.title().minPx());
            } else if (gotScreen < s.title().minPx()) {
                why = "直接画上屏幕时只能缩到 " + gotScreen + " px（地板 " + s.title().minPx() + "）";
            }
            if (why != null) {
                bad++;
                LOGGER.warn("牌名越界：{} {}（{} · {}）「{}」在牌宽 {} px 时{}", lang, key, named.kind(), tier,
                        named.text(), Math.round(w), why);
            }
        }
        return bad;
    }

    /** 一个要查的名字：它是哪种牌、印出来是什么。 */
    private record Named(String kind, String text) {
    }

    /** 这种语言里全部要上牌的名字。航海卡的按模板 × 角色名展开。 */
    private static Map<String, Named> names(String lang) {
        MinecraftClient client = MinecraftClient.getInstance();
        JsonObject entries = load(client, lang);
        if (entries == null) {
            return null;
        }
        Map<String, Named> out = new LinkedHashMap<>();
        List<String> characters = new ArrayList<>();
        for (Map.Entry<String, com.google.gson.JsonElement> entry : entries.entrySet()) {
            String key = entry.getKey();
            String kind = kindOf(key);
            if (kind == null || !hasArt(client, kind, key.substring(key.lastIndexOf('.') + 1))) {
                continue;
            }
            out.put(key, new Named(kind, entry.getValue().getAsString()));
            if (kind.equals("character")) {
                characters.add(entry.getValue().getAsString());
            }
        }
        for (Map.Entry<String, com.google.gson.JsonElement> entry : entries.entrySet()) {
            if (!entry.getKey().startsWith(NAV_TITLE)) {
                continue;
            }
            String template = entry.getValue().getAsString();
            if (template.contains("%s")) {
                for (String who : characters) {
                    out.put(entry.getKey() + "[" + who + "]", new Named("nav", template.replace("%s", who)));
                }
            } else {
                out.put(entry.getKey(), new Named("nav", template));
            }
        }
        return out;
    }

    private static boolean hasArt(MinecraftClient client, String kind, String id) {
        return client.getResourceManager().getResource(
                Identifier.of(HeavySeasMod.MOD_ID, "textures/gui/cards/art/" + kind + "/" + id + ".png")).isPresent();
    }

    /** 这个键是不是牌名；是的话返回它那一种牌。 */
    private static String kindOf(String key) {
        for (Map.Entry<String, String> prefix : PREFIXES.entrySet()) {
            if (key.startsWith(prefix.getKey()) && key.indexOf('.', prefix.getKey().length()) < 0) {
                return prefix.getValue();      // heavyseas.provision.oar 要，heavyseas.provision.oar.effect 不要
            }
        }
        return null;
    }

    private static JsonObject load(MinecraftClient client, String lang) {
        Optional<Resource> resource = client.getResourceManager()
                .getResource(Identifier.of(HeavySeasMod.MOD_ID, "lang/" + lang + ".json"));
        if (resource.isEmpty()) {
            return null;
        }
        try (Reader in = new InputStreamReader(resource.get().getInputStream(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(in).getAsJsonObject();
        } catch (Exception e) {
            LOGGER.warn("牌名核对：{} 读坏了：{}", lang, e.toString());
            return null;
        }
    }

    /** 给测试用：把越界的那几条列出来，不打日志。 */
    static List<String> overflows() {
        List<String> out = new ArrayList<>();
        CardLayout layout = CardPainter.layout();
        for (String lang : List.of("zh_cn", "en_us")) {
            Map<String, Named> all = names(lang);
            if (all == null) {
                continue;
            }
            Map<String, Map<String, Double>> units = measure(lang);
            for (Map.Entry<String, Named> e : all.entrySet()) {
                if (report(layout, lang, e.getKey(), e.getValue(),
                        units.getOrDefault(e.getValue().kind(), Map.of())) > 0) {
                    out.add(lang + " " + e.getKey());
                }
            }
        }
        return out;
    }
}
