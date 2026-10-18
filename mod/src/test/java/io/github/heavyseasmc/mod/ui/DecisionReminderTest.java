package io.github.heavyseasmc.mod.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DecisionReminderTest {
    @Test
    void oneArrivalAndOneUrgencySurviveOpeningOtherPages() {
        DecisionReminder reminder = new DecisionReminder();
        assertEquals(DecisionReminder.Signal.TURN, reminder.update("a", true, 20_000));
        assertEquals(DecisionReminder.Signal.NONE, reminder.update("a", true, 18_000));
        assertEquals(DecisionReminder.Signal.URGENT, reminder.update("a", true, 5_000));
        assertEquals(DecisionReminder.Signal.NONE, reminder.update("a", true, 4_500));
        assertEquals(DecisionReminder.Signal.NONE, reminder.update("a", true, 8_000));
        assertEquals(DecisionReminder.Signal.NONE, reminder.update("a", true, 3_000));
        assertEquals(DecisionReminder.Signal.TURN, reminder.update("b", true, 60_000));
    }

    @Test
    void lateArrivalDoesNotPlayTwoSoundsAndNonRecipientsNeverHearReminder() {
        DecisionReminder reminder = new DecisionReminder();
        assertEquals(DecisionReminder.Signal.NONE, reminder.update("other", false, 3_000));
        assertEquals(DecisionReminder.Signal.URGENT, reminder.update("a", true, 3_000));
        assertEquals(DecisionReminder.Signal.NONE, reminder.update("a", true, 0));
        assertEquals(DecisionReminder.Signal.TURN, reminder.update("demo", true, Long.MAX_VALUE));
        assertEquals(DecisionReminder.Signal.NONE, reminder.update("demo", true, Long.MAX_VALUE - 100));
    }
}
