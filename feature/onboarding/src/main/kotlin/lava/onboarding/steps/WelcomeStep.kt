package lava.onboarding.steps

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import lava.designsystem.R
import lava.designsystem.component.Button
import lava.designsystem.component.Surface
import lava.designsystem.component.Text
import lava.designsystem.theme.AppTheme

@Composable
fun WelcomeStep(
    // Issue #6 (2026-06-25): nullable. `null` → omit the specific count (the
    // honest state when the provider list will be (re)populated from the
    // chosen API's catalogue on a LATER step, so any number here would be
    // premature and contradict the picker). Non-null → "N providers available",
    // shown only when this number equals the very next screen's list.
    providerCount: Int?,
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = AppTheme.colors.background,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The brand mark is a colored composited PNG (per density). Use
            // androidx.compose.foundation.Image — NOT designsystem.Icon which
            // wraps Material3.Icon + applies LocalContentColor as tint, designed
            // for monochrome glyphs only and would re-tint our colored bitmap
            // to a single solid color (the §6.AB white-placeholder forensic
            // anchor reported on Lava-Android-1.2.20-1040 by the operator).
            Image(
                painter = painterResource(id = R.drawable.ic_lava_logo),
                contentDescription = null,
                modifier = Modifier.size(80.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Welcome to Lava",
                style = AppTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                // Issue #6: with a known, list-accurate count show it; otherwise
                // show count-free copy rather than a premature number that the
                // provider picker would contradict after API selection.
                text = if (providerCount != null) {
                    "$providerCount providers available"
                } else {
                    "Multiple content providers available"
                },
                style = AppTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Connect to your favorite content providers.\nLet's set everything up.",
                style = AppTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(48.dp))
            Button(
                text = "Get Started",
                onClick = onGetStarted,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
