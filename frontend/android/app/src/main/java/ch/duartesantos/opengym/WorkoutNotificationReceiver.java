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
            String actionId = intent.getStringExtra("actionId");
            Context app = context.getApplicationContext();
            // Cards built before an action plan was synced still relay to the live WebView.
            // New planned cards are consumed natively so a suspended WebView is not in the path.
            if (actionId == null || actionId.isEmpty()) {
                WorkoutNotification.acceptLegacyDone(app, sessionId);
                return;
            }
            WorkoutNotificationState before = WorkoutNotification.savedState(app);
            if (before.dismissed) return;
            WorkoutNotificationStore.AcceptedAction accepted =
                    WorkoutNotification.acceptDone(app, sessionId, actionId);
            if (accepted == null) return;
            if (accepted.restMs > 0 && !accepted.action.finalAction) {
                MapSettings cfg = MapSettings.from(accepted.action.restAlert);
                int accent = intValue(accepted.action.notification, "accent", before.accent);
                int ink = intValue(accepted.action.notification, "ink", before.ink);
                RestAlert.setAccentColor(accent, ink);
                // The AlarmManager PendingIntent captures every setting it needs before the app can
                // be killed again. RestAlert also persists them for +15s/pause alarm rescheduling.
                RestAlert.schedule(app, accepted.endsAt, RestAlert.NOTIFICATION_ID,
                        cfg.title, cfg.sound, cfg.vibrate, cfg.channelId, cfg.visibility,
                        cfg.importance, cfg.localOnly, cfg.countdownTitle, accepted.restMs);
            }
            // Keep an already-running WebView current too. If this races its resume read, JS can
            // safely ignore the duplicate after the command has been acknowledged.
            RestAlertPlugin.emitCompleteSet(sessionId, actionId);
        }
    }

    private static int intValue(java.util.Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static final class MapSettings {
        final String title, countdownTitle, channelId, visibility, importance;
        final boolean sound, vibrate, localOnly;

        private MapSettings(String title, String countdownTitle, String channelId,
                            String visibility, String importance, boolean sound,
                            boolean vibrate, boolean localOnly) {
            this.title = title;
            this.countdownTitle = countdownTitle;
            this.channelId = channelId;
            this.visibility = visibility;
            this.importance = importance;
            this.sound = sound;
            this.vibrate = vibrate;
            this.localOnly = localOnly;
        }

        static MapSettings from(java.util.Map<String, Object> values) {
            return new MapSettings(stringValue(values, "title", "Rest over — next set!"),
                    stringValue(values, "countdownTitle", "Rest"),
                    stringValue(values, "channelId", RestAlert.CHANNEL_ID),
                    stringValue(values, "visibility", "public"),
                    stringValue(values, "importance", "high"),
                    boolValue(values, "sound", true), boolValue(values, "vibrate", true),
                    boolValue(values, "localOnly", false));
        }

        private static String stringValue(java.util.Map<String, Object> values,
                                          String key, String fallback) {
            Object value = values.get(key);
            return value instanceof String && !((String) value).isEmpty() ? (String) value : fallback;
        }

        private static boolean boolValue(java.util.Map<String, Object> values,
                                         String key, boolean fallback) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : fallback;
        }
    }
}
