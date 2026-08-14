package com.legitcoconut.thanimaticketing.model;

import org.json.JSONObject;

public final class UnpaidEntry {

    public final String id;
    public final String name;
    public final String regNo;
    /** "manual" or "ocr". */
    public final String source;
    public final String createdAt;

    public UnpaidEntry(JSONObject o) {
        id = o.optString("_id", "");
        name = o.optString("name", "");
        regNo = o.optString("regNo", "");
        source = o.optString("source", "manual");
        createdAt = o.isNull("createdAt") ? null : o.optString("createdAt", null);
    }

    public boolean matches(String needle) {
        String q = needle.toLowerCase();
        return name.toLowerCase().contains(q) || regNo.toLowerCase().contains(q);
    }
}
