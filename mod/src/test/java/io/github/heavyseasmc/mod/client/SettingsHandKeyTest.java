package io.github.heavyseasmc.mod.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设置一面上 R 只有一种意思（2026-10-13 验收 60：顶上小签写「R 手牌」、底栏写「R 恢复默认」，对局中按下去开的是手牌）。
 *
 * <p>「这一面自己接手牌键」（{@code handlesHandKey}）一处同时决定两件事：顶上那一枚「R 手牌」画不画（{@code drawNavigation}），
 * 共有的手牌键截不截这一下（{@link NavigationKeys}）。这里核对设置一面报的是「自己接」，以及报了之后那一下确实不去开手牌。
 */
class SettingsHandKeyTest {

    @Test
    @DisplayName("设置一面报「手牌键归我」：不画 R 手牌、R 不被截去开手牌；普通的一面照旧")
    void settingsOwnsTheHandKey() throws ReflectiveOperationException {
        assertTrue(blank(SettingsScreen.class).handlesHandKey(), "设置一面没报手牌键归它：顶上照样画「R 手牌」，按 R 开的是手牌");
        // 对照：没覆写的一面（表态）照旧让共有的那一组接手牌键 —— 否则上一条绿只是因为默认值变了
        assertFalse(blank(ConsentScreen.class).handlesHandKey());
        // 报了之后，那一下不归「去别处」那一组：落回这一面自己（恢复默认）
        var press = new NavigationKeys.Press(false, false, false, true, false, false, false, true, true, false, true, false);
        assertEquals(NavigationKeys.Route.NONE, NavigationKeys.route(press));
    }

    /** 不跑构造器的空壳：这两个方法都不读字段（同 EventChannelTest 的做法）。 */
    private static <T> T blank(Class<T> type) throws ReflectiveOperationException {
        Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return type.cast(unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type));
    }
}
