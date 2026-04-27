package digital.vasic.lava.client

import dagger.hilt.android.AndroidEntryPoint
import lava.designsystem.platform.PlatformType

@AndroidEntryPoint
class TvActivity : MainActivity() {
    override val deviceType: PlatformType = PlatformType.TV
}
