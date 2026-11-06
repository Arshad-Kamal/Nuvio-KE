package com.nuvio.tv.ui.screens.cast

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.detail.ReadMoreSheet

/** Read more on the biography (Spec 29 stage 2): the name, the full biography, then the person's facts. */
@Composable
internal fun PersonReadMoreSheet(page: PersonPage, accent: () -> Color, onDismiss: () -> Unit) {
    ReadMoreSheet(
        title = page.person.name,
        body = page.biography,
        facts = page.facts.map { fact -> stringResource(fact.kind.labelRes) to fact.value },
        accent = accent,
        onDismiss = onDismiss
    )
}

private val PersonFactKind.labelRes: Int
    @StringRes get() = when (this) {
        PersonFactKind.KNOWN_FOR -> R.string.cast_fact_known_for
        PersonFactKind.BORN -> R.string.cast_fact_born
        PersonFactKind.DIED -> R.string.cast_fact_died
        PersonFactKind.PLACE_OF_BIRTH -> R.string.cast_fact_place_of_birth
        PersonFactKind.ALSO_KNOWN_AS -> R.string.cast_fact_also_known_as
    }
