package com.legitcoconut.thanimaticketing.net;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.legitcoconut.thanimaticketing.BuildConfig;
import com.legitcoconut.thanimaticketing.model.Event;
import com.legitcoconut.thanimaticketing.model.FoodSession;
import com.legitcoconut.thanimaticketing.model.PoolEntry;
import com.legitcoconut.thanimaticketing.model.Registration;
import com.legitcoconut.thanimaticketing.model.Ticket;
import com.legitcoconut.thanimaticketing.model.UnpaidEntry;
import com.legitcoconut.thanimaticketing.model.User;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Every network call the app makes. No screen talks to HttpURLConnection directly.
 *
 * Two conventions, straight from the API docs:
 *   1. Most methods deliver a parsed model, or an error string on any non 200.
 *   2. The outcome carrying ones deliver a {@link Res} for every status code, because
 *      their 4xx bodies mean something the UI renders differently. Those are the two
 *      attendance calls, assignFoodSlot, scanFoodSession, the three user pool calls
 *      and addUnpaid.
 */
public final class Api {

    private static final String CONNECT_FAIL = "Could not connect to server. Check URL and internet.";

    private static final ExecutorService IO = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Object> CACHE = new ConcurrentHashMap<>();

    private Api() {
    }

    public static void init(Context context) {
        Store.init(context);
    }

    // ------------------------------------------------------------------ base URL

