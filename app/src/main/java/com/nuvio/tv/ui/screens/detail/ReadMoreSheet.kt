package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.marquee.MarqueeEyebrow
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Read more (Spec 29 stage 2: the person page's biography; stage 7: a studio's description): the
 * About sheet shell with one centred `person.sheetWidth` column: "About", [title], the full [body],
 * then [facts] (label to value). Nothing in it takes focus; Up/Down scroll and Back closes ([onDismiss]).
 */
@Composable
internal fun ReadMoreSheet(
    title: String,
    body: String?,
    facts: List<Pair<String, String>>,
    accent: () -> Color,
    onDismiss: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    val person = marquee.person
    val styles = NuvioTheme.textStyles
    // The scrollbar runs in the end gutter; the start gutter keeps the column centred.
    val gutter = NuvioTheme.spacing.lg
    val firstFocus = remember { FocusRequester() }
    AboutSheetScaffold(
        onDismiss = onDismiss,
        hasFocusTargets = false,
        firstFocus = firstFocus,
        area = Modifier
            .padding(top = person.sheetTop)
            .fillMaxWidth()
            .wrapContentWidth()
            .width(person.sheetWidth + gutter * 2)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = gutter),
            verticalArrangement = Arrangement.spacedBy(person.sheetGap)
        ) {
            MarqueeEyebrow(text = stringResource(R.string.about_title), accent = accent)
            Text(
                text = title,
                style = styles.blockTitle,
                color = NuvioPrimitives.white,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            body?.let { Text(text = it, style = styles.bodyLong, color = marquee.onImageBody) }
            if (facts.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = person.sheetFactsTop)) {
                    facts.forEach { (label, value) ->
                        AboutFactRow(label = label, labelWidth = person.sheetLabelWidth) {
                            AboutValue(AnnotatedString(value))
                        }
                    }
                }
            }
        }
    }
}
