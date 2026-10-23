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

    private val acme = JobOption("job-acme", "Acme", 0xFFE3EDFB.toInt())
    private val parts = TagOption("tag-parts", "Parts")
    private val tools = TagOption("tag-tools", "Tools")
    private val groups = listOf(TagGroupOption("g", "Materials", listOf(parts, tools)))

    private fun visit(
        placeLabel: String? = "Rose Bay Marina",
        ownJob: JobOption? = null,
        placeJob: JobOption? = null,
        billable: Boolean? = null,
        placeBillable: Boolean? = null,
        tagIds: Set<String> = emptySet(),
        tagsInherited: Boolean = false,
        frozen: Boolean = false,
        origin: VisitOrigin = VisitOrigin.DETECTED,
        error: EditError? = null,
    ) = EditDrawerState(
        kind = EditKind.VISIT, id = "v", start = at(9), end = at(11), zoneId = zone.id,
        placeLabel = placeLabel, hasPlace = true,
        jobs = listOf(acme), tagGroups = groups,
        places = listOf(PlaceOption("p2", "Depot", "1 Main St")),
        ownJob = ownJob, placeJob = placeJob, billable = billable, placeBillable = placeBillable,
        tagIds = tagIds, tagsInherited = tagsInherited, frozen = frozen, origin = origin, error = error,
    )

    private fun show(state: EditDrawerState, actions: EditDrawerActions = EditDrawerActions(), fontScale: Float? = null) {
        composeRule.setContent {
            val density = LocalDensity.current
            val provided = if (fontScale == null) density else Density(density.density, fontScale = fontScale)
            CompositionLocalProvider(LocalDensity provides provided) {
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
    fun needsReviewChipShowsWithoutAResolvedJob() {
        show(visit())
        composeRule.onNodeWithText("Needs review").assertExists()
    }

    @Test
    fun placeDefaultJobCountsAsResolvedSoNoNeedsReviewChip() {
        show(visit(placeJob = acme))
        composeRule.onNodeWithText("Needs review").assertDoesNotExist()
        composeRule.onNodeWithText("From place: Acme").assertExists()
    }

    @Test
    fun ownJobIsShownWithoutTheFromPlacePrefix() {
        show(visit(ownJob = acme, placeJob = acme.copy(name = "Other")))
        composeRule.onNodeWithText("Acme").assertExists()
        composeRule.onNodeWithText("From place: Other").assertDoesNotExist()
    }

    @Test
    fun inheritedBillableIsLabelledAndOn() {
        show(visit(placeBillable = true))
        composeRule.onNodeWithText("From place: billable").assertExists()
        composeRule.onNode(hasText("Billable") and hasText("From place: billable", substring = true)).assertIsOn()
    }

    @Test
    fun billableSwitchReportsTheNewValue() {
        var value: Boolean? = null
        show(visit(billable = false), EditDrawerActions(onBillable = { value = it }))
        composeRule.onNodeWithText("Billable").performScrollTo().assertIsOff().performClick()
        assertThat(value).isTrue()
    }

    @Test
    fun explicitBillableIsNotLabelledAsInherited() {
        show(visit(billable = true, placeBillable = false))
        composeRule.onNodeWithText("From place: not billable").assertDoesNotExist()
        composeRule.onNodeWithText("Billable").assertIsOn()
    }

    @Test
    fun tagChipsAreCheckboxesThatToggle() {
        val toggled = mutableListOf<String>()
        show(visit(tagIds = setOf("tag-parts")), EditDrawerActions(onToggleTag = { toggled += it }))
        composeRule.onNodeWithText("Parts").assertIsSelected().assert(role(Role.Checkbox))
        composeRule.onNodeWithText("Tools").assertIsNotSelected().assert(role(Role.Checkbox)).performClick()
        composeRule.onNodeWithText("Parts").performClick()
        assertThat(toggled).containsExactly("tag-tools", "tag-parts").inOrder()
    }

    @Test
    fun inheritedTagsAreMarkedFromPlace() {
        show(visit(tagIds = setOf("tag-parts"), tagsInherited = true))
        composeRule.onNodeWithText("From place").assertExists()
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
    fun setAsDefaultIsASwitchThatReportsTheNewValue() {
        var value: Boolean? = null
        show(visit(), EditDrawerActions(onSetPlaceDefault = { value = it }))
        composeRule.onNodeWithText("Set as default for this place")
            .performScrollTo()
            .assert(role(Role.Switch))
            .assertIsOff()
            .performClick()
        assertThat(value).isTrue()
    }


    @Test
    fun sectionsAreOrderedJobTagsNotesTogglesTime() {
        show(visit())
        val order = composeRule.onAllNodes(heading()).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }
            .filter { it in setOf("Job", "Tags", "Notes", "Toggles", "Time") }
        assertThat(order).containsExactly("Job", "Tags", "Notes", "Toggles", "Time").inOrder()
    }

    @Test
    fun newTagSurfaceIsAlwaysVisibleAndClickable() {
        show(visit())
        composeRule.onNode(hasText("New tag") and hasClickAction()).assertExists().assert(role(Role.Button))
    }

    @Test
    fun newTagSurfaceShowsEvenWithNoTagGroups() {
        show(visit().copy(tagGroups = emptyList()))
        composeRule.onNode(hasText("New tag") and hasClickAction()).assertExists()
    }

    @Test
    fun togglesGroupHoldsBothSwitches() {
        show(visit())
        composeRule.onNodeWithText("Billable").assert(role(Role.Switch))
        composeRule.onNodeWithText("Set as default for this place").assert(role(Role.Switch))
    }

    @Test
    fun tripTogglesHaveBillableOnly() {
        val trip = EditDrawerState(kind = EditKind.TRIP, id = "t", start = at(11), end = at(11, 25), zoneId = zone.id)
        show(trip)
        composeRule.onNodeWithText("Billable").assert(role(Role.Switch))
        composeRule.onNodeWithText("Set as default for this place").assertDoesNotExist()
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
    fun tripShowsAttributionAndAddStopButNoTimeEdits() {
        var stops = 0
        val trip = EditDrawerState(
            kind = EditKind.TRIP, id = "t", start = at(11), end = at(11, 25), zoneId = zone.id,
            tripMode = TripMode.VEHICLE, distanceM = 12_400f, jobs = listOf(acme), tagGroups = groups,
        )
        show(trip, EditDrawerActions(onAddStop = { stops++ }))
        composeRule.onNodeWithText("Vehicle").assert(heading())
        composeRule.onNodeWithText("Unattributed").assertExists()
        composeRule.onNodeWithText("Trim start").assertDoesNotExist()
        composeRule.onNodeWithText("Delete visit").assertDoesNotExist()
        composeRule.onNodeWithText("Add a stop during this trip").performScrollTo().performClick()
        assertThat(stops).isEqualTo(1)
    }

    @Test
    fun newVisitRendersOnlyTheTitleAndRange() {
        val state = EditDrawerState(kind = EditKind.NEW_VISIT, id = null, start = at(10), end = at(11), zoneId = zone.id)
        show(state)
        composeRule.onNodeWithText("New visit").assert(heading())
        composeRule.onNodeWithText("Delete visit").assertDoesNotExist()
        composeRule.onNodeWithText("Needs review").assertDoesNotExist()
    }

    @Test
    fun jobRowOpensThePickerWithNoneAndNewJob() {
        val picked = mutableListOf<String?>()
        show(visit(), EditDrawerActions(onSetJob = { picked += it }))
        composeRule.onNodeWithText("No job").performClick()
        composeRule.onNodeWithText("Choose a job").assertExists()
        composeRule.onNodeWithText("New job…").assertExists()
        composeRule.onNodeWithText("Acme").performClick()
        assertThat(picked).containsExactly("job-acme")
    }

    @Test
    fun fontScaleTwoKeepsHeaderRowsAndLabelsUntruncated() {
        show(visit(placeLabel = "Rose Bay Marina", frozen = true, placeJob = acme, placeBillable = true), fontScale = 2f)
        composeRule.onNodeWithText("Rose Bay Marina", useUnmergedTree = true).assertNotTruncated()
        composeRule.onNodeWithText("From place: Acme", useUnmergedTree = true).performScrollTo().assertNotTruncated()
        composeRule.onNodeWithText("From place: billable", useUnmergedTree = true).performScrollTo().assertNotTruncated()
        composeRule.onNodeWithText("Set as default for this place", useUnmergedTree = true).performScrollTo().assertNotTruncated()
        composeRule.onNodeWithText("New tag", useUnmergedTree = true).performScrollTo().assertNotTruncated()
        composeRule.onNodeWithText("Trim start", useUnmergedTree = true).performScrollTo().assertNotTruncated()
        composeRule.onNodeWithText("Merge with previous", useUnmergedTree = true).performScrollTo().assertNotTruncated()
        composeRule.onNodeWithText("Reset to detected", useUnmergedTree = true).performScrollTo().assertNotTruncated()
        composeRule.onNodeWithText("Delete visit", useUnmergedTree = true).performScrollTo().assertNotTruncated()
    }
}
