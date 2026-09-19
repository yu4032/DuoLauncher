package com.jake.duolauncher

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.lang.reflect.Method
import kotlin.math.abs

/**
 * Cached bridge around HyperOS/MIUI's per-View pass-window blur APIs.
 *
 * This stays dependency-free and fails closed on non-MIUI builds. Radius repair is deliberately
 * separate from full activation: HyperOS may rewrite the backdrop radius while HOME/RECENTS
 * changes authority, and replaying every vendor flag during that transition is unnecessarily
 * destructive.
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
            // Non-MIUI and older builds retain Duo's translucent fallback.
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

    fun repairRadius(view: View, radiusPx: Int): Boolean {
        if (!available) return false
        return try {
            val result = setMiBackgroundBlurRadius!!.invoke(view, radiusPx.coerceIn(0, 400))
            result !is Boolean || result
        } catch (_: Throwable) {
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
 * Dedicated RenderNode for MIUI backdrop blur. It owns no interaction and never becomes an
 * accessibility node. The Compose host owns shape, optics, content, and the final sharp edge.
 */
private class MiuiPassBlurBackdropView(context: Context) : View(context) {
    private var blurRadiusPx = DEFAULT_BLUR_RADIUS_PX
    private var fallbackColor = 0
    private var activeTintColor = 0
    private var passBlurRequested = true
    private var blurActive = false
    private var attempts = 0
    private var unsupportedLogged = false
    private var activeLogged = false
    private var rejectedLogged = false

