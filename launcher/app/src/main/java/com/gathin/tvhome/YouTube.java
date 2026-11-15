package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube accounts through the YouTube TV sign-in (the code you type at google.com/device) and its private API
 * ("InnerTube"), as the YouTube app on TVs does. Unofficial: Nicolas agreed to it in writing on 15/11/2026.
 *
 * Nothing secret is stored in the code: the TV client id is read from youtube.com/tv at run time, and the
 * accounts' tokens stay in this app's private preferences on the stick.
 */
final class YouTube {

    static final class Account {
        String name;
        String refresh;
        String access;
        long expiry;
    }

    private static final String TAG = "tvhome";
    private static final String PREFS = "tvhome";
    private static final String PREF_ACCOUNTS = "yt_accounts";
    private static final String PREF_CLIENT = "yt_client";
    private static final String TV_UA = "Mozilla/5.0 (Linux; Tizen 2.3) AppleWebKit/538.1 (KHTML, like Gecko)Version/2.3 TV Safari/538.1";
    private static final String CLIENT_VERSION = "7.20250101.19.00";

    private final Context ctx;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;

    YouTube(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    // ---- accounts ------------------------------------------------------------------------------------

    synchronized List<Account> accounts() {
        List<Account> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs().getString(PREF_ACCOUNTS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Account acc = new Account();
                acc.name = o.optString("name");
                acc.refresh = o.optString("refresh");
                acc.access = o.optString("access");
                acc.expiry = o.optLong("expiry");
                out.add(acc);
            }
        } catch (Exception ignored) {
            // Unreadable list: behave as signed out.
        }
        return out;
    }

    private synchronized void save(List<Account> list) {
        JSONArray a = new JSONArray();
        try {
            for (Account acc : list) {
                a.put(new JSONObject().put("name", acc.name).put("refresh", acc.refresh)
                        .put("access", acc.access).put("expiry", acc.expiry));
            }
        } catch (Exception ignored) {
            return;
        }
        prefs().edit().putString(PREF_ACCOUNTS, a.toString()).apply();
    }

    /** Fresh access token, refreshed a minute before it expires. Runs on a background thread. */
    String token(Account acc) throws Exception {
        if (acc.access != null && !acc.access.isEmpty() && System.currentTimeMillis() < acc.expiry - 60_000) {
            return acc.access;
        }
        String[] c = client();
        JSONObject r = new JSONObject(request("https://www.youtube.com/o/oauth2/token", new JSONObject()
                .put("client_id", c[0]).put("client_secret", c[1])
                .put("refresh_token", acc.refresh).put("grant_type", "refresh_token").toString(), null));
        acc.access = r.getString("access_token");
        acc.expiry = System.currentTimeMillis() + r.optLong("expires_in", 3600) * 1000;
        List<Account> all = accounts();
        for (Account a : all) if (a.refresh.equals(acc.refresh)) { a.access = acc.access; a.expiry = acc.expiry; }
        save(all);
        return acc.access;
    }

    /** The YouTube TV client id and secret, read from youtube.com/tv (they are public, in its page). */
    private String[] client() throws Exception {
        String saved = prefs().getString(PREF_CLIENT, "");
        if (saved.contains("\n")) return saved.split("\n", 2);
        String page = request("https://www.youtube.com/tv", null, null);
        Matcher m = Pattern.compile("clientId:\"([^\"]+)\",[A-Za-z_$]+:\"([^\"]+)\"").matcher(page);
        if (!m.find()) throw new java.io.IOException("TV client not found");
        prefs().edit().putString(PREF_CLIENT, m.group(1) + "\n" + m.group(2)).apply();
        return new String[]{m.group(1), m.group(2)};
    }

    // ---- sign-in by code -----------------------------------------------------------------------------

