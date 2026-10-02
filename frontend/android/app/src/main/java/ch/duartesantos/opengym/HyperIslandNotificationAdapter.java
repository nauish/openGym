package ch.duartesantos.opengym;

import android.content.Context;
import android.graphics.drawable.Icon;
import android.widget.RemoteViews;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import io.github.d4viddf.hyperisland_kit.HyperIslandNotification;
import io.github.d4viddf.hyperisland_kit.HyperPicture;
import io.github.d4viddf.hyperisland_kit.models.TimerInfo;

/** Owns the HyperIsland API and payload format; workout state and commands stay in app code. */
final class HyperIslandNotificationAdapter {
    private static final String TAG = "openGym";
    private static final String PICTURE_KEY = "workout";
    private static final String BUSINESS_ID = "opengym_workout";
    private static boolean unsupportedLogged;
    private static boolean payloadFailureLogged;

    private HyperIslandNotificationAdapter() {}

    /** Returns null when unsupported or when the vendor payload cannot be built. */
    static Bundle buildExtras(Context context, WorkoutNotificationState state, long now) {
        // The published 0.4.4 AAR declares API 26 even though the app's minSdk remains 23.
        if (Build.VERSION.SDK_INT < 26) return null;

        try {
            if (!HyperIslandNotification.Companion.isSupported(context)) {
                logUnsupportedOnce();
                return null;
            }

            TimerInfo timer = timerFor(state, now);
            boolean rest = state.rest != null;
            boolean paused = rest && state.rest.paused;
            int icon = rest ? R.drawable.ic_stat_timer : R.drawable.ic_stat_dumbbell;
            String setLabel = state.setProgress.isEmpty() ? state.setSummary : state.setProgress;
            if (setLabel.isEmpty()) setLabel = state.workoutText;
            String islandLabel = rest ? state.restText : setLabel;
            String pausedClock = paused ? RestAlert.clock(
                    (int) Math.ceil(state.rest.remainingAt(now) / 1000.0)) : null;
            RemoteViews card = WorkoutIslandCard.build(context, state, now);
            HyperIslandNotification island = HyperIslandNotification.Companion.Builder(context, BUSINESS_ID, "")
                    .setSmallWindowTarget(MainActivity.class.getName())
                    .addPicture(new HyperPicture(PICTURE_KEY, context, icon))
                    .setAodConfig(islandLabel, Icon.createWithResource(context, icon))
                    .setCustomRemoteView(card)
                    .setCustomNightRemoteView(card)
                    .setCustomIslandExpandRemoteView(card);
            Bundle extras = island.buildCustomExtras();
            JSONObject params = new JSONObject(extras.getString("miui.focus.param.custom"));
            params.put("param_island", new JSONObject(WorkoutIslandComponents.build(
                    islandLabel, PICTURE_KEY, timer, pausedClock)));
            extras.putString("miui.focus.param.custom", params.toString());
            synchronized (HyperIslandNotificationAdapter.class) {
                unsupportedLogged = false;
                payloadFailureLogged = false;
            }
            return extras;
        } catch (RuntimeException | LinkageError | JSONException e) {
            synchronized (HyperIslandNotificationAdapter.class) {
                if (!payloadFailureLogged) {
                    Log.w(TAG, "HyperIsland payload unavailable; using Android notification", e);
                    payloadFailureLogged = true;
                }
            }
            return null;
        }
    }

    private static void logUnsupportedOnce() {
        synchronized (HyperIslandNotificationAdapter.class) {
            if (unsupportedLogged) return;
            Log.d(TAG, "HyperIsland support check returned false; using Android notification");
            unsupportedLogged = true;
        }
    }

    private static TimerInfo timerFor(WorkoutNotificationState state, long now) {
        if (state.rest == null) {
            // A chronometer's base is the saved session start; no fixed total is applicable.
            return new TimerInfo(1, state.startedAt, 0, now);
        }
        long when = state.rest.paused ? now + state.rest.pausedLeftMs : state.rest.endsAt;
        int type = state.rest.paused ? -2 : -1;
        return new TimerInfo(type, when, state.rest.totalMs, now);
    }

}
