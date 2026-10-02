package ch.duartesantos.opengym;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/** Presentation boundary. Device-specific extras belong here, never in the timer or store. */
final class WorkoutNotificationRenderer {
    private WorkoutNotificationRenderer() {}

    static final class Rendered {
        final Notification notification;
        final boolean needsPeriodicUpdates;

        Rendered(Notification notification, boolean needsPeriodicUpdates) {
            this.notification = notification;
            this.needsPeriodicUpdates = needsPeriodicUpdates;
        }
    }

    static Rendered render(Context context, WorkoutNotificationState state) {
        long now = System.currentTimeMillis();
        String status = state.rest == null ? state.workoutText : state.restText;
        boolean paused = state.rest != null && state.rest.paused;
        if (paused) status = state.pausedLabel + " · "
                + RestAlert.clock((int) Math.ceil(state.rest.remainingAt(now) / 1000.0));

        Bundle hyperIslandExtras = Build.VERSION.SDK_INT >= 26
                ? HyperIslandNotificationAdapter.buildExtras(context, state, now)
                : null;
        if (hyperIslandExtras != null) {
            try {
                // The native chronometer keeps the notification-card countdown live on MIUI;
                // HyperIsland TimerInfo drives the pill from the same persisted deadline.
                // Do not request promoted ongoing when HyperIsland is active, as MIUI redirects
                // promoted notifications to generic liveupdate and suppresses the island.
                NotificationCompat.Builder builder = standardBuilder(context, state, status, now, false);
                builder.addExtras(hyperIslandExtras);
                return new Rendered(builder.build(), false);
            } catch (RuntimeException | LinkageError e) {
                Log.w("openGym", "Cannot attach HyperIsland payload; using Android notification", e);
            }
        }

        if (state.rest != null && Build.VERSION.SDK_INT < 36) {
            return new Rendered(LegacyRestNotification.render(context, state, now), true);
        }
        return new Rendered(standardBuilder(context, state, status, now, true).build(), false);
    }

    private static NotificationCompat.Builder standardBuilder(Context context,
                                                              WorkoutNotificationState state,
                                                              String status, long now,
                                                              boolean allowPromote) {
        boolean paused = state.rest != null && state.rest.paused;
        boolean showNativeTimer = !paused;
        NotificationCompat.Builder builder = baseBuilder(context, state, status)
                .setWhen(state.rest == null ? state.startedAt : paused ? now : state.rest.endsAt)
                .setShowWhen(showNativeTimer)
                .setUsesChronometer(showNativeTimer)
                .setRequestPromotedOngoing(allowPromote && showNativeTimer && state.rest != null && canPromote(context));
        if (state.rest != null) {
            if (showNativeTimer) builder.setChronometerCountDown(true);
            builder.addAction(paused ? R.drawable.ic_notification_play : R.drawable.ic_notification_pause,
                    paused ? state.resumeLabel : state.pauseLabel, control(context, RestAlert.ACTION_PAUSE, 51));
            builder.addAction(R.drawable.ic_notification_add, state.plusLabel,
                    control(context, RestAlert.ACTION_PLUS, 53));
            builder.addAction(R.drawable.ic_notification_skip, state.skipLabel,
                    control(context, RestAlert.ACTION_SKIP, 54));
        } else {
            if (state.completeSetLabel != null && !state.completeSetLabel.isEmpty()) {
                builder.addAction(R.drawable.ic_notification_check, state.completeSetLabel,
                        completeControl(context, 55));
            }
            if (state.setsTotal > 0) {
                builder.setProgress(state.setsTotal, Math.min(state.setsTotal, state.setsDone), false);
            }
        }
        return builder;
    }

    static NotificationCompat.Builder baseBuilder(Context context, WorkoutNotificationState state, String status) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        RestAlert.ensureCountdownChannel(context, manager);
        Intent open = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent dismissed = new Intent(context, WorkoutNotificationReceiver.class)
                .setAction(WorkoutNotification.ACTION_DISMISSED).putExtra("sessionId", state.sessionId);
        boolean isRest = state.rest != null;
        String title;
        String content;
        String subText;

        if (isRest) {
            title = state.restText;
            content = status;
            subText = state.setSummary;
        } else if (state.exerciseName != null && !state.exerciseName.isEmpty()) {
            title = state.setProgress.isEmpty() ? state.exerciseName
                    : state.setProgress + " · " + state.exerciseName;
            content = null;
            subText = state.title;
        } else {
            title = state.title;
            content = status;
            subText = state.setSummary;
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, RestAlert.COUNTDOWN_CHANNEL_ID)
                .setSmallIcon(isRest ? R.drawable.ic_stat_timer : R.drawable.ic_stat_dumbbell)
                .setContentTitle(title)
                .setContentText(content)
                .setSubText(subText)
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

    static PendingIntent completeControl(Context context, int requestCode) {
        Intent intent = new Intent(context, WorkoutNotificationReceiver.class)
                .setAction(WorkoutNotification.ACTION_COMPLETE_SET)
                .putExtra("sessionId", WorkoutNotification.savedState(context).sessionId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, requestCode, intent, flags);
    }

    private static boolean canPromote(Context context) {
        if (Build.VERSION.SDK_INT < 36) return false;
        try { return NotificationManagerCompat.from(context).canPostPromotedNotifications(); }
        catch (RuntimeException ignored) { return false; }
    }
}
