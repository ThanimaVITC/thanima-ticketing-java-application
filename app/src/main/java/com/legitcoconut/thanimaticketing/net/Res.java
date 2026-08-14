package com.legitcoconut.thanimaticketing.net;

import org.json.JSONObject;

/**
 * The second response convention from the API docs: status code plus body, handed to
 * the screen unthrown, because the 4xx bodies carry outcomes the UI renders
 * differently (alreadyScanned, alreadyInPool, cardInUse, found false, alreadyListed).
 */
public final class Res {

    public final int code;
    public final JSONObject data;

    public Res(int code, JSONObject data) {
        this.code = code;
        this.data = data == null ? new JSONObject() : data;
    }

    public boolean ok() {
        return code >= 200 && code < 300;
    }

    /** True when the body carries this boolean flag set to true. */
    public boolean flag(String name) {
        return data.optBoolean(name, false);
    }

    public String error(String fallback) {
        String e = data.optString("error", null);
        if (e == null || e.isEmpty() || "null".equals(e)) {
            e = data.optString("message", null);
        }
        return (e == null || e.isEmpty() || "null".equals(e)) ? fallback : e;
    }

    public JSONObject obj(String name) {
        JSONObject o = data.optJSONObject(name);
        return o == null ? new JSONObject() : o;
    }
}
