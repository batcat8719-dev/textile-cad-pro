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
 * Generates 100% standard RGB + Multichannel Spot-Channel PSDs at 300 DPI
 * with PackBits RLE compression — opens natively in Photopea & Photoshop CS3+.
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

        // ==================== 4. LAYER & MASK INFO (4 bytes) ====================
        dos.writeInt(0)

        // ==================== 5. IMAGE DATA (PackBits RLE = 1) ====================
        dos.writeShort(1)

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

        val allPlanes = ArrayList<ByteArray>(totalChannels)
        allPlanes.add(chR)
        allPlanes.add(chG)
        allPlanes.add(chB)
        for (sc in spotChannels) {
            allPlanes.add(sc.inkMask)
        }

        val totalRows = totalChannels * h
        val rowLengths = IntArray(totalRows)
        val compressedBody = ByteArrayOutputStream(w * h)
        val rowBuf = ByteArray(w)
        val rleScratch = ByteArray(w + (w / 128) + 8)

        var rowIdx = 0
        for (plane in allPlanes) {
            for (y in 0 until h) {
                System.arraycopy(plane, y * w, rowBuf, 0, w)
                val compLen = packBitsRow(rowBuf, w, rleScratch)
                rowLengths[rowIdx++] = compLen
                compressedBody.write(rleScratch, 0, compLen)
            }
        }

        // Write scanline byte-count table (big-endian unsigned 16-bit)
        for (len in rowLengths) {
            dos.writeShort(len and 0xFFFF)
        }
        // Write compressed scanline data
        dos.write(compressedBody.toByteArray())
        dos.flush()
    }

    private fun buildImageResources(spotChannels: List<SpotChannelData>, dpi: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        // Resource 1: 0x03ED (1005) — ResolutionInfo (300 DPI + Inches unit)
        val resInfo = ByteArrayOutputStream()
        val rDos = DataOutputStream(resInfo)
        rDos.writeInt(dpi shl 16) // hRes fixed 16.16
        rDos.writeShort(1)        // hResUnit = pixels/inch
        rDos.writeShort(2)        // widthUnit = inches
        rDos.writeInt(dpi shl 16) // vRes fixed 16.16
        rDos.writeShort(1)        // vResUnit = pixels/inch
        rDos.writeShort(2)        // heightUnit = inches
        writeResourceBlock(dos, 0x03ED, resInfo.toByteArray())

        if (spotChannels.isNotEmpty()) {
            // Resource 2: 0x03EE (1006) — Alpha / Spot Channel Names (Pascal strings)
            val namesBuf = ByteArrayOutputStream()
            for (sc in spotChannels) {
                val ascii = sc.name.take(31).toByteArray(Charsets.US_ASCII)
                namesBuf.write(ascii.size and 0xFF)
                namesBuf.write(ascii)
            }
            writeResourceBlock(dos, 0x03EE, namesBuf.toByteArray())

            // Resource 3: 0x03EF (1007) — DisplayInfo (CS3 Classic: 14 bytes per channel)
            val dispOld = ByteArrayOutputStream()
            val dOld = DataOutputStream(dispOld)
            for (sc in spotChannels) {
                dOld.writeShort(0) // ColorSpace 0 = RGB
                dOld.writeShort(Color.red(sc.colorInt) * 257)
                dOld.writeShort(Color.green(sc.colorInt) * 257)
                dOld.writeShort(Color.blue(sc.colorInt) * 257)
                dOld.writeShort(0)
                dOld.writeShort(sc.solidity.coerceIn(0, 100))
                dOld.writeByte(2) // Kind 2 = Spot Color
                dOld.writeByte(0) // Padding
            }
            writeResourceBlock(dos, 0x03EF, dispOld.toByteArray())

            // Resource 4: 0x0435 (1077) — DisplayInfo (CS3+ / Photopea Modern: 4b ver + 13b/ch)
            val dispNew = ByteArrayOutputStream()
            val dNew = DataOutputStream(dispNew)
            dNew.writeInt(1) // Version 1
            for (sc in spotChannels) {
                dNew.writeShort(0) // ColorSpace 0 = RGB
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
        dos.writeByte(0) // Empty Pascal name
        dos.writeByte(0) // Pad name to even length
        dos.writeInt(data.size)
        dos.write(data)
        if (data.size % 2 != 0) {
            dos.writeByte(0) // Pad odd data length to even
        }
    }

    private fun packBitsRow(src: ByteArray, length: Int, dst: ByteArray): Int {
        var i = 0
        var outPos = 0
        while (i < length) {
            // Check for run of identical bytes (length >= 2)
            var runLen = 1
            while (i + runLen < length && runLen < 128 && src[i + runLen] == src[i]) {
                runLen++
            }
            if (runLen >= 2) {
                dst[outPos++] = (257 - runLen).toByte()
                dst[outPos++] = src[i]
                i += runLen
            } else {
                // Literal run until a 2+ identical run starts or 128 bytes reached
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
