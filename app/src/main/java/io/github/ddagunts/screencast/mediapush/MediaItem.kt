package io.github.ddagunts.screencast.mediapush

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.net.URI

internal data class MediaItem(
    val uri: String,
    val title: String,
    val mimeType: String,
    val local: Boolean,
    val size: Long = -1,
) {
    val streamType: String get() = when {
        mimeType.startsWith("image/") -> "NONE"
        mimeType.equals("application/x-mpegURL", true) ||
            mimeType.equals("application/vnd.apple.mpegurl", true) -> "LIVE"
        else -> "BUFFERED"
    }

    companion object {
        fun fromUrl(raw: String, suppliedMimeType: String = ""): MediaItem {
            val url = URI(raw.trim())
            require(url.scheme.equals("http", true) || url.scheme.equals("https", true)) {
                "Use a direct HTTP or HTTPS media URL"
            }
            require(!url.host.isNullOrBlank()) { "Media URL has no host" }
            val title = url.path.substringAfterLast('/').ifBlank { url.host }
            return MediaItem(url.toString(), title,
                suppliedMimeType.trim().ifBlank { mimeForName(title) }, false)
        }

        fun fromDocument(resolver: ContentResolver, uri: Uri): MediaItem {
            var title = "Selected media"
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) title = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
            if (size < 0) resolver.openAssetFileDescriptor(uri, "r")?.use { size = it.length }
            require(size > 0) { "Selected file is empty or has no known size; choose a local file" }
            val mime = resolver.getType(uri)?.takeUnless { it == "application/octet-stream" }
                ?: mimeForName(title)
            return MediaItem(uri.toString(), title, mime, true, size)
        }

        fun mimeForName(name: String): String = when (name.substringBefore('?')
            .substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "m3u8" -> "application/x-mpegURL"
            "mpd" -> "application/dash+xml"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }
}
