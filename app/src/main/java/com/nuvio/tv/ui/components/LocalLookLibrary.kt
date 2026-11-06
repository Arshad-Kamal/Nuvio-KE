package com.nuvio.tv.ui.components

import androidx.compose.runtime.Immutable
import com.nuvio.tv.data.local.MyPhoto
import com.nuvio.tv.data.local.MyPhotoKind
import com.nuvio.tv.domain.model.LocalLookSets

/**
 * What TV-only looks resolve and draw against (Spec 44): the bundled sets plus the current My
 * photos. Built by each host from `MyPhotosStore.photos`, so a removed photo resolves to the synced
 * value everywhere at once (D5).
 */
@Immutable
data class LocalLookLibrary(val photos: List<MyPhoto>) {
    val photosById: Map<String, MyPhoto> = photos.associateBy { it.id }
    /** The Avatar tab's photos and the Background tab's, newest first. */
    val avatars: List<MyPhoto> = photos.filter { it.kind == MyPhotoKind.Avatar }
    val backgrounds: List<MyPhoto> = photos.filter { it.kind == MyPhotoKind.Background }
    val sets: LocalLookSets = BundledLookSets.copy(photoIds = photosById.keys)

    companion object {
        /** No photos: before the store has loaded, or where photos are not drawn. */
        val Bundled = LocalLookLibrary(emptyList())
    }
}
