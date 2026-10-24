package me.tarry.app.ui.places

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import me.tarry.app.segmentation.PreviewCentre
import me.tarry.app.segmentation.PreviewVisit
import me.tarry.app.segmentation.RadiusPreviewInput
import me.tarry.app.ui.drawer.PlaceOption
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.core.data.repository.MergePreview
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PlaceDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val ui = PlaceDetailUi(
        id = "p", label = "Shop", name = "Shop", address = "1 Main St", visitCount = 3,
        defaultJob = null, pickableJobs = emptyList(), defaultBillable = null, tagIds = emptySet(), tagNames = emptyList(),
        pickableTagGroups = emptyList(), ignored = false, radiusM = 75f,
        otherPlaces = listOf(PlaceOption("q", "Depot", "2 Side St")),
    )

    private var load by mutableStateOf<PlaceDetailLoad>(PlaceDetailLoad.Ready(ui))
    private var closed = 0
    private val saved = mutableListOf<Float>()
    private val merged = mutableListOf<String>()
    private var deleted = 0
    private var preview: MergePreview? = MergePreview(120f, capped = false)
    private var mergeGate: CompletableDeferred<MergePreview?>? = null
    private val centre = PreviewCentre(0.0, 0.0)
    private var input: RadiusPreviewInput? = RadiusPreviewInput("p", centre, emptyMap(), listOf(PreviewVisit("p", false, 0.0, 0.0)))

    private fun show() = composeRule.setContent {
        TarryTheme {
            PlaceDetailContent(
                load = load,
                onClose = { closed++ },
                loadRadiusInput = { input },
                loadMergePreview = { mergeGate?.await() ?: preview },
                onRename = {}, onSetJob = {}, onSetBillable = {}, onSetTags = {}, onSetIgnored = {},
                onSaveRadius = { saved += it },
                onMerge = { merged += it },
                onDelete = { deleted++ },
            )
        }
    }

    @Test
    fun showsTheDetailRowsAndTheRadiusValue() {
        show()

        composeRule.onNodeWithText("Default job").assertExists()
        composeRule.onNodeWithText("Default billable").assertExists()
        composeRule.onNodeWithText("Default tags").assertExists()
        composeRule.onNodeWithText("Ignore this place").assertExists()
        composeRule.onNodeWithText("75 m").assertExists()
        composeRule.onNodeWithText("Merge into…").performScrollTo().assertIsEnabled()
    }

    @Test
    fun minusAndPlusStepTheRadiusByFiveAndSaveCommitsOnce() {
        show()
        composeRule.onNodeWithText("Radius").performScrollTo().performClick()

        composeRule.onNodeWithContentDescription("Increase radius by 5 metres").performClick()
        composeRule.onNodeWithContentDescription("Increase radius by 5 metres").performClick()
        composeRule.onNodeWithContentDescription("Decrease radius by 5 metres").performClick()
        composeRule.onNodeWithText("80 m").assertExists()
        assertThat(saved).isEmpty()
        composeRule.onNodeWithText("Save").performScrollTo().performClick()

        assertThat(saved).containsExactly(80f)
    }

    @Test
    fun cancelDiscardsTheRadiusChange() {
        show()
        composeRule.onNodeWithText("Radius").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Increase radius by 5 metres").performClick()

        composeRule.onNodeWithText("Cancel").performScrollTo().performClick()

        assertThat(saved).isEmpty()
        composeRule.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun radiusSheetShowsLiveCountsAndTheSmallRadiusHint() {
        input = RadiusPreviewInput(
            "p", centre, emptyMap(),
            listOf(PreviewVisit("p", false, 0.0, 0.0), PreviewVisit("p", false, 0.001, 0.0), PreviewVisit("p", true, null, null)),
        )
        show()
        composeRule.onNodeWithText("Radius").performScrollTo().performClick()

        composeRule.onNodeWithText("1 detected visit stays here").assertExists()
        composeRule.onNodeWithText("1 detected visit would leave").assertExists()
        composeRule.onNodeWithText("1 edited visit will not change").assertExists()
        composeRule.onNodeWithText("Below 60 m", substring = true).assertDoesNotExist()
        repeat(4) { composeRule.onNodeWithContentDescription("Decrease radius by 5 metres").performClick() }
        repeat(8) { composeRule.onNodeWithContentDescription("Decrease radius by 5 metres").performClick() }
        composeRule.onNodeWithText("Below 60 m", substring = true).assertExists()
    }

    @Test
    fun mergeConfirmShowsTheRadiusAndTheDefaultsNote() {
        show()
        composeRule.onNodeWithText("Merge into…").performScrollTo().performClick()
        composeRule.onNodeWithText("Depot").performClick()

        composeRule.onNodeWithText("Merge into Depot?").assertExists()
        composeRule.onNodeWithText("combined circle will be 120 m", substring = true).assertExists()
        composeRule.onNodeWithText("may pick up this place's defaults", substring = true).assertExists()
        composeRule.onNodeWithText("limited to", substring = true).assertDoesNotExist()
        composeRule.onNode(hasText("Merge") and hasClickAction()).performClick()

        assertThat(merged).containsExactly("q")
    }

    @Test
    fun mergeConfirmStaysDisabledUntilThePreviewHasLoaded() {
        val gate = CompletableDeferred<MergePreview?>()
        mergeGate = gate
        show()
        composeRule.onNodeWithText("Merge into…").performScrollTo().performClick()
        composeRule.onNodeWithText("Depot").performClick()

        composeRule.onNode(hasText("Merge") and hasClickAction()).assertIsNotEnabled()
        gate.complete(MergePreview(120f, capped = false))
        composeRule.waitForIdle()
        composeRule.onNode(hasText("Merge") and hasClickAction()).assertIsEnabled()
    }

    @Test
    fun mergeConfirmWarnsWhenTheCapApplies() {
        preview = MergePreview(300f, capped = true)
        show()
        composeRule.onNodeWithText("Merge into…").performScrollTo().performClick()
        composeRule.onNodeWithText("Depot").performClick()

        composeRule.onNodeWithText("The circle is limited to 300 m", substring = true).assertExists()
        composeRule.onNodeWithText("combined circle will be 300 m", substring = true).assertExists()
    }

    @Test
    fun mergeIsDisabledWithNoOtherPlace() {
        load = PlaceDetailLoad.Ready(ui.copy(otherPlaces = emptyList()))
        show()

        composeRule.onNodeWithText("Merge into…").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun deleteConfirmExplainsAndSuggestsIgnore() {
        show()
        composeRule.onNodeWithText("Delete place").performScrollTo().performClick()

        composeRule.onNodeWithText("Delete Shop?").assertExists()
        composeRule.onNodeWithText("will come back as a new place", substring = true).assertExists()
        composeRule.onNodeWithText("turn on Ignore", substring = true).assertExists()
        assertThat(deleted).isEqualTo(0)
        composeRule.onNode(hasText("Delete") and hasClickAction()).performClick()

        assertThat(deleted).isEqualTo(1)
    }

    @Test
    fun theScreenClosesWhenThePlaceGoesMissing() {
        show()
        assertThat(closed).isEqualTo(0)

        load = PlaceDetailLoad.Missing
        composeRule.waitForIdle()

        assertThat(closed).isEqualTo(1)
    }
}
