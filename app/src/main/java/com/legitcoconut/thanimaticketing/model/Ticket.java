package com.legitcoconut.thanimaticketing.model;

import org.json.JSONObject;

/** Result of POST /tickets/verify. A read only lookup, it never marks attendance. */
public final class Ticket {

    public final String name;
    public final String regNo;
    public final String email;
    public final String phone;
    public final boolean hasAttended;
    public final String attendedAt;
    public final String eventTitle;

    public Ticket(JSONObject o) {
        name = o.optString("name", "");
        regNo = o.optString("regNo", "");
        email = o.optString("email", "");
        phone = o.isNull("phone") ? null : o.optString("phone", null);
        hasAttended = o.optBoolean("hasAttended", false);
        attendedAt = o.isNull("attendedAt") ? null : o.optString("attendedAt", null);
        eventTitle = o.optString("eventTitle", "");
    }
}
