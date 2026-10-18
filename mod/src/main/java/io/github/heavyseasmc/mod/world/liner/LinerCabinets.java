package io.github.heavyseasmc.mod.world.liner;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/** 能开合的柜子的方块实体类型（ADR-0109 §2 第二版）：六件柜子共用一种，登记在灯与家具之后。 */
public final class LinerCabinets {

    public static BlockEntityType<LinerCabinetEntity> TYPE;

    private LinerCabinets() {
    }

    /** 能开合的那几件（{@link LinerProp.Rules#opens}），按登记顺序。 */
    public static Block[] blocks() {
        return LinerProps.all().values().stream().filter(b -> LinerProp.Rules.opens(b.spec().kind())).toArray(Block[]::new);
    }

    static void register() {
        TYPE = Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of(HeavySeasMod.MOD_ID, "liner_cabinet"),
                BlockEntityType.Builder.create(LinerCabinetEntity::new, blocks()).build(null));
    }
}
