package com.example.attendance

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.animation.DecelerateInterpolator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Labelled field ("Label input"): the label is the placeholder inside the field; on focus it lifts into the top edge and the outline
 * parts around it. Passwords get a show/hide eye. Styled by Widget.Sam.Field (themes.xml), the same style the login and sign-up pages use.
 * Read the value with [text] / [setText] like an EditText.
 */
class FloatField(ctx: Context, label: String, type: Int = InputType.TYPE_CLASS_TEXT, password: Boolean = false) :
    TextInputLayout(ContextThemeWrapper(ctx, R.style.ThemeOverlay_Sam_Field)) {

    val edit = TextInputEditText(this.context)

    init {
        hint = label
        edit.inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else type
        addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (password) endIconMode = END_ICON_PASSWORD_TOGGLE
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (10 * ctx.resources.displayMetrics.density).toInt() }
    }

    val text: Editable get() = edit.text ?: Editable.Factory.getInstance().newEditable("")
    fun setText(t: CharSequence?) = edit.setText(t)
    fun addTextChangedListener(w: TextWatcher) = edit.addTextChangedListener(w)
}

/**
 * Search that "seeks" ("Seek" block): a search icon that becomes a search field as ONE object. Tap it and the field opens leftwards around
 * the magnifier, which travels with the edge into place; the placeholder moves inside; × clears the text, and when it is empty (× again, or
 * the field loses focus) the field folds back into the icon. [openOnShow] opens it by itself a moment after it appears (used in dialogs).
 * Read the value through [text] / [addTextChangedListener] like an EditText; [edit] is the real field.
 */
class SeekField(ctx: Context, private val hintText: String, private val openOnShow: Boolean = false) : FrameLayout(ctx) {
    val edit: EditText = EditText(ctx)
    private val pill = LinearLayout(ctx)
    private val icon = ImageView(ctx)
    private val clear = TextView(ctx)
    private val label = TextView(ctx)
    private var isOpen = false
    private var animator: ValueAnimator? = null
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val closedW get() = dp(48)

    init {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) }
        // the hint, shown at the start while the field is folded into its icon
        label.apply {
            textSize = 14f; setTextColor(Pal.c("#8A5F52")); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL
        }
        label.text = hintText
        addView(label, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { marginStart = dp(6); marginEnd = dp(56) })

        pill.apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pillBackground(false)
        }
        icon.apply {
            setImageResource(R.drawable.ic_seek); setColorFilter(Pal.c("#8A5F52")); scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        pill.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))
        edit.apply {
            background = null; hint = hintText; textSize = 15f; setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(Pal.c("#6B352A")); setHintTextColor(Pal.c("#A98578"))
            setPadding(0, 0, 0, 0); alpha = 0f
            isFocusable = false; isFocusableInTouchMode = false
            setOnFocusChangeListener { _, has ->
                pill.background = pillBackground(has)
                if (!has && edit.text.isNullOrEmpty() && isOpen) close()
            }
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { clear.visibility = if (isOpen && !s.isNullOrEmpty()) VISIBLE else GONE }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
        }
        pill.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        clear.apply {
            textSize = 15f; setTextColor(Pal.c("#8A5F52")); gravity = Gravity.CENTER; visibility = GONE
            isClickable = true; isFocusable = false
            setOnClickListener { if (edit.text.isNullOrEmpty()) close() else edit.setText("") }
        }
        clear.text = "✕"
        pill.addView(clear, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(pill, LayoutParams(closedW, dp(48), Gravity.END or Gravity.CENTER_VERTICAL))

        val openIt = OnClickListener { if (!isOpen) open(true) else if (!edit.hasFocus()) { edit.requestFocus(); showKeyboard() } }
        setOnClickListener(openIt); pill.setOnClickListener(openIt); icon.setOnClickListener(openIt)
        if (openOnShow) postDelayed({ if (!isOpen) open(false) }, 250)
    }

    val text: Editable get() = edit.text ?: Editable.Factory.getInstance().newEditable("")
    fun addTextChangedListener(w: TextWatcher) = edit.addTextChangedListener(w)
    /** Preset text (for example the last query when a screen is drawn again): opens the field at once, without the motion. */
    fun setText(t: CharSequence?) {
        edit.setText(t)
        if (!t.isNullOrEmpty()) { isOpen = true; edit.isFocusable = true; edit.isFocusableInTouchMode = true; applyOpen(1f); setWidth(if (width > 0) width else ViewGroup.LayoutParams.MATCH_PARENT) }
    }

    private fun pillBackground(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(24).toFloat(); setColor(Pal.c("#FFFAE3"))
        if (focused) setStroke(dp(2), Pal.c("#6B352A")) else setStroke(dp(1), Pal.c("#EBDDA0"))
    }

    private fun setWidth(w: Int) { pill.layoutParams = (pill.layoutParams as LayoutParams).also { it.width = w }; pill.requestLayout() }

    /** 0 = folded into the icon, 1 = fully open: fades the hint out and the field in. */
    private fun applyOpen(f: Float) {
        label.alpha = 1f - f
        edit.alpha = ((f - 0.3f) / 0.7f).coerceIn(0f, 1f)
        clear.alpha = edit.alpha
        clear.visibility = if (f > 0.99f && !edit.text.isNullOrEmpty()) VISIBLE else GONE
    }

    private fun runAnim(toOpen: Boolean, onEnd: () -> Unit = {}) {
        animator?.cancel()
        val full = if (width > 0) width else return
        val from = (pill.layoutParams as LayoutParams).width.let { if (it <= 0) full else it }
        val to = if (toOpen) full else closedW
        animator = ValueAnimator.ofInt(from, to).apply {
            duration = 300; interpolator = DecelerateInterpolator()
            addUpdateListener {
                val w = it.animatedValue as Int
                setWidth(w)
                applyOpen(((w - closedW).toFloat() / (full - closedW).coerceAtLeast(1)).coerceIn(0f, 1f))
            }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { onEnd() } })
            start()
        }
    }

    private fun open(focus: Boolean) {
        if (width == 0) { post { open(focus) }; return }
        isOpen = true
        edit.isFocusable = true; edit.isFocusableInTouchMode = true
        runAnim(true) { setWidth(ViewGroup.LayoutParams.MATCH_PARENT) }
        if (focus) { edit.requestFocus(); showKeyboard() }
    }

    private fun close() {
        if (!isOpen) return
        isOpen = false
        hideKeyboard()
        edit.clearFocus(); edit.isFocusable = false; edit.isFocusableInTouchMode = false
        pill.background = pillBackground(false)
        (pill.layoutParams as LayoutParams).width.let { if (it <= 0) setWidth(width) }
        runAnim(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isOpen && animator?.isRunning != true) setWidth(ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun imm() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private fun showKeyboard() { edit.post { imm().showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) } }
    private fun hideKeyboard() { imm().hideSoftInputFromWindow(edit.windowToken, 0) }
}
