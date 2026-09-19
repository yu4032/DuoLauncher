package com.jake.duolauncher

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal enum class LiquidGlassRole {
    DOCK,
    PANEL,
    CARD,
    FOLDER,
    WIDGET,
    CONTROL,
    TRANSIENT,
}

internal data class LiquidGlassSettings(
    val enabled: Boolean = true,
    val passBlurEnabled: Boolean = true,
    val refractionEnabled: Boolean = true,
    val refractionCardsEnabled: Boolean = false,
    val captureScalePercent: Int = 50,
    val refractionStrengthPx: Int = 12,
    val refractionInsetPx: Int = 20,
    val chromatic: Int = 26,
    val dispersionRPercent: Int = 100,
    val dispersionBPercent: Int = 100,
    val dockEnabled: Boolean = true,
    val panelEnabled: Boolean = true,
    val cardEnabled: Boolean = true,
    val folderEnabled: Boolean = true,
    val widgetEnabled: Boolean = true,
    val controlEnabled: Boolean = true,
    val transientEnabled: Boolean = true,
    val blurRadiusPx: Int = 100,
    val followPaletteTint: Boolean = true,
    val tintAlpha: Int = 35,
    val tintRed: Int = 0,
    val tintGreen: Int = 0,
    val tintBlue: Int = 255,
    val highlightWidthPercent: Int = 100,
    val highlightAlphaPercent: Int = 100,
    val brightnessPercent: Int = 108,
    val reflectionStrengthPercent: Int = 28,
    val reflectionLightenPercent: Int = 16,
    val directionalAngleRangePercent: Int = 52,
    val directionalIntensityPercent: Int = 42,
    val oppositeIntensityPercent: Int = 14,
    val lightDirXPercent: Int = -50,
    val lightDirYPercent: Int = -80,
    val skyHaze: Boolean = true,
    val specular: Boolean = true,
    val litRim: Boolean = true,
    val oppositeRim: Boolean = true,
    val cornerRim: Boolean = true,
    val faceSheen: Boolean = true,
    val plainHighlight: Boolean = true,
    val caustics: Boolean = true,
) {
    fun enabledFor(role: LiquidGlassRole): Boolean = enabled && when (role) {
        LiquidGlassRole.DOCK -> dockEnabled
        LiquidGlassRole.PANEL -> panelEnabled
        LiquidGlassRole.CARD -> cardEnabled
        LiquidGlassRole.FOLDER -> folderEnabled
        LiquidGlassRole.WIDGET -> widgetEnabled
        LiquidGlassRole.CONTROL -> controlEnabled
        LiquidGlassRole.TRANSIENT -> transientEnabled
    }

    fun normalized() = copy(
        blurRadiusPx = blurRadiusPx.coerceIn(0, 400),
        captureScalePercent = captureScalePercent.coerceIn(25, 100),
        refractionStrengthPx = refractionStrengthPx.coerceIn(0, 80),
        refractionInsetPx = refractionInsetPx.coerceIn(1, 120),
        chromatic = chromatic.coerceIn(0, 80),
        dispersionRPercent = dispersionRPercent.coerceIn(0, 400),
        dispersionBPercent = dispersionBPercent.coerceIn(0, 400),
        tintAlpha = tintAlpha.coerceIn(0, 160),
        tintRed = tintRed.coerceIn(0, 255),
        tintGreen = tintGreen.coerceIn(0, 255),
        tintBlue = tintBlue.coerceIn(0, 255),
        highlightWidthPercent = highlightWidthPercent.coerceIn(50, 300),
        highlightAlphaPercent = highlightAlphaPercent.coerceIn(0, 200),
        brightnessPercent = brightnessPercent.coerceIn(50, 200),
        reflectionStrengthPercent = reflectionStrengthPercent.coerceIn(0, 200),
        reflectionLightenPercent = reflectionLightenPercent.coerceIn(0, 100),
        directionalAngleRangePercent = directionalAngleRangePercent.coerceIn(5, 150),
        directionalIntensityPercent = directionalIntensityPercent.coerceIn(0, 200),
        oppositeIntensityPercent = oppositeIntensityPercent.coerceIn(0, 200),
        lightDirXPercent = lightDirXPercent.coerceIn(-200, 200),
        lightDirYPercent = lightDirYPercent.coerceIn(-200, 200),
    )
}

