package io.github.heavyseasmc.mod.world.liner;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ---------------------------------------------------------------- 墙角：檐口、腰线拐内角 / 外角（同楼梯）

    private static final int N = 0;
    private static final int E = 1;
    private static final int S = 2;
    private static final int W = 3;
    private static final int[][] STEP = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    /** 一层平面上摆的同一种件：(x, z) → 正面朝向。墙与空地都不在表里（问到就是 null）。 */
    private static final class Plan {
        final Map<String, Integer> at = new HashMap<>();

        Plan put(int x, int z, int facing) {
            at.put(x + "," + z, facing);
            return this;
        }

        String shape(int x, int z) {
            return LinerConnect.Rules.cornerShape(at.get(x + "," + z), d -> at.get((x + STEP[d][0]) + "," + (z + STEP[d][1])));
        }
    }

    /** 一间屋子的内圈（负坐标：x −5…−2 · z −9…−6），四面墙的件贴着墙走；四个屋角那一格放的是南北两面墙的件。 */
    private static Plan room() {
        Plan p = new Plan();
        for (int x = -5; x <= -2; x++) {
            p.put(x, -9, S);                                   // 北墙前一排，正面朝南
            p.put(x, -6, N);                                   // 南墙前一排，正面朝北
        }
        for (int z = -8; z <= -7; z++) {
            p.put(-5, z, E);                                   // 西墙前，正面朝东
            p.put(-2, z, W);                                   // 东墙前，正面朝西
        }
        return p;
    }

    @Test
    void innerCornersTurnTowardsTheSideWallAlsoBelowZero() {
        Plan p = room();
        assertEquals("inner_left", p.shape(-5, -9), "西北角：站在正面看，西墙在左手");
        assertEquals("inner_right", p.shape(-2, -9), "东北角");
        assertEquals("inner_right", p.shape(-5, -6), "西南角：面朝南墙看，西墙在右手");
        assertEquals("inner_left", p.shape(-2, -6), "东南角");
        assertEquals("straight", p.shape(-4, -9), "北墙中间");
        assertEquals("straight", p.shape(-5, -8), "西墙那一条：身后是墙、前面是空地");
    }

    /** 一根 2 × 2 的方柱（x −1…0 · z −3…−2），一圈件贴着柱子走，四个斜对角的格子补外角。 */
    @Test
    void outerCornersFillTheDiagonalCellsAroundAPier() {
        Plan p = new Plan();
        for (int x = -1; x <= 0; x++) {
            p.put(x, -4, N).put(x, -1, S);
        }
        for (int z = -3; z <= -2; z++) {
            p.put(-2, z, W).put(1, z, E);
        }
        p.put(1, -1, S).put(-2, -1, S).put(1, -4, N).put(-2, -4, N);
        assertEquals("outer_left", p.shape(1, -1), "东南：面朝南看，凸角在左后方");
        assertEquals("outer_right", p.shape(-2, -1), "西南");
        assertEquals("outer_right", p.shape(1, -4), "东北：面朝北看，凸角在右后方");
        assertEquals("outer_left", p.shape(-2, -4), "西北");
        assertEquals("straight", p.shape(0, -1), "柱子南面中间");
        assertEquals("straight", p.shape(1, -3), "柱子东面中间：身后是柱子");
    }

    // ---------------------------------------------------------------- 贴附件第一组（ADR-0069 §2 ②）：门套 · 柱脚
    // 同一组用例在 liner_build.py --self-test 的「规则抄本对照」里逐条再跑一遍（Python 抄本与这里必须同一个答案）

    @Test
    void casingPieceFollowsWhereOnTheWallYouClick() {
        assertEquals("corner_right", LinerConnect.Rules.casingPart(0.1, 0.1), "左下角 → 门套在这一格左下角 = 门洞右上方那一块");
        assertEquals("corner_left", LinerConnect.Rules.casingPart(0.9, 0.2), "右下角");
        assertEquals("head", LinerConnect.Rules.casingPart(0.5, 0.0), "下沿正中 = 门楣");
        assertEquals("jamb_right", LinerConnect.Rules.casingPart(0.2, 0.6), "左半 → 竖条贴左沿 = 门洞右边那一侧");
        assertEquals("jamb_left", LinerConnect.Rules.casingPart(0.8, 0.99), "右半");
    }

    @Test
    void casingCarriesTheLineBesideItButHeadsOnlyTheCornice() {
        assertEquals("skirting", LinerConnect.Rules.casingTrim("jamb_left", "skirting", null));
        assertEquals("chair_rail", LinerConnect.Rules.casingTrim("jamb_right", null, "chair_rail"));
        assertEquals("none", LinerConnect.Rules.casingTrim("jamb_left", null, null));
        assertEquals("none", LinerConnect.Rules.casingTrim("head", "skirting", "chair_rail"), "门楣不配踢脚、腰线");
        assertEquals("cornice", LinerConnect.Rules.casingTrim("head", "chair_rail", "cornice"), "几样都有时薄檐口在前");
        assertEquals("cornice", LinerConnect.Rules.casingTrim("corner_right", null, "cornice"));
        assertEquals("none", LinerConnect.Rules.casingTrim("corner_left", "skirting", null));
        assertTrue(LinerConnect.Rules.casingAllows("jamb_left", "skirting"));
        assertFalse(LinerConnect.Rules.casingAllows("head", "chair_rail"));
    }

    /**
     * 一面矮层的墙（室内 3 格）从左到右：踢脚 · 门套 · 门洞两格 · 门套 · 踢脚；顶上一排薄檐口 · 左上角 · 门楣两格 · 右上角 · 薄檐口。
     * 门楣中间那两格两边都是门套：要跨过去找到薄檐口（只看紧挨着的邻居就漏了）。
     */
    @Test
    void casingFindsTheLineAcrossTheOtherCasingPieces() {
        String[] floor = {"skirting", "casing", null, null, "casing", "skirting"};
        String[] top = {"cornice", "casing", "casing", "casing", "casing", "cornice"};
        assertEquals("skirting", trimAt(floor, 1, "jamb_left"));
        assertEquals("skirting", trimAt(floor, 4, "jamb_right"));
        assertEquals("cornice", trimAt(top, 1, "corner_left"));
        assertEquals("cornice", trimAt(top, 2, "head"), "门楣左边第一格：左手跨过左上角找到薄檐口");
        assertEquals("cornice", trimAt(top, 3, "head"));
        assertEquals("cornice", trimAt(top, 4, "corner_right"));
        // 一排门楣两头都没有线（高房间里门楣不在顶上那一排）
        assertEquals("none", trimAt(new String[]{null, "casing", "casing", "casing", null}, 2, "head"));
    }

    /**
     * 客房走廊：左上角外侧紧挨着一盏吸顶灯（那一格没有檐口）—— 从门套另一头那条薄檐口接过来，门套顶上不缺一段
     * （第一版只看外侧那一边：走廊里每扇门的一个上角都没有檐口）。
     */
    @Test
    void aLampBesideOneCornerDoesNotBreakTheCorniceOverTheDoor() {
        String[] top = {"lamp", "casing", "casing", "casing", "cornice"};
        assertEquals("cornice", trimAt(top, 1, "corner_left"));
        assertEquals("cornice", trimAt(top, 2, "head"));
        assertEquals("cornice", trimAt(top, 3, "corner_right"));
    }

    @Test
    void onlyTheLinesOnTheSameWallCount() {
        assertEquals("skirting", LinerConnect.Rules.lineKind("liner_skirting", null));
        assertEquals("skirting", LinerConnect.Rules.lineKind("liner_pilaster", "skirting"), "柱脚两侧那截踢脚");
        assertEquals(null, LinerConnect.Rules.lineKind("liner_pilaster", "plinth"));
        assertEquals("casing", LinerConnect.Rules.lineKind("liner_door_casing", null));
        assertEquals(null, LinerConnect.Rules.lineKind("liner_cornice", null), "凸出檐口不并进门套");
    }

    private static String trimAt(String[] row, int i, String part) {
        String left = LinerConnect.Rules.runBeside(k -> i - k >= 0 && !"lamp".equals(row[i - k]) ? row[i - k] : null);
        String right = LinerConnect.Rules.runBeside(k -> i + k < row.length && !"lamp".equals(row[i + k]) ? row[i + k] : null);
        return LinerConnect.Rules.casingTrim(part, left, right);
    }

    @Test
    void pilasterGrowsABaseOnlyOnItsLowestBlock() {
        assertEquals("none", LinerConnect.Rules.pilasterBase(false, true, true), "不是最下一格");
        assertEquals("plinth", LinerConnect.Rules.pilasterBase(true, false, false), "两边没有踢脚：只有墩");
        assertEquals("skirting", LinerConnect.Rules.pilasterBase(true, true, false), "一边有踢脚就把两侧那截踢脚一起画上");
        assertEquals("skirting", LinerConnect.Rules.pilasterBase(true, false, true));
    }

    @Test
    void aRunThatCarriesOnPastAPerpendicularPieceStaysStraight() {
        // 北墙前一排朝南的件，正前方恰好摆着一个朝东的件 —— 但西边紧挨着的仍是同朝向的那条线：照直，不拐（楼梯也这么判）
        Plan p = new Plan().put(0, 0, S).put(-1, 0, S).put(1, 0, S).put(0, 1, E);
        assertEquals("straight", p.shape(0, 0));
        // 西边那一格空了，就是屋角
        p.at.remove("-1,0");
        assertEquals("inner_left", p.shape(0, 0));
    }
}
