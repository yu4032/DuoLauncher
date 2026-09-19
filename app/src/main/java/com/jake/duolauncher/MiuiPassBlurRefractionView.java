package com.jake.duolauncher;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.AttachedSurfaceControl;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.TextureView;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Zero-copy HyperOS PassBlur producer used for true background-UV refraction.
 *
 * SurfaceFlinger writes the backdrop into an external-OES SurfaceTexture. This view samples that
 * texture directly on a GL thread and displaces UVs around the glass edge. Pixel data never crosses
 * to the CPU. Only one PassBlur producer can own a ViewRoot at a time, so RefractionAuthority
 * arbitrates visible Duo glass surfaces; non-owners stay transparent and reveal the ordinary
 * per-View PassBlur fallback below them.
 */
final class MiuiPassBlurRefractionView extends TextureView
        implements TextureView.SurfaceTextureListener {
    private static final String TAG = "DuoRefraction";
    private static final int MAX_BIND_RETRIES = 16;

    private static final float[] QUAD = new float[] {
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f
    };

    private static final String VERTEX = """
            attribute vec2 aPosition;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
            """;

    /**
     * Prismal-derived edge optics in an OES-native pass.
     *
     * The backdrop mapping/crop compensation is the same contract used by LiquidDock's validated
     * HyperOS 3.0.307 producer adapter. The SDF-derived normal then drives real UV displacement,
     * RGB dispersion and an inexpensive 9-tap local blur. Sharp rim/specular presentation remains
     * in Compose so it is never softened by the backdrop filter.
     */
    private static final String FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;

            uniform samplerExternalOES uTexture;
            uniform mat4 uTexMatrix;
            uniform vec4 uBackdropRect;
            uniform int uConfigRot;
            uniform vec2 uViewSize;
            uniform vec2 uRootSize;
            uniform float uCornerRadius;
            uniform float uRefractionStrength;
            uniform float uRefractionInset;
            uniform float uChromatic;
            uniform float uDispersionR;
            uniform float uDispersionB;
            uniform float uBlurStepPx;
            uniform vec4 uTintColor;
            varying vec2 vUv;

            vec2 orientRootUv(vec2 rootUv) {
                if (uConfigRot == 1) {
                    return vec2(rootUv.y, 1.0 - rootUv.x);
                } else if (uConfigRot == 2) {
                    return vec2(1.0 - rootUv.x, 1.0 - rootUv.y);
                } else if (uConfigRot == 3) {
                    return vec2(1.0 - rootUv.y, rootUv.x);
                }
                return rootUv;
            }

            vec2 compensateCrop(vec2 orientedUv) {
                vec2 column0 = vec2(uTexMatrix[0][0], uTexMatrix[0][1]);
                vec2 column1 = vec2(uTexMatrix[1][0], uTexMatrix[1][1]);
                float scale0 = length(column0);
                float scale1 = length(column1);
                float a00 = uTexMatrix[0][0];
                float a01 = uTexMatrix[1][0];
                float a10 = uTexMatrix[0][1];
                float a11 = uTexMatrix[1][1];
                float determinant = a00 * a11 - a01 * a10;
                if (scale0 <= 0.000001 || scale1 <= 0.000001 || abs(determinant) <= 0.000001) {
                    return orientedUv;
                }
                vec2 orientation0 = column0 / scale0;
                vec2 orientation1 = column1 / scale1;
                if (abs(dot(orientation0, orientation1)) > 0.001) return orientedUv;
                vec2 orientationBias = vec2(
                        -min(0.0, orientation0.x) - min(0.0, orientation1.x),
                        -min(0.0, orientation0.y) - min(0.0, orientation1.y));
                vec2 desired = orientation0 * orientedUv.x
                        + orientation1 * orientedUv.y + orientationBias;
                vec2 translation = vec2(uTexMatrix[3][0], uTexMatrix[3][1]);
                vec2 rhs = desired - translation;
                return vec2(
                        (a11 * rhs.x - a01 * rhs.y) / determinant,
                        (-a10 * rhs.x + a00 * rhs.y) / determinant);
            }

            vec4 sampleRoot(vec2 rootUv) {
                vec2 orientedUv = orientRootUv(rootUv);
                vec2 inputUv = compensateCrop(orientedUv);
                vec4 transformed = uTexMatrix * vec4(inputUv, 0.0, 1.0);
                return texture2D(uTexture, clamp(transformed.xy, vec2(0.0), vec2(1.0)));
            }

            vec3 sampleBlurred(vec2 rootUv) {
                vec2 texel = vec2(
                        uBlurStepPx / max(uRootSize.x, 1.0),
                        uBlurStepPx / max(uRootSize.y, 1.0));
                vec3 c = sampleRoot(rootUv).rgb * 0.24;
                c += sampleRoot(rootUv + vec2( texel.x, 0.0)).rgb * 0.12;
                c += sampleRoot(rootUv + vec2(-texel.x, 0.0)).rgb * 0.12;
                c += sampleRoot(rootUv + vec2(0.0,  texel.y)).rgb * 0.12;
                c += sampleRoot(rootUv + vec2(0.0, -texel.y)).rgb * 0.12;
                c += sampleRoot(rootUv + vec2( texel.x,  texel.y)).rgb * 0.07;
                c += sampleRoot(rootUv + vec2(-texel.x,  texel.y)).rgb * 0.07;
                c += sampleRoot(rootUv + vec2( texel.x, -texel.y)).rgb * 0.07;
                c += sampleRoot(rootUv + vec2(-texel.x, -texel.y)).rgb * 0.07;
                return c;
            }

            float sdRoundBox(vec2 p, vec2 b, float r) {
                vec2 q = abs(p) - b + r;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
            }

            float distAt(vec2 p) {
                vec2 halfSize = max(uViewSize * 0.5 - vec2(1.0), vec2(1.0));
                float r = min(uCornerRadius, min(halfSize.x, halfSize.y));
                return sdRoundBox(p, halfSize, r);
            }

            void main() {
                vec2 p = (vUv - 0.5) * uViewSize;
                float d = distAt(p);
                float alpha = 1.0 - smoothstep(-0.25, 1.4, d);
                if (alpha <= 0.001) discard;

                float inset = max(uRefractionInset, 1.0);
                float edge = 1.0 - smoothstep(0.0, inset, max(-d, 0.0));
                edge = pow(edge, 0.82);

                vec2 ex = vec2(1.0, 0.0);
                vec2 ey = vec2(0.0, 1.0);
                vec2 grad = vec2(
                        distAt(p + ex) - distAt(p - ex),
                        distAt(p + ey) - distAt(p - ey));
                vec2 normal = length(grad) > 0.0001 ? normalize(grad) : vec2(0.0, 1.0);

                float minDim = max(min(uViewSize.x, uViewSize.y), 1.0);
                vec2 centerDir = length(p) > 0.001 ? normalize(p) : vec2(0.0);
                float centerLens = smoothstep(minDim * 0.55, 0.0, length(p)) * 0.12;
                vec2 displacementPx = normal * uRefractionStrength * edge;
                displacementPx += centerDir * uRefractionStrength * centerLens * edge;

                vec2 rootUv = uBackdropRect.xy + vUv * uBackdropRect.zw;
                vec2 displacedRoot = rootUv + displacementPx / max(uRootSize, vec2(1.0));

                float chromaPx = uChromatic * 0.075 * edge;
                vec2 chromaUv = normal * chromaPx / max(uRootSize, vec2(1.0));
                vec2 uvR = displacedRoot + chromaUv * uDispersionR;
                vec2 uvB = displacedRoot - chromaUv * uDispersionB;

                vec3 center = sampleBlurred(displacedRoot);
                float r = sampleBlurred(uvR).r;
                float b = sampleBlurred(uvB).b;
                vec3 color = vec3(r, center.g, b);

                // Preserve a little center contrast while the meniscus carries the stronger blur.
                vec3 direct = sampleRoot(displacedRoot).rgb;
                float meniscus = smoothstep(0.0, 0.72, edge);
                color = mix(direct, color, 0.30 + 0.70 * meniscus);
                color = mix(color, uTintColor.rgb, clamp(uTintColor.a, 0.0, 0.72));

                gl_FragColor = vec4(color, alpha);
            }
            """;

    private static final class AuthorityEntry {
        int priority;
        long sequence;
        boolean requested;

        AuthorityEntry(int priority, long sequence, boolean requested) {
            this.priority = priority;
            this.sequence = sequence;
            this.requested = requested;
        }
    }

    /**
     * SurfaceControl has one PassBlur producer slot per root. Keep exactly one owner.
     * Higher-priority modal/panel surfaces preempt the Dock; ties prefer the newest visible host.
     */
    private static final class RefractionAuthority {
        private static final Object LOCK = new Object();
        private static final IdentityHashMap<MiuiPassBlurRefractionView, AuthorityEntry> ENTRIES =
                new IdentityHashMap<>();
        private static long nextSequence;
        private static WeakReference<MiuiPassBlurRefractionView> ownerRef =
                new WeakReference<>(null);

        static void update(MiuiPassBlurRefractionView view, boolean requested, int priority) {
            MiuiPassBlurRefractionView oldOwner;
            MiuiPassBlurRefractionView newOwner;
            synchronized (LOCK) {
                AuthorityEntry entry = ENTRIES.get(view);
                if (entry == null) {
                    entry = new AuthorityEntry(priority, ++nextSequence, requested);
                    ENTRIES.put(view, entry);
                } else {
                    boolean becameRequested = !entry.requested && requested;
                    entry.priority = priority;
                    entry.requested = requested;
                    if (becameRequested) entry.sequence = ++nextSequence;
                }
                if (!requested) ENTRIES.remove(view);
                oldOwner = ownerRef.get();
                newOwner = chooseOwnerLocked();
                ownerRef = new WeakReference<>(newOwner);
            }
            if (oldOwner != newOwner) {
                if (oldOwner != null) oldOwner.post(() -> oldOwner.onAuthorityChanged(false));
                if (newOwner != null) newOwner.post(() -> newOwner.onAuthorityChanged(true));
            }
        }

        static void unregister(MiuiPassBlurRefractionView view) {
            MiuiPassBlurRefractionView oldOwner;
            MiuiPassBlurRefractionView newOwner;
            synchronized (LOCK) {
                ENTRIES.remove(view);
                oldOwner = ownerRef.get();
                newOwner = chooseOwnerLocked();
                ownerRef = new WeakReference<>(newOwner);
            }
            if (oldOwner != newOwner) {
                if (oldOwner != null) oldOwner.post(() -> oldOwner.onAuthorityChanged(false));
                if (newOwner != null) newOwner.post(() -> newOwner.onAuthorityChanged(true));
            }
        }

        static boolean isOwner(MiuiPassBlurRefractionView view) {
            synchronized (LOCK) {
                return ownerRef.get() == view;
            }
        }

        private static MiuiPassBlurRefractionView chooseOwnerLocked() {
            MiuiPassBlurRefractionView best = null;
            AuthorityEntry bestEntry = null;
            for (Map.Entry<MiuiPassBlurRefractionView, AuthorityEntry> item : ENTRIES.entrySet()) {
                MiuiPassBlurRefractionView candidate = item.getKey();
                AuthorityEntry entry = item.getValue();
                if (candidate == null || entry == null || !entry.requested
                        || !candidate.isAttachedToWindow()
                        || candidate.getVisibility() != VISIBLE) {
                    continue;
                }
                if (bestEntry == null
                        || entry.priority > bestEntry.priority
                        || (entry.priority == bestEntry.priority
                            && entry.sequence > bestEntry.sequence)) {
                    best = candidate;
                    bestEntry = entry;
                }
            }
            return best;
        }
    }

    private static final class Binding {
        final SurfaceControl captureNode;
        final Class<?> transactionClass;
        final Method setPassBlurSurface;
        final Method setUpdateTextureFlag;
        final Method setMiBlurWinExc;
        final Method apply;
        final Method close;
        final float scale;
        final boolean ownsTarget;
        boolean bound = true;

        Binding(SurfaceControl captureNode, Class<?> transactionClass,
                Method setPassBlurSurface, Method setUpdateTextureFlag,
                Method setMiBlurWinExc, Method apply, Method close, float scale,
                boolean ownsTarget) {
            this.captureNode = captureNode;
            this.transactionClass = transactionClass;
            this.setPassBlurSurface = setPassBlurSurface;
            this.setUpdateTextureFlag = setUpdateTextureFlag;
            this.setMiBlurWinExc = setMiBlurWinExc;
            this.apply = apply;
            this.close = close;
            this.scale = scale;
            this.ownsTarget = ownsTarget;
        }
    }

    private final Handler mainHandler;
    private final HandlerThread renderThread;
    private final Handler renderHandler;
    private final FloatBuffer quad;
    private final AtomicBoolean drawQueued = new AtomicBoolean(false);
    private final float[] texMatrix = new float[16];
    private final int[] windowLocation = new int[2];

    private volatile boolean requested;
    private volatile boolean authorityOwner;
    private volatile boolean shuttingDown;
    private volatile boolean producerActive;
    private volatile int priority;
    private volatile int captureScalePercent = 100;
    private volatile int blurRadiusPx = 100;
    private volatile float refractionStrengthPx = 12f;
    private volatile float refractionInsetPx = 20f;
    private volatile float chromatic = 26f;
    private volatile float dispersionR = 1f;
    private volatile float dispersionB = 1f;
    private volatile float cornerRadiusPx = 30f;
    private volatile float tintR;
    private volatile float tintG;
    private volatile float tintB;
    private volatile float tintA;

    private volatile float backdropX;
    private volatile float backdropY;
    private volatile float backdropW = 1f;
    private volatile float backdropH = 1f;
    private volatile int rootWidth = 1;
    private volatile int rootHeight = 1;
    private volatile int configRotation;

    private SurfaceTexture outputSurfaceTexture;
    private Surface outputWindow;
    private int outputWidth;
    private int outputHeight;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLConfig eglConfig;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglWindowSurface = EGL14.EGL_NO_SURFACE;

    private int program;
    private int oesTexture;
    private SurfaceTexture inputSurfaceTexture;
    private Surface inputProducerSurface;
    private Binding binding;
    private int bindAttempts;
    private boolean activeLogged;
    private boolean failureLogged;
    private volatile boolean producerFrameSeen;
    private volatile boolean presentedFrameSeen;
    private int noFrameRecoveryAttempts;
    private boolean preferRawRootTarget;

    MiuiPassBlurRefractionView(Context context) {
        super(context);
        mainHandler = new Handler(context.getMainLooper());
        renderThread = new HandlerThread("Duo-PassBlur-Refraction");
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());

        quad = ByteBuffer.allocateDirect(QUAD.length * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(QUAD).position(0);

        setOpaque(false);
        setSurfaceTextureListener(this);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void updateMaterial(boolean enableRefraction, int priority, int captureScalePercent,
                        int blurRadiusPx, float refractionStrengthPx, float refractionInsetPx,
                        float chromatic, float dispersionR, float dispersionB,
                        float cornerRadiusPx, int tintArgb) {
        int oldCaptureScalePercent = this.captureScalePercent;
        this.requested = enableRefraction;
        this.priority = priority;
        this.captureScalePercent = Math.max(50, Math.min(100, captureScalePercent));
        this.blurRadiusPx = Math.max(0, Math.min(400, blurRadiusPx));
        this.refractionStrengthPx = Math.max(0f, Math.min(80f, refractionStrengthPx));
        this.refractionInsetPx = Math.max(1f, Math.min(120f, refractionInsetPx));
        this.chromatic = Math.max(0f, Math.min(80f, chromatic));
        this.dispersionR = Math.max(0f, Math.min(4f, dispersionR));
        this.dispersionB = Math.max(0f, Math.min(4f, dispersionB));
        this.cornerRadiusPx = Math.max(0f, cornerRadiusPx);
        this.tintR = android.graphics.Color.red(tintArgb) / 255f;
        this.tintG = android.graphics.Color.green(tintArgb) / 255f;
        this.tintB = android.graphics.Color.blue(tintArgb) / 255f;
        this.tintA = android.graphics.Color.alpha(tintArgb) / 255f;
        updateBackdropMapping();
        RefractionAuthority.update(this,
                enableRefraction && isAttachedToWindow() && getVisibility() == VISIBLE,
                priority);
        if (producerActive && oldCaptureScalePercent != this.captureScalePercent) {
            post(() -> {
                if (!authorityOwner || !requested || shuttingDown) return;
                producerActive = false;
                unbindProducer();
                recreateInputProducer("capture-scale");
            });
        } else if (producerActive) {
            requestDraw();
        }
    }

    boolean isProducerActive() {
        return producerActive;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateBackdropMapping();
        if (requested) RefractionAuthority.update(this, true, priority);
    }

    @Override
    protected void onDetachedFromWindow() {
        RefractionAuthority.unregister(this);
        shutdown();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (!isAttachedToWindow()) return;
        RefractionAuthority.update(this, requested && visibility == VISIBLE, priority);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateBackdropMapping();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (shuttingDown) return;
        outputSurfaceTexture = surface;
        outputWidth = Math.max(1, width);
        outputHeight = Math.max(1, height);
        Surface window = new Surface(surface);
        outputWindow = window;
        renderHandler.post(() -> attachOutput(window));
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        outputWidth = Math.max(1, width);
        outputHeight = Math.max(1, height);
        updateBackdropMapping();
        requestDraw();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        outputSurfaceTexture = null;
        Surface window = outputWindow;
        outputWindow = null;
        renderHandler.post(() -> destroyOutput(window));
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // Output-only callback.
    }

    private void onAuthorityChanged(boolean owner) {
        if (shuttingDown) return;
        authorityOwner = owner;
        if (owner && requested) {
            bindAttempts = 0;
            noFrameRecoveryAttempts = 0;
            preferRawRootTarget = false;
            attemptBindWhenReady();
        } else {
            unbindProducer();
            producerActive = false;
            renderHandler.post(this::clearOutput);
        }
    }

    private void attachOutput(Surface window) {
        if (shuttingDown) {
            window.release();
            return;
        }
        try {
            ensureEglContext();
            destroyEglWindowOnly();
            int[] attrs = {EGL14.EGL_NONE};
            eglWindowSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay, eglConfig, window, attrs, 0);
            checkEgl("eglCreateWindowSurface", eglWindowSurface != EGL14.EGL_NO_SURFACE);
            makeCurrent();
            ensureProgram();
            ensureInputProducer();
            clearOutput();
            mainHandler.post(this::attemptBindWhenReady);
        } catch (Throwable error) {
            fail("output attach", error);
        }
    }

    private void ensureEglContext() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT) return;
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        checkEgl("eglGetDisplay", display != EGL14.EGL_NO_DISPLAY);
        int[] version = new int[2];
        checkEgl("eglInitialize", EGL14.eglInitialize(display, version, 0, version, 1));
        int[] configAttrs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        checkEgl("eglChooseConfig", EGL14.eglChooseConfig(
                display, configAttrs, 0, configs, 0, 1, count, 0) && count[0] > 0);
        int[] contextAttrs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        EGLContext context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttrs, 0);
        checkEgl("eglCreateContext", context != EGL14.EGL_NO_CONTEXT);
        eglDisplay = display;
        eglConfig = configs[0];
        eglContext = context;
    }

    private void ensureProgram() {
        if (program != 0) return;
        program = createProgram(VERTEX, FRAGMENT);
        if (program == 0) throw new IllegalStateException("refraction program link failed");
    }

    private void ensureInputProducer() {
        if (inputSurfaceTexture != null && inputProducerSurface != null && oesTexture != 0) {
            resizeInputProducer();
            return;
        }
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        oesTexture = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        inputSurfaceTexture = new SurfaceTexture(oesTexture);
        resizeInputProducer();
        inputSurfaceTexture.setOnFrameAvailableListener(ignored -> {
            if (!producerFrameSeen) {
                producerFrameSeen = true;
                Log.i(TAG, "first PassBlur producer frame received");
            }
            requestDraw();
        }, renderHandler);
        inputProducerSurface = new Surface(inputSurfaceTexture);
    }

    private void resizeInputProducer() {
        SurfaceTexture input = inputSurfaceTexture;
        if (input == null) return;
        float scale = captureScalePercent / 100f;
        input.setDefaultBufferSize(
                Math.max(1, Math.round(rootWidth * scale)),
                Math.max(1, Math.round(rootHeight * scale)));
    }

    private void recreateInputProducer(String reason) {
        if (shuttingDown) return;
        producerFrameSeen = false;
        presentedFrameSeen = false;
        renderHandler.post(() -> {
            try {
                makeCurrent();
                Surface staleSurface = inputProducerSurface;
                SurfaceTexture staleTexture = inputSurfaceTexture;
                inputProducerSurface = null;
                inputSurfaceTexture = null;

                if (staleSurface != null) staleSurface.release();
                if (staleTexture != null) {
                    try { staleTexture.setOnFrameAvailableListener(null); } catch (Throwable ignored) {}
                    staleTexture.release();
                }
                if (oesTexture != 0) {
                    GLES20.glDeleteTextures(1, new int[]{oesTexture}, 0);
                    oesTexture = 0;
                }

                ensureInputProducer();
                Log.i(TAG, "PassBlur input producer recreated reason=" + reason);
                mainHandler.post(() -> {
                    if (shuttingDown || !requested || !authorityOwner) return;
                    bindAttempts = 0;
                    attemptBindWhenReady();
                });
            } catch (Throwable error) {
                fail("producer recreate", error);
            }
        });
    }

    private void verifyFirstProducerFrame(int framesLeft) {
        if (shuttingDown || !requested || !authorityOwner || !producerActive) return;
        if (producerFrameSeen) return;
        if (framesLeft > 0) {
            postOnAnimation(() -> verifyFirstProducerFrame(framesLeft - 1));
            return;
        }

        Log.w(TAG, "producer transaction applied but no SurfaceFlinger frame arrived"
                + " target=" + (preferRawRootTarget ? "raw-root" : "public-child"));
        producerActive = false;
        unbindProducer();

        if (!preferRawRootTarget) {
            // Public child attachment is the preferred normal-app path. Some MIUI builds only
            // drive PassBlur producers from a window root, so use the already-public root
            // attachment as the boundary for one raw-root fallback attempt.
            preferRawRootTarget = true;
            noFrameRecoveryAttempts = 0;
            recreateInputProducer("switch-to-raw-root");
        } else if (noFrameRecoveryAttempts++ < 1) {
            recreateInputProducer("raw-root-no-first-frame");
        } else {
            Log.w(TAG, "refraction disabled after child+root producer attempts; View PassBlur remains active");
        }
    }

    private void attemptBindWhenReady() {
        if (shuttingDown || !requested || !authorityOwner
                || !RefractionAuthority.isOwner(this)) {
            return;
        }
        if (!isAttachedToWindow() || inputProducerSurface == null
                || rootWidth <= 1 || rootHeight <= 1) {
            scheduleBindRetry();
            return;
        }
        if (binding != null && binding.bound) return;
        try {
            binding = bindProducer(
                    this,
                    inputProducerSurface,
                    captureScalePercent / 100f,
                    preferRawRootTarget);
            producerActive = binding != null && binding.bound;
            if (producerActive) {
                producerFrameSeen = false;
                presentedFrameSeen = false;
                postOnAnimation(() -> verifyFirstProducerFrame(12));
                Log.i(TAG, "PassBlur producer transaction applied; waiting for first frame scale="
                        + captureScalePercent + "%");
            } else {
                scheduleBindRetry();
            }
        } catch (Throwable error) {
            if (!failureLogged) {
                failureLogged = true;
                Log.w(TAG, "PassBlur producer unavailable; retaining View PassBlur fallback", error);
            }
            scheduleBindRetry();
        }
    }

    private void scheduleBindRetry() {
        if (++bindAttempts > MAX_BIND_RETRIES) {
            producerActive = false;
            if (!failureLogged) {
                failureLogged = true;
                Log.w(TAG, "PassBlur producer rejected after " + bindAttempts
                        + " frames; retaining View PassBlur fallback");
            }
            return;
        }
        postOnAnimation(this::attemptBindWhenReady);
    }

    private static Binding bindProducer(
            View host,
            Surface producer,
            float scale,
            boolean rawRootTarget) throws Exception {
        AttachedSurfaceControl attachedRoot = host.getRootSurfaceControl();
        if (attachedRoot == null) return null;

        SurfaceControl target = null;
        boolean ownsTarget = false;
        SurfaceControl.Transaction parentTransaction = null;
        try {
            if (rawRootTarget) {
                Method getSurfaceControl = attachedRoot.getClass().getMethod("getSurfaceControl");
                Object rawTarget = getSurfaceControl.invoke(attachedRoot);
                if (!(rawTarget instanceof SurfaceControl)) {
                    throw new IllegalStateException("AttachedSurfaceControl raw root unavailable");
                }
                target = (SurfaceControl) rawTarget;
                if (!target.isValid()) {
                    throw new IllegalStateException("AttachedSurfaceControl raw root invalid");
                }
                Log.i(TAG, "using raw root SurfaceControl fallback target=" + surfaceName(target));
            } else {
                target = new SurfaceControl.Builder()
                        .setName("DuoLauncherMIUIGlass-PassBlurCapture")
                        .build();
                ownsTarget = true;

                parentTransaction = attachedRoot.buildReparentTransaction(target);
                if (parentTransaction == null) {
                    target.release();
                    return null;
                }
                parentTransaction.show(target);
                parentTransaction.apply();
                Log.i(TAG, "PassBlur capture node attached through public AttachedSurfaceControl");
            }

            Class<?> transactionClass = SurfaceControl.Transaction.class;
            Method setPassBlurSurface = transactionClass.getMethod(
                    "SetPassBlurSurface", SurfaceControl.class, Surface.class);
            Method setUpdateTextureFlag = transactionClass.getMethod(
                    "setUpdateTextureFlag", SurfaceControl.class, boolean.class, float.class);
            Method setMiBlurWinExc = transactionClass.getMethod(
                    "setMiBlurWinExc", SurfaceControl.class, String[].class);
            Method apply = transactionClass.getMethod("apply");
            Method close = null;
            try {
                close = transactionClass.getMethod("close");
            } catch (NoSuchMethodException ignored) {}

            String targetName = surfaceName(target);
            String[] exclusions = new String[] {
                    targetName,
                    "NavigationBar",
                    "StatusBar",
                    "GestureStub"
            };
            Object transaction = transactionClass.getConstructor().newInstance();
            try {
                setMiBlurWinExc.invoke(transaction, target, (Object) exclusions);
                setPassBlurSurface.invoke(transaction, target, producer);
                setUpdateTextureFlag.invoke(transaction, target, true, scale);
                apply.invoke(transaction);
            } finally {
                if (close != null) {
                    try { close.invoke(transaction); } catch (Throwable ignored) {}
                }
            }

            Log.i(TAG, "PassBlur producer bound target="
                    + (rawRootTarget ? "raw-root" : "public-child")
                    + " name=" + targetName
                    + " scale=" + scale);
            return new Binding(target, transactionClass,
                    setPassBlurSurface, setUpdateTextureFlag, setMiBlurWinExc,
                    apply, close, scale, ownsTarget);
        } catch (Throwable error) {
            if (ownsTarget && target != null && target.isValid()) {
                try (SurfaceControl.Transaction cleanup = new SurfaceControl.Transaction()) {
                    cleanup.reparent(target, null).apply();
                } catch (Throwable ignored) {}
                try { target.release(); } catch (Throwable ignored) {}
            }
            throw error;
        } finally {
            if (parentTransaction != null) {
                try { parentTransaction.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private void unbindProducer() {
        Binding current = binding;
        binding = null;
        if (current == null || !current.bound) return;
        try {
            if (current.captureNode.isValid()) {
                Object transaction = current.transactionClass.getConstructor().newInstance();
                try {
                    current.setPassBlurSurface.invoke(transaction, current.captureNode, null);
                    current.setUpdateTextureFlag.invoke(
                            transaction, current.captureNode, false, current.scale);
                    current.setMiBlurWinExc.invoke(
                            transaction, current.captureNode, (Object) new String[0]);
                    current.apply.invoke(transaction);
                } finally {
                    if (current.close != null) {
                        try { current.close.invoke(transaction); } catch (Throwable ignored) {}
                    }
                }

                if (current.ownsTarget) {
                    try (SurfaceControl.Transaction detach = new SurfaceControl.Transaction()) {
                        detach.reparent(current.captureNode, null).apply();
                    }
                    current.captureNode.release();
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "PassBlur producer unbind failed", error);
            if (current.ownsTarget) {
                try {
                    if (current.captureNode.isValid()) current.captureNode.release();
                } catch (Throwable ignored) {}
            }
        } finally {
            current.bound = false;
        }
    }

    private void requestDraw() {
        if (shuttingDown || !authorityOwner || !producerActive) return;
        if (!drawQueued.compareAndSet(false, true)) return;
        renderHandler.post(() -> {
            drawQueued.set(false);
            drawLatestFrame();
        });
    }

    private void drawLatestFrame() {
        if (shuttingDown || !producerActive || !authorityOwner
                || inputSurfaceTexture == null
                || eglWindowSurface == EGL14.EGL_NO_SURFACE) return;
        try {
            makeCurrent();
            inputSurfaceTexture.updateTexImage();
            inputSurfaceTexture.getTransformMatrix(texMatrix);

            GLES20.glViewport(0, 0, Math.max(1, outputWidth), Math.max(1, outputHeight));
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(program);
            int position = GLES20.glGetAttribLocation(program, "aPosition");
            int uv = GLES20.glGetAttribLocation(program, "aUv");
            quad.position(0);
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 4 * Float.BYTES, quad);
            GLES20.glEnableVertexAttribArray(position);
            quad.position(2);
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 4 * Float.BYTES, quad);
            GLES20.glEnableVertexAttribArray(uv);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"),
                    1, false, texMatrix, 0);
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uBackdropRect"),
                    backdropX, backdropY, backdropW, backdropH);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uConfigRot"), configRotation);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uViewSize"),
                    Math.max(1, outputWidth), Math.max(1, outputHeight));
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uRootSize"),
                    Math.max(1, rootWidth), Math.max(1, rootHeight));
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uCornerRadius"),
                    Math.min(cornerRadiusPx, Math.min(outputWidth, outputHeight) * 0.5f));
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uRefractionStrength"),
                    refractionStrengthPx);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uRefractionInset"),
                    refractionInsetPx);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uChromatic"), chromatic);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uDispersionR"), dispersionR);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uDispersionB"), dispersionB);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uBlurStepPx"),
                    Math.min(5f, blurRadiusPx * 0.035f));
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uTintColor"),
                    tintR, tintG, tintB, tintA);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(position);
            GLES20.glDisableVertexAttribArray(uv);
            EGL14.eglSwapBuffers(eglDisplay, eglWindowSurface);
            if (!presentedFrameSeen) {
                presentedFrameSeen = true;
                if (!activeLogged) {
                    activeLogged = true;
                    Log.i(TAG, "first refracted frame presented; zero-copy refraction active");
                }
            }
        } catch (Throwable error) {
            producerActive = false;
            fail("render", error);
            mainHandler.post(() -> {
                unbindProducer();
                recreateInputProducer("render-failure");
            });
        }
    }

    private void clearOutput() {
        if (eglWindowSurface == EGL14.EGL_NO_SURFACE) return;
        try {
            makeCurrent();
            GLES20.glViewport(0, 0, Math.max(1, outputWidth), Math.max(1, outputHeight));
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            EGL14.eglSwapBuffers(eglDisplay, eglWindowSurface);
        } catch (Throwable ignored) {}
    }

    private void updateBackdropMapping() {
        if (!isAttachedToWindow()) return;
        View root = getRootView();
        int rw = Math.max(1, root.getWidth());
        int rh = Math.max(1, root.getHeight());
        getLocationInWindow(windowLocation);
        int left = windowLocation[0];
        int top = windowLocation[1];
        int width = Math.max(1, getWidth());
        int height = Math.max(1, getHeight());

        rootWidth = rw;
        rootHeight = rh;
        backdropX = left / (float) rw;
        backdropY = 1f - (top + height) / (float) rh;
        backdropW = width / (float) rw;
        backdropH = height / (float) rh;
        configRotation = getDisplay() != null ? getDisplay().getRotation() : 0;
        renderHandler.post(this::resizeInputProducer);
    }

    private void makeCurrent() {
        checkEgl("eglMakeCurrent", EGL14.eglMakeCurrent(
                eglDisplay, eglWindowSurface, eglWindowSurface, eglContext));
    }

    private void destroyOutput(Surface window) {
        if (window != null) window.release();
        destroyEglWindowOnly();
    }

    private void destroyEglWindowOnly() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglWindowSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglWindowSurface);
            eglWindowSurface = EGL14.EGL_NO_SURFACE;
        }
    }

    private void shutdown() {
        if (shuttingDown) return;
        shuttingDown = true;
        authorityOwner = false;
        producerActive = false;
        unbindProducer();
        renderHandler.post(() -> {
            try {
                if (eglWindowSurface != EGL14.EGL_NO_SURFACE) makeCurrent();
            } catch (Throwable ignored) {}
            if (inputSurfaceTexture != null) {
                try { inputSurfaceTexture.setOnFrameAvailableListener(null); } catch (Throwable ignored) {}
                inputSurfaceTexture.release();
                inputSurfaceTexture = null;
            }
            if (inputProducerSurface != null) {
                inputProducerSurface.release();
                inputProducerSurface = null;
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program);
                program = 0;
            }
            if (oesTexture != 0) {
                GLES20.glDeleteTextures(1, new int[]{oesTexture}, 0);
                oesTexture = 0;
            }
            destroyEglWindowOnly();
            if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                EGL14.eglTerminate(eglDisplay);
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            eglContext = EGL14.EGL_NO_CONTEXT;
        });
        renderThread.quitSafely();
    }

    private static String surfaceName(SurfaceControl surface) {
        if (surface == null) return "";
        try {
            Method getName = SurfaceControl.class.getDeclaredMethod("getName");
            getName.setAccessible(true);
            Object value = getName.invoke(surface);
            if (value instanceof String) return (String) value;
        } catch (Throwable ignored) {}
        return surface.toString();
    }

    private static int createProgram(String vertexSource, String fragmentSource) {
        int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (vertex == 0 || fragment == 0) return 0;
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        if (status[0] == 0) {
            Log.e(TAG, "program link failed: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            Log.e(TAG, "shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private static void checkEgl(String operation, boolean success) {
        if (!success) {
            throw new IllegalStateException(operation + " failed egl=0x"
                    + Integer.toHexString(EGL14.eglGetError()));
        }
    }

    private static void fail(String stage, Throwable error) {
        Log.w(TAG, stage + " failed; fallback remains active", error);
    }
}
