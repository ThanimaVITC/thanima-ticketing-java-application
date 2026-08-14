package com.legitcoconut.thanimaticketing.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class User {

    public final String id;
    public final String name;
    public final String email;
    public final String role;
    public final List<String> assignedEvents = new ArrayList<>();

    public User(JSONObject o) {
        id = o.optString("id", o.optString("_id", ""));
        name = o.optString("name", "");
        email = o.optString("email", "");
        role = o.optString("role", "app_user");
        JSONArray a = o.optJSONArray("assignedEvents");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) assignedEvents.add(a.optString(i));
        }
    }

    /** Staff readable role, for the profile screen. The server does the real gating. */
    public String roleLabel() {
        switch (role) {
            case "admin":
                return "Admin";
            case "event_admin":
                return "Event admin";
            default:
                return "Volunteer";
        }
    }

    public String initials() {
        if (name == null || name.trim().isEmpty()) return "?";
        String[] parts = name.trim().split("\\s+");
        String s = parts[0].substring(0, 1);
        if (parts.length > 1) s += parts[parts.length - 1].substring(0, 1);
        return s.toUpperCase();
    }
}
