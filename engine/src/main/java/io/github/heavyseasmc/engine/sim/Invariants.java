package io.github.heavyseasmc.engine.sim;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.state.SurvivorState;

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
            if (s.thirst().count() > 3) {
                bad.add("%s 单回合口渴 %d 次，超过来源种类数".formatted(id, s.thirst().count()));
            }

            // 生死状态必须与「伤害 vs 体型」一致。这条看起来是废话，正是它要防的 ——
            // 哪天有人把 Condition 缓存进 SurvivorState，这条立刻红。
            int size = g.roster().get(id).size();
            Condition expected = Condition.onBoat(s.damage(), size);
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
        List<String> bad = new ArrayList<>();
        for (CharacterId id : before.bySeat()) {
            Condition was = before.conditionOf(id);
            Condition now = after.conditionOf(id);
            if (was == Condition.DEAD && now != Condition.DEAD) {
                bad.add("%s 从死亡复活到 %s —— 死亡不可复生".formatted(id, now));
            }
            int d0 = before.stateOf(id).damage();
            int d1 = after.stateOf(id).damage();
            // 医疗箱能减伤，但模拟器不用它；真用起来时这条要放宽成「只能由治疗减少」。
            if (d1 < d0) {
                bad.add("%s 的伤害从 %d 降到 %d，而本局没有任何治疗".formatted(id, d0, d1));
            }
        }
        if (after.turn() < before.turn()) {
            bad.add("回合数倒退: %d → %d".formatted(before.turn(), after.turn()));
        }
        return bad;
    }

    /** 跨状态检查并在有违规时抛出。 */
    public static void requireValidTransition(GameState before, GameState after, long seed, String where) {
        List<String> bad = checkTransition(before, after);
        if (!bad.isEmpty()) {
            throw new IllegalStateException(
                    "状态转移非法（seed=%d, %s, 回合 %d → %d）：%s"
                            .formatted(seed, where, before.turn(), after.turn(), String.join("; ", bad)));
        }
    }

    /** 检查并在有违规时抛出，附上全部违规与一个可复现的种子。 */
    public static void requireValid(GameState g, long seed, String where) {
        List<String> bad = check(g);
        if (!bad.isEmpty()) {
            throw new IllegalStateException(
                    "状态非法（seed=%d, %s, 回合 %d, 阶段 %s）：%s"
                            .formatted(seed, where, g.turn(), g.phase(), String.join("; ", bad)));
        }
    }
}
