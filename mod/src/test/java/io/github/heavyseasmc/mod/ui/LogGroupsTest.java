package io.github.heavyseasmc.mod.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 航海日志哪几条归「终局」那一组（{@link LogGroups}）。 */
final class LogGroupsTest {

    private static final Path LANG = Path.of("src", "main", "resources", "assets", "heavyseas", "lang", "zh_cn.json");

    @Test
    @DisplayName("2026-10-13 第二轮验收 52：落海那一条（game.overboard）不算终局；lang 里每一个键按段认，终局三族之外一个都不算")
    void onlyTheEndgameFamiliesCount() throws IOException {
        assertFalse(LogGroups.endgameKey("heavyseas.game.overboard"), "落海：键的前缀恰好是 game.over，但它不是终局");
        assertTrue(LogGroups.endgameKey("heavyseas.game.over.landed"));
        assertTrue(LogGroups.endgameKey("heavyseas.game.final_line_removed"));
        assertTrue(LogGroups.endgameKey("heavyseas.endgame.winner"));

        JsonObject lang = JsonParser.parseString(Files.readString(LANG, StandardCharsets.UTF_8)).getAsJsonObject();
        List<String> wrong = new ArrayList<>();
        int endgame = 0;
        for (String key : lang.keySet()) {
            boolean expected = key.startsWith("heavyseas.endgame.") || key.startsWith("heavyseas.game.over.")
                    || key.startsWith("heavyseas.game.final_line");
            if (LogGroups.endgameKey(key) != expected) {
                wrong.add(key + " 应为 " + expected);
            }
            if (expected) {
                endgame++;
            }
        }
        assertTrue(lang.size() > 500 && endgame >= 10, "正向对照：真的读到了 lang（" + lang.size() + " 个键，终局 " + endgame + " 个）");
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }
}
