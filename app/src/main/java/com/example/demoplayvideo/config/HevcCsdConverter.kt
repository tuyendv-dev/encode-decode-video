package com.example.demoplayvideo.config

import android.util.Log
import java.nio.ByteBuffer

/**
 * Convert Annex B format to H.265 hvcC (HEVC Configuration)
 */
object HevcCsdConverter {
    private const val TAG = "HevcCsdConverter"

    fun hevcCsdToAnnexB(hvcC: ByteArray): ByteArray {
        val result = mutableListOf<Byte>()
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)

        if (hvcC.size < 23) return byteArrayOf()

        var offset = 22
        val numArrays = hvcC[offset++].toInt() and 0xFF

        for (i in 0 until numArrays) {
            if (offset + 3 > hvcC.size) break

            offset++ // Skip array info
            val numNalus = ((hvcC[offset].toInt() and 0xFF) shl 8) or
                    (hvcC[offset + 1].toInt() and 0xFF)
            offset += 2

            for (j in 0 until numNalus) {
                if (offset + 2 > hvcC.size) break

                val nalLength = ((hvcC[offset].toInt() and 0xFF) shl 8) or
                        (hvcC[offset + 1].toInt() and 0xFF)
                offset += 2

                if (offset + nalLength > hvcC.size) break

                result.addAll(START_CODE.toList())
                for (k in 0 until nalLength) {
                    result.add(hvcC[offset++])
                }
            }
        }

