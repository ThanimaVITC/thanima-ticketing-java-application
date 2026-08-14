package com.legitcoconut.thanimaticketing.model;

import org.json.JSONObject;

public final class Event {

    public final String id;
    public final String title;
    public final String description;
    public final String date;
    /** Read by the client, absent from the server schema, so always null in practice. */
    public final String location;
    /**
     * Presigned GET URL for the event logo, valid 30 minutes, absent when no logo is set.
     * Never persisted: re-fetch the event once it expires.
     */
    public final String logoPath;
    public final boolean foodSessionsEnabled;
    public final boolean userPoolEnabled;
    public final boolean unpaidEnabled;

    public Event(JSONObject o) {
        id = o.optString("_id", o.optString("id", ""));
        title = o.optString("title", "Untitled event");
        description = nullable(o, "description");
        date = nullable(o, "date");
        location = nullable(o, "location");
        logoPath = nullable(o, "logoPath");
        foodSessionsEnabled = o.optBoolean("foodSessionsEnabled", false);
        userPoolEnabled = o.optBoolean("userPoolEnabled", false);
        unpaidEnabled = o.optBoolean("unpaidEnabled", false);
    }

    private static String nullable(JSONObject o, String key) {
        if (o.isNull(key)) return null;
        String v = o.optString(key, "");
        return v.isEmpty() ? null : v;
    }
}
