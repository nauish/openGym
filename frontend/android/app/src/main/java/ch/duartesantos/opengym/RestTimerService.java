package ch.duartesantos.opengym;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;

/**
 * Foreground while a rest is running. Android 16's Chronometer owns the visible countdown;
 * older Android versions keep their existing once-per-second progress card.
 */
public class RestTimerService extends Service {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long endsAt;
    private long totalMs;
    private long pausedLeft;
    private boolean paused;
    private boolean running;
    private boolean foregroundStarted;
    private PowerManager.WakeLock cpu;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running || paused || endsAt <= 0) return;
            long left = endsAt - System.currentTimeMillis();
            if (left <= 0) {
                reachEnd();
                return;
            }
            if (WorkoutNotificationRenderer.needsPeriodicUpdates()) show();
            // Next on the clock's next whole second, so the last run lands on the end itself.
            handler.postDelayed(this, ((left - 1) % 1000) + 1);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (WorkoutNotification.isDismissed(this)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!running && isRestControl(action) && restoreSavedRest()) {
            // Notification actions can arrive after Android recreated the service process.
            // Promote before applying the action so the service remains valid on Android 8+.
            show();
        }
        if (RestAlert.ACTION_HOLD.equals(action)) {
            if (running) hold(intent.getLongExtra("leftMs", 0), intent.getLongExtra("totalMs", 0));
            else stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (RestAlert.ACTION_SKIP.equals(action)) {
            finishSkip();
            return START_NOT_STICKY;
        }
        if (RestAlert.ACTION_PAUSE.equals(action)) {
            if (running) togglePause();
            else stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (RestAlert.ACTION_MINUS.equals(action)) {
            if (running) nudge(-15_000);
            else stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (RestAlert.ACTION_PLUS.equals(action)) {
            if (running) nudge(15_000);
            else stopSelf(startId);
            return START_NOT_STICKY;
        }
        long nextEnd = intent == null ? 0 : intent.getLongExtra("endsAt", 0);
        if (nextEnd <= System.currentTimeMillis()) {
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }
        endsAt = nextEnd;
        long passed = intent.getLongExtra("totalMs", 0);
        totalMs = passed > 0 ? passed : Math.max(1000, endsAt - System.currentTimeMillis());
        paused = false;
        running = true;
        holdCpuUntilEnd();
        handler.removeCallbacks(tick);
        if (!WorkoutNotificationRenderer.needsPeriodicUpdates()) show();
        tick.run();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(tick);
        releaseCpu();
        endsAt = 0;
        stopForegroundCompat();
        // Destruction or dismissal is not a logical rest completion. Keep the saved state
        // and alarm available for recovery; explicit cancel, skip and alarm delivery finish it.
        super.onDestroy();
    }

    private void togglePause() {
        if (!paused) {
            pausedLeft = Math.max(0, endsAt - System.currentTimeMillis());
            paused = true;
            handler.removeCallbacks(tick);
            releaseCpu();
            RestAlert.cancelAlarmOnly(this, RestAlert.NOTIFICATION_ID);
        } else {
            endsAt = System.currentTimeMillis() + pausedLeft;
            paused = false;
            holdCpuUntilEnd();
            RestAlert.updateAlarm(this, endsAt);
            handler.removeCallbacks(tick);
        }
        persistRest();
        show();
        if (!paused) tick.run();
        emit("pause");
    }

    /**
     * Paused in the app. The clock stops at the time the app holds, so the two read the same,
     * and the button offers Resume. No event goes back: the app made this change. The plugin
     * has already called off the alarm.
     */
    private void hold(long leftMs, long total) {
        if (leftMs <= 0) return;
        paused = true;
        pausedLeft = leftMs;
        if (total > 0) totalMs = total;
        handler.removeCallbacks(tick);
        releaseCpu();
        persistRest();
        show();
    }

    private void nudge(long deltaMs) {
        long now = System.currentTimeMillis();
        // endsAt may already be in the past when +15 lands on the last second.
        // Adding the delta to that old deadline would leave the clock stuck.
        long left = paused ? pausedLeft : Math.max(0, endsAt - now);
        left += deltaMs;
        totalMs = Math.max(1000, totalMs + deltaMs);
        if (left <= 0) {
            finishSkip();
            return;
        }
        if (paused) pausedLeft = left;
        else {
            endsAt = now + left;
            holdCpuUntilEnd();
            RestAlert.updateAlarm(this, endsAt);
            handler.removeCallbacks(tick);
            handler.post(tick);
        }
        persistRest();
        show();
        emit("adjust");
    }

    /**
     * The end, reached by this countdown. The alert (or, with the app on screen, just the end of
     * this card) comes from RestAlert, which stops this service once it is up. The CPU stays held
     * until then: letting go here could let the phone sleep before that alert holds its own.
     */
    private void reachEnd() {
        running = false;
        RestAlert.fireFromCountdown(getApplicationContext(), endsAt);
    }

    /**
     * Keeps the CPU running until the end, and ten seconds past it. With the screen off a phone
     * otherwise sleeps through this countdown's last second, and the only thing left to wake it
     * is the alarm, which without the exact-alarm permission comes late. Released on pause,
     * on skip and when the service stops after the end, so a held rest costs nothing.
     */
    private void holdCpuUntilEnd() {
        try {
            if (cpu == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opengym:rest-countdown");
                cpu.setReferenceCounted(false);
            }
            cpu.acquire(Math.max(0, endsAt - System.currentTimeMillis()) + 10_000);
        } catch (Exception ignored) { /* the alarm is still set */ }
    }

    private void releaseCpu() {
        try {
            if (cpu != null && cpu.isHeld()) cpu.release();
        } catch (Exception ignored) { /* already released by its timeout */ }
    }

    private void finishSkip() {
        running = false;
        handler.removeCallbacks(tick);
        releaseCpu();
        RestAlert.cancelAlarmOnly(this, RestAlert.NOTIFICATION_ID);
        RestAlertPlugin.emit("skip", 0, 0, 0, false);
        stopForegroundCompat();
        WorkoutNotification.finishRest(this);
        stopSelf();
    }

    private void emit(String type) {
        long left = paused ? pausedLeft : Math.max(0, endsAt - System.currentTimeMillis());
        RestAlertPlugin.emit(type, endsAt, totalMs, left, paused);
    }

    private void show() {
        try {
            Notification n = WorkoutNotification.current(this);
            if (!foregroundStarted) {
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(RestAlert.COUNTDOWN_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                } else {
                    startForeground(RestAlert.COUNTDOWN_ID, n);
                }
                foregroundStarted = true;
            } else if (!WorkoutNotification.isDismissed(this)) {
                NotificationManagerCompat.from(this).notify(RestAlert.COUNTDOWN_ID, n);
            }
        } catch (Exception e) {
            Log.w("openGym", "Cannot publish rest foreground notification", e);
            stopSelf();
        }
    }

    private void persistRest() {
        long left = paused ? pausedLeft : Math.max(0, endsAt - System.currentTimeMillis());
        long displayedEnd = paused ? System.currentTimeMillis() + left : endsAt;
        WorkoutNotificationState state = WorkoutNotification.savedState(this);
        WorkoutNotification.setRest(this, displayedEnd, totalMs, paused, left, state.accent, state.ink);
    }

    private boolean restoreSavedRest() {
        WorkoutNotificationState.Rest rest = WorkoutNotification.savedState(this).rest;
        if (rest == null) return false;
        endsAt = rest.endsAt;
        totalMs = rest.totalMs;
        pausedLeft = rest.pausedLeftMs;
        paused = rest.paused;
        running = true;
        if (!paused) holdCpuUntilEnd();
        handler.removeCallbacks(tick);
        return true;
    }

    private static boolean isRestControl(String action) {
        return RestAlert.ACTION_HOLD.equals(action)
                || RestAlert.ACTION_SKIP.equals(action)
                || RestAlert.ACTION_PAUSE.equals(action)
                || RestAlert.ACTION_MINUS.equals(action)
                || RestAlert.ACTION_PLUS.equals(action);
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        if (!foregroundStarted) return;
        // ID 41 transitions back to the session notification instead of disappearing when
        // the rest-only foreground service ends.
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH);
        else stopForeground(false);
        foregroundStarted = false;
    }
}
