package ch.duartesantos.opengym;

import android.app.Notification;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Build;
import android.widget.RemoteViews;
import androidx.core.app.NotificationCompat;

/** Existing countdown card for Android versions without the standard Live Update presentation. */
final class LegacyRestNotification {
    private LegacyRestNotification() {}

    static Notification render(Context ctx, WorkoutNotificationState state, long now) {
        WorkoutNotificationState.Rest rest = state.rest;
        int accent = state.accent;
        int ink = state.ink;
        int max = (int) Math.max(1, Math.round(rest.totalMs / 1000.0));
        int left = (int) Math.min(max, (rest.remainingAt(now) + 999) / 1000);
        String clock = RestAlert.clock(left);
        RemoteViews compact = new RemoteViews(ctx.getPackageName(), R.layout.rest_countdown);
        fillClock(compact, clock, max, left, accent);
        RemoteViews expanded = new RemoteViews(ctx.getPackageName(), R.layout.rest_countdown_big);
        fillClock(expanded, clock, max, left, accent);
        applyAccent(expanded, accent, ink);
        expanded.setTextViewText(R.id.rest_pause, rest.paused ? state.resumeLabel : state.pauseLabel);
        expanded.setTextViewText(R.id.rest_minus, state.minusLabel);
        expanded.setTextViewText(R.id.rest_plus, state.plusLabel);
        expanded.setTextViewText(R.id.rest_skip, state.skipLabel);
        expanded.setOnClickPendingIntent(R.id.rest_pause, WorkoutNotificationRenderer.control(ctx, RestAlert.ACTION_PAUSE, 51));
        expanded.setOnClickPendingIntent(R.id.rest_minus, WorkoutNotificationRenderer.control(ctx, RestAlert.ACTION_MINUS, 52));
        expanded.setOnClickPendingIntent(R.id.rest_plus, WorkoutNotificationRenderer.control(ctx, RestAlert.ACTION_PLUS, 53));
        expanded.setOnClickPendingIntent(R.id.rest_skip, WorkoutNotificationRenderer.control(ctx, RestAlert.ACTION_SKIP, 54));
        return WorkoutNotificationRenderer.baseBuilder(ctx, state, clock)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setCustomContentView(compact)
                .setCustomBigContentView(expanded)
                .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                .build();
    }

    private static void fillClock(RemoteViews views, String clock, int max, int left, int accent) {
        views.setTextViewText(R.id.rest_clock, clock);
        views.setProgressBar(R.id.rest_bar, max, left, false);
        if (Build.VERSION.SDK_INT >= 31) {
            views.setColorStateList(R.id.rest_bar, "setProgressTintList", ColorStateList.valueOf(accent));
        }
    }

    private static void applyAccent(RemoteViews views, int accent, int ink) {
        views.setTextColor(R.id.rest_pause, accent);
        views.setTextColor(R.id.rest_minus, accent);
        views.setTextColor(R.id.rest_plus, accent);
        views.setTextColor(R.id.rest_skip, ink);
        if (Build.VERSION.SDK_INT >= 31) {
            views.setColorStateList(R.id.rest_skip, "setBackgroundTintList", ColorStateList.valueOf(accent));
        }
    }

}
