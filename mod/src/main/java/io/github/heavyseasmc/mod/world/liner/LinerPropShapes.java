package io.github.heavyseasmc.mod.world.liner;

import java.util.ArrayList;
import java.util.List;

/**
 * 灯与家具的轮廓（也是碰撞箱），模型坐标、单位像素、正面朝北（ADR-0063）。
 *
 * <p>只求与模型大致重合的几个盒子：点得中、走不穿、坐垫上踩得上去（座面 8 像素，低于一步能迈上去的 9.6）。
 * 跨几格的件按整件写（x、z 可到 32，水晶大吊灯到 48），再按这一块所在的格切出来 —— 与 {@code liner_props.py} 画整件、按格切模型同一个办法。
 */
final class LinerPropShapes {

    private LinerPropShapes() {
    }

    /** 这一块的盒子（这一格自己的坐标 0–16）。 */
    static List<double[]> boxes(LinerProp.Kind kind, LinerProp.Part part) {
        return switch (kind) {
            case TALL_LAMP -> part == LinerProp.Part.LOWER ? TALL_LOWER : TALL_UPPER;
            case TABLE_LAMP -> TABLE_LAMP;
            case CHAIR -> CHAIR;
            case GRAND_TABLE -> cut(GRAND_TABLE, part);
            case SOFA -> cut(SOFA, part);
            case CEILING_LAMP -> CEILING_LAMP;
            case CHANDELIER -> part == LinerProp.Part.UPPER ? CHANDELIER_UPPER : CHANDELIER_LOWER;
            case GRAND_CHANDELIER -> part == LinerProp.Part.CROWN ? GRAND_CROWN : cut(GRAND_RING, part);
            case CEILING_PAIR -> cut(CEILING_PAIR, part);
            case CEILING_QUAD -> cut(CEILING_QUAD, part);
        };
    }

    // 顶灯与吊灯（ADR-0066；用户「异形物品碰撞箱不能是完整的一个方块，需要也是异形的」）：
    //   吸顶花玻璃 —— 铜框 · 两层玻璃盘 · 铜纽，贴着天花只有 5.5 像素厚
    private static final List<double[]> CEILING_LAMP = List.of(box(1.5, 14.5, 1.5, 14.5, 16, 14.5), box(2.5, 13, 2.5, 13.5, 14.5, 13.5),
            box(4.5, 12, 4.5, 11.5, 13, 11.5), box(7, 10.5, 7, 9, 12, 9));
    //   骑缝的吸顶灯（ADR-0068）：同一盏灯整件挪半格（两格一件挪到 x 16 · 2 × 2 一件挪到 (16, 16)），再按格切
    private static final List<double[]> CEILING_PAIR = moved(CEILING_LAMP, 8, 0);
    private static final List<double[]> CEILING_QUAD = moved(CEILING_LAMP, 8, 8);
    // 黄铜小吊灯：上面一格是圆座与链；下面一格是链尾、一圈蜡烛与卷臂（半径 6）、瓶身与坠
    private static final List<double[]> CHANDELIER_UPPER = List.of(box(5, 14, 5, 11, 16, 11), box(6.5, 0, 6.5, 9.5, 14, 9.5));
    private static final List<double[]> CHANDELIER_LOWER = List.of(box(6.5, 12, 6.5, 9.5, 16, 9.5), box(1.5, 7, 1.5, 14.5, 14.5, 14.5),
            box(6, 1.5, 6, 10, 7, 10));
    // 水晶大吊灯：吊杆那一格（圆座 · 吊杆与一圈短水晶帘）；下面那一层按整件 48 × 48 写（中心 24, 24），再按格切 ——
    //   半径 18 的八边形主圈连水晶帘用三块盒子拼（十字两块 + 斜角那一圈收进来的一块），上冠一块，正中铜杆与水晶球一块
    private static final List<double[]> GRAND_CROWN = List.of(box(3.5, 13, 3.5, 12.5, 16, 12.5), box(5, 4, 5, 11, 13, 11),
            box(6.5, 0, 6.5, 9.5, 4, 9.5));
    private static final List<double[]> GRAND_RING = List.of(box(5.5, 2, 16.5, 42.5, 12.5, 31.5), box(16.5, 2, 5.5, 31.5, 12.5, 42.5),
            box(9.5, 2, 9.5, 38.5, 12.5, 38.5), box(15, 7, 15, 33, 16, 33), box(21.5, 0, 21.5, 26.5, 16, 26.5));

