package com.voicemonitor.app

/**
 * Single-producer / single-consumer ring buffer of 16-bit samples.
 *
 * The whole point of this class is drift control. The microphone's sample clock and
 * the earbuds' playback clock are different crystals and they disagree by a fraction
 * of a percent. Left alone, that error accumulates: after half an hour the audible
 * delay has grown noticeably and the stream starts to pop. Callers keep the fill
 * level pinned to a fixed target so the error never gets a chance to build up.
 */
class RingBuffer(private val capacityFrames: Int) {

    private val buf = ShortArray(capacityFrames)
    private var w = 0
    private var r = 0
    private var count = 0

    @Synchronized
    fun write(src: ShortArray, n: Int) {
        var remaining = n
        var offset = 0
        while (remaining > 0) {
            if (count == capacityFrames) {
                // Buffer full and nobody is draining fast enough. Drop the oldest
                // samples rather than block, so the mic thread never stalls.
                r = (r + 1) % capacityFrames
                count--
            }
            val chunk = minOf(remaining, capacityFrames - w)
            System.arraycopy(src, offset, buf, w, chunk)
            w = (w + chunk) % capacityFrames
            count += chunk
            offset += chunk
            remaining -= chunk
        }
    }

    /** Read up to [max] frames into [dst]. Returns frames actually copied. */
    @Synchronized
    fun read(dst: ShortArray, max: Int): Int {
        val n = minOf(max, count)
        var written = 0
        var consumed = 0
        while (consumed < n) {
            val chunk = minOf(n - consumed, capacityFrames - r)
            System.arraycopy(buf, r, dst, written, chunk)
            written += chunk
            consumed += chunk
            r = (r + chunk) % capacityFrames
        }
        count -= n
        return n
    }

    /** Throw away [n] frames from the oldest end. Used to pull latency back down. */
    @Synchronized
    fun skip(n: Int) {
        val drop = minOf(n, count)
        r = (r + drop) % capacityFrames
        count -= drop
    }

    @Synchronized
    fun available(): Int = count

    @Synchronized
    fun clear() {
        w = 0; r = 0; count = 0
    }
}