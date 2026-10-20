package io.github.ddagunts.screencast.nativecast

import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ddagunts.screencast.media.VideoEncoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CastVideoEncoderDeviceTest {
    @Test fun availableVp8AndVp9EncodersProduceDecodableFrames() {
        var tested = 0
        for (codec in listOf(CastStreamingProtocol.VideoCodec.VP8,
                CastStreamingProtocol.VideoCodec.VP9)) {
            val name = VideoEncoder.encoderFor(codec.mimeType, 640, 360, 30)
                ?: surfaceEncoderFor(codec.mimeType) ?: continue
            val encoded = ArrayBlockingQueue<ByteArray>(8)
            val encoder = VideoEncoder(640, 360, 30, 1_000_000,
                keyframeIntervalSec = 1, codecName = name, mimeType = codec.mimeType)
            try {
                encoder.start(object : VideoEncoder.Sink {
                    override fun onFormat(sps: ByteArray, pps: ByteArray) = Unit

                    override fun onSample(nalUnits: List<ByteArray>, ptsUs: Long,
                                          isKeyFrame: Boolean) {
                        if (isKeyFrame) encoded.offer(CastStreamingProtocol.videoFrameData(
                            codec, nalUnits, null, null, true))
                    }
                })
                repeat(8) { frame ->
                    val canvas = encoder.inputSurface.lockCanvas(null)
                    canvas.drawColor(if (frame % 2 == 0) Color.RED else Color.BLUE)
                    encoder.inputSurface.unlockCanvasAndPost(canvas)
                    Thread.sleep(40)
                }
                val keyFrame = encoded.poll(5, TimeUnit.SECONDS)
                assertTrue("${codec.wireName} produced no key frame", keyFrame != null)
                assertTrue("${codec.wireName} produced an empty frame", keyFrame!!.isNotEmpty())
                decode(codec.mimeType, keyFrame)
                tested++
            } finally {
                encoder.stop()
            }
        }
        assumeTrue("Device has no VP8 or VP9 surface encoder", tested > 0)
    }

    private fun surfaceEncoderFor(mimeType: String): String? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } &&
                runCatching {
                    val capabilities = info.getCapabilitiesForType(mimeType)
                    capabilities.colorFormats.contains(
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) &&
                        capabilities.videoCapabilities?.areSizeAndRateSupported(640, 360, 30.0) == true
                }.getOrDefault(false)
        }?.name

    private fun decode(mimeType: String, encoded: ByteArray) {
        val decoder = MediaCodec.createDecoderByType(mimeType)
        try {
            decoder.configure(MediaFormat.createVideoFormat(mimeType, 640, 360), null, null, 0)
            decoder.start()
            val inputIndex = decoder.dequeueInputBuffer(2_000_000)
            assertTrue("$mimeType decoder has no input buffer", inputIndex >= 0)
            decoder.getInputBuffer(inputIndex)!!.put(encoded)
            decoder.queueInputBuffer(inputIndex, 0, encoded.size, 0, 0)
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 5_000
            var decoded = false
            while (!decoded && SystemClock.elapsedRealtime() < deadline) {
                val index = decoder.dequeueOutputBuffer(info, 100_000)
                if (index >= 0) {
                    decoded = info.size > 0
                    decoder.releaseOutputBuffer(index, false)
                }
            }
            assertTrue("$mimeType key frame did not decode", decoded)
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
        }
    }
}
