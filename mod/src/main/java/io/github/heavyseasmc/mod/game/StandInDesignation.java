package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.state.GameComponent;
import net.minecraft.server.world.ServerWorld;

/** Stand-ins announce before selecting a target, without exposing that target early. */
final class StandInDesignation {
    private StandInDesignation() { }

    static long delay(boolean humans, boolean quiet, boolean fast) {
        return !humans || quiet ? 0L : fast ? 300L : 1_500L;
    }

    static void begin(ServerWorld world, GameComponent component, CharacterId actor,
                      Contest.Kind kind, CharacterId target) {
        Session session = component.requireSession();
        long pause = delay(component.anyHumanSeated(), quiet(session, actor, kind), component.dummyFast());
        if (pause == 0L) {
            ContestPhase.declare(world, component, actor, kind, target);
            return;
        }
        DesignationPhase.begin(world, component, actor, kind);
        Cue cue = new Cue(session, session.state().turn(), actor, kind, target, component.designationSerial());
        GameFlow.schedule(component, pause, "替身指定目标", () -> fire(cue, component,
                () -> DesignationPhase.finish(world, component, actor),
                () -> ActionPhase.passForStandIn(world, component, actor),
                () -> ContestPhase.declare(world, component, actor, kind, target)));
    }

    /**
     * 到点那一下：窗没换才收起指定模式，行动者还该出手才出手。
     * 单独拆出来只为单测能走到这两道闸（三件出手要世界，由调用方传进来）；{@code cue} 里的局面与目标就是开窗时那一份。
     */
    static void fire(Cue cue, GameComponent component, Runnable finish, Runnable pass, Runnable declare) {
        if (!cue.sameWindow(component)) return;
        finish.run();
        if (!cue.actorReady()) return;
        if (cue.session().state().isRemoved(cue.target())) {
            pass.run();
        } else {
            declare.run();
        }
    }

    static boolean quiet(Session session, CharacterId actor, Contest.Kind kind) {
        return kind == Contest.Kind.STEAL && session.stealsUncontested(actor);
    }

    record Cue(Session session, int turn, CharacterId actor, Contest.Kind kind, CharacterId target, long serial) {
        boolean sameWindow(GameComponent component) {
            return component.session().orElse(null) == session && component.designationSerial() == serial
                    && component.designating().map(actor::equals).orElse(false)
                    && component.designationKind().filter(kind::equals).isPresent();
        }

        boolean actorReady() {
            return !session.state().isOver() && session.state().turn() == turn && session.state().phase() == Phase.ACTION
                    && session.state().canAct(actor) && session.nextActor().filter(actor::equals).isPresent()
                    && session.contest().isEmpty() && session.rower().isEmpty();
        }
    }
}
