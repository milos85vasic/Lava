/*
 * SP-3a Phase 5 Challenge Test C6 — Download .torrent file.
 *
 * Per docs/superpowers/plans/2026-04-30-sp3a-multi-tracker-sdk-foundation.md
 * Task 5.14. Compose UI test that, from TopicDetail, taps Download and
 * asserts a file is written to the app's downloads directory and the
 * bytes parse as a valid bencoded torrent (info.pieces field present).
 *
 * STATUS: Source-only at SP-3a Phase 5 commit. Operator runs on a real
 * device per Task 5.22.
 *
 * FALSIFIABILITY REHEARSAL:
 *
 *   1. In core/tracker/rutor/src/main/kotlin/lava/tracker/rutor/
 *      RuTorDownload.kt short-circuit downloadTorrentFile() to return
 *      ByteArray(0).
 *   2. Re-run on real device.
 *   3. Expected failure: file written but empty; assertion
 *      'file.length > 0' fails or the bencoded-torrent parse fails.
 *   4. Revert; re-run; test passes.
 */
package lava.app.challenges

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

@HiltAndroidTest
class C6_DownloadTorrentFileTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun searchTapResultDownload_torrentFileWrittenWithValidBencodedInfoPieces() {
        hiltRule.inject()

        // Step 1: drive search → tap result → TopicDetail.
        composeRule.onNodeWithText("Search").performClick()
        composeRule.onNodeWithContentDescription("Search field")
            .performTextInput("ubuntu")
        composeRule.onNodeWithText("Go").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("seeders", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onAllNodesWithText("seeders", substring = true)
            .onFirst()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Download")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // Step 2: tap Download.
        composeRule.onNodeWithContentDescription("Download").performClick()

        // Step 3: assert primary user-visible state — a file was written
        // to the downloads dir and parses as a bencoded torrent
        // (Sixth Law clause 3: "file written to disk" qualifies).
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val downloadsDir = File(ctx.filesDir, "downloads")
        composeRule.waitUntil(timeoutMillis = 30_000) {
            downloadsDir.exists() &&
                (downloadsDir.listFiles()?.any { it.length() > 0 } == true)
        }
        val torrentFile = downloadsDir.listFiles()
            ?.firstOrNull { it.length() > 0 }
            ?: error("No non-empty torrent file written to $downloadsDir")
        assertTrue("Torrent file too small: ${torrentFile.length()} bytes",
            torrentFile.length() > 100)

        // Bencoded torrent files start with 'd' and contain '4:info'.
        // The 'info' dictionary contains 'pieces' (SHA1 hashes).
        val bytes = torrentFile.readBytes()
        assertTrue("File does not start with bencoded dict marker 'd'",
            bytes.isNotEmpty() && bytes[0] == 'd'.code.toByte())
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue("info.pieces field not present in bencoded torrent",
            text.contains("6:pieces"))
    }
}
