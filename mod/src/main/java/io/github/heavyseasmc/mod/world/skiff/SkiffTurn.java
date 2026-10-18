package io.github.heavyseasmc.mod.world.skiff;

import net.minecraft.util.StringIdentifiable;

import java.util.Locale;

/**
 * 舵往哪边偏（ADR-0057 §4）：左 = 面朝船头时的左手边。
 *
 * <p>单独一个文件而不是放进 {@link SkiffBlocks}：{@link SkiffProps.Rules} 要用它，而单测一碰 {@code SkiffBlocks}
 * 就会去造方块 —— 不起游戏的单测里那一步会崩。
 */
public enum SkiffTurn implements StringIdentifiable {
    NONE, LEFT, RIGHT;

    @Override
    public String asString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
