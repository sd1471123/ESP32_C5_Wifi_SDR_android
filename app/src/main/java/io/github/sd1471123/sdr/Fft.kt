package io.github.sd1471123.sdr

/** Iterative radix-2 FFT (in place). [re]/[im] must have a power-of-two length. */
object Fft {

    fun transform(re: FloatArray, im: FloatArray) {
        val n = re.size
        if (n <= 1) return

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = Math.cos(ang).toFloat()
            val wIm = Math.sin(ang).toFloat()
            val half = len / 2
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val xRe = re[b] * curRe - im[b] * curIm
                    val xIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - xRe
                    im[b] = im[a] - xIm
                    re[a] += xRe
                    im[a] += xIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
