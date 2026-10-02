package ch.duartesantos.opengym;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/** Presentation boundary. Device-specific extras belong here, never in the timer or store. */
final class WorkoutNotificationRenderer {
    private WorkoutNotificationRenderer() {}

    static boolean needsPeriodicUpdates() {
        return Build.VERSION.SDK_INT < 36;
    }

    static Notification render(Context context, WorkoutNotificationState state) {
        long now = System.currentTimeMillis();
        if (state.rest != null && needsPeriodicUpdates()) {
            return LegacyRestNotification.render(context, state, now);
        }
        String status = state.rest == null ? state.workoutText : state.restText;
        boolean paused = state.rest != null && state.rest.paused;
        if (paused) status += " · " + state.pausedLabel + " "
                + RestAlert.clock((int) Math.ceil(state.rest.remainingAt(now) / 1000.0));
        NotificationCompat.Builder builder = baseBuilder(context, state, status)
                .setWhen(state.rest == null ? state.startedAt : paused ? now : state.rest.endsAt)
                .setShowWhen(!paused)
                .setUsesChronometer(!paused)
                .setRequestPromotedOngoing(state.rest != null && !paused && canPromote(context));
        if (state.rest != null) {
            if (!paused) builder.setChronometerCountDown(true);
            builder.addAction(paused ? R.drawable.ic_notification_play : R.drawable.ic_notification_pause,
                    paused ? state.resumeLabel : state.pauseLabel, control(context, RestAlert.ACTION_PAUSE, 51));
            builder.addAction(R.drawable.ic_notification_add, state.plusLabel,
                    control(context, RestAlert.ACTION_PLUS, 53));
            builder.addAction(R.drawable.ic_notification_skip, state.skipLabel,
                    control(context, RestAlert.ACTION_SKIP, 54));
        } else if (state.setsTotal > 0) {
            builder.setProgress(state.setsTotal, Math.min(state.setsTotal, state.setsDone), false);
        }
        // A future HyperIsland adapter can merge its extras into this standard builder using
        // this same state and these PendingIntents, then retain this fallback on failure.
        return builder.build();
    }

    static NotificationCompat.Builder baseBuilder(Context context, WorkoutNotificationState state, String status) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        RestAlert.ensureCountdownChannel(context, manager);
        Intent open = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent dismissed = new Intent(context, WorkoutNotificationReceiver.class)
                .setAction(WorkoutNotification.ACTION_DISMISSED).putExtra("sessionId", state.sessionId);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, RestAlert.COUNTDOWN_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_dumbbell)
                .setContentTitle(state.title)
                .setContentText(status)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setColor(state.accent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(PendingIntent.getActivity(context, RestAlert.COUNTDOWN_ID + 1, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setDeleteIntent(PendingIntent.getBroadcast(context, 45, dismissed,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder;
    }

    static PendingIntent control(Context context, String action, int requestCode) {
        // Labels and theme come from persisted state at delivery time, including after process death.
        Intent intent = new Intent(context, RestTimerService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return Build.VERSION.SDK_INT >= 26
                ? PendingIntent.getForegroundService(context, requestCode, intent, flags)
                : PendingIntent.getService(context, requestCode, intent, flags);
    }

    private static boolean canPromote(Context context) {
        if (Build.VERSION.SDK_INT < 36) return false;
        try { return NotificationManagerCompat.from(context).canPostPromotedNotifications(); }
        catch (RuntimeException ignored) { return false; }
    }
}
