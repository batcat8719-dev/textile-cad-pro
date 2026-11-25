package com.textilecad.pro

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class PrintChemistry(val label: String) {
    PROCION("PROCION (RFD + Trap)"),
    DISCHARGE("DISCHARGE (No Gadd)"),
    KHADI_GOLD("KHADI / GOLD (Thick)")
}

enum class RepeatType(val label: String) {
    STRAIGHT("STRAIGHT"),
    HALF_DROP("HALF-DROP")
}

// Standard Indian Mill 25.25" Rotary Screen Repeat Divisions (Ashrafi Master Guide)
enum class RotaryRepeat(val divisions: Int, val inches: Float, val label: String) {
    DIV_2(2, 12.625f, "12.62\" (2/cyl)"),
    DIV_3(3, 8.416f, "8.41\" (3/cyl)"),
    DIV_4(4, 6.312f, "6.31\" (4/cyl)"),
    DIV_5(5, 5.050f, "5.05\" (5/cyl)"),
    DIV_6(6, 4.208f, "4.20\" (6/cyl)"),
    DIV_7(7, 3.607f, "3.60\" (7/cyl)"),
    DIV_8(8, 3.156f, "3.15\" (8/cyl)")
}

data class CadBuildResult(
    val previewCadBitmap: Bitmap,
    val psdPathLabel: String,
    val psdUri: Uri?,
    val cadSheetUri: Uri?,
    val positiveCount: Int,
    val channelNames: List<String>,
    val summary: String
)

class TextileCadEngine(private val context: Context) {

