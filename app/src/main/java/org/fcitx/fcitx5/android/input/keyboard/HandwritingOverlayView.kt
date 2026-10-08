package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.google.mlkit.vision.digitalink.Ink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.input.handwriting.HandwritingEngine
import splitties.dimensions.dp
import kotlin.math.abs

/**
 * 手写板打开请求总线：
 * 候选栏「三个点 → 手写板」（StatusAreaWindow）经此通知 TextKeyboard 的 overlay 进入手写板模式。
 */
object HandwritingBoardBus {
    var listener: (() -> Unit)? = null
    fun requestOpen() {
        listener?.invoke()
    }
}

@SuppressLint("ViewConstructor")
class HandwritingOverlayView(
    context: Context,
    private val engine: HandwritingEngine,
    private val onRecognized: (List<String>) -> Unit
) : View(context) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // 绘制相关
    private val paint = Paint().apply {
        color = Color.parseColor("#4A90E2") // fcitx 风格蓝
        isAntiAlias = true
        strokeWidth = dp(4f)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()

    // 引擎数据采集
    private var inkBuilder = Ink.builder()
    private var strokeBuilder: Ink.Stroke.Builder? = null

    // 触摸状态
    private var isWriting = false
    private var startX = 0f
    private var startY = 0f

    // 延时识别逻辑
    private var recognizeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private val recognizeDelayMs = 500L

    // —— 手写板（显式按钮）模式 ——
    // boardMode=true 时：overlay 变成"纸板"，拦截全部触摸直接落笔；
    // 左上角显示「✕ 退出手写板」按钮；键盘普通触摸被上层 dispatchTouchEvent 吃掉。
    // boardMode=false 时：透明手势模式（按住停顿触发），右上角显示「手写板」入口按钮。
    private var boardMode = false

    private val buttonWidth = dp(96f)
    private val buttonHeight = dp(32f)
    private val buttonMargin = dp(8f)

    // 入口按钮已移除（曾放键盘右上角，挡住 IOP 字母）；
    // 唯一入口 = 候选栏「三个点 → 手写板」。rect 置空保证判定永不命中。
    private val entryButtonRect = RectF()
    private val exitButtonRect = RectF()

    private val entryButtonPaint = Paint().apply {
        color = Color.parseColor("#66000000")
        isAntiAlias = true
    }
    private val boardBgPaint = Paint().apply {
        color = Color.parseColor("#FDFDFD")
    }
    private val boardBorderPaint = Paint().apply {
        color = Color.parseColor("#4A90E2")
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val boardButtonPaint = Paint().apply {
        color = Color.parseColor("#FFE3ECF5")
        isAntiAlias = true
    }
    private val buttonTextPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        textSize = dp(14f)
        textAlign = Paint.Align.CENTER
    }
    private val exitButtonTextPaint = Paint().apply {
        color = Color.parseColor("#1A5FA8")
        isAntiAlias = true
        textSize = dp(14f)
        textAlign = Paint.Align.CENTER
    }
    private val boardHintPaint = Paint().apply {
        color = Color.parseColor("#664A90E2")
        isAntiAlias = true
        textSize = dp(14f)
        textAlign = Paint.Align.CENTER
    }

    fun setBoardMode(on: Boolean) {
        if (boardMode == on) return
        boardMode = on
        // 切换时清掉未结算的笔画与识别任务，避免退出后墨迹残留导致误接管按键
        cancelWriting()
        invalidate()
    }

    fun isBoardMode(): Boolean = boardMode

    fun isEntryButtonHit(x: Float, y: Float): Boolean =
        !boardMode && !entryButtonRect.isEmpty && entryButtonRect.contains(x, y)

    fun isExitButtonHit(x: Float, y: Float): Boolean =
        boardMode && exitButtonRect.contains(x, y)

    init {
        // ViewGroup 默认不调用 onDraw，必须得关掉以强制绘制手写轨迹
        setWillNotDraw(false)
        // 确保引擎初始化
        engine.initModel()
    }

    /**
     * 判断当前画板上是否还有未被识别结算的笔画。
     * 可以用来让外部拦截器知道当前是否正处于“连续手写”的会话中。
     */
    fun hasInk(): Boolean {
        // 如果 path 不为空（或 inkBuilder 中已构建过 stroke），则表示正处于手写过程中
        return !path.isEmpty
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 入口按钮已废弃（挡键盘字母），rect 保持 empty
        entryButtonRect.setEmpty()
        // 退出按钮：左上角「✕ 退出手写板」（加宽容纳文字）
        exitButtonRect.set(
            buttonMargin,
            buttonMargin,
            buttonMargin + buttonWidth * 1.6f,
            buttonMargin + buttonHeight
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (boardMode) {
            // 手写板：不透明纸面 + 蓝色边框，让用户明确知道当前在"手写板"上
            canvas.drawRect(
                0f, 0f, width.toFloat(), height.toFloat(), boardBgPaint
            )
            canvas.drawRect(
                boardBorderPaint.strokeWidth,
                boardBorderPaint.strokeWidth,
                width - boardBorderPaint.strokeWidth,
                height - boardBorderPaint.strokeWidth,
                boardBorderPaint
            )
            // 笔迹
            canvas.drawPath(path, paint)
            // 左上角退出按钮
            canvas.drawRoundRect(exitButtonRect, dp(16f), dp(16f), boardButtonPaint)
            drawButtonText(canvas, exitButtonTextPaint, "✕ 退出手写板", exitButtonRect)
            // 中部提示（无墨迹时才显示，避免干扰笔迹）
            if (path.isEmpty) {
                val hintY = height * 0.5f
                canvas.drawText("在此区域写字，识别后自动上屏", width / 2f, hintY, boardHintPaint)
            }
        } else {
            // 透明手势模式：只画笔迹，无常驻按钮（入口在「三个点 → 手写板」）
            canvas.drawPath(path, paint)
        }
    }

    private fun drawButtonText(canvas: Canvas, p: Paint, text: String, rect: RectF) {
        val fm = p.fontMetrics
        val baseline = rect.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, rect.centerX(), baseline, p)
    }

    fun resetRecognizeJob() {
        recognizeJob?.cancel()
    }

    fun startWriting(x: Float, y: Float, t: Long) {
        strokeBuilder = Ink.Stroke.builder()
        strokeBuilder?.addPoint(Ink.Point.create(x, y, t))
        path.moveTo(x, y)
    }

    fun continueWriting(x: Float, y: Float, t: Long) {
        path.lineTo(x, y)
        invalidate()
        strokeBuilder?.addPoint(Ink.Point.create(x, y, t))
    }

    fun endWriting(x: Float, y: Float, t: Long) {
        strokeBuilder?.addPoint(Ink.Point.create(x, y, t))
        strokeBuilder?.build()?.let { inkBuilder.addStroke(it) }
        strokeBuilder = null
        scheduleRecognition()
    }

    // 暴露一个强制取消的方法以防出错
    fun cancelWriting() {
        recognizeJob?.cancel()
        strokeBuilder = null
        clearCanvas()
    }

    private fun scheduleRecognition() {
        recognizeJob?.cancel()
        recognizeJob = scope.launch {
            delay(recognizeDelayMs)
            performRecognition()
        }
    }

    private suspend fun performRecognition() {
        val ink = inkBuilder.build()
        if (ink.strokes.isEmpty()) {
            onRecognized(emptyList()) // 让上层知道会话已经因为空而结束
            return
        }

        val results = engine.recognize(ink)
        
        // 无论有无结果都清空画板
        clearCanvas()
        
        // 无论结果是否为空，都回调给上层，以便上层恢复普通键盘状态
        onRecognized(results)
    }

    private fun clearCanvas() {
        path.reset()
        invalidate()
        inkBuilder = Ink.builder()
    }
}
