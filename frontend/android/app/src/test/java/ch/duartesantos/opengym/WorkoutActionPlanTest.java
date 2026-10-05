package ch.duartesantos.opengym;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Regression coverage for the native Done action's session and replay guards. */
public class WorkoutActionPlanTest {
    @Test public void consumesOnlyTheExpectedActionForTheActiveWorkout() {
        Map<String, Object> nextNotification = new HashMap<>();
        nextNotification.put("setsDone", 1);
        nextNotification.put("sessionId", "workout-a");
        WorkoutActionPlan.Action first = new WorkoutActionPlan.Action("set-1", 60_000,
                nextNotification, false, Collections.singletonMap("totalMs", 60_000L));
        WorkoutActionPlan.Action second = new WorkoutActionPlan.Action("set-2", 0,
                Collections.emptyMap(), true, Collections.emptyMap());
        WorkoutActionPlan plan = new WorkoutActionPlan("workout-a", Arrays.asList(first, second), 0);

        assertNull(plan.consume("workout-b", "set-1", "workout", false));
        assertNull(plan.consume("workout-a", "set-2", "workout", false));
        assertNull(plan.consume("workout-a", "set-1", "rest", false));
        assertNull(plan.consume("workout-a", "set-1", "workout", true));
        assertEquals("set-1", plan.expected().actionId);

        WorkoutActionPlan.Action consumed = plan.consume("workout-a", "set-1", "workout", false);
        assertSame(first, consumed);
        assertEquals(60_000L, consumed.restMs);
        assertEquals(1, consumed.notification.get("setsDone"));
        assertEquals(60_000L, consumed.restAlert.get("totalMs"));
        assertEquals("set-2", plan.expected().actionId);
        // Replaying an already consumed PendingIntent cannot consume the next set.
        assertNull(plan.consume("workout-a", "set-1", "workout", false));
        assertEquals("set-2", plan.expected().actionId);
    }

    @Test public void pendingDoneQueueIsIdempotentAndAcknowledgesOnlyItsIdentity() {
        List<WorkoutActionPlan.Pending> pending = new ArrayList<>();
        WorkoutActionPlan.Pending first = new WorkoutActionPlan.Pending("workout-a", "set-1", false);
        WorkoutActionPlan.Pending finalAction = new WorkoutActionPlan.Pending("workout-a", "set-2", true);

        assertTrue(WorkoutActionPlan.appendPending(pending, first));
        assertFalse(WorkoutActionPlan.appendPending(pending,
                new WorkoutActionPlan.Pending("workout-a", "set-1", false)));
        assertTrue(WorkoutActionPlan.appendPending(pending, finalAction));
        assertEquals(2, pending.size());

        assertFalse(WorkoutActionPlan.removePending(pending, "workout-a", "unknown"));
        assertEquals(2, pending.size());
        assertTrue(WorkoutActionPlan.removePending(pending, "workout-a", "set-1"));
        assertEquals(1, pending.size());
        assertEquals("set-2", pending.get(0).actionId);
        assertTrue("final completion remains marked for the JS finish flow", pending.get(0).finalAction);
        assertFalse(WorkoutActionPlan.removePending(pending, "workout-b", "set-2"));
        assertEquals(1, pending.size());
    }

    @Test public void aRestoredPlanStartsAtThePersistedNextAction() {
        WorkoutActionPlan.Action first = new WorkoutActionPlan.Action("set-1", 60_000,
                Collections.emptyMap(), false, Collections.emptyMap());
        WorkoutActionPlan.Action last = new WorkoutActionPlan.Action("set-2", 0,
                Collections.emptyMap(), true, Collections.emptyMap());

        // The store persists the plan and pointer together. Reconstructing with pointer 1 models
        // the receiver process starting again after set-1 was accepted but before the WebView ran.
        WorkoutActionPlan restored = new WorkoutActionPlan("workout-a", Arrays.asList(first, last), 1);
        assertEquals("set-2", restored.expected().actionId);
        assertNull(restored.consume("workout-a", "set-1", "workout", false));
        assertTrue(restored.consume("workout-a", "set-2", "workout", false).finalAction);
        assertNull(restored.expected());
    }

