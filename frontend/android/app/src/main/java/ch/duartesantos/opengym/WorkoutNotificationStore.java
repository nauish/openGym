package ch.duartesantos.opengym;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent session and rest state. No service or notification side effects. */
final class WorkoutNotificationStore {
    private static final String PREFS = "workout_notification";
    private static final String KEY_SESSION = "sessionId";
    private static final String KEY_MODE = "mode";
    private static final String MODE_WORKOUT = "workout";
    private static final String MODE_REST = "rest";
    private static final String MODE_INACTIVE = "inactive";

    private WorkoutNotificationStore() {}

    static synchronized void syncSession(Context context, String sessionId, String title,
                                         long startedAt, int setsDone, int setsTotal,
                                         String workoutText, String restText, String pausedLabel,
                                         String setSummary,
                                         String exerciseName, String exerciseImage, String setProgress, String completeLabel,
                                         String pause, String resume, String minus, String plus,
                                         String skip, int accent, int ink) {
        SharedPreferences prefs = prefs(context);
        String previousId = prefs.getString(KEY_SESSION, "");
        boolean isNewSession = !sessionId.equals(previousId);
        SharedPreferences.Editor edit = prefs.edit()
                .putString(KEY_SESSION, sessionId)
                .putString("title", title)
                .putLong("startedAt", Math.max(0, startedAt))
                .putInt("setsDone", Math.max(0, setsDone))
                .putInt("setsTotal", Math.max(0, setsTotal))
                .putString("workoutText", workoutText)
                .putString("restText", restText)
                .putString("pausedLabel", pausedLabel)
                .putString("setSummary", setSummary)
                .putString("exerciseName", exerciseName)
                .putString("exerciseImage", exerciseImage)
                .putString("setProgress", setProgress)
                .putString("completeSetLabel", completeLabel)
                .putString("pauseLabel", pause)
                .putString("resumeLabel", resume)
                .putString("minusLabel", minus)
                .putString("plusLabel", plus)
                .putString("skipLabel", skip)
                .putInt("accent", accent)
                .putInt("ink", ink);
        if (isNewSession) {
            edit.putString(KEY_MODE, MODE_WORKOUT)
                    .putString("dismissedSessionId", "")
                    .remove("restEndsAt")
                    .remove("restTotalMs")
                    .remove("restLeftMs")
                    .remove("restPaused");
        } else if (prefs.getString(KEY_MODE, MODE_WORKOUT).equals(MODE_INACTIVE)) {
            edit.putString(KEY_MODE, MODE_WORKOUT);
        }
        edit.apply();
    }

    static synchronized void clearSession(Context context) {
        prefs(context).edit().clear().putString(KEY_MODE, MODE_INACTIVE).apply();
    }

    static synchronized boolean isDismissed(Context context) {
        SharedPreferences p = prefs(context);
        String id = p.getString(KEY_SESSION, "");
        return !id.isEmpty() && id.equals(p.getString("dismissedSessionId", ""));
    }

    static synchronized void setRest(Context context, long endsAt, long totalMs,
                                     boolean paused, long leftMs, int accent, int ink) {
        prefs(context).edit()
                .putString(KEY_MODE, MODE_REST)
                .putLong("restEndsAt", Math.max(0, endsAt))
                .putLong("restTotalMs", Math.max(1000, totalMs))
                .putLong("restLeftMs", Math.max(0, leftMs))
                .putBoolean("restPaused", paused)
                .putInt("accent", accent)
                .putInt("ink", ink)
                .apply();
    }

    static synchronized WorkoutNotificationState read(Context context) {
        return new WorkoutNotificationState(prefs(context));
    }

    static synchronized boolean finishRest(Context context) {
        SharedPreferences p = prefs(context);
        if (!MODE_REST.equals(p.getString(KEY_MODE, MODE_INACTIVE))) return false;
        boolean activeSession = !p.getString(KEY_SESSION, "").isEmpty();
        p.edit().putString(KEY_MODE, activeSession ? MODE_WORKOUT : MODE_INACTIVE)
                .remove("restEndsAt").remove("restTotalMs").remove("restLeftMs").remove("restPaused")
                .apply();
        return true;
    }

    static synchronized void markDismissed(Context context, String sessionId) {
        SharedPreferences p = prefs(context);
        if (sessionId == null || sessionId.isEmpty()
                || !sessionId.equals(p.getString(KEY_SESSION, ""))) return;
        p.edit().putString("dismissedSessionId", sessionId).apply();
    }

    static synchronized void updateLabels(Context context, String pause, String resume,
                                          String minus, String plus, String skip) {
        prefs(context).edit().putString("pauseLabel", pause).putString("resumeLabel", resume)
                .putString("minusLabel", minus).putString("plusLabel", plus).putString("skipLabel", skip).apply();
    }

    static synchronized void updateTheme(Context context, int accent, int ink) {
        prefs(context).edit().putInt("accent", accent).putInt("ink", ink).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
