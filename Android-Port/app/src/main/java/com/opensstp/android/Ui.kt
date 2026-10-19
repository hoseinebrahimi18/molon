package com.opensstp.android

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.animation.LinearInterpolator

/** Colors + small drawable helpers shared by the whole UI. */
object Ui {
    val BG0 = Color.parseColor("#060A13")
    val BG1 = Color.parseColor("#0C1424")
    val CARD = Color.parseColor("#111A2C")
    val FIELD = Color.parseColor("#0E1626")
    val CARD_HI = Color.parseColor("#16213A")
    val STROKE = Color.parseColor("#1F2B46")
    val TEXT = Color.parseColor("#F2F5FB")
    val TEXT2 = Color.parseColor("#93A1BC")
    val MUTED = Color.parseColor("#5F6E8A")
    val IDLE = Color.parseColor("#6B7FA3")
    val ACCENT = Color.parseColor("#3B82F6")
    val ACCENT2 = Color.parseColor("#7C5CFF")
    val ACCENT_SOFT = Color.parseColor("#7FB0FF")
    val GREEN = Color.parseColor("#2EE6A6")
    val CYAN = Color.parseColor("#4DD0FF")
    val RED = Color.parseColor("#FF6B7A")
    val AMBER = Color.parseColor("#FFBE5A")
    val DANGER_A = Color.parseColor("#E5484D")
    val DANGER_B = Color.parseColor("#FF7A59")
    val CORE_TOP = Color.parseColor("#1E2B49")
    val CORE_BOTTOM = Color.parseColor("#0E1626")

    fun alpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    private fun density(ctx: Context) = ctx.resources.displayMetrics.density

    fun shape(ctx: Context, fill: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Float = 0f): GradientDrawable {
        val d = density(ctx)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radiusDp * d
            if (strokeDp > 0f) setStroke(maxOf(1, (strokeDp * d).toInt()), strokeColor)
        }
    }

    fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    fun gradient(
        ctx: Context,
        from: Int,
        to: Int,
        radiusDp: Float,
        orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.LEFT_RIGHT
    ): GradientDrawable = GradientDrawable(orientation, intArrayOf(from, to)).apply {
        cornerRadius = radiusDp * density(ctx)
    }

    fun ripple(ctx: Context, content: Drawable, radiusDp: Float, rippleColor: Int = 0x33FFFFFF): RippleDrawable {
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = radiusDp * density(ctx)
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }
}

enum class IconKind {
    PLUS, CLOSE, ARROW_DOWN, ARROW_UP, SERVER, USER, LOCK, EYE, EYE_OFF, SHIELD,
    MENU, SAVE, CLIPBOARD, SHARE, KEY, BATTERY, TRASH, INFO, HELP, EXIT, CHEVRON
}

