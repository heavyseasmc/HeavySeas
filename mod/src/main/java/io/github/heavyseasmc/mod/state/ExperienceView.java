package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.engine.state.Phase;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Recipient-filtered explanations. No hidden hand or weapon is broadcast through this view.
 *
 * <p>❗{@link #events} 不在这一段线上：播报与私信走 HUD 那一半的增量通道（{@code GameComponent} 的事件历史，
 * 每条只发一次、私信只发本人），客户端读回之后再装进这里。此前这里每次同步都给每人整份重发最多 400 条，
 * 与那条增量播报并行两份。
 *
 * <p>{@link #playability} 只在这个人的那一份变了时才上线（{@link #write} 的 {@code withPlayability}），
 * 没变时客户端沿用上一份（{@link #read} 的 {@code previous}）。
 */
public record ExperienceView(String matchId, String decisionId, String commandContext, List<Text> goals, List<Playability> playability,
                             List<String> committedWeapons, int committedPower, List<Event> events,
                             BattleReport battle, List<ScoreSummary> scores, List<Text> scoreExplanation,
                             Result lastResult, long returnAtMs) {
    public ExperienceView(String matchId, String decisionId, List<Text> goals, List<Playability> playability,
                          List<String> committedWeapons, int committedPower, List<Event> events,
                          BattleReport battle, List<ScoreSummary> scores, List<Text> scoreExplanation,
                          Result lastResult, long returnAtMs) {
        this(matchId, decisionId, "", goals, playability, committedWeapons, committedPower, events, battle,
                scores, scoreExplanation, lastResult, returnAtMs);
    }
    public static final ExperienceView EMPTY = new ExperienceView("", "", List.of(), List.of(), List.of(), 0,
            List.of(), BattleReport.NONE, List.of(), List.of(), Result.NONE, 0);
    public ExperienceView {
        goals = List.copyOf(goals); playability = List.copyOf(playability); committedWeapons = List.copyOf(committedWeapons);
        events = List.copyOf(events); scores = List.copyOf(scores); scoreExplanation = List.copyOf(scoreExplanation);
    }
    public enum Action { PLAY, REVEAL, GIVE_HAND, GIVE_FRONT, OVERBOARD, WEAPON }
    public enum Source { PLAYER, TIMEOUT, DISCONNECT, STAND_IN, ADMIN, RULE }
    public record Playability(String card, Action action, boolean enabled, Text reason) { }
    /** 客户端读回增量播报之后装进来；服务端不经这里发送。 */
    public ExperienceView withEvents(List<Event> value) {
        return new ExperienceView(matchId, decisionId, commandContext, goals, playability, committedWeapons,
                committedPower, value, battle, scores, scoreExplanation, lastResult, returnAtMs);
    }

    public Optional<Playability> availability(String card, Action action) {
        return playability.stream().filter(p -> p.card().equals(card) && p.action() == action).findFirst();
    }
    public record Event(long seq, int turn, Phase phase, String kind, String actor, Source source,
                        Text message, boolean privateEvent) { }
    public record BattleReport(boolean present, String attacker, String target, int attackPower, int defendPower,
                               boolean attackerWon, int damage, List<String> losers,
                               List<String> attackWeapons, List<String> defendWeapons) {
        public static final BattleReport NONE = new BattleReport(false, "", "", 0, 0, false, 0, List.of(), List.of(), List.of());
        public BattleReport {
            losers = List.copyOf(losers); attackWeapons = List.copyOf(attackWeapons); defendWeapons = List.copyOf(defendWeapons);
        }
    }
    public record ScoreSummary(String who, int selfSurvival, int treasure, int loved, int hated, boolean selfHate) {
        public ScoreSummary(String who, int selfSurvival, int treasure, int loved, int hated) {
            this(who, selfSurvival, treasure, loved, hated, false);
        }
        public int total() { return selfSurvival + treasure + loved + hated; }
    }
    /**
     * 最近一局的账（快照）。{@code removed}：那一局被移出游戏的人（落海 · ADR-0022），计分面板照当前局一样把他们淡下去
     * （2026-10-13 第二轮验收 98：「上一局」里死者那两行不是灰的 —— 快照原先不带这一项）。
     */
    public record Result(String matchId, int turn, String outcome, int alive, List<ScoreSummary> scores,
                         String yours, List<Text> explanation, int debugChanges, List<String> removed) {
        public static final Result NONE = new Result("", 0, "", 0, List.of(), "", List.of(), 0, List.of());
        public Result {
            scores = List.copyOf(scores); explanation = List.copyOf(explanation); removed = List.copyOf(removed);
        }
        public boolean present() { return !matchId.isEmpty(); }
    }

    /** @param withPlayability 这个人的可用性变了才写（否则只写一个 false，客户端沿用上一份） */
    public void write(RegistryByteBuf b, boolean withPlayability) {
        b.writeString(matchId); b.writeString(decisionId); b.writeString(commandContext); texts(b, goals);
        b.writeBoolean(withPlayability);
        if (withPlayability) {
            b.writeVarInt(playability.size());
            for (Playability p : playability) {
                b.writeString(p.card());
                b.writeEnumConstant(p.action());
                b.writeBoolean(p.enabled());
                text(b, p.reason());
            }
        }
        strings(b, committedWeapons); b.writeVarInt(committedPower);
        b.writeBoolean(battle.present());
        if (battle.present()) {
            b.writeString(battle.attacker()); b.writeString(battle.target()); b.writeVarInt(battle.attackPower());
            b.writeVarInt(battle.defendPower()); b.writeBoolean(battle.attackerWon()); b.writeVarInt(battle.damage());
            strings(b, battle.losers()); strings(b, battle.attackWeapons()); strings(b, battle.defendWeapons());
        }
        scores(b, scores); texts(b, scoreExplanation);
        b.writeBoolean(lastResult.present());
        if (lastResult.present()) {
            b.writeString(lastResult.matchId()); b.writeVarInt(lastResult.turn()); b.writeString(lastResult.outcome());
            b.writeVarInt(lastResult.alive()); scores(b, lastResult.scores()); b.writeString(lastResult.yours());
            texts(b, lastResult.explanation()); b.writeVarInt(lastResult.debugChanges()); strings(b, lastResult.removed());
        }
        b.writeVarLong(returnAtMs);
    }
    /** @param previous 这一段没带可用性时沿用的上一份 */
    public static ExperienceView read(RegistryByteBuf b, List<Playability> previous) {
        String match = b.readString(), decision = b.readString(), command = b.readString(); List<Text> goals = texts(b);
        List<Playability> plays = previous;
        if (b.readBoolean()) {
            plays = new ArrayList<>();
            for (int n = b.readVarInt(); n > 0; n--) {
                plays.add(new Playability(b.readString(), b.readEnumConstant(Action.class), b.readBoolean(), text(b)));
            }
        }
        List<String> committed = strings(b); int power = b.readVarInt(); List<Event> events = List.of();
        BattleReport battle = b.readBoolean() ? new BattleReport(true, b.readString(), b.readString(), b.readVarInt(),
                b.readVarInt(), b.readBoolean(), b.readVarInt(), strings(b), strings(b), strings(b)) : BattleReport.NONE;
        List<ScoreSummary> scores = scores(b); List<Text> explanation = texts(b);
        Result result = b.readBoolean() ? new Result(b.readString(), b.readVarInt(), b.readString(), b.readVarInt(),
                scores(b), b.readString(), texts(b), b.readVarInt(), strings(b)) : Result.NONE;
        return new ExperienceView(match, decision, command, goals, plays, committed, power, events, battle, scores, explanation, result, b.readVarLong());
    }
    /** 一条播报在增量通道上的写法（{@code GameComponent} 写 HUD 那一半时用）；消息已按收件人序列化好。 */
    public static void writeEvent(RegistryByteBuf b, Event e, String messageJson) {
        b.writeVarLong(e.seq());
        b.writeVarInt(e.turn());
        b.writeEnumConstant(e.phase());
        b.writeString(e.kind());
        b.writeString(e.actor());
        b.writeEnumConstant(e.source());
        b.writeString(messageJson);
        b.writeBoolean(e.privateEvent());
    }

    public static Event readEvent(RegistryByteBuf b) {
        return new Event(b.readVarLong(), b.readVarInt(), b.readEnumConstant(Phase.class), b.readString(),
                b.readString(), b.readEnumConstant(Source.class), text(b), b.readBoolean());
    }

    private static void text(RegistryByteBuf b, Text t) { b.writeString(Text.Serialization.toJsonString(t, b.getRegistryManager())); }
    private static Text text(RegistryByteBuf b) { return java.util.Objects.requireNonNull(Text.Serialization.fromJson(b.readString(), b.getRegistryManager())); }
    private static void texts(RegistryByteBuf b, List<Text> xs) { b.writeVarInt(xs.size()); for (Text t : xs) text(b, t); }
    private static List<Text> texts(RegistryByteBuf b) { List<Text> xs = new ArrayList<>(); for (int n = b.readVarInt(); n > 0; n--) xs.add(text(b)); return xs; }
    private static void strings(RegistryByteBuf b, List<String> xs) { b.writeVarInt(xs.size()); for (String x : xs) b.writeString(x); }
    private static List<String> strings(RegistryByteBuf b) { List<String> xs = new ArrayList<>(); for (int n = b.readVarInt(); n > 0; n--) xs.add(b.readString()); return xs; }
    private static void scores(RegistryByteBuf b, List<ScoreSummary> xs) {
        b.writeVarInt(xs.size()); for (ScoreSummary s : xs) { b.writeString(s.who()); b.writeVarInt(s.selfSurvival()); b.writeVarInt(s.treasure()); b.writeVarInt(s.loved()); b.writeVarInt(s.hated()); b.writeBoolean(s.selfHate()); }
    }
    private static List<ScoreSummary> scores(RegistryByteBuf b) {
        List<ScoreSummary> xs = new ArrayList<>(); for (int n = b.readVarInt(); n > 0; n--) xs.add(new ScoreSummary(b.readString(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBoolean())); return xs;
    }
}