internal class LiquidGlassStore(context: Context) {
    private val prefs = context.getSharedPreferences("liquid_glass", Context.MODE_PRIVATE)

    var state by mutableStateOf(load())
        private set

    fun set(value: LiquidGlassSettings) {
        val normalized = value.normalized()
        state = normalized
        prefs.edit()
            .putBoolean("enabled", normalized.enabled)
            .putBoolean("pass_blur_enabled", normalized.passBlurEnabled)
            .putBoolean("refraction_enabled", normalized.refractionEnabled)
            .putBoolean("refraction_cards_enabled", normalized.refractionCardsEnabled)
            .putInt("capture_scale_percent", normalized.captureScalePercent)
            .putInt("refraction_strength_px", normalized.refractionStrengthPx)
            .putInt("refraction_inset_px", normalized.refractionInsetPx)
            .putInt("chromatic", normalized.chromatic)
            .putInt("dispersion_r_percent", normalized.dispersionRPercent)
            .putInt("dispersion_b_percent", normalized.dispersionBPercent)
            .putBoolean("dock_enabled", normalized.dockEnabled)
            .putBoolean("panel_enabled", normalized.panelEnabled)
            .putBoolean("card_enabled", normalized.cardEnabled)
            .putBoolean("folder_enabled", normalized.folderEnabled)
            .putBoolean("widget_enabled", normalized.widgetEnabled)
            .putBoolean("control_enabled", normalized.controlEnabled)
            .putBoolean("transient_enabled", normalized.transientEnabled)
            .putInt("blur_radius_px", normalized.blurRadiusPx)
            .putBoolean("follow_palette_tint", normalized.followPaletteTint)
            .putInt("tint_alpha", normalized.tintAlpha)
            .putInt("tint_red", normalized.tintRed)
            .putInt("tint_green", normalized.tintGreen)
            .putInt("tint_blue", normalized.tintBlue)
            .putInt("highlight_width_percent", normalized.highlightWidthPercent)
            .putInt("highlight_alpha_percent", normalized.highlightAlphaPercent)
            .putInt("brightness_percent", normalized.brightnessPercent)
            .putInt("reflection_strength_percent", normalized.reflectionStrengthPercent)
            .putInt("reflection_lighten_percent", normalized.reflectionLightenPercent)
            .putInt("directional_angle_range_percent", normalized.directionalAngleRangePercent)
            .putInt("directional_intensity_percent", normalized.directionalIntensityPercent)
            .putInt("opposite_intensity_percent", normalized.oppositeIntensityPercent)
            .putInt("light_dir_x_percent", normalized.lightDirXPercent)
            .putInt("light_dir_y_percent", normalized.lightDirYPercent)
            .putBoolean("sky_haze", normalized.skyHaze)
            .putBoolean("specular", normalized.specular)
            .putBoolean("lit_rim", normalized.litRim)
            .putBoolean("opposite_rim", normalized.oppositeRim)
            .putBoolean("corner_rim", normalized.cornerRim)
            .putBoolean("face_sheen", normalized.faceSheen)
            .putBoolean("plain_highlight", normalized.plainHighlight)
            .putBoolean("caustics", normalized.caustics)
            .apply()
    }

    fun reset() = set(LiquidGlassSettings())

