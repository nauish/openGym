package ch.duartesantos.opengym;

import android.content.SharedPreferences;

/** Immutable input shared by every notification presentation, independent of device branding. */
final class WorkoutNotificationState {
    final String sessionId, title, workoutText, restText, pausedLabel, setSummary;
    final String exerciseName, exerciseImage, setProgress, completeSetLabel;
    final String pauseLabel, resumeLabel, minusLabel, plusLabel, skipLabel;
    final long startedAt;
    final int setsDone, setsTotal, accent, ink;
    final boolean dismissed;
    final Rest rest;

    WorkoutNotificationState(SharedPreferences p) {
        sessionId = p.getString("sessionId", "");
        title = text(p, "title", "Workout");
        workoutText = text(p, "workoutText", "Workout");
        restText = text(p, "restText", "Rest");
        pausedLabel = text(p, "pausedLabel", "Paused");
        setSummary = text(p, "setSummary", "");
        exerciseName = text(p, "exerciseName", "");
        exerciseImage = text(p, "exerciseImage", "");
        setProgress = text(p, "setProgress", "");
        completeSetLabel = text(p, "completeSetLabel", "");
        pauseLabel = text(p, "pauseLabel", "Pause");
        resumeLabel = text(p, "resumeLabel", "Resume");
        minusLabel = text(p, "minusLabel", "− 15s");
        plusLabel = text(p, "plusLabel", "+ 15s");
        skipLabel = text(p, "skipLabel", "Skip");
        startedAt = p.getLong("startedAt", 0);
        setsDone = p.getInt("setsDone", 0);
        setsTotal = p.getInt("setsTotal", 0);
        accent = p.getInt("accent", 0xFF30D158);
        ink = p.getInt("ink", 0xFF000000);
        dismissed = !sessionId.isEmpty() && sessionId.equals(p.getString("dismissedSessionId", ""));
        rest = "rest".equals(p.getString("mode", "inactive"))
                ? new Rest(p.getLong("restEndsAt", 0), p.getLong("restTotalMs", 1000),
                        p.getLong("restLeftMs", 0), p.getBoolean("restPaused", false)) : null;
    }

    static final class Rest {
        final long endsAt, totalMs, pausedLeftMs;
        final boolean paused;

        Rest(long endsAt, long totalMs, long pausedLeftMs, boolean paused) {
            this.endsAt = Math.max(0, endsAt);
            this.totalMs = Math.max(1000, totalMs);
            this.pausedLeftMs = Math.max(0, pausedLeftMs);
            this.paused = paused;
        }

        long remainingAt(long now) {
            return paused ? pausedLeftMs : Math.max(0, endsAt - now);
        }
    }

    private static String text(SharedPreferences p, String key, String fallback) {
        String value = p.getString(key, null);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
