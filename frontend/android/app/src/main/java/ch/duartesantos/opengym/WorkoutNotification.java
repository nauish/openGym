package ch.duartesantos.opengym;

import android.app.Notification;
import android.content.Context;
import android.os.Build;
import androidx.core.app.NotificationManagerCompat;

/** Coordinates the single workout notification and its persisted state. */
final class WorkoutNotification {
    static final int ID = RestAlert.COUNTDOWN_ID;
    static final String ACTION_DISMISSED = "ch.duartesantos.opengym.workout.DISMISSED";
    static final String ACTION_COMPLETE_SET = "ch.duartesantos.opengym.workout.COMPLETE_SET";

    private WorkoutNotification() {}

    static synchronized void syncSession(Context context, String sessionId, String title,
                                         long startedAt, int setsDone, int setsTotal,
                                         String workoutText, String restText, String pausedLabel,
                                         String setSummary,
                                         String exerciseName, String exerciseImage, String setProgress, String completeLabel,
                                         String pause, String resume, String minus, String plus,
                                         String skip, int accent, int ink) {
        WorkoutNotificationStore.syncSession(context, sessionId, title, startedAt, setsDone, setsTotal,
                workoutText, restText, pausedLabel, setSummary,
                exerciseName, exerciseImage, setProgress, completeLabel,
                pause, resume, minus, plus, skip, accent, ink);
        postCurrent(context);
    }

    static synchronized void clearSession(Context context) {
        WorkoutNotificationStore.clearSession(context);
        RestAlert.cancelAlarmOnly(context, RestAlert.NOTIFICATION_ID);
        RestAlert.stopCountdown(context);
        NotificationManagerCompat.from(context).cancel(ID);
        NotificationManagerCompat.from(context).cancel(RestAlert.NOTIFICATION_ID);
    }

    static synchronized boolean isDismissed(Context context) {
        return WorkoutNotificationStore.isDismissed(context);
    }

    static synchronized void setRest(Context context, long endsAt, long totalMs,
                                     boolean paused, long leftMs, int accent, int ink) {
        WorkoutNotificationStore.setRest(context, endsAt, totalMs, paused, leftMs, accent, ink);
    }

    static synchronized WorkoutNotificationState savedState(Context context) {
        return WorkoutNotificationStore.read(context);
    }

    static synchronized void finishRest(Context context) {
        if (!WorkoutNotificationStore.finishRest(context)) return;
        WorkoutNotificationState state = savedState(context);
        if (state.sessionId.isEmpty()) NotificationManagerCompat.from(context).cancel(ID);
        else postCurrent(context);
    }

    static synchronized void markDismissed(Context context, String sessionId) {
        WorkoutNotificationStore.markDismissed(context, sessionId);
        if (isDismissed(context) && savedState(context).rest != null) RestAlert.stopCountdown(context);
    }

    static synchronized WorkoutNotificationRenderer.Rendered current(Context context) {
        return WorkoutNotificationRenderer.render(context, savedState(context));
    }

    static synchronized void postCurrent(Context context) {
        WorkoutNotificationState state = savedState(context);
        if (state.sessionId.isEmpty() || state.dismissed) return;
        // State changes (including locale/theme changes while paused) refresh every presentation.
        // The service additionally refreshes running legacy rest cards once per second.
        try {
            NotificationManagerCompat.from(context).notify(ID,
                    WorkoutNotificationRenderer.render(context, state).notification);
        } catch (SecurityException ignored) {
            // Denied notification permission does not cancel the rest alarm.
        }
    }
}
