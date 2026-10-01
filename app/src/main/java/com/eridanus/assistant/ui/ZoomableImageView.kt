package com.eridanus.assistant.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val currentMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    private var baseScale = 1.0f
    private val minRelativeScale = 1.0f
    private val maxRelativeScale = 5.0f
    private val doubleTapRelativeScale = 2.5f

    var onSingleTapListener: (() -> Unit)? = null

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    init {
        scaleType = ScaleType.MATRIX

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val currentRel = getRelativeScale()
                if (currentRel <= 0f) return true

                val targetRel = (currentRel * factor).coerceIn(minRelativeScale * 0.8f, maxRelativeScale * 1.25f)
                val appliedFactor = targetRel / currentRel

                currentMatrix.postScale(appliedFactor, appliedFactor, detector.focusX, detector.focusY)
                checkBoundsAndCenter()
                imageMatrix = currentMatrix
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                val currentRel = getRelativeScale()
                if (currentRel < minRelativeScale) {
                    resetMatrix()
                } else if (currentRel > maxRelativeScale) {
                    val appliedFactor = maxRelativeScale / currentRel
                    currentMatrix.postScale(appliedFactor, appliedFactor, detector.focusX, detector.focusY)
                    checkBoundsAndCenter()
                    imageMatrix = currentMatrix
                }
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTapListener?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val currentRel = getRelativeScale()
                if (currentRel > minRelativeScale * 1.25f) {
                    resetMatrix()
                } else {
                    val appliedFactor = doubleTapRelativeScale / currentRel.coerceAtLeast(0.01f)
                    currentMatrix.postScale(appliedFactor, appliedFactor, e.x, e.y)
                    checkBoundsAndCenter()
                    imageMatrix = currentMatrix
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (getRelativeScale() > 1.01f) {
                    currentMatrix.postTranslate(-distanceX, -distanceY)
                    checkBoundsAndCenter()
                    imageMatrix = currentMatrix
                    return true
                }
                return false
            }
        })
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { resetMatrix() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetMatrix()
    }

    fun resetMatrix() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0f || dh <= 0f) return

        currentMatrix.reset()
        baseScale = minOf(vw / dw, vh / dh)
        val dx = (vw - dw * baseScale) / 2f
        val dy = (vh - dh * baseScale) / 2f

        currentMatrix.postScale(baseScale, baseScale)
        currentMatrix.postTranslate(dx, dy)
        imageMatrix = currentMatrix
    }

    private fun getScale(): Float {
        currentMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    fun getRelativeScale(): Float {
        val s = getScale()
        return if (baseScale > 0f) s / baseScale else 1.0f
    }

    private fun getDisplayRect(): RectF {
        val d = drawable ?: return RectF()
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        currentMatrix.mapRect(rect)
        return rect
    }

    private fun checkBoundsAndCenter() {
        val rect = getDisplayRect()
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        var deltaX = 0f
        var deltaY = 0f

        if (rect.width() <= vw) {
            deltaX = (vw - rect.width()) / 2f - rect.left
        } else {
            if (rect.left > 0f) {
                deltaX = -rect.left
            } else if (rect.right < vw) {
                deltaX = vw - rect.right
            }
        }

        if (rect.height() <= vh) {
            deltaY = (vh - rect.height()) / 2f - rect.top
        } else {
            if (rect.top > 0f) {
                deltaY = -rect.top
            } else if (rect.bottom < vh) {
                deltaY = vh - rect.bottom
            }
        }

        currentMatrix.postTranslate(deltaX, deltaY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        var handled = scaleDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled
        return handled || super.onTouchEvent(event)
    }
}
