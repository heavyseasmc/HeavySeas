package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.heavyseasmc.mod.world.liner.LinerLooks.tex;

/**
 * 大邮轮的灯与家具（ADR-0063；样子 = ADR-0061 §4–§5 的细模定稿，模型与贴图由 {@code liner_props.py --write} 画好入库）。
 *
 * <p>跨几格的件（两格高的灯、2 × 2 的大桌、两格的沙发）像床、门那样：<b>一件物品摆出整件</b>，空间不够就摆不下；
 * <b>拆掉任何一格，整件一起没</b>（每一格都认得自己的搭档该在哪、是哪一块，搭档不在了自己就变成空气，一格传一格）。
 * 灯<b>右键开关</b>，亮着时发光（灯柱 15 · 落地灯 14 · 台灯 12；两格高的灯只有上面那一格发光）。
 * 顶灯与吊灯（ADR-0066）<b>只能挂在天花下</b>（摆的时候看正上方），整件往下长；吊灯只有灯身那几格发光（15）。
 * 骑缝的吸顶灯（ADR-0068）两格或 2 × 2 一件，灯身落在接缝上，像沙发、大桌那样往人的右手与远处长。
 * 轮廓与碰撞箱都照外形拼几个盒子（{@link LinerPropShapes}），没有一件是整块的。
 * 台灯放在大桌上时整件下沉 3 像素落在桌布上（{@code on_table}，看正下方那一格），并往桌子正中斜挪 3 像素（{@code table_corner}，ADR-0071）。
 * 客房与阅览室的家具（ADR 草稿 furniture）：黄铜床 1 × 2（床头往远处长）· 衣柜与盥洗台一格宽两格高 · 书柜 2 × 2 竖着两格高 ·
 * 写字台两格宽 · 写字椅一格；壁灯一格、背贴墙（朝向从点中的墙定，墙拆了不掉，没有碰撞箱）。
 * A 甲板新家具（ADR 草稿 furnish）：壁炉与炉上件 3 宽 × 2 高（炉火右键开关、只有正中下面那一格发光）· 棕榈两格高 / 大棵三格高
 * （碰撞只算盆）· 藤编扶手椅 · 小圆桌一格、长椅两格 · 吧台 4 长 × 2 高（带台后酒架）。
 * 肖像画框（C3 第二轮，ADR-0086）：小 1 × 1 · 中 2 × 2 · 大 2 × 3、背贴墙；画的是谁是属性 {@code sitter}，右键看那一位的人物牌，
 * 中、大两档的画框灯是属性 {@code lamp}（潜行右键开 / 关，灯那一排两格发光 12）。
 * C3 第一批娱乐（ADR-0086）：斜面海图桌 3 宽 × 2 深（斜面高过一格，那一截画在模型里、轮廓只到一格高）· 讲台 1 × 2 高（阅读灯右键开关）。
 *
 * <p>模型一律<b>正面朝北</b>作画（与游戏自带方块的约定一致，物品栏里才看得到正面），与 {@link LinerBlock} 的「朝南作画」不同：
 * 这里的 y 旋转是 北 0 · 东 90 · 南 180 · 西 270。
 *
 * <p>纯规则（格怎么随朝向转、镜像换哪一块、整件从哪一格往哪边长）在 {@link Rules}，单测不用起游戏。
 */
public final class LinerProp extends Block implements LinerLooks.Styled {

    /** 一件里的哪一块；(x, z) = 它在模型里的格（模型正面朝北：x 往东、z 往南），y = 第几层。 */
    public enum Part implements StringIdentifiable {
        LOWER(0, 0, 0), UPPER(0, 1, 0),
        NW(0, 0, 0), NE(1, 0, 0), SW(0, 0, 1), SE(1, 0, 1),
        WEST(0, 0, 0), EAST(1, 0, 0),
        /** 水晶大吊灯（ADR-0066）：贴天花的吊杆那一格，正中 3 × 3 那一层的上面。 */
        CROWN(1, 1, 1),
        RING_NW(0, 0, 0), RING_N(1, 0, 0), RING_NE(2, 0, 0),
        RING_W(0, 0, 1), RING_C(1, 0, 1), RING_E(2, 0, 1),
        RING_SW(0, 0, 2), RING_S(1, 0, 2), RING_SE(2, 0, 2),
        /** 床（1 × 2）：床尾那一格（离摆的人近）与床头那一格（往远处长）。 */
        FOOT(0, 0, 0), HEAD(0, 0, 1),
        /** 书柜（2 × 2，竖着两格高）：下面一层用 {@link #WEST} · {@link #EAST}，上面一层是这两块。 */
        UPPER_WEST(0, 1, 0), UPPER_EAST(1, 1, 0),
        /** 三格高的件（大棵棕榈 · 高通风筒）：下面两格用 {@link #LOWER} · {@link #UPPER}，最上面一格是它。 */
        TOP(0, 2, 0),
        /**
         * 3 宽 × 2 高、背贴墙的件（壁炉 · 炉上件）：下面一层西 · 中 · 东（模型里由西往东），上面一层同样三块。
         * 不借 {@link #WEST} · {@link #EAST}：那两块是两格宽的件的第 0 · 1 格，镜像时也只对调这两块；3 宽的东在第 2 格、镜像是 0 ↔ 2。
         */
        WIDE_WEST(0, 0, 0), WIDE_MID(1, 0, 0), WIDE_EAST(2, 0, 0),
        WIDE_UPPER_WEST(0, 1, 0), WIDE_UPPER_MID(1, 1, 0), WIDE_UPPER_EAST(2, 1, 0),
        /** 3 宽 × 3 高的件（魔镜，2026-10-04 放大）：下面两层同 3 宽 × 2 高那一族，最上面一层是这三块；镜像是西 ↔ 东。 */
        WIDE_TOP_WEST(0, 2, 0), WIDE_TOP_MID(1, 2, 0), WIDE_TOP_EAST(2, 2, 0),
        /** 吧台（4 长 × 2 高）：下面一层第 1–4 格（模型里由西往东），上面一层（台后酒架）同样四块；镜像是 1 ↔ 4 · 2 ↔ 3。 */
        BAY_1(0, 0, 0), BAY_2(1, 0, 0), BAY_3(2, 0, 0), BAY_4(3, 0, 0),
        UPPER_BAY_1(0, 1, 0), UPPER_BAY_2(1, 1, 0), UPPER_BAY_3(2, 1, 0), UPPER_BAY_4(3, 1, 0),
        /**
         * 吊艇架（艇甲板设备，ADR 草稿 deckgear）：铁座两格（锚点 {@link #BASE} · 往舷内一格），其余顺着斜臂往上、往舷外一格不落 ——
         * 每一格都与上一格面贴面挨着，「拆一格整件没」（一格传一格）才传得到头。模型正面朝北 = 舷外，所以往舷外是 −z。
         */
        BASE(0, 0, 0), BASE_IN(0, 0, 1), QUADRANT(0, 1, 0), ARM_A(0, 2, 0), ARM_B(0, 3, 0), ARM_C(0, 3, -1),
        ARM_D(0, 4, -1), ARM_E(0, 5, -1), ARM_F(0, 5, -2), ARM_HEAD(0, 6, -2),
        /**
         * 开局的钟（门形钟架，2 宽 × 6 高，ADR-0084 第三轮）：西一列（模型 x 0）与东一列（x 1），由下往上 0–5；钟挂在横梁正中（两列的接缝上）。
         * 镜像是西 ↔ 东。
         */
        BELL_W0(0, 0, 0), BELL_W1(0, 1, 0), BELL_W2(0, 2, 0), BELL_W3(0, 3, 0), BELL_W4(0, 4, 0), BELL_W5(0, 5, 0),
        BELL_E0(1, 0, 0), BELL_E1(1, 1, 0), BELL_E2(1, 2, 0), BELL_E3(1, 3, 0), BELL_E4(1, 4, 0), BELL_E5(1, 5, 0),
        // C3 第二轮 · c3-gallery
        /**
         * 大肖像画框（2 宽 × 3 高、背贴墙，ADR-0086）最上面一排：下面两层借书柜那四块（{@link #WEST} · {@link #EAST} ·
         * {@link #UPPER_WEST} · {@link #UPPER_EAST}），中框只用那四块。画框灯在最上面一排（中框是上面一排），两格都发光。镜像是西 ↔ 东。
         */
        TOP_WEST(0, 2, 0), TOP_EAST(1, 2, 0),
        // C3 第二轮 · c3-table
        /**
         * 海图桌（3 宽 × 2 深、一层，ADR-0086 §2 第 3 条 C′）：前排（离摆的人近、斜面低的那一头）西 · 中 · 东，后排同样三块，
         * 模型里由西往东。斜面后沿高过一格（到 26 像素），那一截画在这一层的模型里、不另占格。镜像是西 ↔ 东。
         */
        FRONT_WEST(0, 0, 0), FRONT_MID(1, 0, 0), FRONT_EAST(2, 0, 0),
        BACK_WEST(0, 0, 1), BACK_MID(1, 0, 1), BACK_EAST(2, 0, 1),
        // C3 第二轮 · c3-deck（ADR-0086 §2 第 11–15 条）：只加空心桅杆的三个零件 —— 竖井一格（自己就是一件，见 Rules.piece）· 门两格（下半 · 上半）。
        //   瞭望台台面一圈 · 口沿一圈借 RING_*（大吊灯那一层的 3 × 3，去掉正中 RING_C = 桅杆那一格），甲板躺椅借床的 FOOT · HEAD
        SHAFT(0, 0, 0), DOOR_LOWER(0, 0, 0), DOOR_UPPER(0, 1, 0);

        final int x;
        final int y;
        final int z;