    fun buildOperatorReadyCad(
        source: Bitmap,
        normCorners: FloatArray,
        numColors: Int,
        chemistry: PrintChemistry,
        repeatType: RepeatType,
        rotary: RotaryRepeat,
        designCode: String,
        onProgress: (String) -> Unit
    ): CadBuildResult {
        // 1. Perspective Straighten (Ctrl+T Skew Fix) & Scale to Clean Mill Working Resolution
        onProgress("1/6 Perspective straightening & 300 DPI sizing...")
        val targetH = (rotary.inches * 300f).roundToInt().coerceIn(900, 1800)
        val warped = warpPerspectiveRepeat(source, normCorners, targetH)

        // 2. Flatten Uneven WhatsApp Photo Lighting & Shadows
        onProgress("2/6 Flattening WhatsApp shadows & normalizing white balance...")
        val flattened = flattenUnevenLighting(warped)
        if (warped !== source) warped.recycle()

        val w = flattened.width
        val h = flattened.height
        val total = w * h
        val pixels = IntArray(total)
        flattened.getPixels(pixels, 0, w, 0, 0, w, h)
        flattened.recycle()

        // 3. K-Means++ Colour Separation + Majority De-speckling (Kills JPEG noise)
        onProgress("3/6 Separating $numColors flat colours & removing JPEG noise...")
        val (palette, rawLabels) = quantizeFlatColors(pixels, w, h, numColors.coerceIn(2, 8))
        val cleanLabels = majorityDespeckle(rawLabels, w, h, passes = 2)

        // Count pixel area per cluster
        val counts = IntArray(palette.size)
        for (lbl in cleanLabels) counts[lbl]++

        // Identify Ground (Gadd) = largest area cluster
        val groundIdx = counts.indices.maxByOrNull { counts[it] } ?: 0

        // Identify Rekh (Outline) = darkest non-ground colour (if sufficiently dark)
        val rekhIdx = palette.indices
            .filter { it != groundIdx }
            .minByOrNull { luma(palette[it]) }
            ?.takeIf { luma(palette[it]) < 115 }

        // 4. Ashrafi Channel Stack Ordering (Top: Lightest -> Middle: Motifs -> Ground -> BOTTOM: Rekh)
        onProgress("4/6 Applying ${chemistry.name} chemistry, trapping & Bottom Rekh rule...")
        val orderedClusterIndices = mutableListOf<Int>()
        val midClusters = palette.indices
            .filter { it != groundIdx && it != rekhIdx }
            .sortedByDescending { luma(palette[it]) } // Lightest on top

        orderedClusterIndices.addAll(midClusters)
        // Discharge Golden Rule: Dark dyed kapda is already ground — NO Gadd screen!
        val skipGround = (chemistry == PrintChemistry.DISCHARGE && luma(palette[groundIdx]) < 145)
        if (!skipGround) {
            orderedClusterIndices.add(groundIdx)
        }
        if (rekhIdx != null) {
            orderedClusterIndices.add(rekhIdx) // Bottom Rekh Rule!
        }

        // Build 100% Binary Hard-Edge Spot Channels (0 = Black Ink, 255 = White/No Ink)
        val spotChannels = mutableListOf<SpotChannelData>()
        for (stackPos in orderedClusterIndices.indices) {
            val cIdx = orderedClusterIndices[stackPos]
            val col = palette[cIdx]
            var mask = BooleanArray(total) { i -> cleanLabels[i] == cIdx }

            val isRekh = (cIdx == rekhIdx)
            val isGround = (cIdx == groundIdx)
            val isWhiteOrLightest = (luma(col) > 215)

            when (chemistry) {
                PrintChemistry.PROCION -> {
                    // Procion Rule: 1 px Expand (Trapping / Alt+S+M+E) on lighter colors under darker colors
                    if (!isRekh && !isGround) {
                        mask = dilateBinary(mask, w, h)
                    }
                }
                PrintChemistry.DISCHARGE -> {
                    // Discharge Golden Rule: Control White Discharge weight (1 px Contract/Choke)
                    if (isWhiteOrLightest) {
                        mask = erodeBinary(mask, w, h)
                    }
                }
                PrintChemistry.KHADI_GOLD -> {
                    // Khadi / Gold Rule: Minimum 3-4 px line thickness so thick paste never chokes mesh
                    mask = dilateBinary(mask, w, h)
                }
            }

            // Convert boolean ink mask to strict Binary ByteArray (0 = Ink, 255 = Empty)
            val inkBytes = ByteArray(total) { i -> if (mask[i]) 0.toByte() else 0xFF.toByte() }
            val roleTag = when {
                isRekh -> "Rekh_Outline"
                isGround -> "Gadd_Ground"
                isWhiteOrLightest -> "White_Reserve"
                else -> colorNameHint(col)
            }
            val chName = "Ch${stackPos + 1}_$roleTag"
            spotChannels.add(SpotChannelData(chName, col, solidity = 100, inkMask = inkBytes))
        }

        // 5. Render Clean Flattened Composite Preview from Spot Channels (Double-Q Merge Logic!)
        onProgress("5/6 Rendering clean RGB composite & Master CAD Sheet...")
        val bgBaseColor = if (skipGround) palette[groundIdx] else Color.WHITE
        val cleanTile = renderSpotComposite(w, h, bgBaseColor, spotChannels)

        // Save Real 300 DPI Multichannel Spot-Channel .PSD to Download/TextileCAD-Pro/
        val psdFileName = "${designCode}_MILL_300DPI.psd"
        val psdUri = savePsdToDownloads(cleanTile, spotChannels, psdFileName)

        // Build & Save Client/Operator Master CAD Presentation Sheet (Color Chits + Red Dimensions in Inches & Soot)
        val widthInches = (w.toFloat() / h.toFloat()) * rotary.inches
        val masterSheet = renderMasterCadSheet(
            tile = cleanTile,
            spotChannels = spotChannels,
            repeatType = repeatType,
            widthInches = widthInches,
            heightInches = rotary.inches,
            chemistry = chemistry,
            rotary = rotary,
            designCode = designCode,
            dyedGroundColor = if (skipGround) palette[groundIdx] else null
        )
        val cadSheetUri = savePngToGallery(masterSheet, "${designCode}_MASTER_CAD")

        // 6. Export Individual 300 DPI Binary B&W Film Positives with 4-Corner (+) Registration Marks
        onProgress("6/6 Saving ${spotChannels.size} B&W Film Positives with + registration marks...")
        for (i in spotChannels.indices) {
            val sc = spotChannels[i]
            val posBmp = renderFilmPositive(w, h, sc, designCode)
            savePngToGallery(posBmp, "${designCode}_${sc.name}_POS")
            posBmp.recycle()
        }
        cleanTile.recycle()

        val summary = "${spotChannels.size} Spot Screens • ${chemistry.name} • ${rotary.label}"
        return CadBuildResult(
            previewCadBitmap = masterSheet,
            psdPathLabel = "Download/TextileCAD-Pro/$psdFileName",
            psdUri = psdUri,
            cadSheetUri = cadSheetUri,
            positiveCount = spotChannels.size,
            channelNames = spotChannels.map { it.name },
            summary = summary
        )
    }

