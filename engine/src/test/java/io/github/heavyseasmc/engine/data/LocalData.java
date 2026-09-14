package io.github.heavyseasmc.engine.data;

import io.github.heavyseasmc.engine.navigation.NavigationCard;

import java.util.List;
import java.util.Set;

/**
 * 测试里读真实 {@code data/} 的唯一入口。
 *
 * <h2>三份数据现在都是硬要求</h2>
 * 航海牌曾经<b>允许缺席</b>：O1 定案前它不入库，CI 上必然没有，所以依赖它的测试要跳过。
 * O1 定案选了 A 版之后那份数据已经落库，缺席不再是合法状态 —— 于是跳过那条路被删掉了。
 *
 * <p>❗<b>删它不是因为用不上，是因为它再也不会被触发。</b> 一条永远走不到的分支
 * 与一道只见过绿灯的闸门是同一种东西：它给人「这里防着呢」的错觉，
 * 而真出事时谁也不知道它是不是还能响。要么让它有机会红，要么删掉。
 *
 * <p>现在三份数据一律走 {@link DataDir#file}：缺任何一份都是<b>失败</b>，
 * 报出解析到的绝对路径。CI 与本地在这件事上没有差别了。
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

    /** 真实航海牌堆。文件不在就是失败 —— 它已经落库，缺席只可能是配错了路径。 */
    public static List<NavigationCard> navigationDeck() {
        return NavigationLoader.load(dir().file(NAVIGATION), roster().ids(), provisionIds());
    }
}
