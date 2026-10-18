package io.github.heavyseasmc.mod.state;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SyncBatcherTest {
    @Test
    void fourOrFifteenRequestsBecomeOneProjection() {
        for (int requests : new int[]{4, 15}) {
            SyncBatcher<String> sync = new SyncBatcher<>();
            List<String> sent = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                sync.mark("world");
            }
            sync.flush("world", sent::add);
            sync.flush("world", sent::add);
            assertEquals(List.of("world"), sent);
        }
    }

    @Test
    void requiredProjectionPrecedesPayloadAndLaterChangesGetAnotherFlush() {
        SyncBatcher<String> sync = new SyncBatcher<>();
        List<String> sent = new ArrayList<>();
        sync.mark("world");
        sync.flush("world", ignored -> sent.add("projection"));
        sent.add("special-payload");
        sync.mark("world");
        sync.flush("world", ignored -> sent.add("end-of-tick"));
        assertEquals(List.of("projection", "special-payload", "end-of-tick"), sent);
    }

    @Test
    void reentrantChangesSurviveAndStoppedWorldsDoNot() {
        SyncBatcher<String> sync = new SyncBatcher<>();
        List<String> sent = new ArrayList<>();
        sync.mark("one");
        sync.mark("stopped");
        sync.flush("one", key -> { sent.add(key); sync.mark(key); });
        sync.forget("stopped"::equals);
        sync.flush("stopped", sent::add);
        sync.flush("one", sent::add);
        assertEquals(List.of("one", "one"), sent);
    }
}
