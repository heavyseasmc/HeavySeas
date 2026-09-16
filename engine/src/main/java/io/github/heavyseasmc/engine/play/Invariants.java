package io.github.heavyseasmc.engine.play;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.state.SurvivorState;
import io.github.heavyseasmc.engine.thirst.ThirstSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 每走一步都要成立的性质。
 *
 * <p><b>这是模拟器存在的理由。</b> 本作不做 AI 玩家，因此没有任何对局层面的自动验证手段；
 * 单测覆盖的是「我想到要测的那些情况」，而随机对局覆盖的是「我没想到的那些」。
 * 两者的区别不是数量，是<b>谁来挑输入</b>。
 *
 * <p>❗<b>每条不变量都必须能被违反。</b> 一条永远成立的断言与没有断言等价 ——
 * 本仓库已经三次实证假绿（守卫放行全部迁移 id、textguard 扫不存在的目录、
 * MANIFEST 前缀对不上）。所以每条不变量都配了对应的变异测试。
 */
public final class Invariants {

    private Invariants() {
    }

    /**
     * 检查一个状态是否自洽。返回违规描述；空列表表示通过。
     *
     * <p>返回列表而不是抛异常，是为了让模拟器能<b>一次报出全部问题</b> ——
     * 只报第一个会让人修一条跑一次，几千局的反馈周期就没了意义。
     */
    public static List<String> check(GameState g) {
        List<String> bad = new ArrayList<>();

        Set<Integer> seats = new HashSet<>();
        for (CharacterId id : g.bySeat()) {
            SurvivorState s = g.stateOf(id);

            if (!seats.add(s.seat())) {
                bad.add("座位 %d 被多人占用（%s）".formatted(s.seat(), id));
            }
            if (s.damage() < 0) {
                bad.add("%s 的伤害为负: %d".formatted(id, s.damage()));
            }
            // ❗上限取自枚举的常量数，不写死数字：来源加一种时这条不需要跟着改，
            //   而写死的 3 会在加了第四种来源之后变成一条**拦下正确状态**的判据。
            if (s.thirst().count() > ThirstSource.values().length) {
                bad.add("%s 单回合口渴 %d 次，超过来源种类数 %d"
                        .formatted(id, s.thirst().count(), ThirstSource.values().length));
            }
            // 撑开的伞必须还在面前 —— 两者脱节的表现是它一直白挡口渴，而没有任何一步会报错。
            for (String open : s.opened()) {
                if (!s.front().contains(open)) {
                    bad.add("%s 的 %s 标着已打开，却不在他面前".formatted(id, open));
                }
            }

            // 生死状态必须与「伤害 vs 体型」一致。这条看起来是废话，正是它要防的 ——
            // 哪天有人把 Condition 缓存进 SurvivorState，这条立刻红。
            // 被移出游戏的人：手上与面前都必须是空的 —— 牌随人离场。不空的话，那几张牌既在他身上又「退出了游戏」。
            if (g.isRemoved(id) && (!s.hand().isEmpty() || !s.front().isEmpty())) {
                bad.add("%s 已被移出游戏，手上或面前却还有牌（%s / %s）".formatted(id, s.hand(), s.front()));
            }
            int size = g.roster().get(id).size();
            Condition expected = g.isRemoved(id) ? Condition.DEAD : Condition.onBoat(s.damage(), size);
            if (g.conditionOf(id) != expected) {
                bad.add("%s 的状态 %s 与伤害/体型 %d/%d 不符（应为 %s）"
                        .formatted(id, g.conditionOf(id), s.damage(), size, expected));
            }

        }

        if (g.turn() < 1) {
            bad.add("回合数必须从 1 起，实际: " + g.turn());
        }
        if (g.gulls() < 0) {
            bad.add("海鸥数不能为负: " + g.gulls());
        }

        // 舵手必须是清醒者里最靠船尾的那个。写错了整局的权力位就错了，而它不会自己报错。
        g.helmsman().ifPresent(h -> {
            if (!g.conditionOf(h).canAct()) {
                bad.add("舵手 %s 不是清醒状态".formatted(h));
            }
            List<CharacterId> conscious = g.consciousBySeat();
            if (!conscious.isEmpty() && !conscious.get(conscious.size() - 1).equals(h)) {
                bad.add("舵手 %s 不是最靠船尾的清醒角色（应为 %s）"
                        .formatted(h, conscious.get(conscious.size() - 1)));
            }
        });
        if (g.helmsman().isEmpty() && !g.consciousBySeat().isEmpty()) {
            bad.add("有清醒角色却没有舵手");
        }

        // 下一个行动者必须清醒且本回合未行动过。
        g.nextActor().ifPresent(a -> {
            if (!g.conditionOf(a).canAct()) {
                bad.add("下一个行动者 %s 不是清醒状态".formatted(a));
            }
            if (g.stateOf(a).actedThisTurn()) {
                bad.add("下一个行动者 %s 本回合已经行动过".formatted(a));
            }
        });

        return bad;
    }

