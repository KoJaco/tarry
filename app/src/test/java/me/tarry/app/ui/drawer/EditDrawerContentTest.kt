package me.tarry.app.ui.drawer

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.assertNotTruncated
import me.tarry.app.ui.map.LocalMapsEnabled
import me.tarry.app.map.GeoPoint
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.core.data.model.TripMode
import me.tarry.core.data.model.VisitOrigin
import me.tarry.core.data.repository.MergeDirection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class EditDrawerContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("Australia/Sydney")
    private val day = LocalDate.of(2026, 10, 21)
    private fun at(hour: Int, minute: Int = 0) = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()


    private fun visit(
        placeLabel: String? = "Rose Bay Marina",
        frozen: Boolean = false,
        origin: VisitOrigin = VisitOrigin.DETECTED,
        error: EditError? = null,
    ) = EditDrawerState(
        kind = EditKind.VISIT, id = "v", start = at(9), end = at(11), zoneId = zone.id,
        placeLabel = placeLabel, hasPlace = true,
        places = listOf(PlaceOption("p2", "Depot", "1 Main St")),
        frozen = frozen, origin = origin, error = error,
    )

    private fun show(state: EditDrawerState, actions: EditDrawerActions = EditDrawerActions(), fontScale: Float? = null) {
        composeRule.setContent {
            val density = LocalDensity.current
            val provided = if (fontScale == null) density else Density(density.density, fontScale = fontScale)
            CompositionLocalProvider(LocalDensity provides provided, LocalMapsEnabled provides false) {
                TarryTheme { EditDrawerContent(state, actions) }
            }
        }
    }

    private fun heading() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    @Test
    fun headerShowsThePlaceAsAHeadingWithRangeAndDuration() {
        show(visit())
        composeRule.onNodeWithText("Rose Bay Marina").assert(heading())
        composeRule.onNode(hasText("2 h", substring = true)).assertExists()
    }

    @Test
    fun resetIsHiddenForManualVisits() {
        show(visit(frozen = true, origin = VisitOrigin.MANUAL))
        composeRule.onNodeWithText("Reset to detected").assertDoesNotExist()
    }

    @Test
    fun resetIsHiddenWhileTheVisitIsUnedited() {
        show(visit(frozen = false))
        composeRule.onNodeWithText("Reset to detected").assertDoesNotExist()
    }

    @Test
    fun resetAsksForConfirmationBeforeCalling() {
        var resets = 0
        show(visit(frozen = true), EditDrawerActions(onReset = { resets++ }))
        composeRule.onNodeWithText("Reset to detected").performScrollTo().performClick()
        assertThat(resets).isEqualTo(0)
        composeRule.onNodeWithText("Reset to detected?").assertExists()
        composeRule.onNodeWithText("Reset").performClick()
        assertThat(resets).isEqualTo(1)
    }

    @Test
    fun timeSectionOffersTheStructuralEdits() {
        val merges = mutableListOf<MergeDirection>()
        var deleted = 0
        show(visit(), EditDrawerActions(onMerge = { merges += it }, onDelete = { deleted++ }))
        listOf("Trim start", "Trim end", "Split at…", "Change place").forEach {
            composeRule.onNodeWithText(it).assertExists()
        }
        composeRule.onNodeWithText("Merge with previous").performScrollTo().performClick()
        composeRule.onNodeWithText("Merge with next").performScrollTo().performClick()
        composeRule.onNodeWithText("Delete visit").performScrollTo().performClick()
        assertThat(merges).containsExactly(MergeDirection.PREVIOUS, MergeDirection.NEXT).inOrder()
        assertThat(deleted).isEqualTo(1)
    }

    @Test
    fun validationErrorsShowInlineAsText() {
        show(visit(error = EditError.OVERLAP))
        composeRule.onNodeWithText("That would overlap another visit.").assertExists()
    }


    @Test
    fun sectionsAreOrderedNotesTime() {
        show(visit())
        val order = composeRule.onAllNodes(heading()).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }
            .filter { it in setOf("Notes", "Time") }
        assertThat(order).containsExactly("Notes", "Time").inOrder()
    }

    // Note: a TextField inside a dialog never goes idle under Robolectric, so the dialogs themselves are not opened here.
    // What they write is covered by EditDrawerViewModelTest and TimelineEditorTest.
    @Test
    fun renamePencilIsAnAccessibleFortyEightDpButtonNextToTheName() {
        show(visit())
        composeRule.onNodeWithContentDescription("Rename place")
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun noRenamePencilForTripsOrPlacelessVisits() {
        show(visit().copy(hasPlace = false, placeLabel = null))
        composeRule.onNodeWithContentDescription("Rename place").assertDoesNotExist()
    }

    @Test
    fun tripShowsNotesAndAddStopButNoTimeEdits() {
        var stops = 0
        val trip = EditDrawerState(
            kind = EditKind.TRIP, id = "t", start = at(11), end = at(11, 25), zoneId = zone.id,
            tripMode = TripMode.VEHICLE, distanceM = 12_400f,
        )
        show(trip, EditDrawerActions(onAddStop = { stops++ }))
        composeRule.onNodeWithText("Vehicle").assert(heading())
        composeRule.onNodeWithText("Trim start").assertDoesNotExist()
        composeRule.onNodeWithText("Delete visit").assertDoesNotExist()
        composeRule.onNodeWithText("Add a stop during this trip").performScrollTo().performClick()
        assertThat(stops).isEqualTo(1)
    }

    private fun newVisit(error: EditError? = null) = EditDrawerState(
        kind = EditKind.NEW_VISIT, id = null, start = at(10), end = at(11), zoneId = zone.id,
        places = listOf(PlaceOption("p1", "Rose Bay Marina", "1 Beach Rd"), PlaceOption("p2", "Depot", null)),
        error = error,
    )

    @Test
    fun newVisitShowsTheTitleTimesAndNoStructuralControls() {
        show(newVisit())
        composeRule.onNodeWithText("New visit").assert(heading())
        composeRule.onNodeWithText("Delete visit").assertDoesNotExist()
        composeRule.onNodeWithText("Needs review").assertDoesNotExist()
        composeRule.onNodeWithText("Start").assertExists()
        composeRule.onNodeWithText("End").assertExists()
        composeRule.onNodeWithText("Choose place").assertHasClickAction()
    }

    @Test
    fun newVisitSaveNeedsAPlaceThenSendsThePlaceAndTimes() {
        val saved = mutableListOf<Triple<String, Long, Long>>()
        show(newVisit(), EditDrawerActions(onCreateManual = { p, s, e -> saved += Triple(p, s, e) }))
        composeRule.onNodeWithText("Save visit").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Choose a place to save this visit.").assertExists()

        composeRule.onNodeWithText("Choose place").performClick()
        composeRule.onNodeWithText("Rose Bay Marina").performClick()

        composeRule.onNodeWithText("Choose a place to save this visit.").assertDoesNotExist()
        composeRule.onNodeWithText("Save visit").performScrollTo().assertIsEnabled().performClick()
        assertThat(saved).containsExactly(Triple("p1", at(10), at(11)))
    }

    @Test
    fun newVisitTimeRowOpensThePickerAndConfirmKeepsTheForm() {
        val saved = mutableListOf<Triple<String, Long, Long>>()
        show(newVisit(), EditDrawerActions(onCreateManual = { p, s, e -> saved += Triple(p, s, e) }))
        composeRule.onNodeWithText("Start").performClick()
        composeRule.onNodeWithText("Start time").assertExists()
        composeRule.onNodeWithText("OK").performClick()
        composeRule.onNodeWithText("Start time").assertDoesNotExist()
        composeRule.onNodeWithText("Choose place").performClick()
        composeRule.onNodeWithText("Depot").performClick()
        composeRule.onNodeWithText("Save visit").performScrollTo().performClick()
        assertThat(saved).containsExactly(Triple("p2", at(10), at(11)))
    }

    private fun newVisitWithCentres() = newVisit().copy(
        places = listOf(
            PlaceOption("p1", "Rose Bay Marina", "1 Beach Rd", GeoPoint(-33.87, 151.27), lastVisitAt = 100),
            PlaceOption("p2", "Depot", null, GeoPoint(-33.90, 151.20), lastVisitAt = 900),
        ),
    )

    @Test
    fun pickOnMapIsAFortyEightDpButtonUnderChoosePlace() {
        show(newVisit())
        composeRule.onNodeWithText("Pick on map").assertHasClickAction().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun pickingNewPlaceHereShowsTheCoordinatesAndSaveSendsThePinAndTimes() {
        val saved = mutableListOf<List<Number>>()
        val known = mutableListOf<String>()
        show(
            newVisit(),
            EditDrawerActions(
                onCreateManual = { p, _, _ -> known += p },
                onCreateManualAtNewPlace = { lat, lng, s, e -> saved += listOf(lat, lng, s, e) },
            ),
        )
        composeRule.onNodeWithText("Pick on map").performClick()
        composeRule.onNodeWithText("New place here").assertHasClickAction().performClick()

        composeRule.onNodeWithText("New place at 20.00000, 0.00000").assertExists()
        composeRule.onNodeWithText("Choose a place to save this visit.").assertDoesNotExist()
        composeRule.onNodeWithText("Save visit").performScrollTo().assertIsEnabled().performClick()
        assertThat(saved).containsExactly(listOf(20.0, 0.0, at(10), at(11)))
        assertThat(known).isEmpty()
    }

    @Test
    fun pickerOffersKnownPlacesNearTheStartAndPickingOneUsesTheNormalSavePath() {
        val saved = mutableListOf<Triple<String, Long, Long>>()
        show(newVisitWithCentres(), EditDrawerActions(onCreateManual = { p, s, e -> saved += Triple(p, s, e) }))
        composeRule.onNodeWithText("Pick on map").performClick()
        // The camera starts at the most recently visited place (Depot), so only it is within snapping distance.
        composeRule.onNodeWithText("Rose Bay Marina").assertDoesNotExist()
        composeRule.onNodeWithText("Depot").performClick()

        composeRule.onNodeWithText("Save visit").performScrollTo().performClick()
        assertThat(saved).containsExactly(Triple("p2", at(10), at(11)))
    }

    @Test
    fun choosingAPlaceFromSearchClearsAPinAndViceVersa() {
        val saved = mutableListOf<Triple<String, Long, Long>>()
        show(newVisit(), EditDrawerActions(onCreateManual = { p, s, e -> saved += Triple(p, s, e) }))
        composeRule.onNodeWithText("Pick on map").performClick()
        composeRule.onNodeWithText("New place here").performClick()
        composeRule.onNodeWithText("Choose place").performClick()
        composeRule.onNodeWithText("Depot").performClick()

        composeRule.onNodeWithText("New place at 20.00000, 0.00000").assertDoesNotExist()
        composeRule.onNodeWithText("Save visit").performScrollTo().performClick()
        assertThat(saved).containsExactly(Triple("p2", at(10), at(11)))
    }

    @Test
    fun pinPickerStaysReachableAtFontScaleTwo() {
        show(newVisitWithCentres(), fontScale = 2f)
        composeRule.onNodeWithText("Pick on map").performScrollTo().performClick()
        composeRule.onNodeWithText("New place here").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("Depot").performScrollTo().assertHasClickAction()
        composeRule.onNodeWithContentDescription("Close map").assertHasClickAction().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun newVisitShowsEditorErrorsInlineAndKeepsTheForm() {
        show(newVisit(error = EditError.OVERLAP))
        composeRule.onNodeWithText("That would overlap another visit.").assertExists()
        composeRule.onNodeWithText("Save visit").assertExists()
    }

    @Test
    fun newVisitFormFitsAtFontScaleTwo() {
        show(newVisit(error = EditError.EMPTY_RANGE), fontScale = 2f)
        composeRule.onNodeWithText("Choose place", useUnmergedTree = true).performScrollTo().assertNotTruncated()
        composeRule.onNodeWithText("Choose a place to save this visit.", useUnmergedTree = true).performScrollTo().assertNotTruncated()
        composeRule.onNodeWithText("Save visit", useUnmergedTree = true).performScrollTo().assertNotTruncated()
    }

    @Test
    fun fontScaleTwoKeepsHeaderRowsAndLabelsUntruncated() {
        show(visit(placeLabel = "Rose Bay Marina", frozen = true), fontScale = 2f)
        composeRule.onNodeWithText("Rose Bay Marina", useUnmergedTree = true).assertNotTruncated()
        composeRule.onNodeWithText("Trim start", useUnmergedTree = true).performScrollTo().assertNotTruncated()
        composeRule.onNodeWithText("Merge with previous", useUnmergedTree = true).performScrollTo().assertNotTruncated()
        composeRule.onNodeWithText("Reset to detected", useUnmergedTree = true).performScrollTo().assertNotTruncated()
        composeRule.onNodeWithText("Delete visit", useUnmergedTree = true).performScrollTo().assertNotTruncated()
    }

    // region job blocks (phase 6b T5)

    private val acme = JobOption("j", "Acme", 1, defaultBillable = true)
    private val beta = JobOption("k", "Beta", 2, defaultBillable = false)

    private fun newJob(start: Long = at(10), end: Long = at(11), jobId: String? = null) = EditDrawerState(
        kind = EditKind.NEW_JOB, id = null, start = start, end = end, zoneId = zone.id,
        jobs = listOf(acme, beta), jobId = jobId, billable = true,
    )

    private fun jobBlock() = EditDrawerState(
        kind = EditKind.JOB, id = "b", start = at(9), end = at(15), zoneId = zone.id,
        jobs = listOf(acme, beta), jobId = "j", billable = true, notes = "weekly invoice", coveredVisits = 3, coveredTrips = 2,
    )

    @Test
    fun jobDrawerShowsItsSectionsAndWhatItCovers() {
        show(jobBlock())
        composeRule.onAllNodesWithText("Acme").onFirst().assert(heading())
        val sections = composeRule.onAllNodes(heading()).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }
        assertThat(sections).containsAtLeast("Job", "When", "Notes").inOrder()
        listOf("Start", "End", "Billable").forEach { composeRule.onAllNodesWithText(it).onFirst().assertExists() }
        composeRule.onNodeWithText("Covers 3 visits · 2 trips").performScrollTo().assertExists()
        composeRule.onNodeWithText("Delete job block").performScrollTo().assertHasClickAction()
        composeRule.onNodeWithText("Billable").performScrollTo().assertIsOn().assert(role(Role.Switch))
    }

    @Test
    fun jobDrawerCoversLineUsesPlurals() {
        show(jobBlock().copy(coveredVisits = 1, coveredTrips = 0))
        composeRule.onNodeWithText("Covers 1 visit · 0 trips").performScrollTo().assertExists()
    }

    @Test
    fun jobDrawerSendsDeleteBillableAndPickedJob() {
        var deleted = 0
        val billable = mutableListOf<Boolean>()
        val jobs = mutableListOf<String>()
        show(
            jobBlock(),
            EditDrawerActions(onDeleteBlock = { deleted++ }, onBlockBillable = { billable += it }, onSetBlockJob = { jobs += it }),
        )
        composeRule.onNodeWithText("Billable").performScrollTo().performClick()
        composeRule.onNodeWithText("Delete job block").performScrollTo().performClick()
        composeRule.onNodeWithText("Job").performClick()
        composeRule.onNodeWithText("Beta").performClick()
        assertThat(billable).containsExactly(false)
        assertThat(deleted).isEqualTo(1)
        assertThat(jobs).containsExactly("k")
    }

    @Test
    fun jobDrawerShowsTheInlineErrorForABadRange() {
        show(jobBlock().copy(error = EditError.EMPTY_RANGE))
        composeRule.onNodeWithText("The times need to leave this visit at least a moment long, and a split has to fall inside it.").assertExists()
    }

    @Test
    fun newJobSaveIsDisabledUntilAJobIsChosenThenSendsTheFormValues() {
        val saved = mutableListOf<List<Any?>>()
        show(newJob(), EditDrawerActions(onCreateBlock = { j, s, e, b, n -> saved += listOf(j, s, e, b, n) }))
        composeRule.onNodeWithText("New job block").assert(heading())
        composeRule.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Choose a job to save this block.").assertExists()
        composeRule.onNodeWithText("Delete job block").assertDoesNotExist()

        composeRule.onNodeWithText("Job").performClick()
        composeRule.onNodeWithText("Acme").performClick()

        composeRule.onNodeWithText("Choose a job to save this block.").assertDoesNotExist()
        composeRule.onNodeWithText("Save").performScrollTo().assertIsEnabled().performClick()
        assertThat(saved).containsExactly(listOf("j", at(10), at(11), null, ""))
    }

    @Test
    fun newJobBillableFollowsTheChosenJobUntilTheUserTouchesTheSwitch() {
        val saved = mutableListOf<Boolean?>()
        show(newJob(), EditDrawerActions(onCreateBlock = { _, _, _, b, _ -> saved += b }))
        composeRule.onNodeWithText("Job").performClick()
        composeRule.onNodeWithText("Beta").performClick() // default not billable
        composeRule.onNodeWithText("Billable").performScrollTo().assertIsOff()

        composeRule.onNodeWithText("Job").performClick()
        composeRule.onAllNodesWithText("Acme").onFirst().performClick() // default billable
        composeRule.onNodeWithText("Billable").performScrollTo().assertIsOn()

        composeRule.onNodeWithText("Billable").performClick() // touched: now off
        composeRule.onNodeWithText("Billable").assertIsOff()
        composeRule.onNodeWithText("Job").performClick()
        composeRule.onAllNodesWithText("Beta").onFirst().performClick()
        composeRule.onNodeWithText("Billable").performScrollTo().assertIsOff()
        composeRule.onNodeWithText("Job").performClick()
        composeRule.onAllNodesWithText("Acme").onFirst().performClick()
        composeRule.onNodeWithText("Billable").performScrollTo().assertIsOff() // no longer follows the job

        composeRule.onNodeWithText("Save").performScrollTo().performClick()
        assertThat(saved).containsExactly(false)
    }

    @Test
    fun newJobWithAnEndNotAfterTheStartShowsAnInlineErrorAndCannotBeSaved() {
        show(newJob(start = at(11), end = at(10), jobId = "j"))
        composeRule.onNodeWithText("The end needs to be after the start.").assertExists()
        composeRule.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun newJobPreselectedJobEnablesSaveAndStartsFromItsDefault() {
        show(newJob(jobId = "k").copy(billable = false))
        composeRule.onNodeWithText("Beta").assertExists()
        composeRule.onNodeWithText("Billable").performScrollTo().assertIsOff()
        composeRule.onNodeWithText("Save").performScrollTo().assertIsEnabled()
    }

    private fun visitWithBlocks(blocks: List<BlockChip> = listOf(BlockChip("b1", acme, at(9), at(11))), defaultJob: JobOption? = null) =
        visit().copy(jobs = listOf(acme, beta), blocks = blocks, placeDefaultJob = defaultJob)

    @Test
    fun visitDrawerListsOverlappingBlocksAsChipsThatOpenTheBlock() {
        val opened = mutableListOf<String>()
        show(visitWithBlocks(), EditDrawerActions(onOpenBlock = { opened += it }))
        composeRule.onNodeWithTag("drawer_block_b1").assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        assertThat(opened).containsExactly("b1")
    }

    @Test
    fun visitDrawerWithoutBlocksSaysSoAndOffersAddToJob() {
        val added = mutableListOf<String>()
        show(visitWithBlocks(blocks = emptyList()), EditDrawerActions(onAddToJob = { added += it }))
        composeRule.onNodeWithText("Not in a job yet.").assertExists()
        composeRule.onNodeWithText("Add to job").performClick()
        composeRule.onNodeWithText("Beta").performClick()
        assertThat(added).containsExactly("k")
    }

    @Test
    fun tripDrawerHasTheSameJobSectionButNoPlaceDefault() {
        val added = mutableListOf<String>()
        val trip = EditDrawerState(
            kind = EditKind.TRIP, id = "t", start = at(11), end = at(11, 25), zoneId = zone.id,
            tripMode = TripMode.VEHICLE, jobs = listOf(beta), blocks = listOf(BlockChip("b1", acme, at(11), at(12))),
        )
        show(trip, EditDrawerActions(onAddToJob = { added += it }))
        composeRule.onNodeWithTag("drawer_block_b1").assertExists()
        composeRule.onNodeWithText("Add to job").performClick()
        composeRule.onNodeWithText("Beta").performClick()
        assertThat(added).containsExactly("k")
        composeRule.onNode(hasText("Default job for", substring = true)).assertDoesNotExist()
    }

    @Test
    fun visitDrawerShowsThePlaceDefaultJobRowWithItsHelperAndCanClearIt() {
        val defaults = mutableListOf<String?>()
        show(visitWithBlocks(defaultJob = acme), EditDrawerActions(onSetPlaceDefaultJob = { defaults += it }))
        composeRule.onNodeWithText("Default job for Rose Bay Marina").assertHasClickAction()
        composeRule.onNodeWithText("Suggests this job for new visits here.").assertExists()

        composeRule.onNodeWithText("Default job for Rose Bay Marina").performClick()
        composeRule.onNodeWithText("None").performClick()
        composeRule.onNodeWithText("Default job for Rose Bay Marina").performClick()
        composeRule.onNodeWithText("Beta").performClick()
        assertThat(defaults).containsExactly(null, "k").inOrder()
    }

    @Test
    fun placeDefaultRowIsHiddenForAnIgnoredPlace() {
        show(visitWithBlocks().copy(placeIgnored = true))
        composeRule.onNode(hasText("Default job for", substring = true)).assertDoesNotExist()
    }

    @Test
    fun jobDrawerFitsAtFontScaleTwo() {
        show(jobBlock(), fontScale = 2f)
        composeRule.onNodeWithText("Covers 3 visits · 2 trips", useUnmergedTree = true).performScrollTo().assertNotTruncated()
        composeRule.onNodeWithText("Delete job block", useUnmergedTree = true).performScrollTo().assertNotTruncated()
    }

    // endregion
}
