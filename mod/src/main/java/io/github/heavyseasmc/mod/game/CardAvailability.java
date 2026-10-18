package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.seat.Gift;
import io.github.heavyseasmc.engine.seat.Legal;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.engine.state.SurvivorState;
import io.github.heavyseasmc.mod.state.ExperienceView;
import io.github.heavyseasmc.mod.state.GameComponent;
import net.minecraft.text.Text;

/**
 * 手牌一面「这张现在能不能这样用」，以及服务端收包时的同一道门。
 *
 * <h2>判据只有一份：引擎的 {@link Legal}</h2>
 * 能不能做由 {@code Legal} 说了算（它与引擎守卫同源，{@code LegalTest} 在几千局随机局面里核对过）。
 * 这里只做两件引擎不管的事：
 * <ul>
 *   <li>模组自己的流程门：这一窗开着没有（落海 · 挂武器）、是不是轮到他行动、手上是不是正挂着另一件事；</li>
 *   <li>做不了时<b>为什么</b>：把结果映射成一句给人看的拒绝原因。原因只用来说话，不参与判定。</li>
 * </ul>
 *
 * <p>❗此前这里照着引擎守卫手抄了一份，两处已经分家（撑伞不看「要不要撑开」、送牌不排除「划船中」），
 * 屏幕上的表现是界面说能、按下去被拒，或反过来。{@code CardAvailabilityLegalTest} 逐局面核对两边相同。
 */
public final class CardAvailability {
    private CardAvailability() {
    }

    public enum Block {
        NONE, ENDED, CANNOT_ACT, CARD_CHANGED, NOT_YOUR_TURN, BUSY, PICK_LOCKED, ALREADY_USED,
        NOBODY_WOUNDED, NO_CORPSE, ALREADY_OPEN, WRONG_TIMING, NO_TARGET, WINDOW_CLOSED;

        public Text text() {
            return Text.translatable(switch (this) {
                case NONE -> "heavyseas.ux.available";
                case ENDED -> "heavyseas.ux.reject.ended";
                case CANNOT_ACT -> "heavyseas.ux.reject.cannot_act";
                case CARD_CHANGED -> "heavyseas.ux.reject.card_changed";
                case NOT_YOUR_TURN -> "heavyseas.ux.reject.not_your_turn";
                case BUSY -> "heavyseas.ux.reject.busy";
                case PICK_LOCKED -> "heavyseas.ux.reject.pick_locked";
                case ALREADY_USED -> "heavyseas.ux.reject.already_used";
                case NOBODY_WOUNDED -> "heavyseas.ux.reject.nobody_wounded";
                case NO_CORPSE -> "heavyseas.ux.reject.no_corpse";
                case ALREADY_OPEN -> "heavyseas.ux.reject.already_open";
                case WRONG_TIMING -> "heavyseas.ux.reject.wrong_timing";
                case NO_TARGET -> "heavyseas.ux.reject.no_target";
                case WINDOW_CLOSED -> "heavyseas.ux.reject.window_closed";
            });
        }
    }

    public static ExperienceView.Playability view(GameComponent component, CharacterId who, String card,
                                                  ExperienceView.Action action) {
        Block reason = check(component, who, card, action);
        return new ExperienceView.Playability(card, action, reason == Block.NONE, reason.text());
    }

    /** 只读：问多少次都不改局面。 */
    public static Block check(GameComponent c, CharacterId who, String card, ExperienceView.Action action) {
        if (c.session().isEmpty() || c.requireSession().state().isOver()) {
            return Block.ENDED;
        }
        Session session = c.requireSession();
        GameState g = session.state();
        if (!g.bySeat().contains(who) || !g.canAct(who)) {
            return Block.CANNOT_ACT;
        }
        SurvivorState held = g.stateOf(who);
        if (card == null || !session.provisions().has(card) || (!held.hasInHand(card) && !held.hasInFront(card))) {
            return Block.CARD_CHANGED;
        }
        return switch (action) {
            case REVEAL -> Legal.reveals(session, who).contains(card) ? Block.NONE
                    : pickingFrom(session, who) ? Block.PICK_LOCKED : Block.CARD_CHANGED;
            case GIVE_HAND, GIVE_FRONT -> gift(session, who, card, action == ExperienceView.Action.GIVE_FRONT);
            case OVERBOARD -> overboard(c, session, who, card);
            case WEAPON -> weapon(c, session, who, card);
            case PLAY -> play(c, session, who, card);
        };
    }

