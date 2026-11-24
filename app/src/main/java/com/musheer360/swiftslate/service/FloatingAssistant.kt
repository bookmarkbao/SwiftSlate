package com.musheer360.swiftslate.service

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.musheer360.swiftslate.model.QuickAction
import kotlin.math.max

/**
 * The floating "AI" bubble shown next to the focused input field, and the confirm panel it
 * opens. Pure UI: every decision (what to send, where to write) is made by [Listener], which
 * is the accessibility service. Both windows are TYPE_ACCESSIBILITY_OVERLAY (no extra
 * permission) and FLAG_NOT_FOCUSABLE, so the host app keeps input focus and the user's own
 * keyboard stays up the whole time. All methods must be called on the main thread.
 */
class FloatingAssistant(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onBubbleClick()
        fun onActionSelected(action: QuickAction)
        fun onApply(text: String)
        fun onCopy(text: String)
        fun onClose()
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var panel: View? = null
    private var resultView: TextView? = null
    private var statusView: TextView? = null
    private var progressView: ProgressBar? = null
    private var applyBtn: TextView? = null
    private var copyBtn: TextView? = null
    private var retryBtn: TextView? = null
    private val chipViews = LinkedHashMap<String, TextView>()

    private var currentResult: String? = null
    private var currentAction: QuickAction? = null

    val isPanelShowing: Boolean get() = panel != null
    val isBubbleShowing: Boolean get() = bubble != null

    // ---------------------------------------------------------------- bubble

    fun showBubble(anchor: Rect) {
        val size = dp(BUBBLE_DP)
        val sw = context.resources.displayMetrics.widthPixels
        var x = anchor.right - size - dp(2)
        var y = anchor.top - size - dp(6)
        if (y < statusBarHeight()) {
            // No room above the field (it sits at the top of the screen): put the bubble
            // inside the field's right edge instead.
            y = anchor.top + (anchor.height() - size) / 2
        }
        x = x.coerceIn(0, max(0, sw - size))

        val existing = bubble
        val params = bubbleParams
        if (existing != null && params != null) {
            if (params.x != x || params.y != y) {
                params.x = x
                params.y = y
                try {
                    wm.updateViewLayout(existing, params)
                } catch (_: Exception) {
                    hideBubble()
                }
            }
            return
        }

        val view = TextView(context).apply {
            text = "AI"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ACCENT)
                setStroke(dp(2), Color.WHITE)
            }
            contentDescription = "AI 优化"
            setOnClickListener { listener.onBubbleClick() }
        }
        val p = overlayParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        try {
            wm.addView(view, p)
            bubble = view
            bubbleParams = p
        } catch (_: Exception) {
            bubble = null
            bubbleParams = null
        }
    }

    fun hideBubble() {
        bubble?.let { safeRemove(it) }
        bubble = null
        bubbleParams = null
    }

    // ---------------------------------------------------------------- panel

    fun showPanel(anchor: Rect, original: String, actions: List<QuickAction>) {
        hidePanel()
        hideBubble()

        val fg = if (isDark) 0xFFECECEC.toInt() else 0xFF1F1F1F.toInt()
        val sub = if (isDark) 0xFF9A9AA0.toInt() else 0xFF7A7A80.toInt()
        val bg = if (isDark) 0xFF26262A.toInt() else Color.WHITE
        val stroke = if (isDark) 0xFF3A3A40.toInt() else 0xFFE2E2E6.toInt()
        val resultBg = if (isDark) 0xFF1C1C1F.toInt() else 0xFFF5F6F8.toInt()

        val sh = context.resources.displayMetrics.heightPixels
        val top = statusBarHeight() + dp(8)
        val fixedPart = dp(200)
        val y: Int
        val maxResult: Int
        if (anchor.centerY() > sh / 2) {
            // Field is in the lower half (chat input above the keyboard): panel at the top.
            y = top
            maxResult = (anchor.top - dp(8) - top - fixedPart).coerceIn(dp(90), dp(340))
        } else {
            y = max(top, anchor.bottom + dp(8))
            maxResult = (sh - y - fixedPart - dp(16)).coerceIn(dp(90), dp(340))
        }

        val pad = dp(14)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, dp(10))
            background = rounded(bg, dp(16)).apply { setStroke(dp(1), stroke) }
            elevation = dp(8).toFloat()
        }

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "AI 优化"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(fg)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(context).apply {
            text = "✕"
            textSize = 18f
            setTextColor(sub)
            setPadding(dp(12), dp(2), dp(4), dp(2))
            contentDescription = "关闭"
            setOnClickListener { listener.onClose() }
        })
        root.addView(header)

        // Action chips
        val chipRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        chipViews.clear()
        for (action in actions) {
            val chip = TextView(context).apply {
                text = action.label
                textSize = 13f
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener { listener.onActionSelected(action) }
            }
            chipRow.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) })
            chipViews[action.id] = chip
        }
        val chipScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
        }
        root.addView(chipScroll, matchWrap().apply { topMargin = dp(10) })

        // Original text
        root.addView(TextView(context).apply {
            text = "原文：" + original.replace('\n', ' ')
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(sub)
        }, matchWrap().apply { topMargin = dp(10) })

        // Result box
        val resultBox = MaxHeightScrollView(context, maxResult).apply {
            background = rounded(resultBg, dp(10))
            isVerticalScrollBarEnabled = true
        }
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
        statusRow.addView(progress, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
        val status = TextView(context).apply {
            textSize = 13f
            setTextColor(sub)
        }
        statusRow.addView(status)
        inner.addView(statusRow)
        val result = TextView(context).apply {
            textSize = 15f
            setTextColor(fg)
            setLineSpacing(dp(3).toFloat(), 1f)
            visibility = View.GONE
        }
        inner.addView(result, matchWrap())
        resultBox.addView(inner)
        root.addView(resultBox, matchWrap().apply { topMargin = dp(8) })

        // Buttons
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val copy = makeButton("复制", primary = false) { currentResult?.let { listener.onCopy(it) } }
        val retry = makeButton("重新生成", primary = false) { currentAction?.let { listener.onActionSelected(it) } }
        val apply = makeButton("替换", primary = true) { currentResult?.let { listener.onApply(it) } }
        buttons.addView(copy)
        buttons.addView(retry, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(6) })
        buttons.addView(apply, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(6) })
        root.addView(buttons, matchWrap().apply { topMargin = dp(10) })

        val sw = context.resources.displayMetrics.widthPixels
        val margin = dp(10)
        val params = overlayParams(sw - margin * 2, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = margin
            this.y = y
        }
        try {
            wm.addView(root, params)
            panel = root
            resultView = result
            statusView = status
            progressView = progress
            applyBtn = apply
            copyBtn = copy
            retryBtn = retry
        } catch (_: Exception) {
            panel = null
        }
    }

    fun setLoading(action: QuickAction) {
        currentAction = action
        currentResult = null
        highlightChip(action.id)
        progressView?.visibility = View.VISIBLE
        statusView?.apply {
            visibility = View.VISIBLE
            setTextColor(if (isDark) 0xFF9A9AA0.toInt() else 0xFF7A7A80.toInt())
            text = "正在「${action.label}」…"
        }
        resultView?.visibility = View.GONE
        setEnabled(applyBtn, false)
        setEnabled(copyBtn, false)
        setEnabled(retryBtn, false)
    }

    fun setResult(text: String) {
        currentResult = text
        progressView?.visibility = View.GONE
        statusView?.visibility = View.GONE
        resultView?.apply {
            visibility = View.VISIBLE
            this.text = text
        }
        setEnabled(applyBtn, true)
        setEnabled(copyBtn, true)
        setEnabled(retryBtn, true)
    }

    fun setError(message: String) {
        currentResult = null
        progressView?.visibility = View.GONE
        statusView?.apply {
            visibility = View.VISIBLE
            setTextColor(0xFFE5484D.toInt())
            text = message
        }
        resultView?.visibility = View.GONE
        setEnabled(applyBtn, false)
        setEnabled(copyBtn, false)
        setEnabled(retryBtn, true)
    }

    fun hidePanel() {
        panel?.let { safeRemove(it) }
        panel = null
        resultView = null
        statusView = null
        progressView = null
        applyBtn = null
        copyBtn = null
        retryBtn = null
        chipViews.clear()
        currentResult = null
        currentAction = null
    }

    fun hideAll() {
        hideBubble()
        hidePanel()
    }

    // ---------------------------------------------------------------- helpers

    private fun highlightChip(selectedId: String) {
        val fg = if (isDark) 0xFFECECEC.toInt() else 0xFF1F1F1F.toInt()
        val chipBg = if (isDark) 0xFF34343A.toInt() else 0xFFEEEFF3.toInt()
        for ((id, chip) in chipViews) {
            val selected = id == selectedId
            chip.background = rounded(if (selected) ACCENT else chipBg, dp(14))
            chip.setTextColor(if (selected) Color.WHITE else fg)
        }
    }

    private fun makeButton(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            if (primary) {
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = rounded(ACCENT, dp(18))
            } else {
                setTextColor(ACCENT)
                background = rounded(if (isDark) 0xFF34343A.toInt() else 0xFFEEF1FD.toInt(), dp(18))
            }
            setOnClickListener { onClick() }
        }

    private fun setEnabled(view: View?, enabled: Boolean) {
        view ?: return
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.4f
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { windowAnimations = 0 }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun safeRemove(view: View) {
        try {
            wm.removeView(view)
        } catch (_: Exception) {
        }
    }

    private val isDark: Boolean
        get() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /** ScrollView that never grows past [maxHeightPx]. */
    private class MaxHeightScrollView(context: Context, private val maxHeightPx: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            )
        }
    }

    private companion object {
        const val BUBBLE_DP = 36
        val ACCENT = 0xFF4F6BED.toInt()
    }
}
