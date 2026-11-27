package com.textilecad.pro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

enum class StudioTool(val label: String) {
    CROP_RECT("MODE: RECT"),
    CROP_4PT("MODE: 4-PT"),
    F3_FILL("F3: FILL"),
    F2_REKH("F2: REKH"),
    EYEDROPPER("PICK COL")
}

class PerspectiveCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var previewBitmap: Bitmap? = null
    private val imgRect = RectF()
    private val undoStack = ArrayDeque<Bitmap>()

    // 100% full-frame seamless edges (zero auto-trim)
    private val normPts = floatArrayOf(
        0f, 0f,
        1f, 0f,
        1f, 1f,
        0f, 1f
    )
    private var activeCorner = -1

    var activeTool: StudioTool = StudioTool.CROP_RECT
        set(value) {
            field = value
            livePath.reset()
            invalidate()
        }

    var cropHandlesVisible = true
        set(value) {
            field = value
            invalidate()
        }

    var activeColor: Int = Color.BLACK
    var onColorPicked: ((Int) -> Unit)? = null

    private val livePath = Path()
    private val bmpPath = Path()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFF00")
        strokeWidth = 4.2f
        style = Paint.Style.STROKE
    }

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(95, 204, 255, 0)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFF00")
        style = Paint.Style.FILL
    }

    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#09090B")
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#09090B")
        textSize = 26f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val tracePreviewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFF00")
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    fun setBitmap(bmp: Bitmap?, resetCorners: Boolean = true, clearUndo: Boolean = true) {
        if (clearUndo) {
            while (undoStack.isNotEmpty()) undoStack.removeFirst().recycle()
        }
        previewBitmap = bmp?.let {
            if (it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888, true)
        }
        if (resetCorners) resetQuad()
        computeImageRect()
        invalidate()
    }

    fun getCurrentBitmap(): Bitmap? = previewBitmap

    fun resetQuad() {
        normPts[0] = 0f; normPts[1] = 0f
        normPts[2] = 1f; normPts[3] = 0f
        normPts[4] = 1f; normPts[5] = 1f
        normPts[6] = 0f; normPts[7] = 1f
        invalidate()
    }

    fun getNormalizedCorners(): FloatArray = normPts.copyOf()

    fun undoTrace(): Boolean {
        if (undoStack.isEmpty()) return false
        val prev = undoStack.removeLast()
        previewBitmap?.recycle()
        previewBitmap = prev
        computeImageRect()
        invalidate()
        return true
    }

    private fun pushUndoSnapshot() {
        val cur = previewBitmap ?: return
        if (undoStack.size >= 6) {
            undoStack.removeFirst().recycle()
        }
        undoStack.addLast(cur.copy(Bitmap.Config.ARGB_8888, true))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeImageRect()
    }

    private fun computeImageRect() {
        val bmp = previewBitmap ?: return
        if (width <= 0 || height <= 0) return
        val scale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f
        imgRect.set(left, top, left + dw, top + dh)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#111116"))
        val bmp = previewBitmap ?: return
        if (imgRect.isEmpty) computeImageRect()

        canvas.drawBitmap(bmp, null, imgRect, null)

        if (activeTool == StudioTool.F3_FILL || activeTool == StudioTool.F2_REKH) {
            tracePreviewPaint.color = activeColor
            canvas.drawPath(livePath, tracePreviewPaint)
            return
        }

        if (!cropHandlesVisible || (activeTool != StudioTool.CROP_RECT && activeTool != StudioTool.CROP_4PT)) return

        val x0 = imgRect.left + normPts[0] * imgRect.width()
        val y0 = imgRect.top + normPts[1] * imgRect.height()
        val x1 = imgRect.left + normPts[2] * imgRect.width()
        val y1 = imgRect.top + normPts[3] * imgRect.height()
        val x2 = imgRect.left + normPts[4] * imgRect.width()
        val y2 = imgRect.top + normPts[5] * imgRect.height()
        val x3 = imgRect.left + normPts[6] * imgRect.width()
        val y3 = imgRect.top + normPts[7] * imgRect.height()

        val quadPath = Path().apply {
            moveTo(x0, y0); lineTo(x1, y1); lineTo(x2, y2); lineTo(x3, y3); close()
        }
        canvas.drawPath(quadPath, linePaint)
        canvas.drawLine((x0 + x1) / 2f, (y0 + y1) / 2f, (x3 + x2) / 2f, (y3 + y2) / 2f, guidePaint)
        canvas.drawLine((x0 + x3) / 2f, (y0 + y3) / 2f, (x1 + x2) / 2f, (y1 + y2) / 2f, guidePaint)

        val coords = arrayOf(x0 to y0, x1 to y1, x2 to y2, x3 to y3)
        for (i in coords.indices) {
            val (cx, cy) = coords[i]
            val r = if (i == activeCorner) 34f else 26f
            canvas.drawCircle(cx, cy, r, handleFill)
            canvas.drawCircle(cx, cy, r, handleStroke)
            canvas.drawText("${i + 1}", cx, cy + 9f, textPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = previewBitmap ?: return false
        if (imgRect.isEmpty) return false

        when (activeTool) {
            StudioTool.EYEDROPPER -> {
                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                    val bx = (((event.x - imgRect.left) / imgRect.width()) * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                    val by = (((event.y - imgRect.top) / imgRect.height()) * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                    activeColor = bmp.getPixel(bx, by)
                    onColorPicked?.invoke(activeColor)
                    return true
                }
            }

            StudioTool.F3_FILL, StudioTool.F2_REKH -> {
                val bx = (((event.x - imgRect.left) / imgRect.width()) * bmp.width).coerceIn(0f, bmp.width.toFloat())
                val by = (((event.y - imgRect.top) / imgRect.height()) * bmp.height).coerceIn(0f, bmp.height.toFloat())
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        livePath.reset()
                        bmpPath.reset()
                        livePath.moveTo(event.x, event.y)
                        bmpPath.moveTo(bx, by)
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        livePath.lineTo(event.x, event.y)
                        bmpPath.lineTo(bx, by)
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        parent?.requestDisallowInterceptTouchEvent(false)
                        pushUndoSnapshot()
                        val canvas = Canvas(bmp)
                        val hardPaint = Paint().apply {
                            isAntiAlias = false
                            isDither = false
                            color = activeColor
                            if (activeTool == StudioTool.F3_FILL) {
                                style = Paint.Style.FILL
                            } else {
                                style = Paint.Style.STROKE
                                strokeWidth = (bmp.width / 260f).coerceIn(3f, 8f)
                                strokeCap = Paint.Cap.ROUND
                                strokeJoin = Paint.Join.ROUND
                            }
                        }
                        if (activeTool == StudioTool.F3_FILL) bmpPath.close()
                        canvas.drawPath(bmpPath, hardPaint)
                        livePath.reset()
                        bmpPath.reset()
                        invalidate()
                        return true
                    }
                }
            }

            StudioTool.CROP_RECT, StudioTool.CROP_4PT -> {
                if (!cropHandlesVisible) return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        var bestIdx = -1
                        var bestDist = 95f
                        for (i in 0 until 4) {
                            val cx = imgRect.left + normPts[i * 2] * imgRect.width()
                            val cy = imgRect.top + normPts[i * 2 + 1] * imgRect.height()
                            val d = hypot(event.x - cx, event.y - cy)
                            if (d < bestDist) {
                                bestDist = d
                                bestIdx = i
                            }
                        }
                        activeCorner = bestIdx
                        if (activeCorner >= 0) {
                            parent?.requestDisallowInterceptTouchEvent(true)
                            invalidate()
                            return true
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (activeCorner >= 0) {
                            val nx = ((event.x - imgRect.left) / imgRect.width()).coerceIn(0f, 1f)
                            val ny = ((event.y - imgRect.top) / imgRect.height()).coerceIn(0f, 1f)
                            if (activeTool == StudioTool.CROP_RECT) {
                                when (activeCorner) {
                                    0 -> {
                                        val cx = nx.coerceAtMost(normPts[2] - 0.1f)
                                        val cy = ny.coerceAtMost(normPts[7] - 0.1f)
                                        normPts[0] = cx; normPts[1] = cy
                                        normPts[6] = cx; normPts[3] = cy
                                    }
                                    1 -> {
                                        val cx = nx.coerceAtLeast(normPts[0] + 0.1f)
                                        val cy = ny.coerceAtMost(normPts[5] - 0.1f)
                                        normPts[2] = cx; normPts[3] = cy
                                        normPts[4] = cx; normPts[1] = cy
                                    }
                                    2 -> {
                                        val cx = nx.coerceAtLeast(normPts[6] + 0.1f)
                                        val cy = ny.coerceAtLeast(normPts[3] + 0.1f)
                                        normPts[4] = cx; normPts[5] = cy
                                        normPts[2] = cx; normPts[7] = cy
                                    }
                                    3 -> {
                                        val cx = nx.coerceAtMost(normPts[4] - 0.1f)
                                        val cy = ny.coerceAtLeast(normPts[1] + 0.1f)
                                        normPts[6] = cx; normPts[7] = cy
                                        normPts[0] = cx; normPts[5] = cy
                                    }
                                }
                            } else {
                                normPts[activeCorner * 2] = nx
                                normPts[activeCorner * 2 + 1] = ny
                            }
                            invalidate()
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        activeCorner = -1
                        parent?.requestDisallowInterceptTouchEvent(false)
                        invalidate()
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
