package app.stremioorigin.tv;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/* What the user is watching, on the TV's own home screen (the list: src/platform/androidtv/homerows_atv.c, Home's
   Continue Watching, sent when it changes; {"items":[{id,type,vid,name,poster,bg,s,e,pos,dur,next,t}]}).
     Android 8 and up: the system's Play Next / Watch Next row (a part-watched title: continue, with its place; a
       show's new episode: next), the first 10; and the app's own row, "Stremio Origin", every title (up to 20). The
       row is made at install (INITIALIZE_PROGRAMS, HomeRowsReceiver) so the launcher shows it without asking; where it
       is still hidden once it has titles and has never been shown, the system's "Add this row?" question is asked
       once; a row the user hid in the TV's settings is never asked for again.
     Android 5 to 7: the launcher's recommendations (notifications of the recommendation kind), the first 6, posted
       again after a restart (the system does not keep them).
   Each card plays as the app's own Continue Watching does, through CardActivity (stremioorigin://play/...).
   A title the user takes off a row (the system's word for it, HomeRowsReceiver) stays off it until it moves on
   (another episode); a card the launcher hides any other way is left as it is, never re-added over it. No switch in
   the app: the TV's own settings turn each row off and on. An empty list (signed out): everything taken down. A
   device that is not a TV (a phone or tablet running this app):
   nothing. Fire TV keeps its own home screen; Google TV may keep its Continue Watching row to the services it works
   with: there the app's row is in the Apps tab where the launcher shows app rows at all. */
