package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Fight;

import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * 一个座位怎么做决定 —— 一局里每一个要人拿主意的地方，这里都有一个方法。
 *
 * <h2>三条约定</h2>
 * <ol>
 *   <li><b>只拿得到 {@link SeatView}</b>：看不见的东西（别人的手牌、别人的爱恨、牌堆次序）根本不在参数里。</li>
 *   <li><b>只从给的选项里挑</b>：合法选项由引擎算好（{@link Legal}）。交回一个不在里面的，驱动者退回默认那一项并记一笔
 *       （{@link SeatDriver#fallbacks()}），不抛 —— 替身卡住比替身做错更糟，卡住的局面没有下一步来推它。</li>
 *   <li><b>随机数只从参数里的 {@code rng} 拿</b>（或策略自己带的、按种子造出来的那一条）：同一个种子必须跑出同一局。</li>
 * </ol>
 *
 * <h2>调用的次序与次数是约定的一部分</h2>
 * 随机席位（{@link RandomSeatPolicy}）照搬搬家前模拟器的随机分布，它在哪一步消费几次随机数是钉死的。
 * 所以驱动者<b>没有可选项也照样问</b>（比如没有酒也问一次「喝不喝」）—— 次序一变，钉死的几千局就全变了。
 *
 * <p>有默认实现的几个方法，是搬家前的模拟器<b>从来不做</b>的事（送牌、打架前喝酒、落海时扔救生圈 / 血饵）：
 * 默认「不做」且不碰随机数，随机席位因此不必知道它们的存在。
 *
 * <h2>不经 {@link SeatDriver}、自己逐个问（模组就是这样）</h2>
 * 模组的每一步要等人、计时、播报，自己有一套异步流程；它在自己的决定点上直接调下面某一个方法。要做的只有三件：
 * <ol>
 *   <li><b>在拥有那一局的线程上</b>造视角 {@link SeatView#of(Session, CharacterId)}、取合法选项（下表），
 *       然后可以把这一次调用整个交给工作线程 —— 视角与选项都是不可变的快照，策略碰不到 {@link Session}；</li>
 *   <li>拿回来的答案先核对再执行：选项清单 {@code contains} 它（数字在范围内）；不在，就用下表的默认，
 *       别抛（替身卡住比做错更糟）。{@link SeatDriver} 里那一套就是这么做的，照着写即可；</li>
 *   <li><b>每局每个座位一个策略实例</b>：{@link RandomSeatPolicy} 与 {@link HeuristicSeatPolicy} 没有按局的状态，
 *       一个实例可以全船共用；会往前推演的那一种（{@link SearchSeatPolicy}）带着按种子造的随机流，必须每局每座各造一个
 *       （{@link SearchSeatPolicy#seats} 就是这么造的），同一个实例也不要同时在两个线程上用。
 *       「记得谁帮过我」不在策略里，在视角的公开记事（{@link SeatView#deeds()}）里 —— 调用方不用替它保管什么。</li>
 * </ol>
 * 会往前推演的那一种<b>一个决定要想到 {@link SeatPolicySettings#millisPerDecision()} 毫秒</b>（默认 250），
 * 别在服务端主线程上调：照第 1 条在主线程上造好视角与选项，把调用交给工作线程，答案回到主线程再核对、执行。
 * 它推演出了错（引擎的毛病）时不抛，退回第一层的答案并记一笔（{@link SearchSeatPolicy#failures()}）—— 值得打进日志。
 *
 * <table border="1">
 *   <caption>每个方法什么时候问、选项从哪儿来、答错了退回什么</caption>
 *   <tr><th>方法</th><th>什么时候</th><th>选项</th><th>默认</th></tr>
 *   <tr><td>{@link #keepProvision}</td><td>补给箱在他手上</td><td>{@link Session#provisionOffer()}</td><td>第一张</td></tr>
 *   <tr><td>{@link #reveal}</td><td>轮到他行动，开始时</td><td>{@link Legal#reveals}</td><td>不亮</td></tr>
 *   <tr><td>{@link #drink}</td><td>轮到他行动，开始时</td><td>{@link Legal#drinks}</td><td>不喝</td></tr>
 *   <tr><td>{@link #give}</td><td>轮到他行动，开始时（可反复问）</td><td>{@link Legal#gifts}</td><td>不送</td></tr>
 *   <tr><td>{@link #act}</td><td>轮到他行动</td><td>{@link Legal#actions}</td><td>什么也不做</td></tr>
 *   <tr><td>{@link #keepRowCard}</td><td>他划船摸了牌</td><td>{@link Session#rowing()} 里的牌</td><td>第一张</td></tr>
 *   <tr><td>{@link #refuse}</td><td>他被指了（或问他反不反对分食）</td><td>拒绝 / 同意</td><td>同意</td></tr>
 *   <tr><td>{@link #joinStance}</td><td>站队，他清醒、不在场上</td><td>进攻 / 防守 / 不站</td><td>不站</td></tr>
 *   <tr><td>{@link #drinkForFight}</td><td>押武器之前，他在场上</td><td>{@link Legal#drinks}</td><td>不喝</td></tr>
 *   <tr><td>{@link #commitWeapons}</td><td>押武器，他在场上</td><td>{@link Legal#weapons}（交回其中几张）</td><td>不押</td></tr>
 *   <tr><td>{@link #pick}</td><td>他抢到手了</td><td>{@link Legal#picks}</td><td>从手里摸；他手里没牌就面前第一张</td></tr>
 *   <tr><td>{@link #steer}</td><td>他是舵手、要挑牌</td><td>划船堆 {@code table().rowStack()}</td><td>第一张</td></tr>
 *   <tr><td>{@link #overboard}</td><td>落海那一刻，他手里有能打的</td><td>{@link Legal#overboard}</td><td>不打</td></tr>
 *   <tr><td>{@link #drinkWater}</td><td>轮到他结算口渴</td><td>{@code 0..min(ownUnits, remaining)}，
 *       {@code ownUnits} = {@link Legal#ownWaterUnits}</td><td>按范围夹住</td></tr>
 *   <tr><td>{@link #donateWater}</td><td>别人在结算口渴、还差几次</td><td>{@code 0..min(myUnits, shortUnits)}</td><td>不递</td></tr>
 * </table>
 */
public interface SeatPolicy {

    /** 补给箱在我手上：从 {@code offer} 里留哪一张（按牌 id；重复的同 id 没有区别）。 */
    String keepProvision(SeatView view, List<String> offer, Random rng);

    /** 轮到我行动，开始之前亮不亮一张手牌（不占行动、不可逆）。{@code revealable} 就是我的手牌。 */
    Optional<String> reveal(SeatView view, List<String> revealable, Random rng);

    /** 轮到我行动，喝不喝一口酒（不占行动）。{@code drinkable} 可能为空 —— 照样会问。 */
    Optional<String> drink(SeatView view, List<String> drinkable, Random rng);

    /** 轮到我行动，送不送一张牌（不占行动；会接着再问，直到交回空）。 */
    default Optional<Gift> give(SeatView view, List<Gift> gifts, Random rng) {
        return Optional.empty();
    }

    /** 这一天的行动（规则第七章「五件事」）。 */
    ActionChoice act(SeatView view, List<ActionChoice> legal, Random rng);

    /** 划船摸到 {@code drawn}，留哪一张进划船堆（下标）。 */
    int keepRowCard(SeatView view, List<NavigationCard> drawn, Random rng);

    /**
     * 我被指了（换座位 · 抢），或者别人打出分食在问我反不反对：{@code true} = 拒绝 / 反对，打起来。
     * 这一场是什么见 {@code view.contest()}。
     */
    boolean refuse(SeatView view, Random rng);

    /** 站队：加入哪一边；空 = 不加入。这一场是什么见 {@code view.contest()}。 */
    Optional<Fight.Side> joinStance(SeatView view, Random rng);

    /** 我在场上、押武器之前，喝不喝一口酒。 */
    default Optional<String> drinkForFight(SeatView view, List<String> drinkable, Random rng) {
        return Optional.empty();
    }

    /**
     * 押武器：从 {@code weapons}（我手里与面前的每一张武器）里挑几张押下，交回挑中的那几张（暗牌）。
     */
    List<String> commitWeapons(SeatView view, List<String> weapons, Random rng);

    /** 抢到手了：挑哪一张。 */
    PickChoice pick(SeatView view, List<PickChoice> legal, Random rng);

    /** 我是舵手：从划船堆 {@code rowStack} 里挑一张执行。 */
    NavigationCard steer(SeatView view, List<NavigationCard> rowStack, Random rng);

    /** 落海那一刻：扔救生圈 / 打血饵；空 = 不打。会接着再问，直到交回空。 */
    default Optional<Session.OverboardPlay> overboard(SeatView view, List<Session.OverboardPlay> plays, Random rng) {
        return Optional.empty();
    }

    /**
     * 轮到我结算口渴：自己化解几次（{@code 0..min(ownUnits, view.thirst().remaining())}），还差的要不要开口要水。
     *
     * @param ownUnits 我自己的水够化解几次（手里加面前；昏迷时为 0 —— 自己喝不了）
     */
    WaterPlan drinkWater(SeatView view, int ownUnits, Random rng);

    /**
     * 别人在结算口渴、还差几次：我递几次（{@code 0..min(myUnits, shortUnits)}）。
     *
     * @param drinker      在结算的人
     * @param shortUnits   他还差几次
     * @param myUnits      我的水够递几次
     * @param helpAsked    他开口要了
     * @param donatedSoFar 这一次结算里，在我之前别人已经递了几次
     */
    default int donateWater(SeatView view, CharacterId drinker, int shortUnits, int myUnits, boolean helpAsked,
                            int donatedSoFar, Random rng) {
        return 0;
    }

    /** 这次测量用的是哪个策略 —— 打进报告里，免得回头对不上。 */
    String label();
}
