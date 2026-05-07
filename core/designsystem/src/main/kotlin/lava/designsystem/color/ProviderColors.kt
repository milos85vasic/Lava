package lava.designsystem.color

import androidx.compose.ui.graphics.Color

object ProviderColors {
    val rutracker = Color(0xFF1976D2)
    val rutor = Color(0xFF00897B)
    val archiveorg = Color(0xFFF9A825)
    val gutenberg = Color(0xFF7B1FA2)
    val nnmclub = Color(0xFFD32F2F)
    val kinozal = Color(0xFFE64A19)

    fun forProvider(providerId: String): Color = when (providerId) {
        "rutracker" -> rutracker
        "rutor" -> rutor
        "archiveorg" -> archiveorg
        "gutenberg" -> gutenberg
        "nnmclub" -> nnmclub
        "kinozal" -> kinozal
        else -> Color.Gray
    }
}
