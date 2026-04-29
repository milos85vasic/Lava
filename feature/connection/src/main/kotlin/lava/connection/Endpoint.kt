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
            // SP-3.4 (2026-04-29): dedicated "Lava API" label so the
            // user can tell a discovered lava-api-go entry apart from
            // a manually-configured rutracker mirror. The bug was that
            // every GoApi rendered as "Mirror" in the Connections list,
            // which led users to suspect the routing was wrong even
            // when it was correct.
            is Endpoint.GoApi -> R.string.connection_endpoint_goapi
        },
    )