/** Tiny vector icon set drawn on a 24x24 grid, so no drawable resources are needed. */
class IconView(context: Context, private var kind: IconKind, private var tint: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()

    fun applyTint(color: Int) {
        tint = color
        invalidate()
    }

    fun changeKind(newKind: IconKind) {
        kind = newKind
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        if (s <= 0f) return
        canvas.save()
        canvas.translate((width - s) / 2f, (height - s) / 2f)
        canvas.scale(s / 24f, s / 24f)
        paint.color = tint
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        when (kind) {
            IconKind.PLUS -> {
                canvas.drawLine(12f, 5f, 12f, 19f, paint)
                canvas.drawLine(5f, 12f, 19f, 12f, paint)
            }
            IconKind.CLOSE -> {
                canvas.drawLine(6f, 6f, 18f, 18f, paint)
                canvas.drawLine(18f, 6f, 6f, 18f, paint)
            }
            IconKind.ARROW_DOWN -> {
                canvas.drawLine(12f, 5f, 12f, 19f, paint)
                val p = Path()
                p.moveTo(6f, 13f); p.lineTo(12f, 19f); p.lineTo(18f, 13f)
                canvas.drawPath(p, paint)
            }
            IconKind.ARROW_UP -> {
                canvas.drawLine(12f, 19f, 12f, 5f, paint)
                val p = Path()
                p.moveTo(6f, 11f); p.lineTo(12f, 5f); p.lineTo(18f, 11f)
                canvas.drawPath(p, paint)
            }
            IconKind.SERVER -> {
                rect.set(4f, 4f, 20f, 10f); canvas.drawRoundRect(rect, 2.5f, 2.5f, paint)
                rect.set(4f, 14f, 20f, 20f); canvas.drawRoundRect(rect, 2.5f, 2.5f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(8f, 7f, 1f, paint)
                canvas.drawCircle(8f, 17f, 1f, paint)
            }
            IconKind.USER -> {
                canvas.drawCircle(12f, 8f, 4f, paint)
                rect.set(4f, 13f, 20f, 29f)
                canvas.drawArc(rect, 180f, 180f, false, paint)
            }
            IconKind.LOCK -> {
                rect.set(5f, 11f, 19f, 21f); canvas.drawRoundRect(rect, 2.5f, 2.5f, paint)
                rect.set(8f, 4f, 16f, 12f); canvas.drawArc(rect, 180f, 180f, false, paint)
                canvas.drawLine(8f, 8f, 8f, 11f, paint)
                canvas.drawLine(16f, 8f, 16f, 11f, paint)
            }
            IconKind.EYE, IconKind.EYE_OFF -> {
                val p = Path()
                p.moveTo(2f, 12f); p.quadTo(12f, 3f, 22f, 12f); p.quadTo(12f, 21f, 2f, 12f)
                canvas.drawPath(p, paint)
                canvas.drawCircle(12f, 12f, 3f, paint)
                if (kind == IconKind.EYE_OFF) canvas.drawLine(4f, 4f, 20f, 20f, paint)
            }
            IconKind.MENU -> {
                canvas.drawLine(4f, 6f, 20f, 6f, paint)
                canvas.drawLine(10f, 12f, 20f, 12f, paint)
                canvas.drawLine(4f, 18f, 20f, 18f, paint)
            }
            IconKind.SAVE -> {
                rect.set(4f, 4f, 20f, 20f); canvas.drawRoundRect(rect, 2.5f, 2.5f, paint)
                rect.set(8f, 4f, 16f, 9f); canvas.drawRect(rect, paint)
                rect.set(7f, 14f, 17f, 20f); canvas.drawRect(rect, paint)
            }
            IconKind.CLIPBOARD -> {
                rect.set(5f, 5f, 19f, 21f); canvas.drawRoundRect(rect, 2.5f, 2.5f, paint)
                rect.set(9f, 3f, 15f, 7f); canvas.drawRoundRect(rect, 1.5f, 1.5f, paint)
                canvas.drawLine(9f, 12f, 15f, 12f, paint)
                canvas.drawLine(9f, 16f, 13f, 16f, paint)
            }
            IconKind.SHARE -> {
                canvas.drawCircle(6f, 12f, 2.5f, paint)
                canvas.drawCircle(18f, 6f, 2.5f, paint)
                canvas.drawCircle(18f, 18f, 2.5f, paint)
                canvas.drawLine(8.2f, 10.8f, 15.8f, 7.2f, paint)
                canvas.drawLine(8.2f, 13.2f, 15.8f, 16.8f, paint)
            }
            IconKind.KEY -> {
                canvas.drawCircle(8f, 15f, 4f, paint)
                canvas.drawLine(11f, 12f, 20f, 3f, paint)
                canvas.drawLine(16f, 7f, 19f, 10f, paint)
            }
            IconKind.BATTERY -> {
                rect.set(3f, 7f, 19f, 17f); canvas.drawRoundRect(rect, 2.5f, 2.5f, paint)
                canvas.drawLine(21.5f, 10.5f, 21.5f, 13.5f, paint)
                paint.style = Paint.Style.FILL
                rect.set(5.5f, 9.5f, 11f, 14.5f); canvas.drawRoundRect(rect, 1f, 1f, paint)
            }
            IconKind.TRASH -> {
                canvas.drawLine(4f, 7f, 20f, 7f, paint)
                canvas.drawLine(9f, 4f, 15f, 4f, paint)
                val p = Path()
                p.moveTo(6f, 7f); p.lineTo(7f, 20f); p.lineTo(17f, 20f); p.lineTo(18f, 7f)
                canvas.drawPath(p, paint)
                canvas.drawLine(10f, 11f, 10f, 16f, paint)
                canvas.drawLine(14f, 11f, 14f, 16f, paint)
            }
            IconKind.INFO -> {
                canvas.drawCircle(12f, 12f, 9f, paint)
                canvas.drawLine(12f, 11f, 12f, 17f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(12f, 7.5f, 1.2f, paint)
            }
            IconKind.HELP -> {
                canvas.drawCircle(12f, 12f, 9f, paint)
                rect.set(9f, 7f, 15f, 13f); canvas.drawArc(rect, 180f, 220f, false, paint)
                canvas.drawLine(12f, 13f, 12f, 14.5f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(12f, 17.5f, 1.2f, paint)
            }
            IconKind.EXIT -> {
                val p = Path()
                p.moveTo(10f, 4f); p.lineTo(5f, 4f); p.lineTo(5f, 20f); p.lineTo(10f, 20f)
                canvas.drawPath(p, paint)
                canvas.drawLine(9f, 12f, 20f, 12f, paint)
                val head = Path()
                head.moveTo(16f, 8f); head.lineTo(20f, 12f); head.lineTo(16f, 16f)
                canvas.drawPath(head, paint)
            }
            IconKind.CHEVRON -> {
                val p = Path()
                p.moveTo(15f, 6f); p.lineTo(9f, 12f); p.lineTo(15f, 18f)
                canvas.drawPath(p, paint)
            }
            IconKind.SHIELD -> {
                val p = Path()
                p.moveTo(12f, 3f); p.lineTo(19f, 6f); p.lineTo(19f, 12f)
                p.cubicTo(19f, 16.5f, 16f, 19.5f, 12f, 21f)
                p.cubicTo(8f, 19.5f, 5f, 16.5f, 5f, 12f)
                p.lineTo(5f, 6f); p.close()
                canvas.drawPath(p, paint)
                val tick = Path()
                tick.moveTo(9f, 12f); tick.lineTo(11f, 14f); tick.lineTo(15f, 10f)
                canvas.drawPath(tick, paint)
            }
        }
        canvas.restore()
    }
}

/**
 * The hero button: a glowing orb with a power icon.
 * idle = calm slate, connecting = rotating cyan arc + ripples, connected = green glow + ripples, error = red.
 */
class PowerOrbView(context: Context) : View(context) {
    private var state: String = SoftEtherVpnService.STATE_DISCONNECTED
    private var accent: Int = Ui.IDLE
    private var phase = 0f
    private var attached = false

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    fun setState(newState: String) {
        state = newState
        accent = when (newState) {
            SoftEtherVpnService.STATE_CONNECTED -> Ui.GREEN
            SoftEtherVpnService.STATE_CONNECTING -> Ui.CYAN
            SoftEtherVpnService.STATE_ERROR -> Ui.RED
            else -> Ui.IDLE
        }
        rebuildShaders()
        syncAnimation()
        invalidate()
    }

    private fun isLive() =
        state == SoftEtherVpnService.STATE_CONNECTED || state == SoftEtherVpnService.STATE_CONNECTING

    private fun syncAnimation() {
        if (attached && isLive()) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
    }

    private fun rebuildShaders() {
        if (width == 0 || height == 0) return
        val u = minOf(width, height) / 220f
        val cx = width / 2f
        val cy = height / 2f
        val glowAlpha = when {
            isLive() -> 95
            state == SoftEtherVpnService.STATE_ERROR -> 60
            else -> 26
        }
        glowPaint.shader = RadialGradient(
            cx, cy, 110f * u,
            intArrayOf(Ui.alpha(accent, glowAlpha), Ui.alpha(accent, 0)),
            floatArrayOf(0.35f, 1f),
            Shader.TileMode.CLAMP
        )
        corePaint.shader = LinearGradient(
            cx, cy - 64f * u, cx, cy + 64f * u,
            Ui.CORE_TOP, Ui.CORE_BOTTOM,
            Shader.TileMode.CLAMP
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShaders()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        syncAnimation()
    }

    override fun onDetachedFromWindow() {
        attached = false
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val u = minOf(width, height) / 220f
        if (u <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val live = isLive()

        if (live) {
            for (i in 0..1) {
                val p = (phase + i * 0.5f) % 1f
                strokePaint.color = accent
                strokePaint.alpha = (110f * (1f - p)).toInt()
                strokePaint.strokeWidth = 2f * u
                canvas.drawCircle(cx, cy, (66f + 44f * p) * u, strokePaint)
            }
        }

        canvas.drawCircle(cx, cy, 110f * u, glowPaint)
        canvas.drawCircle(cx, cy, 64f * u, corePaint)

        strokePaint.color = Ui.alpha(accent, if (live) 230 else 140)
        strokePaint.strokeWidth = 2f * u
        canvas.drawCircle(cx, cy, 64f * u, strokePaint)

        rect.set(cx - 76f * u, cy - 76f * u, cx + 76f * u, cy + 76f * u)
        when (state) {
            SoftEtherVpnService.STATE_CONNECTING -> {
                strokePaint.color = Ui.alpha(accent, 50)
                strokePaint.strokeWidth = 3f * u
                canvas.drawCircle(cx, cy, 76f * u, strokePaint)
                strokePaint.color = accent
                strokePaint.strokeWidth = 4f * u
                canvas.drawArc(rect, phase * 360f - 90f, 100f, false, strokePaint)
            }
            SoftEtherVpnService.STATE_CONNECTED -> {
                strokePaint.color = Ui.alpha(accent, 210)
                strokePaint.strokeWidth = 3.5f * u
                canvas.drawCircle(cx, cy, 76f * u, strokePaint)
            }
            SoftEtherVpnService.STATE_ERROR -> {
                strokePaint.color = Ui.alpha(accent, 150)
                strokePaint.strokeWidth = 3f * u
                canvas.drawCircle(cx, cy, 76f * u, strokePaint)
            }
            else -> {
                strokePaint.color = Ui.alpha(accent, 70)
                strokePaint.strokeWidth = 1.5f * u
                canvas.drawCircle(cx, cy, 76f * u, strokePaint)
            }
        }

        // Power glyph
        strokePaint.color = if (state == SoftEtherVpnService.STATE_DISCONNECTED) Ui.TEXT2 else accent
        strokePaint.strokeWidth = 5f * u
        rect.set(cx - 21f * u, cy - 21f * u, cx + 21f * u, cy + 21f * u)
        canvas.drawArc(rect, -55f, 290f, false, strokePaint)
        canvas.drawLine(cx, cy - 30f * u, cx, cy - 7f * u, strokePaint)
    }
}
