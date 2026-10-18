package io.github.heavyseasmc.mod.world.liner;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSetType;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.TallBlockItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static io.github.heavyseasmc.mod.world.liner.LinerLooks.tex;

/**
 * 北辰号的门（ADR-0069 §2 第 ⑤ 批）：继承游戏自带的门拿开关、红石、两格一起放一起拆与碰撞箱；模型与贴图全是自己的
 * （{@code liner_hull.py --write} 画进 {@code template/hull/door_*} · {@code textures/block/liner/hull/door_*}）。
 *
 * <ul>
 *   <li><b>客房门</b>：白漆镶板门，黄铜门把手（两面都有），走廊那一面上半截一块空白的黄铜门牌 —— 门牌是门本身的一部分
 *       （ADR-0069 §4：贴在会动的东西上的件做成门的变体），开门时跟着门扇转。</li>
 *   <li><b>大房间双开门</b>：桃花心木，上半截玻璃（田字分格）。两扇各是一扇门，紧挨着放时游戏自己把第二扇的合页放到另一边；
 *       <b>右键一扇，两扇一起开关</b>（{@link Rules#partnerSide}）。红石仍是各管各的（与游戏自带的门相同）。</li>
 * </ul>
 *
 * <p>模板按<b>朝东</b>（{@code facing=east}，y 0）作画，与游戏自带的门同一个约定：门扇贴着这一格的西边（x 0–3），合页在左手（北，z 0），
 * 走廊那一面朝西（摆门的人站的那一边）。开着的模板是同一扇门绕合页转过去之后的样子（不是镜像），走廊那一面跟着转。
 * 朝向 → y 旋转：东 0 · 南 90 · 西 180 · 北 270。门框不在这里（贴附件那一批）。
 */
public final class LinerDoors {

    private static final Map<String, LinerDoor> DOORS = new LinkedHashMap<>();

    public static final LinerDoor CABIN = door("liner_door_cabin", Kind.CABIN, MapColor.OFF_WHITE);
    public static final LinerDoor DOUBLE = door("liner_door_double", Kind.DOUBLE, MapColor.DARK_RED);

    /** 两种门。 */
    public enum Kind {
        /** 客房门：白漆镶板 + 黄铜门把手 + 空白门牌。 */
        CABIN,
        /** 大房间双开门的一扇：桃花心木 + 上半截玻璃；右键带着隔壁那一扇一起开关。 */
        DOUBLE;

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private LinerDoors() {
    }

    /** 方块与物品一起登记：物品照游戏自带的门用「两格高」的那一种（放下时先清上面那一格）。 */
    public static void register() {
        DOORS.forEach((name, block) -> {
            Identifier id = Identifier.of(HeavySeasMod.MOD_ID, name);
            Registry.register(Registries.BLOCK, id, block);
            Registry.register(Registries.ITEM, id, new TallBlockItem(block, new Item.Settings()));
        });
    }

    public static Map<String, LinerDoor> all() {
        return Collections.unmodifiableMap(DOORS);
    }

    private static LinerDoor door(String name, Kind kind, MapColor color) {
        AbstractBlock.Settings settings = AbstractBlock.Settings.create().mapColor(color).strength(-1.0f, 3_600_000.0f)
                .dropsNothing().pistonBehavior(PistonBehavior.BLOCK).nonOpaque();
        LinerDoor block = new LinerDoor(settings, kind);
        DOORS.put(name, block);
        return block;
    }

    /** 一扇门。开关、红石、上下两格的联动、碰撞箱都是游戏自带门的；这里只管样子与双开门的联动。 */
    public static final class LinerDoor extends DoorBlock implements LinerLooks.Styled {

        private final Kind kind;

        LinerDoor(AbstractBlock.Settings settings, Kind kind) {
            super(BlockSetType.OAK, settings);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }

        @Override
        public LinerLooks.Look look(BlockState state) {
            String half = state.get(HALF) == DoubleBlockHalf.LOWER ? "lower" : "upper";
            String hinge = state.get(HINGE) == DoorHinge.LEFT ? "left" : "right";
            String template = "hull/door_" + kind.id() + "_" + half + "_" + hinge + (state.get(OPEN) ? "_open" : "");
            return LinerLooks.look(template, textures(half), Rules.yaw(state.get(FACING)));
        }

        /** 物品：一整扇门（上下两格）缩小。 */
        @Override
        public LinerLooks.Look itemLook() {
            return LinerLooks.look("hull/door_" + kind.id() + "_item", kind == Kind.DOUBLE
                    ? tex("f", "hull/door_double_lower", "u", "hull/door_double_upper", "m", "hull/brass", "g", "hull/glass")
                    : tex("f", "hull/door_cabin_lower", "u", "hull/door_cabin_upper", "m", "hull/brass"));
        }

        private Map<String, String> textures(String half) {
            return kind == Kind.DOUBLE
                    ? tex("f", "hull/door_double_" + half, "m", "hull/brass", "g", "hull/glass")
                    : tex("f", "hull/door_cabin_" + half, "m", "hull/brass");
        }

        /** 双开门：右键这一扇，隔壁那一扇（同一种门、同一朝向、合页在另一边、同一半）跟着开或关。 */
        @Override
        protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
            ActionResult result = super.onUse(state, world, pos, player, hit);
            if (kind != Kind.DOUBLE || !result.isAccepted()) {
                return result;
            }
            BlockState now = world.getBlockState(pos);
            if (!now.isOf(this)) {
                return result;
            }
            BlockPos other = pos.offset(Rules.partnerSide(state.get(FACING), state.get(HINGE)));
            BlockState o = world.getBlockState(other);
            if (o.isOf(this) && o.get(FACING) == state.get(FACING) && o.get(HINGE) != state.get(HINGE)
                    && o.get(HALF) == state.get(HALF) && o.get(OPEN) != now.get(OPEN)) {
                // 只改这一半：另一半由游戏自带门的「上下两格对齐」跟着改（同右键门的上半格）
                world.setBlockState(other, o.with(OPEN, now.get(OPEN)), Block.NOTIFY_LISTENERS | Block.REDRAW_ON_MAIN_THREAD);
            }
            return result;
        }
    }

    // ================================================================ 纯规则

    /** 单测 {@code LinerDoorRulesTest}；{@code liner_hull.py} 的样张与判据用同一套。 */
    public static final class Rules {

        private Rules() {
        }

        /**
         * 双开门的另一扇在哪一边：合页在左手（朝 facing 看）时，门扇的活动边在右手，另一扇就在右手那一格 —— facing 顺时针转一格；
         * 合页在右手时反过来。与游戏自带门摆放时定合页的规则一致：左手边已经有一扇门，新摆的这一扇合页就放在右手。
         */
        public static Direction partnerSide(Direction facing, DoorHinge hinge) {
            return hinge == DoorHinge.LEFT ? facing.rotateYClockwise() : facing.rotateYCounterclockwise();
        }

        /** 朝向 → y 旋转：模板朝东（游戏自带门的约定）。 */
        public static int yaw(Direction facing) {
            return switch (facing) {
                case SOUTH -> 90;
                case WEST -> 180;
                case NORTH -> 270;
                default -> 0;
            };
        }
    }
}
