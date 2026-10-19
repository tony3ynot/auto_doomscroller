package com.autodoomscroller.data

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/** Downscaled frames kept only in research mode, named `<reelId>_<frameIndex>.jpg`. */
class ThumbnailStore(private val dir: File) {

    fun save(reelId: Long, frameIndex: Int, bitmap: Bitmap) {
        dir.mkdirs()
        File(dir, "${reelId}_$frameIndex.jpg").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
        }
    }

    fun all(): List<File> = dir.listFiles()?.filter { it.extension == "jpg" }.orEmpty()

    fun deleteAll() {
        dir.deleteRecursively()
    }

    companion object {
        private const val JPEG_QUALITY = 85

        fun default(context: Context) = ThumbnailStore(File(context.filesDir, "thumbnails"))
    }
}