    private fun load(): LiquidGlassSettings {
        val d = LiquidGlassSettings()
        return LiquidGlassSettings(
            enabled = prefs.getBoolean("enabled", d.enabled),
            passBlurEnabled = prefs.getBoolean("pass_blur_enabled", d.passBlurEnabled),
            refractionEnabled = prefs.getBoolean("refraction_enabled", d.refractionEnabled),
            refractionCardsEnabled = prefs.getBoolean("refraction_cards_enabled", d.refractionCardsEnabled),
            captureScalePercent = prefs.getInt("capture_scale_percent", d.captureScalePercent),
            refractionStrengthPx = prefs.getInt("refraction_strength_px", d.refractionStrengthPx),
            refractionInsetPx = prefs.getInt("refraction_inset_px", d.refractionInsetPx),
            chromatic = prefs.getInt("chromatic", d.chromatic),
            dispersionRPercent = prefs.getInt("dispersion_r_percent", d.dispersionRPercent),
            dispersionBPercent = prefs.getInt("dispersion_b_percent", d.dispersionBPercent),
            dockEnabled = prefs.getBoolean("dock_enabled", d.dockEnabled),
            panelEnabled = prefs.getBoolean("panel_enabled", d.panelEnabled),
            cardEnabled = prefs.getBoolean("card_enabled", d.cardEnabled),
            folderEnabled = prefs.getBoolean("folder_enabled", d.folderEnabled),
            widgetEnabled = prefs.getBoolean("widget_enabled", d.widgetEnabled),
            controlEnabled = prefs.getBoolean("control_enabled", d.controlEnabled),
            transientEnabled = prefs.getBoolean("transient_enabled", d.transientEnabled),
            blurRadiusPx = prefs.getInt("blur_radius_px", d.blurRadiusPx),
            followPaletteTint = prefs.getBoolean("follow_palette_tint", d.followPaletteTint),
            tintAlpha = prefs.getInt("tint_alpha", d.tintAlpha),
            tintRed = prefs.getInt("tint_red", d.tintRed),
            tintGreen = prefs.getInt("tint_green", d.tintGreen),
            tintBlue = prefs.getInt("tint_blue", d.tintBlue),
            highlightWidthPercent = prefs.getInt("highlight_width_percent", d.highlightWidthPercent),
            highlightAlphaPercent = prefs.getInt("highlight_alpha_percent", d.highlightAlphaPercent),
            brightnessPercent = prefs.getInt("brightness_percent", d.brightnessPercent),
            reflectionStrengthPercent = prefs.getInt("reflection_strength_percent", d.reflectionStrengthPercent),
            reflectionLightenPercent = prefs.getInt("reflection_lighten_percent", d.reflectionLightenPercent),
            directionalAngleRangePercent = prefs.getInt("directional_angle_range_percent", d.directionalAngleRangePercent),
            directionalIntensityPercent = prefs.getInt("directional_intensity_percent", d.directionalIntensityPercent),
            oppositeIntensityPercent = prefs.getInt("opposite_intensity_percent", d.oppositeIntensityPercent),
            lightDirXPercent = prefs.getInt("light_dir_x_percent", d.lightDirXPercent),
            lightDirYPercent = prefs.getInt("light_dir_y_percent", d.lightDirYPercent),
            skyHaze = prefs.getBoolean("sky_haze", d.skyHaze),
            specular = prefs.getBoolean("specular", d.specular),
            litRim = prefs.getBoolean("lit_rim", d.litRim),
            oppositeRim = prefs.getBoolean("opposite_rim", d.oppositeRim),
            cornerRim = prefs.getBoolean("corner_rim", d.cornerRim),
            faceSheen = prefs.getBoolean("face_sheen", d.faceSheen),
            plainHighlight = prefs.getBoolean("plain_highlight", d.plainHighlight),
            caustics = prefs.getBoolean("caustics", d.caustics),
        ).normalized()
    }
}

internal val LocalLiquidGlassSettings = staticCompositionLocalOf { LiquidGlassSettings() }
internal val LocalLiquidGlassUpdate = staticCompositionLocalOf<(LiquidGlassSettings) -> Unit> { {} }

