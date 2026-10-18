package io.github.heavyseasmc.mod.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Source wiring guard; runtime mixin targets are independently checked on the remapped jar. */
class VanillaHudIntegrationTest {
    @Test
    @org.junit.jupiter.api.DisplayName("HUD 入口每帧读取当前模式：创造和旁观不隐藏，离开雾海立即恢复")
    void currentModeActuallyReachesVisibilityRule() {
        var mode = new java.util.concurrent.atomic.AtomicReference<>(net.minecraft.world.GameMode.ADVENTURE);
        java.util.function.Supplier<net.minecraft.world.GameMode> current = mode::get;
        assertTrue(GameHud.hotbarHidden(true, current));
        mode.set(net.minecraft.world.GameMode.CREATIVE);
        assertFalse(GameHud.hotbarHidden(true, current), "实际模式是创造，不能用写死的冒险模式代替");
        mode.set(net.minecraft.world.GameMode.SURVIVAL);
        assertTrue(GameHud.hotbarHidden(true, current), "切回生存必须重新判定，不能沿用上一帧");
        mode.set(net.minecraft.world.GameMode.SPECTATOR);
        assertFalse(GameHud.hotbarHidden(true, current));
        mode.set(net.minecraft.world.GameMode.SURVIVAL);
        assertFalse(GameHud.hotbarHidden(false, current), "离开雾海不应继续隐藏");
    }
    private static final Path CLIENT = Path.of("src/client/java/io/github/heavyseasmc/mod/client");

    @Test
    void allBarsAndSelectedItemNameShareTheDimensionModeRule() throws IOException {
        String source = uncomment(Files.readString(CLIENT.resolve("mixin/InGameHudMixin.java")));
        for (String method : List.of("renderHotbar", "renderHeldItemTooltip", "renderStatusBars",
                "renderMountHealth", "renderExperienceBar", "renderExperienceLevel")) {
            var injection = Pattern.compile("@Inject\\(method = \"" + method
                    + "\", at = @At\\(\"HEAD\"\\), cancellable = true\\)\\s*"
                    + "private void [^{]+\\{\\s*if \\(GameHud\\.hotbarHidden\\(\\)\\) \\{\\s*ci\\.cancel\\(\\);")
                    .matcher(source);
            assertTrue(injection.find(), method + " must share the dimension/mode rule");
        }
    }

    @Test
    void hidingDoesNotDependOnGameParticipationOrTheOldPreference() throws IOException {
        String source = uncomment(Files.readString(CLIENT.resolve("GameHud.java")));
        int start = source.indexOf("public static boolean hotbarHidden() {");
        assertTrue(start >= 0);
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start);
        String method = source.substring(start, end);
        assertAll(
                () -> assertTrue(method.contains("&& hotbarHidden(SkyOverride.forWorld(client.world) != null,")),
                () -> assertTrue(method.contains("client.interactionManager::getCurrentGameMode")),
                () -> assertFalse(method.contains("ClientPrefs"), "preference must not bypass mist sea hiding"),
                () -> assertFalse(method.contains("GameComponents"), "hiding must not wait for an active game"));
    }

    private static String uncomment(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//[^\\r\\n]*", "");
    }
}
