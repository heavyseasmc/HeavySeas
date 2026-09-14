package io.github.heavyseasmc.engine.data;

import io.github.heavyseasmc.engine.navigation.NavigationCard;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 测试里读真实 {@code data/} 的唯一入口。
 *
 * <h2>为什么跳过必须自己打印</h2>
 * {@code data/navigation/default.json} 在 O1 定案前不入库（`.gitignore` 里有它），
 * 所以 CI 上必然缺席，依赖它的测试<b>只能跳过</b>。而 2026-09-14 实测：
 *
 * <ul>
 *   <li>Gradle 只打印一行 {@code SKIPPED}，<b>不打印</b> {@code Assumptions} 的理由；</li>
 *   <li>{@code afterTest} 监听器拿到的 {@code TestResult} 里<b>一个异常都没有</b>，
 *       理由在 Gradle 这一层根本不存在；</li>
 *   <li>把 {@code testLogging.showStandardStreams} 打开之后，测试自己 {@code println}
 *       的内容才会出现在构建日志里。</li>
 * </ul>
 *
 * 所以这里自己往标准输出写一行，{@code engine/build.gradle} 相应打开了 showStandardStreams。
 * 「静默跳过」与「跑过了」在日志上长得一样，那正是本仓库已经吃过三次的假绿。
 *
 * <p>❗<b>两条路都打印</b>：找到了也说一声，说明那份数据真的被读了。
 * 只在缺席时打印的话，「加载器其实没在扫」照样是静默的。
 */
public final class LocalData {

    public static final String NAVIGATION = "navigation/default.json";

    private LocalData() {
    }

    public static DataDir dir() {
        return DataDir.fromEnvironment();
    }

    public static RosterData roster() {
        return RosterLoader.load(dir().file("roster/default.json"));
    }

    public static Set<String> provisionIds() {
        return ProvisionLoader.loadIds(dir().file("provisions/default.json"));
    }

    /** 真实航海牌堆；文件不在就<b>出声跳过</b>，不是失败，也不是静默。 */
    public static List<NavigationCard> navigationDeckOrSkip() {
        DataDir data = dir();
        Optional<Path> file = data.optionalFile(NAVIGATION);
        if (file.isEmpty()) {
            String path = data.root().resolve(NAVIGATION).toAbsolutePath().toString();
            System.out.println("⚠ 跳过：本地没有 " + path);
            System.out.println("  这是预期之内 —— 航海牌数据在 O1 定案前不入库，CI 上必然缺席。");
            System.out.println("  本地要跑：用 docs/tools/export_datapack.py 导出后重跑。");
            Assumptions.abort("缺 " + NAVIGATION + "（O1 定案前不入库）");
        }
        List<NavigationCard> deck = NavigationLoader.load(file.orElseThrow(), roster().ids(), provisionIds());
        System.out.println("✓ 实跑：读到 " + deck.size() + " 张真实航海牌（" + file.orElseThrow() + "）");
        return deck;
    }
}
