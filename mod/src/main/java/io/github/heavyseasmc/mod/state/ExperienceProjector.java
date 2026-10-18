package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.scoring.Scorer;
import io.github.heavyseasmc.mod.game.CardAvailability;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Builds recipient-only explanations from authoritative state and the scorer's rule model.
 *
 * <p>事件历史不在这里装：它走 HUD 那一半的增量通道（{@link GameComponent#eventsFor} 是它的全量视图），
 * 客户端读回之后再放进 {@link ExperienceView#events}。
 */
public final class ExperienceProjector {
    private ExperienceProjector() { }

    public static ExperienceView of(GameComponent component, UUID recipient) {
        ExperienceView.Result last = GameComponent.lastResultFor(recipient);
        if (component.session().isEmpty()) {
            return new ExperienceView("", "", List.of(), List.of(), List.of(), 0, List.of(),
                    ExperienceView.BattleReport.NONE, List.of(), List.of(), last, 0);
        }
        Session session = component.requireSession();
        var own = component.seatOf(recipient);
        List<Text> goals = own.map(who -> goals(session, who)).orElse(List.of());
        List<ExperienceView.Playability> availability = new ArrayList<>();
        List<String> committed = List.of();
        if (own.isPresent()) {
            CharacterId who = own.get();
            var state = session.state().stateOf(who);
            LinkedHashSet<String> cards = new LinkedHashSet<>(state.hand());
            cards.addAll(state.front());
            for (String card : cards) {
                for (ExperienceView.Action action : ExperienceView.Action.values()) {
                    availability.add(CardAvailability.view(component, who, card, action));
                }
            }
            committed = session.contest().map(c -> c.committedBy(who)).orElse(List.of());
        }
        int power = committed.stream().mapToInt(card -> session.provisions().get(card).weaponPower()).sum();
        boolean scores = component.endgame().map(e -> e.stage() == EndgameProgress.Stage.SCORES).orElse(false);
        return new ExperienceView(component.matchId(), decisionId(component, own), component.commandContext(recipient), goals, availability, committed, power,
                List.of(), component.lastBattle(), scores ? summaries(component) : List.of(),
                scores && own.isPresent() ? component.scoreExplanation(own.get()) : List.of(), last,
                scores ? component.returnAtMs() : 0L);
    }

    /** Deliberately excludes adjustable deadlines for action and contest windows. */
    private static String decisionId(GameComponent c, java.util.Optional<CharacterId> own) {
        if (own.isEmpty()) return "";
        CharacterId who = own.get(); var s = c.requireSession(); var g = s.state();
        if (g.isOver() || !g.canAct(who)) return "";
        String key = c.matchId() + "/" + g.turn() + "/" + g.phase();
        if (s.contest().isPresent()) {
            var fight = s.contest().orElseThrow();
            boolean owes = c.contestDeadline() > 0 && switch (fight.stage()) {
                case CONSENT -> fight.target().equals(who);
                case PICK -> fight.attacker().equals(who);
                case STANCES -> !c.hasDecided(who) && io.github.heavyseasmc.mod.game.ContestPhase.canJoin(s, fight, who);
                case WEAPONS -> !c.hasDecided(who) && java.util.stream.Stream.concat(g.stateOf(who).hand().stream(),
                        g.stateOf(who).front().stream()).anyMatch(card -> io.github.heavyseasmc.mod.game.ContestPhase.canCommitWeapon(s, fight, who, card));
            };
            return owes ? key + "/contest/" + fight.kind() + "/" + fight.attacker() + "/" + fight.target() + "/" + fight.stage() : "";
        }
        if (s.overboardPending().isPresent()) return c.overboardDeadline() > 0 && !c.hasDecided(who) && !s.overboardPlays(who).isEmpty()
                ? key + "/overboard/" + s.overboardPending().orElseThrow().token() : "";
        if (c.thirstDeadline() > 0 && s.thirstPending().isPresent()) {
            var prompt = s.thirstPending().orElseThrow();
            long ownDonations = c.thirstDonors().stream().filter(who::equals).count();
            boolean owes = prompt.who().equals(who)
                    ? io.github.heavyseasmc.mod.game.ThirstEligibility.canChoose(g.conditionOf(who), s.watersOf(who), prompt.waterPerSource())
                    : !c.hasDecided(who) && s.watersOf(who) > ownDonations && c.thirstDonors().size() < prompt.waterNeeded();
            return owes ? key + "/thirst/" + prompt.who() + "/" + c.thirstSerial() : "";
        }
        if (s.provisionHolder().isPresent()) return s.provisionHolder().filter(who::equals).isPresent()
                ? key + "/provision/" + who + "/" + c.provisionSerial() : "";
        if (c.helmDeadline() > 0) return c.helmSeat(g).filter(who::equals).isPresent() ? key + "/helm/" + who + "/" + c.helmSerial() : "";
        if (s.rower().isPresent()) return s.rower().filter(who::equals).isPresent() ? key + "/row/" + who : "";
        if (c.designating().isPresent()) return c.designating().filter(who::equals).isPresent() ? key + "/designate/" + who : "";
        if (c.provisionTargeter().isPresent()) return c.provisionTargeter().filter(who::equals).isPresent() ? key + "/medical/" + who : "";
        return g.phase() == io.github.heavyseasmc.engine.state.Phase.ACTION && s.nextActor().filter(who::equals).isPresent()
                ? key + "/action/" + who : "";
    }

    public static List<Text> goals(Session session, CharacterId who) {
        if (session.affinities().isEmpty()) return List.of();
        var affinities = session.affinities().orElseThrow();
        var goal = Scorer.goal(session.state().roster(), who, affinities.loveOf(who), affinities.hateOf(who));
        List<Text> lines = new ArrayList<>();
        lines.add(Text.translatable(goal.selfHate() ? "heavyseas.ux.goal.self_hate" : "heavyseas.ux.goal.self", goal.selfSurvival()));
        lines.add(Text.translatable("heavyseas.ux.goal.love", name(goal.love()), goal.lovedSurvival()));
        if (goal.selfHate()) {
            lines.add(Text.translatable("heavyseas.ux.goal.corpses", name(goal.love())));
            for (Scorer.Contribution candidate : goal.corpseCandidates()) {
                lines.add(Text.translatable("heavyseas.ux.goal.corpse_value", name(candidate.who()), candidate.points()));
            }
        } else {
            lines.add(Text.translatable("heavyseas.ux.goal.hate", name(goal.hate()), goal.hatedSize()));
        }
        return List.copyOf(lines);
    }

    public static List<Text> explain(Scorer.Explanation e) {
        var score = e.score(); List<Text> lines = new ArrayList<>();
        String self = e.goal().selfHate() ? "heavyseas.ux.score.self_hate"
                : e.alive() ? "heavyseas.ux.score.self_alive" : "heavyseas.ux.score.self_dead";
        lines.add(Text.translatable(self, score.selfSurvival()));
        lines.add(Text.translatable(e.onBoat() ? "heavyseas.ux.score.treasure_kept" : "heavyseas.ux.score.treasure_removed", score.treasure()));
        lines.add(Text.translatable(e.lovedAlive() ? "heavyseas.ux.score.love_alive" : "heavyseas.ux.score.love_dead",
                name(e.goal().love()), score.loved()));
        if (e.goal().selfHate()) {
            lines.add(Text.translatable("heavyseas.ux.score.corpses", score.hated()));
            for (var corpse : e.corpses()) lines.add(Text.translatable("heavyseas.ux.goal.corpse_value", name(corpse.who()), corpse.points()));
            lines.add(Text.translatable("heavyseas.ux.score.corpses_excluded", name(e.goal().love())));
        } else {
            lines.add(Text.translatable(e.hatedDead() ? "heavyseas.ux.score.hate_dead" : "heavyseas.ux.score.hate_alive",
                    name(e.goal().hate()), score.hated()));
        }
        return List.copyOf(lines);
    }

    public static List<ExperienceView.ScoreSummary> summaries(GameComponent component) {
        var end = component.endgame().orElseThrow();
        var aff = component.requireSession().affinities();
        return end.order().stream().map(who -> {
            var s = end.scores().get(who);
            return new ExperienceView.ScoreSummary(who.value(), s.selfSurvival(), s.treasure(), s.loved(), s.hated(),
                    aff.map(a -> a.hateOf(who).equals(who)).orElse(false));
        }).toList();
    }
    private static Text name(CharacterId id) { return Text.translatable("heavyseas.character." + id.value()); }
}