    /** Trims, drops a trailing slash and appends /api when it is missing. */
    public static String normalizeUrl(String raw) {
        String u = raw == null ? "" : raw.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.isEmpty()) return BuildConfig.DEFAULT_BASE_URL;
        if (!u.endsWith("/api")) u = u + "/api";
        return u;
    }

    public static String baseUrl() {
        String stored = Store.get(Store.KEY_SERVER_URL, null);
        if (stored != null && !stored.isEmpty()) return stored;
        return BuildConfig.DEFAULT_BASE_URL;
    }

    public static void setServerUrl(String raw) {
        Store.put(Store.KEY_SERVER_URL, normalizeUrl(raw));
    }

    // ------------------------------------------------------------------ session

    public static boolean hasToken() {
        return Store.has(Store.KEY_TOKEN);
    }

    public static User cachedUser() {
        String raw = Store.get(Store.KEY_USER, null);
        if (raw == null) return null;
        try {
            return new User(new JSONObject(raw));
        } catch (JSONException e) {
            return null;
        }
    }

    public static void logout() {
        Store.remove(Store.KEY_TOKEN);
        Store.remove(Store.KEY_USER);
        Store.clearCache();
        CACHE.clear();
    }

    // ------------------------------------------------------------------ cache

    /**
     * Last value a read method produced, for stale while revalidate rendering.
     *
     * Falls back to the disk copy on a miss, so a cold start paints the events list and the
     * event page immediately instead of showing an empty screen while the network answers.
     */
    @SuppressWarnings("unchecked")
    public static <T> T peek(String key) {
        Object value = CACHE.get(key);
        if (value == null) {
            value = restore(key);
            if (value != null) CACHE.put(key, value);
        }
        return (T) value;
    }

    private static <T> T cache(String key, T value) {
        CACHE.put(key, value);
        return value;
    }

    /** Above this a response is left in memory only, rather than bloating the preferences file. */
    private static final int MAX_PERSISTED_CHARS = 512 * 1024;

    private static void persist(String key, JSONObject body) {
        String raw = body.toString();
        if (raw.length() > MAX_PERSISTED_CHARS) return;
        Store.put("cache:" + key, raw);
    }

    /** Rebuilds a cached response from disk. Only the two cold start screens are stored. */
    private static Object restore(String key) {
        String raw = Store.get("cache:" + key, null);
        if (raw == null) return null;
        try {
            JSONObject body = new JSONObject(raw);
            if ("events".equals(key)) {
                JSONArray a = body.optJSONArray("events");
                List<Event> out = new ArrayList<>();
                if (a != null) for (int i = 0; i < a.length(); i++) out.add(new Event(a.getJSONObject(i)));
                return out;
            }
            if (key.startsWith("event:")) {
                return new EventDetails(body);
            }
        } catch (JSONException ignored) {
        }
        return null;
    }

    /** The unpaid list is the only one that survives a cold start. */
    public static List<UnpaidEntry> unpaidFromDisk(String eventId) {
        String raw = Store.get("cache:unpaid:" + eventId, null);
        if (raw == null) return null;
        try {
            return unpaidList(new JSONArray(raw));
        } catch (JSONException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ transport

    private interface Call<T> {
        T get() throws Exception;
    }

    private static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }
    }

    private static <T> void run(Call<T> call, Cb<T> cb) {
        IO.execute(() -> {
            T value = null;
            String error = null;
            try {
                value = call.get();
            } catch (Failure f) {
                error = f.getMessage();
            } catch (Exception e) {
                error = friendly(e);
            }
            final T v = value;
            final String err = error;
            MAIN.post(() -> cb.done(v, err));
        });
    }

    private static String friendly(Exception e) {
        if (e instanceof UnknownHostException || e instanceof ConnectException
                || e instanceof SocketTimeoutException || e instanceof SocketException) {
            return CONNECT_FAIL;
        }
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? "Something went wrong. Try again." : m;
    }

    private static Res raw(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl() + path).openConnection();
        try {
            c.setConnectTimeout(12000);
            c.setReadTimeout(25000);
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json");
            String token = Store.get(Store.KEY_TOKEN, null);
            if (token != null && !token.isEmpty()) {
                c.setRequestProperty("Cookie", "auth-token=" + token);
            }
            if (body != null) {
                byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(out.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(out);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Res(code, decode(readAll(in), code));
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString(StandardCharsets.UTF_8.name());
    }

    /** Guards against a server that answers a missing route with an HTML 404 page. */
    private static JSONObject decode(String text, int code) {
        String t = text.trim();
        if (t.startsWith("{")) {
            try {
                return new JSONObject(t);
            } catch (JSONException ignored) {
            }
        }
        JSONObject o = new JSONObject();
        try {
            o.put("error", code == 404
                    ? "This feature is not available on the server yet"
                    : "Unexpected response (HTTP " + code + ")");
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** Throwing family: anything but 2xx becomes an error string for the screen. */
    private static Res expect(Res r, String fallback) throws Failure {
        if (!r.ok()) throw new Failure(r.error(fallback));
        return r;
    }

    // ------------------------------------------------------------------ auth

    public static void login(String email, String password, Cb<User> cb) {
        run(() -> {
            JSONObject body = new JSONObject().put("email", email).put("password", password);
            Res r = expect(raw("POST", "/auth/login", body), "Login failed");
            String token = r.data.optString("token", "");
            if (token.isEmpty()) throw new Failure("Login failed: no token returned");
            Store.put(Store.KEY_TOKEN, token);
            JSONObject u = r.obj("user");
            Store.put(Store.KEY_USER, u.toString());
            return new User(u);
        }, cb);
    }

    /** Startup check. Any failure means logged out, the screen does not show the message. */
    public static void getMe(Cb<User> cb) {
        run(() -> {
            Res r = expect(raw("GET", "/auth/me", null), "Not signed in");
            JSONObject u = r.obj("user");
            Store.put(Store.KEY_USER, u.toString());
            return new User(u);
        }, cb);
    }

    // ------------------------------------------------------------------ events

    public static void getEvents(Cb<List<Event>> cb) {
        run(() -> {
            Res r = expect(raw("GET", "/events?page=1&limit=100", null), "Failed to load events");
            JSONArray a = r.data.optJSONArray("events");
            List<Event> out = new ArrayList<>();
            if (a != null) for (int i = 0; i < a.length(); i++) out.add(new Event(a.getJSONObject(i)));
            persist("events", r.data);
            return cache("events", out);
        }, cb);
    }

    /** Event, every registration with attendance joined in, and the counters. */
    public static final class EventDetails {
        public final Event event;
        public final List<Registration> registrations = new ArrayList<>();
        public final int totalRegistrations;
        public final int totalAttendance;
        public final int attendanceRate;
        public final int foodAssignedCount;
        public final int foodAssignedRate;
        public final int userPoolCount;
        public final int unpaidCount;

        EventDetails(JSONObject o) throws JSONException {
            event = new Event(o.optJSONObject("event") == null ? new JSONObject() : o.getJSONObject("event"));
            JSONArray a = o.optJSONArray("registrations");
            if (a != null) for (int i = 0; i < a.length(); i++) registrations.add(new Registration(a.getJSONObject(i)));
            JSONObject s = o.optJSONObject("stats");
            if (s == null) s = new JSONObject();
            totalRegistrations = s.optInt("totalRegistrations", registrations.size());
            totalAttendance = s.optInt("totalAttendance", 0);
            attendanceRate = s.optInt("attendanceRate", 0);
            foodAssignedCount = s.optInt("foodAssignedCount", 0);
            foodAssignedRate = s.optInt("foodAssignedRate", 0);
            userPoolCount = s.optInt("userPoolCount", 0);
            unpaidCount = s.optInt("unpaidCount", 0);
        }
    }

    public static void getEventDetails(String eventId, Cb<EventDetails> cb) {
        run(() -> {
            Res r = expect(raw("GET", "/events/" + eventId, null), "Failed to load event details");
            persist("event:" + eventId, r.data);
            return cache("event:" + eventId, new EventDetails(r.data));
        }, cb);
    }

    // ------------------------------------------------------------------ attendance

    /** Manual name tap marking. The server only authenticates this route for source web. */
    /**
     * Outcome carrying. Both marking calls ride back a "food" block carrying the live
     * session list and whatever colour this attendee already holds, and it is present on
     * the 409 already marked reply too — so someone marked before the sessions went live
     * still gets the colour picker on a re-scan.
     */
    public static void markAttendance(String eventId, String email, Cb<Res> cb) {
        run(() -> {
            JSONObject body = new JSONObject()
                    .put("eventId", eventId).put("email", email).put("source", "mobile");
            return raw("POST", "/attendance/mark", body);
        }, cb);
    }

    /** QR marking. Resolves the ticket and marks in one step. Outcome carrying, as above. */
    public static void verifyQrAttendance(String encryptedData, String eventId, Cb<Res> cb) {
        run(() -> {
            JSONObject body = new JSONObject()
                    .put("encryptedData", encryptedData).put("eventId", eventId);
            return raw("POST", "/attendance/verify-qr", body);
        }, cb);
    }

    // ------------------------------------------------------------------ registrations

    public static void getRegistrations(String eventId, Cb<List<Registration>> cb) {
        run(() -> {
            Res r = expect(raw("GET", "/registrations/" + eventId, null), "Failed to load registrations");
            JSONArray a = r.data.optJSONArray("registrations");
            List<Registration> out = new ArrayList<>();
            if (a != null) for (int i = 0; i < a.length(); i++) out.add(new Registration(a.getJSONObject(i)));
            return cache("regs:" + eventId, out);
        }, cb);
    }

    public static void assignQrPayload(String eventId, String registrationId, String qrPayload,
                                       Cb<JSONObject> cb) {
        run(() -> {
            JSONObject body = new JSONObject()
                    .put("registrationId", registrationId).put("qrPayload", qrPayload);
            Res r = expect(raw("PUT", "/registrations/" + eventId + "/assign-qr", body),
                    "Failed to assign the ticket");
            return r.obj("registration");
        }, cb);
    }

    /**
     * Duplicate guard for ticket assignment. The route does not exist on the server yet,
     * so it answers with an HTML 404 and this reports "free to assign", which is what the
     * Flutter app does too. Delivers the holder name when the ticket is already taken,
     * null when it is free.
     */
    public static void checkQrPayloadExists(String eventId, String qrPayload, Cb<String> cb) {
        run(() -> {
            Res r = raw("GET", "/registrations/" + eventId + "/check-qr?qrPayload="
                    + Uri.encode(qrPayload), null);
            if (r.code == 200 && r.flag("exists")) return r.data.optString("name", "someone else");
            return null;
        }, cb);
    }

    // ------------------------------------------------------------------ food

    public static void getFoodSessions(String eventId, Cb<List<FoodSession>> cb) {
        run(() -> {
            Res r = expect(raw("GET", "/events/" + eventId + "/food-sessions?activeOnly=1", null),
                    "Failed to load food sessions");
            return cache("food:" + eventId, foodSessions(r.data.optJSONArray("sessions")));
        }, cb);
    }

    /** Reads a sessions array from either the list endpoint or an attendance food block. */
    public static List<FoodSession> foodSessions(JSONArray a) throws JSONException {
        List<FoodSession> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(new FoodSession(a.getJSONObject(i)));
        return out;
    }

    /**
     * Hands an attendee a colour. Outcome carrying: 409 full and 409 alreadyAssigned are
     * normal answers the picker redraws from, not errors.
     *
     * There is deliberately no call for moving someone between colours. That is a dashboard
     * job, and leaving it out of this class is what keeps it one.
     */
    public static void assignFoodSlot(String eventId, String email, String sessionId, Cb<Res> cb) {
        run(() -> raw("POST", "/events/" + eventId + "/food-assignments",
                new JSONObject().put("email", email).put("foodSessionId", sessionId)), cb);
    }

    /** Outcome carrying. 409 wrongSession, noAssignment and alreadyServed are normal answers. */
    public static void scanFoodSession(String eventId, String sessionId, String encryptedData,
                                       Cb<Res> cb) {
        run(() -> raw("POST", "/events/" + eventId + "/food-sessions/" + sessionId + "/scan",
                new JSONObject().put("encryptedData", encryptedData)), cb);
    }

    // ------------------------------------------------------------------ user pool

    public static final class PoolResult {
        public final List<PoolEntry> entries = new ArrayList<>();
        public final int currentCount;
        public final int totalVisits;
        public final int uniqueUsers;

        PoolResult(JSONObject o) throws JSONException {
            JSONArray a = o.optJSONArray("entries");
            if (a != null) for (int i = 0; i < a.length(); i++) entries.add(new PoolEntry(a.getJSONObject(i)));
            JSONObject s = o.optJSONObject("stats");
            if (s == null) s = new JSONObject();
            currentCount = s.optInt("currentCount", 0);
            totalVisits = s.optInt("totalVisits", entries.size());
            uniqueUsers = s.optInt("uniqueUsers", 0);
        }
    }

    /** status is "active" for who is in now, "all" for the full history. */
    public static void getUserPool(String eventId, String status, Cb<PoolResult> cb) {
        run(() -> {
            Res r = expect(raw("GET", "/events/" + eventId + "/user-pool?status=" + status, null),
                    "Failed to load the user pool");
            return cache("pool:" + eventId + ":" + status, new PoolResult(r.data));
        }, cb);
    }

    /** Outcome carrying. 409 alreadyInPool is an answer the screen renders, not an error. */
    public static void addToUserPool(String eventId, String encryptedData, Cb<Res> cb) {
        run(() -> raw("POST", "/events/" + eventId + "/user-pool/add",
                new JSONObject().put("encryptedData", encryptedData)), cb);
    }

    /** Outcome carrying. A 404 with found false means that person is simply not in the pool. */
    public static void lookupPoolByTicket(String eventId, String encryptedData, Cb<Res> cb) {
        run(() -> raw("GET", "/events/" + eventId + "/user-pool/lookup?encryptedData="
                + Uri.encode(encryptedData), null), cb);
    }

    /** Outcome carrying. 409 alreadyRemoved means someone else already signed them out. */
    public static void removeFromUserPool(String eventId, String entryId, Cb<Res> cb) {
        run(() -> raw("POST", "/events/" + eventId + "/user-pool/remove",
                new JSONObject().put("entryId", entryId)), cb);
    }

    // ------------------------------------------------------------------ unpaid

    private static List<UnpaidEntry> unpaidList(JSONArray a) throws JSONException {
        List<UnpaidEntry> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(new UnpaidEntry(a.getJSONObject(i)));
        return out;
    }

    public static void getUnpaid(String eventId, Cb<List<UnpaidEntry>> cb) {
        run(() -> {
            Res r = expect(raw("GET", "/events/" + eventId + "/unpaid", null),
                    "Failed to load the unpaid list");
            JSONArray a = r.data.optJSONArray("entries");
            Store.put("cache:unpaid:" + eventId, a == null ? "[]" : a.toString());
            return cache("unpaid:" + eventId, unpaidList(a));
        }, cb);
    }

    /** Outcome carrying. 201 or ok true means added, 409 alreadyListed is a no op. */
    public static void addUnpaid(String eventId, String name, String regNo, String source, Cb<Res> cb) {
        run(() -> raw("POST", "/events/" + eventId + "/unpaid",
                new JSONObject().put("name", name).put("regNo", regNo).put("source", source)), cb);
    }

    // ------------------------------------------------------------------ tickets

    /** Read only lookup. Never marks attendance. */
    public static void verifyTicket(String qrPayload, String eventId, Cb<Ticket> cb) {
        run(() -> {
            JSONObject body = new JSONObject().put("qrPayload", qrPayload).put("eventId", eventId);
            Res r = expect(raw("POST", "/tickets/verify", body), "Failed to verify the ticket");
            JSONObject t = r.data.optJSONObject("ticket");
            if (t == null) throw new Failure("Invalid ticket data received");
            return new Ticket(t);
        }, cb);
    }
}