private enum class LiquidGlassPreset(val label: String) {
    LIQUID_DOCK("LiquidDock"),
    CLEAR("Clear"),
    FROSTED("Frosted"),
    OS4("OS4"),
}

private fun LiquidGlassPreset.settings(current: LiquidGlassSettings): LiquidGlassSettings = when (this) {
    LiquidGlassPreset.LIQUID_DOCK -> LiquidGlassSettings(
        enabled = current.enabled,
        dockEnabled = current.dockEnabled,
        panelEnabled = current.panelEnabled,
        cardEnabled = current.cardEnabled,
        folderEnabled = current.folderEnabled,
        widgetEnabled = current.widgetEnabled,
        controlEnabled = current.controlEnabled,
        transientEnabled = current.transientEnabled,
    )
    LiquidGlassPreset.CLEAR -> current.copy(
        blurRadiusPx = 64,
        refractionStrengthPx = 7,
        refractionInsetPx = 14,
        chromatic = 10,
        tintAlpha = 12,
        highlightWidthPercent = 80,
        highlightAlphaPercent = 72,
        brightnessPercent = 102,
        reflectionStrengthPercent = 18,
        reflectionLightenPercent = 8,
        directionalIntensityPercent = 28,
        oppositeIntensityPercent = 8,
    )
    LiquidGlassPreset.FROSTED -> current.copy(
        blurRadiusPx = 180,
        refractionStrengthPx = 8,
        refractionInsetPx = 24,
        chromatic = 8,
        tintAlpha = 76,
        highlightWidthPercent = 125,
        highlightAlphaPercent = 112,
        brightnessPercent = 112,
        reflectionStrengthPercent = 18,
        reflectionLightenPercent = 24,
        directionalIntensityPercent = 24,
        oppositeIntensityPercent = 12,
    )
    LiquidGlassPreset.OS4 -> current.copy(
        blurRadiusPx = 100,
        refractionStrengthPx = 12,
        refractionInsetPx = 20,
        chromatic = 26,
        dispersionRPercent = 100,
        dispersionBPercent = 100,
        tintAlpha = 35,
        highlightWidthPercent = 100,
        highlightAlphaPercent = 100,
        brightnessPercent = 108,
        reflectionStrengthPercent = 28,
        reflectionLightenPercent = 16,
        directionalAngleRangePercent = 52,
        directionalIntensityPercent = 42,
        oppositeIntensityPercent = 14,
        lightDirXPercent = -50,
        lightDirYPercent = -80,
    )
}

