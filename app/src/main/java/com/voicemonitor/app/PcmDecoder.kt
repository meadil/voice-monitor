package com.voicemonitor.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Decodes a user-supplied audio file into plain 16-bit mono PCM held in memory.
 *
 * We decode it ourselves rather than handing the file to a media player on purpose:
 * the model audio then gets mixed into the *same* output stream as the microphone, so
 * the two can never drift apart and Android never has to arbitrate between two
 * competing audio routes.
 */
object PcmDecoder {

    fun decode(context: Context, uri: Uri): Decoded? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            extractor.selectTrack(trackIndex)
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val out = ArrayList<Short>(1 shl 18)
            val info = MediaCodec.BufferInfo()
            var done = false
            var buffer = ByteBuffer.allocate(1 shl 18).order(ByteOrder.LITTLE_ENDIAN)

            while (!done) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(inBuf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(
                            inIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    MediaCodec.INFO_TRY_AGAIN_LATER -> if (inIndex < 0 && extractor.sampleTime < 0) done = true
                    else -> {
                        if (outIndex >= 0) {
                            val outBuf = codec.getOutputBuffer(outIndex)!!
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val shorts = outBuf.asShortBuffer()
                            // Downmix to mono; for speech this is indistinguishable
                            // from stereo and halves the memory we hold.
                            if (channels >= 2) {
                                while (shorts.remaining() >= 2) {
                                    val l = shorts.get().toInt()
                                    val r = shorts.get().toInt()
                                    out.add(((l + r) / 2).toShort())
                                }
                            } else {
                                while (shorts.remaining() > 0) out.add(shorts.get())
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                        }
                    }
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            if (out.isEmpty()) null else Decoded(out.toShortArray(), srcRate)
        } catch (t: Throwable) {
            runCatching { extractor.release() }
            null
        }
    }

    data class Decoded(val pcm: ShortArray, val sampleRate: Int) {
        val durationSec: Float get() = pcm.size.toFloat() / sampleRate
    }
}