    private val retry = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || blurActive || !passBlurRequested || attempts >= MAX_ATTACH_RETRIES) return
            attempts++
            blurActive = MiuiPassBlurBridge.apply(this@MiuiPassBlurBackdropView, blurRadiusPx)
            if (blurActive && !activeLogged) {
                activeLogged = true
                Log.d(TAG, "MIUI pass-window blur active radius=$blurRadiusPx")
            }
            invalidate()
            if (!blurActive && attempts < MAX_ATTACH_RETRIES) {
                postOnAnimation(this)
            } else if (!blurActive && !rejectedLogged) {
                rejectedLogged = true
                Log.d(TAG, "MIUI pass-window blur rejected after $attempts attach frames; using translucent fallback")
            }
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        background = ColorDrawable(android.graphics.Color.TRANSPARENT)
    }

    fun updateMaterial(
        radiusPx: Int,
        fallbackArgb: Int,
        activeTintArgb: Int,
        requestPassBlur: Boolean,
    ) {
        val safeRadius = radiusPx.coerceIn(0, 400)
        val radiusChanged = blurRadiusPx != safeRadius
        val requestChanged = passBlurRequested != requestPassBlur
        blurRadiusPx = safeRadius
        fallbackColor = fallbackArgb
        activeTintColor = activeTintArgb
        passBlurRequested = requestPassBlur

        if (!requestPassBlur) {
            removeCallbacks(retry)
            MiuiPassBlurBridge.clear(this)
            blurActive = false
            attempts = 0
        } else if (isAttachedToWindow && requestChanged) {
            blurActive = MiuiPassBlurBridge.apply(this, blurRadiusPx)
            if (!blurActive) scheduleActivation()
        } else if (isAttachedToWindow && radiusChanged) {
            if (blurActive) {
                if (!MiuiPassBlurBridge.repairRadius(this, blurRadiusPx)) {
                    blurActive = false
                    scheduleActivation()
                }
            } else {
                scheduleActivation()
            }
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

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus || !passBlurRequested || !isAttachedToWindow) return
        if (blurActive) {
            if (!MiuiPassBlurBridge.repairRadius(this, blurRadiusPx)) {
                blurActive = false
                scheduleActivation()
            }
        } else {
            scheduleActivation()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && passBlurRequested && isAttachedToWindow) {
            if (blurActive) MiuiPassBlurBridge.repairRadius(this, blurRadiusPx)
            else scheduleActivation()
        }
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(if (blurActive) activeTintColor else fallbackColor)
    }

    private fun scheduleActivation() {
        removeCallbacks(retry)
        attempts = 0
        rejectedLogged = false
        if (!passBlurRequested) {
            blurActive = false
            invalidate()
            return
        }
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
 * MIUI-backed glass shell shared by Duo's large surfaces.
 *
 * PassBlur supplies only the backdrop material. The Compose overlay deliberately owns tint,
 * directional light, haze, caustic wash and sharp rims. This mirrors LiquidDock's separation
 * between the compositor body and the optical/highlight presentation without pretending the
 * basic View API exposes a UV texture for Prismal refraction.
 */
@Composable
internal fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape,
    fallbackColor: Color,
    border: BorderStroke? = null,
    role: LiquidGlassRole = LiquidGlassRole.CARD,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val settings = LocalLiquidGlassSettings.current
    val materialEnabled = enabled && settings.enabledFor(role)
    val fallbackAlpha = (fallbackColor.alpha * settings.tintAlpha / 35f).coerceIn(0f, 1f)
    val materialFallback = if (settings.followPaletteTint) {
        fallbackColor.copy(alpha = fallbackAlpha)
    } else {
        Color(
            red = settings.tintRed / 255f,
            green = settings.tintGreen / 255f,
            blue = settings.tintBlue / 255f,
            alpha = fallbackAlpha,
        )
    }
    val paletteTint = if (settings.followPaletteTint) {
        fallbackColor.copy(alpha = settings.tintAlpha / 255f)
    } else {
        Color(
            red = settings.tintRed / 255f,
            green = settings.tintGreen / 255f,
            blue = settings.tintBlue / 255f,
            alpha = settings.tintAlpha / 255f,
        )
    }
    val refractionEligible = when (role) {
        LiquidGlassRole.DOCK,
        LiquidGlassRole.PANEL,
        LiquidGlassRole.FOLDER,
        LiquidGlassRole.WIDGET -> true
        LiquidGlassRole.CARD -> settings.refractionCardsEnabled
        LiquidGlassRole.CONTROL,
        LiquidGlassRole.TRANSIENT -> false
    }
    val refractionPriority = when (role) {
        LiquidGlassRole.PANEL -> 60
        LiquidGlassRole.FOLDER -> 55
        LiquidGlassRole.WIDGET -> 45
        LiquidGlassRole.DOCK -> 40
        LiquidGlassRole.CARD -> 20
        LiquidGlassRole.CONTROL,
        LiquidGlassRole.TRANSIENT -> 0
    }
    val density = LocalDensity.current
    val refractionCornerRadiusPx = with(density) {
        when (role) {
            LiquidGlassRole.DOCK,
            LiquidGlassRole.PANEL,
            LiquidGlassRole.FOLDER -> 30.dp.toPx()
            LiquidGlassRole.WIDGET,
            LiquidGlassRole.CARD -> 24.dp.toPx()
            LiquidGlassRole.CONTROL,
            LiquidGlassRole.TRANSIENT -> 18.dp.toPx()
        }
    }
    val largeHighlightEdgePx = with(density) {
        (settings.highlightWidthPercent / 100f).coerceIn(.5f, 3f).dp.toPx()
    }

    Box(
        modifier = modifier.clip(shape),
        propagateMinConstraints = true,
    ) {
        if (materialEnabled) {
            AndroidView(
                factory = { MiuiPassBlurBackdropView(it) },
                modifier = Modifier.matchParentSize(),
                update = {
                    it.updateMaterial(
                        radiusPx = settings.blurRadiusPx,
                        fallbackArgb = materialFallback.toArgb(),
                        activeTintArgb = paletteTint.toArgb(),
                        requestPassBlur = settings.passBlurEnabled,
                    )
                },
            )
            AndroidView(
                factory = { MiuiPassBlurRefractionView(it) },
                modifier = Modifier.matchParentSize(),
                update = {
                    it.updateMaterial(
                        settings.passBlurEnabled && settings.refractionEnabled && refractionEligible,
                        refractionPriority,
                        settings.captureScalePercent,
                        settings.blurRadiusPx,
                        settings.refractionStrengthPx.toFloat(),
                        settings.refractionInsetPx.toFloat(),
                        settings.chromatic.toFloat(),
                        settings.dispersionRPercent / 100f,
                        settings.dispersionBPercent / 100f,
                        refractionCornerRadiusPx,
                        paletteTint.toArgb(),
                    )
                },
            )
            // Large surfaces keep the sharp presentation in the platform View hierarchy.
            // This avoids AndroidView/Compose interop ordering from hiding the rim behind PassBlur.
            AndroidView(
                factory = { MiuiGlassHighlightView(it) },
                modifier = Modifier.matchParentSize(),
                update = {
                    it.updateMaterial(
                        refractionCornerRadiusPx,
                        settings.brightnessPercent / 100f,
                        settings.highlightAlphaPercent / 100f,
                        settings.reflectionStrengthPercent / 100f,
                        settings.reflectionLightenPercent / 100f,
                        settings.directionalIntensityPercent / 100f,
                        settings.oppositeIntensityPercent / 100f,
                        (settings.directionalAngleRangePercent / 150f).coerceIn(.03f, 1f),
                        largeHighlightEdgePx,
                        settings.lightDirXPercent / 100f,
                        settings.lightDirYPercent / 100f,
                        settings.skyHaze,
                        settings.specular,
                        settings.litRim,
                        settings.oppositeRim,
                        settings.cornerRim,
                        settings.faceSheen,
                        settings.plainHighlight,
                        settings.caustics,
                    )
                },
            )
        } else {
            Box(Modifier.matchParentSize().background(fallbackColor))
        }

        Box(Modifier.matchParentSize(), propagateMinConstraints = true, content = content)

        if (border != null) {
            Box(Modifier.matchParentSize().border(border, shape))
        }
    }
}