@Composable
internal fun LiquidGlassSettingsPanel() {
    val settings = LocalLiquidGlassSettings.current
    val update = LocalLiquidGlassUpdate.current

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.semantics { contentDescription = "Liquid glass settings" },
    ) {
        Text("Liquid glass", style = MaterialTheme.typography.titleMedium)
        Text(
            "MIUI PassBlur supplies the live backdrop. Edge light, haze, tint, and directional reflection are rendered separately so they stay sharp.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GlassSettingSwitch("Enable liquid glass", settings.enabled) { update(settings.copy(enabled = it)) }
        GlassSettingSwitch("Use MIUI PassBlur", settings.passBlurEnabled, settings.enabled) {
            update(settings.copy(passBlurEnabled = it))
        }

        HorizontalDivider()
        Text("True refraction", style = MaterialTheme.typography.titleSmall)
        Text(
            "Uses the HyperOS SurfaceControl PassBlur producer as a GPU texture, then displaces background UVs before sharp highlights are added. One visible large surface owns the producer at a time; other glass keeps the normal PassBlur fallback.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GlassSettingSwitch(
            "Enable GPU refraction",
            settings.refractionEnabled,
            settings.enabled && settings.passBlurEnabled,
        ) { update(settings.copy(refractionEnabled = it)) }
        GlassSettingSwitch(
            "Allow refraction on cards",
            settings.refractionCardsEnabled,
            settings.enabled && settings.passBlurEnabled && settings.refractionEnabled,
        ) { update(settings.copy(refractionCardsEnabled = it)) }
        GlassSettingSlider(
            "Producer capture scale",
            settings.captureScalePercent,
            25..100,
            "%",
            settings.enabled && settings.passBlurEnabled && settings.refractionEnabled,
        ) { update(settings.copy(captureScalePercent = it)) }
        GlassSettingSlider(
            "Refraction strength",
            settings.refractionStrengthPx,
            0..80,
            " px",
            settings.enabled && settings.passBlurEnabled && settings.refractionEnabled,
        ) { update(settings.copy(refractionStrengthPx = it)) }
        GlassSettingSlider(
            "Refraction edge inset",
            settings.refractionInsetPx,
            1..120,
            " px",
            settings.enabled && settings.passBlurEnabled && settings.refractionEnabled,
        ) { update(settings.copy(refractionInsetPx = it)) }
        GlassSettingSlider(
            "Chromatic dispersion",
            settings.chromatic,
            0..80,
            "",
            settings.enabled && settings.passBlurEnabled && settings.refractionEnabled,
        ) { update(settings.copy(chromatic = it)) }
        GlassSettingSlider(
            "Red dispersion",
            settings.dispersionRPercent,
            0..400,
            "%",
            settings.enabled && settings.passBlurEnabled && settings.refractionEnabled,
        ) { update(settings.copy(dispersionRPercent = it)) }
        GlassSettingSlider(
            "Blue dispersion",
            settings.dispersionBPercent,
            0..400,
            "%",
            settings.enabled && settings.passBlurEnabled && settings.refractionEnabled,
        ) { update(settings.copy(dispersionBPercent = it)) }

        Text("Presets", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiquidGlassPreset.entries.forEach { preset ->
                FilterChip(
                    selected = false,
                    onClick = { update(preset.settings(settings)) },
                    label = { Text(preset.label) },
                    enabled = settings.enabled,
                )
            }
        }

        HorizontalDivider()
        Text("Surfaces", style = MaterialTheme.typography.titleSmall)
        GlassSettingSwitch("Dock", settings.dockEnabled, settings.enabled) { update(settings.copy(dockEnabled = it)) }
        GlassSettingSwitch("Panels and sheets", settings.panelEnabled, settings.enabled) { update(settings.copy(panelEnabled = it)) }
        GlassSettingSwitch("Cards", settings.cardEnabled, settings.enabled) { update(settings.copy(cardEnabled = it)) }
        GlassSettingSwitch("Folders", settings.folderEnabled, settings.enabled) { update(settings.copy(folderEnabled = it)) }
        GlassSettingSwitch("Widgets", settings.widgetEnabled, settings.enabled) { update(settings.copy(widgetEnabled = it)) }
        GlassSettingSwitch("Controls", settings.controlEnabled, settings.enabled) { update(settings.copy(controlEnabled = it)) }
        GlassSettingSwitch("Drag and transient surfaces", settings.transientEnabled, settings.enabled) {
            update(settings.copy(transientEnabled = it))
        }

        HorizontalDivider()
        Text("Backdrop and tint", style = MaterialTheme.typography.titleSmall)
        GlassSettingSlider("PassBlur radius", settings.blurRadiusPx, 0..400, " px", settings.enabled) {
            update(settings.copy(blurRadiusPx = it))
        }
        GlassSettingSwitch("Follow Duo palette tint", settings.followPaletteTint, settings.enabled) {
            update(settings.copy(followPaletteTint = it))
        }
        GlassSettingSlider("Tint alpha", settings.tintAlpha, 0..160, "", settings.enabled) {
            update(settings.copy(tintAlpha = it))
        }
        if (!settings.followPaletteTint) {
            GlassSettingSlider("Tint red", settings.tintRed, 0..255, "", settings.enabled) {
                update(settings.copy(tintRed = it))
            }
            GlassSettingSlider("Tint green", settings.tintGreen, 0..255, "", settings.enabled) {
                update(settings.copy(tintGreen = it))
            }
            GlassSettingSlider("Tint blue", settings.tintBlue, 0..255, "", settings.enabled) {
                update(settings.copy(tintBlue = it))
            }
        }
        GlassSettingSlider("Brightness", settings.brightnessPercent, 50..200, "%", settings.enabled) {
            update(settings.copy(brightnessPercent = it))
        }

        HorizontalDivider()
        Text("Edge and directional light", style = MaterialTheme.typography.titleSmall)
        GlassSettingSlider("Highlight width", settings.highlightWidthPercent, 50..300, "%", settings.enabled) {
            update(settings.copy(highlightWidthPercent = it))
        }
        GlassSettingSlider("Highlight strength", settings.highlightAlphaPercent, 0..200, "%", settings.enabled) {
            update(settings.copy(highlightAlphaPercent = it))
        }
        GlassSettingSlider("Reflection strength", settings.reflectionStrengthPercent, 0..200, "%", settings.enabled) {
            update(settings.copy(reflectionStrengthPercent = it))
        }
        GlassSettingSlider("Dark-background lift", settings.reflectionLightenPercent, 0..100, "%", settings.enabled) {
            update(settings.copy(reflectionLightenPercent = it))
        }
        GlassSettingSlider("Directional angle range", settings.directionalAngleRangePercent, 5..150, "%π", settings.enabled) {
            update(settings.copy(directionalAngleRangePercent = it))
        }
        GlassSettingSlider("Main directional light", settings.directionalIntensityPercent, 0..200, "%", settings.enabled) {
            update(settings.copy(directionalIntensityPercent = it))
        }
        GlassSettingSlider("Opposite fill light", settings.oppositeIntensityPercent, 0..200, "%", settings.enabled) {
            update(settings.copy(oppositeIntensityPercent = it))
        }
        GlassSettingSlider("Light direction X", settings.lightDirXPercent, -200..200, "%", settings.enabled) {
            update(settings.copy(lightDirXPercent = it))
        }
        GlassSettingSlider("Light direction Y", settings.lightDirYPercent, -200..200, "%", settings.enabled) {
            update(settings.copy(lightDirYPercent = it))
        }

        HorizontalDivider()
        Text("Highlight components", style = MaterialTheme.typography.titleSmall)
        GlassSettingSwitch("Sky haze", settings.skyHaze, settings.enabled) { update(settings.copy(skyHaze = it)) }
        GlassSettingSwitch("Specular", settings.specular, settings.enabled) { update(settings.copy(specular = it)) }
        GlassSettingSwitch("Lit rim", settings.litRim, settings.enabled) { update(settings.copy(litRim = it)) }
        GlassSettingSwitch("Opposite rim", settings.oppositeRim, settings.enabled) { update(settings.copy(oppositeRim = it)) }
        GlassSettingSwitch("Corner rim", settings.cornerRim, settings.enabled) { update(settings.copy(cornerRim = it)) }
        GlassSettingSwitch("Face sheen", settings.faceSheen, settings.enabled) { update(settings.copy(faceSheen = it)) }
        GlassSettingSwitch("Plain highlight", settings.plainHighlight, settings.enabled) {
            update(settings.copy(plainHighlight = it))
        }
        GlassSettingSwitch("Caustic wash", settings.caustics, settings.enabled) { update(settings.copy(caustics = it)) }

        Text(
            "Rim, specular, haze, face sheen, and caustic controls are composited after refraction, so their edges remain sharp even when backdrop blur is strong.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { update(LiquidGlassSettings()) }, modifier = Modifier.fillMaxWidth()) {
            Text("Reset liquid glass")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun GlassSettingSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

@Composable
private fun GlassSettingSlider(
    label: String,
    value: Int,
    range: IntRange,
    suffix: String,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Text("$value$suffix", color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}