    /** Shows a code to type at google.com/device; polls until the account is linked (30 minutes at most). */
    void startLink(ContentRepository.LinkCallback cb) {
        cancelled = false;
        io.execute(() -> {
            try {
                String[] c = client();
                JSONObject d = new JSONObject(request("https://www.youtube.com/o/oauth2/device/code", new JSONObject()
                        .put("client_id", c[0])
                        .put("scope", "http://gdata.youtube.com https://www.googleapis.com/auth/youtube")
                        .put("device_id", UUID.randomUUID().toString())
                        .put("device_model", "ytlr::").toString(), null));
                String url = d.optString("verification_url", "https://www.google.com/device");
                main.post(() -> cb.onCode(d.optString("user_code"), url, null));
                long interval = Math.max(5, d.optLong("interval", 5)) * 1000;
                long end = System.currentTimeMillis() + d.optLong("expires_in", 1800) * 1000;
                while (!cancelled && System.currentTimeMillis() < end) {
                    Thread.sleep(interval);
                    JSONObject t = new JSONObject(request("https://www.youtube.com/o/oauth2/token", new JSONObject()
                            .put("client_id", c[0]).put("client_secret", c[1])
                            .put("code", d.getString("device_code"))
                            .put("grant_type", "http://oauth.net/grant_type/device/1.0").toString(), null));
                    if (!t.has("refresh_token")) continue;   // authorization_pending until the code is typed
                    Account acc = new Account();
                    acc.refresh = t.getString("refresh_token");
                    acc.access = t.optString("access_token");
                    acc.expiry = System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000;
                    acc.name = accountName(acc.access);
                    List<Account> all = accounts();
                    all.add(acc);
                    save(all);
                    main.post(cb::onSignedIn);
                    return;
                }
                if (!cancelled) main.post(cb::onFailed);
            } catch (Exception e) {
                Log.i(TAG, "youtube link failed: " + e);
                if (!cancelled) main.post(cb::onFailed);
            }
        });
    }

    void cancelLink() {
        cancelled = true;
    }

    private String accountName(String access) {
        try {
            JSONObject r = innertube("account/accounts_list", new JSONObject(), access);
            Matcher m = Pattern.compile("\"accountName\":\\{\"(?:simpleText|runs)\":(?:\"([^\"]+)\"|\\[\\{\"text\":\"([^\"]+)\")")
                    .matcher(r.toString());
            if (m.find()) return m.group(1) != null ? m.group(1) : m.group(2);
        } catch (Exception e) {
            Log.i(TAG, "youtube account name: " + e);
        }
        return "YouTube";
    }

    // ---- rows -----------------------------------------------------------------------------------------

    /** Name part that marks Vic's account, the one that also shows her playlist. */
    private static final String PLAYLIST_ACCOUNT = "vic";
    private static final String PLAYLIST_TITLE = "bibous";

    /**
     * For each account: its home feed and Watch later; for Vic's account, also the "Bibous" playlist right after
     * its home feed. Blocking: call from a background thread. An account that fails just has no rows.
     */
    List<WatchItem.Row> rows() {
        List<WatchItem.Row> out = new ArrayList<>();
        List<Account> all = accounts();
        // Vic's account: the one named after her, else the second one connected.
        Account vic = all.size() > 1 ? all.get(1) : null;
        for (Account a : all) if (a.name.toLowerCase(java.util.Locale.ROOT).contains(PLAYLIST_ACCOUNT)) vic = a;
        for (Account acc : all) {
            String kicker = "YouTube · " + acc.name;
            try {
                addRow(out, kicker, ctx.getString(R.string.yt_row_home), browse(acc, "FEwhat_to_watch"));
                if (acc == vic) {
                    String[] pl = findPlaylist(browse(acc, "FElibrary"), PLAYLIST_TITLE);
                    if (pl != null) addRow(out, kicker, pl[1], browse(acc, pl[0]));
                }
                addRow(out, kicker, ctx.getString(R.string.yt_row_later), browse(acc, "VLWL"));
            } catch (Exception e) {
                Log.i(TAG, "youtube rows for an account failed: " + e);
            }
        }
        return out;
    }

