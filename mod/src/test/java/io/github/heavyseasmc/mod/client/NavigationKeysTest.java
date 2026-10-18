package io.github.heavyseasmc.mod.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 各面共有的「去别处」的键（2026-10-13 验收：设置界面输字时按 T、/ 被拽进聊天）。 */
class NavigationKeysTest {

    /** 按下的是聊天键（T）：capturing = 这一面正在收字，active = 有对局。 */
    private static NavigationKeys.Press chat(boolean capturing, boolean active) {
        return new NavigationKeys.Press(capturing, false, false, false, true, false,
                false, active, true, false, false, false);
    }

    private static NavigationKeys.Press command(boolean capturing, boolean active) {
        return new NavigationKeys.Press(capturing, false, false, false, false, true,
                false, active, true, false, false, false);
    }

    @Test
    @DisplayName("正在输字（设置界面的文字项）：T 与 / 都是字，不去聊天")
    void typingKeepsChatAndCommandKeys() {
        assertEquals(NavigationKeys.Route.NONE, NavigationKeys.route(chat(true, true)), "输字时按 T 被拽进了聊天");
        assertEquals(NavigationKeys.Route.NONE, NavigationKeys.route(command(true, true)), "输字时按 / 被拽进了指令");
    }

    @Test
    @DisplayName("没有对局：聊天与指令不归这一组")
    void noMatchNoChatShortcut() {
        assertEquals(NavigationKeys.Route.NONE, NavigationKeys.route(chat(false, false)));
        assertEquals(NavigationKeys.Route.NONE, NavigationKeys.route(command(false, false)));
    }

    @Test
    @DisplayName("对局里、没在输字：T 开聊天、/ 开指令；手牌与座位面板上 T 是赠送")
    void chatInsideAMatch() {
        assertEquals(NavigationKeys.Route.CHAT, NavigationKeys.route(chat(false, true)));
        assertEquals(NavigationKeys.Route.COMMAND, NavigationKeys.route(command(false, true)));
        var gift = new NavigationKeys.Press(false, false, false, false, true, false,
                true, true, true, false, true, false);
        assertEquals(NavigationKeys.Route.NONE, NavigationKeys.route(gift));
    }

    @Test
    @DisplayName("二级页上按 G 回原来的决定；输字时 G 也只是字")
    void actKeyReturnsUnlessTyping() {
        var back = new NavigationKeys.Press(false, false, true, false, false, false,
                false, true, true, false, false, true);
        assertEquals(NavigationKeys.Route.BACK_TO_DECISION, NavigationKeys.route(back));
        var typing = new NavigationKeys.Press(true, false, true, false, false, false,
                false, true, true, false, false, true);
        assertEquals(NavigationKeys.Route.NONE, NavigationKeys.route(typing));
    }
}
