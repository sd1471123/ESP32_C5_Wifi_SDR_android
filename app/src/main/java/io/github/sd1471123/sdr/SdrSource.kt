package io.github.sd1471123.sdr

/**
 * Everything the UI needs from a capture back-end.
 *
 * [SdrEngine] is the real thing: it talks to an ESP32-C5 over USB. [DemoEngine] produces the same
 * [SdrFrame] stream from synthesised data, so the whole UI — spectrum, waterfall, sweep stitching,
 * channel activity, I/Q recording — can be exercised without a board.
 */
interface SdrSource {

    var mode: Mode
    var band: String
    var centerMhz: Int
    var gain: Int
    var rateIdx: Int
    var nfft: Int
    var bandwidthMhz: Int
    var loOffsetMhz: Int

    /** Wire format: 8 = CAP16 signed bytes, 10 = CAP20 packed 10-bit (demo engine ignores it). */
    var iqBits: Int

    /** Remove the per-frame windowed DC before the FFT; off leaves the LO leak at bin 0. */
    var dcRemove: Boolean

    /** Set to a recorder to stream every capture to disk; null stops recording. */
    var recorder: IqRecorder?

    fun startEngine()
    fun stopEngine()
}
