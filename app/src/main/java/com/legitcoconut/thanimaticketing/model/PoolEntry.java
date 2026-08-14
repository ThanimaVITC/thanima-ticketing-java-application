package com.legitcoconut.thanimaticketing.model;

import com.legitcoconut.thanimaticketing.util.Ui;

import org.json.JSONObject;

public final class PoolEntry {

    public final String id;
    public final String registrationId;
    public final String name;
    public final String regNo;
    public final String email;
    public final String phone;
    public final String nfcId;
    public final String enteredAt;
    public final String exitedAt;
    private final long serverDurationMs;
    private final long enteredAtMillis;

    public PoolEntry(JSONObject o) {
        id = o.optString("_id", "");
        registrationId = o.optString("registrationId", "");
        name = o.optString("name", "");
        regNo = o.optString("regNo", "");
        email = o.optString("email", "");
        phone = o.isNull("phone") ? null : o.optString("phone", null);
        nfcId = o.optString("nfcId", "");
        enteredAt = o.isNull("enteredAt") ? null : o.optString("enteredAt", null);
        exitedAt = o.isNull("exitedAt") ? null : o.optString("exitedAt", null);
        serverDurationMs = o.optLong("durationMs", 0L);
        enteredAtMillis = Ui.parseIso(enteredAt);
    }

    public boolean isInPool() {
        return exitedAt == null;
    }

    /**
     * Recomputed locally for an active stay so the timer ticks without re-fetching,
     * exactly like the Flutter model does. A finished stay keeps the server value.
     */
    public long timeInPoolMs() {
        if (!isInPool() || enteredAtMillis <= 0) return serverDurationMs;
        return Math.max(0, System.currentTimeMillis() - enteredAtMillis);
    }

    public String timeInPool() {
        return Ui.formatDuration(timeInPoolMs());
    }
}
