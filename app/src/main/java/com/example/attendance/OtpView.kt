package com.example.attendance

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The verification-code scene, drawn as ONE view so the pieces can fly over each other:
 *
 *  - a padlock in a soft tile, the title, the sentence under it, and six boxes. Each typed digit pops into its box as a blue tile with a
 *    white digit, joined to its neighbour by a small bar.
 *  - the sixth digit: the tiles tilt, then slide together and melt into one blue ball ("checking").
 *  - good code ([success]): the ball turns green, squashes up to the padlock, the padlock opens and shrinks to a yellow dot, which turns into
 *    a green circle; the check mark is drawn and a seal bursts around it. Where the boxes were, a green check circle appears and
 *    "Verified and secure" fades in, and the sentence changes to "Your number has been verified." Then [onFinished] runs.
 *  - wrong code ([fail]): the ball turns red and shakes, then opens back into six empty boxes ([onRetry]).
 *
 * The typing itself happens in a normal (hidden) EditText: the screen feeds its text to [setDigits] and shows the keyboard on [onTap].
 */
class OtpView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    companion object {
        const val LENGTH = 6
        private const val SUBMIT_MS = 750L
        private const val SUCCESS_MS = 2600L
        private const val FAIL_MS = 700L
        private const val POP_MS = 260L
        private const val VERIFIED = "Your number has been verified."
    }

    /** Tapped while typing is allowed: show the keyboard. */
    var onTap: (() -> Unit)? = null
    /** All six digits are in: send the code to the server, then call [success] or [fail]. */
    var onComplete: ((String) -> Unit)? = null
    /** The success scene has played completely. */
    var onFinished: (() -> Unit)? = null
    /** The wrong-code scene has played: the boxes are empty again and typing may start. */
    var onRetry: (() -> Unit)? = null

    private enum class St { IDLE, SUBMIT, WAIT, SUCCESS, FAIL }

    private val d = ctx.resources.displayMetrics.density
    private var st = St.IDLE
    private var digits = ""
    private val popStart = LongArray(LENGTH) { -10000L }
    private var submitT = 0f
    private var succT = 0f
    private var failT = 0f
    private var pulse = 0f
    private var pending: Boolean? = null
    private var anim: ValueAnimator? = null
    private var pulseAnim: ValueAnimator? = null
    private var subtitle = ""
    private var subLayout: StaticLayout? = null
    private var okLayout: StaticLayout? = null
    private val submitRun = Runnable { startSubmit() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val digitPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true; color = 0xFFFFF1A6.toInt() }
    private val captionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val okPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val checkPath = Path()
    private val seg = Path()
    private val rect = RectF()

    init {
        isSaveEnabled = false
        titlePaint.textSize = sp(20f); digitPaint.textSize = sp(22f); captionPaint.textSize = sp(12f)
        subPaint.textSize = sp(14f); okPaint.textSize = sp(14f)
        checkPath.moveTo(-8f * d, 0f); checkPath.lineTo(-2.5f * d, 6f * d); checkPath.lineTo(8f * d, -6f * d)
        setOnClickListener { if (st == St.IDLE) onTap?.invoke() }
        contentDescription = "Verification code"
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    // ------------------------------------------------------------------ public API

    /** The sentence under the title (for example who the code was sent to). */
    fun setSubtitle(s: String) { subtitle = s; buildLayouts(); invalidate() }

    /** Typed so far (called from the hidden EditText's watcher). Ignored while a check is running. */
    fun setDigits(s: String) {
        if (st != St.IDLE) return
        val n = s.filter { it.isDigit() }.take(LENGTH)
        if (n == digits) return
        val now = SystemClock.uptimeMillis()
        for (i in digits.length until n.length) popStart[i] = now
        digits = n
        contentDescription = "Verification code, ${digits.length} of $LENGTH digits"
        removeCallbacks(submitRun)
        if (digits.length == LENGTH) { postDelayed(submitRun, POP_MS - 40); onComplete?.invoke(digits) }
        invalidate()
    }

    /** Back to six empty boxes and the padlock (new code requested, or "change my details"). */
    fun reset() {
        anim?.cancel(); removeCallbacks(submitRun)
        st = St.IDLE; digits = ""; pending = null; submitT = 0f; succT = 0f; failT = 0f
        contentDescription = "Verification code"
        invalidate()
    }

    /** The server accepted the code. */
    fun success() { if (st == St.WAIT) startSuccess() else if (st == St.IDLE || st == St.SUBMIT) pending = true }

    /** The server refused the code. */
    fun fail() { if (st == St.WAIT) startFail() else if (st == St.IDLE || st == St.SUBMIT) pending = false }

    // ------------------------------------------------------------------ animation phases

    private fun play(ms: Long, update: (Float) -> Unit, end: () -> Unit) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms; interpolator = LinearInterpolator()
            addUpdateListener { update(it.animatedValue as Float); invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) { if (!cancelled) end() }
            })
            start()
        }
    }

    private fun startSubmit() {
        if (st != St.IDLE || digits.length != LENGTH) return
        st = St.SUBMIT; submitT = 0f
        play(SUBMIT_MS, { submitT = it }) {
            st = St.WAIT
            when (pending) { true -> startSuccess(); false -> startFail(); else -> {} }
            pending = null
        }
    }

    private fun startSuccess() {
        st = St.SUCCESS; succT = 0f
        play(SUCCESS_MS, { succT = it }) { succT = 1f; onFinished?.invoke() }
    }

    private fun startFail() {
        st = St.FAIL; failT = 0f; digits = ""
        contentDescription = "Verification code"
        play(FAIL_MS, { failT = it }) { st = St.IDLE; failT = 0f; invalidate(); onRetry?.invoke() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { pulse = it.animatedValue as Float; if (st == St.IDLE || st == St.WAIT) invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        pulseAnim?.cancel(); anim?.cancel(); removeCallbacks(submitRun)
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize((300 * d).toInt(), widthMeasureSpec), (330 * d).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); buildLayouts() }

    private fun buildLayouts() {
        if (width <= 0) return
        val tw = (width - 32 * d).toInt().coerceAtLeast(1)
        fun mk(t: CharSequence, p: TextPaint) = StaticLayout.Builder.obtain(t, 0, t.length, p, tw)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.15f).build()
        subLayout = mk(subtitle, subPaint)
        okLayout = mk(VERIFIED, okPaint)
    }

    // ------------------------------------------------------------------ colours

    private fun ink() = Pal.c("#6B352A")
    private fun muted() = Pal.c("#8A5F52")
    private fun accent() = if (Pal.night) 0xFFFFE680.toInt() else 0xFF6B352A.toInt()
    private fun lineColor() = if (Pal.night) 0xFF6A453A.toInt() else 0xFFCDB88A.toInt()
    private fun green() = if (Pal.night) 0xFF22C55E.toInt() else 0xFF16A34A.toInt()
    // The "blue" of the original animation is Clay Brown here (a lighter clay at night so it stands out on the dark card).
    private val blue get() = if (Pal.night) 0xFF9A4B38.toInt() else 0xFF6B352A.toInt()
    private val blueTop get() = if (Pal.night) 0xFFB9644A.toInt() else 0xFF8A4A3A.toInt()
    private val blueBottom get() = if (Pal.night) 0xFF8A4333.toInt() else 0xFF5A2B21.toInt()
    private val yellow = 0xFFE8A800.toInt()
    private val red = 0xFFDC2626.toInt()

    private fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun m(x: Int, y: Int) = (x + (y - x) * k).toInt().coerceIn(0, 255)
        return (m(a ushr 24, b ushr 24) shl 24) or (m((a shr 16) and 255, (b shr 16) and 255) shl 16) or
            (m((a shr 8) and 255, (b shr 8) and 255) shl 8) or m(a and 255, b and 255)
    }

    private fun clamp01(x: Float) = x.coerceIn(0f, 1f)
    private fun inOut(x: Float): Float { val t = clamp01(x); return t * t * (3f - 2f * t) }
    private fun back(x: Float): Float { val t = clamp01(x) - 1f; return 1f + 2.70158f * t * t * t + 1.70158f * t * t }
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val cx = w / 2f
        val iconCy = 46 * d
        val rowY = 250 * d
        val ts = succT * SUCCESS_MS          // milliseconds into the success scene

        // padlock tile
        fill.shader = null; fill.style = Paint.Style.FILL; fill.color = Pal.c("#FFF1A6")
        rect.set(cx - 44 * d, iconCy - 44 * d, cx + 44 * d, iconCy + 44 * d)
        c.drawRoundRect(rect, 26 * d, 26 * d, fill)
        if (st == St.SUCCESS) drawIconSuccess(c, cx, iconCy, ts) else drawLock(c, cx, iconCy, 1f, 0f)

        // title and sentence
        titlePaint.color = ink()
        c.drawText("Verification code", cx, 128 * d, titlePaint)
        subPaint.color = muted(); okPaint.color = muted()
        val oldA = if (st == St.SUCCESS) 1f - clamp01((ts - 1100f) / 300f) else 1f
        val newA = if (st == St.SUCCESS) clamp01((ts - 1250f) / 300f) else 0f
        subLayout?.let { if (oldA > 0f) { subPaint.alpha = (255 * oldA).toInt(); c.save(); c.translate(16 * d, 144 * d); it.draw(c); c.restore() } }
        okLayout?.let { if (newA > 0f) { okPaint.alpha = (255 * newA).toInt(); c.save(); c.translate(16 * d, 144 * d); it.draw(c); c.restore() } }

        // the boxes / ball / result
        val ballR = 15 * d
        when (st) {
            St.IDLE -> drawBoxes(c, cx, rowY, 0f, 0f, 1f)
            St.SUBMIT -> {
                val tilt = inOut(submitT / 0.4f)
                val col = inOut((submitT - 0.35f) / 0.65f)
                drawBoxes(c, cx, rowY, tilt * (1f - col), col, 1f)
            }
            St.WAIT -> drawBall(c, cx, rowY, ballR * (1f + 0.1f * sin(2f * PI.toFloat() * pulse)), blue, 1f, 1f, 1f)
            St.FAIL -> {
                val shake = if (failT < 0.5f) sin(failT / 0.5f * 6f * PI.toFloat()) * 8 * d * (1f - failT / 0.5f * 0.3f) else 0f
                val e = inOut((failT - 0.5f) / 0.5f)
                if (failT < 0.5f) drawBall(c, cx + shake, rowY, ballR, mix(blue, red, failT / 0.2f), 1f, 1f, 1f)
                else {
                    drawBall(c, cx, rowY, ballR * (1f - e), red, 1f - e, 1f, 1f)
                    drawBoxes(c, cx, rowY, 0f, 0f, e)
                }
            }
            St.SUCCESS -> drawSuccessRow(c, cx, rowY, iconCy, ts, ballR)
        }
        if (st == St.IDLE || st == St.SUBMIT) { if (anyPopping()) postInvalidateOnAnimation() }
    }

    private fun anyPopping(): Boolean {
        val now = SystemClock.uptimeMillis()
        for (i in 0 until digits.length) if (now - popStart[i] < POP_MS) return true
        return false
    }

    private fun boxSize(w: Float) = min(44 * d, (w - 24 * d - 5 * 8 * d) / LENGTH)
    private fun boxX(i: Int, cx: Float, b: Float): Float = cx - (LENGTH * b + (LENGTH - 1) * 8 * d) / 2f + i * (b + 8 * d) + b / 2f

    /** Six boxes. [collapse] 0..1 slides them together into a ball, [tilt] rocks them, [alpha] fades the whole row. */
    private fun drawBoxes(c: Canvas, cx: Float, y: Float, tilt: Float, collapse: Float, alpha: Float) {
        val b = boxSize(width.toFloat())
        val now = SystemClock.uptimeMillis()
        val n = digits.length
        // the little bars joining filled neighbours
        if (collapse < 0.3f) {
            fill.shader = null; fill.style = Paint.Style.FILL; fill.color = blue
            fill.alpha = (255 * alpha * (1f - collapse / 0.3f)).toInt()
            for (i in 0 until n - 1) {
                val x1 = boxX(i, cx, b) + b / 2f - 2 * d
                val x2 = boxX(i + 1, cx, b) - b / 2f + 2 * d
                rect.set(x1, y - 2 * d, x2, y + 2 * d); c.drawRoundRect(rect, 2 * d, 2 * d, fill)
            }
        }
        for (i in 0 until LENGTH) {
            val filled = i < n
            val pop = if (filled) back((now - popStart[i]).toFloat() / POP_MS) else 1f
            val xi = lerp(boxX(i, cx, b), cx, collapse)
            val sz = lerp(b, 30 * d, collapse)
            val rr = lerp(12 * d, sz / 2f, collapse)
            val rot = tilt * 10f * (if (i % 2 == 0) 1f else -1f)
            val dy = tilt * 3 * d * (if (i % 2 == 0) -1f else 1f)
            c.save(); c.translate(xi, y + dy); c.rotate(rot); c.scale(pop, pop)
            rect.set(-sz / 2f, -sz / 2f, sz / 2f, sz / 2f)
            if (filled) {
                fill.style = Paint.Style.FILL
                fill.shader = LinearGradient(0f, -sz / 2f, 0f, sz / 2f, blueTop, blueBottom, Shader.TileMode.CLAMP)
                fill.alpha = (255 * alpha).toInt()
                c.drawRoundRect(rect, rr, rr, fill)
                fill.shader = null
                if (collapse < 0.5f) {
                    digitPaint.alpha = (255 * alpha * (1f - collapse * 2f)).toInt()
                    val m = digitPaint.fontMetrics
                    c.drawText(digits[i].toString(), 0f, -(m.ascent + m.descent) / 2f, digitPaint)
                }
            } else {
                fill.style = Paint.Style.FILL; fill.color = Pal.c("#FFFFFF"); fill.alpha = ((if (Pal.night) 14 else 130) * alpha).toInt()
                c.drawRoundRect(rect, rr, rr, fill)
                line.strokeWidth = 1.5f * d; line.color = lineColor(); line.alpha = (255 * alpha).toInt()
                c.drawRoundRect(rect, rr, rr, line)
                if (st == St.IDLE && i == n && alpha > 0.99f) {
                    line.strokeWidth = 2.2f * d; line.color = accent(); line.alpha = (150 + 105 * sin(2f * PI.toFloat() * pulse)).toInt().coerceIn(0, 255)
                    c.drawRoundRect(rect, rr, rr, line)
                    if (pulse < 0.5f) { fill.color = accent(); fill.alpha = 255; c.drawRect(-1 * d, -9 * d, 1 * d, 9 * d, fill) }
                }
            }
            c.restore()
        }
        fill.alpha = 255; line.alpha = 255
    }

    /** One glossy ball; [sx]/[sy] squash and stretch it. */
    private fun drawBall(c: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Float, sx: Float, sy: Float) {
        if (r <= 0f || alpha <= 0f) return
        c.save(); c.translate(x, y); c.scale(sx, sy)
        fill.shader = null; fill.style = Paint.Style.FILL
        fill.color = color; fill.alpha = (60 * alpha).toInt(); c.drawCircle(0f, 0f, r * 1.5f, fill)        // soft glow
        fill.color = color; fill.alpha = (255 * alpha).toInt(); c.drawCircle(0f, 0f, r, fill)
        fill.color = 0xFFFFFFFF.toInt(); fill.alpha = (90 * alpha).toInt(); c.drawCircle(-r * 0.3f, -r * 0.35f, r * 0.28f, fill)   // highlight
        fill.alpha = 255
        c.restore()
    }

    private fun drawSuccessRow(c: Canvas, cx: Float, rowY: Float, iconCy: Float, ts: Float, ballR: Float) {
        // 0-250 ms: ball turns green; 250-900 ms: it flies up to the padlock (squashing); 900-1150 ms: it is absorbed
        val color = mix(blue, green(), ts / 250f)
        val fly = inOut((ts - 250f) / 650f)
        val absorb = clamp01((ts - 900f) / 250f)
        val stretch = sin(PI.toFloat() * fly)
        val y = lerp(rowY, iconCy, fly)
        if (absorb < 1f) drawBall(c, cx, y, ballR * (1f - absorb), color, 1f, 1f - 0.2f * stretch, 1f + 0.4f * stretch)
        // where the boxes were: a green check circle and the caption
        val bc = clamp01((ts - 700f) / 450f)
        if (bc > 0f) {
            val r = 21 * d * back(bc)
            fill.shader = null; fill.style = Paint.Style.FILL
            fill.color = green(); fill.alpha = 55; c.drawCircle(cx, rowY, r * 1.45f, fill)
            fill.color = green(); fill.alpha = 255; c.drawCircle(cx, rowY, r, fill)
            drawCheck(c, cx, rowY, bc, 1f)
        }
        val ca = clamp01((ts - 1000f) / 400f)
        if (ca > 0f) {
            captionPaint.color = green(); captionPaint.alpha = (255 * ca).toInt()
            c.drawText("Verified and secure", cx, rowY + 50 * d, captionPaint)
        }
    }

    /** The padlock in the tile after a good code: opens, shrinks to a yellow dot, becomes a green circle, draws the check, bursts a seal. */
    private fun drawIconSuccess(c: Canvas, cx: Float, cy: Float, ts: Float) {
        if (ts < 1150f) {
            val open = inOut((ts - 600f) / 300f)
            val absorb = inOut((ts - 900f) / 250f)
            drawLock(c, cx, cy, 1f - 0.75f * absorb, open)
            return
        }
        val g = clamp01((ts - 1150f) / 350f)
        val r = lerp(7 * d, 22 * d, back(g))
        val seal = clamp01((ts - 1500f) / 600f)
        if (seal > 0f && seal < 1f) {                                   // the burst: a scalloped seal that grows and fades
            val sr = (24 * d) * (1f + 0.35f * sin(PI.toFloat() * seal) + 0.1f * seal)
            val p = Path()
            val steps = 96
            for (k in 0..steps) {
                val a = 2f * PI.toFloat() * k / steps
                val rad = sr * (1f + 0.09f * cos(12f * a))
                val px = cx + rad * cos(a); val py = cy + rad * sin(a)
                if (k == 0) p.moveTo(px, py) else p.lineTo(px, py)
            }
            p.close()
            fill.shader = null; fill.style = Paint.Style.FILL; fill.color = green(); fill.alpha = (255 * (1f - seal)).toInt()
            c.drawPath(p, fill); fill.alpha = 255
        }
        fill.shader = null; fill.style = Paint.Style.FILL
        fill.color = green(); fill.alpha = 50; c.drawCircle(cx, cy, r * 1.35f, fill)
        fill.color = mix(yellow, green(), g); fill.alpha = 255; c.drawCircle(cx, cy, r, fill)
        if (g < 1f) { fill.color = 0xFF3B2F00.toInt(); fill.alpha = (255 * (1f - g)).toInt(); c.drawCircle(cx, cy, 2.6f * d, fill); fill.alpha = 255 }
        drawCheck(c, cx, cy, clamp01((ts - 1500f) / 400f), 1f)
    }

    private fun drawCheck(c: Canvas, x: Float, y: Float, t: Float, scale: Float) {
        if (t <= 0f) return
        val pm = PathMeasure(checkPath, false)
        seg.reset()
        pm.getSegment(0f, pm.length * clamp01(t), seg, true)
        line.shader = null; line.style = Paint.Style.STROKE; line.color = 0xFFFFFFFF.toInt(); line.alpha = 255
        line.strokeWidth = 3.6f * d; line.strokeCap = Paint.Cap.ROUND; line.strokeJoin = Paint.Join.ROUND
        c.save(); c.translate(x, y); c.scale(scale, scale); c.drawPath(seg, line); c.restore()
        line.strokeCap = Paint.Cap.BUTT; line.strokeJoin = Paint.Join.MITER
    }

    /** The padlock: blue body with a keyhole, yellow shackle. [open] 0..1 lifts and swings the shackle. */
    private fun drawLock(c: Canvas, cx: Float, cy: Float, scale: Float, open: Float) {
        if (scale <= 0f) return
        c.save(); c.scale(scale, scale, cx, cy)
        val bodyTop = cy + 0 * d
        // shackle
        line.shader = null; line.style = Paint.Style.STROKE; line.color = yellow; line.alpha = 255; line.strokeWidth = 4.6f * d; line.strokeCap = Paint.Cap.ROUND
        c.save(); c.translate(0f, -open * 6 * d); c.rotate(-open * 28f, cx + 8 * d, bodyTop)
        rect.set(cx - 8 * d, bodyTop - 17 * d, cx + 8 * d, bodyTop + 1 * d)
        val sh = Path()
        sh.moveTo(cx - 8 * d, bodyTop + 2 * d); sh.lineTo(cx - 8 * d, bodyTop - 8 * d)
        sh.arcTo(rect, 180f, 180f, false)
        sh.lineTo(cx + 8 * d, bodyTop + 2 * d)
        c.drawPath(sh, line)
        c.restore()
        line.strokeCap = Paint.Cap.BUTT
        // body
        rect.set(cx - 14 * d, bodyTop - 1 * d, cx + 14 * d, bodyTop + 23 * d)
        fill.style = Paint.Style.FILL
        fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, blueTop, blueBottom, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, 6 * d, 6 * d, fill)
        fill.shader = null
        fill.color = 0xFFFFFFFF.toInt(); fill.alpha = 235; c.drawCircle(cx, bodyTop + 9.5f * d, 2.6f * d, fill); fill.alpha = 255
        c.restore()
    }
}
