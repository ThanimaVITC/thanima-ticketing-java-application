package com.legitcoconut.thanimaticketing.card;

/**
 * Anything that can hand back a card UID. The phone's own NFC stack is one, an ESP over
 * Bluetooth is another, and the pool screens do not care which one they were given.
 *
 * Every callback arrives on the main thread.
 */
public interface CardReader {

    interface OnCard {
        void onCard(String uid);
    }

    /**
     * Connection progress, which only the Bluetooth readers really have. NFC reports ready
     * once and never changes.
     */
    interface OnState {
        void onState(String status, boolean ready);
    }

    /** False when this reader could never work here: no NFC chip, no device chosen. */
    boolean isAvailable();

    /** Staff readable reason, or null when the reader is usable. */
    String unavailableReason();

    void setOnState(OnState listener);

    void start(OnCard listener);

    void stop();
}
