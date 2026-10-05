package ch.duartesantos.opengym;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Pure action-plan rules shared by the Android store and local JVM regression tests. */
final class WorkoutActionPlan {
    final String sessionId;
    final List<Action> actions;
    int nextIndex;

    WorkoutActionPlan(String sessionId, List<Action> actions, int nextIndex) {
        this.sessionId = sessionId == null ? "" : sessionId;
        this.actions = actions == null ? Collections.emptyList() : new ArrayList<>(actions);
        this.nextIndex = Math.max(0, Math.min(nextIndex, this.actions.size()));
    }

    Action expected() {
        return nextIndex < actions.size() ? actions.get(nextIndex) : null;
    }

    /** Consumes only the current action for the matching active session, and advances once. */
    Action consume(String requestedSessionId, String requestedActionId, String mode, boolean finalizing) {
        if (finalizing || !"workout".equals(mode) || sessionId.isEmpty()
                || !sessionId.equals(requestedSessionId) || requestedActionId == null) return null;
        Action action = expected();
        if (action == null || action.actionId.isEmpty() || !action.actionId.equals(requestedActionId)) return null;
        nextIndex++;
        return action;
    }

    static boolean appendPending(List<Pending> pending, Pending value) {
        if (pending == null || value == null || value.sessionId.isEmpty() || value.actionId.isEmpty()) return false;
        for (Pending item : pending) {
            if (item.sameIdentity(value)) return false;
        }
        pending.add(value);
        return true;
    }

    static boolean removePending(List<Pending> pending, String sessionId, String actionId) {
        if (pending == null || sessionId == null || actionId == null) return false;
        for (int i = 0; i < pending.size(); i++) {
            if (pending.get(i).sessionId.equals(sessionId) && pending.get(i).actionId.equals(actionId)) {
                pending.remove(i);
                return true;
            }
        }
        return false;
    }

    static Transition transition(Action action) {
        if (action == null) return new Transition("workout", false, 0);
        if (action.finalAction) return new Transition("workout", true, 0);
        if (action.restMs > 0) return new Transition("rest", false, action.restMs);
        return new Transition("workout", false, 0);
    }

    /** Notification snapshot for the action that becomes available after the active rest ends. */
    Map<String, Object> notificationAfterRest() {
        Action next = expected();
        return next == null ? Collections.emptyMap() : next.beforeNotification;
    }

    static boolean shouldRecoverRest(String markedSessionId, String markedActionId,
                                    String activeSessionId, boolean hasRest,
                                    boolean paused, boolean dismissed) {
        return hasRest && !paused && !dismissed
                && markedSessionId != null && !markedSessionId.isEmpty()
                && markedSessionId.equals(activeSessionId)
                && markedActionId != null && !markedActionId.isEmpty();
    }

    static final class Transition {
        final String mode;
        final boolean finalizing;
        final long restMs;

        Transition(String mode, boolean finalizing, long restMs) {
            this.mode = mode;
            this.finalizing = finalizing;
            this.restMs = restMs;
        }
    }

    static final class Action {
        final String actionId;
        final long restMs;
        final Map<String, Object> notification;
        final Map<String, Object> beforeNotification;
        final boolean finalAction;
        final Map<String, Object> restAlert;

        Action(String actionId, long restMs, Map<String, Object> notification,
               boolean finalAction, Map<String, Object> restAlert) {
            this(actionId, restMs, notification, finalAction, restAlert, Collections.emptyMap());
        }

        Action(String actionId, long restMs, Map<String, Object> notification,
               boolean finalAction, Map<String, Object> restAlert,
               Map<String, Object> beforeNotification) {
            this.actionId = actionId == null ? "" : actionId;
            this.restMs = Math.max(0, restMs);
            this.notification = notification == null ? Collections.emptyMap() : notification;
            this.beforeNotification = beforeNotification == null ? Collections.emptyMap() : beforeNotification;
            this.finalAction = finalAction;
            this.restAlert = restAlert == null ? Collections.emptyMap() : restAlert;
        }
    }

    static final class Pending {
        final String sessionId;
        final String actionId;
        final boolean finalAction;

        Pending(String sessionId, String actionId, boolean finalAction) {
            this.sessionId = sessionId == null ? "" : sessionId;
            this.actionId = actionId == null ? "" : actionId;
            this.finalAction = finalAction;
        }

        boolean sameIdentity(Pending other) {
            return sessionId.equals(other.sessionId) && actionId.equals(other.actionId);
        }
    }
}
