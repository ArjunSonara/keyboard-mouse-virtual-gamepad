package com.virtualpad.app

import android.util.Log

/**
 * Minimal H.264 SPS parser for extracting video dimensions dynamically without native dependencies.
 */
object SpsParser {
    fun parse(sps: ByteArray): Pair<Int, Int>? {
        return try {
            var offset = 0
            while (offset + 3 < sps.size) {
                if (sps[offset] == 0.toByte() && sps[offset + 1] == 0.toByte()) {
                    if (sps[offset + 2] == 1.toByte()) { offset += 3; break }
                    if (offset + 3 < sps.size && sps[offset + 2] == 0.toByte() && sps[offset + 3] == 1.toByte()) { offset += 4; break }
                }
                offset++
            }
            offset++

            val rbsp = ArrayList<Byte>()
            var i = offset
            while (i < sps.size) {
                if (i + 2 < sps.size && sps[i] == 0.toByte() && sps[i + 1] == 0.toByte() && sps[i + 2] == 3.toByte()) {
                    rbsp.add(0.toByte()); rbsp.add(0.toByte())
                    i += 3
                } else {
                    rbsp.add(sps[i])
                    i++
                }
            }
            val br = BitReader(rbsp.toByteArray())
            val profileIdc = br.readBits(8)
            br.readBits(8)
            br.readBits(8)
            br.readUe()

            if (profileIdc in listOf(100, 110, 122, 244, 44, 83, 86, 118, 128)) {
                val chromaFormatIdc = br.readUe()
                if (chromaFormatIdc == 3) br.readBits(1)
                br.readUe()
                br.readUe()
                br.readBits(1)
                val seqScaling = br.readBits(1)
                if (seqScaling == 1) {
                    val count = if (chromaFormatIdc != 3) 8 else 12
                    for (j in 0 until count) {
                        if (br.readBits(1) == 1) {
                            val size = if (j < 6) 16 else 64
                            var lastScale = 8; var nextScale = 8
                            for (k in 0 until size) {
                                if (nextScale != 0) nextScale = (lastScale + br.readSe() + 256) % 256
                                lastScale = if (nextScale == 0) lastScale else nextScale
                            }
                        }
                    }
                }
            }
            br.readUe()
            val picOrderCntType = br.readUe()
            if (picOrderCntType == 0) {
                br.readUe()
            } else if (picOrderCntType == 1) {
                br.readBits(1); br.readSe(); br.readSe()
                val numRef = br.readUe()
                for (j in 0 until numRef) br.readSe()
            }
            br.readUe()
            br.readBits(1)
            val picWidthInMbsMinus1 = br.readUe()
            val picHeightInMapUnitsMinus1 = br.readUe()
            val frameMbsOnlyFlag = br.readBits(1)
            if (frameMbsOnlyFlag == 0) br.readBits(1)
            br.readBits(1)
            val frameCropping = br.readBits(1)
            var cropL = 0; var cropR = 0; var cropT = 0; var cropB = 0
            if (frameCropping == 1) {
                cropL = br.readUe(); cropR = br.readUe(); cropT = br.readUe(); cropB = br.readUe()
            }
            val width = (picWidthInMbsMinus1 + 1) * 16 - (cropL + cropR) * 2
            val height = ((2 - frameMbsOnlyFlag) * (picHeightInMapUnitsMinus1 + 1) * 16) - (cropT + cropB) * 2
            Pair(width, height)
        } catch (e: Exception) {
            Log.w("VirtualPad", "Could not parse SPS resolution: ${e.message}")
            null
        }
    }

    private class BitReader(private val bytes: ByteArray) {
        private var byteOffset = 0
        private var bitOffset = 0

        fun readBits(n: Int): Int {
            var res = 0
            for (i in 0 until n) {
                if (byteOffset >= bytes.size) return res
                val bit = (bytes[byteOffset].toInt() ushr (7 - bitOffset)) and 1
                res = (res shl 1) or bit
                bitOffset++
                if (bitOffset == 8) { bitOffset = 0; byteOffset++ }
            }
            return res
        }

        fun readUe(): Int {
            var zeros = 0
            while (readBits(1) == 0 && zeros < 31) zeros++
            return if (zeros == 0) 0 else (1 shl zeros) - 1 + readBits(zeros)
        }

        fun readSe(): Int {
            val v = readUe()
            val sign = ((v and 1) shl 1) - 1
            return ((v + 1) shr 1) * sign
        }
    }
}
