package lava.search.input

import androidx.compose.ui.text.input.TextFieldValue
import lava.models.search.Suggest
import lava.search.input.components.ProviderChip

internal data class SearchInputState(
    val searchInput: TextFieldValue = TextFieldValue(),
    val suggests: List<Suggest> = emptyList(),
    val providerChips: List<ProviderChip> = emptyList(),
) {
    val showClearButton = searchInput.text.isNotEmpty()
}
