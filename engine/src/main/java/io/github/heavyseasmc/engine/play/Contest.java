package io.github.heavyseasmc.engine.play;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.state.Fight;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 进行中的一次争议（ADR-0023）：换座位、抢夺，或打出绝境后有人反对。
 *
 * <h2>为什么是一个状态机，而不是「调用方组好一场 {@link Fight} 交进来」</h2>
 * 规则里最容易写错的几条都跨着好几步：只有<b>清醒且不同意</b>才打得起来；站队锁定后不可退出；
 * <b>战斗结束前任何卡不得易手</b>；挑牌那一刻被抢方不能亮牌；无论胜负，进攻方的行动就此结束。
 * 由调用方组装的话，这些全靠驱动者自觉 —— 而模拟器此前正是在「组装」那一步凭空造过武器（证伪表）。
 *
 * <p>本类只是<b>一张快照</b>，不可变；推进全在 {@link Session} 里。
 *
 * @param kind      换座位、抢夺，还是绝境反对
 * @param attacker  发起的人。他的行动在这一场收场时结束，由驱动者记（{@link Session#markActed}）
 * @param target    被指定的人
 * @param stage     走到哪一段
 * @param fight     站队与挂武器两段里的那一场；表态与挑牌时为空
 * @param handOnly  挑牌只能挑手牌（小孩的偷窃，决策 ⑦）
 * @param committed 挂武器段里每个参战者押下的牌。❗<b>暗牌</b>（决策 ④）：结算之前不挪、不亮 ——
 *                  投影只能把押下的人自己那一份发给他本人
 * @param provision 绝境反对所对应的物资 id；另外两种争议为空串
 * @param passed    绝境反对里已经选择不反对的人；轮到下一位时不能再问一遍
 */
public record Contest(Kind kind, CharacterId attacker, CharacterId target, Stage stage,
                      Optional<Fight> fight, boolean handOnly, Map<CharacterId, List<String>> committed,
                      String provision, Set<CharacterId> passed) {

    public Contest {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(attacker, "attacker");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(fight, "fight");
        Objects.requireNonNull(committed, "committed");
        Objects.requireNonNull(provision, "provision");
        Objects.requireNonNull(passed, "passed");
        if (attacker.equals(target)) {
            throw new IllegalArgumentException("不能对自己换座位或抢夺：" + attacker);
        }
        if (handOnly && kind != Kind.STEAL) {
            throw new IllegalArgumentException("只挑手牌是抢夺才有的限制，换座位没有：" + attacker);
        }
        if ((kind == Kind.RATION) != !provision.isEmpty()) {
            throw new IllegalArgumentException("只有绝境反对能带物资 id：" + kind + " / " + provision);
        }
        if (kind != Kind.RATION && !passed.isEmpty()) {
            throw new IllegalArgumentException("只有绝境反对会逐个记录不反对的人：" + kind);
        }
        boolean fighting = stage == Stage.STANCES || stage == Stage.WEAPONS;
        if (fighting != fight.isPresent()) {
            throw new IllegalArgumentException("站队与挂武器两段必须有一场战斗，其余几段不能有 —— 现在是 " + stage);
        }
        Map<CharacterId, List<String>> copy = new LinkedHashMap<>();
        committed.forEach((who, cards) -> copy.put(who, List.copyOf(cards)));
        committed = Collections.unmodifiableMap(copy);
        passed = Set.copyOf(passed);
    }

    /** 换座位、抢夺，还是绝境反对。 */
    public enum Kind {
        SWAP, STEAL, RATION
    }

    /** 走到哪一段。 */
    public enum Stage {
        /** 等被指定的人表态：同意，还是战斗。 */
        CONSENT,
        /** 站队：清醒的人可以加入任意一边，加入后不可退出。 */
        STANCES,
        /** 挂武器：只有参战者能押，暗牌。 */
        WEAPONS,
        /** 抢夺成功之后，抢夺方挑一张：面前一张，或手牌随机一张。 */
        PICK
    }

    /** 某人在这一场里押下的牌；没押过为空。 */
    public List<String> committedBy(CharacterId who) {
        return committed.getOrDefault(who, List.of());
    }

    Contest advance(Stage next, Optional<Fight> nextFight) {
        return new Contest(kind, attacker, target, next, nextFight, handOnly, committed, provision, passed);
    }

    Contest withFight(Fight next) {
        return new Contest(kind, attacker, target, stage, Optional.of(next), handOnly, committed, provision, passed);
    }

    /** 绝境这一位不反对，改问下一位。 */
    Contest passAndAsk(CharacterId who, CharacterId nextTarget) {
        if (kind != Kind.RATION || stage != Stage.CONSENT || !target.equals(who)) {
            throw new IllegalStateException("现在不是绝境逐个询问反对者：" + kind + " / " + stage);
        }
        Set<CharacterId> nextPassed = new java.util.LinkedHashSet<>(passed);
        nextPassed.add(who);
        return new Contest(kind, attacker, nextTarget, stage, Optional.empty(), false, committed,
                provision, nextPassed);
    }

    Contest withCommitted(CharacterId who, String cardId) {
        Map<CharacterId, List<String>> next = new LinkedHashMap<>(committed);
        List<String> cards = new ArrayList<>(committedBy(who));
        cards.add(cardId);
        next.put(who, cards);
        return new Contest(kind, attacker, target, stage, fight, handOnly, next, provision, passed);
    }
}
