package me.tarry.app.summary

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.JobBlock
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.Trip
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.settings.Settings
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SummaryViewModelTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private val hour = 3_600_000L

    // Wednesday 12 November 2025.
    private val today = LocalDate.of(2025, 11, 12)

    private val blocks = MutableStateFlow<List<JobBlock>>(emptyList())
    private val jobs = MutableStateFlow(listOf(Job(id = "A", name = "Alpha", colorArgb = 1, updatedAt = 0)))
    private val visits = MutableStateFlow<List<Visit>>(emptyList())
    private val trips = MutableStateFlow<List<Trip>>(emptyList())
    private val places = MutableStateFlow<List<Place>>(emptyList())
    private val settings = MutableStateFlow(Settings())

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun at(date: LocalDate, h: Int) = ZonedDateTime.of(date, java.time.LocalTime.of(h, 0), zone).toInstant().toEpochMilli()

    private fun block(id: String, date: LocalDate, fromHour: Int, toHour: Int) = JobBlock(
        id = id, jobId = "A", start = at(date, fromHour), end = at(date, toHour), zoneId = zone.id, billable = true, updatedAt = 0,
    )

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()) = SummaryViewModel(
        saved,
        blocksOverlapping = { _, _ -> blocks },
        visitsIn = { _, _ -> visits },
        tripsIn = { _, _ -> trips },
        jobs = jobs,
        places = places,
        settings = settings,
        zone = zone,
        todayProvider = { today },
    )

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    private fun SummaryViewModel.stateWhere(predicate: (SummaryUiState) -> Boolean): SummaryUiState =
        await { state.first { it != null && predicate(it) }!! }

    @Test
    fun startsOnToday_byJob() {
        blocks.value = listOf(block("b", today, 9, 11))
        val s = viewModel().stateWhere { it.result != null }
        assertThat(s.preset).isEqualTo(RangePreset.TODAY)
        assertThat(s.range).isEqualTo(SummaryRange(today, today))
        assertThat(s.grouping).isEqualTo(SummaryGrouping.BY_JOB)
        assertThat(s.result!!.billedMs).isEqualTo(2 * hour)
    }

    @Test
    fun changingTheRange_recomputes() {
        blocks.value = listOf(block("t", today, 9, 11), block("y", today.minusDays(1), 9, 12))
        val vm = viewModel()
        assertThat(vm.stateWhere { it.result != null }.result!!.billedMs).isEqualTo(2 * hour)

        vm.selectPreset(RangePreset.YESTERDAY)
        val yesterday = vm.stateWhere { it.preset == RangePreset.YESTERDAY && it.result != null }
        assertThat(yesterday.range).isEqualTo(SummaryRange(today.minusDays(1), today.minusDays(1)))
        assertThat(yesterday.result!!.billedMs).isEqualTo(3 * hour)

        vm.selectPreset(RangePreset.THIS_WEEK)
        val week = vm.stateWhere { it.preset == RangePreset.THIS_WEEK && it.result != null }
        assertThat(week.range).isEqualTo(SummaryRange(LocalDate.of(2025, 11, 10), LocalDate.of(2025, 11, 16)))
        assertThat(week.result!!.billedMs).isEqualTo(5 * hour)
    }

    @Test
    fun customRange_isUsed_andSwappedWhenBackwards() {
        blocks.value = listOf(block("a", LocalDate.of(2025, 11, 3), 9, 10), block("b", LocalDate.of(2025, 11, 5), 9, 10))
        val vm = viewModel()
        vm.setCustomRange(LocalDate.of(2025, 11, 5), LocalDate.of(2025, 11, 3))
        val s = vm.stateWhere { it.preset == RangePreset.CUSTOM && it.result != null }
        assertThat(s.range).isEqualTo(SummaryRange(LocalDate.of(2025, 11, 3), LocalDate.of(2025, 11, 5)))
        assertThat(s.result!!.billedMs).isEqualTo(2 * hour)
    }

    @Test
    fun customWithoutDates_isIgnored() {
        val vm = viewModel()
        vm.selectPreset(RangePreset.CUSTOM)
        assertThat(vm.stateWhere { it.result != null }.preset).isEqualTo(RangePreset.TODAY)
    }

    @Test
    fun rangeAndGrouping_surviveARecreatedViewModel() {
        val saved = SavedStateHandle()
        val first = viewModel(saved)
        first.setCustomRange(LocalDate.of(2025, 11, 3), LocalDate.of(2025, 11, 7))
        first.setGrouping(SummaryGrouping.BY_DAY)
        // Rotation or process death builds a new view model around the same saved state.
        val second = viewModel(saved).stateWhere { it.result != null }
        assertThat(second.preset).isEqualTo(RangePreset.CUSTOM)
        assertThat(second.range).isEqualTo(SummaryRange(LocalDate.of(2025, 11, 3), LocalDate.of(2025, 11, 7)))
        assertThat(second.grouping).isEqualTo(SummaryGrouping.BY_DAY)
    }

    @Test
    fun changingTheGrouping_updatesTheState_keepingTheResult() {
        blocks.value = listOf(block("b", today, 9, 11))
        val vm = viewModel()
        vm.stateWhere { it.result != null }
        vm.setGrouping(SummaryGrouping.BY_DAY)
        val s = vm.stateWhere { it.grouping == SummaryGrouping.BY_DAY && it.result != null }
        assertThat(s.result!!.byDay.single().billedMs).isEqualTo(2 * hour)
    }

    @Test
    fun aBlockEdit_reEmits() {
        blocks.value = listOf(block("b", today, 9, 11))
        val vm = viewModel()
        assertThat(vm.stateWhere { it.result != null }.result!!.billedMs).isEqualTo(2 * hour)
        blocks.value = listOf(block("b", today, 9, 12))
        assertThat(vm.stateWhere { it.result?.billedMs == 3 * hour }.result!!.billedMs).isEqualTo(3 * hour)
        blocks.value = emptyList()
        assertThat(vm.stateWhere { it.result?.hasBlocks == false }.result!!.billedMs).isEqualTo(0)
    }

    @Test
    fun aBillingSettingChange_reEmits() {
        blocks.value = listOf(block("b", today, 9, 10))
        val vm = viewModel()
        assertThat(vm.stateWhere { it.result != null }.result!!.billedMs).isEqualTo(hour)
        settings.value = Settings(minBillableMinutes = 120)
        assertThat(vm.stateWhere { it.result?.billedMs == 2 * hour }.result!!.billedMs).isEqualTo(2 * hour)
    }

    @Test
    fun ignoredPlaces_keepTheirVisitsOutOfUnlabelled() {
        places.value = listOf(Place(id = "home", centroidLat = 0.0, centroidLng = 0.0, ignored = true, updatedAt = 0))
        visits.value = listOf(
            Visit(
                id = "v", placeId = "home", start = at(today, 9), end = at(today, 10), zoneId = zone.id,
                origin = me.tarry.core.data.model.VisitOrigin.DETECTED, confidence = 1f, updatedAt = 0,
            ),
        )
        val s = viewModel().stateWhere { it.result != null }
        assertThat(s.result!!.unlabelledOnSiteMs).isEqualTo(0)
    }

    @Test
    fun weekPresets_runMondayToSunday() {
        assertThat(RangePreset.THIS_WEEK.rangeFor(LocalDate.of(2025, 11, 16)))
            .isEqualTo(SummaryRange(LocalDate.of(2025, 11, 10), LocalDate.of(2025, 11, 16))) // a Sunday
        assertThat(RangePreset.LAST_WEEK.rangeFor(LocalDate.of(2025, 11, 10)))
            .isEqualTo(SummaryRange(LocalDate.of(2025, 11, 3), LocalDate.of(2025, 11, 9))) // a Monday
        assertThat(RangePreset.CUSTOM.rangeFor(today)).isNull()
    }
}
