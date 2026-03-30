/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package io.github.proify.lyricon.lyric.view.line

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.text.TextPaint
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.View
import androidx.core.view.isVisible
import io.github.proify.lyricon.lyric.model.LyricLine
import io.github.proify.lyricon.lyric.view.LyricLineConfig
import io.github.proify.lyricon.lyric.view.UpdatableColor
import io.github.proify.lyricon.lyric.view.dp
import io.github.proify.lyricon.lyric.view.line.model.LyricModel
import io.github.proify.lyricon.lyric.view.line.model.createModel
import io.github.proify.lyricon.lyric.view.line.model.emptyLyricModel
import io.github.proify.lyricon.lyric.view.sp
import java.lang.ref.WeakReference

open class LyricLineView(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs), UpdatableColor {

    companion object {
        private const val TAG = "LyricLineView"
        private const val DEBUG = false
    }

    init {
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(10.dp)
        // 关键修复：禁用自身及子视图的裁剪
    }

    val textPaint: TextPaint = TextPaintX().apply {
        textSize = 24f.sp
    }
    private var currentTextColors: IntArray = intArrayOf(Color.BLACK)

    var lyric: LyricModel = emptyLyricModel()
        private set

    var scrollXOffset: Float = 0f

    var isScrollFinished: Boolean = false
    val marquee: Marquee = Marquee(WeakReference(this))
    val syllable: Syllable = Syllable(this)
    private val animationDriver = AnimationDriver()

    // 安全余量：增加 1 像素防止测量误差导致的裁剪
    val lyricWidth: Float get() = lyric.width + 1.0f

    fun reset() {
        animationDriver.stop()
        marquee.reset()
        syllable.reset()
        scrollXOffset = 0f
        isScrollFinished = false
        lyric = emptyLyricModel()
        refreshModelSizes()
        invalidate()
    }

    val textSize: Float get() = textPaint.textSize

