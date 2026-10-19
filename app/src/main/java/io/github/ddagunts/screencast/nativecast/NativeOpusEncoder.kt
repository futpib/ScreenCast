package io.github.ddagunts.screencast.nativecast

internal object NativeOpusEncoder {
    init { System.loadLibrary("screencast_opus") }

    external fun create(): Long
    external fun encode(handle: Long, pcm: ByteArray): ByteArray?
    external fun destroy(handle: Long)
}
