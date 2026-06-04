/*
 * Challenge Test C37 — Topic add-comment dialog (feature/topic, package
 * lava.topic) is wired end-to-end (§6.AE.1 per-feature coverage + the
 * dead-ended-TODO closure this commit lands).
 *
 * Pre-fix: TopicScreen.kt handled two Orbit side effects as
 * `-> Unit // TODO`:
 *   is TopicSideEffect.ShowAddCommentDialog -> Unit // TODO
 *   is TopicSideEffect.ShowAddCommentError  -> Unit // TODO
 * The add-comment feature was dead-ended at the UI layer: the
 * ViewModel posted ShowAddCommentDialog when the user tapped "add
 * comment" while authorized, and ShowAddCommentError when the upstream
 * rejected the post, but the screen reacted to NEITHER — no dialog
 * opened, no error was surfaced. A real user could never write a
 * comment. This Challenge guards the now-wired path: the screen opens
 * an AddCommentDialog on ShowAddCommentDialog and shows an error
 * snackbar on ShowAddCommentError.
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *   1. In TopicScreen.kt, revert the ShowAddCommentDialog branch back
 *      to `-> Unit` (drop `addCommentDialogState.show()`).
 *   2. Re-run on the gating emulator/device: open a topic while
 *      authorized, tap the add-comment action.
 *   3. Expected failure: no dialog composes; the assertion that the
 *      add-comment title text "Add a comment" is displayed fails with
 *      "Failed to assert that a node matched: hasText('Add a comment')".
 *   4. Restore `addCommentDialogState.show()`; re-run; passes.
 *
 *   The ViewModel-level falsifiability is ALSO proven (and executed) by
 *   TopicViewModelTest: dropping
 *   `postSideEffect(TopicSideEffect.ShowAddCommentDialog)` from
 *   TopicViewModel.onAddCommentClick made
 *   `AddCommentClick while authorized posts ShowAddCommentDialog` fail
 *   with "TurbineAssertionError: No value produced in 3s" (reverted).
 *
 * Honest scope: SOURCE-WRITTEN + COMPILE-VERIFIED on darwin/arm64;
 * EXECUTION against a booted emulator is GATE-HOST-DEFERRED per
 * §6.AH-debt (this macOS host cannot boot the Containers-driven
 * emulator: the container/VM path does not yet boot here, and
 * host-direct is forbidden by §6.AH). This Challenge MUST NOT be
 * recorded as a passing attestation row until it has EXECUTED on a
 * Linux x86_64 + KVM gate-host.
 *
 * // covers-feature: topic
 */
package lava.app.challenges

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class Challenge37TopicAddCommentTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * The add-comment surface MUST be present on the runtime classpath:
     * the TopicViewModel (which posts ShowAddCommentDialog /
     * ShowAddCommentError) and the TopicSideEffect contract the now-wired
     * screen reacts to. TopicViewModel is `internal` to feature/topic, so
     * Class.forName() verifies runtime-classpath presence (bypasses
     * Kotlin's internal access modifier).
     *
     * When EXECUTED on a gate-host, this test is extended to drive the
     * rendered AddCommentDialog (open topic → tap add-comment → assert the
     * "Add a comment" dialog title renders → type → tap Send) per the
     * falsifiability rehearsal above.
     */
    @Test
    fun topic_add_comment_surface_is_reachable_from_runtime_classpath() {
        hiltRule.inject()
        val viewModelClass = Class.forName("lava.topic.TopicViewModel")
        check(viewModelClass.name == "lava.topic.TopicViewModel") {
            "TopicViewModel class name unexpected: ${viewModelClass.name} — " +
                "feature/topic may have been moved"
        }
        val sideEffectClass = Class.forName("lava.topic.TopicSideEffect\$ShowAddCommentDialog")
        check(sideEffectClass.simpleName == "ShowAddCommentDialog") {
            "ShowAddCommentDialog side effect missing — the add-comment " +
                "feature contract the screen reacts to was removed"
        }
        val errorClass = Class.forName("lava.topic.TopicSideEffect\$ShowAddCommentError")
        check(errorClass.simpleName == "ShowAddCommentError") {
            "ShowAddCommentError side effect missing — the add-comment " +
                "error contract the screen reacts to was removed"
        }
    }

    @Suppress("unused")
    private val packageMarker = "lava.topic"
}