    // ==================== PERSPECTIVE WARP (CTRL+T SKEW FIX) ====================

    private fun warpPerspectiveRepeat(src: Bitmap, normPts: FloatArray, targetH: Int): Bitmap {
        val sw = src.width.toFloat()
        val sh = src.height.toFloat()
        val srcPts = floatArrayOf(
            normPts[0] * sw, normPts[1] * sh,
            normPts[2] * sw, normPts[3] * sh,
            normPts[4] * sw, normPts[5] * sh,
            normPts[6] * sw, normPts[7] * sh
        )
        val topW = hypotF(srcPts[2] - srcPts[0], srcPts[3] - srcPts[1])
        val botW = hypotF(srcPts[4] - srcPts[6], srcPts[5] - srcPts[7])
        val leftH = hypotF(srcPts[6] - srcPts[0], srcPts[7] - srcPts[1])
        val rightH = hypotF(srcPts[4] - srcPts[2], srcPts[5] - srcPts[3])

        val estAspect = ((topW + botW) / (leftH + rightH).coerceAtLeast(1f)).coerceIn(0.45f, 2.2f)
        val outH = targetH
        val outW = (outH * estAspect).roundToInt().coerceIn(512, 1800)

        val dstPts = floatArrayOf(
            0f, 0f,
            outW.toFloat(), 0f,
            outW.toFloat(), outH.toFloat(),
            0f, outH.toFloat()
        )
        val matrix = Matrix()
        matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, matrix, paint)
        return out
    }

    // ==================== WHATSAPP SHADOW / FOLD LIGHTING FLATTENER ====================

    private fun flattenUnevenLighting(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        // Low-frequency illumination map (removes phone shadows & uneven room bulbs)
        val tiny = Bitmap.createScaledBitmap(src, 20, 20, true)
        val illumBmp = Bitmap.createScaledBitmap(tiny, w, h, true)
        tiny.recycle()

        val px = IntArray(w * h)
        val il = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        illumBmp.getPixels(il, 0, w, 0, 0, w, h)
        illumBmp.recycle()

        var sumLum = 0L
        for (i in il.indices step 4) {
            sumLum += luma(il[i])
        }
        val meanLum = (sumLum / (il.size / 4).coerceAtLeast(1)).toFloat().coerceIn(40f, 230f)

        for (i in px.indices) {
            val localLum = luma(il[i]).toFloat().coerceAtLeast(28f)
            // Gentle 55% illumination flattening so shadows equalize without destroying dark designs
            val gain = (1f + (meanLum / localLum - 1f) * 0.55f).coerceIn(0.74f, 1.38f)
            val p = px[i]
            val r = (((p shr 16) and 0xFF) * gain).toInt().coerceIn(0, 255)
            val g = (((p shr 8) and 0xFF) * gain).toInt().coerceIn(0, 255)
            val b = ((p and 0xFF) * gain).toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    // ==================== SMART K-MEANS++ FLAT COLOUR SEPARATION ====================

    private fun quantizeFlatColors(pixels: IntArray, w: Int, h: Int, k: Int): Pair<IntArray, ByteArray> {
        val step = max(1, (w * h) / 14000)
        val samples = ArrayList<Int>(15000)
        var i = 0
        while (i < pixels.size) {
            samples.add(pixels[i])
            i += step
        }

        // K-Means++ deterministic farthest-point initialization (guarantees small accent colours aren't lost!)
        val cR = FloatArray(k)
        val cG = FloatArray(k)
        val cB = FloatArray(k)

        val first = samples[samples.size / 2]
        cR[0] = ((first shr 16) and 0xFF).toFloat()
        cG[0] = ((first shr 8) and 0xFF).toFloat()
        cB[0] = (first and 0xFF).toFloat()

        for (c in 1 until k) {
            var bestColor = samples[0]
            var bestMinDist = -1f
            for (s in samples) {
                val sr = ((s shr 16) and 0xFF).toFloat()
                val sg = ((s shr 8) and 0xFF).toFloat()
                val sb = (s and 0xFF).toFloat()
                var minDist = Float.MAX_VALUE
                for (j in 0 until c) {
                    val d = colorDistSq(sr, sg, sb, cR[j], cG[j], cB[j])
                    if (d < minDist) minDist = d
                }
                if (minDist > bestMinDist) {
                    bestMinDist = minDist
                    bestColor = s
                }
            }
            cR[c] = ((bestColor shr 16) and 0xFF).toFloat()
            cG[c] = ((bestColor shr 8) and 0xFF).toFloat()
            cB[c] = (bestColor and 0xFF).toFloat()
        }

        // 10 iterations of weighted K-Means refinement
        val sumR = DoubleArray(k); val sumG = DoubleArray(k); val sumB = DoubleArray(k)
        val cnt = IntArray(k)
        repeat(10) {
            sumR.fill(0.0); sumG.fill(0.0); sumB.fill(0.0); cnt.fill(0)
            for (s in samples) {
                val sr = ((s shr 16) and 0xFF).toFloat()
                val sg = ((s shr 8) and 0xFF).toFloat()
                val sb = (s and 0xFF).toFloat()
                var bestIdx = 0; var bestD = Float.MAX_VALUE
                for (c in 0 until k) {
                    val d = colorDistSq(sr, sg, sb, cR[c], cG[c], cB[c])
                    if (d < bestD) { bestD = d; bestIdx = c }
                }
                sumR[bestIdx] += sr; sumG[bestIdx] += sg; sumB[bestIdx] += sb; cnt[bestIdx]++
            }
            for (c in 0 until k) {
                if (cnt[c] > 0) {
                    cR[c] = (sumR[c] / cnt[c]).toFloat()
                    cG[c] = (sumG[c] / cnt[c]).toFloat()
                    cB[c] = (sumB[c] / cnt[c]).toFloat()
                }
            }
        }

        val palette = IntArray(k) { c ->
            Color.rgb(cR[c].roundToInt().coerceIn(0, 255), cG[c].roundToInt().coerceIn(0, 255), cB[c].roundToInt().coerceIn(0, 255))
        }

        // Assign every pixel to nearest flat palette colour
        val labels = ByteArray(pixels.size)
        for (idx in pixels.indices) {
            val p = pixels[idx]
            val pr = ((p shr 16) and 0xFF).toFloat()
            val pg = ((p shr 8) and 0xFF).toFloat()
            val pb = (p and 0xFF).toFloat()
            var bestIdx = 0; var bestD = Float.MAX_VALUE
            for (c in 0 until k) {
                val d = colorDistSq(pr, pg, pb, cR[c], cG[c], cB[c])
                if (d < bestD) { bestD = d; bestIdx = c }
            }
            labels[idx] = bestIdx.toByte()
        }
        return palette to labels
    }

    // Perceptual redmean color distance (far more accurate for fabrics than plain RGB!)
    private fun colorDistSq(r1: Float, g1: Float, b1: Float, r2: Float, g2: Float, b2: Float): Float {
        val dr = r1 - r2; val dg = g1 - g2; val db = b1 - b2
        val rMean = (r1 + r2) * 0.5f
        return (2f + rMean / 256f) * dr * dr + 4f * dg * dg + (2f + (255f - rMean) / 256f) * db * db
    }

    // Majority 3x3 Mode Filter: kills WhatsApp speckles while preserving sharp hard edges!
    private fun majorityDespeckle(labels: ByteArray, w: Int, h: Int, passes: Int): IntArray {
        var cur = IntArray(labels.size) { labels[it].toInt() and 0xFF }
        var next = IntArray(labels.size)
        val freq = IntArray(12)

        repeat(passes) {
            System.arraycopy(cur, 0, next, 0, cur.size)
            for (y in 1 until h - 1) {
                val r0 = (y - 1) * w; val r1 = y * w; val r2 = (y + 1) * w
                for (x in 1 until w - 1) {
                    freq.fill(0)
                    freq[cur[r0 + x - 1]]++; freq[cur[r0 + x]]++; freq[cur[r0 + x + 1]]++
                    freq[cur[r1 + x - 1]]++; freq[cur[r1 + x]]++; freq[cur[r1 + x + 1]]++
                    freq[cur[r2 + x - 1]]++; freq[cur[r2 + x]]++; freq[cur[r2 + x + 1]]++
                    var bestLbl = cur[r1 + x]; var bestCnt = 0
                    for (c in 0 until 10) {
                        if (freq[c] > bestCnt) { bestCnt = freq[c]; bestLbl = c }
                    }
                    next[r1 + x] = bestLbl
                }
            }
            val tmp = cur; cur = next; next = tmp
        }
        return cur
    }

    // ==================== BINARY MORPHOLOGY (TRAPPING & CHOKE) ====================

    private fun dilateBinary(src: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = src.copyOf()
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                if (src[row + x] || src[row + x - 1] || src[row + x + 1] || src[row - w + x] || src[row + w + x]) {
                    out[row + x] = true
                }
            }
        }
        return out
    }

    private fun erodeBinary(src: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(src.size)
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                out[row + x] = src[row + x] && src[row + x - 1] && src[row + x + 1] && src[row - w + x] && src[row + w + x]
            }
        }
        return out
    }

    // ==================== RENDER COMPOSITE, CAD SHEET & FILM POSITIVES ====================

    private fun renderSpotComposite(w: Int, h: Int, baseBg: Int, channels: List<SpotChannelData>): Bitmap {
        val px = IntArray(w * h) { baseBg }
        // Paint Top-to-Bottom in channel order (so Bottom Rekh always overprints cleanly on top!)
        for (sc in channels) {
            val col = sc.colorInt
            val mask = sc.inkMask
            for (i in px.indices) {
                if (mask[i].toInt() == 0) {
                    px[i] = col
                }
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun renderMasterCadSheet(
        tile: Bitmap,
        spotChannels: List<SpotChannelData>,
        repeatType: RepeatType,
        widthInches: Float,
        heightInches: Float,
        chemistry: PrintChemistry,
        rotary: RotaryRepeat,
        designCode: String,
        dyedGroundColor: Int?
    ): Bitmap {
        // 2x2 Repeat Canvas (Compact 1400px wide for instant WhatsApp sharing + crisp print)
        val tileDrawW = 640
        val tileDrawH = ((tile.height.toFloat() / tile.width) * tileDrawW).roundToInt().coerceIn(420, 900)
        val scaledTile = Bitmap.createScaledBitmap(tile, tileDrawW, tileDrawH, false)

        val repeatW = tileDrawW * 2
        val repeatH = tileDrawH * 2
        val topHeaderH = 210
        val sidePad = 90
        val bottomPad = 75

        val sheetW = repeatW + sidePad * 2
        val sheetH = topHeaderH + repeatH + bottomPad
        val sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)

        // Draw 2x2 Repeat (Straight or Half-Drop)
        canvas.save()
        canvas.clipRect(sidePad, topHeaderH, sidePad + repeatW, topHeaderH + repeatH)
        if (repeatType == RepeatType.STRAIGHT) {
            for (gx in 0..1) {
                for (gy in 0..1) {
                    canvas.drawBitmap(scaledTile, (sidePad + gx * tileDrawW).toFloat(), (topHeaderH + gy * tileDrawH).toFloat(), null)
                }
            }
        } else {
            // Half-Drop: second column shifts 50% vertically
            for (gy in 0..1) {
                canvas.drawBitmap(scaledTile, sidePad.toFloat(), (topHeaderH + gy * tileDrawH).toFloat(), null)
            }
            val halfY = tileDrawH / 2
            for (gy in -1..2) {
                canvas.drawBitmap(scaledTile, (sidePad + tileDrawW).toFloat(), (topHeaderH + gy * tileDrawH + halfY).toFloat(), null)
            }
        }
        canvas.restore()
        scaledTile.recycle()

        // Border around repeat box
        val borderPaint = Paint().apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f
        }
        canvas.drawRect(sidePad.toFloat(), topHeaderH.toFloat(), (sidePad + repeatW).toFloat(), (topHeaderH + repeatH).toFloat(), borderPaint)

        // Header Text (Design Code + Print Chemistry + Rotary Spec)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 32f; isFakeBoldText = true
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY; textSize = 23f
        }
        canvas.drawText("$designCode  |  ${chemistry.name}  |  ${spotChannels.size} SCREENS  |  ${repeatType.label}", sidePad.toFloat(), 48f, titlePaint)
        canvas.drawText("Rotary 25.25\" (${rotary.divisions} Div)  •  Repeat: ${formatInchesSoot(widthInches)} W × ${formatInchesSoot(heightInches)} H  •  300 DPI", sidePad.toFloat(), 84f, subPaint)

        // Top Color Chits (Swatches) with Ashrafi 16px-proportional dark stroke on White swatch!
        val chitSize = 68f
        val chitGap = 18f
        var cx = sidePad.toFloat()
        val cy = 108f
        val chitFill = Paint(Paint.ANTI_ALIAS_FLAG)
        val chitBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f }
        val whiteChitBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 7f }
        val chitLbl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 18f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }

        if (dyedGroundColor != null) {
            chitFill.color = dyedGroundColor
            canvas.drawRect(cx, cy, cx + chitSize, cy + chitSize, chitFill)
            canvas.drawRect(cx, cy, cx + chitSize, cy + chitSize, chitBorder)
            canvas.drawText("DYED", cx + chitSize / 2f, cy + chitSize + 20f, chitLbl)
            cx += chitSize + chitGap + 12f
        }

        for (i in spotChannels.indices) {
            val sc = spotChannels[i]
            chitFill.color = sc.colorInt
            canvas.drawRect(cx, cy, cx + chitSize, cy + chitSize, chitFill)
            val strokeP = if (luma(sc.colorInt) > 215) whiteChitBorder else chitBorder
            canvas.drawRect(cx, cy, cx + chitSize, cy + chitSize, strokeP)
            canvas.drawText("C${i + 1}", cx + chitSize / 2f, cy + chitSize + 20f, chitLbl)
            cx += chitSize + chitGap
        }

        // Red Dimension Lines (Right = Repeat Height, Bottom = Repeat Width)
        val redLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D50000"); strokeWidth = 3.5f }
        val redText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D50000"); textSize = 26f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }

        // Bottom Width Dimension (1 Repeat)
        val dimY = (topHeaderH + repeatH + 34).toFloat()
        canvas.drawLine(sidePad.toFloat(), dimY, (sidePad + tileDrawW).toFloat(), dimY, redLine)
        canvas.drawLine(sidePad.toFloat(), dimY - 12f, sidePad.toFloat(), dimY + 12f, redLine)
        canvas.drawLine((sidePad + tileDrawW).toFloat(), dimY - 12f, (sidePad + tileDrawW).toFloat(), dimY + 12f, redLine)
        canvas.drawText("1 Repeat W: ${formatInchesSoot(widthInches)}", sidePad + tileDrawW / 2f, dimY + 28f, redText)

        // Right Height Dimension (1 Repeat)
        val dimX = (sidePad + repeatW + 36).toFloat()
        canvas.drawLine(dimX, topHeaderH.toFloat(), dimX, (topHeaderH + tileDrawH).toFloat(), redLine)
        canvas.drawLine(dimX - 12f, topHeaderH.toFloat(), dimX + 12f, topHeaderH.toFloat(), redLine)
        canvas.drawLine(dimX - 12f, (topHeaderH + tileDrawH).toFloat(), dimX + 12f, (topHeaderH + tileDrawH).toFloat(), redLine)
        canvas.save()
        canvas.rotate(90f, dimX + 26f, topHeaderH + tileDrawH / 2f)
        canvas.drawText("H: ${formatInchesSoot(heightInches)}", dimX + 26f, topHeaderH + tileDrawH / 2f, redText)
        canvas.restore()

        return sheet
    }

    private fun renderFilmPositive(w: Int, h: Int, sc: SpotChannelData, designCode: String): Bitmap {
        val margin = 70
        val outW = w + margin * 2
        val outH = h + margin * 2
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)

        val tilePx = IntArray(w * h) { i -> if (sc.inkMask[i].toInt() == 0) Color.BLACK else Color.WHITE }
        val tileBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        tileBmp.setPixels(tilePx, 0, w, 0, 0, w, h)
        canvas.drawBitmap(tileBmp, margin.toFloat(), margin.toFloat(), null)
        tileBmp.recycle()

        // 4-Corner (+) Registration Marks for Screen Exposure
        val regP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 3f; style = Paint.Style.STROKE }
        val corners = arrayOf(
            35f to 35f,
            (outW - 35f) to 35f,
            35f to (outH - 35f),
            (outW - 35f) to (outH - 35f)
        )
        for ((rx, ry) in corners) {
            canvas.drawCircle(rx, ry, 14f, regP)
            canvas.drawLine(rx - 22f, ry, rx + 22f, ry, regP)
            canvas.drawLine(rx, ry - 22f, rx, ry + 22f, regP)
        }

        val lblP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 26f; isFakeBoldText = true }
        canvas.drawText("$designCode  •  ${sc.name}  •  300 DPI POSITIVE", 80f, 44f, lblP)
        return out
    }

    // ==================== FILE EXPORTS (PSD -> DOWNLOADS, PNG -> GALLERY) ====================

    private fun savePsdToDownloads(composite: Bitmap, spotChannels: List<SpotChannelData>, fileName: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/TextileCAD-Pro")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        PsdWriter.writeSpotChannelPsd(out, composite, spotChannels, dpi = 300)
                    }
                }
                uri
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TextileCAD-Pro")
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, fileName)
                FileOutputStream(f).use { out ->
                    PsdWriter.writeSpotChannelPsd(out, composite, spotChannels, dpi = 300)
                }
                Uri.fromFile(f)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun savePngToGallery(bmp: Bitmap, fileName: String): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TextileCAD-Pro")
                }
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
            uri
        } catch (_: Throwable) {
            null
        }
    }

    // ==================== MILL MATH & HELPERS ====================

    private fun formatInchesSoot(inches: Float): String {
        val whole = inches.toInt()
        val soot = ((inches - whole) * 8f).roundToInt()
        return if (soot == 0) "${whole}\"" else if (soot == 8) "${whole + 1}\"" else "${whole}\" ${soot}S"
    }

    private fun luma(color: Int): Int {
        return (77 * Color.red(color) + 150 * Color.green(color) + 29 * Color.blue(color)) shr 8
    }

    private fun hypotF(dx: Float, dy: Float): Float = sqrt(dx * dx + dy * dy)

    private fun colorNameHint(c: Int): String {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        val lum = luma(c)
        return when {
            lum > 220 -> "White"
            lum < 48 -> "Black"
            r > g + 35 && r > b + 35 -> if (g > 130) "Orange" else "Red"
            r > 180 && g > 170 && b < 110 -> "Yellow"
            g > r + 20 && g > b + 20 -> "Green"
            b > r + 25 && b > g + 15 -> "Blue"
            r > 160 && b > 140 && g < 130 -> "Pink"
            else -> String.format(Locale.US, "Col_%02X%02X%02X", r, g, b)
        }
    }
}
