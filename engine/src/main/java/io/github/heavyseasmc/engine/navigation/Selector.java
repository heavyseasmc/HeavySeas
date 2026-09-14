package io.github.heavyseasmc.engine.navigation;

import io.github.heavyseasmc.engine.model.CharacterId;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 航海牌上「点谁」的五种写法。
 *
 * <p>做成 sealed 接口而非带 mode 字段的记录，是为了让<b>穷尽性由编译器保证</b> ——
 * 数据里真实存在五种模式，漏掉任何一种都会在运行期才炸，而航海牌是每回合都要结算的东西。
 *
 * <h2>候选集由调用方给，不由本类推断</h2>
 * 「所有人」到底包括谁，取决于结算的是落海还是口渴，两者不一样：
 * <ul>
 *   <li>落海：尸体也会被冲下去（然后彻底退出游戏），所以候选含死者；</li>
 *   <li>口渴：死者不口渴，候选只含活着的（昏迷者<b>算</b>，他仍会口渴）。</li>
 * </ul>
 * 把这个判断放进选择器就等于让它知道自己被用在哪一步，那是两份真相。
 */
public sealed interface Selector {

    /** 判定某个条件对某人是否成立。`conditional` 模式用它，条件名来自物资 id（如喝过酒）。 */
    @FunctionalInterface
    interface ConditionResolver {
        boolean holds(String condition, CharacterId who);
    }

    Set<CharacterId> select(Set<CharacterId> candidates, ConditionResolver resolver);

    /** 全体候选。 */
    record Everyone() implements Selector {
        @Override
        public Set<CharacterId> select(Set<CharacterId> candidates, ConditionResolver resolver) {
            return Set.copyOf(candidates);
        }
    }

    /** 一个人也不点。整张牌该栏为空时用它，<b>不要用空的 Only 代替</b> —— 那会让「没写」和「写了空名单」看起来一样。 */
    record Nobody() implements Selector {
        @Override
        public Set<CharacterId> select(Set<CharacterId> candidates, ConditionResolver resolver) {
            return Set.of();
        }
    }

    /**
     * 点名。
     *
     * <p>❗名单里的人<b>不一定还在候选集里</b>（可能已经出局）。取交集，不报错 ——
     * 牌是印死的，场上少个人是常态。
     */
    record Only(Set<CharacterId> characters) implements Selector {
        public Only {
            characters = Set.copyOf(Objects.requireNonNull(characters, "characters"));
        }

        @Override
        public Set<CharacterId> select(Set<CharacterId> candidates, ConditionResolver resolver) {
            Set<CharacterId> out = new LinkedHashSet<>(candidates);
            out.retainAll(characters);
            return Set.copyOf(out);
        }
    }

    /** 除名单之外的所有候选。 */
    record Except(Set<CharacterId> characters) implements Selector {
        public Except {
            characters = Set.copyOf(Objects.requireNonNull(characters, "characters"));
        }

        @Override
        public Set<CharacterId> select(Set<CharacterId> candidates, ConditionResolver resolver) {
            Set<CharacterId> out = new LinkedHashSet<>(candidates);
            out.removeAll(characters);
            return Set.copyOf(out);
        }
    }

    /**
     * 按条件筛选，例如「喝过酒的人」。
     *
     * <p>❗条件名取自<b>物资</b> id，不是角色 id。两者在这里长得一样（都是小写下划线），
     * 传错了不会有任何报错，只会静默选出空集合 —— 所以解析这一栏时要按物资 id 全集校验。
     */
    record Conditional(String condition) implements Selector {
        public Conditional {
            Objects.requireNonNull(condition, "condition");
            if (condition.isBlank()) {
                throw new IllegalArgumentException("条件名不能为空");
            }
        }

        @Override
        public Set<CharacterId> select(Set<CharacterId> candidates, ConditionResolver resolver) {
            Objects.requireNonNull(resolver, "conditional 模式需要 ConditionResolver");
            Set<CharacterId> out = new LinkedHashSet<>();
            for (CharacterId id : candidates) {
                if (resolver.holds(condition, id)) {
                    out.add(id);
                }
            }
            return Set.copyOf(out);
        }
    }
}
