package com.textilecad.pro

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
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

enum class RotaryRepeat(val divisions: Int, val inches: Float, val label: String) {
    DIV_2(2, 12.625f, "12.62\" (2/cyl)"),
    DIV_3(3, 8.416f, "8.41\" (3/cyl)"),
    DIV_4(4, 6.312f, "6.31\" (4/cyl)"),
    DIV_5(5, 5.050f, "5.05\" (5/cyl)"),
    DIV_6(6, 4.208f, "4.20\" (6/cyl)"),
    DIV_7(7, 3.607f, "3.60\" (7/cyl)"),
    DIV_8(8, 3.156f, "3.15\" (8/cyl)")
}

data class AiExtractOutcome(
    val success: Boolean,
    val bitmap: Bitmap?,
    val message: String
)

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

    companion object {
        const val DEFAULT_AI_MODEL = "gemini-2.5-flash-image"

        fun buildExtractionPrompt(numColors: Int): String = """
Extract ONLY the textile print pattern from this garment/fabric photo onto a completely flat, straight 2D square tile. Remove the garment silhouette, mannequin, body, folds, wrinkles, shadows, background, and fabric texture completely. Redraw and cleanly trace every floral motif, booty, leaf, vine, and border in sharp, crisp, flat Sanganer screen-printing CAD style using strictly $numColors flat solid colors with 100% hard vector-like edges, smooth clean curves, zero gradients, zero halftones, zero blur, and zero speckles. Make it a clean seamless all-over textile print repeat tile ready for screen printing color separation.
        """.trimIndent()
    }

    // ==================== 1. ONLINE AI PATTERN EXTRACTOR (GARMENT -> FLAT TRACED TILE) ====================

    fun extractFlatDesignWithAi(
        source: Bitmap,
        numColors: Int,
        apiKey: String,
        modelId: String
    ): AiExtractOutcome {
        val targetW = 1024
        val scale = targetW.toFloat() / max(source.width, source.height).coerceAtLeast(1)
        val resized = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).roundToInt().coerceAtLeast(64),
                (source.height * scale).roundToInt().coerceAtLeast(64),
                true
            )
        } else source

        val baos = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        if (resized !== source) resized.recycle()
        val base64Img = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

        val cleanModel = modelId.ifBlank { DEFAULT_AI_MODEL }.trim()
        val prompt = buildExtractionPrompt(numColors)

        var conn: HttpURLConnection? = null
        return try {
            val genConfig = JSONObject()
                .put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
                .put("imageConfig", JSONObject().put("aspectRatio", "1:1"))

            val body = JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray()
                                .put(JSONObject().put("text", prompt))
                                .put(
                                    JSONObject().put(
                                        "inlineData",
                                        JSONObject().put("mimeType", "image/jpeg").put("data", base64Img)
                                    )
                                )
                        )
                    )
                )
                .put("generationConfig", genConfig)

            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=$apiKey")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 90000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val respText = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                val err = try {
                    JSONObject(respText).optJSONObject("error")?.optString("message") ?: "HTTP $code"
                } catch (_: Exception) { "HTTP $code" }
                return AiExtractOutcome(false, null, "API $code: ${err.take(90)}")
            }

            val parts = JSONObject(respText)
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?: return AiExtractOutcome(false, null, "Empty AI response")

            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                val inline = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data")
                if (inline != null) {
                    val dataStr = inline.optString("data", "")
                    if (dataStr.isNotEmpty()) {
                        val bytes = Base64.decode(dataStr, Base64.DEFAULT)
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            return AiExtractOutcome(true, bmp, "✦ Flat Traced Design Extracted!")
                        }
                    }
                }
            }
            AiExtractOutcome(false, null, "AI returned text only — check Model ID or use ✦ GEMINI button")
        } catch (e: Exception) {
            AiExtractOutcome(false, null, e.message ?: "Network error")
        } finally {
            conn?.disconnect()
        }
    }

    // ==================== 2. OPERATOR-READY 300 DPI PSD & CAD PIPELINE ====================

    fun buildOperatorReadyCad(
        source: Bitmap,
        normCorners: FloatArray,
        numColors: Int,
        chemistry: PrintChemistry,
        repeatType: RepeatType,
        rotary: RotaryRepeat,
        enableDotting32Lpi: Boolean,
        designCode: String,
        onProgress: (String) -> Unit
    ): CadBuildResult {
        onProgress("1/6 Perspective straightening & 300 DPI sizing...")
        val targetH = (rotary.inches * 300f).roundToInt().coerceIn(900, 1800)
        val warped = warpPerspectiveRepeat(source, normCorners, targetH)

        onProgress("2/6 Flattening lighting & cleaning fabric texture...")
        val flattened = flattenUnevenLighting(warped)
        if (warped !== source) warped.recycle()

        val w = flattened.width
        val h = flattened.height
        val total = w * h
        val pixels = IntArray(total)
        flattened.getPixels(pixels, 0, w, 0, 0, w, h)
        flattened.recycle()

        onProgress("3/6 Separating $numColors flat colours & killing speckles...")
        val (palette, rawLabels) = quantizeFlatColors(pixels, w, h, numColors.coerceIn(2, 8))
        val smoothedLabels = majorityDespeckle(rawLabels, w, h, passes = 3)
        // Connected-component Speckle-Killer: removes all tiny noisy islands (< 28 px)
        val cleanLabels = removeTinySpeckles(smoothedLabels, w, h, minAreaPx = 28)

        val counts = IntArray(palette.size)
        for (lbl in cleanLabels) counts[lbl]++

        val groundIdx = counts.indices.maxByOrNull { counts[it] } ?: 0
        val rekhIdx = palette.indices
            .filter { it != groundIdx }
            .minByOrNull { luma(palette[it]) }
            ?.takeIf { luma(palette[it]) < 115 }

        onProgress("4/6 Applying ${chemistry.name} chemistry, trapping & Bottom Rekh...")
        val orderedClusterIndices = mutableListOf<Int>()
        val midClusters = palette.indices
            .filter { it != groundIdx && it != rekhIdx }
            .sortedByDescending { luma(palette[it]) }

        orderedClusterIndices.addAll(midClusters)
        val skipGround = (chemistry == PrintChemistry.DISCHARGE && luma(palette[groundIdx]) < 145)
        if (!skipGround) orderedClusterIndices.add(groundIdx)
        if (rekhIdx != null) orderedClusterIndices.add(rekhIdx)

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
                    if (!isRekh && !isGround) mask = dilateBinary(mask, w, h)
                }
                PrintChemistry.DISCHARGE -> {
                    if (isWhiteOrLightest) mask = erodeBinary(mask, w, h)
                }
                PrintChemistry.KHADI_GOLD -> {
                    mask = dilateBinary(mask, w, h)
                }
            }

            // Optional 32 LPI @ 30° Round Halftone Dotting on mid-tone shading channels
            val applyDotsHere = enableDotting32Lpi && !isRekh && !isGround && !isWhiteOrLightest && stackPos == 1
            val inkBytes = if (applyDotsHere) {
                applyHalftone32Lpi(mask, w, h)
            } else {
                ByteArray(total) { i -> if (mask[i]) 0.toByte() else 0xFF.toByte() }
            }

            val roleTag = when {
                isRekh -> "Rekh_Outline"
                isGround -> "Gadd_Ground"
                isWhiteOrLightest -> "White_Reserve"
                applyDotsHere -> "Dot32LPI_${colorNameHint(col)}"
                else -> colorNameHint(col)
            }
            spotChannels.add(SpotChannelData("Ch${stackPos + 1}_$roleTag", col, 100, inkBytes))
        }

        onProgress("5/6 Rendering clean RGB composite & Master CAD Sheet...")
        val bgBaseColor = if (skipGround) palette[groundIdx] else Color.WHITE
        val cleanTile = renderSpotComposite(w, h, bgBaseColor, spotChannels)

        val psdFileName = "${designCode}_MILL_300DPI.psd"
        val psdUri = savePsdToDownloads(cleanTile, spotChannels, psdFileName)

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

        onProgress("6/6 Exporting ${spotChannels.size} clean B&W Film Positives...")
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

    // ==================== SPECKLE-KILLER (FLOOD-FILL AREA CLEANER) ====================

    private fun removeTinySpeckles(labels: IntArray, w: Int, h: Int, minAreaPx: Int): IntArray {
        val total = w * h
        val out = labels.copyOf()
        val visited = BooleanArray(total)
        val queue = IntArray(total)
        val compPixels = IntArray(minAreaPx + 8)

        for (start in 0 until total) {
            if (visited[start]) continue
            val targetLbl = out[start]
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true

            var compCount = 0
            var neighborLbl = targetLbl
            var foundNeighbor = false

            while (head < tail) {
                val cur = queue[head++]
                if (compCount < compPixels.size) compPixels[compCount] = cur
                compCount++

                val cx = cur % w
                val cy = cur / w

                // 4-connected neighbors
                if (cx > 0) {
                    val n = cur - 1
                    if (out[n] == targetLbl) {
                        if (!visited[n]) { visited[n] = true; queue[tail++] = n }
                    } else if (!foundNeighbor) { neighborLbl = out[n]; foundNeighbor = true }
                }
                if (cx + 1 < w) {
                    val n = cur + 1
                    if (out[n] == targetLbl) {
                        if (!visited[n]) { visited[n] = true; queue[tail++] = n }
                    } else if (!foundNeighbor) { neighborLbl = out[n]; foundNeighbor = true }
                }
                if (cy > 0) {
                    val n = cur - w
                    if (out[n] == targetLbl) {
                        if (!visited[n]) { visited[n] = true; queue[tail++] = n }
                    } else if (!foundNeighbor) { neighborLbl = out[n]; foundNeighbor = true }
                }
                if (cy + 1 < h) {
                    val n = cur + w
                    if (out[n] == targetLbl) {
                        if (!visited[n]) { visited[n] = true; queue[tail++] = n }
                    } else if (!foundNeighbor) { neighborLbl = out[n]; foundNeighbor = true }
                }
            }

            if (compCount < minAreaPx && foundNeighbor) {
                for (k in 0 until compCount) {
                    out[compPixels[k]] = neighborLbl
                }
            }
        }
        return out
    }

    // ==================== 32 LPI @ 30° ROUND HALFTONE DOTTING ====================

    private fun applyHalftone32Lpi(mask: BooleanArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h) { 0xFF.toByte() }
        // At 300 DPI, 32 LPI => cell period ~ 9.4 px, Angle = 30 degrees
        val period = 9.4
        val rad = Math.toRadians(30.0)
        val cosA = cos(rad)
        val sinA = sin(rad)
        val maxDotRadius = period * 0.42

        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val idx = row + x
                if (!mask[idx]) continue
                val u = x * cosA + y * sinA
                val v = -x * sinA + y * cosA
                val cu = ((u % period) + period) % period - period * 0.5
                val cv = ((v % period) + period) % period - period * 0.5
                if (hypot(cu, cv) <= maxDotRadius) {
                    out[idx] = 0.toByte()
                }
            }
        }
        return out
    }

    // ==================== PERSPECTIVE WARP & LIGHTING FLATTENER ====================

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

    private fun flattenUnevenLighting(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val tiny = Bitmap.createScaledBitmap(src, 20, 20, true)
        val illumBmp = Bitmap.createScaledBitmap(tiny, w, h, true)
        tiny.recycle()

        val px = IntArray(w * h)
        val il = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        illumBmp.getPixels(il, 0, w, 0, 0, w, h)
        illumBmp.recycle()

        var sumLum = 0L
        for (i in il.indices step 4) sumLum += luma(il[i])
        val meanLum = (sumLum / (il.size / 4).coerceAtLeast(1)).toFloat().coerceIn(40f, 230f)

        for (i in px.indices) {
            val localLum = luma(il[i]).toFloat().coerceAtLeast(28f)
            val gain = (1f + (meanLum / localLum - 1f) * 0.45f).coerceIn(0.78f, 1.32f)
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

    // ==================== K-MEANS++ FLAT COLOUR SEPARATION ====================

    private fun quantizeFlatColors(pixels: IntArray, w: Int, h: Int, k: Int): Pair<IntArray, ByteArray> {
        val step = max(1, (w * h) / 14000)
        val samples = ArrayList<Int>(15000)
        var i = 0
        while (i < pixels.size) {
            samples.add(pixels[i])
            i += step
        }

        val cR = FloatArray(k); val cG = FloatArray(k); val cB = FloatArray(k)
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
                if (minDist > bestMinDist) { bestMinDist = minDist; bestColor = s }
            }
            cR[c] = ((bestColor shr 16) and 0xFF).toFloat()
            cG[c] = ((bestColor shr 8) and 0xFF).toFloat()
            cB[c] = (bestColor and 0xFF).toFloat()
        }

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
                sumR[bestIdx] = sumR[bestIdx] + sr.toDouble()
                sumG[bestIdx] = sumG[bestIdx] + sg.toDouble()
                sumB[bestIdx] = sumB[bestIdx] + sb.toDouble()
                cnt[bestIdx] = cnt[bestIdx] + 1
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

    private fun colorDistSq(r1: Float, g1: Float, b1: Float, r2: Float, g2: Float, b2: Float): Float {
        val dr = r1 - r2; val dg = g1 - g2; val db = b1 - b2
        val rMean = (r1 + r2) * 0.5f
        return (2f + rMean / 256f) * dr * dr + 4f * dg * dg + (2f + (255f - rMean) / 256f) * db * db
    }

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

    // ==================== RENDER COMPOSITE, CAD SHEET & CLEAN FILM POSITIVES ====================

    private fun renderSpotComposite(w: Int, h: Int, baseBg: Int, channels: List<SpotChannelData>): Bitmap {
        val px = IntArray(w * h) { baseBg }
        for (sc in channels) {
            val col = sc.colorInt
            val mask = sc.inkMask
            for (i in px.indices) {
                if (mask[i].toInt() == 0) px[i] = col
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

        canvas.save()
        canvas.clipRect(sidePad, topHeaderH, sidePad + repeatW, topHeaderH + repeatH)
        if (repeatType == RepeatType.STRAIGHT) {
            for (gx in 0..1) {
                for (gy in 0..1) {
                    canvas.drawBitmap(scaledTile, (sidePad + gx * tileDrawW).toFloat(), (topHeaderH + gy * tileDrawH).toFloat(), null)
                }
            }
        } else {
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

        val borderPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f }
        canvas.drawRect(sidePad.toFloat(), topHeaderH.toFloat(), (sidePad + repeatW).toFloat(), (topHeaderH + repeatH).toFloat(), borderPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 32f; isFakeBoldText = true }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 23f }
        canvas.drawText("$designCode  |  ${chemistry.name}  |  ${spotChannels.size} SCREENS  |  ${repeatType.label}", sidePad.toFloat(), 48f, titlePaint)
        canvas.drawText("Rotary 25.25\" (${rotary.divisions} Div)  •  Repeat: ${formatInchesSoot(widthInches)} W × ${formatInchesSoot(heightInches)} H  •  300 DPI", sidePad.toFloat(), 84f, subPaint)

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

        val redLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D50000"); strokeWidth = 3.5f }
        val redText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D50000"); textSize = 26f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }

        val dimY = (topHeaderH + repeatH + 34).toFloat()
        canvas.drawLine(sidePad.toFloat(), dimY, (sidePad + tileDrawW).toFloat(), dimY, redLine)
        canvas.drawLine(sidePad.toFloat(), dimY - 12f, sidePad.toFloat(), dimY + 12f, redLine)
        canvas.drawLine((sidePad + tileDrawW).toFloat(), dimY - 12f, (sidePad + tileDrawW).toFloat(), dimY + 12f, redLine)
        canvas.drawText("1 Repeat W: ${formatInchesSoot(widthInches)}", sidePad + tileDrawW / 2f, dimY + 28f, redText)

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

    // FIXED: Clean Film Positive with centered header & zero overlap on (+) registration marks!
    private fun renderFilmPositive(w: Int, h: Int, sc: SpotChannelData, designCode: String): Bitmap {
        val marginSide = 80
        val marginTop = 100
        val marginBot = 80
        val outW = w + marginSide * 2
        val outH = h + marginTop + marginBot
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)

        val tilePx = IntArray(w * h) { i -> if (sc.inkMask[i].toInt() == 0) Color.BLACK else Color.WHITE }
        val tileBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        tileBmp.setPixels(tilePx, 0, w, 0, 0, w, h)
        canvas.drawBitmap(tileBmp, marginSide.toFloat(), marginTop.toFloat(), null)
        tileBmp.recycle()

        val regP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 3f; style = Paint.Style.STROKE }
        val corners = arrayOf(
            40f to 45f,
            (outW - 40f) to 45f,
            40f to (outH - 40f),
            (outW - 40f) to (outH - 40f)
        )
        for ((rx, ry) in corners) {
            canvas.drawCircle(rx, ry, 14f, regP)
            canvas.drawLine(rx - 22f, ry, rx + 22f, ry, regP)
            canvas.drawLine(rx, ry - 22f, rx, ry + 22f, regP)
        }

        val lblP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = (outW * 0.022f).coerceIn(18f, 26f)
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("$designCode • ${sc.name} • 300 DPI", outW / 2f, 52f, lblP)
        return out
    }

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
