package com.jake.duolauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Sharp presentation layer for large glass surfaces.
 *
 * Kept as a platform View so it is guaranteed to render in the same hierarchy as the MIUI
 * PassBlur/refraction views instead of depending on Compose/platform-view interop ordering.
 */
final class MiuiGlassHighlightView extends View {
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final RectF rect = new RectF();

    private float cornerRadiusPx = 30f;
    private float brightness = 1f;
    private float strength = 1f;
    private float reflection = 0.28f;
    private float lighten = 0.16f;
    private float mainDirectional = 0.42f;
    private float oppositeDirectional = 0.14f;
    private float angleSoftness = 0.35f;
    private float edgeWidthPx = 1f;
    private float lightDirX = -0.5f;
    private float lightDirY = -0.8f;

    private boolean skyHaze = true;
    private boolean specular = true;
    private boolean litRim = true;
    private boolean oppositeRim = true;
    private boolean cornerRim = true;
    private boolean faceSheen = true;
    private boolean plainHighlight = true;
    private boolean caustics = true;

    MiuiGlassHighlightView(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void updateMaterial(
            float cornerRadiusPx,
            float brightness,
            float highlightStrength,
            float reflectionStrength,
            float reflectionLighten,
            float directionalIntensity,
            float oppositeIntensity,
            float angleSoftness,
            float edgeWidthPx,
            float lightDirX,
            float lightDirY,
            boolean skyHaze,
            boolean specular,
            boolean litRim,
            boolean oppositeRim,
            boolean cornerRim,
            boolean faceSheen,
            boolean plainHighlight,
            boolean caustics) {
        this.cornerRadiusPx = Math.max(0f, cornerRadiusPx);
        this.brightness = clamp(brightness, .5f, 2f);
        this.strength = clamp(highlightStrength, 0f, 2f);
        this.reflection = clamp(reflectionStrength, 0f, 2f);
        this.lighten = clamp(reflectionLighten, 0f, 1f);
        this.mainDirectional = clamp(directionalIntensity, 0f, 2f);
        this.oppositeDirectional = clamp(oppositeIntensity, 0f, 2f);
        this.angleSoftness = clamp(angleSoftness, 0.03f, 1f);
        this.edgeWidthPx = Math.max(0.7f, edgeWidthPx);
        this.lightDirX = lightDirX;
        this.lightDirY = lightDirY;
        this.skyHaze = skyHaze;
        this.specular = specular;
        this.litRim = litRim;
        this.oppositeRim = oppositeRim;
        this.cornerRim = cornerRim;
        this.faceSheen = faceSheen;
        this.plainHighlight = plainHighlight;
        this.caustics = caustics;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float w = getWidth();
        final float h = getHeight();
        if (w <= 1f || h <= 1f || strength <= 0f) return;

        final float radius = Math.min(cornerRadiusPx, Math.min(w, h) * 0.5f);
        rect.set(0f, 0f, w, h);
        clipPath.reset();
        clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW);

        final int save = canvas.save();
        canvas.clipPath(clipPath);

        if (Math.abs(brightness - 1f) > .001f) {
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setShader(null);
            if (brightness > 1f) {
                fillPaint.setColor(whiteAlpha(clamp((brightness - 1f) * .14f, 0f, .14f)));
            } else {
                fillPaint.setColor(Color.argb(
                        Math.round(255f * clamp((1f - brightness) * .18f, 0f, .18f)),
                        0, 0, 0));
            }
            canvas.drawRect(rect, fillPaint);
        }

        if (skyHaze) {
            int top = whiteAlpha(clamp(.18f * strength + .05f * lighten, 0f, .34f));
            int mid = whiteAlpha(clamp(.045f * strength, 0f, .12f));
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setShader(new LinearGradient(
                    0f, 0f, 0f, Math.max(1f, h * .72f),
                    new int[] { top, mid, Color.TRANSPARENT },
                    new float[] { 0f, .42f, 1f },
                    Shader.TileMode.CLAMP));
            canvas.drawRect(rect, fillPaint);
            fillPaint.setShader(null);
        }

        final boolean horizontal = Math.abs(lightDirX) >= Math.abs(lightDirY);
        final boolean reverse = horizontal ? lightDirX > 0f : lightDirY > 0f;

        if (faceSheen) {
            int strong = whiteAlpha(clamp(.13f * strength * (.65f + reflection), 0f, .26f));
            int soft = whiteAlpha(clamp(.05f * strength * (.5f + angleSoftness), 0f, .13f));
            int[] colors = reverse
                    ? new int[] { soft, Color.TRANSPARENT, strong }
                    : new int[] { strong, Color.TRANSPARENT, soft };
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setShader(horizontal
                    ? new LinearGradient(0f, 0f, w, 0f, colors, null, Shader.TileMode.CLAMP)
                    : new LinearGradient(0f, 0f, 0f, h, colors, null, Shader.TileMode.CLAMP));
            canvas.drawRect(rect, fillPaint);
            fillPaint.setShader(null);
        }

        if (caustics) {
            int glow = whiteAlpha(clamp(.035f * strength * (1f + reflection), 0f, .10f));
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setShader(new LinearGradient(
                    0f, h * .22f, 0f, h * .82f,
                    new int[] { Color.TRANSPARENT, glow, Color.TRANSPARENT },
                    new float[] { 0f, .54f, 1f },
                    Shader.TileMode.CLAMP));
            canvas.drawRect(rect, fillPaint);
            fillPaint.setShader(null);
        }

        canvas.restoreToCount(save);

        float mainAlpha = .19f * strength * (1f + .55f * reflection) * (1f + .30f * lighten);
        float oppositeAlpha = .065f * strength * (1f + .40f * reflection);
        if (!litRim) mainAlpha = 0f;
        if (!oppositeRim) oppositeAlpha = 0f;
        float midAlpha = cornerRim
                ? clamp(.085f * strength * (1f + .50f * angleSoftness), 0f, .24f)
                : 0f;
        float baseAlpha = plainHighlight ? clamp(.055f * strength, 0f, .16f) : 0f;
        mainAlpha = clamp(mainAlpha * mainDirectional + baseAlpha, 0f, .52f);
        oppositeAlpha = clamp(oppositeAlpha * oppositeDirectional + baseAlpha, 0f, .30f);

        int main = whiteAlpha(mainAlpha);
        int mid = whiteAlpha(midAlpha);
        int opposite = whiteAlpha(oppositeAlpha);
        int[] edgeColors = reverse
                ? new int[] { opposite, mid, main }
                : new int[] { main, mid, opposite };

        float inset = edgeWidthPx * .55f;
        RectF strokeRect = new RectF(inset, inset, w - inset, h - inset);
        float strokeRadius = Math.max(0f, radius - inset);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(edgeWidthPx);
        strokePaint.setShader(horizontal
                ? new LinearGradient(0f, 0f, w, 0f, edgeColors, null, Shader.TileMode.CLAMP)
                : new LinearGradient(0f, 0f, 0f, h, edgeColors, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(strokeRect, strokeRadius, strokeRadius, strokePaint);
        strokePaint.setShader(null);

        // Opposing dark hairline makes the bright glass edge visible on white wallpapers.
        int dark = Color.argb(
                Math.round(255f * clamp(.07f * strength * (1f + .35f * reflection), 0f, .16f)),
                0, 0, 0);
        int[] darkColors = reverse
                ? new int[] { dark, Color.TRANSPARENT, Color.TRANSPARENT }
                : new int[] { Color.TRANSPARENT, Color.TRANSPARENT, dark };
        strokePaint.setStrokeWidth(Math.max(.7f, edgeWidthPx * .72f));
        strokePaint.setShader(horizontal
                ? new LinearGradient(0f, 0f, w, 0f, darkColors, null, Shader.TileMode.CLAMP)
                : new LinearGradient(0f, 0f, 0f, h, darkColors, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(strokeRect, strokeRadius, strokeRadius, strokePaint);
        strokePaint.setShader(null);

        if (specular) {
            float specAlpha = clamp(
                    .13f * strength * (1f + .75f * reflection) * (.55f + mainDirectional),
                    0f, .38f);
            strokePaint.setColor(whiteAlpha(specAlpha));
            strokePaint.setStrokeWidth(Math.max(.55f, edgeWidthPx * .58f));
            canvas.drawRoundRect(strokeRect, strokeRadius, strokeRadius, strokePaint);
        }
    }

    private static int whiteAlpha(float alpha) {
        return Color.argb(Math.round(255f * clamp(alpha, 0f, 1f)), 255, 255, 255);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
