package ru.kojexy.chinesetv

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore

data class VideoFolder(val name: String, val videos: List<Episode>)

/**
 * Video folders on the device and on USB drives, read from MediaStore. Android TV often has no file picker,
 * but MediaStore is everywhere, and it already knows every file's duration.
 */
object MediaLibrary {

    fun permission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun folders(context: Context): List<VideoFolder> {
        val uri = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                  else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
        )
        val byFolder = LinkedHashMap<String, Pair<String, MutableList<Pair<String, Episode>>>>()
        context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val bucket = c.getString(2) ?: "?"
                val folder = c.getString(3) ?: "Videos"
                val item = ContentUris.withAppendedId(uri, c.getLong(0))
                val episode = Episode(name.substringBeforeLast('.'), item.toString(), c.getLong(4))
                byFolder.getOrPut(bucket) { folder to mutableListOf() }.second += name to episode
            }
        }
        return byFolder.values
            .map { (folder, items) -> VideoFolder(folder, items.sortedWith(compareBy(NaturalOrder) { it.first }).map { it.second }) }
            .sortedWith(compareBy(NaturalOrder) { it.name })
    }
}
