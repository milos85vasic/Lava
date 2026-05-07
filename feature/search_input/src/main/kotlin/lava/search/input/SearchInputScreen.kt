package lava.search.input

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import lava.designsystem.component.AppBar
import lava.designsystem.component.AppBarState
import lava.designsystem.component.BackButton
import lava.designsystem.component.Icon
import lava.designsystem.component.IconButton
import lava.designsystem.component.LazyList
import lava.designsystem.component.Scaffold
import lava.designsystem.component.Surface
import lava.designsystem.component.Text
import lava.designsystem.component.TextField
import lava.designsystem.component.onEnter
import lava.designsystem.component.rememberFocusRequester
import lava.designsystem.drawables.LavaIcons
import lava.designsystem.theme.AppTheme
import lava.designsystem.utils.RunOnFirstComposition
import lava.models.search.Filter
import lava.models.search.Suggest
import lava.search.input.components.ProviderChipBar
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect
import lava.designsystem.R as DsR

@Composable
internal fun SearchInputScreen(
    viewModel: SearchInputViewModel,
    back: () -> Unit,
    openSearchResult: (Filter) -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    viewModel.collectSideEffect { sideEffect ->
        when (sideEffect) {
            is SearchInputSideEffect.Back -> back()
            is SearchInputSideEffect.HideKeyboard -> keyboardController?.hide()
            is SearchInputSideEffect.OpenSearch -> openSearchResult(sideEffect.filter)
        }
    }
    val state by viewModel.collectAsState()
    SearchInputScreen(
        state = state,
        onAction = viewModel::perform,
    )
}

@Composable
private fun SearchInputScreen(
    state: SearchInputState,
    onAction: (SearchInputAction) -> Unit,
) = Scaffold(
    topBar = { appBarState ->
        SearchInputAppBar(
            inputValue = state.searchInput,
            onInputValueChange = { onAction(SearchInputAction.InputChanged(it)) },
            showClearButton = state.showClearButton,
            onClearButtonClick = { onAction(SearchInputAction.ClearInputClick) },
            onSubmitClick = { onAction(SearchInputAction.SubmitClick) },
            onBackClick = { onAction(SearchInputAction.BackClick) },
            appBarState = appBarState,
        )
    },
    content = { padding ->
        Column(modifier = Modifier.padding(padding)) {
            ProviderChipBar(
                chips = state.providerChips,
                onChipToggled = { providerId -> onAction(SearchInputAction.ProviderToggled(providerId)) },
            )
            LazyList(
                contentPadding = PaddingValues(vertical = AppTheme.spaces.medium),
            ) {
                items(items = state.suggests) { suggest ->
                    SuggestItem(
                        suggest = suggest,
                        onClick = { onAction(SearchInputAction.SuggestClick(suggest)) },
                        onSubmit = { onAction(SearchInputAction.SuggestEditClick(suggest)) },
                    )
                }
            }
        }
    },
)

@Composable
private fun SearchInputAppBar(
    modifier: Modifier = Modifier,
    inputValue: TextFieldValue,
    onInputValueChange: (TextFieldValue) -> Unit,
    showClearButton: Boolean,
    onClearButtonClick: () -> Unit,
    onSubmitClick: () -> Unit,
    onBackClick: () -> Unit,
    appBarState: AppBarState,
) = AppBar(
    modifier = modifier,
    navigationIcon = { BackButton(onBackClick) },
    title = {
        SearchInputField(
            modifier = Modifier.fillMaxWidth(),
            inputValue = inputValue,
            onInputValueChange = onInputValueChange,
            showClearButton = showClearButton,
            onClearButtonClick = onClearButtonClick,
            onSubmitClick = onSubmitClick,
        )
    },
    appBarState = appBarState,
)

@Composable
private fun SearchInputField(
    modifier: Modifier = Modifier,
    inputValue: TextFieldValue,
    onInputValueChange: (TextFieldValue) -> Unit,
    showClearButton: Boolean,
    onClearButtonClick: () -> Unit,
    onSubmitClick: () -> Unit,
) {
    val focusRequester = rememberFocusRequester()
    RunOnFirstComposition { focusRequester.requestFocus() }
    TextField(
        modifier = modifier
            .focusRequester(focusRequester)
            .onEnter(onSubmitClick),
        value = inputValue,
        placeholder = { Text(stringResource(DsR.string.designsystem_hint_search)) },
        onValueChange = onInputValueChange,
        trailingIcon = {
            AnimatedVisibility(
                visible = showClearButton,
                enter = fadeIn() + expandIn(expandFrom = Alignment.Center),
                exit = fadeOut() + shrinkOut(shrinkTowards = Alignment.Center),
            ) {
                IconButton(
                    icon = LavaIcons.Clear,
                    contentDescription = stringResource(DsR.string.designsystem_action_clear),
                    onClick = onClearButtonClick,
                )
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            autoCorrect = true,
            imeAction = ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(
            onSearch = { onSubmitClick() },
        ),
    )
}

@Composable
private fun SuggestItem(
    suggest: Suggest,
    onClick: () -> Unit,
    onSubmit: () -> Unit,
) = Surface(
    modifier = Modifier
        .fillMaxWidth()
        .height(AppTheme.sizes.default),
    onClick = onClick,
) {
    Row(
        modifier = Modifier.padding(horizontal = AppTheme.spaces.large),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon = LavaIcons.History, contentDescription = null)
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = AppTheme.spaces.large),
        ) {
            SuggestText(value = suggest.value, substring = suggest.substring)
        }
        IconButton(
            icon = LavaIcons.InsertSuggest,
            contentDescription = stringResource(DsR.string.designsystem_content_description_state_edit),
            onClick = onSubmit,
        )
    }
}

@Composable
private fun SuggestText(value: String, substring: IntRange?) {
    if (substring == null) {
        Text(value)
    } else {
        val substringStartIndex = substring.first
        val substringEndIndex = substring.last
        Text(
            text = buildAnnotatedString {
                if (substringStartIndex > 0) {
                    append(value.substring(0, substringStartIndex))
                }
                withStyle(style = SpanStyle(color = AppTheme.colors.accentOrange)) {
                    append(value.substring(substring))
                }
                if (value.lastIndex > substringEndIndex) {
                    append(value.substring(substringEndIndex + 1))
                }
            },
        )
    }
}