    private void addRow(List<WatchItem.Row> out, String kicker, String name, JSONObject page) {
        WatchItem.Row row = new WatchItem.Row(kicker + " · " + name, ContentRepository.YOUTUBE_COLOR);
        List<JSONObject> tiles = new ArrayList<>();
        collect(page, "tileRenderer", tiles);
        for (JSONObject t : tiles) {
            if (!"TILE_CONTENT_TYPE_VIDEO".equals(t.optString("contentType"))) continue;
            String id = t.optString("contentId");
            JSONObject md = t.optJSONObject("metadata") != null ? t.optJSONObject("metadata").optJSONObject("tileMetadataRenderer") : null;
            if (id.isEmpty() || md == null) continue;
            WatchItem w = new WatchItem(WatchItem.LANDSCAPE, WatchItem.YOUTUBE);
            w.title = text(md.optJSONObject("title"));
            List<String> lines = new ArrayList<>();
            JSONArray ls = md.optJSONArray("lines");
            for (int i = 0; ls != null && i < ls.length(); i++) {
                List<JSONObject> items = new ArrayList<>();
                collect(ls.optJSONObject(i), "lineItemRenderer", items);
                StringBuilder b = new StringBuilder();
                for (JSONObject it : items) b.append(text(it.optJSONObject("text")));
                // "7,8 k vues · • · il y a 1 heure" → "7,8 k vues · il y a 1 heure"
                String line = b.toString().replace(" · • · ", " · ").replaceAll("^ · ", "").trim();
                if (!line.isEmpty()) lines.add(line);
            }
            w.subtitle = String.join(" · ", lines);
            List<JSONObject> times = new ArrayList<>();
            collect(t, "thumbnailOverlayTimeStatusRenderer", times);
            String duration = "";
            for (JSONObject o : times) if (!text(o.optJSONObject("text")).isEmpty()) duration = text(o.optJSONObject("text"));
            w.meta = duration.isEmpty() ? w.subtitle : w.subtitle + " · " + duration;
            List<JSONObject> resume = new ArrayList<>();
            collect(t, "thumbnailOverlayResumePlaybackRenderer", resume);
            if (!resume.isEmpty()) w.progress = resume.get(0).optInt("percentDurationWatched") / 100f;
            w.kicker = row.title;
            w.imageUrl = "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg";
            w.backdropUrl = "https://i.ytimg.com/vi/" + id + "/maxresdefault.jpg";
            w.intent = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://www.youtube.com/watch?v=" + id))
                    .setPackage(ctx.getString(R.string.youtube_package));
            row.items.add(w);
        }
        if (!row.items.isEmpty()) out.add(row);
    }

    /** browseId and title of the first library playlist whose title contains {@code part}. */
    private static String[] findPlaylist(JSONObject library, String part) {
        List<JSONObject> tiles = new ArrayList<>();
        collect(library, "tileRenderer", tiles);
        for (JSONObject t : tiles) {
            if (!"TILE_CONTENT_TYPE_PLAYLIST".equals(t.optString("contentType"))) continue;
            JSONObject md = t.optJSONObject("metadata") != null ? t.optJSONObject("metadata").optJSONObject("tileMetadataRenderer") : null;
            String title = md != null ? text(md.optJSONObject("title")) : "";
            JSONObject cmd = t.optJSONObject("onSelectCommand");
            JSONObject be = cmd != null ? cmd.optJSONObject("browseEndpoint") : null;
            if (be != null && title.toLowerCase(java.util.Locale.ROOT).contains(part)) {
                return new String[]{be.optString("browseId"), title};
            }
        }
        return null;
    }

    private static void collect(Object o, String key, List<JSONObject> out) {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = j.opt(k);
                if (k.equals(key) && v instanceof JSONObject) out.add((JSONObject) v);
                else collect(v, key, out);
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), key, out);
        }
    }

    private static String text(JSONObject t) {
        if (t == null) return "";
        if (t.has("simpleText")) return t.optString("simpleText");
        StringBuilder b = new StringBuilder();
        JSONArray runs = t.optJSONArray("runs");
        for (int i = 0; runs != null && i < runs.length(); i++) b.append(runs.optJSONObject(i).optString("text"));
        return b.toString();
    }

    // ---- InnerTube -----------------------------------------------------------------------------------

    JSONObject browse(Account acc, String browseId) throws Exception {
        return innertube("browse", new JSONObject().put("browseId", browseId), token(acc));
    }

    private JSONObject innertube(String endpoint, JSONObject body, String access) throws Exception {
        body.put("context", new JSONObject().put("client", new JSONObject()
                .put("clientName", "TVHTML5").put("clientVersion", CLIENT_VERSION).put("hl", "fr").put("gl", "FR")));
        return new JSONObject(request("https://www.youtube.com/youtubei/v1/" + endpoint + "?prettyPrint=false",
                body.toString(), access));
    }

    // ---- HTTP ----------------------------------------------------------------------------------------

    /** GET or POST JSON; returns the body whatever the status (OAuth answers its "pending" with an error code). */
    private static String request(String url, String json, String bearer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", TV_UA);
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
            if (json != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = c.getOutputStream()) {
                    o.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            InputStream in = code / 100 == 2 ? c.getInputStream() : c.getErrorStream();
            if (in == null) throw new java.io.IOException("HTTP " + code);
            try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