@Composable
internal fun LiquidGlassTintSurface(
    modifier: Modifier = Modifier,
    shape: Shape,
    fallbackColor: Color,
    border: BorderStroke? = null,
    role: LiquidGlassRole = LiquidGlassRole.CONTROL,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val settings = LocalLiquidGlassSettings.current
    val materialEnabled = enabled && settings.enabledFor(role)
    val scaledAlpha = (fallbackColor.alpha * settings.tintAlpha / 35f).coerceIn(0f, 1f)
    val tint = if (!materialEnabled) {
        fallbackColor
    } else if (settings.followPaletteTint) {
        fallbackColor.copy(alpha = scaledAlpha)
    } else {
        Color(
            red = settings.tintRed / 255f,
            green = settings.tintGreen / 255f,
            blue = settings.tintBlue / 255f,
            alpha = scaledAlpha,
        )
    }
    Box(
        modifier = modifier.clip(shape).background(tint),
        propagateMinConstraints = true,
    ) {
        if (materialEnabled) LiquidGlassOpticsOverlay(shape, settings)
        Box(Modifier.matchParentSize(), propagateMinConstraints = true, content = content)
        if (border != null) Box(Modifier.matchParentSize().border(border, shape))
    }
}

@Composable
internal fun liquidGlassTintColor(
    fallbackColor: Color,
    role: LiquidGlassRole = LiquidGlassRole.CONTROL,
): Color {
    val settings = LocalLiquidGlassSettings.current
    if (!settings.enabledFor(role)) return fallbackColor
    val alpha = (fallbackColor.alpha * settings.tintAlpha / 35f).coerceIn(0f, 1f)
    return if (settings.followPaletteTint) {
        fallbackColor.copy(alpha = alpha)
    } else {
        Color(
            red = settings.tintRed / 255f,
            green = settings.tintGreen / 255f,
            blue = settings.tintBlue / 255f,
            alpha = alpha,
        )
    }
}

