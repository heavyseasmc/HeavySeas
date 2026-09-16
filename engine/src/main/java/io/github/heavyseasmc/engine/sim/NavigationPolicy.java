package io.github.heavyseasmc.engine.sim;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.Selector;
import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.thirst.ThirstSource;

import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * 划船与挑牌的<b>取向</b>：划船者留哪张、舵手挑哪张。
 *
 * <h2>这不是 AI，是偏置发生器</h2>
 * 本作不做 AI 玩家（决策 ②）。这里要的不是「打得好」，而是<b>一个明确写下来的偏置</b>：
 * 舵手会挑牌这件事会把落水/口渴的实际分布拉离牌面上的张数分布，而拉多远取决于他怎么挑。
 * 所以策略必须是可替换的、并且每次测量都要说清用的是哪个 —— 一条不注明策略的曲线
 * 没有任何意义，因为它测的一半是牌、一半是策略。
 *
 * <h2>❗结论必须在几种策略下都成立</h2>
 * O1 要判的是「B 版与 A 版是否统计等价」。若两版在无所谓的舵手下等价、在自保的舵手下不等价，
 * 那答案就是「取决于玩家怎么玩」，而不是「等价」。把策略做成参数，才问得出这个问题。
 */
public interface NavigationPolicy {

    /**
     * 划船者看过这张牌之后：放进划船堆（true），还是塞回牌堆底部（false）。
     *
     * <p>❗划船堆是<b>面朝下</b>的：只有舵手能看到全部，别人只知道有几个人划了船。
     * 所以这个决定只能用划船者自己的视角，不能参考别人留了什么。
     */
    boolean keepWhenRowing(NavigationCard card, GameState state, CharacterId rower, Random rng);

    /** 舵手从划船堆里挑一张执行。传进来的列表非空。 */
    NavigationCard pick(List<NavigationCard> rowStack, GameState state, CharacterId helmsman, Random rng);

    /** 这次测量用的是哪个策略 —— 打进报告里，免得回头对不上。 */
    String label();

    /**
     * 无所谓：留不留、挑哪张全看运气。
     *
     * <p>它是<b>对照组</b>，不是「玩家很笨」的模型：只有把它与有取向的策略放在一起，
     * 才分得清分布里哪一部分来自牌、哪一部分来自挑牌。
     */
    NavigationPolicy INDIFFERENT = new NavigationPolicy() {
        @Override
        public boolean keepWhenRowing(NavigationCard card, GameState state, CharacterId rower, Random rng) {
            return rng.nextBoolean();
        }

        @Override
        public NavigationCard pick(List<NavigationCard> rowStack, GameState state, CharacterId helmsman, Random rng) {
            return rowStack.get(rng.nextInt(rowStack.size()));
        }

        @Override
        public String label() {
            return "无所谓（对照组）";
        }
    };

    /**
     * 自保：只考虑这张牌对自己的好坏，不考虑坑谁。
     *
     * <p>刻意<b>不</b>建模「针对讨厌的人」—— 那需要憎恨目标与场上局势，属于 AI。
     * 自保是能被写死、也能被解释的最强偏置：社区共识本来就是「无论如何都要抢到航海权」，
     * 而抢到之后第一件事就是别让自己落水。
     */
    NavigationPolicy SELF_INTERESTED = new SelfInterested(SelfInterested.GULL);

    /**
     * 只躲水、<b>不追海鸥</b>的自保。
     *
     * <p>存在的理由是把两件事拆开：海鸥权重一大，舵手就一路催着靠岸，整局从十二回合缩到八回合，
     * 而<b>局变短本身</b>就会改变每个人被点到的次数。要看「躲水」这一件事对分布的影响，
     * 就得有一个局长不变的对照。
     */
    NavigationPolicy SELF_INTERESTED_NO_RUSH = new SelfInterested(0);

    /** {@link #SELF_INTERESTED} 的实现。分数越高越想要。 */
    final class SelfInterested implements NavigationPolicy {

        /** 落水要挨 1 点伤，体型小的可能直接送命，所以扣得最狠。 */
        static final int OVERBOARD = -4;
        /** 口渴可以喝水化解，但水是消耗品。 */
        static final int THIRST = -2;
        /** 海鸥推进靠岸：活着的人希望这一局早点结束。 */
        static final int GULL = 3;

        /**
         * 海鸥的权重。<b>它是这套策略里唯一没有客观依据的数</b> ——
         * 靠岸对谁有利取决于谁的财宝多，而本模拟器不建模财宝。所以它是个旋钮，
         * 测量时要连着结论一起报出来。
         */
        private final int gullWeight;

        private SelfInterested(int gullWeight) {
            this.gullWeight = gullWeight;
        }

        @Override
        public boolean keepWhenRowing(NavigationCard card, GameState state, CharacterId rower, Random rng) {
            // 划船者只能二选一：留下，或塞回底部。留下意味着它可能被舵手挑中，
            // 所以「对我不坏」就留 —— 阈值取 0，不是随手定的：0 分意味着这张牌与我无关。
            return score(card, state, rower) >= 0;
        }

        @Override
        public NavigationCard pick(List<NavigationCard> rowStack, GameState state, CharacterId helmsman, Random rng) {
            NavigationCard best = null;
            int bestScore = Integer.MIN_VALUE;
            for (NavigationCard card : rowStack) {
                int score = score(card, state, helmsman);
                // 同分时随机取一张，否则牌堆顺序会变成暗中的第二个策略。
                if (score > bestScore || (score == bestScore && rng.nextBoolean())) {
                    best = card;
                    bestScore = score;
                }
            }
            return Objects.requireNonNull(best);
        }

        @Override
        public String label() {
            return "自保（落水 %d / 口渴 %d / 海鸥 %+d）".formatted(OVERBOARD, THIRST, gullWeight);
        }

        /** 这张牌对 {@code who} 的好坏。只看自己，不看别人。 */
        int score(NavigationCard card, GameState state, CharacterId who) {
            int score = card.gull() * gullWeight;

            Set<CharacterId> everyone = Set.copyOf(state.onBoatBySeat());   // 被移出的人不会再被点到
            Selector.ConditionResolver noConditions = (condition, id) -> false;
            if (card.overboard().select(everyone, noConditions).contains(who)) {
                score += OVERBOARD;
            }

            // 口渴：牌面点名，外加本回合已经背着的划船 / 战斗标记 —— 那两个只在牌上
            // 画着对应图示时才发作，所以要连标记一起看，光看名单会低估这张牌的代价。
            if (state.conditionOf(who).suffersThirst()) {
                var marks = state.stateOf(who).thirst();
                int thirsts = 0;
                if (card.thirst().select(everyone, noConditions).contains(who)) {
                    thirsts++;
                }
                if (card.thirstRowers() && marks.has(ThirstSource.ROWED)) {
                    thirsts++;
                }
                if (card.thirstFighters() && marks.has(ThirstSource.FOUGHT)) {
                    thirsts++;
                }
                score += thirsts * THIRST;
            }

            // 已经死了的人没有偏好。理论上轮不到他挑牌（舵手必须清醒），
            // 但划船标记与统计都按「活着」算，这里保持同一个口径。
            return state.conditionOf(who) == Condition.DEAD ? 0 : score;
        }
    }
}
