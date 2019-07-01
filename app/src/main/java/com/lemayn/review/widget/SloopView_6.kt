package com.lemayn.review.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color.*
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

/**
 * author: ly
 * date  : 2019/6/22 11:37
 * desc  :
 * [安卓自定义View进阶-分类与流程](https://www.gcssloop.com/customview/Path_Bezier)
 **/
class SloopView_6 : View {

    private val FACTOR = 0.551915024494f // 一个常量，用来计算绘制圆形贝塞尔曲线控制点的位置

    private var flag = 0
    private var oneStep = false
    private val paint = Paint()
    private val path = Path()
    private var mWidth = 0
    private var mHeight = 0
    private val controlPoint = Point(300, 300)
    private val startPoint = Point(-300, 0)
    private val endPoint = Point(300, 0)
    private val singleTapDetector = GestureDetector(context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(event: MotionEvent): Boolean {
                    flag++
                    postInvalidateOnAnimation()
                    return true
                }
            })

    // 一般在直接 new 一个 View 的时候调用
    constructor(context: Context, flag: Boolean) : super(context) {
        oneStep = flag
        init()
    }

    private fun init() {
        // empty
        paint.strokeWidth = 3f
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.color = BLACK

        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    // 一般在 layout 文件中使用的时候会调用，关于它的所有属性(包括自定义属性)都会包含在 attrs 中传递进来
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?,
                defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 宽度的测量模式
        val widthMode = View.MeasureSpec.getMode(widthMeasureSpec)
        // 宽度的确切值
        val widthSize = View.MeasureSpec.getSize(widthMeasureSpec)
        // 高度的测量模式
        val heightMode = View.MeasureSpec.getMode(heightMeasureSpec)
        // 高度的确切值
        val heightSize = View.MeasureSpec.getSize(heightMeasureSpec)

        // 对宽高进行修改后，不能调用 super 方法
        setMeasuredDimension(widthSize, heightSize)
    }

    // 在视图大小发生改变的时候调用
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // w、h 为 view 的最终宽高
        super.onSizeChanged(w, h, oldw, oldh)
        mWidth = w
        mHeight = h
    }

    /**
     * 自定义 ViewGroup 中会用到
     * 循环取出子 View，计算子 View 的坐标。调用 child.layout
     */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // empty
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) {
            return false
        }
        if (oneStep) {
            event.offsetLocation(-mWidth / 2f, -mHeight / 2f)
            controlPoint.x = event.x.toInt()
            controlPoint.y = -event.y.toInt()
            postInvalidateOnAnimation()
        } else {
            singleTapDetector.onTouchEvent(event)
        }
        return true
    }

    // 实际执行绘制的函数
    override fun onDraw(canvas: Canvas) {
        drawCoordinate(canvas)

        if (oneStep) {
            path.moveTo(startPoint.x.toFloat(), startPoint.y.toFloat())
            path.quadTo(controlPoint.x.toFloat(), controlPoint.y.toFloat(), endPoint.x.toFloat(), endPoint.y.toFloat())
            canvas.drawPath(path, paint)
        } else {
            drawBezierHeart(canvas)
        }


    }

    private fun drawCoordinate(canvas: Canvas) {
        canvas.drawColor(WHITE)

        paint.apply {
            strokeWidth = 3f
            style = Paint.Style.STROKE
            color = parseColor("#33FF0000")
        }
        path.reset()

        canvas.translate(mWidth / 2f, mHeight / 2f)
        if (oneStep) {
            canvas.drawLine(-mWidth / 2f, 0f, mWidth / 2f, 0f, paint)
            canvas.drawLine(0f, -mHeight / 2f, 0f, mHeight / 2f, paint)
        }
        canvas.scale(1f, -1f)

        if (oneStep) {
            canvas.drawLine(startPoint.x.toFloat(), startPoint.y.toFloat(),
                    controlPoint.x.toFloat(), controlPoint.y.toFloat(), paint)
            canvas.drawLine(endPoint.x.toFloat(), endPoint.y.toFloat(),
                    controlPoint.x.toFloat(), controlPoint.y.toFloat(), paint)
        }

        paint.color = BLACK
    }

    private fun drawBezierHeart(canvas: Canvas) {
        paint.apply {
            color = RED
            style = Paint.Style.FILL
        }
        path.fillType = Path.FillType.EVEN_ODD

        path.moveTo(0f, 300f)
        path.cubicTo(300f * FACTOR, 300f, 300f, 300f * FACTOR, 300f, 0f)
        path.cubicTo(300f, -300f * FACTOR, 300f * FACTOR, -300f, 0f, -300f)
        path.cubicTo(-300f * FACTOR, -300f, -300f, -300f * FACTOR, -300f, 0f)
        path.cubicTo(-300f, 300f * FACTOR, -300f * FACTOR, 300f, 0f, 300f)
        canvas.drawPath(path, paint)

        path.reset()

        paint.apply {
            color = WHITE
            style = Paint.Style.FILL
        }

        if (flag % 2 == 0) {
            path.fillType = Path.FillType.INVERSE_EVEN_ODD
        } else {
            path.fillType = Path.FillType.EVEN_ODD
        }
        path.moveTo(0f, 120f)
        path.cubicTo(300f * FACTOR, 300f, 300f, 300f * FACTOR, 300f, 0f)
        path.cubicTo(270f, -300f * FACTOR, 300f * FACTOR, -180f, 0f, -300f)
        path.cubicTo(-300f * FACTOR, -180f, -270f, -300f * FACTOR, -300f, 0f)
        path.cubicTo(-300f, 300f * FACTOR, -300f * FACTOR, 300f, 0f, 120f)
        canvas.drawPath(path, paint)
    }
}
