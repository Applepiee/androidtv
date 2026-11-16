package app.stremioorigin.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/* The system's word about the app's home-screen rows (HomeRows): installed (Android 8 and up, the launcher asks the app
   to make its row now: INITIALIZE_PROGRAMS), the TV started (recommendations are not kept over a restart), and a title
   the user took off a row (kept off). */
public class HomeRowsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction(); if (a == null) return;
        final PendingResult done = goAsync();
        Runnable fin = () -> { try { done.finish(); } catch (Throwable t) { } };
        switch (a) {
            case "android.media.tv.action.INITIALIZE_PROGRAMS": HomeRows.replay(c, true, fin); break;
            case Intent.ACTION_BOOT_COMPLETED: case Intent.ACTION_MY_PACKAGE_REPLACED: HomeRows.replay(c, false, fin); break;
            case "android.media.tv.action.WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED":
                HomeRows.removedByUser(c, i.getLongExtra("android.media.tv.extra.WATCH_NEXT_PROGRAM_ID", -1), true, fin); break;
            case "android.media.tv.action.PREVIEW_PROGRAM_BROWSABLE_DISABLED":
                HomeRows.removedByUser(c, i.getLongExtra("android.media.tv.extra.PREVIEW_PROGRAM_ID", -1), false, fin); break;
            default: fin.run();
        }
    }
}