    // 两格高的灯：灯柱与落地灯共用一套（底座 + 杆；灯头 + 灯罩），灯柱的横担也算在灯头那一块里
    private static final List<double[]> TALL_LOWER = List.of(box(3, 0, 3, 13, 4, 13), box(6, 4, 6, 10, 16, 10));
    private static final List<double[]> TALL_UPPER = List.of(box(6.5, 0, 6.5, 9.5, 5, 9.5), box(3, 3, 3, 13, 15, 13),
            box(7, 15, 7, 9, 16, 9), box(1, 2, 7.5, 15, 5, 8.5));
    private static final List<double[]> TABLE_LAMP = List.of(box(3, 0, 5, 13, 2, 11), box(7, 2, 7, 9, 8, 9),
            box(3, 8, 5.5, 13, 12.5, 10.5));
    //   第三版（ADR-0070）：坐垫冠面 8.5、靠背软包前到 z 11.5、八边形卷包顶到 12.5
    private static final List<double[]> CHAIR = List.of(box(1.5, 0, 1, 14.5, 8.5, 15), box(1.5, 5, 11.5, 14.5, 16, 16),
            box(0, 2, 0.5, 3.5, 12.5, 16), box(12.5, 2, 0.5, 16, 12.5, 16));
    // 大桌（整件 32 × 32）：近圆的桌布四层叠出来 + 正中粗柱
    private static final List<double[]> GRAND_TABLE = List.of(box(1, 7, 9, 31, 13, 23), box(3, 7, 6, 29, 13, 26),
            box(6, 7, 3, 26, 13, 29), box(10, 7, 1, 22, 13, 31), box(11.5, 0, 11.5, 20.5, 7, 20.5));
    // 沙发（整件 32 × 16）：底座与坐垫 · 靠背 · 两头卷边扶手（第三版，ADR-0070：坐垫中段前沿 z 1.5、冠面 8.5，靠背软包前到 z 10.5）
    private static final List<double[]> SOFA = List.of(box(1.5, 0, 1.5, 30.5, 8.5, 14.5), box(2.5, 5, 10.5, 29.5, 13.5, 15),
            box(0, 2, 1, 3.5, 14, 15.5), box(28.5, 2, 1, 32, 14, 15.5));

    private static double[] box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new double[]{x0, y0, z0, x1, y1, z1};
    }

    private static List<double[]> moved(List<double[]> boxes, double dx, double dz) {
        List<double[]> out = new ArrayList<>();
        for (double[] b : boxes) {
            out.add(new double[]{b[0] + dx, b[1], b[2] + dz, b[3] + dx, b[4], b[5] + dz});
        }
        return List.copyOf(out);
    }

    /** 整件的盒子按这一块的格切出来、挪到这一格自己的坐标。 */
    private static List<double[]> cut(List<double[]> whole, LinerProp.Part part) {
        double x0 = part.x * 16.0;
        double z0 = part.z * 16.0;
        List<double[]> out = new ArrayList<>();
        for (double[] b : whole) {
            double lx = Math.max(b[0], x0);
            double hx = Math.min(b[3], x0 + 16);
            double lz = Math.max(b[2], z0);
            double hz = Math.min(b[5], z0 + 16);
            if (hx - lx > 1e-6 && hz - lz > 1e-6) {
                out.add(new double[]{lx - x0, b[1], lz - z0, hx - x0, b[4], hz - z0});
            }
        }
        return out;
    }
}
