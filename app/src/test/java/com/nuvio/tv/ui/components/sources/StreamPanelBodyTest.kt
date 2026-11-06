package com.nuvio.tv.ui.components.sources

import org.junit.Assert.assertEquals
import org.junit.Test

/** U5k sources panel body: the as-built order loading > error > empty > list. */
class StreamPanelBodyTest {

    @Test
    fun `loading wins over an error and over streams already shown`() {
        assertEquals(StreamPanelBody.LOADING, streamPanelBody(isLoading = true, hasError = true, hasStreams = true))
    }

    @Test
    fun `an error shows even with streams`() {
        assertEquals(StreamPanelBody.ERROR, streamPanelBody(isLoading = false, hasError = true, hasStreams = true))
    }

    @Test
    fun `no streams is empty, streams are the list`() {
        assertEquals(StreamPanelBody.EMPTY, streamPanelBody(isLoading = false, hasError = false, hasStreams = false))
        assertEquals(StreamPanelBody.LIST, streamPanelBody(isLoading = false, hasError = false, hasStreams = true))
    }
}
