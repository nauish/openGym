package ch.duartesantos.opengym;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Records an explicit swipe dismissal so later session updates do not bring the card back. */
public class WorkoutNotificationReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !WorkoutNotification.ACTION_DISMISSED.equals(intent.getAction())) return;
        WorkoutNotification.markDismissed(context.getApplicationContext(), intent.getStringExtra("sessionId"));
    }
}
