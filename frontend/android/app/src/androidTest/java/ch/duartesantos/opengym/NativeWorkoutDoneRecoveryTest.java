package ch.duartesantos.opengym;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationManagerCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises a notification Done tap using app-private persisted state and no WebView. */
@RunWith(AndroidJUnit4.class)
public final class NativeWorkoutDoneRecoveryTest {
    private Context context;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        clearNativeState();
    }

    @After public void tearDown() {
        clearNativeState();
    }

    @Test public void doneCommitsRestBeforeReturningAndQueueSurvivesAContextRestart() throws Exception {
        final String sessionId = "instrumented-session";
        final String firstActionId = "set-1";
        final long restMs = 45_000L;
        JSONObject notification = new JSONObject()
                .put("sessionId", sessionId)
                .put("title", "Workout")
                .put("workoutText", "Workout")
                .put("restText", "Rest")
                .put("pausedLabel", "Paused")
                .put("setSummary", "Bench press")
                .put("exerciseName", "Bench press")
                .put("setProgress", "1 of 2")
                .put("completeLabel", "Done")
                .put("pause", "Pause")
                .put("resume", "Resume")
                .put("minus", "− 15s")
                .put("plus", "+ 15s")
                .put("skip", "Skip")
                .put("setsDone", 1)
                .put("setsTotal", 2)
                .put("startedAt", System.currentTimeMillis())
                .put("accent", 0xFF30D158)
                .put("ink", 0xFF000000)
                .put("restForIdx", 0);
        JSONObject restAlert = new JSONObject()
                .put("title", "Rest over — next set!")
                .put("countdownTitle", "Rest")
                .put("channelId", RestAlert.CHANNEL_ID)
                .put("visibility", "public")
                .put("importance", "high")
                .put("sound", false)
                .put("vibrate", false)
                .put("localOnly", true);
        JSONObject firstAction = new JSONObject()
                .put("actionId", firstActionId)
                .put("restMs", restMs)
                .put("notification", notification)
                .put("final", false)
                .put("restAlert", restAlert);
        JSONObject finalAction = new JSONObject()
                .put("actionId", "set-2")
                .put("restMs", 0)
                .put("notification", new JSONObject(notification.toString()).put("setsDone", 2))
                .put("beforeNotification", new JSONObject(notification.toString())
                        .put("exerciseName", "Bench press")
                        .put("setProgress", "Set 2/2"))
                .put("final", true);
        JSONObject actionPlan = new JSONObject()
                .put("sessionId", sessionId)
                .put("actions", new JSONArray().put(firstAction).put(finalAction));

        WorkoutNotification.syncSession(context, sessionId, "Workout", System.currentTimeMillis(),
                0, 2, "Workout", "Rest", "Paused", "Bench press", "Bench press", "",
                "1 of 2", "Done", "Pause", "Resume", "− 15s", "+ 15s", "Skip",
                0xFF30D158, 0xFF000000, actionPlan);
        assertEquals(firstActionId, WorkoutNotificationStore.expectedActionId(context));

        Intent done = new Intent(context, WorkoutNotificationReceiver.class)
                .setAction(WorkoutNotification.ACTION_COMPLETE_SET)
                .putExtra("sessionId", sessionId)
                .putExtra("actionId", firstActionId);
        long before = System.currentTimeMillis();
        new WorkoutNotificationReceiver().onReceive(context, done);

        WorkoutNotificationState accepted = WorkoutNotification.savedState(context);
        assertEquals(sessionId, accepted.sessionId);
        assertNotNull("native Done must move the persisted card into rest immediately", accepted.rest);
        assertEquals(restMs, accepted.rest.totalMs);
        assertTrue(accepted.rest.endsAt >= before + restMs - 1000L);
        assertTrue(accepted.rest.endsAt <= System.currentTimeMillis() + restMs);
        assertEquals(1, accepted.setsDone);
        assertEquals("Rest", WorkoutNotification.current(context).notification.extras
                .getCharSequence(Notification.EXTRA_TITLE).toString());

        // If notifications are enabled, assert that the receiver posted the updated workout card.
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            boolean workoutCardPosted = false;
            if (Build.VERSION.SDK_INT >= 23) {
                for (android.service.notification.StatusBarNotification active
                        : NotificationManagerCompat.from(context).getActiveNotifications()) {
                    if (active.getId() == WorkoutNotification.ID) workoutCardPosted = true;
                }
            }
            assertTrue("the Done receiver should immediately repost the rest-state workout card", workoutCardPosted);
        }

        // A stale card still carries set-1. It cannot consume the next action or reset this rest.
        long acceptedDeadline = accepted.rest.endsAt;
        new WorkoutNotificationReceiver().onReceive(context, done);
        WorkoutNotificationState replayed = WorkoutNotification.savedState(context);
        assertEquals(1, replayed.setsDone);
        assertEquals(acceptedDeadline, replayed.rest.endsAt);
        assertEquals("the next Done remains unavailable during rest", "",
                WorkoutNotificationStore.expectedActionId(context));
        WorkoutNotification.finishRest(context);
        assertEquals("the plan pointer advanced exactly once", "set-2",
                WorkoutNotificationStore.expectedActionId(context));
        assertEquals("rest completion switches the card to the next set",
                "Set 2/2", WorkoutNotification.savedState(context).setProgress);

        // A distinct package Context reopens the same SharedPreferences, modeling the state read
        // by the next process start after the notification receiver has finished.
        Context reopened = context.createPackageContext(context.getPackageName(), 0);
        JSONArray pending = WorkoutNotification.pendingDone(reopened);
        assertEquals(1, pending.length());
        assertEquals(sessionId, pending.getJSONObject(0).getString("sessionId"));
        assertEquals(firstActionId, pending.getJSONObject(0).getString("actionId"));
        assertFalse(pending.getJSONObject(0).getBoolean("final"));
    }

    private void clearNativeState() {
        if (context == null) return;
        RestAlert.stopCountdown(context);
        RestAlert.cancelAlarmOnly(context, RestAlert.NOTIFICATION_ID);
        context.stopService(new Intent(context, RestTimerService.class));
        NotificationManagerCompat.from(context).cancel(WorkoutNotification.ID);
        NotificationManagerCompat.from(context).cancel(RestAlert.NOTIFICATION_ID);
        context.getSharedPreferences("workout_notification", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("rest_alert_settings", Context.MODE_PRIVATE).edit().clear().commit();
    }
}