    private static Block gift(Session session, CharacterId who, String card, boolean fromFront) {
        boolean legal = Legal.gifts(session, who).stream()
                .anyMatch(gift -> gift.card().equals(card) && gift.fromFront() == fromFront);
        if (legal) {
            return Block.NONE;
        }
        GameState g = session.state();
        if (g.phase() != Phase.ACTION) {
            return Block.WRONG_TIMING;
        }
        if (session.contest().isPresent() || session.rower().isPresent()) {
            return Block.BUSY;
        }
        boolean there = fromFront ? g.stateOf(who).hasInFront(card) : g.stateOf(who).hasInHand(card);
        return there ? Block.NO_TARGET : Block.CARD_CHANGED;
    }

    /** 送给这个人这一张，此刻合不合法 —— 收包时带着目标再问一遍（{@link CardActions}）。 */
    static boolean giftLegal(Session session, CharacterId from, String card, boolean fromFront, CharacterId to) {
        return Legal.gifts(session, from).contains(new Gift(card, fromFront, to));
    }

    private static Block overboard(GameComponent c, Session session, CharacterId who, String card) {
        if (!overboardWindowOpen(c)) {
            return Block.WINDOW_CLOSED;
        }
        return Legal.overboard(session, who).stream().anyMatch(p -> p.card().equals(card))
                ? Block.NONE : Block.NO_TARGET;
    }

    static boolean overboardWindowOpen(GameComponent c) {
        return c.overboardDeadline() > 0 && System.currentTimeMillis() < c.overboardDeadline()
                && c.requireSession().overboardPending().isPresent();
    }

    private static Block weapon(GameComponent c, Session session, CharacterId who, String card) {
        if (c.contestDeadline() <= 0 || session.contest().filter(t -> t.stage() == Contest.Stage.WEAPONS).isEmpty()) {
            return Block.WRONG_TIMING;
        }
        return ContestPhase.canCommitWeapon(session, session.contest().orElseThrow(), who, card)
                ? Block.NONE : Block.WRONG_TIMING;
    }

    private static Block play(GameComponent c, Session session, CharacterId who, String card) {
        GameState g = session.state();
        ProvisionEffect effect = session.provisions().get(card).effect();
        if (effect instanceof ProvisionEffect.BuffSize) {
            if (Legal.drinks(session, who).contains(card)) {
                return Block.NONE;
            }
            return pickingFrom(session, who) ? Block.PICK_LOCKED
                    : g.stateOf(who).usedThisTurn(card) ? Block.ALREADY_USED : Block.CARD_CHANGED;
        }
        if (!session.provisions().get(card).isSpecialAction()) {
            return Block.WRONG_TIMING;
        }
        // 特殊行动占这一回合的行动：轮次与「手上没挂着别的事」是模组的流程，引擎的 Legal 默认已经轮到他
        if (g.phase() != Phase.ACTION || !session.nextActor().map(who::equals).orElse(false)) {
            return Block.NOT_YOUR_TURN;
        }
        if (session.contest().isPresent() || session.rower().isPresent() || c.designating().isPresent()
                || c.provisionTargeter().isPresent()) {
            return Block.BUSY;
        }
        if (!Legal.plays(session, who, card).isEmpty()) {
            return Block.NONE;
        }
        if (effect instanceof ProvisionEffect.Heal) {
            return Block.NOBODY_WOUNDED;
        }
        if (effect instanceof ProvisionEffect.HealAll) {
            return Block.NO_CORPSE;
        }
        if (effect instanceof ProvisionEffect.PreventThirst && g.stateOf(who).isOpen(card)) {
            return Block.ALREADY_OPEN;
        }
        return Block.WRONG_TIMING;
    }

    private static boolean pickingFrom(Session session, CharacterId who) {
        return session.contest().map(t -> t.stage() == Contest.Stage.PICK && t.target().equals(who)).orElse(false);
    }
}
