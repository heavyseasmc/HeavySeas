package io.github.heavyseasmc.mod.ui;

/** One arrival and one urgency reminder per private decision; independent of which page is open. */
public final class DecisionReminder {
    public enum Signal { NONE, TURN, URGENT }
    private String current = "";
    private boolean warned;

    public Signal update(String identity, boolean required, long remainingMs) {
        if (!required || identity.isEmpty() || remainingMs <= 0) {
            current = "";
            warned = false;
            return Signal.NONE;
        }
        boolean first = !current.equals(identity);
        if (first) {
            current = identity;
            warned = false;
        }
        if (!warned && remainingMs <= 5_000) {
            warned = true;
            return Signal.URGENT;
        }
        return first ? Signal.TURN : Signal.NONE;
    }
}
