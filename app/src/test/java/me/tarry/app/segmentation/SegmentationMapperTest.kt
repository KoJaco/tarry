package me.tarry.app.segmentation

import com.google.common.truth.Truth.assertThat
import me.tarry.core.data.model.ActivityType
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.TripMode
import me.tarry.segmentation.Activity
import org.junit.Test
import me.tarry.segmentation.GapCause as SegGapCause
import me.tarry.segmentation.TripMode as SegTripMode

class SegmentationMapperTest {
    @Test
    fun activityTypesMapByName() {
        for (type in ActivityType.entries) {
            assertThat(type.toSegmentation()).isEqualTo(Activity.valueOf(type.name))
        }
    }

    @Test
    fun tripModesRoundTrip() {
        for (mode in TripMode.entries) {
            assertThat(mode.toSegmentation()).isEqualTo(SegTripMode.valueOf(mode.name))
            assertThat(mode.toSegmentation().toEntity()).isEqualTo(mode)
        }
    }

    @Test
    fun segmentationOwnedGapCausesRoundTrip() {
        assertThat(GapCause.NO_SESSION.toSegmentationOrNull()).isEqualTo(SegGapCause.NO_SESSION)
        assertThat(GapCause.NO_FIX.toSegmentationOrNull()).isEqualTo(SegGapCause.NO_FIX)
        for (cause in SegGapCause.entries) {
            assertThat(cause.toEntity().toSegmentationOrNull()).isEqualTo(cause)
        }
    }

    @Test
    fun healthMonitorGapCausesAreNotSegmentationOwned() {
        val health = GapCause.entries - setOf(GapCause.NO_SESSION, GapCause.NO_FIX)
        assertThat(health).isNotEmpty()
        for (cause in health) assertThat(cause.toSegmentationOrNull()).isNull()
    }
}
