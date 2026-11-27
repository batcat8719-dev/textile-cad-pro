package com.textilecad.pro

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream

data class SpotChannelData(
    val name: String,
    val colorInt: Int,
    val solidity: Int = 100,
    /**
     * Photoshop Spot Channel pixel convention:
     * 0 (0x00) = 100% Black Ink (Screen Open)
     * 255 (0xFF) = White / No Ink (Screen Blocked)
     */
    val inkMask: ByteArray
)

/**
 * Pure Kotlin Adobe Photoshop (.PSD) v1 Binary Encoder.
 * Generates 100% standard RGB + Background Layer + Named Spot/Alpha Channels at 300 DPI
 * with PackBits RLE compression — displays all extra channels in both Photopea & Photoshop CS3+.
 */
object PsdWriter {

    fun writeSpotChannelPsd(
        out: OutputStream,
        compositeRgb: Bitmap,
        spotChannels: List<SpotChannelData>,
        dpi: Int = 300
    ) {
        val w = compositeRgb.width
        val h = compositeRgb.height
        val totalChannels = 3 + spotChannels.size
        val dos = DataOutputStream(out)

        // Extract RGB planes once (used for both Background Layer & Merged Image Data)
        val pixels = IntArray(w * h)
        compositeRgb.getPixels(pixels, 0, w, 0, 0, w, h)

        val chR = ByteArray(w * h)
        val chG = ByteArray(w * h)
        val chB = ByteArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            chR[i] = ((p shr 16) and 0xFF).toByte()
            chG[i] = ((p shr 8) and 0xFF).toByte()
            chB[i] = (p and 0xFF).toByte()
        }

        // Pre-compress R, G, B planes using PackBits RLE
        val compR = compressPlaneRle(chR, w, h)
        val compG = compressPlaneRle(chG, w, h)
        val compB = compressPlaneRle(chB, w, h)

        // ==================== 1. FILE HEADER (26 bytes) ====================
        dos.writeBytes("8BPS")
        dos.writeShort(1)                 // Version 1 (Standard PSD)
        dos.write(ByteArray(6))           // Reserved 6 zero bytes
        dos.writeShort(totalChannels)     // 3 RGB + N Spot Channels
        dos.writeInt(h)                   // Height
        dos.writeInt(w)                   // Width
        dos.writeShort(8)                 // 8 bits per channel
        dos.writeShort(3)                 // Color Mode: 3 = RGB

        // ==================== 2. COLOR MODE DATA (4 bytes) ====================
        dos.writeInt(0)

        // ==================== 3. IMAGE RESOURCES ====================
        val resBytes = buildImageResources(spotChannels, dpi)
        dos.writeInt(resBytes.size)
        dos.write(resBytes)

        // ==================== 4. LAYER & MASK INFO ====================
        // Explicit positive layerCount (+1 "Background" layer) guarantees Photopea & Photoshop
        // treat channels 4..N as Extra Spot/Alpha Channels instead of merged transparency!
        val layerMaskBytes = buildBackgroundLayerSection(w, h, compR, compG, compB)
        dos.writeInt(layerMaskBytes.size)
        dos.write(layerMaskBytes)

        // ==================== 5. MERGED IMAGE DATA (PackBits RLE = 1) ====================
        dos.writeShort(1)

        val allCompressed = ArrayList<CompressedPlane>(totalChannels)
        allCompressed.add(compR)
        allCompressed.add(compG)
        allCompressed.add(compB)
        for (sc in spotChannels) {
            allCompressed.add(compressPlaneRle(sc.inkMask, w, h))
        }

