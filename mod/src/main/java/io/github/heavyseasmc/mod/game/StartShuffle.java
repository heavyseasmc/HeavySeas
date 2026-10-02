package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.Affinities;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.navigation.NavigationDeck;
import io.github.heavyseasmc.engine.play.Table;
import io.github.heavyseasmc.engine.weather.WeatherDeck;
import io.github.heavyseasmc.mod.data.GameData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * 开局要洗的几样，全从<b>同一个种子</b>派生（ADR-0060，{@code /seas debug next seed}）：
 * 真人分到哪几个座位 · 航海牌堆 · 天候牌堆 · 物资牌堆 · 爱恨 · 对局中途用的随机源。
 *
 * <p>纯函数，不碰 Minecraft —— 单测直接核「同一个种子开两局，第一天天候、三副牌的顺序、爱恨完全相同」。
 * 没指定种子的一局照旧随机：种子由世界的随机源现取，而且<b>不进日志</b>（知道种子就等于知道全部暗牌，
 * 而开服的人往往也是玩家 —— 与「日志里不打谁爱谁」同一条）。
 *
 * <p>❗取随机数的<b>次序</b>是这份约定的一部分：调换其中两步，同一个种子就洗出另一局。
 */
public final class StartShuffle {

    private StartShuffle() {
    }

    /**
     * @param humanSeats 没被替身预留的座位，洗过之后的顺序（真人按这个顺序入座）
     * @param table      三副洗好的牌
     * @param affinities 爱恨
     * @param gameSeed   对局中途那个随机源的种子
     */
    public record Result(List<CharacterId> humanSeats, Table table, Affinities affinities, long gameSeed) {

        public Result {
            humanSeats = List.copyOf(humanSeats);
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(affinities, "affinities");
        }
    }

    public static Result shuffle(GameData data, Roster roster, List<CharacterId> open, long seed) {
        Random seeds = new Random(seed);
        List<CharacterId> shuffled = new ArrayList<>(open);
        Collections.shuffle(shuffled, new Random(seeds.nextLong()));
        // 实参从左往右求值：航海 → 天候 → 物资，次序固定
        Table table = new Table(
                new NavigationDeck(data.navigation(), new Random(seeds.nextLong())),
                data.provisions(),
                new WeatherDeck(data.weather(), new Random(seeds.nextLong())),
                new Random(seeds.nextLong()));
        Affinities affinities = Affinities.random(roster, new Random(seeds.nextLong()));
        return new Result(shuffled, table, affinities, seeds.nextLong());
    }
}
