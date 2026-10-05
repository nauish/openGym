package ch.duartesantos.opengym;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Persistent session and rest state. No service or notification side effects. */
final class WorkoutNotificationStore {
    private static final String PREFS = "workout_notification";
    private static final String KEY_SESSION = "sessionId";
    private static final String KEY_MODE = "mode";
    private static final String MODE_WORKOUT = "workout";
    private static final String MODE_REST = "rest";
    private static final String MODE_INACTIVE = "inactive";
    private static final String KEY_ACTION_PLAN = "actionPlanJson";
    private static final String KEY_ACTION_INDEX = "actionPlanIndex";
    private static final String KEY_PENDING_DONE = "pendingDoneJson";

    private WorkoutNotificationStore() {}

    static synchronized void syncSession(Context context, String sessionId, String title,
                                         long startedAt, int setsDone, int setsTotal,
                                         String workoutText, String restText, String pausedLabel,
                                         String setSummary,
                                         String exerciseName, String exerciseImage, String setProgress, String completeLabel,
                                         String pause, String resume, String minus, String plus,
                                         String skip, int accent, int ink, JSONObject actionPlan) {
        SharedPreferences prefs = prefs(context);
        String previousId = prefs.getString(KEY_SESSION, "");
        boolean isNewSession = !sessionId.equals(previousId);
        // A delayed WebView render can arrive after a native Done tap. Keep the native-applied
        // snapshot and action pointer intact until JS has replayed and acknowledged every command.
        if (!isNewSession && !readPending(prefs).isEmpty()) return;

        String validPlan = serializePlan(actionPlan, sessionId);
        boolean keepFinalizing = !isNewSession && prefs.getBoolean("finalizing", false)
                && !hasNonFinalAction(actionPlan);
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
                .putInt("ink", ink)
                .putBoolean("finalizing", keepFinalizing);
        if (isNewSession) {
            edit.putString(KEY_MODE, MODE_WORKOUT)
                    .putString("dismissedSessionId", "")
                    .remove("restEndsAt")
                    .remove("restTotalMs")
                    .remove("restLeftMs")
                    .remove("restPaused")
                    .remove("restForIdx")
                    .remove("nativeRestSessionId")
                    .remove("nativeRestActionId")
                    .remove("nativeRestAlertJson");
        } else if (prefs.getString(KEY_MODE, MODE_WORKOUT).equals(MODE_INACTIVE)) {
            edit.putString(KEY_MODE, MODE_WORKOUT);
        }
        if (validPlan == null) edit.remove(KEY_ACTION_PLAN).putInt(KEY_ACTION_INDEX, 0);
        else edit.putString(KEY_ACTION_PLAN, validPlan).putInt(KEY_ACTION_INDEX, 0);
        edit.apply();
    }

