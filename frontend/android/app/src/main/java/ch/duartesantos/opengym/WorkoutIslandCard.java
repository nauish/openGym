package ch.duartesantos.opengym;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

/** Two-column workout metrics with a live system chronometer and two independent controls. */
final class WorkoutIslandCard {
    static RemoteViews build(Context context, WorkoutNotificationState state, long now) {
        RemoteViews card = new RemoteViews(context.getPackageName(), R.layout.workout_island_card);
        boolean rest = state.rest != null;
        boolean paused = rest && state.rest.paused;
        card.setTextViewText(R.id.island_exercise, rest ? state.restText
                : state.exerciseName.isEmpty() ? state.title : state.exerciseName);
        String subtitle = state.title;
        if (paused) subtitle += " · " + state.pausedLabel;
        card.setTextViewText(R.id.island_session, subtitle);
        card.setTextViewText(R.id.island_time_label, context.getString(rest ? R.string.island_remaining : R.string.island_time));
        card.setTextViewText(R.id.island_set_count, state.setProgress.isEmpty()
                ? state.setsDone + "/" + state.setsTotal : state.setProgress);
        card.setContentDescription(R.id.island_set_count, state.setSummary + " · " + state.setProgress);
        card.setViewVisibility(R.id.island_clock, paused ? View.GONE : View.VISIBLE);
        card.setViewVisibility(R.id.island_paused_clock, paused ? View.VISIBLE : View.GONE);
        if (paused) {
            card.setTextViewText(R.id.island_paused_clock, RestAlert.clock((int) Math.ceil(state.rest.remainingAt(now) / 1000.0)));
        } else {
            long base = SystemClock.elapsedRealtime() + (rest ? state.rest.endsAt - now : state.startedAt - now);
            card.setChronometerCountDown(R.id.island_clock, rest);
            card.setChronometer(R.id.island_clock, base, null, true);
        }
        Bitmap picture = rest ? null : WorkoutNotificationArtwork.get(context, state);
        if (picture != null) card.setImageViewBitmap(R.id.island_artwork, roundedArtwork(picture));
        else card.setImageViewResource(R.id.island_artwork, rest ? R.drawable.ic_stat_timer : R.drawable.ic_stat_dumbbell);
        card.setContentDescription(R.id.island_artwork, rest ? state.restText : state.exerciseName);
        Intent open = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openIntent = PendingIntent.getActivity(context, 56, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (rest) {
            card.setImageViewResource(R.id.island_primary, paused ? R.drawable.ic_notification_play : R.drawable.ic_notification_pause);
            card.setContentDescription(R.id.island_primary, paused ? state.resumeLabel : state.pauseLabel);
            card.setOnClickPendingIntent(R.id.island_primary, WorkoutNotificationRenderer.control(context, RestAlert.ACTION_PAUSE, 51));
            card.setImageViewResource(R.id.island_secondary, R.drawable.ic_notification_skip);
            card.setContentDescription(R.id.island_secondary, state.skipLabel);
            card.setOnClickPendingIntent(R.id.island_secondary, WorkoutNotificationRenderer.control(context, RestAlert.ACTION_SKIP, 54));
        } else {
            card.setContentDescription(R.id.island_primary, state.completeSetLabel);
            card.setOnClickPendingIntent(R.id.island_primary, WorkoutNotificationRenderer.completeControl(context, 55));
            card.setContentDescription(R.id.island_secondary, state.title);
            card.setOnClickPendingIntent(R.id.island_secondary, openIntent);
        }
        return card;
    }

    private static Bitmap roundedArtwork(Bitmap source) {
        Bitmap rounded = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        rounded.setDensity(source.getDensity());
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setShader(new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        // Match Xiaomi's radius recommendation without changing the thumbnail's aspect ratio.
        float radius = Math.min(source.getWidth(), source.getHeight()) / 4f;
        new Canvas(rounded).drawRoundRect(new RectF(0, 0, source.getWidth(), source.getHeight()), radius, radius, paint);
        return rounded;
    }
}
