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
            case BUNK_BED -> cut(BUNK_BED, part);
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
            // C3 第二轮 · c3-gallery：肖像画框（背贴墙；画框灯另算，见 frameLamp）
            case PORTRAIT_SMALL -> PORTRAIT_SMALL;
            case PORTRAIT_MEDIUM -> cut(PORTRAIT_MEDIUM, part);
            case PORTRAIT_LARGE -> cut(PORTRAIT_LARGE, part);
            // C3 第二轮 · c3-table
            case CHART_TABLE -> cut(CHART_TABLE, part);
            case LECTERN -> cut(LECTERN, part);
            // C3 第二轮 · c3-deck
            case DECK_CHAIR -> cut(DECK_CHAIR, part);
            case LOOKOUT_FLOOR -> cut(LOOKOUT_FLOOR, part);
            case LOOKOUT_RIM -> cut(LOOKOUT_RIM, part);
            case HOLLOW_MAST -> mast(part, false);
            case TELESCOPE_CABINET -> TELESCOPE_CABINET;
            case ALARM_BELL -> ALARM_BELL;
            // 浴室道具（2026-10-15）
            case BATHTUB -> cut(BATHTUB, part);
            case SHOWER -> cut(SHOWER, part);
            case WATER_CLOSET -> cut(WATER_CLOSET, part);
            case BASIN -> cut(BASIN, part);
            case TOWEL_RAIL -> TOWEL_RAIL;
            case STALL_PARTITION -> cut(STALL_PARTITION, part);
            // 客房第二版的卧室道具（2026-10-15）
            case FOUR_POSTER_BED -> cut(FOUR_POSTER_BED, part);
            case DOUBLE_BED -> cut(DOUBLE_BED, part);
            case NIGHTSTAND -> NIGHTSTAND;
            case DRESSING_TABLE -> cut(DRESSING_TABLE, part);
            case FOLDING_SCREEN -> cut(FOLDING_SCREEN, part);
            // 客房第三版的卧室 / 门厅杂件（2026-10-16）
            case BED_BENCH -> cut(BED_BENCH, part);
            case CHEST_OF_DRAWERS -> CHEST_OF_DRAWERS;
            case CHEVAL_MIRROR -> cut(CHEVAL_MIRROR, part);
            case COAT_STAND -> cut(COAT_STAND, part);
            case STEAMER_TRUNK -> STEAMER_TRUNK;
            // 客房第三版的三等舱家具（2026-10-16）
            case FOLDING_TABLE -> FOLDING_TABLE;
            case STOOL -> STOOL;
            case WALL_HOOKS -> WALL_HOOKS;
            // 客房第三版的起居室 / 餐室家具（2026-10-16）
            case DINING_CHAIR -> DINING_CHAIR;
            case SIDEBOARD -> cut(SIDEBOARD, part);
            case LONGCASE_CLOCK -> cut(LONGCASE_CLOCK, part);
            case TEA_TABLE -> TEA_TABLE;
            case UPRIGHT_PIANO -> cut(UPRIGHT_PIANO, part);
            case CHAISE_LONGUE -> cut(CHAISE_LONGUE, part);
        };
    }

    // ================================================================ 客房第三版的三等舱家具（2026-10-16；样子 = liner_props_steerage.py）

    //   折叠桌：桌面（面高 12）· 两头的 X 形腿连横档 · 桌上的搪瓷缸子；两副腿之间空着（横撑只在脚下与交叉处，挡不住人也不必挡）
    static final List<double[]> FOLDING_TABLE = List.of(box(1, 11.25, 2.25, 15, 12, 13.75),
            box(1.75, 0, 2.5, 3.75, 11.25, 13.5), box(12.25, 0, 2.5, 14.25, 11.25, 13.5), box(9.5, 12, 4, 12.5, 14.25, 6));
    //   凳子：凳面（面高 7，低于一步迈得上去的 9.6）· 四条外撇的腿连横撑（脚伸到离凳心 5.3）
    static final List<double[]> STOOL = List.of(box(4.5, 6, 4.5, 11.5, 7, 11.5), box(2.75, 0, 2.75, 13.25, 6, 13.25));
    //   挂钩板（背贴墙，墙在 z 16）：板 · 三只钩与挂着的帽子、围巾、帆布包（只是轮廓：点得中；碰撞箱是空的，见 LinerProp.Rules#onWall）
    static final List<double[]> WALL_HOOKS = List.of(box(1.5, 8, 14.5, 14.5, 10.5, 16), box(1.5, 0.75, 11.25, 14.75, 10, 14.75));

    // ================================================================ 客房第三版的起居室 / 餐室家具（2026-10-16；样子 = liner_props_salon.py，整件写、按格切）

    //   餐椅：腿连座面（冠面 7.75）· 靠背（只到这一格顶：伸进上一格的冠头那一截只在模型里）
    static final List<double[]> DINING_CHAIR = List.of(box(2.25, 0, 2, 13.75, 7.75, 13.5), box(2.25, 7.75, 12.25, 13.75, 16, 14));
    //   茶几：腿、搁板与楣板 · 桌面（面高 8）· 托盘与茶具
    static final List<double[]> TEA_TABLE = List.of(box(2.25, 0, 2.25, 13.75, 7.25, 13.75), box(1.25, 7.25, 1.25, 14.75, 8, 14.75),
            box(2.25, 8, 3.75, 13.5, 11.5, 12));
    //   餐边柜（整件 32 × 16，背贴墙）：柜身连脚与台面（台面 13.75，正中一段往前凸）· 背板 · 正中的托盘（烛台伸出这一格的那一截只在模型里）
    static final List<double[]> SIDEBOARD = List.of(box(0.5, 0, 2.5, 31.5, 13.75, 15.75), box(10.75, 2, 1.75, 21.25, 13.75, 2.5),
            box(0.75, 13.75, 14.25, 31.25, 15.75, 15.5), box(12.5, 13.75, 6.5, 19.5, 14.75, 12.5));
    //   落地钟（整件 16 × 32，背贴墙）：踢脚座与底座 · 钟腰 · 钟头连檐口与山花
    static final List<double[]> LONGCASE_CLOCK = List.of(box(2.75, 0, 8.25, 13.25, 9.5, 15.5), box(4.25, 9.5, 9.25, 11.75, 19.75, 15.5),
            box(2.75, 19.75, 8.25, 13.25, 31.75, 15.5));
    //   立式钢琴（整件 32 × 32，背贴墙）：琴身（侧板到琴顶 21.5）· 键床、键盘与两头托腮 · 两只脚座与前腿 · 琴顶上的烛台与乐谱
    static final List<double[]> UPRIGHT_PIANO = List.of(box(0.5, 0, 5.75, 31.5, 21.5, 15.75), box(1, 8.75, 1.25, 31, 13.5, 5.75),
            box(1, 0, 1.25, 3, 8.75, 6), box(29, 0, 1.25, 31, 8.75, 6),
            box(2.25, 21.5, 9.75, 4.75, 29.75, 12.25), box(27.25, 21.5, 9.75, 29.75, 29.75, 12.25), box(9.5, 21.5, 8.5, 17.5, 23.25, 14));
    //   贵妃椅（整件 16 × 32，z 0 = 脚那一头）：座（冠面 7.25）· 头端连卷枕（卷枕顶 19.75，伸出这一格的那一截只在模型里）· 东侧半截靠背
    static final List<double[]> CHAISE_LONGUE = List.of(box(1.25, 0, 1, 14.75, 7.25, 27.5), box(0.75, 0, 26.5, 15.25, 16, 31.5),
            box(12.5, 7.25, 12.75, 15, 13.75, 26.5));

    // ================================================================ 客房第二版的卧室道具（2026-10-15；样子 = liner_props_bedroom.py，整件写、按格切）

    //   四柱床（整件 32 × 48 × 32）：四根立柱（方墩连瓶身 · 柱身）· 床身（床梁到床罩面）与枕头 · 床尾板 · 床头板连山花 ·
    //   床头两侧的厚帘 · 床顶一圈（帷幔连流苏 · 楣板到冠线）。床面 10 以上、床顶以下空着：人站得进床里
    static final List<double[]> FOUR_POSTER_BED = List.of(
            box(0.75, 0, 0.75, 4.25, 17, 4.25), box(27.75, 0, 0.75, 31.25, 17, 4.25),
            box(0.75, 0, 27.75, 4.25, 17, 31.25), box(27.75, 0, 27.75, 31.25, 17, 31.25),
            box(1.5, 17, 1.5, 3.5, 41, 3.5), box(28.5, 17, 1.5, 30.5, 41, 3.5),
            box(1.5, 17, 28.5, 3.5, 41, 30.5), box(28.5, 17, 28.5, 30.5, 41, 30.5),
            box(2.25, 3.5, 3, 29.75, 10, 28.875), box(5, 10, 23.25, 27, 13, 28.5),
            box(3.75, 3, 1.5, 28.25, 13.25, 3.25), box(10, 13.25, 1.75, 22, 14.75, 3),
            box(3, 3, 28.625, 29, 31.5, 30.375), box(8, 31.5, 28.875, 24, 34.5, 30.125), box(14.75, 34.5, 29, 17.25, 36.5, 30),
            box(0.75, 0.5, 21.5, 3.5, 36, 28), box(28.5, 0.5, 21.5, 31.25, 36, 28),
            box(1.5, 34.5, 1.5, 30.5, 41, 2), box(1.5, 34.5, 30, 30.5, 41, 30.5),
            box(1.5, 34.5, 2, 2, 41, 30), box(30, 34.5, 2, 30.5, 41, 30),
            box(0.5, 41, 0.5, 31.5, 47.5, 2.75), box(0.5, 41, 29.25, 31.5, 47.5, 31.5),
            box(0.5, 41, 2.75, 2.75, 47.5, 29.25), box(29.25, 41, 2.75, 31.5, 47.5, 29.25));
    //   黄铜双人床（整件 32 × 32）：四根床柱连铜球 · 床尾栏 · 床头栏连拱冠 · 床身（床梁到毯面）· 枕头
    static final List<double[]> DOUBLE_BED = List.of(
            box(0.5, 0, 0.5, 2.5, 12, 2.5), box(29.5, 0, 0.5, 31.5, 12, 2.5),
            box(0.5, 0, 29.5, 2.5, 15, 31.5), box(29.5, 0, 29.5, 31.5, 15, 31.5),
            box(2.5, 3, 1, 29.5, 10, 2), box(2.5, 3, 30, 29.5, 15, 31),
            box(1, 3, 2, 31, 10.25, 30), box(3.5, 9.5, 24, 28.5, 12, 29.25));
    //   床头柜：腿与柜身 · 桌面（面高 = 16 − STAND_SINK，台灯放在上面正落在它上面；LinerBedroomPropsTest 核对）
    static final List<double[]> NIGHTSTAND = List.of(box(2, 0, 2.25, 14, 10.75, 13.75), box(1.5, 10.75, 1.75, 14.5, 12, 14.25));
    //   梳妆台（整件 32 × 32，背贴墙）：两座抽屉柜 · 台面 · 空当里的背板 · 镜台 · 三联镜（两翼斜着伸到 z 11.5）· 台面上的瓶
    static final List<double[]> DRESSING_TABLE = List.of(
            box(0.75, 0, 3, 10.25, 11, 15.5), box(21.75, 0, 3, 31.25, 11, 15.5), box(0.25, 11, 2, 31.75, 12.25, 15.75),
            box(10, 1, 13.75, 22, 11, 15.25), box(7, 12.25, 10.5, 25, 14.25, 15.25), box(5.5, 14.25, 11.5, 26.5, 31.25, 14.5),
            box(3.5, 12.25, 6, 7.75, 15.75, 8.5));
    //   屏风（整件 16 × 32）：三扇之字折，每扇斜 45°（整排的包围盒）
    static final List<double[]> FOLDING_SCREEN = List.of(box(0.25, 0, 5, 15.75, 31.25, 11));

    // ================================================================ 客房第三版的卧室 / 门厅杂件（2026-10-16；样子 = liner_props_bedroom2.py，整件写、按格切）

    //   床尾凳（整件 32 × 16）：四条弯腿连牙板 · 软垫（座面高 7.75）
    static final List<double[]> BED_BENCH = List.of(box(2.875, 0, 4.125, 29.125, 5.25, 11.875), box(2.75, 5.25, 4.25, 29.25, 7.75, 11.75));
    //   五斗柜（一格，背贴墙）：托脚与裙脚 · 柜身连抽屉 · 托线与台面（面高 14）· 台面上的首饰盒与相框
    static final List<double[]> CHEST_OF_DRAWERS = List.of(
            box(1.25, 0, 6.75, 14.75, 2.25, 15.75), box(1.5, 2.25, 6, 14.5, 12.75, 15.75), box(0.75, 12.75, 6.25, 15.25, 14, 15.75),
            box(2, 14, 8.75, 6.5, 15.75, 12.25), box(9.25, 14, 10, 12.25, 15.75, 11.75));
    //   穿衣镜（整件 16 × 32）：两只脚连横撑 · 两根立柱（连顶珠）· 镜框连背板与山花 · 两只轴
    static final List<double[]> CHEVAL_MIRROR = List.of(
            box(1, 0, 3.5, 3, 2, 12), box(13, 0, 3.5, 15, 2, 12), box(3, 1, 7.375, 13, 1.75, 8.125),
            box(1.125, 2, 6.875, 2.875, 31, 8.625), box(13.125, 2, 6.875, 14.875, 31, 8.625),
            box(3.25, 3.5, 7, 12.75, 30.75, 9), box(0.625, 16.25, 7, 3.25, 17.75, 8.5), box(12.75, 16.25, 7, 15.375, 17.75, 8.5));
    //   衣帽架（整件 16 × 32）：四条弯腿（十字）· 立柱连顶珠 · 伞托 · 挂钩一圈 · 大衣 · 礼帽
    static final List<double[]> COAT_STAND = List.of(
            box(7.125, 0, 1.75, 8.875, 3, 14.25), box(1.75, 0, 7.125, 14.25, 3, 8.875), box(6.75, 0.75, 6.75, 9.25, 4.25, 9.25),
            box(7.25, 4.25, 7.25, 8.75, 31.25, 8.75), box(5, 5.5, 5, 11, 6, 11), box(4.25, 26.75, 4.25, 12.25, 28.5, 11.75),
            box(3.25, 10.5, 2, 12.75, 27, 5.75), box(9, 27.75, 5.5, 14, 30.75, 10.5));
    //   旅行皮箱（一格）：箱身连箍条、包角、锁与提手 · 顶上的小手提箱连提把
    static final List<double[]> STEAMER_TRUNK = List.of(box(0.25, 0, 3, 15.75, 9.5, 13.5), box(3.5, 9.5, 5.5, 12.5, 13.5, 10.5));

    /** 这一块的盒子，门开着 / 关着不一样的（空心桅杆）按 open 挑；别的件同 {@link #boxes(LinerProp.Kind, LinerProp.Part)}。 */
    static List<double[]> boxes(LinerProp.Kind kind, LinerProp.Part part, boolean open) {
        return kind == LinerProp.Kind.HOLLOW_MAST ? mast(part, open) : boxes(kind, part);
    }


    // C3 第二轮 · c3-gallery
    /** 肖像画框带灯时，这一块多出来的盒子（罩子连灯管 · 臂与底座）；灯够不着的格是空的。 */
    static List<double[]> frameLamp(LinerProp.Kind kind, LinerProp.Part part) {
        return switch (kind) {
            case PORTRAIT_MEDIUM -> cut(LAMP_MEDIUM, part);
            case PORTRAIT_LARGE -> cut(LAMP_LARGE, part);
            default -> List.of();
        };
    }

    // C3 第二轮 · c3-gallery：肖像画框（liner_props_gallery.py 的乙 · 桃花心木框）—— 框连背板一块（最凸的是铜牌，z 12.25）；
    //   中框整件 32 × 32、大框 32 × 48，按格切。画框灯：罩子连灯管（最前到 z 5.75）· 臂与底座（贴回框上）
    private static final List<double[]> PORTRAIT_SMALL = List.of(box(1.5, 1, 12.25, 14.5, 15, 16));
    private static final List<double[]> PORTRAIT_MEDIUM = List.of(box(3, 1, 12.25, 29, 31, 16));
    private static final List<double[]> PORTRAIT_LARGE = List.of(box(1, 2, 12.25, 31, 44, 16));
    private static final List<double[]> LAMP_MEDIUM = List.of(box(10, 29.25, 5.75, 22, 31.75, 8.25),
            box(14.5, 28.75, 8.25, 17.5, 30.75, 13.25));
    private static final List<double[]> LAMP_LARGE = List.of(box(9, 44.25, 5.75, 23, 46.75, 8.25),
            box(14.5, 44, 8.25, 17.5, 46.25, 15));

    /**
     * 碰撞箱与轮廓不一样的那几种：→ 这一块的碰撞盒子；{@code null} = 碰撞箱就是轮廓。
     * 棕榈（ADR 草稿 furnish）：碰撞只算盆 —— 叶子伸出这一格 4–6 像素，轮廓里有一团（点得中、看得见选中框），人从叶子底下穿得过去；
     * 盆上面那几格一个碰撞盒子都没有。
     */
    static List<double[]> collision(LinerProp.Kind kind, LinerProp.Part part) {
        return switch (kind) {
            case PALM, PALM_TALL -> part == LinerProp.Part.LOWER ? PALM_POT : List.of();
            // C3 第二轮 · c3-deck：口沿那一圈往下伸到台面、高 1.5 格（翻不出去）；警钟只有拉绳垂在人站的那一格里，不挡路（同壁灯）
            case LOOKOUT_RIM -> cutFlat(LOOKOUT_BARRIER, part);
            case ALARM_BELL -> List.of();
            case SHOWER -> cut(SHOWER_SOLID, part);
            default -> null;
        };
    }

    // ================================================================ C3 第二轮 · c3-deck（样子 = liner_props_opendeck.py；整件写、按格切，同下面那几族）

    //   甲板躺椅（整件 16 × 32，z 0 = 脚那一头）：纵梁与板条一层（座那一段顶 5）· 座垫 · 搁脚垫 · 两侧扶手 · 后仰的靠背（两级台阶近似 22.5°）
    private static final List<double[]> DECK_CHAIR = List.of(box(1.5, 0, 0.5, 14.5, 5, 31.5), box(3.25, 5, 12.75, 12.75, 8, 24.25),
            box(3.25, 4.5, 1.25, 12.75, 6, 11.75), box(0.75, 5, 17.5, 3, 9.5, 25.5), box(13, 5, 17.5, 15.25, 9.5, 25.5),
            box(2.75, 5, 22.5, 13.25, 14, 28.5), box(2.75, 14, 26, 13.25, 22, 32));

    /*
     * 瞭望台（整件 48 × 48，桅杆那一格在正中 16..32；十六边形外壁，同 liner_props_opendeck 的 NEST_*）：一条一条的带子，每 STEP 像素一条拼。
     *   台面一圈 = 台面板（这一格的格顶下 1.5 像素）；口沿一圈的轮廓 = 卷边口沿那一道（口沿那一层格底起 3.5 像素）；
     *   口沿一圈的碰撞 = 从外壁里面那一圈（边心距 NEST_INNER）往外到这一圈的外沿，从台面（口沿那一层格底下 16 像素）高到台面上 1.5 格。
     */
    static final double NEST_C = 24;
    static final double NEST_INNER = 21.5;
    static final double NEST_OUTER = 23;
    static final double NEST_RIM_OUTER = 23.75;
    /** 口沿碰撞箱的顶：台面上 1.5 格（24 像素）—— 口沿那一层的格底在台面上 16 像素，所以在这一格里是 y 8。原地起跳够不着 1.5 格（同栅栏）。 */
    static final double BARRIER_TOP = 24 - 16;
    static final double STEP = 2;
    private static final List<double[]> LOOKOUT_FLOOR = band(-1, NEST_OUTER, 14.5, 16);
    private static final List<double[]> LOOKOUT_RIM = band(NEST_INNER - 0.25, NEST_RIM_OUTER, 0, 3.5);
    static final List<double[]> LOOKOUT_BARRIER = band(NEST_INNER, 1e9, -16, BARRIER_TOP);

    /**
     * 十六边形（以桅杆中线为心、边心距 a；十六个面的法向在 0° · 22.5° · …）在离中线 u 处、另一个方向上的半宽；u 超出边心距就是 −1（那一条整条在外面）。
     * 同 liner_props_opendeck._poly_halfspan，只多了「u 超出边心距」那一条（那边只在 u ≤ 边心距的地方用，没查）。
     */
    static double halfSpan(double u, double a) {
        if (Math.abs(u) > a) {
            return -1;
        }
        double best = 1e9;
        for (int k = 0; k < 16; k++) {
            double th = 2 * Math.PI * k / 16;
            if (Math.sin(th) > 1e-9) {
                best = Math.min(best, (a - u * Math.cos(th)) / Math.sin(th));
            }
        }
        return best;
    }

    /**
     * 两圈十六边形之间的带子（里沿边心距 rIn，≤ 0 = 没有里沿；外沿 rOut，很大 = 一直到这一圈的外沿），y0..y1：顺着 z 每 STEP 像素一条。
     * 每一条按「这一条里最难的那一点」取：里沿取离中线最远的那一边（半宽最小，带子往里多盖一点、不留缝），外沿取离中线最近的那一边。
     */
    private static List<double[]> band(double rIn, double rOut, double y0, double y1) {
        List<double[]> out = new ArrayList<>();
        for (double z = 0; z < 48 - 1e-9; z += STEP) {
            double near = z < NEST_C && z + STEP > NEST_C ? 0 : Math.min(Math.abs(z - NEST_C), Math.abs(z + STEP - NEST_C));
            double far = Math.max(Math.abs(z - NEST_C), Math.abs(z + STEP - NEST_C));
            double xo = Math.min(NEST_C, halfSpan(near, rOut));
            if (xo <= 0) {
                continue;
            }
            double xi = rIn <= 0 ? -1 : halfSpan(far, rIn);
            if (xi <= 0) {
                out.add(box(NEST_C - xo, y0, z, NEST_C + xo, y1, z + STEP));
            } else if (xi < xo) {
                out.add(box(NEST_C - xo, y0, z, NEST_C - xi, y1, z + STEP));
                out.add(box(NEST_C + xi, y0, z, NEST_C + xo, y1, z + STEP));
            }
        }
        return List.copyOf(out);
    }

    //   空心桅杆（一格，模型北面 = 门那一面）：南 · 西 · 东三面壁（1.5）· 横档那一溜（对着门那一壁里面）；竖井北面也是整壁；
    //   门那两格北面：门洞两边的壁 · 门槛（连门槛帽）/ 门楣 · 门扇（关着：堵在门洞里退后 1、0.5 厚 —— C3 第三轮打磨，同 liner_props_opendeck 的
    //   DOOR_SHUT_Z；开着：贴在西壁里面）
    private static final List<double[]> MAST_WALLS = List.of(box(0, 0, 14.5, 16, 16, 16), box(0, 0, 1.5, 1.5, 16, 14.5),
            box(14.5, 0, 1.5, 16, 16, 14.5), box(4, 1.5, 13, 12, 14.5, 14.5));

    static List<double[]> mast(LinerProp.Part part, boolean open) {
        List<double[]> out = new ArrayList<>(MAST_WALLS);
        if (part == null || part == LinerProp.Part.SHAFT) {
            out.add(box(0, 0, 0, 16, 16, 1.5));
            return List.copyOf(out);
        }
        out.add(box(0, 0, 0, 2.5, 16, 1.5));
        out.add(box(13.5, 0, 0, 16, 16, 1.5));
        boolean lower = part == LinerProp.Part.DOOR_LOWER;
        out.add(lower ? box(2.5, 0, 0, 13.5, 2, 1.5) : box(2.5, 15, 0, 13.5, 16, 1.5));
        if (open) {
            out.add(lower ? box(1.5, 2.5, 1.5, 2.25, 16, 12.5) : box(1.5, 0, 1.5, 2.25, 14.5, 12.5));
        } else {
            out.add(lower ? box(2.5, 2, 1, 13.5, 16, 1.5) : box(2.5, 0, 1, 13.5, 15, 1.5));
        }
        return List.copyOf(out);
    }

    //   望远镜柜 A（背贴桅杆 z 16）：柜身连顶上托架与那一支望远镜（伸进上面那一格的那一截不算）
    private static final List<double[]> TELESCOPE_CABINET = List.of(box(2.5, 3.5, 9.25, 13.5, 14.5, 16),
            box(1.75, 14.5, 10.75, 14.25, 16, 14.25));
    //   警钟（背贴桅杆）：钟身在上面那一格里，这一格里只有拉绳、绳结、钟舌与唇的下沿 —— 轮廓放宽一圈（罩满口宽 7），拉绳好点中
    private static final List<double[]> ALARM_BELL = List.of(box(4.5, 7.5, 8.5, 11.5, 16, 15.5));
    //   警钟伸进上面那一格的那一截（上面那一格自己的坐标）：钟身连钟冠与吊环（钟顶在那一格的 y 8、吊环到 9.5）· 背板、螺栓、挑臂与斜撑
    private static final List<double[]> ALARM_BELL_ABOVE = List.of(box(4.5, 0, 8.5, 11.5, 9.5, 15.5), box(6.5, 6, 11, 9.5, 12, 16));

    /**
     * 这一件伸进正上方那一格的那一截（那一格自己的坐标 0–16）：只有警钟。C3 第三轮打磨（ADR-0086 §5.3 实测「只有钟口那一小截点得中」）：
     * 钟身挂在人站的那一格的上面一格，那一格是瞭望台口沿那一圈；Minecraft 找准星点中的方块是一格一格往前走、每一格只问那一格自己的轮廓
     * （1.21.1 {@code BlockView.raycast}），这一格的轮廓往上伸也没用 —— 视线一直在上面那一格里走、从不进这一格。所以由上面那一格（口沿）把这一截
     * 并进它自己的轮廓、点中了就把右键转给警钟（{@link LinerProp#getOutlineShape} · {@code onUse}）。别的件是空的。
     */
    static List<double[]> overhang(LinerProp.Kind kind) {
        return kind == LinerProp.Kind.ALARM_BELL ? ALARM_BELL_ABOVE : List.of();
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

    // C3 第二轮 · c3-table（ADR-0086）：整件写（海图桌 48 × 32、一层；讲台竖着两格高），按格切 ——
    //   海图桌：柜身（含踢脚座）· 正面抽屉与拉手 · 背面镶板 · 台板 · 斜板那一层（板底到木框顶、前沿连黄铜挡边）按 z 每 2.5 像素一级台阶
    //   （liner_props_table 的斜面几何量出来的，每一级取那一段里最低的板底与最高的框顶）· 后排两根撑杆。
    //   斜面在后排高过一格（到 26 像素），盒子只许在这一格里 —— 切到 16 为止：后排那一格的碰撞到台板顶（11.5）、前排到 16
    private static final List<double[]> CHART_TABLE = List.of(box(1, 0, 1.5, 47, 10.5, 31), box(1.5, 1.75, 0.5, 46.5, 10.25, 1.5),
            box(1.5, 2, 31, 46.5, 9.5, 31.5), box(0.5, 10.5, 0.5, 47.5, 11.5, 31.5),
            box(0.5, 11.5, 0, 47.5, 14.75, 2.5), box(0.5, 12.5, 2.5, 47.5, 15.75, 5), box(0.5, 13.5, 5, 47.5, 16.75, 7.5),
            box(0.5, 14.5, 7.5, 47.5, 17.75, 10), box(0.5, 15.5, 10, 47.5, 18.75, 12.5),
            box(7.5, 11.5, 24, 8.25, 21.75, 24.75), box(39.75, 11.5, 24, 40.5, 21.75, 24.75));
    //   讲台：方座 · 柱身 · 帽檐 · 颈 · 书托与书（斜 22.5°，按 z 三像素一级）· 灯杆与杆头 · 横臂与灯座 · 绿罩
    private static final List<double[]> LECTERN = List.of(box(2, 0, 2, 14, 2.5, 14), box(4.5, 2.5, 4.5, 11.5, 13.5, 11.5),
            box(3.5, 13.5, 3.5, 12.5, 14.5, 12.5), box(5.5, 14.5, 5.5, 10.5, 17, 10.5),
            box(1, 13.5, 0.5, 15, 18.5, 3), box(1, 13.75, 3, 15, 19.75, 6), box(1, 15, 6, 15, 21, 9), box(1, 16, 9, 15, 22.25, 12),
            box(1, 17.25, 12, 15, 22.5, 14), box(7.25, 19.75, 12.75, 8.75, 27.5, 14.25), box(7, 25.25, 7, 9, 27, 13),
            box(4.5, 23.75, 6, 11.5, 25.75, 10));

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
    //   上下铺（整件 16 × 32 × 32，按 y、z 都切；样子 = liner_props.py 的 bunk_bed）：四根立柱连柱帽 · 两层铺位（床梁到床垫面）·
    //   两层的床尾板与床头板 · 上铺东侧的护栏 · 床尾西半的梯子。两层之间（下铺床垫面 7 到上铺床梁底 18.5）空着：下铺躺得进去
    private static final List<double[]> BUNK_BED = List.of(
            box(0.25, 0, 0.25, 2.25, 31.25, 2.25), box(13.75, 0, 0.25, 15.75, 31.25, 2.25),
            box(0.25, 0, 29.75, 2.25, 31.25, 31.75), box(13.75, 0, 29.75, 15.75, 31.25, 31.75),
            box(0.75, 2.5, 2, 15.25, 7, 30), box(2, 2, 0.75, 14, 9.5, 2), box(2, 2, 30.25, 14, 13.5, 31.5),
            box(0.75, 18.5, 2, 15.25, 23, 30), box(2, 18, 0.75, 14, 25, 2), box(2, 18, 30.25, 14, 28.5, 31.5),
            box(14.125, 20.5, 2, 15.375, 27.75, 30), box(2.5, 0, 0.25, 7.5, 25.5, 1.5));
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
    // 浴室道具（2026-10-15；样子 = liner_props.py 的同名件，整件写、按格切）
    //   浴缸（整件 16 × 32，z 0 = foot）：缸肚与缸底（到缸里的底面 4.5）· 四面缸壁连缸沿（到 11.25；中间空着，人站得进去）·
    //   head 那一头的一对龙头 · 缸外的溢水管
    private static final List<double[]> BATHTUB = List.of(box(2, 0, 2.5, 14, 4.5, 29.5),
            box(1.5, 4.5, 2, 3.25, 11.25, 30), box(12.75, 4.5, 2, 14.5, 11.25, 30),
            box(3.25, 4.5, 2, 12.75, 11.25, 4), box(3.25, 4.5, 28, 12.75, 11.25, 30),
            box(4.25, 11.25, 26.75, 11.75, 14.25, 30), box(7.5, 0, 30, 8.5, 10, 30.5));
    //   花洒淋浴（整件 16 × 32 高）：底盘 · 护墙板与压条 · 水管与阀 · 立管 · 横臂 · 莲蓬 · 帘杆三根 · 收起的布帘
    private static final List<double[]> SHOWER = List.of(box(0.5, 0, 0.5, 15.5, 3, 14.5), box(0.5, 0, 14.5, 15.5, 30.5, 16),
            box(0.25, 30.5, 14, 15.75, 31.5, 16), box(4, 17.25, 12, 12, 19.75, 14.5), box(7.5, 18.5, 13.5, 8.5, 30.5, 14.5),
            box(7.5, 30.5, 6.5, 8.5, 31.5, 14), box(4.5, 29.25, 4, 11.5, 30.5, 11),
            box(0.75, 30.75, 0.75, 15.25, 31.5, 1.5), box(0.75, 30.75, 1.5, 1.5, 31.5, 14), box(14.5, 30.75, 1.5, 15.25, 31.5, 14),
            box(1, 4, 1.25, 2.75, 31.25, 6));
    //   花洒淋浴的碰撞：去掉横臂与莲蓬 —— 站在底盘（3 高）上的人头顶到 3 + 28.8 = 31.8，莲蓬底 29.25，挡着就站不进去；
    //   帘杆只在前沿与两侧（人站在正中，两边各差 3 像素以上），不碰头
    private static final List<double[]> SHOWER_SOLID = List.of(box(0.5, 0, 0.5, 15.5, 3, 14.5), box(0.5, 0, 14.5, 15.5, 30.5, 16),
            box(0.25, 30.5, 14, 15.75, 31.5, 16), box(4, 17.25, 12, 12, 19.75, 14.5), box(7.5, 18.5, 13.5, 8.5, 30.5, 14.5),
            box(0.75, 30.75, 0.75, 15.25, 31.5, 1.5), box(0.75, 30.75, 1.5, 1.5, 31.5, 14), box(14.5, 30.75, 1.5, 15.25, 31.5, 14),
            box(1, 4, 1.25, 2.75, 31.25, 6));
    //   抽水马桶：底座 · 立柱 · 碗 · 圈与盖（到 10.75）· 冲水管 · 高水箱连托架 · 拉杆、拉链与瓷把手
    private static final List<double[]> WATER_CLOSET = List.of(box(4, 0, 3.25, 12, 10.75, 14.5), box(7.375, 7.5, 13.5, 8.625, 27, 15.5),
            box(2.5, 24.5, 10, 13.5, 31.75, 15.75), box(12.5, 16.25, 11, 14.75, 29.75, 12.5));
    //   立式洗脸盆：底座连立柱与柱头 · 盆 · 龙头 · 挡水板与搁板 · 小镜子
    private static final List<double[]> BASIN = List.of(box(5, 0, 8, 11, 12.5, 15), box(2, 12.5, 4, 14, 15.5, 15.75),
            box(3.5, 15.5, 11.25, 12.5, 18, 14.5), box(2.5, 15.5, 12.25, 13.5, 21.5, 16), box(3.5, 21, 14.5, 12.5, 29, 15.75));
    //   毛巾架（一格）：两只长脚 · 立柱、横杆与两条毛巾
    private static final List<double[]> TOWEL_RAIL = List.of(box(1.5, 0, 3.5, 3.5, 0.75, 12.5), box(12.5, 0, 3.5, 14.5, 0.75, 12.5),
            box(1.75, 0.75, 6.75, 14.25, 15, 9.25));
    //   隔间板（贴在这一格背面那一边）：脚、底边、大理石板与压顶连成一块薄板（1.75 米）
    private static final List<double[]> STALL_PARTITION = List.of(box(0, 0, 13.25, 16, 28.5, 16));
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

    /**
     * 只按 x · z 切（C3 第二轮 · c3-deck）：碰撞箱竖着要越出这一格的那几块（瞭望台口沿往下伸到台面）—— 照 {@link #cut} 竖着也切就只剩格里那一截。
     * 越出这一格的碰撞盒子游戏照样认（方块的碰撞形状许出格，同栅栏的 1.5 格高）。
     */
    private static List<double[]> cutFlat(List<double[]> whole, LinerProp.Part part) {
        List<double[]> out = new ArrayList<>();
        for (double[] b : whole) {
            double[] c = cut(List.of(new double[]{b[0], part.y * 16.0, b[2], b[3], part.y * 16.0 + 16, b[5]}), part).stream().findFirst().orElse(null);
            if (c != null) {
                out.add(new double[]{c[0], b[1], c[2], c[3], b[4], c[5]});
            }
        }
        return out;
    }
}
