package io.github.heavyseasmc.mod.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装饰件贴图与 {@link HudPart} 那张表对得上：每件 × 每个主题 × 每一倍都在，尺寸 = (盒子 + 2 × margin) × 倍数。
 *
 * <p>两边不同源：表在 Java 里，尺寸是管线从样张 CSS 渲出来的 PNG 自己的头。表里少写一件、管线改了 margin、
 * 某一倍没烘出来，都在这里红 —— 否则屏幕上只是某一件的阴影被裁掉一截，或者整件缩歪，不报错。
 */
class HudPartTest {

    /** 测试的工作目录是 {@code mod/}。 */
    private static final Path ASSETS = Path.of("src", "main", "resources", "assets", "heavyseas");

    @Test
    @DisplayName("每一件装饰件的每一倍贴图都在，尺寸与表一致")
    void bakedPartsMatchTheTable() throws IOException {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (HudPart part : HudPart.values()) {
            for (String theme : part.themed() ? new String[]{"dark", "light"} : new String[]{"common"}) {
                for (int bake = 1; bake <= part.maxBake(); bake++) {
                    Path png = ASSETS.resolve(part.path(theme, bake));
                    if (!Files.isRegularFile(png)) {
                        problems.add("缺 " + png);
                        continue;
                    }
                    int[] size = pngSize(png);
                    checked++;
                    if (size[0] != part.texW(bake) || size[1] != part.texH(bake)) {
                        problems.add(png.getFileName() + "（" + theme + "）是 " + size[0] + "×" + size[1]
                                + "，表上应为 " + part.texW(bake) + "×" + part.texH(bake));
                    }
                }
            }
        }
        assertTrue(checked >= HudPart.values().length,
                "只核对了 " + checked + " 张 —— 没在扫，不是都对（" + ASSETS.toAbsolutePath() + "）");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /** Minecraft 资源路径只认这些字符（{@code Identifier.isPathCharacterValid}）。 */
    private static final java.util.regex.Pattern VALID_PATH = java.util.regex.Pattern.compile("[a-z0-9/._-]+");

    /**
     * 路径是合法的资源标识：2026-09-30 第一版文件名带 {@code @}，上面那条尺寸判据全绿（文件都在、尺寸都对），
     * 而客户端开局第一帧就崩在 {@code InvalidIdentifierException} —— 「文件在」与「游戏认得它」是两个问题。
     */
    @Test
    @DisplayName("每一件的资源路径只含 Minecraft 认得的字符")
    void pathsAreValidIdentifiers() {
        List<String> problems = new ArrayList<>();
        for (HudPart part : HudPart.values()) {
            for (int bake : HudPart.BAKES) {
                String path = part.path("dark", bake);
                if (!VALID_PATH.matcher(path).matches()) {
                    problems.add(path);
                }
            }
        }
        assertTrue(problems.isEmpty(), "资源路径里有 Minecraft 不认的字符：\n" + String.join("\n", problems));
    }

    @Test
    @DisplayName("三段拼的件，两头加起来比整件短：中间那一段不会是负的")
    void slicedPartsLeaveAMiddle() {
        for (HudPart part : HudPart.values()) {
            int along = switch (part.slice()) {
                case H3 -> part.w();
                case V3 -> part.h();
                case NINE -> Math.min(part.w(), part.h());
                case FIXED -> Integer.MAX_VALUE;
            };
            assertTrue(2 * part.cap() < along, part + " 两头 " + part.cap() + " × 2 不小于整件 " + along);
        }
    }

    /**
     * 按钮一族用到的最矮那一种（稿子像素）：板外那排小签（规则 · 聊天 · 返回决定）= 13 号字的一行 17 + 上下各 3。
     * 其余是 49（底栏确认）· 46（设置的签）· 36（设置的控件）· 26（键帽高的小钮）。
     */
    static final int SHORTEST_BUTTON = 22;

    /** 切角牌与铜框（带铆钉）：会在好几种高度上画的那几件。 */
    static final List<HudPart> PLATES = List.of(HudPart.BTN, HudPart.BTN_FOCUS, HudPart.BTN_FIRM, HudPart.FRAME);

    /**
     * 按钮一族必须九宫格拼，且最矮那一种也放得下上下两个角：ADR-0100 第二期的风险 ①——横向三段时竖向是整张压的，
     * 切角压扁、铆钉压成椭圆；角比半个按钮还高时 {@code hudPart} 只好把角夹扁，结果一样。
     */
    @Test
    @DisplayName("切角牌与铜框按九宫格拼，最矮的按钮也放得下上下两个角（铆钉不压成椭圆）")
    void platesKeepTheirCornersAtEveryHeight() {
        List<String> problems = new ArrayList<>();
        for (HudPart part : PLATES) {
            if (part.slice() != HudPart.Slice.NINE) {
                problems.add(part + " 是 " + part.slice() + "：竖向会整张压，切角与铆钉跟着变形");
            }
            if (2 * part.cap() > SHORTEST_BUTTON) {
                problems.add(part + " 两角 " + part.cap() + " × 2 高过最矮的按钮 " + SHORTEST_BUTTON + "：画出来角被夹扁");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("挑倍数：不小于 k 的最小一份，1 到 3 之间")
    void bakePicksTheSmallestThatIsNotBlurry() {
        assertTrue(HudPart.bakeFor(1.0) == 1, "界面尺寸 3 下正好 1 倍：与样张逐像素相同");
        assertTrue(HudPart.bakeFor(2.0 / 3) == 1);
        assertTrue(HudPart.bakeFor(4.0 / 3) == 2);
        assertTrue(HudPart.bakeFor(3.0) == 3);
        assertTrue(HudPart.bakeFor(4.0) == 3);
    }

    private static int[] pngSize(Path png) throws IOException {
        try (InputStream in = Files.newInputStream(png); DataInputStream data = new DataInputStream(in)) {
            byte[] head = new byte[16];
            data.readFully(head);
            return new int[]{data.readInt(), data.readInt()};
        }
    }
}
