package io.github.heavyseasmc.mod.world.liner;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 装修锤（ADR-0093 B15 · B5，用户 2026-10-07 定）：一把工具管「放下以后再改」—— 拿着它右键一格，那一格（整件一起）换成下一种形态，
 * 快捷栏上方报一行新形态的名字（只写状态名）。管的是这几样：
 * <ul>
 *   <li>整扇窗的窗台（屋里 → 屋外 → 两边 → 没有，整扇一起换，玻璃跟着窗台走）</li>
 *   <li>墙面类整块的背面（外墙白 ↔ 饰面，{@link LinerBlocks#EXTERIOR}）</li>
 *   <li>桃花心木顶帽（普通 ↔ 高檐）· 桃花心木壁柱（左半根 → 居中 → 右半根）</li>
 *   <li>吸顶灯（一格 → 两格东西 → 两格南北 → 四格；旁边放不下的那一种跳过）—— 两格 / 四格就是骑缝的那两件（亮度对称、每块都点得中）</li>
 *   <li>格架的常春藤（有 ↔ 没有）· 躺椅的毯（搭 ↔ 不搭）· 肖像画的是谁（八个人轮着）· 吊艇架的艇在哪一边（左 ↔ 右）</li>
 * </ul>
 * 在「右键方块」事件里最先接：灯这类方块自己的右键（开关灯）会先于手里的物品被叫到，锤子要在它之前截住。
 * 点中的不是这几样就放行（DrillSkiff 等后面的照常）。潜行右键：舷窗锁成单格 ↔ 自动拼大（第 5 批 5b，{@link LinerHull#toggleLock}），别的放行。
 */
public final class DecorHammer extends Item {

    public static final DecorHammer HAMMER = new DecorHammer(new Item.Settings().maxCount(1));

    /** 摆动灯件时只改这几格、不惊动邻居（两格 / 四格灯缺一块会整件拆掉 —— 摆到一半旁边那块就被拆了）：通知客户端 · 不做邻居的形状更新 · 不掉东西。 */
    private static final int QUIET = Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS;

    private DecorHammer(Settings settings) {
        super(settings);
    }

    /** 登记物品，并在「右键方块」事件里接住（由 {@link LinerBlocks#register} 叫，排在别的右键事件前面）。 */
    static void register() {
        Registry.register(Registries.ITEM, Identifier.of(HeavySeasMod.MOD_ID, "decor_hammer"), HAMMER);
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> use(player, world, hand, hit));
    }

    static ActionResult use(PlayerEntity player, World world, Hand hand, BlockHitResult hit) {
        if (!player.getStackInHand(hand).isOf(HAMMER)) {
            return ActionResult.PASS;
        }
        BlockPos pos = hit.getBlockPos();
        if (!mayEdit(player, world, pos)) {
            return ActionResult.PASS;         // 冒险 / 旁观 / 出生点保护里：锤子不改船（审查 2026-10-07 U10）
        }
        BlockState state = world.getBlockState(pos);
        if (player.isSneaking()) {
            return lock(player, world, pos, state);
        }
        Change change = next(world, pos, state);
        if (change == null) {
            return ActionResult.PASS;
        }
        if (!world.isClient) {
            change.cells.forEach((p, s) -> world.setBlockState(p, s, change.quiet ? QUIET : Block.NOTIFY_ALL));
            change.removed.forEach(p -> world.setBlockState(p, net.minecraft.block.Blocks.AIR.getDefaultState(), QUIET));
            if (change.quiet) {
                change.cells.keySet().forEach(p -> world.updateNeighbors(p, change.cells.get(p).getBlock()));
            }
            world.playSound(null, pos, SoundEvents.BLOCK_WOOD_HIT, SoundCategory.BLOCKS, 0.6f, 1.2f);
            player.sendMessage(change.label, true);
        }
        return ActionResult.success(world.isClient);
    }

    /**
     * 这个人此刻能不能拿工具改这一格（锤子 · 剪刀揭地毯共用）：不是旁观者、能改世界（冒险模式不能，{@code canModifyBlocks}）、
     * 这一格不在他改不了的地方（服务端上还查出生点保护与世界边界，{@code canModifyAt}）—— 与 Minecraft 自己拿物品改方块那条路
     * （{@code ItemStack#useOnBlock} 认 {@code allowModifyWorld}）同一套判据。
     *
     * <p>❗审查 2026-10-07 U10：原先只看手里拿的是不是锤子。Fabric 的「右键方块」事件挂在旁观者那道检查之前，
     * 冒险模式又照样走到这里 —— 船上的冒险模式玩家、甚至旁观者，拿着锤子就能改北辰号。
     */
    static boolean mayEdit(PlayerEntity player, World world, BlockPos pos) {
        return !player.isSpectator() && player.canModifyBlocks() && player.canModifyAt(world, pos);
    }

    /** 潜行右键：舷窗锁成单格 ↔ 自动拼（ADR-0093 B12，{@link LinerHull#toggleLock}）；别的方块放行。 */
    private static ActionResult lock(PlayerEntity player, World world, BlockPos pos, BlockState state) {
        if (!LinerHull.isPorthole(state)) {
            return ActionResult.PASS;
        }
        if (!world.isClient) {
            boolean locked = LinerHull.toggleLock(world, pos);
            world.playSound(null, pos, SoundEvents.BLOCK_WOOD_HIT, SoundCategory.BLOCKS, 0.6f, 0.9f);
            player.sendMessage(label("porthole", locked ? "locked" : "free"), true);
        }
        return ActionResult.success(world.isClient);
    }

    /** 一次轮换：要写的格（位置 → 新状态）· 要拆成空气的格 · 快捷栏上那一行 · 要不要「不惊动邻居」地摆。 */
    record Change(Map<BlockPos, BlockState> cells, List<BlockPos> removed, Text label, boolean quiet) {

        static Change of(BlockPos pos, BlockState s, Text label) {
            Map<BlockPos, BlockState> m = new LinkedHashMap<>();
            m.put(pos, s);
            return new Change(m, List.of(), label, false);
        }
    }

    /** 这一格下一种形态；不归锤子管、或者没有能换的就是 null。 */
    static Change next(World world, BlockPos pos, BlockState s) {
        Block block = s.getBlock();
        if (block instanceof LinerGlass.Window w) {
            LinerGlass.Sill sill = Rules.nextSill(s.get(LinerGlass.SILL));
            Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
            for (BlockPos p : w.cellsOf(pos, s)) {
                BlockState o = world.getBlockState(p);
                if (o.isOf(w) && o.get(LinerBlocks.FACING) == s.get(LinerBlocks.FACING)) {
                    cells.put(p, o.with(LinerGlass.SILL, sill));
                }
            }
            return new Change(cells, List.of(), label("sill", sill.asString()), false);
        }
        if (block instanceof LinerBlock lb) {
            if (s.contains(LinerBlocks.EXTERIOR)) {
                boolean ext = !s.get(LinerBlocks.EXTERIOR);
                return Change.of(pos, s.with(LinerBlocks.EXTERIOR, ext), label("exterior", ext ? "on" : "off"));
            }
            if (s.contains(LinerBlocks.TALL)) {
                boolean tall = !s.get(LinerBlocks.TALL);
                return Change.of(pos, lb.connect(s.with(LinerBlocks.TALL, tall), world, pos), label("tall", tall ? "on" : "off"));
            }
            if (s.contains(LinerBlocks.CAPITAL)) {
                LinerBlocks.PilasterSide side = Rules.nextSide(s.get(LinerBlocks.SIDE));
                return Change.of(pos, lb.connect(s.with(LinerBlocks.SIDE, side), world, pos), label("side", side.asString()));
            }
            if (s.contains(LinerBlocks.IVY)) {
                boolean ivy = s.get(LinerBlocks.IVY) == LinerBlocks.Ivy.NONE;
                BlockState t = s.with(LinerBlocks.IVY, ivy ? LinerBlocks.Ivy.MID : LinerBlocks.Ivy.NONE);
                return Change.of(pos, lb.connect(t, world, pos), label("ivy", ivy ? "on" : "off"));
            }
            return null;
        }
        if (block instanceof LinerProp prop) {
            if (block == LinerProps.CEILING_LIGHT || block == LinerProps.CEILING_LIGHT_PAIR || block == LinerProps.CEILING_LIGHT_QUAD) {
                return lamp(world, pos, s, prop);
            }
            if (s.contains(LinerProp.RUG)) {
                boolean rug = !s.get(LinerProp.RUG);
                return whole(world, pos, s, prop, o -> o.with(LinerProp.RUG, rug), label("rug", rug ? "on" : "off"));
            }
            if (s.contains(LinerProp.SITTER)) {
                LinerProp.Sitter who = Rules.nextSitter(s.get(LinerProp.SITTER));
                return whole(world, pos, s, prop, o -> o.with(LinerProp.SITTER, who),
                        Text.translatable("heavyseas.hammer.sitter", Text.translatable("heavyseas.character." + who.asString())));
            }
            if (s.contains(LinerProp.BOAT_SIDE)) {
                LinerProp.BoatSide side = s.get(LinerProp.BOAT_SIDE) == LinerProp.BoatSide.LEFT ? LinerProp.BoatSide.RIGHT : LinerProp.BoatSide.LEFT;
                return whole(world, pos, s, prop, o -> o.with(LinerProp.BOAT_SIDE, side), label("boat_side", side.asString()));
            }
        }
        return null;
    }

    /** 整件（躺椅 · 肖像 · 吊艇架）每一格都改同一个属性。 */
    private static Change whole(World world, BlockPos pos, BlockState s, LinerProp prop,
                                java.util.function.UnaryOperator<BlockState> f, Text label) {
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        for (BlockPos p : prop.piece(s, pos)) {
            BlockState o = world.getBlockState(p);
            if (o.isOf(prop)) {
                cells.put(p, f.apply(o));
            }
        }
        return new Change(cells, List.of(), label, false);
    }

    /**
     * 吸顶灯：一格 → 两格（东西）→ 两格（南北）→ 四格 → 一格。整件按西北角那一格起算（从两格 / 四格往回换也落在那一格）；
     * 新形态要占的格得是空气（或本来就是这一件的）、头顶不是空的（灯挂在天花下）—— 放不下的那一种跳过。亮着灭着照旧。
     */
    private static Change lamp(World world, BlockPos pos, BlockState s, LinerProp prop) {
        List<BlockPos> old = prop.piece(s, pos);
        BlockPos origin = old.stream().reduce((a, b) -> b.getX() < a.getX() || b.getX() == a.getX() && b.getZ() < a.getZ() ? b : a).orElse(pos);
        Rules.LampForm form = Rules.LampForm.of(old.size(), old.stream().anyMatch(p -> p.getX() != origin.getX()));
        boolean lit = s.get(LinerProp.LIT);
        for (Rules.LampForm f = form.next(); f != form; f = f.next()) {
            List<BlockPos> cells = f.cells(origin);
            boolean room = cells.stream().allMatch(p -> (old.contains(p) || world.getBlockState(p).isAir()) && !world.getBlockState(p.up()).isAir());
            if (!room) {
                continue;
            }
            Map<BlockPos, BlockState> put = new LinkedHashMap<>();
            for (int i = 0; i < cells.size(); i++) {
                put.put(cells.get(i), f.state(i).with(LinerProp.LIT, lit));
            }
            List<BlockPos> removed = new ArrayList<>(old);
            removed.removeAll(cells);
            return new Change(put, removed, label("lamp", f.key()), true);
        }
        return new Change(Map.of(), List.of(), Text.translatable("heavyseas.hammer.no_room"), false);
    }

    private static Text label(String what, String value) {
        return Text.translatable("heavyseas.hammer." + what, Text.translatable("heavyseas.hammer." + what + "." + value));
    }

    /** 纯规则（单测 DecorHammerRulesTest）：各样东西的轮换次序。 */
    static final class Rules {

        private Rules() {
        }

        /** 窗台：屋里（放下时的默认）→ 屋外 → 两边 → 没有 → 屋里。 */
        static LinerGlass.Sill nextSill(LinerGlass.Sill s) {
            return switch (s) {
                case INSIDE -> LinerGlass.Sill.OUTSIDE;
                case OUTSIDE -> LinerGlass.Sill.BOTH;
                case BOTH -> LinerGlass.Sill.NONE;
                case NONE -> LinerGlass.Sill.INSIDE;
            };
        }

        /** 木壁柱：左半根 → 居中 → 右半根 → 左半根。 */
        static LinerBlocks.PilasterSide nextSide(LinerBlocks.PilasterSide s) {
            return switch (s) {
                case LEFT -> LinerBlocks.PilasterSide.CENTER;
                case CENTER -> LinerBlocks.PilasterSide.RIGHT;
                case RIGHT -> LinerBlocks.PilasterSide.LEFT;
            };
        }

        /** 肖像：照座位号轮着画下一个人。 */
        static LinerProp.Sitter nextSitter(LinerProp.Sitter s) {
            LinerProp.Sitter[] all = LinerProp.Sitter.values();
            return all[(s.ordinal() + 1) % all.length];
        }

        /** 吸顶灯的四种形态；格子按西北角那一格起算，状态照北辰号上两格灯 / 四格灯的摆法（朝北的两格东西排、朝东的两格南北排、四格朝北）。 */
        enum LampForm {
            SINGLE("single"), PAIR_X("pair_x"), PAIR_Z("pair_z"), QUAD("quad");

            final String key;

            LampForm(String key) {
                this.key = key;
            }

            String key() {
                return key;
            }

            static LampForm of(int cells, boolean alongX) {
                return cells == 1 ? SINGLE : cells == 4 ? QUAD : alongX ? PAIR_X : PAIR_Z;
            }

            LampForm next() {
                return values()[(ordinal() + 1) % values().length];
            }

            List<BlockPos> cells(BlockPos o) {
                return switch (this) {
                    case SINGLE -> List.of(o);
                    case PAIR_X -> List.of(o, o.east());
                    case PAIR_Z -> List.of(o, o.south());
                    case QUAD -> List.of(o, o.east(), o.south(), o.east().south());
                };
            }

            /** 第 i 格的状态（与 {@link #cells} 同序）。 */
            BlockState state(int i) {
                return switch (this) {
                    case SINGLE -> LinerProps.CEILING_LIGHT.getDefaultState().with(LinerProp.FACING, Direction.NORTH);
                    case PAIR_X -> LinerProps.CEILING_LIGHT_PAIR.getDefaultState().with(LinerProp.FACING, Direction.NORTH)
                            .with(LinerProp.PAIR, i == 0 ? LinerProp.Part.WEST : LinerProp.Part.EAST);
                    case PAIR_Z -> LinerProps.CEILING_LIGHT_PAIR.getDefaultState().with(LinerProp.FACING, Direction.EAST)
                            .with(LinerProp.PAIR, i == 0 ? LinerProp.Part.WEST : LinerProp.Part.EAST);
                    case QUAD -> LinerProps.CEILING_LIGHT_QUAD.getDefaultState().with(LinerProp.FACING, Direction.NORTH)
                            .with(LinerProp.QUAD, new LinerProp.Part[]{LinerProp.Part.NW, LinerProp.Part.NE, LinerProp.Part.SW, LinerProp.Part.SE}[i]);
                };
            }
        }
    }
}
