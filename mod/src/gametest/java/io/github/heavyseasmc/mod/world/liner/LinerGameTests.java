package io.github.heavyseasmc.mod.world.liner;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 GameTest（Fabric API 自带的 {@code fabric-gametest-api-v1}，2026-10-19 小范围试，ADR-0109 §7.2）：
 * 在真服务端的真世界里摆方块、用一个模拟玩家右键、读方块状态 —— 替掉一部分「Python 经 RCON 问隔离测试服」的世界核对。
 * 那条路的坑（RCON 回话格式 · 区块卸载后什么都答不上 · 结构模板缓存）都在 ENGINEERING 证伪表里；这里读的是 Java 对象，没有那一层。
 *
 * <p>跑：{@code ./gradlew :mod:runGametest}（起一个专用服务端、跑完全部、失败就以非 0 退出；报告在 build/gametest/report.xml）。
 * 这一份源码集只给这个任务用，不进发给玩家的 jar。
 *
 * <p>三条：① 旧存档缺的方块实体，区块载入那一步补得上（{@link LinerLiftRigs#repair}）—— 单测做不了（要真区块）；
 * ② 四件洁具右键开、开着不自己停、再右键关、拆了就关（替 evidence/2026-10-17-gecko-cabinets/water_toggle.py 那一条）；
 * ③ 柜子右键开合（衣柜：方块状态 open 两格一起变）。
 * ④ 电梯只按召唤按钮也叫得来车（用户 2026-10-22 报「只按按钮不会下来」；这里视线射线 + 整条右键路都过，复现不出来）+ 右键门的对照。
 */
public final class LinerGameTests implements FabricGameTest {

    // ---------------------------------------------------------------- ① 旧存档补方块实体

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void repairsMissingRigEntities(TestContext ctx) {
        BlockPos gl = new BlockPos(1, 1, 1);
        BlockPos gr = new BlockPos(2, 1, 1);
        BlockPos dl = new BlockPos(1, 3, 1);
        BlockPos dr = new BlockPos(2, 3, 1);
        BlockState gate = LinerLift.GATE.getDefaultState().with(LinerLift.FACING, Direction.SOUTH).with(LinerLift.OPEN, false);
        ctx.setBlockState(gl, gate.with(LinerLift.HALF, DoubleBlockHalf.LOWER).with(LinerLift.SIDE, LinerLift.Side.LEFT));
        ctx.setBlockState(gl.up(), gate.with(LinerLift.HALF, DoubleBlockHalf.UPPER).with(LinerLift.SIDE, LinerLift.Side.LEFT));
        ctx.setBlockState(gr, gate.with(LinerLift.HALF, DoubleBlockHalf.LOWER).with(LinerLift.SIDE, LinerLift.Side.RIGHT));
        ctx.setBlockState(gr.up(), gate.with(LinerLift.HALF, DoubleBlockHalf.UPPER).with(LinerLift.SIDE, LinerLift.Side.RIGHT));
        BlockState dial = LinerLift.DIAL.getDefaultState().with(LinerLift.FACING, Direction.SOUTH);
        ctx.setBlockState(dl, dial.with(LinerLift.SIDE, LinerLift.Side.LEFT));
        ctx.setBlockState(dr, dial.with(LinerLift.SIDE, LinerLift.Side.RIGHT));
        ServerWorld world = ctx.getWorld();
        List<BlockPos> carriers = List.of(ctx.getAbsolutePos(gl), ctx.getAbsolutePos(gr), ctx.getAbsolutePos(dl));
        List<BlockPos> bare = List.of(ctx.getAbsolutePos(gl.up()), ctx.getAbsolutePos(gr.up()), ctx.getAbsolutePos(dr));
        // 放下时就有（setBlockState 那一段建的）—— 正向对照：判据读得到方块实体
        for (BlockPos p : carriers) {
            ctx.assertTrue(world.getWorldChunk(p).getBlockEntity(p, WorldChunk.CreationType.CHECK) != null, "放下时 " + p + " 该有方块实体");
        }
        // 装成旧存档的样子：拿掉方块实体、方块状态不动
        for (BlockPos p : carriers) {
            world.removeBlockEntity(p);
            ctx.assertTrue(world.getWorldChunk(p).getBlockEntity(p, WorldChunk.CreationType.CHECK) == null, p + " 没拿掉");
        }
        int made = 0;
        for (WorldChunk chunk : chunksOf(world, carriers)) {
            made += LinerLiftRigs.repair(world, chunk);
        }
        for (BlockPos p : carriers) {
            ctx.assertTrue(world.getWorldChunk(p).getBlockEntity(p, WorldChunk.CreationType.CHECK) != null, "补完 " + p + " 还是没有方块实体");
        }
        for (BlockPos p : bare) {
            ctx.assertTrue(world.getWorldChunk(p).getBlockEntity(p, WorldChunk.CreationType.CHECK) == null, p + " 不该挂方块实体");
        }
        ctx.assertTrue(made == carriers.size(), "补了 " + made + " 个，应 " + carriers.size());
        ctx.complete();
    }

    private static List<WorldChunk> chunksOf(ServerWorld world, List<BlockPos> ps) {
        List<WorldChunk> out = new ArrayList<>();
        for (BlockPos p : ps) {
            WorldChunk c = world.getWorldChunk(p);
            if (!out.contains(c)) {
                out.add(c);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- ② 洁具出水开关

    /** 四件出水的（ADR-0109 §6 第 12 条）：盥洗台 · 立式洗脸盆 · 浴缸 · 花洒。 */
    private static final List<String> SPOUTS = List.of("liner_washstand", "liner_basin", "liner_bathtub", "liner_shower");

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400)
    public void waterTogglesAndKeepsRunning(TestContext ctx) {
        List<BlockPos> placed = new ArrayList<>();
        int x = 0;
        for (String id : SPOUTS) {
            LinerProp block = (LinerProp) LinerProps.all().get(id);
            ctx.assertTrue(block != null, "没有登记 " + id);
            BlockPos origin = new BlockPos(x, 1, 2);
            placeWhole(ctx, block, origin);
            placed.add(origin);
            x += 4;
        }
        ServerWorld world = ctx.getWorld();
        // 正向对照：一开始全关着 —— 「判据读不到」与「关着」要分得开，所以先核读到的是 false 而不是没量
        for (BlockPos p : placed) {
            ctx.assertTrue(!LinerAmbience.running(world, ctx.getAbsolutePos(p)), p + " 一开始就在出水");
            ctx.useBlock(p);
            ctx.assertTrue(LinerAmbience.running(world, ctx.getAbsolutePos(p)), p + " 右键之后没出水");
        }
        // 开着一直出（原来是 5 秒就停）：等 8 秒再看
        ctx.waitAndRun(160, () -> {
            for (BlockPos p : placed) {
                ctx.assertTrue(LinerAmbience.running(world, ctx.getAbsolutePos(p)), p + " 8 秒后自己停了");
            }
            BlockPos first = placed.get(0);
            ctx.useBlock(first);                                         // 再点一下：关
            ctx.assertTrue(!LinerAmbience.running(world, ctx.getAbsolutePos(first)), first + " 再右键没关");
            BlockPos second = placed.get(1);
            ctx.setBlockState(second, Blocks.AIR);                       // 拆了：下一 tick 关
            ctx.setBlockState(second.up(), Blocks.AIR);
            ctx.waitAndRun(2, () -> {
                ctx.assertTrue(!LinerAmbience.running(world, ctx.getAbsolutePos(second)), second + " 拆了还在出水");
                ctx.complete();
            });
        });
    }

    // ---------------------------------------------------------------- ③ 柜子开合

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void wardrobeOpensAndCloses(TestContext ctx) {
        LinerProp ward = (LinerProp) LinerProps.all().get("liner_wardrobe");
        BlockPos o = new BlockPos(2, 1, 2);
        placeWhole(ctx, ward, o);
        ctx.assertTrue(!ctx.getBlockState(o).get(LinerProp.OPEN), "一开始就开着");
        ctx.useBlock(o.up());                                            // 点上半格：整件一起开
        ctx.assertTrue(ctx.getBlockState(o).get(LinerProp.OPEN) && ctx.getBlockState(o.up()).get(LinerProp.OPEN), "右键上半格，两格没一起开");
        ctx.useBlock(o);
        ctx.assertTrue(!ctx.getBlockState(o).get(LinerProp.OPEN) && !ctx.getBlockState(o.up()).get(LinerProp.OPEN), "再右键没一起关");
        ctx.complete();
    }

    // ---------------------------------------------------------------- ④ 电梯：只按召唤按钮也叫得来车

    /**
     * 用户 2026-10-22：「如果我只按按钮不右键门，电梯是不会下来的」。两层一部电梯（门朝南，左扇 x 1、右扇 x 2，按钮贴在左扇左手边那一列墙前），
     * 车停在下层；一个模拟玩家站在上层按钮前，<b>沿视线射线</b>点中按钮（与真客户端同一条：射线认外形框，点到的是哪一格就交给哪一格），
     * 走服务端处理右键的整条路（{@code interactBlock}：右键事件 → 方块 onUse），等车上来开门。{@code viaGate} = 对照：右键上层的门。
     */
    private void liftCalledFromAbove(TestContext ctx, boolean viaGate) {
        BlockPos low = new BlockPos(1, 1, 1);
        BlockPos high = new BlockPos(1, 5, 1);
        for (BlockPos gl : List.of(low, high)) {
            BlockState gate = LinerLift.GATE.getDefaultState().with(LinerLift.FACING, Direction.SOUTH).with(LinerLift.OPEN, false);
            for (int dx = 0; dx < 2; dx++) {
                LinerLift.Side side = dx == 0 ? LinerLift.Side.LEFT : LinerLift.Side.RIGHT;
                ctx.setBlockState(gl.east(dx), gate.with(LinerLift.HALF, DoubleBlockHalf.LOWER).with(LinerLift.SIDE, side));
                ctx.setBlockState(gl.east(dx).up(), gate.with(LinerLift.HALF, DoubleBlockHalf.UPPER).with(LinerLift.SIDE, side));
                ctx.setBlockState(gl.east(dx).up(2), LinerLift.DIAL.getDefaultState().with(LinerLift.FACING, Direction.SOUTH)
                        .with(LinerLift.SIDE, side));
            }
            ctx.setBlockState(gl.west(), Blocks.STONE);                       // 按钮背后那一列墙
            ctx.setBlockState(gl.west().up(), Blocks.STONE);
            ctx.setBlockState(gl.west().up().south(), LinerLift.CALL.getDefaultState().with(LinerLift.FACING, Direction.SOUTH));
        }
        ServerWorld world = ctx.getWorld();
        BlockPos button = ctx.getAbsolutePos(high.west().up().south());
        BlockPos gateHigh = ctx.getAbsolutePos(high);
        BlockPos target = viaGate ? gateHigh.up() : button;
        net.minecraft.server.network.ServerPlayerEntity player = ctx.createMockCreativeServerPlayerInWorld();
        // 站在目标前方 2.5 格、眼睛看向它正面那一层（按钮的板贴在格子北沿：z 0–2.25 像素；门看格子中心）
        net.minecraft.util.math.Vec3d aim = viaGate ? net.minecraft.util.math.Vec3d.ofCenter(target)
                : new net.minecraft.util.math.Vec3d(button.getX() + 0.5, button.getY() + 0.5, button.getZ() + 0.07);
        player.refreshPositionAndAngles(aim.x, aim.y - 1.5, target.getZ() + 3.0, 180f, 0f);
        net.minecraft.util.math.Vec3d eye = player.getEyePos();
        net.minecraft.util.hit.BlockHitResult hit = world.raycast(new net.minecraft.world.RaycastContext(eye, aim.add(aim.subtract(eye).normalize()),
                net.minecraft.world.RaycastContext.ShapeType.OUTLINE, net.minecraft.world.RaycastContext.FluidHandling.NONE, player));
        ctx.assertTrue(hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK && hit.getBlockPos().equals(target),
                "视线点中的是 " + hit.getBlockPos() + " " + world.getBlockState(hit.getBlockPos()) + "，不是 " + target);
        player.interactionManager.interactBlock(player, world, player.getMainHandStack(), net.minecraft.util.Hand.MAIN_HAND, hit);
        ctx.waitAndRun(100, () -> {
            ctx.assertTrue(world.getBlockState(gateHigh).get(LinerLift.OPEN), (viaGate ? "右键门" : "只按按钮") + "：5 秒后上层的门没开（车没上来）");
            ctx.assertTrue(!world.getBlockState(ctx.getAbsolutePos(low)).get(LinerLift.OPEN), "下层的门开着");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200)
    public void liftComesOnCallButtonOnly(TestContext ctx) {
        liftCalledFromAbove(ctx, false);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200)
    public void liftComesOnGateClick(TestContext ctx) {
        liftCalledFromAbove(ctx, true);
    }

    /** 整件按朝北摆好（每一格写上自己的 part），照 {@link LinerProp#placeWhole} 的同一套格偏移。 */
    private static void placeWhole(TestContext ctx, LinerProp block, BlockPos origin) {
        for (var e : block.wholeStates(Direction.NORTH).entrySet()) {
            ctx.setBlockState(origin.add(e.getKey()), e.getValue());
        }
    }
}
