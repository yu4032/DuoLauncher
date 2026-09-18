package com.jake.duolauncher

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import java.lang.reflect.Method
import kotlin.math.roundToInt

/**
 * Small cached bridge around HyperOS/MIUI's per-View pass-window blur APIs.
 *
 * Duo stays a normal launcher app: there is no Xposed dependency here and no package-private
 * Launcher class access. Unsupported ROMs simply retain the original translucent material.
 */
private object MiuiPassBlurBridge {
    private val setPassWindowBlurEnabled: Method?
    private val setMiViewBlurMode: Method?
    private val setMiBackgroundBlurMode: Method?
    private val setMiBackgroundBlurRadius: Method?

    val available: Boolean

    init {
        var passEnabled: Method? = null
        var viewMode: Method? = null
        var backgroundMode: Method? = null
        var backgroundRadius: Method? = null
        var passAvailable = false
        try {
            passEnabled = View::class.java.getMethod("setPassWindowBlurEnabled", Boolean::class.javaPrimitiveType)
            viewMode = View::class.java.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType)
            backgroundMode = View::class.java.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType)
            backgroundRadius = View::class.java.getMethod("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType)
            passAvailable = true
        } catch (_: Throwable) {
            // Non-MIUI and older builds use the translucent fallback.
        }
        setPassWindowBlurEnabled = passEnabled
        setMiViewBlurMode = viewMode
        setMiBackgroundBlurMode = backgroundMode
        setMiBackgroundBlurRadius = backgroundRadius
        available = passAvailable
    }

    fun apply(view: View, radiusPx: Int): Boolean {
        if (!available) return false
        val safeRadius = radiusPx.coerceIn(0, 400)
        return try {
            setPassWindowBlurEnabled!!.invoke(view, true)
            setMiBackgroundBlurMode!!.invoke(view, 1)
            val result = setMiBackgroundBlurRadius!!.invoke(view, safeRadius)
            setMiViewBlurMode!!.invoke(view, 1)
            if (result is Boolean && !result) {
                clear(view)
                false
            } else true
        } catch (_: Throwable) {
            clear(view)
            false
        }
    }

    fun clear(view: View) {
        if (!available) return
        runCatching { setPassWindowBlurEnabled!!.invoke(view, false) }
        runCatching { setMiViewBlurMode!!.invoke(view, 0) }
        runCatching { setMiBackgroundBlurMode!!.invoke(view, 0) }
        runCatching { setMiBackgroundBlurRadius!!.invoke(view, 0) }
    }
}

/**
 * A dedicated RenderNode for MIUI backdrop blur.
 *
 * Pass-window blur can reject the first request before the View has a valid ViewRoot, so attachment
 * retries are bounded to a few animation frames. The original Duo translucent fill remains the
 * exact fallback and is replaced by only a light tint once compositor blur is active.
 */
private class MiuiPassBlurBackdropView(context: Context) : View(context) {
    private var blurRadiusPx = DEFAULT_BLUR_RADIUS_PX
    private var fallbackColor = 0
    private var activeTintColor = 0
    private var blurActive = false
    private var attempts = 0
    private var unsupportedLogged = false

    private val retry = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || blurActive || attempts >= MAX_ATTACH_RETRIES) return
            attempts++
            blurActive = MiuiPassBlurBridge.apply(this@MiuiPassBlurBackdropView, blurRadiusPx)
            invalidate()
            if (!blurActive && attempts < MAX_ATTACH_RETRIES) postOnAnimation(this)
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        background = ColorDrawable(android.graphics.Color.TRANSPARENT)
    }

    fun updateMaterial(radiusPx: Int, fallbackArgb: Int, activeTintArgb: Int) {
        val radiusChanged = blurRadiusPx != radiusPx
        blurRadiusPx = radiusPx.coerceIn(0, 400)
        fallbackColor = fallbackArgb
        activeTintColor = activeTintArgb
        if (radiusChanged && isAttachedToWindow) {
            blurActive = MiuiPassBlurBridge.apply(this, blurRadiusPx)
            if (!blurActive) scheduleActivation()
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleActivation()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(retry)
        MiuiPassBlurBridge.clear(this)
        blurActive = false
        attempts = 0
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(if (blurActive) activeTintColor else fallbackColor)
    }

    private fun scheduleActivation() {
        removeCallbacks(retry)
        attempts = 0
        if (!MiuiPassBlurBridge.available) {
            if (!unsupportedLogged) {
                unsupportedLogged = true
                Log.d(TAG, "MIUI pass-window blur unavailable; using translucent fallback")
            }
            blurActive = false
            invalidate()
            return
        }
        postOnAnimation(retry)
    }

    companion object {
        private const val TAG = "DuoPassBlur"
        private const val DEFAULT_BLUR_RADIUS_PX = 100
        private const val MAX_ATTACH_RETRIES = 8
    }
}

/**
 * Compose material shell backed by MIUI pass-window blur when available.
 *
 * The AndroidView is deliberately the bottom layer. Compose owns final shape clipping and the
 * sharp border so the compositor blur never softens the edge treatment or child content.
 */
@Composable
internal fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape,
    fallbackColor: Color,
    border: BorderStroke? = null,
    blurRadiusPx: Int = 100,
    activeTintAlpha: Float = 0.08f,
    content: @Composable BoxScope.() -> Unit,
) {
    val tint = fallbackColor.copy(
        alpha = activeTintAlpha.coerceIn(0f, fallbackColor.alpha.coerceAtLeast(activeTintAlpha)),
    )
    Box(
        modifier = modifier.clip(shape),
        propagateMinConstraints = true,
    ) {
        AndroidView(
            factory = { MiuiPassBlurBackdropView(it) },
            modifier = Modifier.matchParentSize(),
            update = {
                it.updateMaterial(
                    radiusPx = blurRadiusPx,
                    fallbackArgb = fallbackColor.toArgb(),
                    activeTintArgb = tint.toArgb(),
                )
            },
        )
        Box(Modifier.matchParentSize(), propagateMinConstraints = true, content = content)
        if (border != null) {
            Box(Modifier.matchParentSize().border(border, shape))
        }
    }
}
