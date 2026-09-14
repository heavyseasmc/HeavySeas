package io.github.heavyseasmc.engine.model;

import java.util.Objects;

/**
 * 角色主键。
 *
 * <p>❗必须是字符串 id，<b>不能用体型做索引或主键</b>。8 人阵容里体型 3 与 4 各有两个
 * （厨子 3 / 小孩 3、医生 4 / 夫人 4），拿体型当键会在 8 人局直接撞车。
 *
 * <p>包成值类型而不是裸 {@code String}，是为了让它在编译期与物资 id、牌 id 区分开——
 * 这类 id 混用的 bug 在运行时极难查。
 */
public record CharacterId(String value) implements Comparable<CharacterId> {

    public CharacterId {
        Objects.requireNonNull(value, "角色 id 不能为 null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("角色 id 不能为空");
        }
    }

    public static CharacterId of(String value) {
        return new CharacterId(value);
    }

    @Override
    public int compareTo(CharacterId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
