package ch.duartesantos.opengym;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Records an explicit swipe dismissal so later session updates do not bring the card back. */
public class WorkoutNotificationReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (WorkoutNotification.ACTION_DISMISSED.equals(action)) {
            WorkoutNotification.markDismissed(context.getApplicationContext(), intent.getStringExtra("sessionId"));
        } else if (WorkoutNotification.ACTION_COMPLETE_SET.equals(action)) {
            String sessionId = intent.getStringExtra("sessionId");
            WorkoutNotificationState state = WorkoutNotification.savedState(context);
            if (sessionId != null && sessionId.equals(state.sessionId) && !state.dismissed && state.rest == null) {
                RestAlertPlugin.emitCompleteSet(sessionId);
            }
        }
    }
}
