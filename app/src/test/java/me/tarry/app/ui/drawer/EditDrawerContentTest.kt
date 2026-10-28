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
}
