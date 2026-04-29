package lava.connection

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import lava.models.settings.Endpoint

// SP-3.2 (2026-04-29): `Endpoint.Proxy` was removed from the model;
// no `Proxy` branch needed here.
internal val Endpoint.title: String
    @Composable
    get() = stringResource(
        when (this) {
            is Endpoint.Rutracker -> R.string.connection_endpoint_rutracker
            is Endpoint.Mirror -> R.string.connection_endpoint_mirror
            // SP-3 LAN api-go reuses the mirror string for now;
            // dedicated string can be added later.
            is Endpoint.GoApi -> R.string.connection_endpoint_mirror
        },
    )
