package com.legitcoconut.thanimaticketing.model;

import android.graphics.Color;

import org.json.JSONObject;

/**
 * One food sitting, identified by its colour rather than a name. The server resolves the
 * palette and sends the name and hex with every session, so this app never carries its own
 * copy of the colour list.
 *
 * {@code admitted} counts people <em>assigned</em> this colour at the door, not people fed —
 * a seat is taken when the colour is handed out.
 */
public final class FoodSession {

    public final String id;
    public final String color;
    public final String colorName;
    /** Parsed from the server's hex, falling back to grey if it is ever unreadable. */
    public final int colorInt;
    /** Soft warning threshold. */
    public final int limit;
    /** Hard cap. */
    public final int maxLimit;
    public final boolean isVisible;
    public final int count;
    /** How many of this colour have actually eaten. Counted server side, display only. */
    public final int served;

    public final int admitted;
    public final int remainingToLimit;
    public final int remainingToMax;
    public final boolean nearLimit;
    public final boolean full;

    public FoodSession(JSONObject o) {
        // The sessions list uses _id; the food block on an attendance response uses id.
        String rawId = o.optString("_id", "");
        id = rawId.isEmpty() ? o.optString("id", "") : rawId;
        color = o.optString("color", "");
        colorName = o.optString("colorName", color);
        colorInt = parseColor(o.optString("colorHex", ""));
        limit = o.optInt("limit", 0);
        maxLimit = o.optInt("maxLimit", 0);
        isVisible = o.optBoolean("isVisible", true);
        count = o.optInt("count", 0);
        served = o.optInt("served", 0);

        JSONObject s = o.optJSONObject("stats");
        if (s == null) s = new JSONObject();
        admitted = s.optInt("admitted", count);
        remainingToLimit = s.optInt("remainingToLimit", Math.max(0, limit - count));
        remainingToMax = s.optInt("remainingToMax", Math.max(0, maxLimit - count));
        nearLimit = s.optBoolean("nearLimit", limit > 0 && count >= limit);
        full = s.optBoolean("full", maxLimit > 0 && count >= maxLimit);
    }

    private static int parseColor(String hex) {
        try {
            return Color.parseColor(hex);
        } catch (IllegalArgumentException | NullPointerException e) {
            return 0xFF64748B;
        }
    }

    /** 0 to 100, against the hard cap. */
    public int percentOfMax() {
        if (maxLimit <= 0) return 0;
        return Math.min(100, admitted * 100 / maxLimit);
    }
}
