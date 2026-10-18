package io.github.heavyseasmc.mod.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 世界视图里一次按键交给谁（审查 2026-10-07 U4）：日志翻页默认在 ↑ ↓ End 上，
 * 把移动绑在方向键上的玩家按 ↑ 不能被本模组截走。
 */
class KeyRouteTest {

    private static final Set<String> OURS = Set.of("hand", "act", "log", "log_older", "log_newer", "log_latest");
    private static final Set<String> YIELDING = Set.of("log_older", "log_newer", "log_latest");

    private static String route(List<String> onKey, boolean live) {
        return KeyRoute.route(onKey, OURS::contains, YIELDING::contains, live);
    }

    @Test
    @DisplayName("↑ 同时绑着日志往回翻与前进：交给前进，对局里也一样")
    void logScrollYieldsToMovement() {
        assertEquals("forward", route(List.of("log_older", "forward"), true), "对局里 ↑ 被日志截走，人走不动");
        assertEquals("forward", route(List.of("forward", "log_older"), true));
        assertEquals("back", route(List.of("log_newer", "back"), false),
                "对局外也要让：一个键只存一个绑定，撞了的话轮到谁全看 HashMap");
    }

    @Test
    @DisplayName("没撞键：日志键照常归本模组")
    void logScrollAloneStaysOurs() {
        assertEquals("log_older", route(List.of("log_older"), true));
        assertNull(route(List.of("log_older"), false), "对局外不接管：照 Minecraft 自己的办法");
    }

    @Test
    @DisplayName("别的本模组键（L 与进度撞）对局里照旧先接（ADR-0042）")
    void otherOwnKeysStillClaimInGame() {
        assertEquals("log", route(List.of("advancements", "log"), true));
        assertNull(route(List.of("advancements", "log"), false));
        assertNull(route(List.of("advancements"), true), "跟本模组无关的键：不管");
    }
}
