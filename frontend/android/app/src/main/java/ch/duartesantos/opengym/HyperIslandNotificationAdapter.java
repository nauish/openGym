package ch.duartesantos.opengym;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import io.github.d4viddf.hyperisland_kit.HyperAction;
import io.github.d4viddf.hyperisland_kit.HyperIslandNotification;
import io.github.d4viddf.hyperisland_kit.HyperPicture;
import io.github.d4viddf.hyperisland_kit.models.TimerInfo;

/** Owns the HyperIsland API and payload format; workout state and commands stay in app code. */
final class HyperIslandNotificationAdapter {
    private static final String TAG = "openGym";
    private static final String PICTURE_KEY = "workout";
    private static final String CARD_PICTURE_KEY = "workout_card";
    private static final String APP_PICTURE_KEY = "app_badge";
    private static final String PROFILE_BADGE_KEY = "profile_badge";
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

            Bitmap artwork = WorkoutNotificationArtwork.get(context, state);
            TimerInfo timer = timerFor(state, now);
            boolean paused = state.rest != null && state.rest.paused;
            boolean isRest = state.rest != null;
            int iconRes = isRest ? R.drawable.ic_stat_timer : R.drawable.ic_stat_dumbbell;
            String setLabel = state.setProgress.isEmpty() ? state.setSummary : state.setProgress;
            if (setLabel.isEmpty()) setLabel = state.workoutText;
            String exerciseLabel = state.exerciseName.isEmpty() ? state.title : state.exerciseName;
            String cardTitle = isRest
                    ? paused ? state.restText + " · " + state.pausedLabel : state.restText
                    : setLabel + " · " + exerciseLabel;
            String cardContent = paused ? RestAlert.clock(
                    (int) Math.ceil(state.rest.remainingAt(now) / 1000.0)) : null;

            // Leave the ticker blank; rewriteBigIsland supplies the localized set label separately.
            HyperIslandNotification island = HyperIslandNotification.Companion.Builder(context, BUSINESS_ID, "")
                    .setSmallWindowTarget(MainActivity.class.getName())
                    .addPicture(new HyperPicture(PICTURE_KEY, context, iconRes))
                    .addPicture(!isRest && artwork != null
                            ? new HyperPicture(CARD_PICTURE_KEY, artwork)
                            : new HyperPicture(CARD_PICTURE_KEY, context, iconRes))
                    .addPicture(new HyperPicture(APP_PICTURE_KEY, context, context.getApplicationInfo().icon))
                    // ChatInfo defaults a missing appIconPkg to the posting app's badge.
                    // Xiaomi also resolves this field as a picture resource; supply a
                    // transparent icon so only the independent right-hand badge is visible.
                    .addPicture(new HyperPicture(PROFILE_BADGE_KEY, context,
                            android.R.drawable.screen_background_light_transparent))
                    .setPicInfo(1, APP_PICTURE_KEY)
                    .setSmallIsland(PICTURE_KEY)
                    // A pill dismissal only concerns the island. It does not cancel the rest.
                    .setIslandConfig(2, null, false, false, null, null, true);

            // Template 17's image/text body and bottom text-button layout, using the IM
            // body so its second line remains a system chronometer. Keep the current set
            // first in the heading so a long exercise name cannot hide it.
            island.setChatInfo(TextUtils.htmlEncode(cardTitle), cardContent,
                    CARD_PICTURE_KEY, null, "miui.focus.pic_" + PROFILE_BADGE_KEY, paused ? null : timer,
                    null, null, null, null, null);

            if (state.rest == null) {
                island.setBigIslandCountUp(state.startedAt, PICTURE_KEY);
                addWorkoutPrimaryAction(context, island, state);
            } else {
                long deadline = state.rest.paused
                        ? now + state.rest.pausedLeftMs
                        : state.rest.endsAt;
                island.setBigIslandCountdown(deadline, PICTURE_KEY);
                addRestPrimaryAction(context, island, state);
            }
            String json = rewriteBigIsland(island.buildJsonParam(), timer, state, now);
            Bundle resources = island.buildResourceBundle();
            Bundle pictures = resources.getBundle("miui.focus.pics");
            Bundle actions = resources.getBundle("miui.focus.actions");
            if (pictures == null || !pictures.containsKey("miui.focus.pic_" + PICTURE_KEY)
                    || !pictures.containsKey("miui.focus.pic_" + CARD_PICTURE_KEY)) {
                throw new JSONException("HyperIsland resource bundle is incomplete");
            }
            if (state.rest != null) {
                ensureRestActionBindings(json, actions);
            } else if (state.completeSetLabel != null && !state.completeSetLabel.isEmpty()) {
                ensureWorkoutActionBindings(json, actions);
            }
            Bundle extras = new Bundle(resources);
            extras.putString("miui.focus.param", json);
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

