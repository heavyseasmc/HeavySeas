package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 牌的**目录**：哪张牌属于哪一类、牌堆里一共几张。进服时发一次。
 *
 * <p>为什么要它：这些是<b>牌自己的属性</b>，不是某一局的状态，服务端的数据包说了算
 * （`data/provisions/<id>.json`，数据包可以换）。而客户端的提示签要写「类别 · 共几张」——
 * 在这个包之前，那两栏根本拿不到数据，只能空着（ADR-0037 §7.12 记的欠项）。
 *
 * <p>❗不从客户端那边猜：把张数写死在客户端等于把数据包的规则抄了一份，
 * 换数据包时两边就分家了 —— 而分家之后屏幕上照样有数字，只是错的。
 *
 * <p>范围：这一版**只带物资**。航海卡那一半（落海名单与口渴排）要等牌面去字那一刀一起定 ——
 * 现在发过去也没有人读，属于提前处理（ENGINEERING Phase 3）。
 */
public record CatalogS2C(List<Provisions> provisions) implements CustomPayload {

    /** 一张物资牌的目录条目。 */
    public record Provisions(String id, String category, int count) {
        public static final PacketCodec<ByteBuf, Provisions> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, Provisions::id,
                PacketCodecs.STRING, Provisions::category,
                PacketCodecs.VAR_INT, Provisions::count,
                Provisions::new);
    }

    public static final Id<CatalogS2C> ID = new Id<>(Identifier.of(HeavySeasMod.MOD_ID, "catalog"));
    public static final PacketCodec<RegistryByteBuf, CatalogS2C> CODEC = PacketCodec.tuple(
            Provisions.CODEC.collect(PacketCodecs.toList(64)), CatalogS2C::provisions,
            CatalogS2C::new);

    public CatalogS2C {
        provisions = List.copyOf(provisions);
    }

    /** 从正式物资数据构造。类别名用小写的枚举名 —— 客户端拿它拼 {@code heavyseas.category.<类别>}。 */
    public static CatalogS2C of(List<Provision> cards) {
        return new CatalogS2C(cards.stream()
                .map(card -> new Provisions(card.id(),
                        card.category().name().toLowerCase(java.util.Locale.ROOT), card.count()))
                .toList());
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
