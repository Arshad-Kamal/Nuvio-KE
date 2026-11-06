package com.nuvio.tv.data.remote.supabase

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Remote asset ids become cache file names: they must never leave the cache directory. */
class MemberAssetCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `remote ids are reduced to safe path tokens`() {
        assertEquals("....etcpasswd", "../../etc/passwd".toSafePathToken())
        assertEquals("gold_star-2.v1", "gold_star-2.v1".toSafePathToken())
        assertEquals("x", "/\\".toSafePathToken())
        assertEquals(64, "a".repeat(100).toSafePathToken().length)
    }

    @Test
    fun `fileFor stays inside the cache directory and keeps the legacy name for safe ids`() {
        val dir = tmp.newFolder("member_profile_backgrounds")
        val cache = MemberAssetCache(dir)

        assertEquals(dir, cache.fileFor("../../evil", 3, "png").parentFile)
        assertEquals("aurora-v7.png", cache.fileFor("aurora", 7, "png").name)
    }

    @Test
    fun `download writes atomically and is reused`() = runTest {
        val cache = MemberAssetCache(tmp.newFolder("member_profile_avatars"))
        var downloads = 0

        val first = cache.getOrDownload("star", 1, "webp") { downloads++; byteArrayOf(1, 2, 3) }
        val second = cache.getOrDownload("star", 1, "webp") { downloads++; byteArrayOf(9) }

        assertEquals(first, second)
        assertEquals(1, downloads)
        assertEquals(3L, first.length())
        assertTrue(first.parentFile!!.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `latest picks the highest cached version`() {
        val dir = tmp.newFolder("member_profile_backgrounds")
        val cache = MemberAssetCache(dir)
        cache.fileFor("aurora", 2, "png").writeBytes(byteArrayOf(1))
        cache.fileFor("aurora", 10, "png").writeBytes(byteArrayOf(1))
        cache.fileFor("aurora", 11, "jpg").writeBytes(byteArrayOf(1))
        cache.fileFor("aurora", 12, "png").writeBytes(ByteArray(0)) // empty = unusable

        val (file, version) = cache.latest("aurora", "png")!!

        assertEquals(10, version)
        assertEquals("aurora-v10.png", file.name)
        assertNull(cache.latest("missing"))
    }
}
