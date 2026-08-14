package com.legitcoconut.thanimaticketing.model;

import org.json.JSONObject;

public final class Registration {

    public final String id;
    public final String name;
    public final String regNo;
    public final String email;
    public final String phone;
    public final String qrPayload;
    public final String emailStatus;
    public boolean attended;
    public String markedAt;
    public final String attendanceSource;

    public Registration(JSONObject o) {
        id = o.optString("_id", "");
        name = o.optString("name", "");
        regNo = o.optString("regNo", "");
        email = o.optString("email", "");
        phone = o.isNull("phone") ? null : o.optString("phone", null);
        qrPayload = o.isNull("qrPayload") ? null : o.optString("qrPayload", null);
        emailStatus = o.optString("emailStatus", "pending");
        attended = o.optBoolean("attended", false);
        JSONObject att = o.optJSONObject("attendance");
        markedAt = att == null ? null : att.optString("markedAt", null);
        attendanceSource = att == null ? null : att.optString("source", null);
    }

    public boolean hasTicket() {
        return qrPayload != null && !qrPayload.isEmpty();
    }

    public boolean matches(String needle) {
        String q = needle.toLowerCase();
        return name.toLowerCase().contains(q)
                || regNo.toLowerCase().contains(q)
                || email.toLowerCase().contains(q);
    }
}
