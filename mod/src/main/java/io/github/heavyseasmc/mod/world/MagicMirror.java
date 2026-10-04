package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.world.liner.LinerShip;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 魔镜（ADR-0083；观感与版本 ADR-0065）：玩家自己世界里那面右键 → 托管背包与身体、落到北辰号大楼梯平台（船上那面镜子前）；
 * 北辰号上那面右键 → 背包还回来、回到进来的那面镜子前（那面不在了就送重生点，并明说）。
 *
 * <p>判「这面镜子在哪一边」只看维度：北辰号所在的那个维度里的镜子都是「回家」那一面（ADR-0054 D12：北辰号与对局同一个维度）。
 * 穿越动画（水波 · 老照片 · 白闪 · 字幕）是客户端那一层，另起一批（ADR-0065）；这里只做传送与托管，回滚动画时退回的就是这一层。
 */
public final class MagicMirror {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private MagicMirror() {
    }

    /**
     * @param centre 镜子下面一层正中那一格（镜面正中的正下方；镜子 3 × 3，{@code LinerProp} 从点中的那一格换算过来）
     * @param facing 镜面朝哪（摆它的人站在这一边）
     */
    public static ActionResult use(World rawWorld, BlockPos centre, Direction facing, PlayerEntity rawPlayer) {
        if (!(rawWorld instanceof ServerWorld world) || !(rawPlayer instanceof ServerPlayerEntity player)) {
            return ActionResult.SUCCESS;                // 客户端：挥一下手，结果由服务端定
        }
        if (player.isSpectator()) {
            return ActionResult.FAIL;
        }
        Optional<LinerShip.Manifest> manifest = LinerShip.manifest();
        if (manifest.isEmpty()) {
            player.sendMessage(Text.translatable("heavyseas.mirror.no_liner"), true);
            return ActionResult.FAIL;
        }
        if (world.getRegistryKey().equals(manifest.get().dimension())) {
            return home(player);
        }
        return cross(world, player, centre, facing, manifest.get());
    }

    private static ActionResult cross(ServerWorld world, ServerPlayerEntity player, BlockPos centre, Direction facing,
                                      LinerShip.Manifest manifest) {
        ServerWorld liner = player.server.getWorld(manifest.dimension());
        if (liner == null || !manifest.version().equals(LinerShip.placedVersion(player.server)) || LinerShip.progress().isPresent()) {
            player.sendMessage(Text.translatable("heavyseas.mirror.no_liner"), true);
            return ActionResult.FAIL;
        }
        for (ServerWorld any : player.server.getWorlds()) {
            GameComponent component = GameComponents.of(any);
            if (component.belongsToActiveVoyage(player.getUuid()) || component.hasVoyageEscrow(player.getUuid())) {
                player.sendMessage(Text.translatable("heavyseas.mirror.busy"), true);
                return ActionResult.FAIL;
            }
        }
        // 回程从这面镜子出来：镜面正中前那一格、背对镜子
        Vec3d back = Vec3d.ofBottomCenter(centre.offset(facing));
        try {
            player.stopRiding();
            MistSea.escrowAtMirror(liner, player, new GameComponent.MirrorAt(world.getRegistryKey().getValue().toString(), centre),
                    back, facing.asRotation());
        } catch (IllegalStateException refused) {
            player.sendMessage(Text.translatable("heavyseas.mirror.busy"), true);
            return ActionResult.FAIL;
        }
        world.playSound(null, centre, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.BLOCKS, 1f, 0.6f);
        MistSea.toLiner(player, MistSea.mirrorEscrow(player).orElseThrow());
        // 与语言无关的一行：验收脚本按「魔镜：… 过去了」认这一下真的走通了
        LOGGER.info("魔镜：{} 过去了（{} {} → 北辰号 {}）", player.getGameProfile().getName(), world.getRegistryKey().getValue(),
                centre.toShortString(), manifest.arrival());
        return ActionResult.SUCCESS;
    }

    private static ActionResult home(ServerPlayerEntity player) {
        for (ServerWorld any : player.server.getWorlds()) {
            if (GameComponents.of(any).belongsToActiveVoyage(player.getUuid())) {
                player.sendMessage(Text.translatable("heavyseas.mirror.busy"), true);
                return ActionResult.FAIL;
            }
        }
        MistSea.Home home;
        try {
            home = MistSea.goHome(player);
        } catch (IllegalStateException failed) {
            player.sendMessage(Text.literal(failed.getMessage()).formatted(Formatting.RED), true);
            return ActionResult.FAIL;
        }
        switch (home) {
            case MIRROR -> player.sendMessage(Text.translatable("heavyseas.mirror.home"), true);
            case RESPAWN, WORLD_SPAWN -> {
                BlockPos at = player.getBlockPos();
                Text where = Text.translatable(home == MistSea.Home.RESPAWN ? "heavyseas.mirror.where_respawn" : "heavyseas.mirror.where_spawn");
                // 不悄悄换地方（ADR-0065 样张页第 9 步）：屏幕上一行、聊天栏留一条带坐标的
                player.sendMessage(Text.translatable("heavyseas.mirror.gone", where), true);
                player.sendMessage(Text.translatable("heavyseas.mirror.gone_log", where, at.getX(), at.getY(), at.getZ()), false);
            }
            case NO_ESCROW -> player.sendMessage(Text.translatable("heavyseas.mirror.home_no_escrow"), true);
        }
        LOGGER.info("魔镜：{} 回家了（{} · {}）", player.getGameProfile().getName(), home, player.getBlockPos().toShortString());
        return ActionResult.SUCCESS;
    }
}
