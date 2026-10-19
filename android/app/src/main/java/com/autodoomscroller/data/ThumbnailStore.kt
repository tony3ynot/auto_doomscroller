package com.autodoomscroller.data

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/**
 * Frames kept only in research mode: 224 px thumbnails named `<reelId>_<frameIndex>.jpg`, plus
 * one 540 px frame per reel named `<reelId>_hires.jpg` for OCR.
 */
class ThumbnailStore(private val dir: File) {

    fun save(reelId: Long, frameIndex: Int, bitmap: Bitmap) {
        dir.mkdirs()
        File(dir, "${reelId}_$frameIndex.jpg").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
        }
    }

    fun saveHiRes(reelId: Long, bitmap: Bitmap) {
        dir.mkdirs()
        File(dir, "${reelId}_hires.jpg").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, HI_RES_QUALITY, it)
        }
    }

    fun all(): List<File> = dir.listFiles()?.filter { it.extension == "jpg" }.orEmpty()

    fun deleteAll() {
        dir.deleteRecursively()
    }

    companion object {
        private const val JPEG_QUALITY = 85
        private const val HI_RES_QUALITY = 80

        fun default(context: Context) = ThumbnailStore(File(context.filesDir, "thumbnails"))
    }
}
