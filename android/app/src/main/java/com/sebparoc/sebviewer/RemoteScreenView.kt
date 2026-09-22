package com.sebparoc.sebviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the remote screen and turns touch gestures into pointer events.
 *
 * Gestures:
 *  - tap: left click            - long press: right click
 *  - 1 finger drag: move pointer (or press-drag in drag mode, relative in trackpad mode)
 *  - 2 fingers drag: scroll     - 2 finger tap: right click
 *  - pinch: zoom                - 3 fingers drag: pan the zoomed view
 */
class RemoteScreenView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface InputListener {
        fun onMove(nx: Float, ny: Float)
        fun onClick(button: Int, nx: Float, ny: Float)
        fun onButton(button: Int, down: Boolean, nx: Float, ny: Float)
        fun onScroll(dx: Int, dy: Int)
    }

    var inputListener: InputListener? = null
    var dragMode = false
    var trackpadMode = false

    private var bitmap: Bitmap? = null
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var fitScale = 1f
    private var bmpW = 0
    private var bmpH = 0

    // pointer position in normalised remote coordinates (used in trackpad mode)
    private var cursorX = 0.5f
    private var cursorY = 0.5f

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val density = resources.displayMetrics.density
    private val scrollStepPx = 36f * density

    private enum class Mode { NONE, SINGLE, SINGLE_DRAG, TWO_UNDECIDED, ZOOM, SCROLL, PAN, DONE }
    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var startSpan = 0f
    private var focusX = 0f
    private var focusY = 0f
    private var startFocusX = 0f
    private var startFocusY = 0f
    private var twoDownTime = 0L
    private var scrollAccX = 0f
    private var scrollAccY = 0f
    private var longPressFired = false
    private var lastMoveSent = 0L
    private val longPress = Runnable { onLongPress() }

    fun setFrame(bmp: Bitmap) {
        val sizeChanged = bmp.width != bmpW || bmp.height != bmpH
        bitmap = bmp
        bmpW = bmp.width
        bmpH = bmp.height
        if (sizeChanged) fitToView()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToView()
    }

    fun fitToView() {
        if (bmpW == 0 || bmpH == 0 || width == 0 || height == 0) return
        fitScale = min(width.toFloat() / bmpW, height.toFloat() / bmpH)
        scale = fitScale
        tx = (width - bmpW * scale) / 2f
        ty = (height - bmpH * scale) / 2f
        invalidate()
    }

    private fun clampTransform() {
        scale = scale.coerceIn(fitScale, fitScale * 8f)
        val sw = bmpW * scale
        val sh = bmpH * scale
        tx = if (sw <= width) (width - sw) / 2f else tx.coerceIn(width - sw, 0f)
        ty = if (sh <= height) (height - sh) / 2f else ty.coerceIn(height - sh, 0f)
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(tx, ty)
        canvas.drawBitmap(bmp, matrix, paint)
    }

    // ------------------------------------------------------------- mapping
    private fun toNormX(px: Float) = ((px - tx) / (scale * max(1, bmpW))).coerceIn(0f, 1f)
    private fun toNormY(py: Float) = ((py - ty) / (scale * max(1, bmpH))).coerceIn(0f, 1f)

    /** Where a touch at (px,py) points to, honouring trackpad mode. */
    private fun targetFor(px: Float, py: Float): Pair<Float, Float> {
        if (trackpadMode) return cursorX to cursorY
        cursorX = toNormX(px)
        cursorY = toNormY(py)
        return cursorX to cursorY
    }

    private fun sendMove(nx: Float, ny: Float, force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastMoveSent < 16) return
        lastMoveSent = now
        inputListener?.onMove(nx, ny)
    }

    // -------------------------------------------------------------- touch
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                mode = Mode.SINGLE
                longPressFired = false
                downX = e.x; downY = e.y
                lastX = e.x; lastY = e.y
                postDelayed(longPress, LONG_PRESS_MS)
                if (dragMode) {
                    val (nx, ny) = targetFor(e.x, e.y)
                    inputListener?.onButton(1, true, nx, ny)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress)
                if (mode == Mode.SINGLE_DRAG && dragMode) {
                    inputListener?.onButton(1, false, cursorX, cursorY)
                }
                when (e.pointerCount) {
                    2 -> {
                        mode = Mode.TWO_UNDECIDED
                        twoDownTime = SystemClock.uptimeMillis()
                        startSpan = span(e); lastSpan = startSpan
                        focusX = focus(e, true); focusY = focus(e, false)
                        startFocusX = focusX; startFocusY = focusY
                        scrollAccX = 0f; scrollAccY = 0f
                    }
                    else -> {
                        mode = Mode.PAN
                        focusX = focus(e, true); focusY = focus(e, false)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.TWO_UNDECIDED &&
                    SystemClock.uptimeMillis() - twoDownTime < TWO_FINGER_TAP_MS) {
                    val (nx, ny) = targetFor(startFocusX, startFocusY)
                    inputListener?.onClick(3, nx, ny)
                }
                if (mode != Mode.PAN || e.pointerCount <= 3) mode = Mode.DONE
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                when (mode) {
                    Mode.SINGLE -> if (!longPressFired) {
                        val (nx, ny) = targetFor(e.x, e.y)
                        if (dragMode) inputListener?.onButton(1, false, nx, ny)
                        else inputListener?.onClick(1, nx, ny)
                    } else if (dragMode) {
                        inputListener?.onButton(1, false, cursorX, cursorY)
                    }
                    Mode.SINGLE_DRAG -> if (dragMode) inputListener?.onButton(1, false, cursorX, cursorY)
                    else -> {}
                }
                mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragMode && (mode == Mode.SINGLE || mode == Mode.SINGLE_DRAG)) {
                    inputListener?.onButton(1, false, cursorX, cursorY)
                }
                mode = Mode.NONE
            }
        }
        return true
    }

    private fun onMove(e: MotionEvent) {
        when (mode) {
            Mode.SINGLE, Mode.SINGLE_DRAG -> {
                val dx = e.x - lastX
                val dy = e.y - lastY
                if (mode == Mode.SINGLE && hypot(e.x - downX, e.y - downY) > touchSlop) {
                    removeCallbacks(longPress)
                    if (longPressFired) return
                    mode = Mode.SINGLE_DRAG
                }
                if (mode == Mode.SINGLE_DRAG) {
                    if (trackpadMode) {
                        cursorX = (cursorX + dx / (scale * max(1, bmpW)) * TRACKPAD_GAIN).coerceIn(0f, 1f)
                        cursorY = (cursorY + dy / (scale * max(1, bmpH)) * TRACKPAD_GAIN).coerceIn(0f, 1f)
                    } else {
                        cursorX = toNormX(e.x); cursorY = toNormY(e.y)
                    }
                    sendMove(cursorX, cursorY)
                }
                lastX = e.x; lastY = e.y
            }
            Mode.TWO_UNDECIDED, Mode.ZOOM, Mode.SCROLL -> {
                if (e.pointerCount < 2) return
                val s = span(e)
                val fx = focus(e, true)
                val fy = focus(e, false)
                if (mode == Mode.TWO_UNDECIDED) {
                    if (abs(s - startSpan) > touchSlop * 2) mode = Mode.ZOOM
                    else if (hypot(fx - startFocusX, fy - startFocusY) > touchSlop) mode = Mode.SCROLL
                }
                if (mode == Mode.ZOOM) {
                    val factor = if (lastSpan > 0) s / lastSpan else 1f
                    val newScale = (scale * factor).coerceIn(fitScale, fitScale * 8f)
                    val real = newScale / scale
                    tx = fx - (fx - tx) * real + (fx - focusX)
                    ty = fy - (fy - ty) * real + (fy - focusY)
                    scale = newScale
                    clampTransform()
                    invalidate()
                } else if (mode == Mode.SCROLL) {
                    scrollAccX += fx - focusX
                    scrollAccY += fy - focusY
                    var stepsX = 0; var stepsY = 0
                    while (scrollAccY <= -scrollStepPx) { stepsY++; scrollAccY += scrollStepPx }
                    while (scrollAccY >= scrollStepPx) { stepsY--; scrollAccY -= scrollStepPx }
                    while (scrollAccX <= -scrollStepPx) { stepsX++; scrollAccX += scrollStepPx }
                    while (scrollAccX >= scrollStepPx) { stepsX--; scrollAccX -= scrollStepPx }
                    if (stepsX != 0 || stepsY != 0) inputListener?.onScroll(stepsX, stepsY)
                }
                lastSpan = s
                focusX = fx; focusY = fy
            }
            Mode.PAN -> {
                if (e.pointerCount < 3) return
                val fx = focus(e, true)
                val fy = focus(e, false)
                tx += fx - focusX
                ty += fy - focusY
                focusX = fx; focusY = fy
                clampTransform()
                invalidate()
            }
            else -> {}
        }
    }

    private fun onLongPress() {
        if (mode != Mode.SINGLE) return
        longPressFired = true
        if (dragMode) return // finger is already holding the button
        val (nx, ny) = targetFor(downX, downY)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        inputListener?.onClick(3, nx, ny)
    }

    private fun span(e: MotionEvent): Float =
        hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))

    private fun focus(e: MotionEvent, x: Boolean): Float {
        var sum = 0f
        val n = e.pointerCount
        for (i in 0 until n) sum += if (x) e.getX(i) else e.getY(i)
        return sum / n
    }

    companion object {
        private const val LONG_PRESS_MS = 450L
        private const val TWO_FINGER_TAP_MS = 250L
        private const val TRACKPAD_GAIN = 1.4f
    }
}