    fun setTextSize(size: Float) {
        val needUpdate = textPaint.textSize != size
                || syllable.textSize != size

        if (!needUpdate) return

        textPaint.textSize = size
        syllable.setTextSize(size)

        refreshModelSizes()
        invalidate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) syllable.reLayout()
    }

    fun reLayout() {
        if (isSyllableMode()) syllable.reLayout()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        reset()
    }

    override fun updateColor(primary: IntArray, background: IntArray, highlight: IntArray) {
        currentTextColors = primary.copyOf()
        applyCurrentTextColor()
        syllable.setColor(background, highlight)
        invalidate()

        if (DEBUG) Log.d(TAG, "updateColor: $primary, $background, $highlight")
    }

    fun setStyle(configs: LyricLineConfig) {
        val textConfig = configs.text
        val marqueeConfig = configs.marquee
        val syllableConfig = configs.syllable

        textPaint.apply {
            textSize = textConfig.textSize
            typeface = textConfig.typeface
        }
        currentTextColors = textConfig.textColor.copyOf()

        syllable.setColor(
            syllableConfig.backgroundColor,
            syllableConfig.highlightColor
        )
        syllable.setTextSize(textConfig.textSize)
        syllable.setTypeface(textConfig.typeface)
        syllable.isGradientEnabled = configs.gradientProgressStyle
        syllable.isSustainLiftEnabled = syllableConfig.enableSustainLift
        syllable.isSustainGlowEnabled = syllableConfig.enableSustainGlow

        marquee.apply {
            ghostSpacing = marqueeConfig.ghostSpacing
            scrollSpeed = marqueeConfig.scrollSpeed
            initialDelayMs = marqueeConfig.initialDelay
            loopDelayMs = marqueeConfig.loopDelay
            repeatCount = marqueeConfig.repeatCount
            stopAtEnd = marqueeConfig.stopAtEnd
        }

        if (configs.fadingEdgeLength <= 0) {
            setFadingEdgeLength(0)
            isHorizontalFadingEdgeEnabled = false
        } else {
            setFadingEdgeLength(configs.fadingEdgeLength)
            isHorizontalFadingEdgeEnabled = true
        }

        refreshModelSizes()

        animationDriver.stop()
        animationDriver.startIfNoRunning()
        invalidate()
    }

    fun isSyllableMode(): Boolean = !isMarqueeMode()

    fun seekTo(position: Long) {
        if (isSyllableMode()) {
            syllable.seek(position)
            animationDriver.startIfNoRunning()
        }
    }

    fun setPosition(position: Long) {
        if (isSyllableMode()) {
            if (syllable.isScrollOnly && !isOverflow()) {
                return
            }
            syllable.updateProgress(position)

            if (syllable.isPlaying && !syllable.isFinished) {
                animationDriver.startIfNoRunning()
            }
        }
    }

    fun refreshModelSizes() {
        lyric.updateSizes(textPaint)
        applyCurrentTextColor()
    }

    override fun getLeftFadingEdgeStrength(): Float {
        if (lyricWidth <= width || horizontalFadingEdgeLength <= 0) return 0f

        val offsetInUnit = if (isMarqueeMode()) {
            marquee.currentUnitOffset
        } else {
            -scrollXOffset
        }

        if (offsetInUnit <= 0f) return 0f

        if (isMarqueeMode() && offsetInUnit > lyricWidth) {
            return 0f
        }

        val edgeL = horizontalFadingEdgeLength.toFloat()
        return (offsetInUnit / edgeL).coerceIn(0f, 1f)
    }

    override fun getRightFadingEdgeStrength(): Float {
        if (lyricWidth <= width || horizontalFadingEdgeLength <= 0) return 0f

        val viewW = width.toFloat()
        val edgeL = horizontalFadingEdgeLength.toFloat()

        if (isMarqueeMode()) {
            if (isScrollFinished) {
                val remaining = lyricWidth + scrollXOffset - viewW
                return (remaining / edgeL).coerceIn(0f, 1f)
            }

            val offsetInUnit = marquee.currentUnitOffset
            val primaryRightEdge = lyricWidth - offsetInUnit
            val ghostLeftEdge = primaryRightEdge + marquee.ghostSpacing

            return if (primaryRightEdge < viewW && ghostLeftEdge > viewW) {
                0f
            } else {
                1.0f
            }
        } else if (isSyllableMode()) {
            if (isPlayFinished()) {
                return 0f
            }
        }

        val remaining = lyricWidth + scrollXOffset - viewW
        return (remaining / edgeL).coerceIn(0f, 1f)
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val textHeight = (textPaint.descent() - textPaint.ascent()).toInt()
        setMeasuredDimension(w, resolveSize(textHeight, hSpec))
    }

    fun setLyric(line: LyricLine?) {
        reset()
        lyric = line?.normalize()?.createModel() ?: emptyLyricModel()

        refreshModelSizes()
        invalidate()
    }

    fun startMarquee() {
        if (isMarqueeMode()) {
            scrollXOffset = 0f
            post {
                marquee.start()
                animationDriver.stop()
                animationDriver.startIfNoRunning()
            }
        }
    }

    fun isMarqueeMode(): Boolean = lyric.isPlainText
    fun isOverflow(): Boolean = lyricWidth > measuredWidth

    override fun onDraw(canvas: Canvas) {
        if (isMarqueeMode()) marquee.draw(canvas) else syllable.draw(canvas)
    }

    fun isPlayStarted(): Boolean = if (isMarqueeMode()) {
        true
    } else {
        syllable.isStarted
    }

    fun isPlaying(): Boolean = if (isMarqueeMode()) {
        !marquee.isAnimationFinished()
    } else {
        syllable.isPlaying
    }

    fun isPlayFinished(): Boolean = if (isMarqueeMode()) {
        marquee.isAnimationFinished()
    } else {
        (syllable.lastPosition >= lyric.end)
                || syllable.isFinished
    }

    /**
     * 强制当前行的高亮宽度为极大值，使其直接绘制整行（避免裁剪）
     */
    fun forceFullHighlight() {
        syllable.renderDelegate.onHighlightUpdate(Float.MAX_VALUE)
        invalidate()
    }

    override fun toString(): String {
        return "LyricLineView{lyric=$lyric,isVisible=$isVisible, lyricWidth=$lyricWidth, lyricHeight=$measuredHeight, isGradientEnabled=${syllable.isGradientEnabled}, scrollXOffset=$scrollXOffset, isScrollFinished=$isScrollFinished, isPlayFinished=${isPlayFinished()}, isPlaying=${isPlaying()}, isPlayStarted=${isPlayStarted()}, isOverflow=${isOverflow()}, isMarqueeMode=${isMarqueeMode()}, isSyllableMode=${isSyllableMode()}, isAttachedToWindow=$isAttachedToWindow"
    }

    internal inner class AnimationDriver : Choreographer.FrameCallback {
        private var running = false
        private var lastFrameNanos = 0L

        fun startIfNoRunning() {
            if (!running && isAttachedToWindow) {
                running = true
                lastFrameNanos = 0L
                post {
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }

        fun stop() {
            running = false
            Choreographer.getInstance().removeFrameCallback(this)
            lastFrameNanos = 0L
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!running || !isAttachedToWindow) {
                running = false
                return
            }

            val deltaNanos = if (lastFrameNanos == 0L) 0L else frameTimeNanos - lastFrameNanos
            lastFrameNanos = frameTimeNanos

            val isMarqueeMode = isMarqueeMode()
            val isOverflow = isOverflow()
            val isSyllableMode = isSyllableMode()

            if (isMarqueeMode) {
                if (!isOverflow || marquee.isAnimationFinished()) {
                    marquee.step(deltaNanos)
                    postInvalidateOnAnimation()
                    return
                }
            }

            if (isSyllableMode) {
                if (isPlayFinished() || (syllable.isScrollOnly && !isOverflow)) {
                    syllable.onFrameUpdate(frameTimeNanos)
                    postInvalidateOnAnimation()
                    return
                }
            }

            var hasChanged = false
            if (isMarqueeMode) {
                marquee.step(deltaNanos)
                hasChanged = true
            } else if (isSyllableMode) {
                hasChanged = syllable.onFrameUpdate(frameTimeNanos)
            }

            if (hasChanged) postInvalidateOnAnimation()

            if (running) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    private var cachedShader: Shader? = null
    private var cachedShaderSignature: Int = 0

    private fun applyCurrentTextColor() {
        val colors = currentTextColors
        if (colors.isEmpty()) {
            textPaint.color = Color.BLACK
            textPaint.shader = null
            return
        }
        textPaint.color = colors.firstOrNull() ?: Color.BLACK
        textPaint.shader = if (colors.size > 1 && lyricWidth > 0f) {
            getRainbowShader(lyricWidth, colors)
        } else {
            null
        }
    }

    private fun getRainbowShader(lyricWidth: Float, colors: IntArray): Shader {
        var sign = 17
        sign = sign * 31 + lyricWidth.hashCode()
        sign = sign * 31 + colors.contentHashCode()

        val shaderCache = cachedShader
        if (shaderCache != null && cachedShaderSignature == sign) {
            return shaderCache
        }
        cachedShaderSignature = sign

        val positions = FloatArray(colors.size) { i ->
            i.toFloat() / (colors.size - 1)
        }
        val shader = LinearGradient(
            0f, 0f, lyricWidth, 0f,
            colors, positions,
            Shader.TileMode.CLAMP
        )
        cachedShader = shader
        return shader
    }
}