package ch.duartesantos.opengym;

import io.github.d4viddf.hyperisland_kit.models.TimerInfo;
import org.junit.Test;
import static org.junit.Assert.*;

public class WorkoutIslandComponentsTest {
    @Test public void workoutCarriesBothAreasAndSynchronizedCountUp() {
        String payload = WorkoutIslandComponents.build("2/4", "workout",
                new TimerInfo(1, 1000L, 0L, 5000L), null);
        assertTrue(payload.contains("\"bigIslandArea\""));
        assertTrue(payload.contains("\"smallIslandArea\""));
        assertTrue(payload.contains("\"pic\":\"miui.focus.pic_workout\""));
        assertTrue(payload.contains("\"title\":\"2/4\""));
        assertTrue(payload.contains("\"timerType\":1"));
        assertTrue(payload.contains("\"timerWhen\":1000"));
        assertTrue(payload.contains("\"timerSystemCurrent\":5000"));
        assertFalse(payload.contains("aod"));
    }

    @Test public void restCarriesDeadlineAndTotalForSystemCountdown() {
        String payload = WorkoutIslandComponents.build("Rest", "workout",
                new TimerInfo(-1, 65000L, 60000L, 5000L), null);
        assertTrue(payload.contains("\"timerType\":-1"));
        assertTrue(payload.contains("\"timerWhen\":65000"));
        assertTrue(payload.contains("\"timerTotal\":60000"));
    }

    @Test public void pausedRestShowsFrozenDigitsWithoutRunningTimer() {
        String payload = WorkoutIslandComponents.build("Rest", "workout",
                new TimerInfo(-2, 35000L, 60000L, 5000L), "00:30");
        assertTrue(payload.contains("\"digit\":\"00:30\""));
        assertFalse(payload.contains("\"timerInfo\""));
    }
}