    @Test public void restCompletionUsesTheNextActionsPreCompletionNotification() {
        Map<String, Object> duringRest = Collections.singletonMap("setProgress", "Set 2/3");
        Map<String, Object> nextSet = Collections.singletonMap("setProgress", "Set 3/3");
        WorkoutActionPlan.Action completingSetTwo = new WorkoutActionPlan.Action("set-2", 60_000,
                duringRest, false, Collections.emptyMap());
        WorkoutActionPlan.Action completingSetThree = new WorkoutActionPlan.Action("set-3", 0,
                Collections.emptyMap(), true, Collections.emptyMap(), nextSet);
        WorkoutActionPlan plan = new WorkoutActionPlan("workout-a",
                Arrays.asList(completingSetTwo, completingSetThree), 1);

        assertEquals("Set 3/3", plan.notificationAfterRest().get("setProgress"));
    }

    @Test public void nativeTransitionArmsRestOnlyWhenTheActionEarnsOne() {
        WorkoutActionPlan.Transition ordinary = WorkoutActionPlan.transition(
                new WorkoutActionPlan.Action("set-1", 45_000, Collections.emptyMap(), false, Collections.emptyMap()));
        assertEquals("rest", ordinary.mode);
        assertEquals(45_000L, ordinary.restMs);
        assertFalse(ordinary.finalizing);

        WorkoutActionPlan.Transition restOff = WorkoutActionPlan.transition(
                new WorkoutActionPlan.Action("set-2", 0, Collections.emptyMap(), false, Collections.emptyMap()));
        assertEquals("workout", restOff.mode);
        assertEquals(0L, restOff.restMs);
        assertFalse(restOff.finalizing);

        WorkoutActionPlan.Transition finished = WorkoutActionPlan.transition(
                new WorkoutActionPlan.Action("set-3", 90_000, Collections.emptyMap(), true, Collections.emptyMap()));
        assertEquals("workout", finished.mode);
        assertEquals(0L, finished.restMs);
        assertTrue(finished.finalizing);
    }

    @Test public void sameActionIdInAnotherSessionIsASeparatePendingCommand() {
        List<WorkoutActionPlan.Pending> pending = new ArrayList<>();

        assertTrue(WorkoutActionPlan.appendPending(pending,
                new WorkoutActionPlan.Pending("workout-a", "set-1", false)));
        assertTrue(WorkoutActionPlan.appendPending(pending,
                new WorkoutActionPlan.Pending("workout-b", "set-1", false)));
        assertEquals(2, pending.size());
        assertTrue(WorkoutActionPlan.removePending(pending, "workout-a", "set-1"));
        assertEquals("workout-b", pending.get(0).sessionId);
    }

    @Test public void restAlarmRecoveryRequiresAnActiveMatchingNativeRest() {
        assertTrue(WorkoutActionPlan.shouldRecoverRest(
                "workout-a", "set-1", "workout-a", true, false, false));

        // A timer can be recovered only from the native action marker that owns the session.
        assertFalse(WorkoutActionPlan.shouldRecoverRest(null, "set-1", "workout-a", true, false, false));
        assertFalse(WorkoutActionPlan.shouldRecoverRest("workout-a", null, "workout-a", true, false, false));
        assertFalse(WorkoutActionPlan.shouldRecoverRest("workout-b", "set-1", "workout-a", true, false, false));
        assertFalse(WorkoutActionPlan.shouldRecoverRest("workout-a", "set-1", "workout-a", false, false, false));
        assertFalse(WorkoutActionPlan.shouldRecoverRest("workout-a", "set-1", "workout-a", true, true, false));
        assertFalse(WorkoutActionPlan.shouldRecoverRest("workout-a", "set-1", "workout-a", true, false, true));
    }
}
