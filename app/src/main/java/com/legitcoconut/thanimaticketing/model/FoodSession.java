package com.legitcoconut.thanimaticketing.model;

import org.json.JSONObject;

public final class FoodSession {

    public final String id;
    public final String name;
    /** Soft warning threshold. */
    public final int limit;
    /** Hard cap. */
    public final int maxLimit;
    public final boolean isVisible;
    public final int count;

    public final int admitted;
    public final int remainingToLimit;
    public final int remainingToMax;
    public final boolean nearLimit;
    public final boolean full;

    public FoodSession(JSONObject o) {
        id = o.optString("_id", "");
        name = o.optString("name", "Session");
        limit = o.optInt("limit", 0);
        maxLimit = o.optInt("maxLimit", 0);
        isVisible = o.optBoolean("isVisible", true);
        count = o.optInt("count", 0);

        JSONObject s = o.optJSONObject("stats");
        if (s == null) s = new JSONObject();
        admitted = s.optInt("admitted", count);
        remainingToLimit = s.optInt("remainingToLimit", Math.max(0, limit - count));
        remainingToMax = s.optInt("remainingToMax", Math.max(0, maxLimit - count));
        nearLimit = s.optBoolean("nearLimit", limit > 0 && count >= limit);
        full = s.optBoolean("full", maxLimit > 0 && count >= maxLimit);
    }

    /** 0 to 100, against the hard cap. */
    public int percentOfMax() {
        if (maxLimit <= 0) return 0;
        return Math.min(100, admitted * 100 / maxLimit);
    }
}
