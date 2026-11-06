package com.nuvio.tv.ui.screens.collection

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.theme.NuvioMarquee
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 41 D5: a folder's preview tile takes its Tile shape, or the landscape tile under the override. */
class FolderPreviewTileSizeTest {

    private val home = NuvioMarquee.tokens.home

    private fun size(shape: PosterShape, override: Boolean) =
        folderPreviewTileSize(shape, override, 54.dp, 80.dp, 88.dp, 50.dp, home)

    @Test
    fun `each tile shape draws at the poster height unless the override is on`() {
        assertEquals(DpSize(54.dp, 80.dp), size(PosterShape.POSTER, override = false))
        assertEquals(DpSize(80.dp, 80.dp), size(PosterShape.SQUARE, override = false))
        assertEquals(80f * 299f / 168f, size(PosterShape.LANDSCAPE, override = false).width.value, 0.01f)
        for (shape in PosterShape.entries) assertEquals(DpSize(88.dp, 50.dp), size(shape, override = true))
    }
}
