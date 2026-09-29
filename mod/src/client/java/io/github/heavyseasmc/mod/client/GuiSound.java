package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;

import java.util.PriorityQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 牌的声音：落桌一声闷响 · 翻开一声纸响 · 「顿」一下轻响（ADR-0037 §7.1 第 3 条「落牌加重」的第三件）。
 *
 * <p>重量感来自落点的三件事（压一下 · 影子收紧 · 一声闷响），不是更慢的曲线（样张页的 Inscryption 一节）。
 * 前两件画在各面上（{@link GuiLanguage#dealScale} · {@code GameScreen#drawCardShadow}），这一件在这里。
 *
 * <p><b>按落位的时刻排好，到点才响</b>：各面只在「开始发 / 开始翻 / 顿」那一下报一次，
 * 每张牌什么时候落定由 {@link GuiLanguage} 的同一套时长算出来 —— 声音与画面同源，不会各走各的。
 * 界面收起时没响的全部作废：牌已经不在桌上了，响出来就是鬼声。
 *
 * <p>全部是 Minecraft 自带的声音，不引外部素材（授权边界，ADR-0038）。只在本机播，不经服务端 —— 别人听不见你翻手牌。
 */
final class GuiSound {

    /** 一次最多排几声：八张牌逐张落下是「嗒嗒嗒」，再多就成了一阵杂音。 */
    private static final int MAX_LANDINGS = 8;

    private record Cue(long at, SoundEvent sound, float pitch, float volume) {
    }

    private static final PriorityQueue<Cue> QUEUE = new PriorityQueue<>((a, b) -> Long.compare(a.at(), b.at()));
    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);
    /** 这一面里真的响了几声：收起时报一行 —— 声音截不到图，「响了」与「从没响过」只有这一行分得开。 */
    private static int lands;
    private static int flips;
    private static int snaps;

    private GuiSound() {
    }

    /** 这一批从 {@code dealtAt} 起「发」了 {@code count} 张：每张落定的那一刻一声闷响，音高逐张略升。 */
    static void dealt(long dealtAt, int count) {
        int n = Math.min(MAX_LANDINGS, Math.max(0, count));
        for (int i = 0; i < n; i++) {
            long at = dealtAt + i * GuiLanguage.DEAL_STAGGER_MS + GuiLanguage.DEAL_LAND_MS;
            QUEUE.add(new Cue(at, SoundEvents.ITEM_BOOK_PUT, 0.95f + 0.03f * i, 0.45f));
        }
    }

    /** 从 {@code at} 起「翻」：到中点换面的那一刻一声纸响。 */
    static void flipped(long at, long flipMs) {
        QUEUE.add(new Cue(at + flipMs / 2, SoundEvents.ITEM_BOOK_PAGE_TURN, 1.0f, 0.7f));
    }

    /** 「顿」：系统替你按下的那一下。 */
    static void snapped(long at) {
        QUEUE.add(new Cue(at, SoundEvents.BLOCK_WOODEN_BUTTON_CLICK_ON, 1.3f, 0.35f));
    }

    /** 每帧调一次：到点的响掉。 */
    static void pump(long now) {
        MinecraftClient client = MinecraftClient.getInstance();
        while (!QUEUE.isEmpty() && QUEUE.peek().at() <= now) {
            Cue cue = QUEUE.poll();
            if (client != null && client.getSoundManager() != null) {
                client.getSoundManager().play(PositionedSoundInstance.master(cue.sound(), cue.pitch(), cue.volume()));
                if (cue.sound() == SoundEvents.ITEM_BOOK_PUT) {
                    lands++;
                } else if (cue.sound() == SoundEvents.ITEM_BOOK_PAGE_TURN) {
                    flips++;
                } else {
                    snaps++;
                }
            }
        }
    }

    /** 界面收起：没响的全部作废。 */
    static void cancel() {
        QUEUE.clear();
        if (lands + flips + snaps > 0) {
            // 与语言无关的一行：回归靠它判「牌声真的响了」。
            LOGGER.info("牌声：落桌 {} · 翻 {} · 顿 {}", lands, flips, snaps);
        }
        lands = 0;
        flips = 0;
        snaps = 0;
    }
}