@Composable
private fun BoxScope.LiquidGlassOpticsOverlay(
    shape: Shape,
    settings: LiquidGlassSettings,
) {
    val strength = (settings.highlightAlphaPercent / 100f).coerceIn(0f, 2f)
    val reflection = (settings.reflectionStrengthPercent / 100f).coerceIn(0f, 2f)
    val lighten = (settings.reflectionLightenPercent / 100f).coerceIn(0f, 1f)
    val mainDirectional = (settings.directionalIntensityPercent / 100f).coerceIn(0f, 2f)
    val oppositeDirectional = (settings.oppositeIntensityPercent / 100f).coerceIn(0f, 2f)
    val angleSoftness = (settings.directionalAngleRangePercent / 150f).coerceIn(0.03f, 1f)
    val edgeWidth = (settings.highlightWidthPercent / 100f).coerceIn(.5f, 3f).dp

    if (settings.brightnessPercent != 100) {
        val delta = (settings.brightnessPercent - 100) / 100f
        val wash = if (delta > 0f) {
            Color.White.copy(alpha = (delta * .10f).coerceIn(0f, .10f))
        } else {
            Color.Black.copy(alpha = (-delta * .14f).coerceIn(0f, .14f))
        }
        Box(Modifier.matchParentSize().background(wash, shape))
    }

    if (settings.skyHaze) {
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = (.18f * strength + .05f * lighten).coerceIn(0f, .28f)),
                        Color.White.copy(alpha = (.045f * strength).coerceIn(0f, .08f)),
                        Color.Transparent,
                    )
                ),
                shape,
            )
        )
    }

    if (settings.faceSheen) {
        val horizontal = abs(settings.lightDirXPercent) >= abs(settings.lightDirYPercent)
        val sheen = listOf(
            Color.White.copy(alpha = (.13f * strength * (0.65f + reflection)).coerceIn(0f, .18f)),
            Color.Transparent,
            Color.White.copy(alpha = (.05f * strength * (0.5f + angleSoftness)).coerceIn(0f, .08f)),
        ).let { colors ->
            val reverse = if (horizontal) settings.lightDirXPercent > 0 else settings.lightDirYPercent > 0
            if (reverse) colors.reversed() else colors
        }
        Box(
            Modifier.matchParentSize().background(
                if (horizontal) Brush.horizontalGradient(sheen) else Brush.verticalGradient(sheen),
                shape,
            )
        )
    }

    if (settings.caustics) {
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = (.035f * strength * (1f + reflection)).coerceIn(0f, .07f)),
                        Color.Transparent,
                    )
                ),
                shape,
            )
        )
    }

    val horizontalLight = abs(settings.lightDirXPercent) >= abs(settings.lightDirYPercent)
    var mainAlpha = .19f * strength * (1f + .55f * reflection) * (1f + .30f * lighten)
    var oppositeAlpha = .065f * strength * (1f + .40f * reflection)
    if (!settings.litRim) mainAlpha = 0f
    if (!settings.oppositeRim) oppositeAlpha = 0f
    val midAlpha = if (settings.cornerRim) {
        (.085f * strength * (1f + .50f * angleSoftness)).coerceIn(0f, .24f)
    } else 0f
    val baseAlpha = if (settings.plainHighlight) (.055f * strength).coerceIn(0f, .16f) else 0f
    mainAlpha = (mainAlpha * mainDirectional + baseAlpha).coerceIn(0f, .52f)
    oppositeAlpha = (oppositeAlpha * oppositeDirectional + baseAlpha).coerceIn(0f, .30f)

    var edgeColors = listOf(
        Color.White.copy(alpha = mainAlpha),
        Color.White.copy(alpha = midAlpha),
        Color.White.copy(alpha = oppositeAlpha),
    )
    val reverseEdge = if (horizontalLight) settings.lightDirXPercent > 0 else settings.lightDirYPercent > 0
    if (reverseEdge) edgeColors = edgeColors.reversed()

    if (edgeColors.any { it.alpha > 0f }) {
        val brush = if (horizontalLight) Brush.horizontalGradient(edgeColors) else Brush.verticalGradient(edgeColors)
        Box(Modifier.matchParentSize().border(BorderStroke(edgeWidth, brush), shape))

        // A faint opposing dark hairline is part of the optical boundary, not a generic outline:
        // it gives the bright rim something to contrast against on white/bright wallpapers.
        val darkColors = listOf(
            Color.Transparent,
            Color.Black.copy(alpha = (.022f * strength * (1f + reflection)).coerceIn(0f, .07f)),
            Color.Black.copy(alpha = (.07f * strength * (1f + .35f * reflection)).coerceIn(0f, .16f)),
        ).let { if (reverseEdge) it.reversed() else it }
        val darkBrush = if (horizontalLight) {
            Brush.horizontalGradient(darkColors)
        } else {
            Brush.verticalGradient(darkColors)
        }
        Box(
            Modifier.matchParentSize().border(
                BorderStroke((edgeWidth.value * .72f).coerceAtLeast(.7f).dp, darkBrush),
                shape,
            )
        )
    }

    if (settings.specular) {
        val specularAlpha = (.13f * strength * (1f + .75f * reflection) * (.55f + mainDirectional)).coerceIn(0f, .38f)
        if (specularAlpha > 0f) {
            Box(
                Modifier.matchParentSize().border(
                    BorderStroke((edgeWidth.value * .58f).coerceAtLeast(.55f).dp,
                        Color.White.copy(alpha = specularAlpha)),
                    shape,
                )
            )
        }
    }
}
