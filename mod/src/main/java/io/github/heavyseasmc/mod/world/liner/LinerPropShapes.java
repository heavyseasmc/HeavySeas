package io.github.heavyseasmc.mod.world.liner;

import java.util.ArrayList;
import java.util.List;

/**
 * 灯与家具的轮廓（也是碰撞箱），模型坐标、单位像素、正面朝北（ADR-0063）。
 *
 * <p>只求与模型大致重合的几个盒子：点得中、走不穿、坐垫上踩得上去（座面 8 像素，低于一步能迈上去的 9.6）。
 * 跨几格的件按整件写（x、z 可到 32，水晶大吊灯到 48；竖着两格高的家具 y 到 32），再按这一块所在的格切出来 ——
 * 与 {@code liner_props.py} 画整件、按格切模型同一个办法。
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
            case BED -> cut(BED, part);
            case WARDROBE -> cut(WARDROBE, part);
            case MIRROR -> cut(MIRROR, part);
            case WASHSTAND -> cut(WASHSTAND, part);
            case BOOKCASE -> cut(BOOKCASE, part);
            case WRITING_TABLE -> cut(WRITING_TABLE, part);
            case WRITING_CHAIR -> WRITING_CHAIR;
            case SCONCE -> SCONCE;
            case FIREPLACE -> cut(FIREPLACE, part);
            case OVERMANTEL -> cut(OVERMANTEL, part);
            case PALM -> cut(PALM, part);
            case PALM_TALL -> cut(PALM_TALL, part);
            case WICKER_CHAIR -> WICKER_CHAIR;
            case WICKER_TABLE -> WICKER_TABLE;
            case WICKER_SETTEE -> cut(WICKER_SETTEE, part);
            case BAR_COUNTER -> cut(BAR_COUNTER, part);
            case DAVIT -> cut(DAVIT, part);
            case VENTILATOR -> cut(VENTILATOR, part);
            case VENTILATOR_SHORT -> cut(VENTILATOR_SHORT, part);
            case DRILL_BELL -> cut(DRILL_BELL, part);
        };
    }

    /**
     * 碰撞箱与轮廓不一样的那几种：→ 这一块的碰撞盒子；{@code null} = 碰撞箱就是轮廓。
     * 棕榈（ADR 草稿 furnish）：碰撞只算盆 —— 叶子伸出这一格 4–6 像素，轮廓里有一团（点得中、看得见选中框），人从叶子底下穿得过去；
     * 盆上面那几格一个碰撞盒子都没有。
     */
    static List<double[]> collision(LinerProp.Kind kind, LinerProp.Part part) {
        return switch (kind) {
            case PALM, PALM_TALL -> part == LinerProp.Part.LOWER ? PALM_POT : List.of();
            default -> null;
        };
    }

    // A 甲板新家具（ADR 草稿 furnish）：整件写（壁炉 · 炉上件 48 × 32、吧台 64 × 32、棕榈竖着 32 / 48），按格切 ——
    //   壁炉：炉台面 · 炉身（两侧壁柱到横楣，炉口也算进去：火里走不进去）· 壁炉台 · 台上的座钟
    private static final List<double[]> FIREPLACE = List.of(box(2, 0, 1, 46, 1.5, 10), box(3.5, 0, 8.5, 44.5, 25.5, 16),
            box(2, 25.5, 6.5, 46, 27.5, 16), box(21, 27.5, 10.5, 27, 32, 13.5));
    //   炉上件：框（贴墙一层，前出到 12.75）· 冠饰 / 檐口；底框往下伸进壁炉那 4.5 像素不算（只按自己那两排切）
    private static final List<double[]> OVERMANTEL = List.of(box(4, 0, 12.75, 44, 29, 16), box(13, 29, 13, 35, 32, 16));
    //   棕榈：盆 · 茎 · 一团叶子（叶子伸出这一格的那几像素轮廓里没有 —— 轮廓只许在这一格里）
    private static final List<double[]> PALM = List.of(box(3, 0, 3, 13, 8, 13), box(5, 8, 5, 11, 16, 11), box(1, 16, 1, 15, 28, 15));
    private static final List<double[]> PALM_TALL = List.of(box(3, 0, 3, 13, 8, 13), box(5, 8, 5, 11, 24, 11), box(1, 24, 1, 15, 40, 15));
    private static final List<double[]> PALM_POT = List.of(box(3, 0, 3, 13, 8, 13));
    //   藤编扶手椅：座与裙 · 背 · 两侧扶手（座面 7.75）
    private static final List<double[]> WICKER_CHAIR = List.of(box(2, 0, 2, 14, 7.75, 14.5), box(1.75, 7.75, 12.25, 14.25, 16, 14.75),
            box(1.5, 7.75, 1.75, 4.25, 11.75, 12.25), box(11.75, 7.75, 1.75, 14.5, 11.75, 12.25));
    //   藤编小圆桌：桌面 · 四条腿与搁板一块
    private static final List<double[]> WICKER_TABLE = List.of(box(2.5, 11, 2.5, 13.5, 12.25, 13.5), box(3.5, 0, 3.5, 12.5, 11, 12.5));
    //   藤编长椅（整件 32 × 16）：同藤椅
    private static final List<double[]> WICKER_SETTEE = List.of(box(2, 0, 2, 30, 7.75, 14.5), box(1.75, 7.75, 12.25, 30.25, 16, 14.75),
            box(1.5, 7.75, 1.75, 4.25, 11.75, 12.25), box(27.75, 7.75, 1.75, 30.5, 11.75, 12.25));
    //   吧台（整件 64 × 32）：台身 · 大理石台面（高 16）· 脚踏杆 · 台后酒架（贴墙一层）
    private static final List<double[]> BAR_COUNTER = List.of(box(0.5, 0, 4, 63.5, 14.5, 16), box(0, 14.5, 2.5, 64, 16, 16),
            box(1.5, 2, 1.25, 62.5, 3, 4), box(0, 16, 11.5, 64, 32, 16));


    // 艇甲板设备（ADR 草稿 deckgear）：整件写（模型正面朝北 = 舷外，往舷外是 −z），按格切 ——
    //   吊艇架：铁座与底板（两格长）· 丝杠与轴承 · 立板与扇板 · 斜臂（每 8 像素高一块、跟着 22.5° 往舷外挪，臂扫过的每一格都有一块）·
    //   臂头与上滑车 · 吊索与下滑车（伸出这一列的那一截切掉）。镜像那一份（艇在左手）由 LinerProp 左右对调
    private static final List<double[]> DAVIT = davit();
    //   通风筒：矮座 · 管 · 喇叭身与口圈（口比一格宽、往前探出这一格的部分切掉）
    private static final List<double[]> VENTILATOR = List.of(box(1, 0, 1, 15, 1.5, 15), box(2, 1.5, 2, 14, 26, 14),
            box(0, 24, 0, 16, 42.5, 13));
    private static final List<double[]> VENTILATOR_SHORT = List.of(box(2, 0, 2, 14, 1.5, 14), box(4, 1.5, 4, 12, 11, 12),
            box(1, 9, 0, 15, 21.75, 11));

    //   开局的钟（门形钟架，整件 32 × 85）：两根柱（连底板）· 横梁 · 钟（钟口到吊环，连钟绳）；钟挂在两列的接缝上
    private static final List<double[]> DRILL_BELL = List.of(box(1, 0, 6, 6, 82.5, 11), box(26, 0, 6, 31, 82.5, 11),
            box(0, 82.5, 6.5, 32, 85, 10.5), box(11, 53, 3.5, 21, 82.5, 13.5));

    private static List<double[]> davit() {
        List<double[]> out = new ArrayList<>(List.of(box(2, 0, 1, 14, 3, 31), box(5.5, 3, 3, 10.5, 7, 30),
                box(3.5, 3, 9, 12.5, 21, 26), box(5, 96, -27, 11, 113, -21), box(6, 64, -26, 16, 97, -22)));
        for (int y = 16; y < 112; y += 8) {
            double zc = 14 - Math.tan(Math.toRadians(22.5)) * (y + 4 - 18);         // 臂的轴线：枢轴 (y 18, z 14) 起，每往上 1 往舷外 tan22.5
            out.add(box(5.5, y, zc - 3, 10.5, y + 8, zc + 3));
        }
        return List.copyOf(out);
    }

    // 客房与阅览室的家具（ADR 草稿 furniture）：整件写（床 z 到 32、衣柜 · 盥洗台 · 书柜 y 到 32），按格切 ——
    //   黄铜床：床尾一排柱与栏 · 床品（床罩 · 翻折 · 床单）· 枕头 · 床头一排柱与栏（到 16）
    private static final List<double[]> BED = List.of(box(0.25, 0, 0.25, 15.75, 14, 2), box(0.75, 3, 2, 15.25, 10.75, 30),
            box(2.5, 10.75, 23.5, 13.5, 12, 29.5), box(0.25, 0, 30, 15.75, 16, 31.75));
    //   衣柜：柜身（踢脚座到楣板）· 两层檐口
    private static final List<double[]> WARDROBE = List.of(box(0.25, 0, 4, 15.75, 29.25, 16), box(0, 29.25, 3.5, 16, 31.75, 16));
    //   魔镜（2 宽 × 3 高，嵌进主景那一版，用户 2026-10-04 选 C；整件 48 × 48，框在正中 x 8–40、两侧两列各带半边框）：
    //   框连背板（贴墙 z 12–16、底边落地）· 弧顶一级级收（照模型里每一排框的宽量的）· 顶上的小冠饰
    private static final List<double[]> MIRROR = List.of(box(8, 0, 12, 40, 40, 16), box(8.5, 40, 12, 39.5, 42, 16),
            box(9.5, 42, 12, 38.5, 43, 16), box(11, 43, 12, 37, 44, 16), box(13, 44, 12, 35, 45, 16),
            box(15.5, 45, 12, 32.5, 45.5, 16), box(19, 45.5, 12.5, 29, 47.75, 15.5));
    //   盥洗台：柜身 · 大理石台面 · 挡水板与搁板 · 龙头 · 镜子
    private static final List<double[]> WASHSTAND = List.of(box(1, 0, 5, 15, 12.5, 16), box(0.5, 12.5, 5.5, 15.5, 14, 16),
            box(0.5, 14, 13.5, 15.5, 19.75, 16), box(4.5, 14, 10.75, 11.5, 17.75, 13.5), box(2.5, 19.75, 14.25, 13.5, 32, 16));
    //   书柜：矮柜与台板 · 书架（到玻璃门的门框）· 檐口
    private static final List<double[]> BOOKCASE = List.of(box(0.5, 0, 4, 31.5, 10.5, 16), box(1.5, 10.5, 7, 30.5, 30.5, 16),
            box(0, 30.5, 5.5, 32, 32, 16));
    //   写字台：桌面与楣板 · 正中的柜 · 两头的腿 · 后沿的矮栏杆；两个座位的膝洞空着（椅子推得进去）
    private static final List<double[]> WRITING_TABLE = List.of(box(0.5, 9.5, 2.75, 31.5, 12.25, 15.25), box(13, 0, 3, 19, 9.5, 14.5),
            box(1, 0, 3.5, 2.5, 9.5, 14.5), box(29.5, 0, 3.5, 31, 9.5, 14.5), box(1, 12.25, 14.25, 31, 14.25, 14.75));
    //   写字椅：座（含四条腿）· 靠背 · 两侧扶手
    private static final List<double[]> WRITING_CHAIR = List.of(box(2.5, 0, 2, 13.5, 8.25, 13), box(2.5, 8.25, 11.25, 13.5, 15.5, 13.25),
            box(2, 7.5, 1.75, 4, 11, 11.5), box(12, 7.5, 1.75, 14, 11, 11.5));
    //   壁灯：壁座 · 铜臂 · 灯托与丝罩（只是轮廓：点得中、看得见选中框；碰撞箱是空的，见 LinerProp）
    private static final List<double[]> SCONCE = List.of(box(5.25, 5.5, 14.5, 10.75, 12.5, 16), box(7.5, 6.5, 10, 8.5, 11.5, 14.5),
            box(5.5, 11, 9, 10.5, 16, 14));

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

    /** 整件的盒子按这一块的格切出来、挪到这一格自己的坐标（竖着两格高的件也按 y 切：衣柜、盥洗台、书柜）。 */
    private static List<double[]> cut(List<double[]> whole, LinerProp.Part part) {
        double x0 = part.x * 16.0;
        double y0 = part.y * 16.0;
        double z0 = part.z * 16.0;
        List<double[]> out = new ArrayList<>();
        for (double[] b : whole) {
            double lx = Math.max(b[0], x0);
            double hx = Math.min(b[3], x0 + 16);
            double ly = Math.max(b[1], y0);
            double hy = Math.min(b[4], y0 + 16);
            double lz = Math.max(b[2], z0);
            double hz = Math.min(b[5], z0 + 16);
            if (hx - lx > 1e-6 && hy - ly > 1e-6 && hz - lz > 1e-6) {
                out.add(new double[]{lx - x0, ly - y0, lz - z0, hx - x0, hy - y0, hz - z0});
            }
        }
        return out;
    }
}
