package io.github.heavyseasmc.mod.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.heavyseasmc.mod.HeavySeasMod;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 每种语言 × 每张牌的名字，都要放得进牌上给它的那一格 —— 放不下就会被截断。
 *
 * <p>贴图是无字画层，牌名由 {@link CardTexture} 实时排（ADR-0037 §7.9）。排不下时 {@code GuiText}
 * 会截断，而**截断了的名字与短名字在屏幕上长得一样**：没有任何机器判据看得见它，
 * 而一张叫「大把钞…」的牌与叫「大把钞票」的牌在日志、计数、包里完全相同。
 *
 * <p>❗量宽用的是 <b>Minecraft 自己的 TextRenderer</b>（经 {@link GuiText#widthAtPx}），不是另写一份度量：
 * 判据要问权威的那一方。另写一份字宽表，就成了「拿一个模型去证明另一个模型」。
 *
 * <p>❗逐级字号各查一遍。字号梯子是离散的（{@link GuiText#BOLD_PX}），
 * 「在某个尺寸下放得下」推不出「每个尺寸下都放得下」—— 牌小下去时字号跳到下一级，
 * 而那一格是按比例缩的，两者不同步。
 *
 * <p>❗两种语言都查，而客户端一次只加载一种。所以这里**直接读语言文件**，不走 {@code Text.translatable}。
 */
final class CardNameFit {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 语言文件里，哪些前缀是「牌名」。{@code .effect} 那一类不是名字，不查。 */
    private static final Map<String, String> PREFIXES = Map.of(
            "heavyseas.provision.", "物资",
            "heavyseas.character.", "角色",
            "heavyseas.weather.", "天候");
    /** 每种语言至少要有这么多个牌名；少于它就是没读到，不是都放得下。 */
    private static final int MIN_NAMES = 30;

    private CardNameFit() {
    }

    /** 查一遍，把结果打进客户端日志。回归脚本读那几行。 */
    static void check() {
        MinecraftClient client = MinecraftClient.getInstance();
        int names = 0;
        int overflow = 0;
        for (String lang : List.of("zh_cn", "en_us")) {
            JsonObject entries = load(client, lang);
            if (entries == null) {
                LOGGER.warn("牌名核对：读不到语言文件 {} —— 这一趟没查它", lang);
                continue;
            }
            int here = 0;
            for (Map.Entry<String, com.google.gson.JsonElement> entry : entries.entrySet()) {
                String kind = kindOf(entry.getKey());
                if (kind == null) {
                    continue;
                }
                here++;
                overflow += report(lang, kind, entry.getKey(), entry.getValue().getAsString());
            }
            if (here < MIN_NAMES) {
                LOGGER.warn("牌名核对：{} 只读到 {} 个牌名 —— 没在查，不是都放得下", lang, here);
            }
            names += here;
        }
        LOGGER.info("牌名核对：{} 个名字 × {} 级字号，越界 {} 处", names, GuiText.BOLD_PX.length, overflow);
    }

    /** 这个键是不是牌名；是的话返回它那一类。 */
    private static String kindOf(String key) {
        for (Map.Entry<String, String> prefix : PREFIXES.entrySet()) {
            if (key.startsWith(prefix.getKey()) && key.indexOf('.', prefix.getKey().length()) < 0) {
                return prefix.getValue();      // heavyseas.provision.oar 要，heavyseas.provision.oar.effect 不要
            }
        }
        return null;
    }

    /**
     * 一个名字过一遍字号梯子。返回越界了几级。
     *
     * <p>可用宽按**母版**算再按字号折算：母版上牌名那一格宽 {@code NAME_BOX_MASTER}、字号
     * {@code NAME_SIZE_MASTER}，两者一起随牌缩放，所以比值不变 —— 判据因此与窗口、与界面尺寸都无关。
     */
    private static int report(String lang, String kind, String key, String name) {
        boolean landscape = key.startsWith("heavyseas.weather.");
        int boxMaster = CardTexture.nameBoxMaster(landscape);
        int bad = 0;
        for (int px : GuiText.BOLD_PX) {
            int room = Math.round(boxMaster * px / (float) CardTexture.NAME_SIZE_MASTER);
            int need = GuiText.widthAtPx(name, px, true);
            if (need > room) {
                bad++;
                if (bad == 1) {
                    LOGGER.warn("牌名越界：{} {}（{}）「{}」在 {} 级上要 {} 宽，只有 {}",
                            lang, key, kind, name, px, need, room);
                }
            }
        }
        return bad;
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
        MinecraftClient client = MinecraftClient.getInstance();
        for (String lang : List.of("zh_cn", "en_us")) {
            JsonObject entries = load(client, lang);
            if (entries == null) {
                continue;
            }
            for (Map.Entry<String, com.google.gson.JsonElement> entry : entries.entrySet()) {
                if (kindOf(entry.getKey()) != null
                        && report(lang, kindOf(entry.getKey()), entry.getKey(), entry.getValue().getAsString()) > 0) {
                    out.add(lang + " " + entry.getKey());
                }
            }
        }
        return out;
    }
}