        // Write scanline byte-count table for all channels
        for (plane in allCompressed) {
            for (len in plane.rowLengths) {
                dos.writeShort(len and 0xFFFF)
            }
        }
        // Write compressed scanline data for all channels
        for (plane in allCompressed) {
            dos.write(plane.data)
        }
        dos.flush()
    }

    private class CompressedPlane(
        val rowLengths: IntArray,
        val data: ByteArray
    ) {
        // Layer channel data size = 2 (compression flag) + h*2 (row lengths) + compressed bytes
        val layerChannelByteSize: Int
            get() = 2 + rowLengths.size * 2 + data.size
    }

    private fun compressPlaneRle(plane: ByteArray, w: Int, h: Int): CompressedPlane {
        val rowLengths = IntArray(h)
        val body = ByteArrayOutputStream(w * h / 2)
        val rowBuf = ByteArray(w)
        val scratch = ByteArray(w + (w / 128) + 8)

        for (y in 0 until h) {
            System.arraycopy(plane, y * w, rowBuf, 0, w)
            val compLen = packBitsRow(rowBuf, w, scratch)
            rowLengths[y] = compLen
            body.write(scratch, 0, compLen)
        }
        return CompressedPlane(rowLengths, body.toByteArray())
    }

    private fun buildBackgroundLayerSection(
        w: Int,
        h: Int,
        compR: CompressedPlane,
        compG: CompressedPlane,
        compB: CompressedPlane
    ): ByteArray {
        val layerInfoBaos = ByteArrayOutputStream()
        val lDos = DataOutputStream(layerInfoBaos)

        // Positive layer count (+1) = no merged transparency in ImageData
        lDos.writeShort(1)

        // Layer Record: "Background"
        lDos.writeInt(0) // top
        lDos.writeInt(0) // left
        lDos.writeInt(h) // bottom
        lDos.writeInt(w) // right
        lDos.writeShort(3) // 3 channels (R, G, B)

        // Channel ID + Length pairs
        lDos.writeShort(0); lDos.writeInt(compR.layerChannelByteSize)
        lDos.writeShort(1); lDos.writeInt(compG.layerChannelByteSize)
        lDos.writeShort(2); lDos.writeInt(compB.layerChannelByteSize)

        lDos.writeBytes("8BIM")
        lDos.writeBytes("norm") // Normal blend mode
        lDos.writeByte(255)     // Opacity 100%
        lDos.writeByte(0)       // Clipping: base
        lDos.writeByte(0)       // Flags: visible
        lDos.writeByte(0)       // Filler

        // Extra Data: Layer Mask (4 bytes) + Blending Ranges (4 bytes) + Pascal Name "Background" (12 bytes) = 20 bytes
        lDos.writeInt(20)
        lDos.writeInt(0)        // Layer mask data length = 0
        lDos.writeInt(0)        // Layer blending ranges length = 0
        lDos.writeByte(10)      // "Background".length
        lDos.writeBytes("Background")
        lDos.writeByte(0)       // Pad to multiple of 4 bytes (1 + 10 + 1 = 12)

        // Channel Image Data for R, G, B
        for (plane in arrayOf(compR, compG, compB)) {
            lDos.writeShort(1) // Compression = 1 (PackBits RLE)
            for (len in plane.rowLengths) {
                lDos.writeShort(len and 0xFFFF)
            }
            lDos.write(plane.data)
        }

        var layerInfoBytes = layerInfoBaos.toByteArray()
        if (layerInfoBytes.size % 2 != 0) {
            layerInfoBaos.write(0)
            layerInfoBytes = layerInfoBaos.toByteArray()
        }

        // Wrap Layer Info + Global Layer Mask Info (0)
        val sectionBaos = ByteArrayOutputStream(layerInfoBytes.size + 8)
        val sDos = DataOutputStream(sectionBaos)
        sDos.writeInt(layerInfoBytes.size)
        sDos.write(layerInfoBytes)
        sDos.writeInt(0) // Global layer mask info length = 0
        return sectionBaos.toByteArray()
    }

    private fun buildImageResources(spotChannels: List<SpotChannelData>, dpi: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        // Resource 1: 0x03ED (1005) — ResolutionInfo (300 DPI + Inches unit)
        val resInfo = ByteArrayOutputStream()
        val rDos = DataOutputStream(resInfo)
        rDos.writeInt(dpi shl 16)
        rDos.writeShort(1)
        rDos.writeShort(2)
        rDos.writeInt(dpi shl 16)
        rDos.writeShort(1)
        rDos.writeShort(2)
        writeResourceBlock(dos, 0x03ED, resInfo.toByteArray())

        if (spotChannels.isNotEmpty()) {
            // Resource 2: 0x03EE (1006) — Alpha / Spot Channel Names (ASCII Pascal strings)
            val namesBuf = ByteArrayOutputStream()
            for (sc in spotChannels) {
                val ascii = sc.name.take(31).toByteArray(Charsets.US_ASCII)
                namesBuf.write(ascii.size and 0xFF)
                namesBuf.write(ascii)
            }
            writeResourceBlock(dos, 0x03EE, namesBuf.toByteArray())

            // Resource 3: 0x0415 (1045) — Unicode Alpha Names (Required by Photopea & modern Photoshop!)
            val uniBuf = ByteArrayOutputStream()
            val uDos = DataOutputStream(uniBuf)
            for (sc in spotChannels) {
                val cleanName = sc.name.take(31)
                uDos.writeInt(cleanName.length + 1) // UTF-16 code units + null terminator
                for (ch in cleanName) {
                    uDos.writeChar(ch.code)
                }
                uDos.writeChar(0)
            }
            writeResourceBlock(dos, 0x0415, uniBuf.toByteArray())

            // Resource 4: 0x041D (1053) — Alpha Identifiers (Unique 4-byte IDs per channel)
            val idBuf = ByteArrayOutputStream()
            val idDos = DataOutputStream(idBuf)
            for (i in spotChannels.indices) {
                idDos.writeInt(i + 1)
            }
            writeResourceBlock(dos, 0x041D, idBuf.toByteArray())

            // Resource 5: 0x03EF (1007) — DisplayInfo (CS3 Classic: 14 bytes per channel)
            val dispOld = ByteArrayOutputStream()
            val dOld = DataOutputStream(dispOld)
            for (sc in spotChannels) {
                dOld.writeShort(0) // RGB
                dOld.writeShort(Color.red(sc.colorInt) * 257)
                dOld.writeShort(Color.green(sc.colorInt) * 257)
                dOld.writeShort(Color.blue(sc.colorInt) * 257)
                dOld.writeShort(0)
                dOld.writeShort(sc.solidity.coerceIn(0, 100))
                dOld.writeByte(2) // Kind 2 = Spot Color
                dOld.writeByte(0)
            }
            writeResourceBlock(dos, 0x03EF, dispOld.toByteArray())

            // Resource 6: 0x0435 (1077) — DisplayInfo (CS3+ / Photopea Modern: 4b ver + 13b/ch)
            val dispNew = ByteArrayOutputStream()
            val dNew = DataOutputStream(dispNew)
            dNew.writeInt(1)
            for (sc in spotChannels) {
                dNew.writeShort(0) // RGB
                dNew.writeShort(Color.red(sc.colorInt) * 257)
                dNew.writeShort(Color.green(sc.colorInt) * 257)
                dNew.writeShort(Color.blue(sc.colorInt) * 257)
                dNew.writeShort(0)
                dNew.writeShort(sc.solidity.coerceIn(0, 100))
                dNew.writeByte(2) // Kind 2 = Spot Color
            }
            writeResourceBlock(dos, 0x0435, dispNew.toByteArray())
        }

        return baos.toByteArray()
    }

    private fun writeResourceBlock(dos: DataOutputStream, resourceId: Int, data: ByteArray) {
        dos.writeBytes("8BIM")
        dos.writeShort(resourceId)
        dos.writeByte(0)
        dos.writeByte(0)
        dos.writeInt(data.size)
        dos.write(data)
        if (data.size % 2 != 0) {
            dos.writeByte(0)
        }
    }

    private fun packBitsRow(src: ByteArray, length: Int, dst: ByteArray): Int {
        var i = 0
        var outPos = 0
        while (i < length) {
            var runLen = 1
            while (i + runLen < length && runLen < 128 && src[i + runLen] == src[i]) {
                runLen++
            }
            if (runLen >= 2) {
                dst[outPos++] = (257 - runLen).toByte()
                dst[outPos++] = src[i]
                i += runLen
            } else {
                val litStart = i
                var litLen = 0
                while (i < length && litLen < 128) {
                    if (i + 1 < length && src[i] == src[i + 1]) {
                        break
                    }
                    i++
                    litLen++
                }
                if (litLen == 0) {
                    dst[outPos++] = 0
                    dst[outPos++] = src[i++]
                } else {
                    dst[outPos++] = (litLen - 1).toByte()
                    System.arraycopy(src, litStart, dst, outPos, litLen)
                    outPos += litLen
                }
            }
        }
        return outPos
    }
}
