package com.celander.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/** What each Android version wants before it hands out the wallpaper, in the order the switch asks. */
class WallpaperAccessTest {
    private fun missing(sdk: Int, allFiles: Boolean = false, photos: Boolean = false) = WallpaperBlur.missing(sdk, allFiles, photos)

    @Test
    fun android12WantsStorage() {
        assertEquals(listOf(BlurAccess.PHOTOS), missing(31))
        assertEquals(emptyList<BlurAccess>(), missing(32, photos = true))
    }

    @Test
    fun android13To15WantAllFilesAndThenAllPhotos() {
        for (sdk in 33..35) {
            assertEquals(listOf(BlurAccess.FILES, BlurAccess.PHOTOS), missing(sdk))
            assertEquals(listOf(BlurAccess.PHOTOS), missing(sdk, allFiles = true))
            assertEquals(listOf(BlurAccess.FILES), missing(sdk, photos = true))
            assertEquals(emptyList<BlurAccess>(), missing(sdk, allFiles = true, photos = true))
        }
    }

    @Test
    fun android16WantsAllFilesOnly() {
        assertEquals(listOf(BlurAccess.FILES), missing(36))
        assertEquals(emptyList<BlurAccess>(), missing(36, allFiles = true))
    }
}