    static synchronized void clearSession(Context context) {
        SharedPreferences p = prefs(context);
        if (!readPending(p).isEmpty()) return;
        // The empty queue key remains well-formed across the next session start.
        String pending = p.getString(KEY_PENDING_DONE, "[]");
        p.edit().clear().putString(KEY_MODE, MODE_INACTIVE).putString(KEY_PENDING_DONE, pending).commit();
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

    static synchronized String expectedActionId(Context context) {
        return expectedActionId(prefs(context));
    }

    static synchronized List<WorkoutActionPlan.Pending> pendingDone(Context context) {
        return readPending(prefs(context));
    }

    /**
     * Atomically records a Done command, advances the plan, applies its notification snapshot,
     * and persists the rest deadline before the receiver starts alarms or services.
     */
    static synchronized AcceptedAction acceptDone(Context context, String sessionId, String actionId) {
        SharedPreferences p = prefs(context);
        String mode = p.getString(KEY_MODE, MODE_INACTIVE);
        boolean finalizing = p.getBoolean("finalizing", false);
        String currentSession = p.getString(KEY_SESSION, "");
        if (sessionId == null || !sessionId.equals(currentSession)
                || sessionId.equals(p.getString("dismissedSessionId", ""))) return null;
        WorkoutActionPlan plan = readPlan(p);
        WorkoutActionPlan.Action action = plan == null ? null : plan.consume(sessionId, actionId, mode, finalizing);
        if (action == null) return null;

        List<WorkoutActionPlan.Pending> pending = readPending(p);
        WorkoutActionPlan.Pending command = new WorkoutActionPlan.Pending(sessionId, actionId, action.finalAction);
        if (!WorkoutActionPlan.appendPending(pending, command)) return null;

        long now = System.currentTimeMillis();
        WorkoutActionPlan.Transition transition = WorkoutActionPlan.transition(action);
        long restMs = transition.restMs;
        long endsAt = restMs > 0 ? now + restMs : 0;
        SharedPreferences.Editor edit = p.edit()
                .putInt(KEY_ACTION_INDEX, plan.nextIndex)
                .putString(KEY_PENDING_DONE, pendingJson(pending))
                .putBoolean("finalizing", transition.finalizing)
                .putString(KEY_MODE, transition.mode);
        applySnapshot(edit, action.notification);
        if (restMs > 0) {
            edit.putLong("restEndsAt", endsAt)
                    .putLong("restTotalMs", restMs)
                    .putLong("restLeftMs", restMs)
                    .putBoolean("restPaused", false)
                    .putString("nativeRestSessionId", sessionId)
                    .putString("nativeRestActionId", actionId)
                    .putString("nativeRestAlertJson", new JSONObject(action.restAlert).toString());
            Object owner = action.notification.get("restForIdx");
            if (owner instanceof Number) edit.putInt("restForIdx", ((Number) owner).intValue());
            else edit.remove("restForIdx");
        } else {
            edit.remove("restEndsAt").remove("restTotalMs").remove("restLeftMs")
                    .remove("restPaused").remove("restForIdx")
                    .remove("nativeRestSessionId").remove("nativeRestActionId").remove("nativeRestAlertJson");
        }
        if (!edit.commit()) return null;
        return new AcceptedAction(action, endsAt, restMs);
    }

    static synchronized boolean acknowledgeDone(Context context, String sessionId, String actionId) {
        SharedPreferences p = prefs(context);
        List<WorkoutActionPlan.Pending> pending = readPending(p);
        if (!WorkoutActionPlan.removePending(pending, sessionId, actionId)) return false;
        return p.edit().putString(KEY_PENDING_DONE, pendingJson(pending)).commit();
    }

    static synchronized boolean hasPendingDone(Context context) {
        return !readPending(prefs(context)).isEmpty();
    }

    static synchronized NativeRestRecovery nativeRestRecovery(Context context, WorkoutNotificationState state) {
        SharedPreferences p = prefs(context);
        String sessionId = p.getString("nativeRestSessionId", "");
        String actionId = p.getString("nativeRestActionId", "");
        if (state == null || !WorkoutActionPlan.shouldRecoverRest(sessionId, actionId,
                state.sessionId, state.rest != null, state.rest != null && state.rest.paused, state.dismissed)) return null;
        Map<String, Object> alert = new HashMap<>();
        try {
            alert = objectMap(new JSONObject(p.getString("nativeRestAlertJson", "{}")));
        } catch (JSONException ignored) { /* RestAlert uses safe defaults if old data is malformed. */ }
        return new NativeRestRecovery(sessionId, actionId, alert);
    }

    static final class NativeRestRecovery {
        final String sessionId, actionId;
        final Map<String, Object> restAlert;

        NativeRestRecovery(String sessionId, String actionId, Map<String, Object> restAlert) {
            this.sessionId = sessionId;
            this.actionId = actionId;
            this.restAlert = restAlert;
        }
    }

    static final class AcceptedAction {
        final WorkoutActionPlan.Action action;
        final long endsAt;
        final long restMs;

        AcceptedAction(WorkoutActionPlan.Action action, long endsAt, long restMs) {
            this.action = action;
            this.endsAt = endsAt;
            this.restMs = restMs;
        }
    }

    static synchronized boolean finishRest(Context context) {
        SharedPreferences p = prefs(context);
        if (!MODE_REST.equals(p.getString(KEY_MODE, MODE_INACTIVE))) return false;
        boolean activeSession = !p.getString(KEY_SESSION, "").isEmpty();
        SharedPreferences.Editor edit = p.edit().putString(KEY_MODE, activeSession ? MODE_WORKOUT : MODE_INACTIVE)
                .remove("restEndsAt").remove("restTotalMs").remove("restLeftMs")
                .remove("restPaused").remove("restForIdx")
                .remove("nativeRestSessionId").remove("nativeRestActionId").remove("nativeRestAlertJson");
        WorkoutActionPlan plan = readPlan(p);
        Map<String, Object> nextNotification = plan == null
                ? java.util.Collections.emptyMap() : plan.notificationAfterRest();
        if (!nextNotification.isEmpty()) applySnapshot(edit, nextNotification);
        return edit.commit();
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

    static String expectedActionId(SharedPreferences p) {
        if (p.getBoolean("finalizing", false) || MODE_REST.equals(p.getString(KEY_MODE, MODE_INACTIVE))) return "";
        WorkoutActionPlan plan = readPlan(p);
        WorkoutActionPlan.Action expected = plan == null ? null : plan.expected();
        return expected == null ? "" : expected.actionId;
    }

    private static String serializePlan(JSONObject raw, String sessionId) {
        if (raw == null || !sessionId.equals(raw.optString("sessionId", ""))) return null;
        JSONArray actions = raw.optJSONArray("actions");
        if (actions == null) return null;
        for (int i = 0; i < actions.length(); i++) {
            JSONObject action = actions.optJSONObject(i);
            JSONObject notification = action == null ? null : action.optJSONObject("notification");
            if (action == null || action.optString("actionId", "").isEmpty() || notification == null
                    || !sessionId.equals(notification.optString("sessionId", ""))) return null;
        }
        return raw.toString();
    }

    private static boolean hasNonFinalAction(JSONObject raw) {
        JSONArray actions = raw == null ? null : raw.optJSONArray("actions");
        JSONObject first = actions == null || actions.length() == 0 ? null : actions.optJSONObject(0);
        return first != null && !first.optBoolean("final", false);
    }

    private static WorkoutActionPlan readPlan(SharedPreferences p) {
        String raw = p.getString(KEY_ACTION_PLAN, "");
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray rows = root.optJSONArray("actions");
            if (rows == null) return null;
            List<WorkoutActionPlan.Action> actions = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) return null;
                actions.add(new WorkoutActionPlan.Action(
                        row.optString("actionId", ""),
                        Math.max(0, row.optLong("restMs", 0)),
                        objectMap(row.optJSONObject("notification")),
                        row.optBoolean("final", false),
                        objectMap(row.optJSONObject("restAlert")),
                        objectMap(row.optJSONObject("beforeNotification"))));
            }
            return new WorkoutActionPlan(root.optString("sessionId", ""), actions,
                    p.getInt(KEY_ACTION_INDEX, 0));
        } catch (JSONException ignored) {
            return null;
        }
    }

    private static Map<String, Object> objectMap(JSONObject object) {
        Map<String, Object> values = new HashMap<>();
        if (object == null) return values;
        JSONArray names = object.names();
        if (names == null) return values;
        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i, "");
            Object value = object.opt(key);
            if (value == JSONObject.NULL) value = null;
            if (value instanceof String || value instanceof Number || value instanceof Boolean || value == null) {
                values.put(key, value);
            }
        }
        return values;
    }

    private static void applySnapshot(SharedPreferences.Editor edit, Map<String, Object> values) {
        putString(edit, values, "sessionId");
        putString(edit, values, "title");
        putLong(edit, values, "startedAt");
        putInt(edit, values, "setsDone");
        putInt(edit, values, "setsTotal");
        putString(edit, values, "workoutText");
        putString(edit, values, "restText");
        putString(edit, values, "pausedLabel");
        putString(edit, values, "setSummary");
        putString(edit, values, "exerciseName");
        putString(edit, values, "exerciseImage");
        putString(edit, values, "setProgress");
        putString(edit, values, "completeLabel");
        putString(edit, values, "pause");
        putString(edit, values, "resume");
        putString(edit, values, "minus");
        putString(edit, values, "plus");
        putString(edit, values, "skip");
        putInt(edit, values, "accent");
        putInt(edit, values, "ink");
        // The JS schema uses public bridge keys; adapt them to the persisted renderer names.
        copyString(values, edit, "completeLabel", "completeSetLabel");
        copyString(values, edit, "pause", "pauseLabel");
        copyString(values, edit, "resume", "resumeLabel");
        copyString(values, edit, "minus", "minusLabel");
        copyString(values, edit, "plus", "plusLabel");
        copyString(values, edit, "skip", "skipLabel");
    }

    private static void putString(SharedPreferences.Editor edit, Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof String) edit.putString(key, (String) value);
    }

    private static void putLong(SharedPreferences.Editor edit, Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Number) edit.putLong(key, ((Number) value).longValue());
    }

    private static void putInt(SharedPreferences.Editor edit, Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Number) edit.putInt(key, ((Number) value).intValue());
    }

    private static void copyString(Map<String, Object> values, SharedPreferences.Editor edit,
                                   String source, String target) {
        Object value = values.get(source);
        if (value instanceof String) edit.putString(target, (String) value);
    }

    private static List<WorkoutActionPlan.Pending> readPending(SharedPreferences p) {
        List<WorkoutActionPlan.Pending> out = new ArrayList<>();
        try {
            JSONArray rows = new JSONArray(p.getString(KEY_PENDING_DONE, "[]"));
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                WorkoutActionPlan.Pending item = new WorkoutActionPlan.Pending(
                        row.optString("sessionId", ""), row.optString("actionId", ""),
                        row.optBoolean("final", false));
                WorkoutActionPlan.appendPending(out, item);
            }
        } catch (JSONException ignored) { /* Treat corrupt queue as empty; the plan still validates clicks. */ }
        return out;
    }

    private static String pendingJson(List<WorkoutActionPlan.Pending> pending) {
        JSONArray rows = new JSONArray();
        for (WorkoutActionPlan.Pending item : pending) {
            JSONObject row = new JSONObject();
            try {
                row.put("sessionId", item.sessionId);
                row.put("actionId", item.actionId);
                row.put("final", item.finalAction);
                rows.put(row);
            } catch (JSONException ignored) { /* primitive JSON values cannot fail */ }
        }
        return rows.toString();
    }
}
