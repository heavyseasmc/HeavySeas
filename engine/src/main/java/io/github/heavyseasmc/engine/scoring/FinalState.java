package io.github.heavyseasmc.engine.scoring;

import io.github.heavyseasmc.engine.model.CharacterId;

import java.util.Objects;

/**
 * 某个角色在终局时刻的状态，计分的全部输入。
 *
 * <p><b>{@code alive} 与 {@code onBoat} 必须分开存，不能合成一个字段</b>，
 * 因为它们各自喂给不同的计分项，而且在「落水死亡」这一种情形下取值不同：
 *
 * <table border="1">
 *   <caption>三种终局形态</caption>
 *   <tr><th>形态</th><th>alive</th><th>onBoat</th><th>财宝</th><th>被恨时给分</th></tr>
 *   <tr><td>活着</td><td>true</td><td>true</td><td>算</td><td>不给</td></tr>
 *   <tr><td>死在艇上</td><td>false</td><td>true</td><td><b>算（遗产）</b></td><td>给</td></tr>
 *   <tr><td>落水死亡被移出</td><td>false</td><td>false</td><td>不算</td><td><b>照给</b></td></tr>
 * </table>
 *
 * <p>最后一行那两格是要害：财宝看的是<b>状态</b>（尸体在不在艇上），
 * 而憎恨卡看的是<b>事件</b>（他死没死）。同一个人可以既"不在艇上所以财宝不算"，
 * 又"确实死了所以恨他的人照拿分"。
 */
public record FinalState(
        boolean alive,
        boolean onBoat,
        Treasures treasures,
        CharacterId love,
        CharacterId hate
) {
    public FinalState {
        Objects.requireNonNull(treasures, "treasures");
        Objects.requireNonNull(love, "love");
        Objects.requireNonNull(hate, "hate");
        if (alive && !onBoat) {
            throw new IllegalArgumentException(
                    "活着却不在艇上——落水者只要没死，落水结算一完成就自动回到艇上");
        }
    }

    /** 憎恨卡的触发条件：一个<b>事件</b>。不问死在哪，也不问尸体还在不在。 */
    public boolean died() {
        return !alive;
    }

    /** 厌世者清点的对象：一个<b>状态</b>。 */
    public boolean corpseOnBoat() {
        return !alive && onBoat;
    }
}
