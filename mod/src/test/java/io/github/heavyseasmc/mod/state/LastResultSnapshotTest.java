package io.github.heavyseasmc.mod.state;

import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 「上一局」快照带着那一局被移出游戏的人（2026-10-13 第二轮验收 98：上一局里死者那两行不是灰的 —— 快照原先不带这一项，
 * 计分面板只好在回看时一律不淡化）。走一遍真实的写法与读法。
 */
final class LastResultSnapshotTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    @DisplayName("快照里被移出的人上线之后原样读回来")
    void removedSurvivesTheWire() {
        ExperienceView.Result last = new ExperienceView.Result("m1", 6, "LANDED", 6,
                List.of(new ExperienceView.ScoreSummary("mate", 1, 2, 0, 0),
                        new ExperienceView.ScoreSummary("kid", 0, 0, 0, 0)),
                "mate", List.of(), 0, List.of("kid", "barmaid"));
        ExperienceView view = new ExperienceView("", "", List.of(), List.of(), List.of(), 0, List.of(),
                ExperienceView.BattleReport.NONE, List.of(), List.of(), last, 0);
        RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        try {
            view.write(buf, true);
            ExperienceView back = ExperienceView.read(buf, List.of());
            assertEquals(List.of("kid", "barmaid"), back.lastResult().removed());
            assertEquals(last.scores(), back.lastResult().scores(), "正向对照：同一段里的别的项也读对了");
            assertFalse(buf.isReadable(), "读完不剩字节：写与读一一对上");
        } finally {
            buf.release();
        }
    }
}
