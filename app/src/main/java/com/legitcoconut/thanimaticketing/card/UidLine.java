package com.legitcoconut.thanimaticketing.card;

import android.os.SystemClock;

/**
 * Turns one line of reader output into the exact spelling the phone's NFC stack produces:
 * uppercase hex, no separators. Anything that is not a card ID returns null, so boot
 * messages and heartbeats from the ESP fall on the floor instead of reaching the server.
 */
public final class UidLine {

    /** 4, 7 and 10 byte card IDs, which is every ISO 14443 UID length. */
    private static final int MIN_HEX = 8;
    private static final int MAX_HEX = 20;

    private UidLine() {
    }

    public static String parse(String line, boolean reverse) {
        if (line == null) return null;

        StringBuilder hex = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ' || c == ':' || c == '-' || c == '\t' || c == '\r' || c == '\n') continue;
            // One non hex character means this line is chatter, not a card.
            if (Character.digit(c, 16) < 0) return null;
            hex.append(Character.toUpperCase(c));
        }

        int n = hex.length();
        if (n < MIN_HEX || n > MAX_HEX || n % 2 != 0) return null;
        if (!reverse) return hex.toString();

        StringBuilder out = new StringBuilder(n);
        for (int i = n - 2; i >= 0; i -= 2) out.append(hex, i, i + 2);
        return out.toString();
    }

    /**
     * A reader keeps firing while the card sits on it. One tap should be one event, so the
     * same ID inside a two second window is ignored.
     */
    public static final class Repeat {

        private static final long WINDOW_MS = 2000;

        private String last;
        private long at;

        public boolean isRepeat(String uid) {
            long now = SystemClock.uptimeMillis();
            boolean same = uid.equals(last) && now - at < WINDOW_MS;
            last = uid;
            at = now;
            return same;
        }

        public void clear() {
            last = null;
            at = 0;
        }
    }
}
