package ch.duartesantos.opengym;

import io.github.d4viddf.hyperisland_kit.models.BigIslandArea;
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoLeft;
import io.github.d4viddf.hyperisland_kit.models.ParamIsland;
import io.github.d4viddf.hyperisland_kit.models.PicInfo;
import io.github.d4viddf.hyperisland_kit.models.SameWidthDigitInfo;
import io.github.d4viddf.hyperisland_kit.models.SmallIslandArea;
import io.github.d4viddf.hyperisland_kit.models.TextInfo;
import io.github.d4viddf.hyperisland_kit.models.TimerInfo;
import kotlin.Unit;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonKt;

/** Typed island composition; kit 0.4.4 has no public setter accepting ParamIsland. */
final class WorkoutIslandComponents {
    private static final Json JSON = JsonKt.Json(Json.Default, config -> {
        config.setEncodeDefaults(true);
        config.setExplicitNulls(false);
        return Unit.INSTANCE;
    });

    private WorkoutIslandComponents() {}

    static String build(String label, String pictureKey, TimerInfo timer, String pausedClock) {
        // Direct models expect the full resource reference; builder helpers add it themselves.
        PicInfo picture = new PicInfo(1, "miui.focus.pic_" + pictureKey,
                false, false, 1, null, null, null);
        ImageTextInfoLeft left = new ImageTextInfoLeft(1, picture,
                new TextInfo(label, null, false, null), null);
        SameWidthDigitInfo digits = new SameWidthDigitInfo(null, pausedClock,
                pausedClock == null ? timer : null, false, false);
        BigIslandArea big = new BigIslandArea(left, null, digits, null, null, null, null);
        SmallIslandArea small = new SmallIslandArea(null, picture);
        ParamIsland area = new ParamIsland(2, 2, null, false, false, false,
                true, null, null, big, small, null);
        return JSON.encodeToString(ParamIsland.Companion.serializer(), area);
    }
}