final class HomeRows {
    private static final String TAG = "StremioOrigin";
    private static final int WATCH_NEXT_MAX = 10, RECS_MAX = 6;
    private static final ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor();

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("homerows", Context.MODE_PRIVATE); }

    /* from the app (MainActivity.homeRows): kept for a restart, then put on the rows away from the app's thread */
    static void update(Context c, final String json) {
        final Context a = c.getApplicationContext();
        prefs(a).edit().putString("last", json).apply();
        ex.execute(() -> run(a, json, false));
    }
    /* from HomeRowsReceiver: the install (the row made at once, empty), a restart, an update: the last list again */
    static void replay(Context c, final boolean install, final Runnable done) {
        final Context a = c.getApplicationContext();
        final String json = prefs(a).getString("last", "{\"items\":[]}");
        ex.execute(() -> { run(a, json, install); if (done != null) done.run(); });
    }
    /* a title taken off a row by the user (the system's broadcast): kept off, its card gone */
    static void removedByUser(Context c, final long programId, final boolean watchNext, final Runnable done) {
        final Context a = c.getApplicationContext();
        ex.execute(() -> {
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    Uri u = watchNext ? TvContract.buildWatchNextProgramUri(programId) : TvContract.buildPreviewProgramUri(programId);
                    ContentResolver r = a.getContentResolver(); String key = null;
                    try (Cursor k = r.query(u, new String[] {TvContract.BaseTvColumns._ID, TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID}, null, null, null)) {
                        if (k != null && k.moveToFirst()) key = k.getString(1); }
                    if (key != null) { gone(a, watchNext ? "goneWn" : "goneCh", key); r.delete(u, null, null); MainActivity.log("[home] taken off the " + (watchNext ? "Play Next" : "app's") + " row by the user"); }
                }
            } catch (Throwable t) { MainActivity.log("[home] " + t); }
            if (done != null) done.run();
        });
    }

    private static void run(Context c, String json, boolean install) {
        try {
            if (!c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return;   /* not a TV */
            JSONObject j = new JSONObject(json);
            JSONArray a = j.optJSONArray("items"); List<JSONObject> items = new ArrayList<>();
            if (a != null) for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null && !o.optString("id").isEmpty()) items.add(o); }
            if (Build.VERSION.SDK_INT >= 26) tvRows(c, items, install); else recommendations(c, items);
        } catch (Throwable t) { MainActivity.log("[home] " + t); }
    }

    private static String key(JSONObject o) { return o.optString("id") + "|" + o.optString("vid"); }
    private static Set<String> goneSet(Context c, String name) { return new HashSet<>(prefs(c).getStringSet(name, new HashSet<>())); }
    private static void gone(Context c, String name, String key) {
        Set<String> s = goneSet(c, name); s.add(key);
        if (s.size() > 300) { List<String> l = new ArrayList<>(s); s = new HashSet<>(l.subList(l.size() - 300, l.size())); }
        prefs(c).edit().putStringSet(name, s).apply();
    }

    /* the card pressed: played as the app's own Continue Watching plays it (homerows_atv.c: the stream it was playing,
       or with best stream on the best one straight into the loader; else the title page with that episode's streams).
       Through CardActivity, never MainActivity itself: the app's task then keeps the plain launch, so the app opened
       again later (from its icon, after Android closed it in the back) never replays an old card */
    private static Intent open(Context c, JSONObject o) {
        String vid = o.optString("vid"), u = "stremioorigin://play/" + o.optString("type", "movie") + "/" + o.optString("id") + "/" + (vid.isEmpty() ? o.optString("id") : vid);
        return new Intent(Intent.ACTION_VIEW, Uri.parse(u)).setClass(c, CardActivity.class);
    }
    private static Intent openApp(Context c) { return new Intent(c, MainActivity.class).setAction(Intent.ACTION_MAIN); }

    /* ---- Android 8 and up: the app's row and Play Next ---- */
    private static void tvRows(Context c, List<JSONObject> items, boolean install) throws Exception {
        ContentResolver r = c.getContentResolver(); SharedPreferences p = prefs(c);
        long ch = p.getLong("channel", -1);
        if (ch != -1 && browsable(r, TvContract.buildChannelUri(ch)) < 0) ch = -1;   /* gone (the app's data cleared, the launcher's reset) */
        if (ch == -1) {
            ContentValues v = new ContentValues();
            v.put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW);
            v.put(TvContract.Channels.COLUMN_DISPLAY_NAME, c.getString(R.string.app_name));
            v.put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI, openApp(c).toUri(Intent.URI_INTENT_SCHEME));
            v.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, "continue");
            Uri u = r.insert(TvContract.Channels.CONTENT_URI, v);
            if (u == null) { MainActivity.log("[home] this TV takes no app rows"); return; }
            ch = ContentUris.parseId(u);
            p.edit().putLong("channel", ch).putLong("madeAt", System.currentTimeMillis()).putInt("logoV", 0).putBoolean("seenShown", false).apply();
            try { TvContract.requestChannelBrowsable(c, ch); } catch (Throwable t) { }   /* the app's first row: shown without asking where the launcher allows it */
            MainActivity.log("[home] the app's row made" + (install ? " at install" : ""));
            final Context a = c; ex.schedule(() -> run(a, prefs(a).getString("last", "{\"items\":[]}"), false), 20, TimeUnit.SECONDS);   /* (then whether the launcher shows it) */
        }
        if (p.getInt("logoV", 0) < 2) { logo(c, ch); p.edit().putInt("logoV", 2).apply(); }   /* (made, or drawn again when the picture changes) */
        int n = sync(r, TvContract.PreviewPrograms.CONTENT_URI, TvContract.buildPreviewProgramsUriForChannel(ch), ch, items, "goneCh", c);
        List<JSONObject> wn = new ArrayList<>(); for (JSONObject o : items) if (wn.size() < WATCH_NEXT_MAX) wn.add(o);
        int hc = hiddenLast;
        int w = sync(r, TvContract.WatchNextPrograms.CONTENT_URI, null, -1, wn, "goneWn", c), hw = hiddenLast;
        int shown = browsable(r, TvContract.buildChannelUri(ch));
        if (shown == 1 && !p.getBoolean("seenShown", false)) p.edit().putBoolean("seenShown", true).apply();
        MainActivity.log("[home] the TV's home screen: " + n + " on the app's row (" + (shown == 1 ? "shown" : "hidden") + (hc > 0 ? ", " + hc + " kept hidden by the TV" : "") + "), " + w + " in Play Next" + (hw > 0 ? " (" + hw + " kept hidden by the TV)" : ""));
        /* hidden from the start with something to show: the system's own question, once (not over the install, not while
           the app is in the back); a row once shown and then hidden was hidden by the user: never asked for */
        if (n > 0 && !p.getBoolean("asked", false) && !p.getBoolean("seenShown", false) && shown == 0
                && System.currentTimeMillis() - p.getLong("madeAt", 0) > 15000) {
            final MainActivity a = MainActivity.self; final long id = ch;
            if (a != null && MainActivity.inFront) {
                p.edit().putBoolean("asked", true).apply();
                a.runOnUiThread(() -> { try { a.startActivityForResult(new Intent(TvContract.ACTION_REQUEST_CHANNEL_BROWSABLE).putExtra(TvContract.EXTRA_CHANNEL_ID, id), 4711); MainActivity.log("[home] asking to show the app's row"); }
                    catch (Throwable t) { MainActivity.log("[home] this launcher has no question to show a row"); } });
            }
        }
    }
    /* 1 shown, 0 hidden, -1 not there */
    private static int browsable(ContentResolver r, Uri u) {
        try (Cursor k = r.query(u, new String[] {TvContract.BaseTvColumns._ID, TvContract.Channels.COLUMN_BROWSABLE}, null, null, null)) {
            if (k == null || !k.moveToFirst()) return -1; return k.getInt(1) != 0 ? 1 : 0;
        } catch (Throwable t) { return -1; }
    }
    /* one row's cards made to match the list: added, changed or taken away; what the user took off (gone, from the
       system's broadcast) not added again; a card the launcher keeps hidden is left as it is (not re-added beside it, not
       counted as removed for good: the TV's own switches hide and show cards that way). How many show */
    private static int hiddenLast;
    private static int sync(ContentResolver r, Uri table, Uri query, long ch, List<JSONObject> items, String goneName, Context c) {
        boolean watchNext = ch < 0;
        Map<String, Long> have = new HashMap<>(); Set<String> hidden = new HashSet<>(), gone = goneSet(c, goneName);
        try (Cursor k = r.query(query != null ? query : table, new String[] {TvContract.BaseTvColumns._ID, TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, TvContract.PreviewPrograms.COLUMN_BROWSABLE}, null, null, null)) {
            while (k != null && k.moveToNext()) {
                long id = k.getLong(0); String key = k.getString(1);
                Uri u = watchNext ? TvContract.buildWatchNextProgramUri(id) : TvContract.buildPreviewProgramUri(id);
                if (key == null || have.containsKey(key)) { r.delete(u, null, null); continue; }   /* (not ours, or twice) */
                have.put(key, id); if (k.getInt(2) == 0) hidden.add(key);
            }
        }
        Set<String> want = new HashSet<>(); int i = 0, n = 0;
        for (JSONObject o : items) {
            String key = key(o); i++;
            if (gone.contains(key) || !want.add(key)) continue;
            Long id = have.get(key);
            if (hidden.contains(key)) continue;   /* hidden by the launcher: left so */
            ContentValues v = values(c, o, items.size() - i, i - 1, watchNext);
            if (!watchNext) v.put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, ch);
            try {
                if (id != null) r.update(watchNext ? TvContract.buildWatchNextProgramUri(id) : TvContract.buildPreviewProgramUri(id), v, null, null);
                else if (r.insert(table, v) == null) continue;
                n++;
            } catch (Throwable t) { MainActivity.log("[home] " + t); }
        }
        for (Map.Entry<String, Long> e : have.entrySet()) if (!want.contains(e.getKey()))   /* off the list (watched, or moved on to another episode) */
            try { r.delete(watchNext ? TvContract.buildWatchNextProgramUri(e.getValue()) : TvContract.buildPreviewProgramUri(e.getValue()), null, null); } catch (Throwable t) { }
        int h = 0; for (String key : hidden) if (want.contains(key)) h++; hiddenLast = h;
        return n;
    }
    private static ContentValues values(Context c, JSONObject o, int weight, int rank, boolean watchNext) {
        ContentValues v = new ContentValues();
        boolean series = "series".equals(o.optString("type")); int s = o.optInt("s", -1), e = o.optInt("e", -1);
        v.put(TvContract.PreviewPrograms.COLUMN_TYPE, series ? (e >= 0 ? TvContract.PreviewPrograms.TYPE_TV_EPISODE : TvContract.PreviewPrograms.TYPE_TV_SERIES)
                : "movie".equals(o.optString("type")) ? TvContract.PreviewPrograms.TYPE_MOVIE : TvContract.PreviewPrograms.TYPE_CLIP);
        v.put(TvContract.PreviewPrograms.COLUMN_TITLE, o.optString("name"));
        if (series && s >= 0) v.put(TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER, String.valueOf(s));
        if (series && e >= 0) v.put(TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, String.valueOf(e));
        String bg = o.optString("bg"), poster = o.optString("poster");
        if (!bg.isEmpty()) { v.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, bg); v.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.PreviewPrograms.ASPECT_RATIO_16_9); }
        else { v.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, poster); v.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.PreviewPrograms.ASPECT_RATIO_2_3); }
        if (!poster.isEmpty()) v.put(TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI, poster);
        v.put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, open(c, o).toUri(Intent.URI_INTENT_SCHEME));
        v.put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, key(o));
        v.put(TvContract.PreviewPrograms.COLUMN_CONTENT_ID, o.optString("vid"));
        long pos = o.optLong("pos", 0), dur = o.optLong("dur", 0); boolean timed = pos > 0 && dur > 0;
        boolean cont = (timed || o.optInt("cont", 0) == 1) && o.optInt("next", 0) == 0; /* (paused on Trakt: how far in, its length unknown: continue, with no progress bar rather than a made-up one) */
        if (timed && cont) { v.put(TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, (int) Math.min(pos, Integer.MAX_VALUE)); v.put(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS, (int) Math.min(dur, Integer.MAX_VALUE)); }
        if (watchNext) {
            v.put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, cont ? TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE : TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT);
            /* when it was last watched: never after this TV's own clock (a time from the account, set by a phone or the
               web, can be ahead of a box whose clock is behind; the launcher leaves such cards out) */
            long now = System.currentTimeMillis(), t = o.optLong("t", 0); if (t <= 0 || t > now) t = now - rank * 1000L;   /* (in the list's order) */
            v.put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, t);
        } else v.put(TvContract.PreviewPrograms.COLUMN_WEIGHT, weight);
        return v;
    }
    /* the row's picture: the mark on the banner's ground, square (tools/androidtv/make-banner.swift) */
    private static void logo(Context c, long ch) {
        try {
            Bitmap b = BitmapFactory.decodeResource(c.getResources(), R.drawable.channel_logo); if (b == null) return;
            try (OutputStream out = c.getContentResolver().openOutputStream(TvContract.buildChannelLogoUri(ch))) { if (out != null) b.compress(Bitmap.CompressFormat.PNG, 100, out); }
        } catch (Throwable t) { MainActivity.log("[home] the row's picture: " + t); }
    }

    /* ---- Android 5 to 7: the launcher's recommendations ---- */
    private static void recommendations(Context c, List<JSONObject> items) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE); if (nm == null) return;
        SharedPreferences p = prefs(c); int had = p.getInt("recs", 0), n = 0;
        for (JSONObject o : items) {
            if (n >= RECS_MAX) break;
            Bitmap art = picture(o.optString("poster"));
            if (art == null) art = picture(o.optString("bg"));
            Notification.Builder b = new Notification.Builder(c)
                    .setCategory(Notification.CATEGORY_RECOMMENDATION).setLocalOnly(true).setOngoing(true).setAutoCancel(false)
                    .setSmallIcon(R.mipmap.ic_launcher).setContentTitle(o.optString("name")).setWhen(o.optLong("t", System.currentTimeMillis()))
                    .setPriority(n == 0 ? Notification.PRIORITY_HIGH : Notification.PRIORITY_DEFAULT).setSortKey(String.valueOf(n))
                    .setContentIntent(PendingIntent.getActivity(c, 100 + n, open(c, o), PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)));
            int s = o.optInt("s", -1), e = o.optInt("e", -1);
            if (e >= 0) b.setContentText(s >= 0 ? "S" + s + " E" + e : "E" + e); else b.setContentText(c.getString(R.string.app_name));
            if (art != null) b.setLargeIcon(art);
            long pos = o.optLong("pos", 0), dur = o.optLong("dur", 0);
            if (pos > 0 && dur > 0) b.setProgress((int) (dur / 1000), (int) (pos / 1000), false);
            android.os.Bundle x = new android.os.Bundle(); x.putString("com.amazon.extra.DISPLAY_NAME", c.getString(R.string.app_name)); b.setExtras(x);
            try { nm.notify("rec", n, b.build()); n++; } catch (Throwable t) { MainActivity.log("[home] " + t); }
        }
        for (int i = n; i < had; i++) nm.cancel("rec", i);
        p.edit().putInt("recs", n).apply();
        MainActivity.log("[home] the TV's home screen: " + n + " recommendations");
    }
    /* a card's picture, about the size the launcher shows it */
    private static Bitmap picture(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            java.net.HttpURLConnection k = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            k.setInstanceFollowRedirects(true); k.setConnectTimeout(10000); k.setReadTimeout(15000); k.setRequestProperty("User-Agent", "StremioOrigin (Android TV)");
            if (k.getResponseCode() != 200) return null;
            ByteArrayOutputStream buf = new ByteArrayOutputStream(); byte[] b = new byte[16384]; int r;
            try (InputStream in = k.getInputStream()) { while ((r = in.read(b)) > 0 && buf.size() < 4 << 20) buf.write(b, 0, r); }
            byte[] d = buf.toByteArray(); BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(d, 0, d.length, o);
            int ss = 1; while (o.outHeight / (ss * 2) >= 360) ss *= 2;
            o = new BitmapFactory.Options(); o.inSampleSize = ss; return BitmapFactory.decodeByteArray(d, 0, d.length, o);
        } catch (Throwable t) { return null; }
    }
}
