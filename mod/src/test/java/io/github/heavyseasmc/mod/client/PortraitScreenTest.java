package io.github.heavyseasmc.mod.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 肖像画框的看牌界面（C3 第二轮 · ADR-0086 §3）：没有对局、没有世界、没有客户端也构造得出来 —— 它只认画的是谁。
 * 真客户端上弹不弹、画得对不对归实拍（主会话合并后做）。
 */
class PortraitScreenTest {

    @Test
    @DisplayName("不读对局状态也能构造：保留肖像身份与标题")
    void constructsWithoutAGame() {
        PortraitScreen s = new PortraitScreen("captain");
        assertEquals("captain", s.sitter());
        assertEquals("heavyseas.portrait.title",
                ((net.minecraft.text.TranslatableTextContent) s.getTitle().getContent()).getKey());
    }

    @Test
    @DisplayName("没说画的是谁就不开（不退回一张空牌）")
    void refusesAnEmptySitter() {
        assertThrows(IllegalArgumentException.class, () -> new PortraitScreen(""));
        assertThrows(IllegalArgumentException.class, () -> new PortraitScreen(null));
    }
}
