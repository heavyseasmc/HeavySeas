package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.util.math.Direction;

/**
 * 「看邻居」的那几样（ADR-0062 §2；用户 2026-10-02 选「自动拼框」）：大框、护墙、顶帽、地毯放下去之后，
 * 由上下左右的邻居决定自己画哪一张。世界里那一半在 {@link LinerBlock}；这里是纯规则，单测在 {@code LinerConnectRulesTest}。
 *
 * <p>「左右」一律按<b>站在正面看它的人</b>算：正面朝 {@code facing}，看的人面朝 {@code facing} 的反方向，
 * 他的左手 = {@code facing} 顺时针转一格（正面朝南 → 看的人面朝北 → 左手是西）。
 */
public final class LinerConnect {

    /** 地毯花纹的周期（格）：第五轮地毯 B 是 2 × 2 一个周期（{@code liner_decor.py} 的 carpet5b · {@code carpet_cell_ij}）。 */
    public static final int CARPET_PERIOD = 2;

    private LinerConnect() {
    }

    /** 站在正面看的人的左手方向。 */
    public static Direction viewerLeft(Direction facing) {
        return facing.rotateYClockwise();
    }

    public static Direction viewerRight(Direction facing) {
        return facing.rotateYCounterclockwise();
    }

    public static final class Rules {

        private Rules() {
        }

        /**
         * 地毯这一格是 2 × 2 花纹里的第几格：{@code floorMod(x, n) + n · floorMod(z, n)}。
         * ❗不能写成 {@code %}：负坐标上 {@code %} 是负数，花纹在 0 那条线两边会错开（单测里有负坐标那一例）。
         */
        public static int cell(int x, int z, int n) {
            return Math.floorMod(x, n) + n * Math.floorMod(z, n);
        }

        /**
         * 大框这一格画哪几边：不接同一种框的那一边画线。名字按 t · b · l · r 的次序（与 {@code liner_decor.py} 的 FRAME_MASKS 一致），
         * 四边都接着（大框正中那一格）= {@code "c"}。
         */
        public static String frameMask(boolean up, boolean down, boolean left, boolean right) {
            StringBuilder s = new StringBuilder();
            if (!up) {
                s.append('t');
            }
            if (!down) {
                s.append('b');
            }
            if (!left) {
                s.append('l');
            }
            if (!right) {
                s.append('r');
            }
            return s.isEmpty() ? "c" : s.toString();
        }

        /**
         * 护墙跟着正上方那块大框走：框在这一格画了左边 → 护墙有左竖线，画了右边 → 右竖线。
         *
         * @param frameAbove     正上方是不是同一朝向的大框
         * @param frameLeftJoins 那块框左边接着同一种框（= 没画左边）
         * @param frameRightJoins 右边同理
         * @return {@code c}（上面不是框：素段）· {@code l} · {@code r} · {@code m}（左右都接着：三宽框的中间）· {@code s}（左右都画：一宽的框）
         */
        public static String wainscotPart(boolean frameAbove, boolean frameLeftJoins, boolean frameRightJoins) {
            if (!frameAbove) {
                return "c";
            }
            if (!frameLeftJoins && !frameRightJoins) {
                return "s";
            }
            if (!frameLeftJoins) {
                return "l";
            }
            return frameRightJoins ? "m" : "r";
        }

        /** 顶帽的两头：左 / 右手边不再是同一朝向的顶帽，那一头就与壁柱合成一件。 */
        public static String cappingEnd(boolean leftSame, boolean rightSame) {
            if (!leftSame && !rightSame) {
                return "both";
            }
            if (!leftSame) {
                return "left";
            }
            return rightSame ? "none" : "right";
        }

        /**
         * 地毯这一格画哪几边的毯边：不接地毯的那几边，按 n · e · s · w 的次序（与 {@code liner_decor.py} 的 CARPET_MASKS 一致）；
         * 四边都接着 = 空串（走花纹那几张）。
         */
        public static String carpetMask(boolean north, boolean east, boolean south, boolean west) {
            StringBuilder s = new StringBuilder();
            if (!north) {
                s.append('n');
            }
            if (!east) {
                s.append('e');
            }
            if (!south) {
                s.append('s');
            }
            if (!west) {
                s.append('w');
            }
            return s.toString();
        }
    }
}
