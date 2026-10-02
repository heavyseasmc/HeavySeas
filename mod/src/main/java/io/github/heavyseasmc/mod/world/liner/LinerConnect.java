package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.util.math.Direction;

import java.util.function.IntFunction;

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

    /** 水平方向 ↔ 规则里用的编号：北 0 · 东 1 · 南 2 · 西 3（顺时针）。 */
    public static int index(Direction d) {
        return switch (d) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> throw new IllegalArgumentException(d.toString());
        };
    }

    public static Direction direction(int index) {
        return switch (index & 3) {
            case 0 -> Direction.NORTH;
            case 1 -> Direction.EAST;
            case 2 -> Direction.SOUTH;
            default -> Direction.WEST;
        };
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
         * 檐口、腰线这一格拐不拐角（用户 2026-10-02「两个都做，先补墙角」）：与楼梯同一套判法。
         * 「朝向」是正面朝的方向（编号见 {@link LinerConnect#index}）；{@code facingAt.apply(方向)} 回答那一边紧挨着的
         * 同一种件朝哪（不是同一种件就回 {@code null}）。
         *
         * <ul>
         *   <li>身后那一格是同一种件、朝向与自己垂直 → 外角（在凸角斜对着的那一格里补一个小方墩接住两条线）；</li>
         *   <li>否则前面那一格是同一种件、朝向垂直 → 内角（屋角那一格：沿着两面墙拐过去）；</li>
         *   <li>拐向的那一边已经有一个同朝向的同种件（这条线本来就接着往前走）→ 不拐，照直。</li>
         * </ul>
         * 左右按站在正面看的人算：拐向他左手那边是 {@code _left}。
         */
        public static String cornerShape(int facing, IntFunction<Integer> facingAt) {
            int back = opposite(facing);                       // 楼梯那一套里的「朝向」= 背后
            Integer behind = facingAt.apply(back);
            if (behind != null && perpendicular(behind, facing)) {
                int turn = opposite(behind);
                if (!sameFacing(facingAt, opposite(turn), facing)) {
                    return turn == ccw(back) ? "outer_left" : "outer_right";
                }
            }
            Integer front = facingAt.apply(facing);
            if (front != null && perpendicular(front, facing)) {
                int turn = opposite(front);
                if (!sameFacing(facingAt, turn, facing)) {
                    return turn == ccw(back) ? "inner_left" : "inner_right";
                }
            }
            return "straight";
        }

        static int opposite(int d) {
            return (d + 2) & 3;
        }

        static int ccw(int d) {
            return (d + 3) & 3;
        }

        private static boolean perpendicular(int a, int b) {
            return (a & 1) != (b & 1);
        }

        private static boolean sameFacing(IntFunction<Integer> facingAt, int side, int facing) {
            Integer f = facingAt.apply(side);
            return f != null && f == facing;
        }

        // ---------------------------------------------------------------- 贴附件第一组（ADR-0069 §2 ②）

        /**
         * 门套放下去是哪一块：看点在这一格墙面的哪儿（u 从站在正面看的人的左手 0 到右手 1，v 从下 0 到上 1）。
         * 下面那三分之一是门楣一排（左三分之一 = 门洞右上角那一块，门套在这一格的左下角；右三分之一 = 左上角；中间 = 门楣），
         * 上面那三分之二是两侧（点在左半 = 竖条贴着这一格的左沿 = 门洞右边那一侧）。
         */
        public static String casingPart(double u, double v) {
            if (v < 1.0 / 3) {
                return u < 1.0 / 3 ? "corner_right" : u > 2.0 / 3 ? "corner_left" : "head";
            }
            return u < 0.5 ? "jamb_right" : "jamb_left";
        }

        /** 门套的这一块许不许顺带画那条线：两侧许踢脚 · 腰线 · 薄檐口；门楣与上角只许薄檐口。 */
        public static boolean casingAllows(String part, String trim) {
            return trim.equals("none") || part.startsWith("jamb_") || trim.equals("cornice");
        }

        /**
         * 门套这一格顺带画哪条线：往左右两边沿墙找下去，跨过同一面墙上的门套，碰到的第一个别的东西是踢脚 / 腰线 / 薄檐口，
         * 就画那一条；几样都有时薄檐口 > 腰线 > 踢脚。两边都看：一边那一格被吸顶灯之类占了时，从门套另一头那条线接过来，门套顶上的檐口才不缺一段；
         * 两侧往门洞那边看到的是门洞前那一格（空的），不碍事。不许的组合（门楣配踢脚之类）一律 {@code none}。
         *
         * @param left  左手那一边找到的线（{@code skirting} · {@code chair_rail} · {@code cornice}；没有 = {@code null}）
         */
        public static String casingTrim(String part, String left, String right) {
            String best = "none";
            for (String t : new String[]{"skirting", "chair_rail", "cornice"}) {
                if (t.equals(left) || t.equals(right)) {
                    best = t;
                }
            }
            return casingAllows(part, best) ? best : "none";
        }

        /** 门套沿墙往一边找最多这么多格（跨过同一面墙上的门套）。 */
        public static final int CASING_SCAN = 16;

        /**
         * 沿墙往一边找那条线：{@code at.apply(i)} 回答往那一边第 i 格（i 从 1 起）是什么 —— {@code casing}（同一面墙上的门套，跨过去接着找）·
         * {@code skirting} · {@code chair_rail} · {@code cornice} · {@code null}（别的）。回碰到的第一条线，没有就 {@code null}。
         */
        public static String runBeside(IntFunction<String> at) {
            for (int i = 1; i <= CASING_SCAN; i++) {
                String k = at.apply(i);
                if (!"casing".equals(k)) {
                    return k;
                }
            }
            return null;
        }

        /**
         * 沿墙那一格对门套来说是什么（{@link #runBeside} 一格一格问的就是它；只认同一朝向的件，别的一律 {@code null}）：
         * 门套 = {@code casing}（跨过去）· 踢脚条、带踢脚的柱脚（墩两侧那一截踢脚就是这条踢脚线）= {@code skirting} ·
         * 腰线 = {@code chair_rail} · 薄檐口 = {@code cornice}。
         *
         * @param block 那一格的方块（{@code liner_door_casing} · {@code liner_skirting} · {@code liner_chair_rail} · {@code liner_cornice_thin} · 别的）
         * @param base  它的柱脚属性（没有 = {@code null}）
         */
        public static String lineKind(String block, String base) {
            return switch (block) {
                case "liner_door_casing" -> "casing";
                case "liner_skirting" -> "skirting";
                case "liner_chair_rail" -> "chair_rail";
                case "liner_cornice_thin" -> "cornice";
                default -> "skirting".equals(base) ? "skirting" : null;
            };
        }

        /**
         * 壁柱这一格的柱脚：正下方不是同一根壁柱（同一种、同一朝向、同一边）= 最下一格 → 长出柱脚；
         * 左右任一边紧挨着同一朝向的踢脚条 → 墩两侧那一截踢脚一起画。不是最下一格 = {@code none}。
         */
        public static String pilasterBase(boolean bottom, boolean skirtingLeft, boolean skirtingRight) {
            if (!bottom) {
                return "none";
            }
            return skirtingLeft || skirtingRight ? "skirting" : "plinth";
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