        return result.toByteArray()
    }

    /**
     * Convert Annex B to hvcC (HEVC Configuration Record)
     *
     * @param annexB ByteArray from MediaFormat getByteBuffer("csd-0")
     * @return ByteArray hvcC format ready for transmission
     */
    fun convertAnnexBToHvcCsd(annexB: ByteArray): ByteArray {
        Log.d(TAG, "Converting Annex B (${annexB.size} bytes) to hvcC")

        try {
            // Step 1: Extract NAL units
            val nalUnits = extractNalUnitsFromAnnexB(annexB)

            if (nalUnits.isEmpty()) {
                Log.e(TAG, "No NAL units found in Annex B data")
                return byteArrayOf()
            }

            Log.d(TAG, "Found ${nalUnits.size} NAL units")

            // Step 2: Separate by type
            var vps: ByteArray? = null
            var sps: ByteArray? = null
            var pps: ByteArray? = null

            for (nalUnit in nalUnits) {
                val nalType = getNalUnitType(nalUnit)
                when (nalType) {
                    32 -> {
                        vps = nalUnit
                        Log.d(TAG, "VPS: ${nalUnit.size} bytes")
                    }
                    33 -> {
                        sps = nalUnit
                        Log.d(TAG, "SPS: ${nalUnit.size} bytes")
                    }
                    34 -> {
                        pps = nalUnit
                        Log.d(TAG, "PPS: ${nalUnit.size} bytes")
                    }
                }
            }

            // Step 3: Parse parameters from SPS
            val params = if (sps != null) {
                parseSpsParameters(sps)
            } else {
                Log.w(TAG, "No SPS found, using defaults")
                HvcCParameters()
            }

            Log.d(TAG, "Parsed parameters: profile=${params.profileIdc}, level=${params.levelIdc}")

            // Step 4: Build hvcC
            val hvcC = buildHvcC(vps, sps, pps, params)

            Log.d(TAG, "✓ hvcC built: ${hvcC.size} bytes")

            return hvcC

        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert Annex B to hvcC", e)
            return byteArrayOf()
        }
    }

    /**
     * Extract NAL units from Annex B stream
     */
    private fun extractNalUnitsFromAnnexB(data: ByteArray): List<ByteArray> {
        val nalUnits = mutableListOf<ByteArray>()
        var i = 0

        while (i < data.size) {
            // Find start code
            val startCodeSize = when {
                i + 3 < data.size &&
                        data[i] == 0.toByte() &&
                        data[i + 1] == 0.toByte() &&
                        data[i + 2] == 0.toByte() &&
                        data[i + 3] == 1.toByte() -> 4

                i + 2 < data.size &&
                        data[i] == 0.toByte() &&
                        data[i + 1] == 0.toByte() &&
                        data[i + 2] == 1.toByte() -> 3

                else -> {
                    i++
                    continue
                }
            }

            val nalStart = i + startCodeSize

            // Find next start code or end of data
            var nalEnd = nalStart
            while (nalEnd < data.size) {
                if (nalEnd + 3 < data.size &&
                    data[nalEnd] == 0.toByte() &&
                    data[nalEnd + 1] == 0.toByte() &&
                    data[nalEnd + 2] == 0.toByte() &&
                    data[nalEnd + 3] == 1.toByte()) {
                    break
                }
                if (nalEnd + 2 < data.size &&
                    data[nalEnd] == 0.toByte() &&
                    data[nalEnd + 1] == 0.toByte() &&
                    data[nalEnd + 2] == 1.toByte()) {
                    break
                }
                nalEnd++
            }

            // Extract NAL unit (without start code)
            if (nalEnd > nalStart) {
                val nalUnit = data.copyOfRange(nalStart, nalEnd)
                nalUnits.add(nalUnit)

                val nalType = getNalUnitType(nalUnit)
                Log.d(TAG, "Extracted NAL type=$nalType, size=${nalUnit.size}")
            }

            i = nalEnd
        }

        return nalUnits
    }

    /**
     * Get NAL unit type from NAL unit data
     */
    private fun getNalUnitType(nalUnit: ByteArray): Int {
        if (nalUnit.size < 2) return -1

        // H.265 NAL header is 2 bytes
        // Bits 9-15 of the 16-bit header contain the NAL unit type
        val nalHeader = ((nalUnit[0].toInt() and 0xFF) shl 8) or (nalUnit[1].toInt() and 0xFF)
        return (nalHeader shr 9) and 0x3F
    }

    /**
     * Parse SPS to extract critical parameters
     */
    private fun parseSpsParameters(sps: ByteArray): HvcCParameters {
        val params = HvcCParameters()

        try {
            if (sps.size < 15) {
                Log.w(TAG, "SPS too short for parsing")
                return params
            }

            // Skip NAL header (2 bytes)
            var offset = 2

            // Parse using bit reader
            val reader = BitReader(sps, offset)

            // sps_video_parameter_set_id (4 bits)
            reader.readBits(4)

            // sps_max_sub_layers_minus1 (3 bits)
            val maxSubLayers = reader.readBits(3) + 1

            // sps_temporal_id_nesting_flag (1 bit)
            val temporalIdNested = reader.readBits(1) == 1
            params.temporalIdNested = temporalIdNested

            // profile_tier_level()
            parseProfileTierLevel(reader, maxSubLayers, params)

            // sps_seq_parameter_set_id (ue(v))
            reader.readUEV()

            // chroma_format_idc (ue(v))
            val chromaFormat = reader.readUEV()
            params.chromaFormat = chromaFormat

            if (chromaFormat == 3) {
                // separate_colour_plane_flag (1 bit)
                reader.readBits(1)
            }

            // pic_width_in_luma_samples (ue(v))
            val picWidth = reader.readUEV()

            // pic_height_in_luma_samples (ue(v))
            val picHeight = reader.readUEV()

            // conformance_window_flag (1 bit)
            val confWinFlag = reader.readBits(1)

            if (confWinFlag == 1) {
                val confWinLeftOffset = reader.readUEV()
                val confWinRightOffset = reader.readUEV()
                val confWinTopOffset = reader.readUEV()
                val confWinBottomOffset = reader.readUEV()

                // Calculate actual dimensions
                val subWidthC = if (chromaFormat == 1 || chromaFormat == 2) 2 else 1
                val subHeightC = if (chromaFormat == 1) 2 else 1

                params.width = picWidth - (confWinLeftOffset + confWinRightOffset) * subWidthC
                params.height = picHeight - (confWinTopOffset + confWinBottomOffset) * subHeightC
            } else {
                params.width = picWidth
                params.height = picHeight
            }

            // bit_depth_luma_minus8 (ue(v))
            params.bitDepthLuma = reader.readUEV()

            // bit_depth_chroma_minus8 (ue(v))
            params.bitDepthChroma = reader.readUEV()

            Log.d(
                TAG, "Parsed SPS: ${params.width}x${params.height}, " +
                    "profile=${params.profileIdc}, level=${params.levelIdc}, " +
                    "chroma=${params.chromaFormat}, bitDepth=${params.bitDepthLuma + 8}")

        } catch (e: Exception) {
            Log.w(TAG, "SPS parsing error, using defaults", e)
        }

        return params
    }

    /**
     * Parse profile_tier_level structure
     */
    private fun parseProfileTierLevel(
        reader: BitReader,
        maxSubLayers: Int,
        params: HvcCParameters
    ) {
        // general_profile_space (2 bits)
        params.profileSpace = reader.readBits(2)

        // general_tier_flag (1 bit)
        params.tierFlag = reader.readBits(1) == 1

        // general_profile_idc (5 bits)
        params.profileIdc = reader.readBits(5)

        // general_profile_compatibility_flag (32 bits)
        params.profileCompatibility = reader.readBits(32)

        // general_constraint_indicator_flags (48 bits)
        val constraint1 = reader.readBits(32)
        val constraint2 = reader.readBits(16)
        params.constraintIndicator = ByteArray(6)
        params.constraintIndicator[0] = ((constraint1 shr 24) and 0xFF).toByte()
        params.constraintIndicator[1] = ((constraint1 shr 16) and 0xFF).toByte()
        params.constraintIndicator[2] = ((constraint1 shr 8) and 0xFF).toByte()
        params.constraintIndicator[3] = (constraint1 and 0xFF).toByte()
        params.constraintIndicator[4] = ((constraint2 shr 8) and 0xFF).toByte()
        params.constraintIndicator[5] = (constraint2 and 0xFF).toByte()

        // general_level_idc (8 bits)
        params.levelIdc = reader.readBits(8)

        // Skip sub_layer info for simplicity
        val subLayerProfilePresent = BooleanArray(maxSubLayers - 1)
        val subLayerLevelPresent = BooleanArray(maxSubLayers - 1)

        for (i in 0 until maxSubLayers - 1) {
            subLayerProfilePresent[i] = reader.readBits(1) == 1
            subLayerLevelPresent[i] = reader.readBits(1) == 1
        }

        if (maxSubLayers > 1) {
            for (i in maxSubLayers - 1 until 8) {
                reader.readBits(2) // reserved_zero_2bits
            }
        }

        for (i in 0 until maxSubLayers - 1) {
            if (subLayerProfilePresent[i]) {
                reader.readBits(88) // sub_layer_profile info
            }
            if (subLayerLevelPresent[i]) {
                reader.readBits(8) // sub_layer_level_idc
            }
        }
    }

    /**
     * Build hvcC structure
     */
    private fun buildHvcC(
        vps: ByteArray?,
        sps: ByteArray?,
        pps: ByteArray?,
        params: HvcCParameters
    ): ByteArray {
        val buffer = ByteBuffer.allocate(2048)

        // [0] configurationVersion (1 byte)
        buffer.put(1.toByte())

        // [1] Profile, tier, level (1 byte)
        val byte1 = (params.profileSpace shl 6) or
                (if (params.tierFlag) 0x20 else 0) or
                (params.profileIdc and 0x1F)
        buffer.put(byte1.toByte())

        // [2-5] Profile compatibility flags (4 bytes)
        buffer.putInt(params.profileCompatibility)

        // [6-11] Constraint indicator flags (6 bytes)
        buffer.put(params.constraintIndicator)

        // [12] Level (1 byte)
        buffer.put(params.levelIdc.toByte())

        // [13-14] Min spatial segmentation (2 bytes)
        // reserved (4 bits) = 1111b + min_spatial_segmentation_idc (12 bits) = 0
        buffer.put(0xF0.toByte())
        buffer.put(0x00.toByte())

        // [15] Parallelism type (1 byte)
        // reserved (6 bits) = 111111b + parallelismType (2 bits) = 0
        buffer.put(0xFC.toByte())

        // [16] Chroma format (1 byte)
        // reserved (6 bits) = 111111b + chromaFormat (2 bits)
        buffer.put((0xFC or (params.chromaFormat and 0x03)).toByte())

        // [17] Bit depth luma (1 byte)
        // reserved (5 bits) = 11111b + bitDepthLumaMinus8 (3 bits)
        buffer.put((0xF8 or (params.bitDepthLuma and 0x07)).toByte())

        // [18] Bit depth chroma (1 byte)
        // reserved (5 bits) = 11111b + bitDepthChromaMinus8 (3 bits)
        buffer.put((0xF8 or (params.bitDepthChroma and 0x07)).toByte())

        // [19-20] Avg frame rate (2 bytes) - 0 = unspecified
        buffer.putShort(0)

        // [21] Frame rate and other info (1 byte)
        // constantFrameRate (2 bits) = 0
        // numTemporalLayers (3 bits) = 1
        // temporalIdNested (1 bit)
        // lengthSizeMinusOne (2 bits) = 3 (means 4 bytes)
        val byte21 = (0 shl 6) or
                (1 shl 3) or
                (if (params.temporalIdNested) 0x04 else 0) or
                3
        buffer.put(byte21.toByte())

        // [22] Number of arrays
        val arrays = mutableListOf<Pair<Int, ByteArray>>()
        vps?.let { arrays.add(32 to it) }
        sps?.let { arrays.add(33 to it) }
        pps?.let { arrays.add(34 to it) }

        buffer.put(arrays.size.toByte())

        // Write NAL arrays
        for ((nalType, nalData) in arrays) {
            // array_completeness (1 bit) = 1
            // reserved (1 bit) = 0
            // NAL_unit_type (6 bits)
            val arrayByte = (0x80 or (nalType and 0x3F))
            buffer.put(arrayByte.toByte())

            // numNalus (2 bytes)
            buffer.putShort(1)

            // nalUnitLength (2 bytes)
            buffer.putShort(nalData.size.toShort())

            // nalUnit
            buffer.put(nalData)

            Log.d(TAG, "Added array: type=$nalType, size=${nalData.size}")
        }

        // Extract result
        val hvcC = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(hvcC)

        return hvcC
    }

    /**
     * Parameters extracted from SPS
     */
    data class HvcCParameters(
        var profileSpace: Int = 0,
        var tierFlag: Boolean = false,
        var profileIdc: Int = 1,
        var profileCompatibility: Int = 0xFFFFFFFF.toInt(),
        var constraintIndicator: ByteArray = ByteArray(6),
        var levelIdc: Int = 93,
        var chromaFormat: Int = 1,
        var bitDepthLuma: Int = 0,
        var bitDepthChroma: Int = 0,
        var temporalIdNested: Boolean = true,
        var width: Int = 0,
        var height: Int = 0
    )

    /**
     * Bit reader for parsing SPS
     */
    private class BitReader(private val data: ByteArray, offset: Int = 0) {
        private var bytePos = offset
        private var bitPos = 0

        fun readBits(n: Int): Int {
            var result = 0
            for (i in 0 until n) {
                if (bytePos >= data.size) break

                val bit = (data[bytePos].toInt() shr (7 - bitPos)) and 1
                result = (result shl 1) or bit

                bitPos++
                if (bitPos == 8) {
                    bitPos = 0
                    bytePos++
                }
            }
            return result
        }

        fun readUEV(): Int {
            var leadingZeros = 0
            while (readBits(1) == 0) {
                leadingZeros++
                if (leadingZeros > 31) return 0
            }

            if (leadingZeros == 0) return 0

            val value = readBits(leadingZeros)
            return (1 shl leadingZeros) - 1 + value
        }
    }
}