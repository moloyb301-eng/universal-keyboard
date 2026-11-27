package com.universalkeyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.universalkeyboard.hid.Hid
import com.universalkeyboard.hid.HidController
import kotlin.math.hypot
import kotlin.math.max

/**
 * Touch surface that drives the host's mouse.
 *
 *  - 1 finger drag: move pointer        - tap: left click
 *  - 2 finger drag: scroll              - 2 finger tap: right click
 *  - double-tap and drag: click-drag    - 3 finger tap: middle click
 */
class TrackpadView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var pointerSpeed = 1.6f
    var scrollSpeed = 1f
    var naturalScroll = true

    /** Called after any click so typed-text tracking can reset. */
    var onClick: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private enum class Mode { IDLE, MOVE, SCROLL, LIFTING }

    private var mode = Mode.IDLE
    private var activeId = -1
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var maxPointers = 0
    private var travelled = 0f
    private var remX = 0f
    private var remY = 0f
    private var scrollAccX = 0f
    private var scrollAccY = 0f
    private var lastTapUp = 0L
    private var dragArmed = false
    private var dragging = false
    private var touching = false

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B1D22.toInt() }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF22252C.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2A2D35.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * density
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5C6270.toInt(); textSize = 18 * density; textAlign = Paint.Align.CENTER
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF454A55.toInt(); textSize = 12 * density; textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val hints = listOf(
        "1 finger: move  ·  tap: click",
        "2 fingers: scroll  ·  tap: right-click",
        "double-tap & drag: drag",
    )

    override fun onDraw(canvas: Canvas) {
        val r = 14 * density
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, if (touching) activePaint else bgPaint)
        rect.inset(borderPaint.strokeWidth / 2, borderPaint.strokeWidth / 2)
        canvas.drawRoundRect(rect, r, r, borderPaint)

        val cx = width / 2f
        var y = height / 2f - (hints.size * hintPaint.textSize) / 2f
        if (height > 160 * density) {
            canvas.drawText("Trackpad", cx, y - 8 * density, titlePaint)
            y += 12 * density
            for (h in hints) {
                canvas.drawText(h, cx, y, hintPaint)
                y += hintPaint.textSize * 1.5f
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                mode = Mode.MOVE
                activeId = e.getPointerId(0)
                lastX = e.x; lastY = e.y
                downTime = e.eventTime
                maxPointers = 1
                travelled = 0f
                remX = 0f; remY = 0f
                dragArmed = e.eventTime - lastTapUp < DOUBLE_TAP_MS
                touching = true
                invalidate()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = max(maxPointers, e.pointerCount)
                if (!dragging && e.pointerCount == 2) {
                    mode = Mode.SCROLL
                    lastX = centroidX(e); lastY = centroidY(e)
                    scrollAccX = 0f; scrollAccY = 0f
                }
            }

            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.MOVE -> {
                    val i = e.findPointerIndex(activeId)
                    if (i >= 0) {
                        val dx = e.getX(i) - lastX
                        val dy = e.getY(i) - lastY
                        lastX = e.getX(i); lastY = e.getY(i)
                        travelled += hypot(dx, dy)
                        if (dragArmed && !dragging && travelled > slop) {
                            dragging = true
                            HidController.setMouseButton(Hid.MOUSE_LEFT, true)
                        }
                        movePointer(dx, dy)
                    }
                }
                Mode.SCROLL -> if (e.pointerCount >= 2) {
                    val x = centroidX(e); val y = centroidY(e)
                    val dx = x - lastX; val dy = y - lastY
                    lastX = x; lastY = y
                    travelled += hypot(dx, dy)
                    scroll(dx, dy)
                }
                else -> Unit
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.SCROLL) {
                    mode = Mode.LIFTING // ignore the finger left behind
                } else if (mode == Mode.MOVE && e.getPointerId(e.actionIndex) == activeId) {
                    val next = if (e.actionIndex == 0) 1 else 0
                    activeId = e.getPointerId(next)
                    lastX = e.getX(next); lastY = e.getY(next)
                }
            }

            MotionEvent.ACTION_UP -> {
                val quickTap = travelled < slop && e.eventTime - downTime < TAP_MS
                lastTapUp = 0L
                if (dragging) {
                    dragging = false
                    HidController.setMouseButton(Hid.MOUSE_LEFT, false)
                } else if (quickTap) {
                    when {
                        maxPointers >= 3 -> HidController.click(Hid.MOUSE_MIDDLE)
                        maxPointers == 2 -> HidController.click(Hid.MOUSE_RIGHT)
                        else -> {
                            HidController.click(Hid.MOUSE_LEFT)
                            lastTapUp = e.eventTime
                        }
                    }
                    onClick?.invoke()
                }
                endGesture()
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    HidController.setMouseButton(Hid.MOUSE_LEFT, false)
                }
                endGesture()
            }
        }
        return true
    }

    private fun endGesture() {
        mode = Mode.IDLE
        dragArmed = false
        touching = false
        invalidate()
    }

    private fun movePointer(dxPx: Float, dyPx: Float) {
        val dx = dxPx / density
        val dy = dyPx / density
        // Gentle acceleration: slow moves stay precise, fast flicks cross the screen.
        val accel = 1f + ((hypot(dx, dy) - 1.5f) / 6f).coerceIn(0f, 1.5f)
        remX += dx * pointerSpeed * accel
        remY += dy * pointerSpeed * accel
        val ix = remX.toInt()
        val iy = remY.toInt()
        remX -= ix; remY -= iy
        if (ix != 0 || iy != 0) HidController.moveMouse(ix, iy)
    }

    private fun scroll(dxPx: Float, dyPx: Float) {
        val step = SCROLL_STEP_DP / scrollSpeed
        scrollAccX += dxPx / density
        scrollAccY += dyPx / density
        val notchesY = (scrollAccY / step).toInt()
        val notchesX = (scrollAccX / step).toInt()
        scrollAccY -= notchesY * step
        scrollAccX -= notchesX * step
        if (notchesX == 0 && notchesY == 0) return
        // Natural: content follows the fingers (fingers down => wheel up).
        val wheel = if (naturalScroll) notchesY else -notchesY
        val pan = if (naturalScroll) -notchesX else notchesX
        HidController.scroll(wheel, pan)
    }

    private fun centroidX(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getX(i)
        return s / e.pointerCount
    }

    private fun centroidY(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getY(i)
        return s / e.pointerCount
    }

    private companion object {
        const val TAP_MS = 220L
        const val DOUBLE_TAP_MS = 300L
        const val SCROLL_STEP_DP = 14f
    }
}
