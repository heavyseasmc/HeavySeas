package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.card.CardFace;
import io.github.heavyseasmc.mod.card.CardFaces;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 牌的**目录**：哪张牌属于哪一类、牌堆里一共几张、角标上印什么数。进服时发一次。
 *
 * <p>为什么要它：这些是<b>牌自己的属性</b>，不是某一局的状态，服务端的数据包说了算
 * （`data/provisions/<id>.json`，数据包可以换）。客户端的提示签要写「类别 · 共几张」，
 * 牌面的角标要印体力 / 分数 / 生存（ADR-0039 §7.3）—— 在这个包之前，这些根本拿不到。
 *
 * <p>❗不从客户端那边猜：把数写死在客户端等于把数据包的规则抄了一份，
 * 换数据包时两边就分家了 —— 而分家之后屏幕上照样有数字，只是错的。
 *
 * <p>范围：物资与角色。航海卡的内容随划船 / 舵手两面的 {@code NavCardView} 下发，不走这里；
 * 天候卡的牌面上没有数。
 */
public record CatalogS2C(List<Provisions> provisions, List<Characters> characters) implements CustomPayload {

    /** 一枚角标：图标名 + 印的字。 */
    public record Badge(String icon, String text) {
        public static final PacketCodec<ByteBuf, Badge> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, Badge::icon,
                PacketCodecs.STRING, Badge::text,
                Badge::new);

        static Badge of(CardFace.Badge b) {
            return new Badge(b.icon(), b.text());
        }

        public CardFace.Badge face() {
            return new CardFace.Badge(icon, text);
        }
    }

    /** 一张物资牌的目录条目。 */
    public record Provisions(String id, String category, int count, List<Badge> badges) {
        public static final PacketCodec<ByteBuf, Provisions> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, Provisions::id,
                PacketCodecs.STRING, Provisions::category,
                PacketCodecs.VAR_INT, Provisions::count,
                Badge.CODEC.collect(PacketCodecs.toList(4)), Provisions::badges,
                Provisions::new);

        public Provisions {
            badges = List.copyOf(badges);
        }
    }

    /** 一个角色的目录条目：角色卡上的两枚角标。 */
    public record Characters(String id, List<Badge> badges) {
        public static final PacketCodec<ByteBuf, Characters> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, Characters::id,
                Badge.CODEC.collect(PacketCodecs.toList(4)), Characters::badges,
                Characters::new);

        public Characters {
            badges = List.copyOf(badges);
        }
    }

    public static final Id<CatalogS2C> ID = new Id<>(Identifier.of(HeavySeasMod.MOD_ID, "catalog"));
    public static final PacketCodec<RegistryByteBuf, CatalogS2C> CODEC = PacketCodec.tuple(
            Provisions.CODEC.collect(PacketCodecs.toList(64)), CatalogS2C::provisions,
            Characters.CODEC.collect(PacketCodecs.toList(32)), CatalogS2C::characters,
            CatalogS2C::new);

    public CatalogS2C {
        provisions = List.copyOf(provisions);
        characters = List.copyOf(characters);
    }

    /** 从正式数据构造。类别名用小写的枚举名 —— 客户端拿它拼 {@code heavyseas.category.<类别>}。 */
    public static CatalogS2C of(List<Provision> cards, List<Survivor> roster) {
        return new CatalogS2C(
                cards.stream()
                        .map(card -> new Provisions(card.id(),
                                card.category().name().toLowerCase(java.util.Locale.ROOT), card.count(),
                                CardFaces.provisionBadges(card).stream().map(Badge::of).toList()))
                        .toList(),
                roster.stream()
                        .map(s -> new Characters(s.id().value(),
                                CardFaces.characterBadges(s.size(), s.survival()).stream().map(Badge::of).toList()))
                        .toList());
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
