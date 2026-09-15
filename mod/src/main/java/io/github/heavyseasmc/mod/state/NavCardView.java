package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.Selector;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 客户端画一张航海牌要知道的全部东西。
 *
 * <h2>为什么发内容而不是发 id</h2>
 * 数值数据以内置数据包送达，只在服务端的资源管理器里（ADR-0015）；连着专用服务端的客户端手里没有那份 json。
 * 只发 id 的话，客户端就得去自己 jar 里找同名文件 —— 服务端换了一套配平，屏幕上画的就是另一张牌，而且不报错。
 * 所以把牌上印的内容一起发，一张几十个字节。
 *
 * @param id             牌的 id。只进日志，不上屏幕（它不是给人读的）
 * @param gull           海鸥增减：-1 / 0 / +1
 * @param overboard      落海点名
 * @param thirst         口渴点名（牌面上的名单，不含划船与战斗图示）
 * @param thirstRowers   划过船的人口渴
 * @param thirstFighters 打过架的人口渴
 */
public record NavCardView(String id, int gull, Names overboard, Names thirst,
                          boolean thirstRowers, boolean thirstFighters) {

    public NavCardView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(overboard, "overboard");
        Objects.requireNonNull(thirst, "thirst");
    }

    /** 点名的五种写法，与引擎的 {@link Selector} 一一对应。 */
    public enum Mode {
        EVERYONE,
        NOBODY,
        ONLY,
        EXCEPT,
        CONDITIONAL
    }

    /**
     * 一栏点名。
     *
     * @param mode       哪种写法
     * @param characters {@code ONLY} / {@code EXCEPT} 的名单（角色 id，按 id 排）；其余写法为空
     * @param condition  {@code CONDITIONAL} 的条件名（如 {@code used_rum}）；其余写法为空串
     */
    public record Names(Mode mode, List<String> characters, String condition) {

        public Names {
            Objects.requireNonNull(mode, "mode");
            characters = List.copyOf(Objects.requireNonNull(characters, "characters"));
            Objects.requireNonNull(condition, "condition");
        }
    }

    public static NavCardView of(NavigationCard card) {
        return new NavCardView(card.id(), card.gull(), names(card.overboard()), names(card.thirst()),
                card.thirstRowers(), card.thirstFighters());
    }

    /** switch 覆盖 sealed 接口的全部实现：引擎哪天加第六种写法，这里编译期就红。 */
    private static Names names(Selector selector) {
        return switch (selector) {
            case Selector.Everyone everyone -> new Names(Mode.EVERYONE, List.of(), "");
            case Selector.Nobody nobody -> new Names(Mode.NOBODY, List.of(), "");
            case Selector.Only only -> new Names(Mode.ONLY, ids(only.characters()), "");
            case Selector.Except except -> new Names(Mode.EXCEPT, ids(except.characters()), "");
            case Selector.Conditional conditional -> new Names(Mode.CONDITIONAL, List.of(), conditional.condition());
        };
    }

    /** 引擎里的名单是 Set、没有次序：按 id 排一次，同一张牌每次发出去的字节才相同。显示次序由客户端按座位排。 */
    private static List<String> ids(Set<CharacterId> characters) {
        return characters.stream().map(CharacterId::value).sorted().toList();
    }

    public void write(PacketByteBuf buf) {
        buf.writeString(id);
        buf.writeVarInt(gull);
        writeNames(buf, overboard);
        writeNames(buf, thirst);
        buf.writeBoolean(thirstRowers);
        buf.writeBoolean(thirstFighters);
    }

    public static NavCardView read(PacketByteBuf buf) {
        String id = buf.readString();
        int gull = buf.readVarInt();
        Names overboard = readNames(buf);
        Names thirst = readNames(buf);
        boolean rowers = buf.readBoolean();
        boolean fighters = buf.readBoolean();
        return new NavCardView(id, gull, overboard, thirst, rowers, fighters);
    }

    private static void writeNames(PacketByteBuf buf, Names names) {
        buf.writeEnumConstant(names.mode());
        buf.writeVarInt(names.characters().size());
        for (String id : names.characters()) {
            buf.writeString(id);
        }
        buf.writeString(names.condition());
    }

    private static Names readNames(PacketByteBuf buf) {
        Mode mode = buf.readEnumConstant(Mode.class);
        int count = buf.readVarInt();
        List<String> characters = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            characters.add(buf.readString());
        }
        return new Names(mode, characters, buf.readString());
    }
}
