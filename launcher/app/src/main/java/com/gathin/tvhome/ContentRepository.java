package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches what the Watch page shows, off the main thread:
 * - Stremio "Continue watching", from the account library (api.strem.io datastoreGet; needs an auth key,
 *   obtained on the TV with Stremio's link-code sign-in, or set at build time),
 * - Stremio catalogs, from any addon catalog URL (Cinemeta's top movies and series by default, no account),
 * - the latest YouTube videos of the channels listed in config.xml, from YouTube's public RSS feeds.
 * All of it is unofficial or public and can break; every source fails quietly and its row is simply left out.
 */
final class ContentRepository {

    interface Callback {
        void onLoaded(List<WatchItem> continueWatching, List<WatchItem.Row> rows);
    }

    interface LinkCallback {
        /** Shows the code; called once. */
        void onCode(String code, String link, String qrUrl);

        /** Signed in: the auth key is saved. */
        void onSignedIn();

        void onFailed();
    }

    static final int STREMIO_COLOR = 0xFF8A5CFF;
    static final int YOUTUBE_COLOR = 0xFFFF3B3B;

    private static final String API = "https://api.strem.io/api/";
    private static final String LINK_API = "https://link.stremio.com/api/v2/";
    private static final String PREFS = "tvhome";
    private static final String PREF_AUTH = "stremio_auth_key";

    private final Context ctx;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean linkCancelled;

    ContentRepository(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    // ---- account -----------------------------------------------------------------------------------

    String authKey() {
        String saved = prefs().getString(PREF_AUTH, "");
        if (!saved.isEmpty()) return saved;
        return ctx.getString(R.string.stremio_auth_key).trim();
    }

    boolean signedIn() {
        return !authKey().isEmpty();
    }

    void signOut() {
        prefs().edit().remove(PREF_AUTH).apply();
    }

    /**
     * Stremio's sign-in by code: the TV shows a short code, the user opens the link on a phone where they are
     * signed in to Stremio, and the TV picks up the auth key. Polls every 3 seconds for 5 minutes.
     */
    void startLink(LinkCallback cb) {
        linkCancelled = false;
        io.execute(() -> {
            try {
                JSONObject r = new JSONObject(get(LINK_API + "create?type=Create")).getJSONObject("result");
                String code = r.getString("code");
                String link = r.optString("link", "https://link.stremio.com/" + code);
                String qr = r.optString("qrcode", "https://link.stremio.com/qr?data=" + Uri.encode(link));
                main.post(() -> cb.onCode(code, link, qr));
                long end = System.currentTimeMillis() + 5 * 60_000L;
                while (!linkCancelled && System.currentTimeMillis() < end) {
                    Thread.sleep(3000);
                    String key = null;
                    try {
                        JSONObject res = new JSONObject(get(LINK_API + "read?type=Read&code=" + Uri.encode(code)));
                        JSONObject result = res.optJSONObject("result");
                        if (result != null) key = result.optString("authKey", null);
                    } catch (Exception ignored) {
                        // Not linked yet: the API answers with an error until the phone confirms.
                    }
                    if (key != null && !key.isEmpty()) {
                        prefs().edit().putString(PREF_AUTH, key).apply();
                        main.post(cb::onSignedIn);
                        return;
                    }
                }
                if (!linkCancelled) main.post(cb::onFailed);
            } catch (Exception e) {
                if (!linkCancelled) main.post(cb::onFailed);
            }
        });
    }

    void cancelLink() {
        linkCancelled = true;
    }

    // ---- loading -----------------------------------------------------------------------------------

    void load(Callback cb) {
        io.execute(() -> {
            List<WatchItem> cont = new ArrayList<>();
            String key = authKey();
            if (!key.isEmpty()) {
                try {
                    cont.addAll(continueWatching(key));
                } catch (Exception ignored) {
                    // Expired or wrong key, or the unofficial API changed: the row just stays empty.
                }
            }
            List<WatchItem.Row> rows = new ArrayList<>();
            WatchItem.Row yt = youtubeRow();
            String[] catalogs = ctx.getResources().getStringArray(R.array.stremio_catalogs);
            for (int i = 0; i < catalogs.length; i++) {
                String[] parts = catalogs[i].split("\\|", 2);
                if (parts.length < 2) continue;
                WatchItem.Row row = catalogRow(parts[0].trim(), parts[1].trim());
                if (row != null && !row.items.isEmpty()) rows.add(row);
                // YouTube goes second, between the first two catalogs.
                if (i == 0 && yt != null) {
                    rows.add(yt);
                    yt = null;
                }
            }
            if (yt != null) rows.add(0, yt);
            main.post(() -> cb.onLoaded(cont, rows));
        });
    }

    // ---- Stremio -----------------------------------------------------------------------------------

    private List<WatchItem> continueWatching(String key) throws Exception {
        JSONObject body = new JSONObject()
                .put("authKey", key)
                .put("collection", "libraryItem")
                .put("ids", new JSONArray())
                .put("all", true);
        JSONArray result = new JSONObject(post(API + "datastoreGet", body.toString())).getJSONArray("result");
        List<JSONObject> watching = new ArrayList<>();
        for (int i = 0; i < result.length(); i++) {
            JSONObject it = result.optJSONObject(i);
            if (it == null) continue;
            JSONObject st = it.optJSONObject("state");
            if (st == null || "other".equals(it.optString("type"))) continue;
            boolean removed = it.optBoolean("removed", false), temp = it.optBoolean("temp", false);
            if (removed && !temp) continue;
            long offset = st.optLong("timeOffset", 0), duration = st.optLong("duration", 0);
            if (offset <= 0) continue;
            if (duration > 0 && offset >= duration * 0.95) continue;
            watching.add(it);
        }
        // Most recently watched first.
        Collections.sort(watching, (a, b) -> Long.compare(lastWatched(b), lastWatched(a)));
        List<WatchItem> out = new ArrayList<>();
        for (int i = 0; i < Math.min(12, watching.size()); i++) {
            JSONObject it = watching.get(i);
            JSONObject st = it.getJSONObject("state");
            String id = it.optString("_id"), type = it.optString("type", "movie"), name = it.optString("name");
            String video = st.optString("video_id", "");
            if (video.isEmpty() || "null".equals(video)) video = id;
            long offset = st.optLong("timeOffset", 0), duration = st.optLong("duration", 0);

            WatchItem w = new WatchItem(WatchItem.LANDSCAPE, WatchItem.STREMIO);
            w.title = name;
            String episode = episodeLabel(video);
            String left = duration > 0 ? timeLeft(duration - offset) : "";
            w.subtitle = join(" · ", episode, left);
            w.kicker = ctx.getString(R.string.watch_kicker_continue);
            w.meta = w.subtitle;
            w.progress = duration > 0 ? Math.min(1f, offset / (float) duration) : -1f;
            w.backdropUrl = backgroundFor(id, it.optString("background", ""));
            w.imageUrl = w.backdropUrl != null ? w.backdropUrl : it.optString("poster", null);
            w.intent = stremioDetail(type, id, video);
            out.add(w);
        }
        return out;
    }

    private WatchItem.Row catalogRow(String title, String url) {
        try {
            JSONArray metas = new JSONObject(get(url)).getJSONArray("metas");
            WatchItem.Row row = new WatchItem.Row(title, STREMIO_COLOR);
            for (int i = 0; i < Math.min(20, metas.length()); i++) {
                JSONObject m = metas.getJSONObject(i);
                String id = m.optString("id"), type = m.optString("type", "movie");
                WatchItem w = new WatchItem(WatchItem.POSTER, WatchItem.STREMIO);
                w.title = m.optString("name");
                w.kicker = title;
                String rating = m.optString("imdbRating", "");
                w.meta = join(" · ", m.optString("releaseInfo", ""), genres(m),
                        rating.isEmpty() ? "" : "IMDb " + rating);
                w.description = m.optString("description", "");
                w.imageUrl = m.optString("poster", null);
                w.backdropUrl = backgroundFor(id, m.optString("background", ""));
                w.intent = stremioDetail(type, id, id);
                row.items.add(w);
            }
            return row;
        } catch (Exception e) {
            return null;
        }
    }

    private Intent stremioDetail(String type, String id, String video) {
        Uri uri = Uri.parse("stremio:///detail/" + Uri.encode(type) + "/" + Uri.encode(id) + "/" + Uri.encode(video));
        return new Intent(Intent.ACTION_VIEW, uri).setPackage(ctx.getString(R.string.stremio_package));
    }

    /** Cinemeta's backdrop for IMDb ids, or the addon's own. */
    private static String backgroundFor(String id, String own) {
        if (own != null && own.startsWith("http")) return own;
        if (id != null && id.startsWith("tt")) return "https://images.metahub.space/background/medium/" + id + "/img";
        return null;
    }

    private String episodeLabel(String video) {
        // Series video ids are "tt1234567:season:episode".
        String[] p = video.split(":");
        if (p.length >= 3) return ctx.getString(R.string.watch_episode, p[p.length - 2], p[p.length - 1]);
        return "";
    }

    private String timeLeft(long ms) {
        long min = Math.max(1, ms / 60000);
        if (min < 60) return ctx.getString(R.string.watch_left_min, min);
        return ctx.getString(R.string.watch_left_hours, min / 60, min % 60);
    }

    private static String genres(JSONObject m) {
        JSONArray g = m.optJSONArray("genres");
        if (g == null) g = m.optJSONArray("genre");
        if (g == null || g.length() == 0) return "";
        return g.optString(0, "");
    }

    private static long lastWatched(JSONObject it) {
        JSONObject st = it.optJSONObject("state");
        long t = st != null ? parseIso(st.optString("lastWatched", "")) : 0;
        return t != 0 ? t : parseIso(it.optString("_mtime", ""));
    }

    private static long parseIso(String s) {
        if (s == null || s.length() < 19) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(s.substring(0, 19));
            return d != null ? d.getTime() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---- YouTube -----------------------------------------------------------------------------------

    /** Newest videos of the configured channels, merged and sorted by date. Null when none are configured. */
    private WatchItem.Row youtubeRow() {
        String[] channels = ctx.getResources().getStringArray(R.array.youtube_channels);
        if (channels.length == 0) return null;
        List<long[]> order = new ArrayList<>();
        List<WatchItem> all = new ArrayList<>();
        for (String ch : channels) {
            String id = ch.split("#")[0].trim();
            if (id.isEmpty()) continue;
            try {
                parseFeed(get("https://www.youtube.com/feeds/videos.xml?channel_id=" + Uri.encode(id)), all, order);
            } catch (Exception ignored) {
                // One broken channel shouldn't hide the others.
            }
        }
        if (all.isEmpty()) return null;
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) idx.add(i);
        Collections.sort(idx, (a, b) -> Long.compare(order.get(b)[0], order.get(a)[0]));
        WatchItem.Row row = new WatchItem.Row(ctx.getString(R.string.watch_row_youtube), YOUTUBE_COLOR);
        for (int i = 0; i < Math.min(15, idx.size()); i++) row.items.add(all.get(idx.get(i)));
        return row;
    }

    private void parseFeed(String xml, List<WatchItem> out, List<long[]> dates) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new java.io.StringReader(xml));
        String channel = "", videoId = null, title = null, published = null, tag;
        boolean inEntry = false, inAuthor = false;
        int count = 0;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT && count < 6; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                tag = p.getName();
                if ("entry".equals(tag)) {
                    inEntry = true;
                    videoId = title = published = null;
                } else if ("author".equals(tag)) {
                    inAuthor = true;
                } else if ("yt:videoId".equals(tag) && inEntry) {
                    videoId = p.nextText();
                } else if ("title".equals(tag) && inEntry) {
                    title = p.nextText();
                } else if ("published".equals(tag) && inEntry) {
                    published = p.nextText();
                } else if ("name".equals(tag) && inAuthor && !inEntry) {
                    channel = p.nextText();
                }
            } else if (ev == XmlPullParser.END_TAG) {
                tag = p.getName();
                if ("author".equals(tag)) inAuthor = false;
                if ("entry".equals(tag) && inEntry) {
                    inEntry = false;
                    if (videoId == null || title == null) continue;
                    WatchItem w = new WatchItem(WatchItem.LANDSCAPE, WatchItem.YOUTUBE);
                    w.title = title;
                    long when = parseIso(published);
                    w.subtitle = join(" · ", channel, ago(when));
                    w.kicker = ctx.getString(R.string.watch_kicker_youtube);
                    w.meta = w.subtitle;
                    w.imageUrl = "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg";
                    w.backdropUrl = "https://i.ytimg.com/vi/" + videoId + "/maxresdefault.jpg";
                    w.intent = youtubeWatch(videoId);
                    out.add(w);
                    dates.add(new long[]{when});
                    count++;
                }
            }
        }
    }

    Intent youtubeWatch(String videoId) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + videoId))
                .setPackage(ctx.getString(R.string.youtube_package));
    }

    private String ago(long when) {
        if (when <= 0) return "";
        long h = (System.currentTimeMillis() - when) / 3_600_000L;
        if (h < 1) return ctx.getString(R.string.ago_now);
        if (h < 24) return ctx.getString(R.string.ago_hours, h);
        long d = h / 24;
        return d == 1 ? ctx.getString(R.string.ago_yesterday) : ctx.getString(R.string.ago_days, d);
    }

    // ---- HTTP --------------------------------------------------------------------------------------

    private static String get(String url) throws Exception {
        return request(url, null);
    }

    private static String post(String url, String json) throws Exception {
        return request(url, json);
    }

    private static String request(String url, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "tvhome/2.1");
            if (json != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = c.getOutputStream()) {
                    o.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new java.io.IOException("HTTP " + code);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    private static String join(String sep, String... parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (b.length() > 0) b.append(sep);
            b.append(p);
        }
        return b.toString();
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
