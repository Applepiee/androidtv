package app.stremioorigin.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/* A card pressed on the TV's home screen (HomeRows) lands here, never on MainActivity: its link is handed to the app
   (MainActivity.cardLink, played by homerows_atv.c), then the app is brought up the way its own icon brings it up, and
   this screen, never drawn, is gone. Android keeps the intent an app's task was started with and gives it again
   whenever it opens that task anew (the app closed in the back for memory, then opened from its icon or the recent
   apps); a card's link kept that way played that card again on an ordinary open. Started from the launcher in a task
   of its own (no affinity), left out of the recent apps and history. */
public class CardActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        boolean replay = b != null || (i != null && (i.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0);
        if (!replay && i != null && i.getData() != null) MainActivity.cardLink(i.getData().toString());
        try {
            startActivity(new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (Throwable t) { }
        finish();
        overridePendingTransition(0, 0);
    }
}
