package io.github.ddagunts.screencast.mediapush

internal data class MediaByteRange(val first: Long, val last: Long) {
    val length: Long get() = last - first + 1

    companion object {
        fun parse(header: String?, size: Long): MediaByteRange? {
            require(size >= 0)
            if (header == null) return if (size == 0L) null else MediaByteRange(0, size - 1)
            if (!header.startsWith("bytes=") || ',' in header || size == 0L) return null
            val spec = header.removePrefix("bytes=")
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val left = spec.substring(0, dash)
            val right = spec.substring(dash + 1)
            if (left.isEmpty()) {
                val suffix = right.toLongOrNull() ?: return null
                if (suffix <= 0) return null
                return MediaByteRange((size - suffix).coerceAtLeast(0), size - 1)
            }
            val first = left.toLongOrNull() ?: return null
            val last = if (right.isEmpty()) size - 1 else right.toLongOrNull() ?: return null
            if (first < 0 || first >= size || last < first) return null
            return MediaByteRange(first, minOf(last, size - 1))
        }
    }
}
