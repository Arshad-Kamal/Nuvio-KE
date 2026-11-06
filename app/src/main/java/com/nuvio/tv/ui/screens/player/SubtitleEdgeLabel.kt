package com.nuvio.tv.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.data.local.SubtitleEdgeStyle

@Composable
internal fun subtitleEdgeLabel(edge: SubtitleEdgeStyle): String = when (edge) {
    SubtitleEdgeStyle.NONE -> stringResource(R.string.subtitle_none)
    SubtitleEdgeStyle.OUTLINE -> stringResource(R.string.subtitle_style_outline)
    SubtitleEdgeStyle.DROP_SHADOW -> stringResource(R.string.subtitle_edge_drop_shadow)
}
