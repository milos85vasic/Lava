package lava.connection

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import lava.models.settings.Endpoint

internal val Endpoint.title: String
    @Composable
    get() = stringResource(
        when (this) {
            is Endpoint.Proxy -> R.string.connection_endpoint_proxy
            is Endpoint.Rutracker -> R.string.connection_endpoint_rutracker
            is Endpoint.Mirror -> R.string.connection_endpoint_mirror
        },
    )
