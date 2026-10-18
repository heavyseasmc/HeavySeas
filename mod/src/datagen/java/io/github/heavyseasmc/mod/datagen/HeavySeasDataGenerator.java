package io.github.heavyseasmc.mod.datagen;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;

/**
 * 批量生成工具的入口（ADR-0053 §5 · ADR-0056）。只在 {@code ./gradlew :mod:runDatagen} 时运行；
 * 这个源码集不进发给玩家的 jar。
 */
public final class HeavySeasDataGenerator implements DataGeneratorEntrypoint {

    @Override
    public void onInitializeDataGenerator(FabricDataGenerator generator) {
        FabricDataGenerator.Pack pack = generator.createPack();
        pack.addProvider(DecorModels::new);           // 救生艇 · 大邮轮两族装饰方块（ADR-0056 · ADR-0062）
    }
}