    /**
     * 跨状态的性质：拿前后两个状态比，单看一个状态看不出来。
     *
     * <p>❗这一组是被模拟器<b>逼出来</b>的。原先有一条「死人不该带着本回合的行动标记」，
     * 2000 局里第 3 回合就红了 —— 而那是<b>我的不变量写错了</b>，不是状态机的缺陷：
     * 角色在行动阶段合法地行动过，随后在同一回合的航海阶段死掉，标记要到回合结束才清。
     * {@code actedThisTurn} 是个标志位，<b>分不出「先行动后死」与「死后还行动」</b>，
     * 那条性质用单个状态根本表达不了。能表达的形式只有这里的跨状态比较。
     *
     * @return 违规描述；空列表表示通过
     */
    public static List<String> checkTransition(GameState before, GameState after) {
        return checkTransition(before, after, 0);
    }

    /**
     * 同上，但允许伤害因治疗而下降。
     *
     * <p>❗这一条原本是「伤害只增不减」，注释里写着「真用起来时要放宽」。
     * 物资建模之后医疗箱与绝境真的会减伤 —— <b>而直接删掉这条的话，「复活」与「治疗」从此再也分不开</b>，
     * 那正是这条不变量存在的理由。所以改成「只能由治疗减少，且总量不超过这两步之间治了几点」。
     *
     * @param healed 这两个状态之间一共治了几点（{@code Session#healedSincePhaseStart}）
     */
    public static List<String> checkTransition(GameState before, GameState after, int healed) {
        List<String> bad = new ArrayList<>();
        if (healed < 0) {
            bad.add("治疗点数不能为负: " + healed);
        }
        int dropped = 0;
        for (CharacterId id : before.bySeat()) {
            Condition was = before.conditionOf(id);
            Condition now = after.conditionOf(id);
            if (was == Condition.DEAD && now != Condition.DEAD) {
                bad.add("%s 从死亡复活到 %s —— 死亡不可复生".formatted(id, now));
            }
            // 与上一条不同源：移出是一个标志，单看一个状态看不出它是不是被弄丢过。
            if (before.isRemoved(id) && !after.isRemoved(id)) {
                bad.add("%s 已被移出游戏，却又回到了船上".formatted(id));
            }
            int d0 = before.stateOf(id).damage();
            int d1 = after.stateOf(id).damage();
            if (d1 < d0) {
                dropped += d0 - d1;
            }
        }
        if (dropped > healed) {
            bad.add("全场伤害合计降了 %d 点，而这两步之间只治了 %d 点".formatted(dropped, healed));
        }
        if (after.turn() < before.turn()) {
            bad.add("回合数倒退: %d → %d".formatted(before.turn(), after.turn()));
        }
        return bad;
    }

    /** 跨状态检查并在有违规时抛出。 */
    public static void requireValidTransition(GameState before, GameState after, long seed, String where) {
        requireValidTransition(before, after, "seed=%d".formatted(seed), where, 0);
    }

    /** 同上，允许这两步之间治掉 {@code healed} 点伤。 */
    public static void requireValidTransition(GameState before, GameState after, long seed,
                                              String where, int healed) {
        requireValidTransition(before, after, "seed=%d".formatted(seed), where, healed);
    }

    /** 检查并在有违规时抛出，附上全部违规与一个可复现的种子。 */
    public static void requireValid(GameState g, long seed, String where) {
        requireValid(g, "seed=%d".formatted(seed), where);
    }

    /**
     * 同上，但出处是一段自述的上下文而不是种子。
     *
     * <p>真实对局没有种子 —— 它的「怎么复现」是存档加日志。硬塞一个假种子会让报错
     * 看上去可复现、实际复现不了，那比没有出处更坏。
     */
    public static void requireValidTransition(
            GameState before, GameState after, String context, String where) {
        requireValidTransition(before, after, context, where, 0);
    }

    /** 同上，允许这两步之间治掉 {@code healed} 点伤。 */
    public static void requireValidTransition(
            GameState before, GameState after, String context, String where, int healed) {
        List<String> bad = checkTransition(before, after, healed);
        if (!bad.isEmpty()) {
            throw new IllegalStateException(
                    "状态转移非法（%s, %s, 回合 %d → %d）：%s"
                            .formatted(context, where, before.turn(), after.turn(), String.join("; ", bad)));
        }
    }

    /** 同上。 */
    public static void requireValid(GameState g, String context, String where) {
        List<String> bad = check(g);
        if (!bad.isEmpty()) {
            throw new IllegalStateException(
                    "状态非法（%s, %s, 回合 %d, 阶段 %s）：%s"
                            .formatted(context, where, g.turn(), g.phase(), String.join("; ", bad)));
        }
    }
}
