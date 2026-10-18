package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 海图桌的浮字与小铜船（ADR-0086 §2 第 5 条）：几何从 jar 里那份读得出；模型坐标换到世界的那一套与方块那一侧
 * （{@link LinerProp.Rules#offset} 摆的六格）是同一套；四个朝向下九档船底都落在桌面上；转动把船头与斜面法向转对。
 */
class ChartTableTest {

    private static final List<Direction> HORIZONTAL = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
    private static final List<LinerProp.Part> CELLS = List.of(LinerProp.Part.FRONT_WEST, LinerProp.Part.FRONT_MID,
            LinerProp.Part.FRONT_EAST, LinerProp.Part.BACK_WEST, LinerProp.Part.BACK_MID, LinerProp.Part.BACK_EAST);

    private static ChartTable.Geometry geometry() throws IOException {
        try (InputStream in = ChartTableTest.class.getResourceAsStream(ChartTable.GEOMETRY)) {
            assertNotNull(in, "jar 里没有 " + ChartTable.GEOMETRY + " —— 没在测，不是通过");
            return ChartTable.Geometry.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void geometryHasNineStepsAndTheTextAboveTheTable() throws IOException {
        ChartTable.Geometry g = geometry();
        assertEquals(DrillSkiff.SEATS + 1, g.ship().size());
        assertTrue(g.text().y() > 26, "浮字要高过斜面后沿（26 像素）：" + g.text());
    }

    @Test
    void modelCellsLandOnTheCellsTheBlockSidePlaces() {
        // 每一块那一格的模型中心（x 0–48 由西往东一格 16、z 0–16 前排 / 16–32 后排）换到世界，必须正好落在 Rules.offset 摆的那一格
        BlockPos anchor = new BlockPos(100, 40, -7);
        for (Direction f : HORIZONTAL) {
            for (LinerProp.Part p : CELLS) {
                BlockPos want = LinerProp.Rules.offset(anchor, LinerProp.Part.FRONT_EAST, p, f);
                Vector3f centre = new Vector3f(p.x * 16 + 8, 8, p.z * 16 + 8);
                Vec3d got = ChartTable.toWorld(anchor, f, centre);
                assertEquals(want, BlockPos.ofFloored(got), f + " · " + p);
            }
        }
    }

    @Test
    void everyShipStepSitsOnTheTableTop() throws IOException {
        ChartTable.Geometry g = geometry();
        BlockPos anchor = new BlockPos(0, 40, 0);
        for (Direction f : HORIZONTAL) {
            java.util.Set<BlockPos> cells = new java.util.HashSet<>();
            CELLS.forEach(p -> cells.add(LinerProp.Rules.offset(anchor, LinerProp.Part.FRONT_EAST, p, f)));
            for (int k = 0; k < g.ship().size(); k++) {
                Vec3d at = ChartTable.toWorld(anchor, f, g.ship().get(k).bottom());
                assertTrue(cells.contains(BlockPos.ofFloored(at.x, anchor.getY(), at.z)), f + " 第 " + k + " 档不在桌子那六格上：" + at);
                assertTrue(at.y - anchor.getY() > 0.9 && at.y - anchor.getY() < 1.63, f + " 第 " + k + " 档的高度不在斜面上：" + at.y);
            }
        }
    }

    @Test
    void rotationTurnsTheModelBowAndUpOntoTheSlope() throws IOException {
        ChartTable.Geometry g = geometry();
        for (Direction f : HORIZONTAL) {
            for (ChartTable.Pose p : g.ship()) {
                Quaternionf q = ChartTable.shipRotation(f, p);
                float[] b = ChartTable.turn(f, p.bow().x(), p.bow().z());
                float[] n = ChartTable.turn(f, p.normal().x(), p.normal().z());
                Vector3f bow = new Vector3f(b[0], p.bow().y(), b[1]).normalize();
                Vector3f up = new Vector3f(n[0], p.normal().y(), n[1]).normalize();
                // 画物品展示时先绕 y 转半圈：模型的 −x 才是船头
                assertTrue(q.transform(new Vector3f(-1, 0, 0)).distance(bow) < 1e-3, f + " 船头没转对");
                assertTrue(q.transform(new Vector3f(0, 1, 0)).distance(up) < 1e-3, f + " 船底没贴着斜面");
                assertTrue(up.y > 0.9f, "斜面法向朝上");
            }
        }
    }
}
