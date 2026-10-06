package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.rulebook.Rulebook;
import io.github.heavyseasmc.mod.rulebook.RulebookData;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 头像说明签上「这个人有什么本事」（用户 2026-10-07：「鼠标移动到角色头像上是浮窗气泡显示角色介绍，全靠脑子记记不住」）。
 *
 * <p>取自规则书第三章每个人底下那一段（{@link Rulebook#notesUnder}）—— 与书同一个文字源头，不在 lang 里另抄一份。
 * 按客户端语言取那一份书（{@link RulebookData#pick}，与讲台上那本书同一条路），换了语言就重取；每个人只解析一次。
 */
final class CharacterNotes {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private static String language;
    private static String source = "";
    private static final Map<String, List<String>> CACHE = new HashMap<>();

    private CharacterNotes() {
    }

    /** 这个人底下那几行；书取不到、或书里没写这个人时是空表（说明签就只写名字与状态）。 */
    static List<String> of(String characterId) {
        String now = MinecraftClient.getInstance().getLanguageManager().getLanguage();
        if (!now.equals(language)) {
            language = now;
            CACHE.clear();
            try {
                var picked = RulebookData.pick(now);
                source = picked.getValue();
                // 报出用的是哪一份：退回到另一种语言与没退回，在说明签上只差在字是什么语言
                LOGGER.info("头像说明：客户端语言 {} · 用的是 {} 那一份规则书", now, picked.getKey());
            } catch (RuntimeException e) {
                source = "";
                LOGGER.warn("头像说明：取不到规则书（{}），说明签只写名字与状态", e.getMessage());
            }
        }
        return CACHE.computeIfAbsent(characterId,
                id -> source.isEmpty() ? List.of() : List.copyOf(Rulebook.notesUnder(source, "roster", id)));
    }
}
