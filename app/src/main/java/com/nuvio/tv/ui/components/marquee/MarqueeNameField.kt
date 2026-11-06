package com.nuvio.tv.ui.components.marquee

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The name field (`.l10in`, the search field's fills and edge; U10 profile editor, U11 rename
 * panels): typed live into [value], [placeholder] while empty, the "n/[maxLength]" counter at its
 * end ([showCounter] false drops it: the Live TV add-source URL field); lit white while focused,
 * red-edged while [isError] (Spec 52 `.l10in.err`). Each change goes through [inputTransform]
 * first (the TMDB key field drops whitespace and cuts at its length, so a padded paste still
 * lands); a result longer than [maxLength] is ignored (as built for profiles). [textStyle] sets
 * the typed text (the placeholder keeps the field's). IME Done runs [onDone].
 *
 * [keyboardOnOk]: focus (from [focusRequester], e.g. a panel's first focus) lands on the field
 * with the keyboard down and OK opens it, InputField's pattern; the text field takes focus only
 * while editing and editing ends when it loses focus. For panels whose other content the keyboard
 * would cover on open (12/N-3: the TMDB key panel's QR and buttons).
 */
@Composable
internal fun MarqueeNameField(
    value: String,
    onValueChange: (String) -> Unit,
    maxLength: Int,
    placeholder: String,
    focusRequester: FocusRequester,
    onDone: () -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    showCounter: Boolean = true,
    isError: Boolean = false,
    inputTransform: (String) -> String = { it },
    textStyle: TextStyle = NuvioTheme.textStyles.profileField,
    keyboardOnOk: Boolean = false
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.profiles
    var textFocused by remember { mutableStateOf(false) }
    var shellFocused by remember { mutableStateOf(false) }
    val isFocused = textFocused || shellFocused
    var editing by remember { mutableStateOf(false) }
    val textFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(editing) {
        if (!editing) return@LaunchedEffect
        // At once (the text field is always composed): a frame wait would let a quick Down leave
        // the shell first and then pull focus back.
        if (textFocus.requestFocus()) keyboard?.show() else editing = false
    }
    val shape = RoundedCornerShape(tokens.fieldRadius)
    Row(
        modifier = Modifier
            .then(
                if (keyboardOnOk) {
                    Modifier
                        .focusRequester(focusRequester)
                        .onFocusChanged { shellFocused = it.isFocused }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            editing = true
                        }
                } else {
                    Modifier
                }
            )
            .fillMaxWidth()
            .height(tokens.fieldHeight)
            .background(if (isFocused) marquee.search.fieldFocusedFill else marquee.search.fieldFill, shape)
            .border(
                width = if (isFocused || isError) marquee.ringWidth else NuvioTheme.spacing.hairline,
                color = when {
                    isError -> NuvioPrimitives.errorOnImage
                    isFocused -> marquee.ring
                    else -> marquee.search.fieldEdge
                },
                shape = shape
            )
            .padding(horizontal = tokens.fieldPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(text = placeholder, style = NuvioTheme.textStyles.profileField, color = marquee.onImage3)
            }
            BasicTextField(
                value = value,
                onValueChange = { raw -> inputTransform(raw).let { if (it.length <= maxLength) onValueChange(it) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusProperties { canFocus = !keyboardOnOk || editing }
                    .focusRequester(if (keyboardOnOk) textFocus else focusRequester)
                    .onFocusChanged {
                        // The first report (on attach) is "not focused": only a loss after focus ends editing.
                        if (textFocused && !it.isFocused) editing = false
                        textFocused = it.isFocused
                    },
                textStyle = textStyle.copy(color = NuvioPrimitives.white),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                cursorBrush = SolidColor(NuvioPrimitives.white)
            )
        }
        if (showCounter) {
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Text(
                text = stringResource(R.string.profile_name_counter, value.length, maxLength),
                style = NuvioTheme.textStyles.profileLine,
                color = marquee.onImage3
            )
        }
    }
}
