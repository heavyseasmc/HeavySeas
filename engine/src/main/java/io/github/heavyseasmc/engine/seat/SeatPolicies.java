package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.model.CharacterId;

import java.util.Objects;

/**
 * 一局里每个座位用哪个策略。每开一局问一遍：带自己随机流的策略（推演）要按这一局的种子造，
 * 不能几局共用一个 —— 共用的话第二局的走向取决于第一局推演了多少次。
 */
@FunctionalInterface
public interface SeatPolicies {

    /**
     * @param seat 哪个座位（角色）
     * @param seed 这一局的种子
     */
    SeatPolicy forSeat(CharacterId seat, long seed);

    /** 全船用同一个（无状态的）策略。 */
    static SeatPolicies all(SeatPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        return (seat, seed) -> policy;
    }

    /** {@code special} 那一个座位用 {@code it}，其余用 {@code rest}。混合桌用。 */
    static SeatPolicies one(CharacterId special, SeatPolicies it, SeatPolicies rest) {
        Objects.requireNonNull(special, "special");
        Objects.requireNonNull(it, "it");
        Objects.requireNonNull(rest, "rest");
        return (seat, seed) -> seat.equals(special) ? it.forSeat(seat, seed) : rest.forSeat(seat, seed);
    }
}
