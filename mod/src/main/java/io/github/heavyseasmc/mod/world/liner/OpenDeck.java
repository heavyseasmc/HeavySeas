package io.github.heavyseasmc.mod.world.liner;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 前桅瞭望台那几件的右键（C3 第二轮 · c3-deck，ADR-0086 §2 第 14–15 条）：空心桅杆的门开关 · 小警钟只响一声。
 * 躺椅坐上去在 {@link DeckChairSeat}。走哪一条由 {@link LinerProp.Rules#use} 定。
 */
final class OpenDeck {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /**
     * 小警钟的声音：游戏自带的钟声（开航钟也用它，1.0 · 1.0），<b>音高 1.5</b>（高五度：小一号的钟，一听就分得出不是开航钟）、
     * <b>音量 1.5</b>（听得见的距离约 24 格：瞭望台在艏楼甲板上 18 格，甲板上、驾驶台上听得见，船尾那一头听不见）。
     */
    static final float ALARM_VOLUME = 1.5f;
    static final float ALARM_PITCH = 1.5f;

    private OpenDeck() {
    }

    /**
     * 空心桅杆的门（{@link LinerProp.Use#DOOR}）：门那两格一起开 / 关（游戏自带的铁门的声音）；竖井那一格右键没有反应。
     */
    static ActionResult toggleDoor(LinerProp block, BlockState state, World world, BlockPos pos) {
        if (state.get(LinerProp.MAST) == LinerProp.Part.SHAFT) {
            return ActionResult.PASS;
        }
        if (!world.isClient) {
            boolean open = !state.get(LinerProp.OPEN);
            for (BlockPos p : block.piece(state, pos)) {
                BlockState s = world.getBlockState(p);
                if (s.isOf(block)) {
                    world.setBlockState(p, s.with(LinerProp.OPEN, open), Block.NOTIFY_ALL);
                }
            }
            world.playSound(null, pos, open ? SoundEvents.BLOCK_IRON_DOOR_OPEN : SoundEvents.BLOCK_IRON_DOOR_CLOSE, SoundCategory.BLOCKS, 1f, 1f);
            world.emitGameEvent(null, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
        }
        return ActionResult.success(world.isClient);
    }

    /**
     * 小警钟（{@link LinerProp.Use#ALARM_BELL}）：只响一声，<b>不开局</b> —— 不走 {@link DrillSkiff#ringBell}（那一条认「演习艇旁那一口、
     * 坐在艇里的人敲」，开阵容面板）。每一下打一行日志，与开航钟那几行（「开航钟：…」）分得开。
     */
    static ActionResult ringAlarm(World world, BlockPos pos, PlayerEntity player) {
        if (!world.isClient) {
            world.playSound(null, pos, SoundEvents.BLOCK_BELL_USE, SoundCategory.BLOCKS, ALARM_VOLUME, ALARM_PITCH);
            world.emitGameEvent(player, GameEvent.BLOCK_CHANGE, pos);
            LOGGER.info("警钟：{} 敲了 {} · 只响", player.getGameProfile().getName(), pos.toShortString());
        }
        return ActionResult.success(world.isClient);
    }
}
