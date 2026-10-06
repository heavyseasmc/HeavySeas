package io.github.heavyseasmc.mod.world.liner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 轮廓（{@code LinerLooks} 手写的盒子，选中框与碰撞用）与模板文件里的元件（{@code liner_decor.py --write} 写的，游戏画的）对得上
 * （ADR-0069 §2 ②：贴附件第一组一下子加了 30 块模板，轮廓是另一处手写的，两边各改各的就会分家 —— 选中框套不住门套、
 * 或者空气里多出一块能撞上的东西，游戏不报错）。判据的两半来自两个来源：模板文件 · Java 里的盒子。
 */
final class LinerLooksTest {

    private static final Path TEMPLATES = Path.of("src", "main", "resources", "assets", "heavyseas", "models", "block", "liner", "template");
    /** 整块的几种：轮廓就是满格（大框前出 1 像素的框条按设计不进轮廓），不在这里对。 */
    private static final Set<String> FULL = Set.of("cube", "cube_front", "floor");
    private static final double EPS = 0.01;

    @Test
    void everyTemplateFileHasAnOutlineAndTheOutlineWrapsItsElements() throws IOException {
        List<String> problems = new ArrayList<>();
        int templates = 0;
        int elements = 0;
        try (Stream<Path> files = Files.list(TEMPLATES)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String name = f.getFileName().toString().replace(".json", "");
                List<double[]> boxes = LinerLooks.boxesOf(name);
                if (boxes == null) {
                    problems.add(name + "：模板文件在，LinerLooks 里没有它的轮廓（游戏里放下去就抛）");
                    continue;
                }
                templates++;
                if (FULL.contains(name) || name.startsWith("frame_")) {
                    continue;
                }
                List<double[]> els = elements(f);
                elements += els.size();
                problems.addAll(problemsOf(name, els, boxes));
            }
        }
        // 正向对照：确实扫到了东西（「0 命中」与「没在扫」要分得开）
        assertTrue(templates >= 60 && elements >= 100, "没在查：只读到 " + templates + " 块模板、" + elements + " 个元件");
        assertEquals(List.of(), problems);
    }

    /**
     * 红测（判据本身）：拿真的模板文件、往轮廓里各装一个缺陷 —— 漏掉门套墩旁那截踢脚的轮廓、在空气里多一块轮廓 ——
     * 两条都要红，而且只红在装进去的那一处；原样的轮廓不红。
     */
    @Test
    void theJudgeRedsOnAMissingAndOnAPhantomOutlineBox() throws IOException {
        String name = "casing_jamb_left_skirting";
        List<double[]> els = elements(TEMPLATES.resolve(name + ".json"));
        List<double[]> good = LinerLooks.boxesOf(name);
        assertEquals(List.of(), problemsOf(name, els, good), "原样的轮廓不该红");
        List<double[]> missing = good.stream().filter(b -> b[0] > 0).toList();             // 去掉两截踢脚（从 x 0 起的那两块）
        List<String> red = problemsOf(name, els, missing);
        assertTrue(!red.isEmpty() && red.stream().allMatch(p -> p.contains("在轮廓外") && p.contains("(0.0, ")),
                "漏掉踢脚那截轮廓：应当只红在 x 从 0 起的踢脚元件上，实际 " + red);
        List<double[]> phantom = new ArrayList<>(good);
        phantom.add(new double[]{2, 10, 0, 6, 14, 1});                                       // 墙前空着的那一块
        red = problemsOf(name, els, phantom);
        assertEquals(1, red.size(), "多一块空气里的轮廓：应当只红一条，实际 " + red);
        assertTrue(red.get(0).contains("中心不在任何元件里") && red.get(0).contains("(2.0, 10.0"), red.get(0));
    }

    static List<String> problemsOf(String name, List<double[]> els, List<double[]> boxes) {
        List<String> problems = new ArrayList<>();
        for (double[] e : els) {
            if (!covered(e, boxes)) {
                problems.add(name + "：元件 " + fmt(e) + " 有一部分在轮廓外（选中框套不住它）");
            }
        }
        for (double[] b : boxes) {
            double[] c = {(b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2};
            if (els.stream().noneMatch(e -> inside(c, e))) {
                problems.add(name + "：轮廓盒子 " + fmt(b) + " 的中心不在任何元件里（空气里多出一块能撞上的东西）");
            }
        }
        return problems;
    }

    /** 元件盒子往里缩一点，八个角与中心都落在某个轮廓盒子里。 */
    static boolean covered(double[] e, List<double[]> boxes) {
        double[] lo = {e[0] + EPS, e[1] + EPS, e[2] + EPS};
        double[] hi = {e[3] - EPS, e[4] - EPS, e[5] - EPS};
        List<double[]> pts = new ArrayList<>();
        for (int m = 0; m < 8; m++) {
            pts.add(new double[]{(m & 1) == 0 ? lo[0] : hi[0], (m & 2) == 0 ? lo[1] : hi[1], (m & 4) == 0 ? lo[2] : hi[2]});
        }
        pts.add(new double[]{(e[0] + e[3]) / 2, (e[1] + e[4]) / 2, (e[2] + e[5]) / 2});
        return pts.stream().allMatch(p -> boxes.stream().anyMatch(b -> inside(p, b)));
    }

    static boolean inside(double[] p, double[] b) {
        return p[0] >= b[0] - EPS && p[0] <= b[3] + EPS && p[1] >= b[1] - EPS && p[1] <= b[4] + EPS
                && p[2] >= b[2] - EPS && p[2] <= b[5] + EPS;
    }

    private static List<double[]> elements(Path f) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
        List<double[]> out = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("elements")) {
            JsonArray from = el.getAsJsonObject().getAsJsonArray("from");
            JsonArray to = el.getAsJsonObject().getAsJsonArray("to");
            double[] b = new double[6];
            for (int i = 0; i < 3; i++) {
                b[i] = Math.min(from.get(i).getAsDouble(), to.get(i).getAsDouble());
                b[i + 3] = Math.max(from.get(i).getAsDouble(), to.get(i).getAsDouble());
            }
            out.add(b);
        }
        return out;
    }

    private static String fmt(double[] b) {
        return String.format("(%s, %s, %s)–(%s, %s, %s)", b[0], b[1], b[2], b[3], b[4], b[5]);
    }
}