    /** Completes the Big Island timer fields and supplies its localized current-set label. */
    private static String rewriteBigIsland(String source, TimerInfo timer,
                                           WorkoutNotificationState state, long now) throws JSONException {
        JSONObject root = new JSONObject(source);
        JSONObject params = root.getJSONObject("param_v2");
        JSONObject island = params.getJSONObject("param_island");
        JSONObject big = island.getJSONObject("bigIslandArea");
        JSONObject digits = big.getJSONObject("sameWidthDigitInfo");
        writeTimer(digits.getJSONObject("timerInfo"), timer);
        // Do not attach a mode label to the timer: the collapsed pill should show one time only.
        digits.remove("content");
        if (state.rest != null && state.rest.paused) {
            digits.put("digit", RestAlert.clock(
                    (int) Math.ceil(state.rest.remainingAt(now) / 1000.0)));
        } else {
            digits.remove("digit");
        }

        JSONObject left = big.optJSONObject("imageTextInfoLeft");
        if (left != null) {
            JSONObject textInfo = new JSONObject();
            if (state.rest != null) {
                textInfo.put("title", state.restText);
            } else if (state.exerciseName != null && !state.exerciseName.isEmpty()) {
                // Only the current set goes on the capsule's left; the timer stays on the
                // right. Exercise/workout names belong to the larger notification card.
                textInfo.put("title", state.setProgress.isEmpty() ? state.setSummary : state.setProgress);
            } else {
                textInfo.put("title", state.setSummary);
            }
            left.put("textInfo", textInfo);
        }
        // Group counts belong to the Android notification card, not another Big Island footer.
        big.remove("progressTextInfo");
        return root.toString();
    }

    private static void writeTimer(JSONObject target, TimerInfo timer) throws JSONException {
        target.put("timerType", timer.getTimerType());
        target.put("timerWhen", timer.getTimerWhen());
        target.put("timerTotal", timer.getTimerTotal());
        target.put("timerSystemCurrent", timer.getTimerSystemCurrent());
    }

    private static void ensureRestActionBindings(String json, Bundle actions) throws JSONException {
        String toggleKey = "miui.focus.action_rest_toggle";
        String skipKey = "miui.focus.action_rest_skip";
        if (actions == null || !actions.containsKey(toggleKey) || !actions.containsKey(skipKey)) {
            throw new JSONException("HyperIsland rest action resources are missing");
        }

        JSONArray buttons = new JSONObject(json).getJSONObject("param_v2").optJSONArray("textButton");
        boolean toggleBound = false;
        boolean skipBound = false;
        if (buttons != null) {
            for (int i = 0; i < buttons.length(); i++) {
                JSONObject button = buttons.optJSONObject(i);
                if (button == null) continue;
                String actionIntent = button.optString("actionIntent");
                String action = button.optString("action");
                if (toggleKey.equals(actionIntent) && toggleKey.equals(action)) toggleBound = true;
                if (skipKey.equals(actionIntent) && skipKey.equals(action)) skipBound = true;
            }
        }
        if (!toggleBound || !skipBound) {
            throw new JSONException("HyperIsland rest text buttons are not bound to their resources");
        }
    }

    private static void addRestPrimaryAction(Context context, HyperIslandNotification island,
                                             WorkoutNotificationState state) {
        boolean paused = state.rest.paused;
        String background = color(state.accent);
        String foreground = color(state.ink);
        HyperAction toggle = new HyperAction("rest_toggle",
                paused ? state.resumeLabel : state.pauseLabel,
                WorkoutNotificationRenderer.control(context, RestAlert.ACTION_PAUSE, 51), 3,
                background, background, foreground, foreground);
        HyperAction skip = new HyperAction("rest_skip", state.skipLabel,
                WorkoutNotificationRenderer.control(context, RestAlert.ACTION_SKIP, 54), 3,
                background, background, foreground, foreground);
        // 0.4.4's setTextButtons creates references only. Register both PendingIntents as hidden
        // actions so buildResourceBundle includes them without duplicating a footer action row.
        island.addHiddenAction(toggle)
                .addHiddenAction(skip)
                .setTextButtons(toggle, skip);
    }

    private static void ensureWorkoutActionBindings(String json, Bundle actions) throws JSONException {
        String completeKey = "miui.focus.action_workout_complete";
        if (actions == null || !actions.containsKey(completeKey)) {
            throw new JSONException("HyperIsland workout action resources are missing");
        }

        JSONArray buttons = new JSONObject(json).getJSONObject("param_v2").optJSONArray("textButton");
        JSONObject button = buttons == null || buttons.length() != 1 ? null : buttons.optJSONObject(0);
        if (button == null || !completeKey.equals(button.optString("actionIntent"))
                || !completeKey.equals(button.optString("action"))
                || !button.optString("actionIcon", "").isEmpty()) {
            throw new JSONException("HyperIsland workout text button is not bound to its resource");
        }
    }

    private static void addWorkoutPrimaryAction(Context context, HyperIslandNotification island,
                                                WorkoutNotificationState state) {
        if (state.completeSetLabel == null || state.completeSetLabel.isEmpty()) return;
        String background = color(state.accent);
        String foreground = color(state.ink);
        HyperAction complete = new HyperAction("workout_complete",
                state.completeSetLabel,
                WorkoutNotificationRenderer.completeControl(context, 55), 3,
                background, background, foreground, foreground);
        island.addHiddenAction(complete)
                .setTextButtons(complete);
    }

    private static String color(int argb) {
        return String.format(java.util.Locale.ROOT, "#%06X", argb & 0xFFFFFF);
    }
}
