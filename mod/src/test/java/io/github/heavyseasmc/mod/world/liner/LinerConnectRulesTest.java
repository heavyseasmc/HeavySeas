package io.github.heavyseasmc.mod.world.liner;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 大邮轮装饰方块「看邻居」的纯规则（ADR-0062 §2）。放下去连不连、物品栏里长什么样，在游戏里实拍。 */
final class LinerConnectRulesTest {

    @Test
    void carpetCellRepeatsEveryTwoBlocksAlsoBelowZero() {
        assertEquals(0, LinerConnect.Rules.cell(0, 0, 2));
        assertEquals(1, LinerConnect.Rules.cell(1, 0, 2));
        assertEquals(2, LinerConnect.Rules.cell(0, 1, 2));
        assertEquals(3, LinerConnect.Rules.cell(3, 5, 2));
        // 负坐标：花纹必须照样每 2 格一个周期接上（写成 % 时这两行是 −1 与 −3）
        assertEquals(1, LinerConnect.Rules.cell(-1, 0, 2), "x = −1 与 x = 1 是同一列");
        assertEquals(3, LinerConnect.Rules.cell(-1, -1, 2), "(−1, −1) 与 (1, 1) 是同一格");
        assertEquals(LinerConnect.Rules.cell(-7, -4, 2), LinerConnect.Rules.cell(1, 0, 2));
    }

    @Test
    void frameDrawsALineWhereverItDoesNotJoinAndCoversAllSixteenCombinations() {
        assertEquals("tblr", LinerConnect.Rules.frameMask(false, false, false, false), "孤零零一格：四边都画");
        assertEquals("c", LinerConnect.Rules.frameMask(true, true, true, true), "大框正中：一边都不画");
        assertEquals("tl", LinerConnect.Rules.frameMask(false, true, false, true), "左上角");
        assertEquals("br", LinerConnect.Rules.frameMask(true, false, true, false), "右下角");
        assertEquals("tbl", LinerConnect.Rules.frameMask(false, false, false, true), "一宽一高横排的左端");
        Set<String> all = new HashSet<>();
        for (int m = 0; m < 16; m++) {
            all.add(LinerConnect.Rules.frameMask((m & 1) != 0, (m & 2) != 0, (m & 4) != 0, (m & 8) != 0));
        }
        // 与 liner_decor.py 的 FRAME_MASKS 一一对应（贴图名 frame_<款>_<这几个字母>）
        assertEquals(Set.of("c", "t", "b", "l", "r", "tb", "tl", "tr", "bl", "br", "lr", "tbl", "tbr", "tlr", "blr", "tblr"), all);
    }

    @Test
    void wainscotFollowsTheFrameAboveIt() {
        assertEquals("c", LinerConnect.Rules.wainscotPart(false, true, true), "上面不是框：素段");
        assertEquals("s", LinerConnect.Rules.wainscotPart(true, false, false), "一宽的框：两边竖线");
        assertEquals("l", LinerConnect.Rules.wainscotPart(true, false, true), "框的左半");
        assertEquals("r", LinerConnect.Rules.wainscotPart(true, true, false), "框的右半");
        assertEquals("m", LinerConnect.Rules.wainscotPart(true, true, true), "三宽框的中间");
    }

    @Test
    void cappingEndsMeetThePilasters() {
        assertEquals("both", LinerConnect.Rules.cappingEnd(false, false));
        assertEquals("left", LinerConnect.Rules.cappingEnd(false, true));
        assertEquals("right", LinerConnect.Rules.cappingEnd(true, false));
        assertEquals("none", LinerConnect.Rules.cappingEnd(true, true));
    }

    @Test
    void carpetBorderOnTheSidesThatDoNotJoinCarpet() {
        assertEquals("", LinerConnect.Rules.carpetMask(true, true, true, true), "四边都接着：走花纹");
        assertEquals("nesw", LinerConnect.Rules.carpetMask(false, false, false, false));
        assertEquals("nw", LinerConnect.Rules.carpetMask(false, true, true, false), "西北角");
        Set<String> all = new HashSet<>();
        for (int m = 1; m < 16; m++) {
            all.add(LinerConnect.Rules.carpetMask((m & 1) == 0, (m & 2) == 0, (m & 4) == 0, (m & 8) == 0));
        }
        // 与 liner_decor.py 的 CARPET_MASKS 一一对应（贴图名 carpet_border_<这几个字母>）
        assertEquals(Set.of("n", "e", "s", "w", "ne", "ns", "nw", "es", "ew", "sw", "nes", "new", "nsw", "esw", "nesw"), all);
    }
}