        Part(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 摆法。 */
    public enum Kind {
        /** 两格高的灯（灯柱、落地灯）：下面一格 + 上面一格，上面那一格发光。 */
        TALL_LAMP,
        /** 一格的台灯：放在大桌上时下沉。 */
        TABLE_LAMP,
        /** 2 × 2 的大桌。 */
        GRAND_TABLE,
        /** 两格的沙发。 */
        SOFA,
        /** 一格的椅子。 */
        CHAIR,
        /** 一格的吸顶灯（ADR-0066）：只能挂在天花下。 */
        CEILING_LAMP,
        /** 两格高的吊灯：上面一格贴天花（摆的时候点中的那一格），下面一格是灯身、发光。 */
        CHANDELIER,
        /** 水晶大吊灯：贴天花的吊杆一格 + 下面 3 × 3 一层，共 10 格；那一层正中的十字五格发光。 */
        GRAND_CHANDELIER,
        /** 骑缝的吸顶灯（ADR-0068）：两格一件，灯身正落在两格的接缝上 —— 双数宽的走廊、客房才挂得到正中。两格都发光。 */
        CEILING_PAIR,
        /** 骑缝的吸顶灯，2 × 2 一件：灯身落在四格的交点上（两个方向都是双数宽的小屋）。四格都发光。 */
        CEILING_QUAD,
        /** 黄铜床（1 × 2）：床尾在点中的那一格，床头往远处长（同游戏自带的床）。 */
        BED,
        /** 衣柜（一格宽、两格高）：背贴墙。 */
        WARDROBE,
        /** 盥洗台（一格宽、两格高，连镜子）：背贴墙。 */
        WASHSTAND,
        /**
         * 魔镜（ADR-0065 · ADR-0083；2026-10-04 放大到两格宽、三格高，嵌进墙里的那一版）：占 3 宽 × 3 高，框在正中两格、
         * 两侧那两列各带半边框（主景正中是一格，两格宽的东西只能这样居中）；背贴墙、镜面朝摆它的人。
         * 点中的那一格是下面一层正中（{@link Part#WIDE_MID}），往人的左右手各长一格、往上长两层。右键穿过去（{@code MagicMirror}）。
         */
        MIRROR,
        /** 书柜（2 × 2，竖着两格高）：往人的右手与上面长。 */
        BOOKCASE,
        /** 写字台（两格宽、两个座位）：同沙发，往人的右手长。 */
        WRITING_TABLE,
        /** 写字椅（一格）。 */
        WRITING_CHAIR,
        /**
         * 壁灯（一格，ADR-0069 §4 倾向 A）：贴在墙前那一格、背贴墙，朝向就是离墙的方向；摆的时候点中的那一面墙要是实的，
         * 摆好之后墙拆了灯也不掉（与檐口、腰线这些挂墙件相同）。右键开关；没有碰撞箱（同游戏自带的墙上火把：走廊两格宽，不碰头）。
         */
        SCONCE,
        /**
         * 壁炉（3 宽 × 2 高、背贴墙，ADR 草稿 furnish）：往人的右手与上面长；炉火右键开关（默认亮），只有正中下面那一格发光
         * （炭与火苗都在那一格的模型里）。灭着时模型里没有火苗、只剩冷炭。
         */
        FIREPLACE,
        /** 炉上件（描金框镜 · 桃花心木框油画；3 宽 × 2 高、背贴墙）：挂在壁炉上面两排，底框往下伸进壁炉那两排 4.5 像素、坐在壁炉台上。 */
        OVERMANTEL,
        /** 盆栽棕榈（两格高）：碰撞只算盆（叶子伸出这一格、穿得过去）。 */
        PALM,
        /** 大棵棕榈（三格高）：同上。 */
        PALM_TALL,
        /** 藤编扶手椅（一格）。 */
        WICKER_CHAIR,
        /** 藤编小圆桌（一格）。 */
        WICKER_TABLE,
        /** 藤编长椅（两格）：同沙发，往人的右手长。 */
        WICKER_SETTEE,
        /** 吧台（4 长 × 2 高，带台后酒架、背贴墙）：往人的右手与上面长。 */
        BAR_COUNTER,
        /**
         * 吊艇架（四分圆摇臂式，ADR-0080 §7 甲）：10 格，正面（模型北）朝舷外，臂倒 22.5° 伸出去，臂头在铁座外 2.5 格、上 6.9 格。
         * 艇在哪一边由 {@link #BOAT_SIDE} 定（右手是画的那一份，左手是镜像那一份）。
         */
        DAVIT,
        /** 喇叭口通风筒（高，三格）：喇叭口朝正面。 */
        VENTILATOR,
        /** 喇叭口通风筒（矮，两格）。 */
        VENTILATOR_SHORT,
        /**
         * 开局的钟（门形钟架：两根白漆柱、柚木横梁、黄铜船钟挂在正中；2 宽 × 6 高，ADR-0084 第三轮）：正面（模型北）朝演习艇，
         * 往人的右手与上面长（同书柜）。右键敲钟：演习艇旁那一口交给 {@link DrillSkiff#ringBell}（坐在艇里 = 开阵容面板）。
         */
        DRILL_BELL,
        // C3 第二轮 · c3-gallery
        /**
         * 肖像画框（ADR-0086 §2 第 8 条：桃花心木配描金内压条；画心 = 木刻头像，ADR-0090 §8），背贴墙、画面朝摆它的人。
         * 画的是谁是方块属性 {@link #SITTER}；右键打开那个角色的人物牌（客户端一面，没有对局也能开，{@link PortraitView}）。
         * 小 1 × 1：一格，没有画框灯。
         */
        PORTRAIT_SMALL,
        /** 中 2 × 2：借书柜那四块，往人的右手与上面长；画框灯（{@link #LAMP}）在上面一排、两格都发光。 */
        PORTRAIT_MEDIUM,
        /** 大 2 × 3：书柜那四块再加最上面一排（{@link Part#TOP_WEST} · {@link Part#TOP_EAST}）；画框灯在最上面一排、两格都发光。 */
        PORTRAIT_LARGE,
        // C3 第二轮 · c3-table
        /**
         * 海图桌（ADR-0086 §2 第 3 · 4 条：C′ 斜面海图板 + 抽屉柜身，3 宽 × 2 深）：正面（模型北）是斜面低的那一头与抽屉，
         * 斜面往后（模型 +z）抬 22.5°。海图一格一张（32 像素/格）。点中的是前排人左手那一块，往人的右手与远处长（同大桌）。
         * 浮字与小铜船（物品展示实体）由海图桌那一侧的代码摆，不在方块里。
         */
        CHART_TABLE,
        /**
         * 讲台（ADR-0086 §2 第 6 条 C：方座 + 黄铜绿罩阅读灯，1 × 2 高）：书常驻、摊开，斜着朝正面（模型北）；灯在上面那一格，
         * 右键开关（同落地灯），亮着时只有上面那一格发光。
         */
        LECTERN,
        // ---- C3 第二轮 · c3-deck（ADR-0086 §2 第 11–15 条：露天甲板的躺椅、前桅瞭望台；样子 = liner_props_opendeck.py）
        /**
         * 甲板躺椅（1 × 2，同床：脚那一格在点中的那一格、头往远处长；靠背后仰 22.5°）：两种铺法 —— 坐垫（B）· 坐垫 + 格子呢毯（C，{@link #RUG}）。
         * 右键坐上去（{@link DeckChairSeat}：自己的座位实体，与对局座位、演习艇报名不相干）。
         */
        DECK_CHAIR,
        /** 前桅瞭望台 A 的台面一圈（桶形钢板台的底、外壁、托架；3 × 3 围着桅杆，正中那一格是桅杆）：点中的是人与桅杆之间那一格，整圈往远处长。 */
        LOOKOUT_FLOOR,
        /** 瞭望台 A 的口沿一圈（人站的那一层上面一层；卷边口沿刷深色）。碰撞箱往下伸到台面、高 1.5 格：站在台上翻不出去。 */
        LOOKOUT_RIM,
        /**
         * 空心桅杆（ADR-0086 §2 第 14 条 (b)）：外壁与实心桅杆同一张贴图（{@link #ROW} 同烟囱板），里面一根根横档、能爬（方块标签 climbable）。
         * 竖井一格一件；门两格一件（门朝摆它的人，右键开关 {@link #OPEN}）。点中空心桅杆的顶面 = 往上接一格竖井，别处 = 摆一扇门。
         */
        HOLLOW_MAST,
        /** 望远镜柜 A（壁柜，背贴桅杆）。 */
        TELESCOPE_CABINET,
        /** 瞭望台的小警钟（背贴桅杆）：右键只响一声，不开局（{@link Rules#use}：只有 {@link #DRILL_BELL} 走 {@link DrillSkiff#ringBell}）。 */
        ALARM_BELL
    }

    // C3 第二轮 · c3-gallery
    /** 肖像画框上画的是谁：八个角色（id 同 {@code data/roster}，顺序照座位号）。值名就是角色 id —— 画面贴图与人物牌都按它找。 */
    public enum Sitter implements StringIdentifiable {
        JEWELER, COLLECTOR, CAPTAIN, MATE, HOSTESS, SAILOR, DOCTOR, KID;

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 肖像画框的画框灯（ADR-0086 §2 第 10 条「做成框的一部分，属性开关」）：没有灯 · 有灯灭着 · 有灯亮着。
     * 潜行右键在 灭 ↔ 亮 之间切（不潜行的右键是看牌）；有没有灯由摆的人定（结构里写好，或调试棒），潜行右键不加不拆。
     */
    public enum FrameLamp implements StringIdentifiable {
        NONE, OFF, ON;

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 右键走哪条路（C3 第二轮 · c3-deck）：只有开航钟走演习艇那一条（{@link DrillSkiff#ringBell}），单测按全部种类核对。 */
    public enum Use {
        /** 开航钟：交给 {@link DrillSkiff#ringBell}（演习艇旁那一口、坐在艇里的人敲 = 开阵容面板）。 */
        VOYAGE_BELL,
        /** 瞭望台的小警钟：只响一声。 */
        ALARM_BELL,
        /** 躺椅：坐上去。 */
        SIT,
        /** 空心桅杆：门那两格开关（竖井那一格不理）。 */
        DOOR,
        /** 别的（魔镜穿过去 · 灯开关 · 没有反应）：照原来那几条判。 */
        OTHER
    }

    /** 吊艇架的艇在哪一边：站在吊艇架后面、面朝舷外（模型的正面）看，艇在右手（模型 +x，画的那一份）还是左手（镜像那一份）。 */
    public enum BoatSide implements StringIdentifiable {
        LEFT, RIGHT;

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final net.minecraft.state.property.DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final BooleanProperty LIT = Properties.LIT;
    /** 台灯：正下方是大桌（整件下沉 3 像素落在桌布上）。 */
    public static final BooleanProperty ON_TABLE = BooleanProperty.of("on_table");
    /**
     * 台灯在大桌上往哪边挪（ADR-0071）：大桌 2 × 2、桌布是圆的，台灯总落在桌子的某一个角格上，摆在格子正中底座就有一角伸出布边
     * （用户 2026-10-03「桌子上台灯的脚有一部分悬空了」）—— 于是往桌子正中斜挪 3 像素。值是<b>台灯自己模型里</b>的方向
     * （NW = 往模型的 −x −z），由正下方那块桌子是哪一块、朝哪算出来（{@link Rules#tableCorner}）；不在桌上时不起作用。
     */
    public static final EnumProperty<Part> TABLE_CORNER = EnumProperty.of("table_corner", Part.class, Part.NW, Part.NE, Part.SW, Part.SE);
    /** 台灯在桌上斜挪多少像素（每个方向）：3 —— 底座 10 × 6、整件仍在这一格里，四角都落在 30 像素的圆桌布上（liner_props.py 有判据）。 */
    static final int TABLE_SHIFT = 3;
    public static final EnumProperty<Part> TALL = EnumProperty.of("part", Part.class, Part.LOWER, Part.UPPER);
    public static final EnumProperty<Part> QUAD = EnumProperty.of("part", Part.class, Part.NW, Part.NE, Part.SW, Part.SE);
    public static final EnumProperty<Part> PAIR = EnumProperty.of("part", Part.class, Part.WEST, Part.EAST);
    public static final EnumProperty<Part> GRAND = EnumProperty.of("part", Part.class, Part.CROWN,
            Part.RING_NW, Part.RING_N, Part.RING_NE, Part.RING_W, Part.RING_C, Part.RING_E, Part.RING_SW, Part.RING_S, Part.RING_SE);
    public static final EnumProperty<Part> BED_PART = EnumProperty.of("part", Part.class, Part.FOOT, Part.HEAD);
    public static final EnumProperty<Part> SHELF = EnumProperty.of("part", Part.class, Part.WEST, Part.EAST, Part.UPPER_WEST, Part.UPPER_EAST);
    public static final EnumProperty<Part> TALL3 = EnumProperty.of("part", Part.class, Part.LOWER, Part.UPPER, Part.TOP);
    public static final EnumProperty<Part> WIDE = EnumProperty.of("part", Part.class, Part.WIDE_WEST, Part.WIDE_MID, Part.WIDE_EAST,
            Part.WIDE_UPPER_WEST, Part.WIDE_UPPER_MID, Part.WIDE_UPPER_EAST);
    public static final EnumProperty<Part> WIDE3 = EnumProperty.of("part", Part.class, Part.WIDE_WEST, Part.WIDE_MID, Part.WIDE_EAST,
            Part.WIDE_UPPER_WEST, Part.WIDE_UPPER_MID, Part.WIDE_UPPER_EAST, Part.WIDE_TOP_WEST, Part.WIDE_TOP_MID, Part.WIDE_TOP_EAST);
    public static final EnumProperty<Part> BAR = EnumProperty.of("part", Part.class, Part.BAY_1, Part.BAY_2, Part.BAY_3, Part.BAY_4,
            Part.UPPER_BAY_1, Part.UPPER_BAY_2, Part.UPPER_BAY_3, Part.UPPER_BAY_4);
    public static final EnumProperty<Part> DAVIT_PART = EnumProperty.of("part", Part.class, Part.BASE, Part.BASE_IN, Part.QUADRANT,
            Part.ARM_A, Part.ARM_B, Part.ARM_C, Part.ARM_D, Part.ARM_E, Part.ARM_F, Part.ARM_HEAD);
    /** 吊艇架：艇在哪一边（见 {@link BoatSide}）。结构照镜子时跟着换（{@link #mirror}）。 */
    public static final EnumProperty<BoatSide> BOAT_SIDE = EnumProperty.of("boat_side", BoatSide.class);
    public static final EnumProperty<Part> BELL_PART = EnumProperty.of("part", Part.class, Part.BELL_W0, Part.BELL_W1, Part.BELL_W2,
            Part.BELL_W3, Part.BELL_W4, Part.BELL_W5, Part.BELL_E0, Part.BELL_E1, Part.BELL_E2, Part.BELL_E3, Part.BELL_E4, Part.BELL_E5);
    // C3 第二轮 · c3-gallery：肖像画框 —— 大框的六块（中框用 SHELF 那四块）· 画的是谁 · 画框灯
    public static final EnumProperty<Part> FRAME3 = EnumProperty.of("part", Part.class, Part.WEST, Part.EAST,
            Part.UPPER_WEST, Part.UPPER_EAST, Part.TOP_WEST, Part.TOP_EAST);
    public static final EnumProperty<Sitter> SITTER = EnumProperty.of("sitter", Sitter.class);
    public static final EnumProperty<FrameLamp> LAMP = EnumProperty.of("lamp", FrameLamp.class);
    // C3 第二轮 · c3-table：海图桌的六块
    public static final EnumProperty<Part> CHART = EnumProperty.of("part", Part.class, Part.FRONT_WEST, Part.FRONT_MID, Part.FRONT_EAST,
            Part.BACK_WEST, Part.BACK_MID, Part.BACK_EAST);
    // C3 第二轮 · c3-deck
    /** 甲板躺椅搭不搭格子呢毯（C 版）：同一种方块两种铺法，一排里隔着放。 */
    public static final BooleanProperty RUG = BooleanProperty.of("rug");
    /** 空心桅杆的门开着没有（门那两格一起开关；竖井那一格不看它）。 */
    public static final BooleanProperty OPEN = Properties.OPEN;
    /** 空心桅杆这一格是一列板的下格（0）还是上格（1）：外壁用实心桅杆（烟囱板）的那一张，按 y 算（{@link LinerHull.Rules#row}）。 */
    public static final IntProperty ROW = IntProperty.of("row", 0, 1);
    /** 瞭望台台面 / 口沿那一圈：3 × 3 去掉正中（桅杆那一格）。 */
    public static final EnumProperty<Part> LOOKOUT = EnumProperty.of("part", Part.class, Part.RING_NW, Part.RING_N, Part.RING_NE,
            Part.RING_W, Part.RING_E, Part.RING_SW, Part.RING_S, Part.RING_SE);
    public static final EnumProperty<Part> MAST = EnumProperty.of("part", Part.class, Part.SHAFT, Part.DOOR_LOWER, Part.DOOR_UPPER);

    /**
     * 一件道具的说明。
     *
     * @param model   模板名的词干（{@code template/prop/<词干>_…}）
     * @param texture 主贴图名（{@code prop/<名>}）；布不同的两件（米色 / 绿丝绒）只差这一张
     * @param glow    发光那一张贴图的词干（灯：{@code prop/<词干>_off|_lit}），别的件为 {@code null}
     * @param light   亮着时的光照等级
     */
    public record Spec(Kind kind, String model, String texture, String glow, int light) {
    }

    private static final ThreadLocal<Spec> PENDING = new ThreadLocal<>();

    private final Spec spec;
    private final EnumProperty<Part> part;
    private final Map<BlockState, VoxelShape> shapes = new ConcurrentHashMap<>();
    private final Map<BlockState, VoxelShape> collisions = new ConcurrentHashMap<>();
    /** 这一件伸进正上方那一格的那一截（{@link LinerPropShapes#overhang}，按这一件的方块状态转好朝向；只有警钟有）。 */
    private final Map<BlockState, VoxelShape> overhangs = new ConcurrentHashMap<>();

    static LinerProp create(AbstractBlock.Settings settings, Spec spec) {
        PENDING.set(spec);
        try {
            return new LinerProp(settings, spec);
        } finally {
            PENDING.remove();
        }
    }

    private LinerProp(AbstractBlock.Settings settings, Spec spec) {
        super(settings);
        this.spec = spec;
        this.part = partProperty(spec.kind());
        BlockState s = getStateManager().getDefaultState().with(FACING, Direction.NORTH);
        if (part != null) {
            s = s.with(part, Rules.anchor(spec.kind()));
        }
        if (s.contains(LIT)) {
            s = s.with(LIT, true);
        }
        if (s.contains(ON_TABLE)) {
            s = s.with(ON_TABLE, false).with(TABLE_CORNER, Part.NW);
        }
        if (s.contains(BOAT_SIDE)) {
            s = s.with(BOAT_SIDE, BoatSide.RIGHT);
        }
        if (s.contains(LAMP)) {
            s = s.with(LAMP, FrameLamp.ON);          // C3 第二轮 · c3-gallery：肖像画框拿出来就带灯、亮着（ADR-0086 §2 第 10 条「要画框灯」）
        }
        // C3 第二轮 · c3-deck：躺椅默认不搭毯（B）· 空心桅杆的门默认关着、row 0
        if (s.contains(RUG)) {
            s = s.with(RUG, false);
        }
        if (s.contains(OPEN)) {
            s = s.with(OPEN, false).with(ROW, 0);
        }
        setDefaultState(s);
    }

    private static EnumProperty<Part> partProperty(Kind kind) {
        return switch (kind) {
            case TALL_LAMP, CHANDELIER, WARDROBE, WASHSTAND, PALM, VENTILATOR_SHORT, LECTERN -> TALL;
            case CHART_TABLE -> CHART;
            case MIRROR -> WIDE3;
            case GRAND_TABLE, CEILING_QUAD -> QUAD;
            case SOFA, CEILING_PAIR, WRITING_TABLE, WICKER_SETTEE -> PAIR;
            case GRAND_CHANDELIER -> GRAND;
            case BED -> BED_PART;
            case BOOKCASE -> SHELF;
            case PALM_TALL, VENTILATOR -> TALL3;
            case FIREPLACE, OVERMANTEL -> WIDE;
            case BAR_COUNTER -> BAR;
            case DAVIT -> DAVIT_PART;
            case DRILL_BELL -> BELL_PART;
            // C3 第二轮 · c3-gallery：中框借书柜那四块，大框六块
            case PORTRAIT_MEDIUM -> SHELF;
            case PORTRAIT_LARGE -> FRAME3;
            // C3 第二轮 · c3-deck
            case DECK_CHAIR -> BED_PART;
            case LOOKOUT_FLOOR, LOOKOUT_RIM -> LOOKOUT;
            case HOLLOW_MAST -> MAST;
            default -> null;
        };
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        Spec s = PENDING.get();
        builder.add(FACING);
        EnumProperty<Part> p = partProperty(s.kind());
        if (p != null) {
            builder.add(p);
        }
        if (Rules.isLamp(s.kind())) {
            builder.add(LIT);
        }
        if (s.kind() == Kind.TABLE_LAMP) {
            builder.add(ON_TABLE, TABLE_CORNER);
        }
        if (s.kind() == Kind.DAVIT) {
            builder.add(BOAT_SIDE);
        }
        // C3 第二轮 · c3-gallery：肖像画框画的是谁；中、大两档另有画框灯
        if (Rules.isPortrait(s.kind())) {
            builder.add(SITTER);
        }
        if (Rules.hasFrameLamp(s.kind())) {
            builder.add(LAMP);
        }
        // C3 第二轮 · c3-deck
        if (s.kind() == Kind.DECK_CHAIR) {
            builder.add(RUG);
        }
        if (s.kind() == Kind.HOLLOW_MAST) {
            builder.add(OPEN, ROW);
        }
    }

    public Spec spec() {
        return spec;
    }

    /**
     * 亮着时的光照：跨几格的灯只有灯身那几格发光（{@link Rules#glows}）。给方块设置的 {@code luminance} 用 ——
     * 方块状态在方块构造时就把光照算好存起来了，那时这个方块的 {@link #spec} 还没赋值，所以摆法由登记处直接传进来。
     */
    static int lightOf(Kind kind, BlockState s, int level) {
        if (s.contains(LAMP)) {
            // C3 第二轮 · c3-gallery：肖像画框的灯亮着时，灯那一排两格都发光（ADR-0086 §2 第 10 条：只一格发光时左右两半有竖缝）
            return s.get(LAMP) == FrameLamp.ON && Rules.glows(kind, s.get(partProperty(kind))) ? level : 0;
        }
        if (!s.contains(LIT) || !s.get(LIT)) {
            return 0;
        }
        EnumProperty<Part> p = partProperty(kind);
        return p == null || Rules.glows(kind, s.get(p)) ? level : 0;
    }

    // ---------------------------------------------------------------- 样子

    @Override
    public LinerLooks.Look look(BlockState s) {
        String st = s.contains(LIT) && s.get(LIT) ? "lit" : "off";
        LinerLooks.Look base = switch (spec.kind()) {
            case TALL_LAMP -> s.get(TALL) == Part.LOWER
                    ? LinerLooks.look("prop/" + spec.model() + "_lower", tex("b", "prop/" + spec.model() + "_lower"))
                    : LinerLooks.look("prop/" + spec.model() + "_upper_" + st, tex("b", "prop/" + spec.model() + "_upper",
                    "g", "prop/" + spec.glow() + "_" + st));
            case TABLE_LAMP -> LinerLooks.look("prop/" + spec.model()
                            + (s.get(ON_TABLE) ? "_sunk_" + s.get(TABLE_CORNER).asString() + "_" : "_") + st,
                    tex("b", "prop/" + spec.texture(), "g", "prop/" + spec.glow() + "_" + st));
            case GRAND_TABLE -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(QUAD).asString(), tex("b", "prop/" + spec.texture()));
            case SOFA -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(PAIR).asString(), tex("b", "prop/" + spec.texture()));
            case CHAIR -> LinerLooks.look("prop/" + spec.model(), tex("b", "prop/" + spec.texture()));
            case CEILING_LAMP -> LinerLooks.look("prop/" + spec.model() + "_" + st, tex("b", "prop/" + spec.texture(),
                    "g", "prop/" + spec.glow() + "_" + st));
            case CHANDELIER -> s.get(TALL) == Part.UPPER
                    ? LinerLooks.look("prop/" + spec.model() + "_upper", tex("b", "prop/" + spec.model() + "_upper"))
                    : LinerLooks.look("prop/" + spec.model() + "_lower_" + st, tex("b", "prop/" + spec.texture(),
                    "g", "prop/" + spec.glow() + "_" + st));
            case GRAND_CHANDELIER -> grandLook(s.get(GRAND), st);
            case CEILING_PAIR -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(PAIR).asString() + "_" + st,
                    tex("b", "prop/" + spec.texture(), "g", "prop/" + spec.glow() + "_" + st));
            case CEILING_QUAD -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(QUAD).asString() + "_" + st,
                    tex("b", "prop/" + spec.texture(), "g", "prop/" + spec.glow() + "_" + st));
            // 客房与阅览室的家具（ADR 草稿 furniture）：每一块一个模板，贴图整件一张（书柜的书架里面另一张 #k）
            case WARDROBE, WASHSTAND -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(TALL).asString(), tex("b", "prop/" + spec.texture()));
            case MIRROR -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(WIDE3).asString(), tex("b", "prop/" + spec.texture()));
            case BED -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(BED_PART).asString(), tex("b", "prop/" + spec.texture()));
            case BOOKCASE -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(SHELF).asString(),
                    tex("b", "prop/" + spec.texture(), "k", "prop/" + spec.texture() + "_shelves"));
            case WRITING_TABLE -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(PAIR).asString(), tex("b", "prop/" + spec.texture()));
            case WRITING_CHAIR -> LinerLooks.look("prop/" + spec.model(), tex("b", "prop/" + spec.texture()));
            case SCONCE -> LinerLooks.look("prop/" + spec.model() + "_" + st, tex("b", "prop/" + spec.texture(),
                    "g", "prop/" + spec.glow() + "_" + st));
            // A 甲板新家具（ADR 草稿 furnish）：大件一张主贴图（#b）+ 细节一张（#k）；壁炉的炭与火在发光那一张（#g，亮 / 灭两张），
            //   灭着那一份的模板里没有火苗（模板名带 _off / _lit）；吧台的酒瓶另一张（#w）
            case FIREPLACE -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(WIDE).asString() + "_" + st, tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_detail", "g", "prop/" + spec.glow() + "_" + st));
            case OVERMANTEL -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(WIDE).asString(), tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_detail"));
            case PALM -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(TALL).asString(), tex("b", "prop/" + spec.texture()));
            case PALM_TALL -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(TALL3).asString(), tex("b", "prop/" + spec.texture()));
            case WICKER_CHAIR, WICKER_TABLE -> LinerLooks.look("prop/" + spec.model(), tex("b", "prop/" + spec.texture()));
            case WICKER_SETTEE -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(PAIR).asString(), tex("b", "prop/" + spec.texture()));
            case BAR_COUNTER -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(BAR).asString(), tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_detail", "w", "prop/" + spec.texture() + "_bottles"));
            // 艇甲板设备（ADR 草稿 deckgear）：白漆一张 #b、铁与吊索 / 喇叭与口一张 #k；吊艇架艇在左手时用镜像那一份模板（_m）
            case DAVIT -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(DAVIT_PART).asString()
                            + (s.get(BOAT_SIDE) == BoatSide.LEFT ? "_m" : ""),
                    tex("b", "prop/" + spec.texture(), "k", "prop/" + spec.texture() + "_iron"));
            case VENTILATOR -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(TALL3).asString(),
                    tex("b", "prop/" + spec.texture(), "k", "prop/" + spec.texture() + "_mouth"));
            case VENTILATOR_SHORT -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(TALL).asString(),
                    tex("b", "prop/" + spec.texture(), "k", "prop/" + spec.texture() + "_mouth"));
            // 开局的钟：白漆柱与柚木横梁一张 #b、黄铜钟与铁件、钟绳一张 #k
            case DRILL_BELL -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(BELL_PART).asString(),
                    tex("b", "prop/" + spec.texture(), "k", "prop/" + spec.texture() + "_brass"));
            // C3 第二轮 · c3-gallery：肖像画框
            case PORTRAIT_SMALL, PORTRAIT_MEDIUM, PORTRAIT_LARGE -> portraitLook(s);
            // C3 第二轮 · c3-table：海图桌一块一块模板（#b 桃花心木 · #k 黄铜 · #c 这一格的海图）；讲台下面一格一块、上面亮 / 灭两块
            case CHART_TABLE -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(CHART).asString(), tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_brass", "c", "prop/" + spec.texture() + "_chart_" + s.get(CHART).asString()));
            case LECTERN -> s.get(TALL) == Part.LOWER
                    ? LinerLooks.look("prop/" + spec.model() + "_lower", tex("b", "prop/" + spec.texture(), "k", "prop/" + spec.texture() + "_detail"))
                    : LinerLooks.look("prop/" + spec.model() + "_upper_" + st, tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_detail", "g", "prop/" + spec.glow() + "_" + st));
            // C3 第二轮 · c3-deck：柚木 / 钢板一张 #b、坐垫与毯 / 柚木台面与铁件一张 #k；空心桅杆外壁 #m = 实心桅杆那一格的贴图（按 row）
            case DECK_CHAIR, LOOKOUT_FLOOR, LOOKOUT_RIM, HOLLOW_MAST, TELESCOPE_CABINET, ALARM_BELL -> openDeckLook(s);
        };
        return base.turned(yawOf(s.get(FACING)));
    }

    // C3 第二轮 · c3-gallery
    /**
     * 肖像画框的一格：框一张 #b、画面一张 #p（{@code prop/<贴图>_<角色>}，按 {@link #SITTER} 填）；灯那一排有灯时多一张灯管 #g
     * （灭 / 亮两张），模板也换成带灯的那一块（{@code _off} · {@code _lit}）。没有灯、或者不在灯那一排：模板不带后缀。
     */
    private LinerLooks.Look portraitLook(BlockState s) {
        String m = "prop/" + spec.model();
        String b = "prop/" + spec.texture();
        String p = b + "_" + s.get(SITTER).asString();
        if (part == null) {
            return LinerLooks.look(m, tex("b", b, "p", p));
        }
        Part here = s.get(part);
        FrameLamp lamp = s.get(LAMP);
        if (lamp == FrameLamp.NONE || !Rules.glows(spec.kind(), here)) {
            return LinerLooks.look(m + "_" + here.asString(), tex("b", b, "p", p));
        }
        String st = lamp == FrameLamp.ON ? "lit" : "off";
        return LinerLooks.look(m + "_" + here.asString() + "_" + st, tex("b", b, "p", p, "g", "prop/" + spec.glow() + "_" + st));
    }

    /** C3 第二轮 · c3-deck 那几件的样子（模板与贴图由 liner_props_opendeck.py 经 liner_props.py --write 写）。 */
    private LinerLooks.Look openDeckLook(BlockState s) {
        String m = "prop/" + spec.model();
        return switch (spec.kind()) {
            case DECK_CHAIR -> {
                String stem = m + (s.get(RUG) ? "_rug" : "");
                yield LinerLooks.look(stem + "_" + s.get(BED_PART).asString(), tex("b", stem, "k", stem + "_cushion"));
            }
            case LOOKOUT_FLOOR, LOOKOUT_RIM -> LinerLooks.look(m + "_" + s.get(LOOKOUT).asString(),
                    tex("b", "prop/" + spec.texture(), "k", "prop/" + spec.texture() + "_detail"));
            case HOLLOW_MAST -> LinerLooks.look(m + "_" + s.get(MAST).asString()
                            + (s.get(MAST) == Part.SHAFT ? "" : s.get(OPEN) ? "_open" : "_closed"),
                    tex("b", "prop/" + spec.texture(), "m", LinerHull.Rules.funnelTexture("buff", s.get(ROW))));
            default -> LinerLooks.look(m, tex("b", "prop/" + spec.texture(), "k", "prop/" + spec.texture() + "_detail"));
        };
    }

    /** 水晶大吊灯的一格：吊杆 · 那一层正中的十字五格（斜件都在正中那一格的模型里）· 四个角（空模型，只占位）。 */
    private LinerLooks.Look grandLook(Part p, String st) {
        String m = "prop/" + spec.model();
        return switch (p) {
            case CROWN -> LinerLooks.look(m + "_top_" + st, tex("b", m + "_top", "g", m + "_top_glow_" + st));
            case RING_NW, RING_NE, RING_SW, RING_SE -> LinerLooks.look(m + "_corner", tex("b", "prop/" + spec.texture()));
            default -> LinerLooks.look(m + "_" + p.asString().substring("ring_".length()) + "_" + st,
                    tex("b", "prop/" + spec.texture(), "g", "prop/" + spec.glow() + "_" + st));
        };
    }

    @Override
    public LinerLooks.Look itemLook() {
        return switch (spec.kind()) {
            case TALL_LAMP -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.model() + "_lower",
                    "u", "prop/" + spec.model() + "_upper", "g", "prop/" + spec.glow() + "_lit"));
            case CHANDELIER -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "u", "prop/" + spec.model() + "_upper", "g", "prop/" + spec.glow() + "_lit"));
            case GRAND_CHANDELIER -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "g", "prop/" + spec.glow() + "_lit", "u", "prop/" + spec.model() + "_top",
                    "h", "prop/" + spec.model() + "_top_glow_lit"));
            case GRAND_TABLE, SOFA -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture()));
            // 骑缝灯在物品栏里就是那一盏灯（整件只是挪了半格）
            case CEILING_PAIR, CEILING_QUAD -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "g", "prop/" + spec.glow() + "_lit"));
            case BED, WARDROBE, WASHSTAND, MIRROR, WRITING_TABLE -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture()));
            case BOOKCASE -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_shelves"));
            // A 甲板新家具：整件缩小（吧台 4 格长，物品是整件再缩一半的那一份）
            case FIREPLACE -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_detail", "g", "prop/" + spec.glow() + "_lit"));
            case OVERMANTEL -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_detail"));
            case PALM, PALM_TALL, WICKER_SETTEE -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture()));
            case BAR_COUNTER -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_detail", "w", "prop/" + spec.texture() + "_bottles"));
            case DAVIT -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_iron"));
            case VENTILATOR, VENTILATOR_SHORT -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_mouth"));
            case DRILL_BELL -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_brass"));
            // C3 第二轮 · c3-gallery：中、大两档的物品是整件缩小（灯亮着，挂默认那一位）；小框用摆出来那一版的方块模型
            case PORTRAIT_MEDIUM, PORTRAIT_LARGE -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "p", "prop/" + spec.texture() + "_" + getDefaultState().get(SITTER).asString(), "g", "prop/" + spec.glow() + "_lit"));
            // C3 第二轮 · c3-table：整件缩小；海图桌的物品模板里六张海图各一个变量（c<模型里第几列><第几排>）
            case CHART_TABLE -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_brass",
                    "c00", "prop/" + spec.texture() + "_chart_front_west", "c10", "prop/" + spec.texture() + "_chart_front_mid",
                    "c20", "prop/" + spec.texture() + "_chart_front_east", "c01", "prop/" + spec.texture() + "_chart_back_west",
                    "c11", "prop/" + spec.texture() + "_chart_back_mid", "c21", "prop/" + spec.texture() + "_chart_back_east"));
            case LECTERN -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_detail", "g", "prop/" + spec.glow() + "_lit"));
            // C3 第二轮 · c3-deck：躺椅是坐垫那一版整件缩小；瞭望台两件各自整圈缩小；空心桅杆是关着门的那两格
            case DECK_CHAIR -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_cushion"));
            case LOOKOUT_FLOOR, LOOKOUT_RIM -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_detail"));
            case HOLLOW_MAST -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "m", LinerHull.Rules.funnelTexture("buff", 0)));
            default -> null;
        };
    }

    /** 朝向 → y 旋转：模型正面朝北；Minecraft 的 y 旋转从上往下看是顺时针（北 → 东 → 南 → 西）。 */
    static int yawOf(Direction facing) {
        return LinerConnect.index(facing) * 90;
    }

    // ---------------------------------------------------------------- 摆 · 拆 · 开关

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        if (spec.kind() == Kind.SCONCE) {
            return sconcePlacement(ctx);
        }
        Direction facing = ctx.getHorizontalPlayerFacing().getOpposite();           // 正面朝着摆它的人
        BlockState s = getDefaultState().with(FACING, facing);
        BlockPos anchorPos = ctx.getBlockPos();
        World world = ctx.getWorld();
        // C3 第二轮 · c3-deck：空心桅杆摆在一格空心桅杆上面 = 往上接一格竖井（朝向跟下面那一格，横档才在同一面）；别处摆一扇门（两格）
        if (spec.kind() == Kind.HOLLOW_MAST && world.getBlockState(anchorPos.down()).isOf(this)) {
            return withRow(s.with(FACING, world.getBlockState(anchorPos.down()).get(FACING)).with(MAST, Part.SHAFT), anchorPos);
        }
        // 顶灯与吊灯只能挂在天花下：贴天花的那几格（整件最上面一层；骑缝灯是每一格）正上方的底面中间那一块要是实的
        //   （与灯笼挂着时同一个判据）。只在摆的时候看；摆好之后天花被拆了灯也不掉（挖不动、不掉东西的装饰件，
        //   结构里放下时也不该一块块碎掉）
        if (Rules.hanging(spec.kind())) {
            for (BlockPos top : topLayer(anchorPos, facing)) {
                if (!Block.sideCoversSmallSquare(world, top.up(), Direction.DOWN)) {
                    return null;
                }
            }
        }
        if (part != null) {
            Part a = Rules.anchor(spec.kind());
            for (Part p : Rules.piece(spec.kind(), a)) {
                if (p == a) {
                    continue;
                }
                BlockPos other = Rules.offset(anchorPos, a, p, facing);
                if (world.isOutOfHeightLimit(other) || !world.getWorldBorder().contains(other)
                        || !world.getBlockState(other).canReplace(ctx)) {
                    return null;                                                    // 整件放不下就不摆（同床、门）
                }
            }
        }
        if (s.contains(ON_TABLE)) {
            s = onTable(s, world.getBlockState(anchorPos.down()));
        }
        return withRow(s, anchorPos);
    }

    /** 空心桅杆这一格的 row（外壁贴图的上下格，同烟囱板按 y 算）；别的件原样。 */
    private static BlockState withRow(BlockState s, BlockPos pos) {
        return s.contains(ROW) ? s.with(ROW, LinerHull.Rules.row(pos.getY())) : s;
    }

    /**
     * 壁灯贴到哪面墙上（照游戏自带的墙上火把）：按人看的方向依次试四个水平方向，那一边的那一格朝着灯的那一面是整面实的，
     * 灯就背贴着它、朝向离墙的那一边。点中地面或天花时也照这个顺序找身边的墙；四面都没有墙就不摆。
     */
    private BlockState sconcePlacement(ItemPlacementContext ctx) {
        World world = ctx.getWorld();
        BlockPos pos = ctx.getBlockPos();
        for (Direction d : ctx.getPlacementDirections()) {
            if (d.getAxis().isHorizontal()) {
                Direction facing = d.getOpposite();
                BlockPos host = pos.offset(d);
                if (world.getBlockState(host).isSideSolidFullSquare(world, host, facing)) {
                    return getDefaultState().with(FACING, facing);
                }
            }
        }
        return null;
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.onPlaced(world, pos, state, placer, stack);
        if (part == null || world.isClient) {
            return;
        }
        Part here = state.get(part);
        for (Part p : Rules.piece(spec.kind(), here)) {
            if (p != here) {
                BlockPos at = Rules.offset(pos, here, p, state.get(FACING));
                world.setBlockState(at, withRow(state.with(part, p), at), Block.NOTIFY_ALL);
            }
        }
    }

    /** 搭档不在了（被拆、被 /setblock 换掉、被炸）这一格就跟着变成空气；台灯看正下方是不是大桌。 */
    @Override
    protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                   WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        if (part != null) {
            Part partner = Rules.partnerAt(state.get(part), direction, state.get(FACING), Rules.piece(spec.kind(), state.get(part)));
            if (partner != null && !(neighborState.isOf(this) && neighborState.get(FACING) == state.get(FACING)
                    && neighborState.get(part) == partner)) {
                return net.minecraft.block.Blocks.AIR.getDefaultState();
            }
        }
        if (state.contains(ON_TABLE) && direction == Direction.DOWN) {
            return onTable(state, neighborState);
        }
        return withRow(state, pos);
    }

    /** 台灯按正下方那一格定：是不是在大桌上、在桌上的话往桌子正中哪边挪。 */
    private static BlockState onTable(BlockState lamp, BlockState below) {
        if (!isTable(below)) {
            return lamp.with(ON_TABLE, false);
        }
        return lamp.with(ON_TABLE, true)
                .with(TABLE_CORNER, Rules.tableCorner(below.get(QUAD), below.get(FACING), lamp.get(FACING)));
    }

    /** 整件最上面那一层的每一格（单格的件就是点中的那一格）。 */
    private List<BlockPos> topLayer(BlockPos anchorPos, Direction facing) {
        List<BlockPos> out = new ArrayList<>();
        if (part == null) {
            out.add(anchorPos);
            return out;
        }
        Part a = Rules.anchor(spec.kind());
        int top = part.getValues().stream().mapToInt(p -> p.y).max().orElse(0);
        for (Part p : part.getValues()) {
            if (p.y == top) {
                out.add(Rules.offset(anchorPos, a, p, facing));
            }
        }
        return out;
    }

    private static boolean isTable(BlockState s) {
        return s.getBlock() instanceof LinerProp p && p.spec.kind() == Kind.GRAND_TABLE;
    }

    /**
     * 灯：右键开关（整件一起）。魔镜：右键穿过去（{@link io.github.heavyseasmc.mod.world.MagicMirror}）。
     * 开局的钟：右键敲钟（{@link DrillSkiff#ringBell}：响一声；演习艇旁那一口、坐在艇里的人敲 = 开阵容面板）。别的件右键没有反应。
     */
    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        // C3 第三轮打磨：瞭望台口沿这一格里点中的是底下警钟伸上来的钟身（overhangBelow）→ 这一下右键交给警钟自己（同一条路：只响、不开局）
        if (spec.kind() == Kind.LOOKOUT_RIM) {
            BlockPos down = pos.down();
            VoxelShape bell = overhangBelow(world, pos);
            if (bell != null && inside(bell, hit.getPos().subtract(pos.getX(), pos.getY(), pos.getZ()))) {
                BlockState below = world.getBlockState(down);
                return ((LinerProp) below.getBlock()).onUse(below, world, down, player, hit.withBlockPos(down));
            }
        }
        // C3 第二轮 · c3-deck：躺椅坐 · 空心桅杆的门开关 · 警钟只响（走哪条由 Rules.use 定；单测守住「只有开航钟走 DrillSkiff」）
        switch (Rules.use(spec.kind())) {
            case SIT -> {
                return DeckChairSeat.sit(state, world, pos, player);
            }
            case DOOR -> {
                return OpenDeck.toggleDoor(this, state, world, pos);
            }
            case ALARM_BELL -> {
                return OpenDeck.ringAlarm(world, pos, player);
            }
            default -> {
            }
        }
        if (Rules.use(spec.kind()) == Use.VOYAGE_BELL) {
            return DrillSkiff.ringBell(world, pos, player);
        }
        if (spec.kind() == Kind.MIRROR) {
            // 点中哪一格都一样：交给 MagicMirror 的是下面一层正中那一格（镜面正中的正下方）
            BlockPos centre = Rules.offset(pos, state.get(WIDE3), Part.WIDE_MID, state.get(FACING));
            return io.github.heavyseasmc.mod.world.MagicMirror.use(world, centre, state.get(FACING), player);
        }
        if (Rules.isPortrait(spec.kind())) {
            return usePortrait(state, world, pos, player);         // C3 第二轮 · c3-gallery
        }
        if (spec.kind() == Kind.LECTERN && !player.isSneaking()) {
            // 讲台：右键翻开规则书（客户端那一侧经钩子开书页，服务端不动世界）；潜行右键才开关阅读灯 —— 同画框「右键看、潜行右键开灯」
            if (world.isClient) {
                io.github.heavyseasmc.mod.rulebook.RulebookView.open();
            }
            return ActionResult.success(world.isClient);
        }
        if (!state.contains(LIT)) {
            return ActionResult.PASS;
        }
        if (!world.isClient) {
            boolean lit = !state.get(LIT);
            for (BlockPos p : piece(state, pos)) {
                BlockState s = world.getBlockState(p);
                if (s.isOf(this)) {
                    world.setBlockState(p, s.with(LIT, lit), Block.NOTIFY_ALL);
                }
            }
            world.playSound(null, pos, SoundEvents.BLOCK_STONE_BUTTON_CLICK_ON, SoundCategory.BLOCKS, 0.3f, lit ? 0.65f : 0.5f);
        }
        return ActionResult.success(world.isClient);
    }

    // C3 第二轮 · c3-gallery
    /**
     * 肖像画框（ADR-0086 §3）：右键看这一位的人物牌 —— 客户端那一侧经 {@link PortraitView} 开界面（钩子由客户端初始化时塞进来；
     * 服务端那一侧什么都不做，只是看，不改世界，所以不要对局、也不发包）。有画框灯时潜行右键开 / 关灯（整件一起，同别的灯）；
     * 没有灯的框潜行右键照样是看。点中哪一格都一样。
     */
    private ActionResult usePortrait(BlockState state, World world, BlockPos pos, PlayerEntity player) {
        if (player.isSneaking() && state.contains(LAMP) && state.get(LAMP) != FrameLamp.NONE) {
            if (!world.isClient) {
                FrameLamp next = state.get(LAMP) == FrameLamp.ON ? FrameLamp.OFF : FrameLamp.ON;
                for (BlockPos p : piece(state, pos)) {
                    BlockState s = world.getBlockState(p);
                    if (s.isOf(this)) {
                        world.setBlockState(p, s.with(LAMP, next), Block.NOTIFY_ALL);
                    }
                }
                world.playSound(null, pos, SoundEvents.BLOCK_STONE_BUTTON_CLICK_ON, SoundCategory.BLOCKS, 0.3f,
                        next == FrameLamp.ON ? 0.65f : 0.5f);
            }
            return ActionResult.success(world.isClient);
        }
        if (world.isClient) {
            PortraitView.open(state.get(SITTER).asString());
        }
        return ActionResult.success(world.isClient);
    }

    /** 这一格所在的整件的每一格（单格的件就是它自己）。 */
    List<BlockPos> piece(BlockState state, BlockPos pos) {
        List<BlockPos> out = new ArrayList<>();
        if (part == null) {
            out.add(pos);
            return out;
        }
        Part here = state.get(part);
        for (Part p : Rules.piece(spec.kind(), here)) {
            out.add(Rules.offset(pos, here, p, state.get(FACING)));
        }
        return out;
    }

    // ---------------------------------------------------------------- 结构转动与镜像

    @Override
    protected BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));          // 整件一起转，每一块还是那一块
    }

    /** 镜像：整件照镜子 = 模型里左右对调（西 ↔ 东），朝向照游戏自带的做法转。推导见 {@link Rules#mirrored}。 */
    @Override
    protected BlockState mirror(BlockState state, BlockMirror mirror) {
        if (mirror == BlockMirror.NONE) {
            return state;
        }
        BlockState s = state.rotate(mirror.getRotation(state.get(FACING)));
        if (s.contains(TABLE_CORNER)) {
            s = s.with(TABLE_CORNER, Rules.mirrored(state.get(TABLE_CORNER)));          // 模型里的方向，照镜子同样是左右对调
        }
        if (s.contains(BOAT_SIDE)) {
            s = s.with(BOAT_SIDE, Rules.mirrored(state.get(BOAT_SIDE)));                // 吊艇架：艇换到另一只手（模板换成镜像那一份）
        }
        return part == null ? s : s.with(part, Rules.mirrored(state.get(part)));
    }

    // ---------------------------------------------------------------- 轮廓（也是碰撞箱）

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        VoxelShape own = shapes.computeIfAbsent(state, this::shapeOf);
        // C3 第三轮打磨：瞭望台口沿底下挂着警钟时，钟身那一截并进口沿这一格的轮廓（点得中整口钟；为什么不能由警钟自己伸上来见
        //   LinerPropShapes#overhang）。只给口沿：它的碰撞箱另写（LOOKOUT_BARRIER），并进来的只是轮廓、不挡人
        if (spec.kind() == Kind.LOOKOUT_RIM) {
            VoxelShape bell = overhangBelow(world, pos);
            if (bell != null) {
                return VoxelShapes.union(own, bell);
            }
        }
        return own;
    }

    /** 正下方那一格伸进这一格的那一截轮廓（{@link LinerPropShapes#overhang}：警钟的钟身）；正下方不是有这一截的件就是 null。 */
    private static VoxelShape overhangBelow(BlockView world, BlockPos pos) {
        BlockState below = world.getBlockState(pos.down());
        if (!(below.getBlock() instanceof LinerProp p) || LinerPropShapes.overhang(p.spec.kind()).isEmpty()) {
            return null;
        }
        return p.overhangs.computeIfAbsent(below, s -> p.shapeOf(s, LinerPropShapes.overhang(p.spec.kind())));
    }

    /** 点中的那一点（这一格的坐标，0–1）落没落在这块轮廓里（边上留 1/256 格的余量：点中的点正落在面上）。 */
    private static boolean inside(VoxelShape shape, Vec3d p) {
        for (Box b : shape.getBoundingBoxes()) {
            if (b.expand(1.0 / 256).contains(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 碰撞箱就是轮廓（Minecraft 默认），只有壁灯没有：同游戏自带的墙上火把，两格宽的走廊里走过去不碰头。
     * 棕榈另算（{@link LinerPropShapes#collision}）：碰撞只算盆，叶子点得中（轮廓有）但穿得过去。
     */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (spec.kind() == Kind.SCONCE) {
            return VoxelShapes.empty();
        }
        if (LinerPropShapes.collision(spec.kind(), part == null ? null : state.get(part)) != null) {
            return collisions.computeIfAbsent(state, s -> shapeOf(s, LinerPropShapes.collision(spec.kind(), part == null ? null : s.get(part))));
        }
        return super.getCollisionShape(state, world, pos, context);
    }

    private VoxelShape shapeOf(BlockState s) {
        // 空心桅杆的门开着 / 关着轮廓不一样（C3 第二轮 · c3-deck）；别的件只看是哪一块
        List<double[]> boxes = LinerPropShapes.boxes(spec.kind(), part == null ? null : s.get(part), s.contains(OPEN) && s.get(OPEN));
        if (s.contains(LAMP) && s.get(LAMP) != FrameLamp.NONE) {
            // C3 第二轮 · c3-gallery：肖像画框带灯时，灯那一排多罩子与臂那几块
            boxes = new ArrayList<>(boxes);
            boxes.addAll(LinerPropShapes.frameLamp(spec.kind(), s.get(part)));
        }
        return shapeOf(s, boxes);
    }

    private VoxelShape shapeOf(BlockState s, List<double[]> boxes) {
        boolean onTable = s.contains(ON_TABLE) && s.get(ON_TABLE);
        double sink = onTable ? 3 : 0;
        // 在桌上的台灯往桌子正中斜挪（模型里的方向，转朝向之前挪）
        double sx = onTable ? TABLE_SHIFT * (s.get(TABLE_CORNER).x == 0 ? -1 : 1) : 0;
        double sz = onTable ? TABLE_SHIFT * (s.get(TABLE_CORNER).z == 0 ? -1 : 1) : 0;
        boolean flip = s.contains(BOAT_SIDE) && s.get(BOAT_SIDE) == BoatSide.LEFT;      // 吊艇架镜像那一份：盒子也左右对调
        VoxelShape shape = VoxelShapes.empty();
        for (double[] b0 : boxes) {
            double[] b = flip ? new double[]{16 - b0[3], b0[1], b0[2], 16 - b0[0], b0[4], b0[5]} : b0;
            double[] lo = LinerLooks.rotateY(b[0] + sx, b[2] + sz, yawOf(s.get(FACING)));
            double[] hi = LinerLooks.rotateY(b[3] + sx, b[5] + sz, yawOf(s.get(FACING)));
            // 只有下沉的台灯要夹在 0 以上；瞭望台口沿的碰撞箱往下伸到台面（y < 0，C3 第二轮 · c3-deck），不夹
            double y0 = sink > 0 ? Math.max(0, b[1] - sink) : b[1];
            double y1 = Math.max(y0 + 0.5, b[4] - sink);
            shape = VoxelShapes.union(shape, VoxelShapes.cuboid(new Box(
                    Math.min(lo[0], hi[0]) / 16, y0 / 16, Math.min(lo[1], hi[1]) / 16,
                    Math.max(lo[0], hi[0]) / 16, y1 / 16, Math.max(lo[1], hi[1]) / 16)));
        }
        return shape.simplify();
    }

    // ---------------------------------------------------------------- 纯规则

    /** 不碰世界的那一半：单测直接测这里（{@code LinerPropRulesTest}）。 */
    public static final class Rules {

        private Rules() {
        }

        /**
         * 摆的时候点中的那一格放哪一块：落地的灯是下面那一格；吊灯是贴天花的那一格（整件往下长）；
         * 沙发、大桌是「离人近、在人左手」的那一块，整件往人的右手、往远处长。
         */
        public static Part anchor(Kind kind) {
            return switch (kind) {
                case TALL_LAMP, WARDROBE, WASHSTAND, PALM, PALM_TALL, VENTILATOR, VENTILATOR_SHORT, LECTERN -> Part.LOWER;
                case CHART_TABLE -> Part.FRONT_EAST;                                // C3 第二轮 · c3-table：前排人左手那一块，往人的右手与远处长（同大桌）
                case MIRROR -> Part.WIDE_MID;                                       // 左右对称：点中的是正中，镜子立在人面前（不偏向一侧）
                case DRILL_BELL -> Part.BELL_E0;                                     // 同书柜：点中的是下面一层人左手那一块，往人的右手与上面长
                case DAVIT -> Part.BASE;                                            // 铁座靠舷外那一格；往舷内一格、往上、往舷外（正面）长
                case CHANDELIER -> Part.UPPER;
                case GRAND_CHANDELIER -> Part.CROWN;
                case GRAND_TABLE, CEILING_QUAD -> Part.NE;
                case SOFA, CEILING_PAIR, WRITING_TABLE, BOOKCASE, WICKER_SETTEE -> Part.EAST;      // 书柜：点中的是下面一层人左手那一块
                case BED -> Part.FOOT;
                case FIREPLACE, OVERMANTEL -> Part.WIDE_EAST;                       // 下面一层人左手那一块，往人的右手（模型的西）与上面长
                case BAR_COUNTER -> Part.BAY_4;
                case PORTRAIT_MEDIUM, PORTRAIT_LARGE -> Part.EAST;                   // C3 第二轮 · c3-gallery：同书柜，往人的右手与上面长
                // C3 第二轮 · c3-deck
                case DECK_CHAIR -> Part.FOOT;                                       // 同床：脚那一格在点中的那一格，头往远处长
                case LOOKOUT_FLOOR, LOOKOUT_RIM -> Part.RING_N;                     // 人与桅杆之间那一格，整圈围着它前面那一格（桅杆）长
                case HOLLOW_MAST -> Part.DOOR_LOWER;                                // 平地上摆的是一扇门（竖井由 getPlacementState 另判）
                default -> null;
            };
        }

        /** 一件里有哪几块（与方块状态的 part 属性同一份清单；单格的件是空集）。单测按它把每一种、每一块都过一遍。 */
        public static Set<Part> parts(Kind kind) {
            return switch (kind) {
                case TALL_LAMP, CHANDELIER, WARDROBE, WASHSTAND, PALM, VENTILATOR_SHORT, LECTERN -> EnumSet.of(Part.LOWER, Part.UPPER);
                case CHART_TABLE -> EnumSet.of(Part.FRONT_WEST, Part.FRONT_MID, Part.FRONT_EAST, Part.BACK_WEST, Part.BACK_MID, Part.BACK_EAST);
                case MIRROR -> EnumSet.of(Part.WIDE_WEST, Part.WIDE_MID, Part.WIDE_EAST, Part.WIDE_UPPER_WEST, Part.WIDE_UPPER_MID,
                        Part.WIDE_UPPER_EAST, Part.WIDE_TOP_WEST, Part.WIDE_TOP_MID, Part.WIDE_TOP_EAST);
                case PALM_TALL, VENTILATOR -> EnumSet.of(Part.LOWER, Part.UPPER, Part.TOP);
                case FIREPLACE, OVERMANTEL -> EnumSet.of(Part.WIDE_WEST, Part.WIDE_MID, Part.WIDE_EAST,
                        Part.WIDE_UPPER_WEST, Part.WIDE_UPPER_MID, Part.WIDE_UPPER_EAST);
                case BAR_COUNTER -> EnumSet.of(Part.BAY_1, Part.BAY_2, Part.BAY_3, Part.BAY_4,
                        Part.UPPER_BAY_1, Part.UPPER_BAY_2, Part.UPPER_BAY_3, Part.UPPER_BAY_4);
                case DAVIT -> EnumSet.of(Part.BASE, Part.BASE_IN, Part.QUADRANT, Part.ARM_A, Part.ARM_B, Part.ARM_C, Part.ARM_D,
                        Part.ARM_E, Part.ARM_F, Part.ARM_HEAD);
                case DRILL_BELL -> EnumSet.of(Part.BELL_W0, Part.BELL_W1, Part.BELL_W2, Part.BELL_W3, Part.BELL_W4, Part.BELL_W5,
                        Part.BELL_E0, Part.BELL_E1, Part.BELL_E2, Part.BELL_E3, Part.BELL_E4, Part.BELL_E5);
                case GRAND_TABLE, CEILING_QUAD -> EnumSet.of(Part.NW, Part.NE, Part.SW, Part.SE);
                case SOFA, CEILING_PAIR, WRITING_TABLE, WICKER_SETTEE -> EnumSet.of(Part.WEST, Part.EAST);
                case BED -> EnumSet.of(Part.FOOT, Part.HEAD);
                case BOOKCASE -> EnumSet.of(Part.WEST, Part.EAST, Part.UPPER_WEST, Part.UPPER_EAST);
                case GRAND_CHANDELIER -> EnumSet.of(Part.CROWN, Part.RING_NW, Part.RING_N, Part.RING_NE, Part.RING_W, Part.RING_C,
                        Part.RING_E, Part.RING_SW, Part.RING_S, Part.RING_SE);
                // C3 第二轮 · c3-gallery：肖像画框
                case PORTRAIT_MEDIUM -> EnumSet.of(Part.WEST, Part.EAST, Part.UPPER_WEST, Part.UPPER_EAST);
                case PORTRAIT_LARGE -> EnumSet.of(Part.WEST, Part.EAST, Part.UPPER_WEST, Part.UPPER_EAST, Part.TOP_WEST, Part.TOP_EAST);
                // C3 第二轮 · c3-deck
                case DECK_CHAIR -> EnumSet.of(Part.FOOT, Part.HEAD);
                case LOOKOUT_FLOOR, LOOKOUT_RIM -> EnumSet.of(Part.RING_NW, Part.RING_N, Part.RING_NE, Part.RING_W, Part.RING_E,
                        Part.RING_SW, Part.RING_S, Part.RING_SE);
                case HOLLOW_MAST -> EnumSet.of(Part.SHAFT, Part.DOOR_LOWER, Part.DOOR_UPPER);
                default -> EnumSet.noneOf(Part.class);
            };
        }

        // C3 第二轮 · c3-gallery
        /** 肖像画框（三档）：有属性 {@link #SITTER}，右键看牌。 */
        public static boolean isPortrait(Kind kind) {
            return kind == Kind.PORTRAIT_SMALL || kind == Kind.PORTRAIT_MEDIUM || kind == Kind.PORTRAIT_LARGE;
        }

        /** 带画框灯的那两档（属性 {@link #LAMP}）。 */
        public static boolean hasFrameLamp(Kind kind) {
            return kind == Kind.PORTRAIT_MEDIUM || kind == Kind.PORTRAIT_LARGE;
        }

        // ---- C3 第二轮 · c3-deck：一件由哪几块拼成 · 右键走哪条路 · 躺椅的座位

        /**
         * part 那一块所在的那一件由哪几块拼成（摆的时候一起摆、拆一格整件没、开关整件一起）。别的种类一件就是 {@link #parts} 的全部；
         * 空心桅杆一种方块两种件：竖井一格自己就是一件（上下叠着的竖井互不相干，拆一格不连累上下），门是下半 · 上半两格一件。
         */
        public static Set<Part> piece(Kind kind, Part part) {
            if (kind == Kind.HOLLOW_MAST) {
                return part == Part.SHAFT ? EnumSet.of(Part.SHAFT) : EnumSet.of(Part.DOOR_LOWER, Part.DOOR_UPPER);
            }
            return parts(kind);
        }

        /** 右键走哪条路：只有开航钟走演习艇那一条（{@link DrillSkiff#ringBell}）—— 小警钟只响、躺椅坐、空心桅杆开门，都不碰开局。 */
        public static Use use(Kind kind) {
            return switch (kind) {
                case DRILL_BELL -> Use.VOYAGE_BELL;
                case ALARM_BELL -> Use.ALARM_BELL;
                case DECK_CHAIR -> Use.SIT;
                case HOLLOW_MAST -> Use.DOOR;
                default -> Use.OTHER;
            };
        }

        /**
         * 这个人能不能坐上躺椅：旁观者不坐；<b>正骑着任何东西的人不坐</b> —— 对局座位、演习艇的报名座位、船、马都一样，
         * 躺椅从不把人从别的座位上拉下来（拉下来就是悄悄退了报名 / 离了位次），要坐先起身。
         */
        public static boolean maySit(boolean riding, boolean spectator) {
            return !riding && !spectator;
        }

        /**
         * 躺椅（B · C）的座位实体放在整件模型里的哪一点（像素，正面朝北：x 往东、y 往上、z 往南，z 16 起是头那一格）。
         * = docs 的 liner_props_opendeck.chair_seat("b")：胯落在坐垫顶上、后背离靠垫留 5 像素（照 1.21.1 的骑乘姿势量过，判据 check_sitting）；
         * liner_props.py --redtest 读这一行与它核对（两边写岔了人就坐进木头里，游戏里不报错）。
         */
        static final double[] CHAIR_SEAT_PX = {8.0, 3.05, 21.5};

        /**
         * 座位实体在世界里的位置，相对 here 那一块所在那一格的西北下角（单位：格）：先找到头那一格，再把模型里的那一点按朝向绕那一格的中心转过去。
         * （转角按 LinerConnect 算、不借外面的 yawOf：碰外面那一类就要初始化方块属性，单测里没有游戏的引导，当场抛 —— 第一次跑就撞上。）
         */
        public static double[] chairSeat(Part here, Direction facing) {
            BlockPos head = offset(BlockPos.ORIGIN, here, Part.HEAD, facing);
            double[] xz = LinerLooks.rotateY(CHAIR_SEAT_PX[0], CHAIR_SEAT_PX[2] - 16 * Part.HEAD.z, LinerConnect.index(facing) * 90);
            return new double[]{head.getX() + xz[0] / 16, head.getY() + CHAIR_SEAT_PX[1] / 16, head.getZ() + xz[1] / 16};
        }

        /**
         * 台灯在大桌上该往哪边挪（{@link #TABLE_CORNER} 的值，台灯自己模型里的方向）：先在桌子的模型里看这一块离桌子正中是哪个方向
         * （NW 那一块往 +x +z，SE 那一块往 −x −z …），按桌子的朝向转到世界，再按台灯的朝向倒转回台灯的模型里。
         */
        public static Part tableCorner(Part tablePart, Direction tableFacing, Direction lampFacing) {
            int[] w = toWorld(tablePart.x == 0 ? 1 : -1, tablePart.z == 0 ? 1 : -1, LinerConnect.index(tableFacing));
            int[] m = toWorld(w[0], w[1], (4 - LinerConnect.index(lampFacing)) & 3);
            return m[0] < 0 ? (m[1] < 0 ? Part.NW : Part.SW) : (m[1] < 0 ? Part.NE : Part.SE);
        }

        /** 有开关、会发光的那几种（壁炉的炉火也是：右键开关，默认亮）。 */
        public static boolean isLamp(Kind kind) {
            return switch (kind) {
                case TALL_LAMP, TABLE_LAMP, CEILING_LAMP, CHANDELIER, GRAND_CHANDELIER, CEILING_PAIR, CEILING_QUAD, SCONCE, FIREPLACE, LECTERN -> true;
                default -> false;
            };
        }

        /** 只能挂在天花下的那几种。 */
        public static boolean hanging(Kind kind) {
            return switch (kind) {
                case CEILING_LAMP, CHANDELIER, GRAND_CHANDELIER, CEILING_PAIR, CEILING_QUAD -> true;
                default -> false;
            };
        }

        /** 一件灯里亮着时发光的那几格：落地灯的灯头、吊灯的灯身、大吊灯那一层正中的十字五格（蜡烛灯都在这五格的模型里）。 */
        public static boolean glows(Kind kind, Part p) {
            return switch (kind) {
                case TALL_LAMP, LECTERN -> p == Part.UPPER;                         // 讲台：绿罩阅读灯在上面那一格
                case CHANDELIER -> p == Part.LOWER;
                case GRAND_CHANDELIER -> p == Part.RING_N || p == Part.RING_W || p == Part.RING_C || p == Part.RING_E
                        || p == Part.RING_S;
                case FIREPLACE -> p == Part.WIDE_MID;                              // 炭与火苗都在正中下面那一格
                // C3 第二轮 · c3-gallery：画框灯那一排两格都发光（罩子与灯管横跨两格，只一格发光时左右两半有竖缝）
                case PORTRAIT_MEDIUM -> p == Part.UPPER_WEST || p == Part.UPPER_EAST;
                case PORTRAIT_LARGE -> p == Part.TOP_WEST || p == Part.TOP_EAST;
                case PORTRAIT_SMALL -> false;
                default -> true;
            };
        }

        /**
         * 模型里的格偏移 (dx, dz) 转到世界：正面朝北时不转；朝向每顺时针一格，(x, z) → (−z, x)
         * （北 (0, −1) → 东 (1, 0)，与方块状态 y 旋转同一个方向）。
         */
        public static int[] toWorld(int dx, int dz, int facingIndex) {
            int x = dx;
            int z = dz;
            for (int i = 0; i < (facingIndex & 3); i++) {
                int nx = -z;
                z = x;
                x = nx;
            }
            return new int[]{x, z};
        }

        /** 从 from 那一块所在的位置，到 to 那一块所在的位置。 */
        public static BlockPos offset(BlockPos pos, Part from, Part to, Direction facing) {
            int[] w = toWorld(to.x - from.x, to.z - from.z, LinerConnect.index(facing));
            return pos.add(w[0], to.y - from.y, w[1]);
        }

        /** 站在 here 那一块上，朝 direction 那一格应该是哪一块；那一格不在这一件里就是 {@code null}。 */
        public static Part partnerAt(Part here, Direction direction, Direction facing, java.util.Collection<Part> parts) {
            for (Part p : parts) {
                if (p == here) {
                    continue;
                }
                int[] w = toWorld(p.x - here.x, p.z - here.z, LinerConnect.index(facing));
                if (w[0] == direction.getOffsetX() && p.y - here.y == direction.getOffsetY() && w[1] == direction.getOffsetZ()) {
                    return p;
                }
            }
            return null;
        }

        /**
         * 镜像之后这一块变成哪一块。整件照镜子，世界里的偏移被翻了一下，朝向也被翻过去；换回模型里看，
         * 对哪一种朝向、哪一种镜子都是同一件事：<b>左右对调</b>（x → 宽 − 1 − x）—— 镜子里的沙发就是左右反过来的沙发。
         */
        public static Part mirrored(Part p) {
            return switch (p) {
                case NW -> Part.NE;
                case NE -> Part.NW;
                case SW -> Part.SE;
                case SE -> Part.SW;
                case WEST -> Part.EAST;
                case EAST -> Part.WEST;
                case RING_NW -> Part.RING_NE;
                case RING_NE -> Part.RING_NW;
                case RING_W -> Part.RING_E;
                case RING_E -> Part.RING_W;
                case RING_SW -> Part.RING_SE;
                case RING_SE -> Part.RING_SW;
                case UPPER_WEST -> Part.UPPER_EAST;
                case UPPER_EAST -> Part.UPPER_WEST;
                case WIDE_WEST -> Part.WIDE_EAST;
                case WIDE_EAST -> Part.WIDE_WEST;
                case WIDE_UPPER_WEST -> Part.WIDE_UPPER_EAST;
                case WIDE_UPPER_EAST -> Part.WIDE_UPPER_WEST;
                case WIDE_TOP_WEST -> Part.WIDE_TOP_EAST;
                case WIDE_TOP_EAST -> Part.WIDE_TOP_WEST;
                case BAY_1 -> Part.BAY_4;
                case BAY_2 -> Part.BAY_3;
                case BAY_3 -> Part.BAY_2;
                case BAY_4 -> Part.BAY_1;
                case UPPER_BAY_1 -> Part.UPPER_BAY_4;
                case UPPER_BAY_2 -> Part.UPPER_BAY_3;
                case UPPER_BAY_3 -> Part.UPPER_BAY_2;
                case UPPER_BAY_4 -> Part.UPPER_BAY_1;
                case BELL_W0 -> Part.BELL_E0;
                case BELL_W1 -> Part.BELL_E1;
                case BELL_W2 -> Part.BELL_E2;
                case BELL_W3 -> Part.BELL_E3;
                case BELL_W4 -> Part.BELL_E4;
                case BELL_W5 -> Part.BELL_E5;
                case BELL_E0 -> Part.BELL_W0;
                case BELL_E1 -> Part.BELL_W1;
                case BELL_E2 -> Part.BELL_W2;
                case BELL_E3 -> Part.BELL_W3;
                case BELL_E4 -> Part.BELL_W4;
                case BELL_E5 -> Part.BELL_W5;
                case TOP_WEST -> Part.TOP_EAST;                                      // C3 第二轮 · c3-gallery
                case TOP_EAST -> Part.TOP_WEST;
                // C3 第二轮 · c3-table：海图桌
                case FRONT_WEST -> Part.FRONT_EAST;
                case FRONT_EAST -> Part.FRONT_WEST;
                case BACK_WEST -> Part.BACK_EAST;
                case BACK_EAST -> Part.BACK_WEST;
                default -> p;
            };
        }

        /** 吊艇架照镜子：艇换到另一只手（各块都在 x 0 那一列，块本身不换）。 */
        public static BoatSide mirrored(BoatSide side) {
            return side == BoatSide.LEFT ? BoatSide.RIGHT : BoatSide.LEFT;
        }
    }
}
