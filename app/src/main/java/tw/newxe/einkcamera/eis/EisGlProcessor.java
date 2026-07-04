package tw.newxe.einkcamera.eis;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Handles OpenGL ES rendering pipeline for EIS.
 */
public class EisGlProcessor implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "EisGlProcessor";

    private final HandlerThread mHandlerThread;
    private final Handler mHandler;

    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mEGLContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig mEGLConfig = null;

    private int mProgram;
    private int mTextureId;
    private SurfaceTexture mInputSurfaceTexture;
    private Surface mInputSurface;

    private final List<OutputSurface> mOutputSurfaces = new ArrayList<>();
    private final float[] mSTMatrix = new float[16];
    private final float[] mEisMatrix = new float[16];

    private int maPositionLoc;
    private int maTextureCoordLoc;
    private int muSTMatrixLoc;
    private int muEisMatrixLoc;

    private final FloatBuffer mVerticesBuffer;
    private final FloatBuffer mTexCoordsBuffer;

    // Reusable scratch array for per-surface center-crop vertex scaling — avoids
    // allocating a new float[] every frame. See renderFrame for the crop math.
    private final float[] mScaledVertices = new float[12];

    private final EisManager mEisManager;
    // Camera-source (actual sensor) dimensions, NOT the video-quality target. The
    // camera may run at a non-16:9 resolution (e.g. 2592×1944 4:3) even when the
    // user selected a 16:9 tier (2560×1440 QHD), because getBestSupportedSize
    // picks the smallest supported size ≥ target — and many UVC sensors have no
    // exact 16:9 QHD mode. mWidth/mHeight feeds the per-surface center-crop
    // calculation in renderFrame: inputAspect (mWidth/mHeight) vs outputAspect
    // (each EGL surface's buffer size) determines the vertex scale. EisManager.
    // getStabilizationMatrix receives mWidth/mHeight too but ignores them — the
    // zoom/compensation it returns is gyro-driven, not size-driven.
    private final int mWidth;
    private final int mHeight;

    private static final String VERTEX_SHADER =
            "uniform mat4 uSTMatrix;\n" +
            "uniform mat4 uEisMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "  gl_Position = uEisMatrix * aPosition;\n" +
            "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";

    private static final float[] VERTICES = {
            -1.0f, -1.0f, 0.0f,
             1.0f, -1.0f, 0.0f,
            -1.0f,  1.0f, 0.0f,
             1.0f,  1.0f, 0.0f,
    };

    private static final float[] TEX_COORDS = {
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f,
    };

    public EisGlProcessor(EisManager eisManager, int width, int height) {
        mEisManager = eisManager;
        mWidth = width;
        mHeight = height;
        mHandlerThread = new HandlerThread("EisGlProcessor");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        mVerticesBuffer = ByteBuffer.allocateDirect(VERTICES.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mVerticesBuffer.put(VERTICES).position(0);

        mTexCoordsBuffer = ByteBuffer.allocateDirect(TEX_COORDS.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mTexCoordsBuffer.put(TEX_COORDS).position(0);

        // initGl must finish before the constructor returns: the caller
        // immediately calls getInputSurface() and hands the result to
        // UVCCamera.setPreviewDisplay(). If initGl is still pending the
        // caller would pass null and the camera would silently produce no
        // frames, which is exactly what made EIS look like it wasn't engaged.
        final CountDownLatch initLatch = new CountDownLatch(1);
        mHandler.post(() -> {
            try {
                initGl();
            } finally {
                initLatch.countDown();
            }
        });
        try {
            initLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void initGl() {
        mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1);

        int[] configAttribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(mEGLDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0);
        mEGLConfig = configs[0];

        int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        mEGLContext = EGL14.eglCreateContext(mEGLDisplay, mEGLConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);

        // Create a dummy surface to make context current
        int[] pbufferAttribs = { EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE };
        EGLSurface dummySurface = EGL14.eglCreatePbufferSurface(mEGLDisplay, mEGLConfig, pbufferAttribs, 0);
        EGL14.eglMakeCurrent(mEGLDisplay, dummySurface, dummySurface, mEGLContext);

        mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        maPositionLoc = GLES20.glGetAttribLocation(mProgram, "aPosition");
        maTextureCoordLoc = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
        muSTMatrixLoc = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
        muEisMatrixLoc = GLES20.glGetUniformLocation(mProgram, "uEisMatrix");

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        mTextureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureId);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        mInputSurfaceTexture = new SurfaceTexture(mTextureId);
        // Pin the listener to our GL handler thread so renderFrame runs on
        // the same thread that owns the EGL context — avoids needing an
        // extra post() and prevents "no EGL context" errors when the system
        // chooses an arbitrary thread for the callback.
        mInputSurfaceTexture.setOnFrameAvailableListener(this, mHandler);
        mInputSurface = new Surface(mInputSurfaceTexture);
    }

    public Surface getInputSurface() {
        return mInputSurface;
    }

    public void addOutputSurface(Surface surface) {
        addOutputSurface(surface, false);
    }

    /**
     * Adds an output surface for the GL pipeline to render stabilized frames into.
     *
     * @param surface            the Surface to render into (preview TextureView
     *                           Surface or MediaRecorder Surface)
     * @param cropToAspectRatio  when true, the quad is scaled beyond the viewport
     *                           edges so the input texture's aspect ratio is
     *                           preserved and the GPU's NDC clipping performs a
     *                           center-crop. This is needed for surfaces whose
     *                           buffer aspect differs from the camera's actual
     *                           sensor aspect — primarily the MediaRecorder
     *                           surface, which is set to the user's 16:9 quality
     *                           tier (e.g. 2560×1440) while the camera may be
     *                           running at a 4:3 sensor mode (e.g. 2592×1944).
     *                           Set false for the preview surface, whose
     *                           AspectRatioSurfaceView.updateTransform already
     *                           handles center-crop (so GL-level crop would
     *                           double-crop the preview).
     */
    public void addOutputSurface(Surface surface, boolean cropToAspectRatio) {
        mHandler.post(() -> {
            int[] surfaceAttribs = { EGL14.EGL_NONE };
            EGLSurface eglSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, mEGLConfig, surface, surfaceAttribs, 0);
            mOutputSurfaces.add(new OutputSurface(surface, eglSurface, cropToAspectRatio));
        });
    }

    public void removeOutputSurface(Surface surface) {
        mHandler.post(() -> {
            for (int i = 0; i < mOutputSurfaces.size(); i++) {
                if (mOutputSurfaces.get(i).surface == surface) {
                    EGL14.eglDestroySurface(mEGLDisplay, mOutputSurfaces.get(i).eglSurface);
                    mOutputSurfaces.remove(i);
                    break;
                }
            }
        });
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        // Listener is already pinned to mHandler via setOnFrameAvailableListener,
        // so we're on the GL thread here. Render synchronously instead of
        // re-posting — re-posting could let frames pile up if rendering takes
        // longer than the producer interval.
        renderFrame();
    }

    private void renderFrame() {
        if (mInputSurfaceTexture == null) return;
        if (mOutputSurfaces.isEmpty()) {
            // No consumer yet — still consume the frame so the BufferQueue
            // doesn't stall the producer (camera). updateTexImage is the
            // standard acquire-release call.
            try { mInputSurfaceTexture.updateTexImage(); } catch (Throwable ignored) {}
            return;
        }
        mInputSurfaceTexture.updateTexImage();
        mInputSurfaceTexture.getTransformMatrix(mSTMatrix);

        long timestamp = mInputSurfaceTexture.getTimestamp();
        float[] eisMatrix = mEisManager.getStabilizationMatrix(timestamp, mWidth, mHeight);
        System.arraycopy(eisMatrix, 0, mEisMatrix, 0, 16);

        int[] surfW = new int[1];
        int[] surfH = new int[1];
        float inputAspect = mHeight > 0 ? (float) mWidth / mHeight : 1.0f;

        for (OutputSurface os : mOutputSurfaces) {
            EGL14.eglMakeCurrent(mEGLDisplay, os.eglSurface, os.eglSurface, mEGLContext);
            // Preview Surface and MediaRecorder Surface have different buffer
            // sizes; using a single mWidth/mHeight viewport leaves one of them
            // letterboxed or under-rendered. Query each surface for its real
            // backing size each frame (cheap) and viewport to that.
            EGL14.eglQuerySurface(mEGLDisplay, os.eglSurface, EGL14.EGL_WIDTH, surfW, 0);
            EGL14.eglQuerySurface(mEGLDisplay, os.eglSurface, EGL14.EGL_HEIGHT, surfH, 0);
            GLES20.glViewport(0, 0, surfW[0], surfH[0]);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            // Center-crop: when the camera's sensor aspect (inputAspect) doesn't
            // match this surface's buffer aspect, the default full-viewport quad
            // (-1..1) would stretch the texture to fill — producing a distorted
            // frame. This happens at QHD on 4:3 sensors: the camera runs at
            // 2592×1944 (4:3) but the MediaRecorder surface is 2560×1440 (16:9).
            // To preserve the input aspect, scale the quad beyond the viewport
            // edges so the GPU's NDC clipping (-1..1 after projection) crops the
            // overflow. The dimension that's relatively larger in the input stays
            // at scale 1.0 (fills that axis); the other axis is scaled > 1.0
            // (overflows and gets clipped). This is equivalent to center-crop in
            // image processing — the input fills the output, excess is cropped.
            // The preview surface is excluded (cropToAspectRatio=false) because
            // AspectRatioSurfaceView.updateTransform already center-crops the
            // buffer; GL-level crop would compound the zoom (double-crop).
            if (os.cropToAspectRatio && surfH[0] > 0) {
                float outputAspect = (float) surfW[0] / surfH[0];
                if (Math.abs(inputAspect - outputAspect) > 0.01f) {
                    float scaleX = Math.max(1.0f, inputAspect / outputAspect);
                    float scaleY = Math.max(1.0f, outputAspect / inputAspect);
                    mScaledVertices[0]  = -scaleX; mScaledVertices[1]  = -scaleY; mScaledVertices[2]  = 0.0f;
                    mScaledVertices[3]  =  scaleX; mScaledVertices[4]  = -scaleY; mScaledVertices[5]  = 0.0f;
                    mScaledVertices[6]  = -scaleX; mScaledVertices[7]  =  scaleY; mScaledVertices[8]  = 0.0f;
                    mScaledVertices[9]  =  scaleX; mScaledVertices[10] =  scaleY; mScaledVertices[11] = 0.0f;
                    mVerticesBuffer.position(0);
                    mVerticesBuffer.put(mScaledVertices);
                } else {
                    mVerticesBuffer.position(0);
                    mVerticesBuffer.put(VERTICES);
                }
            } else {
                mVerticesBuffer.position(0);
                mVerticesBuffer.put(VERTICES);
            }
            mVerticesBuffer.position(0);

            GLES20.glUseProgram(mProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureId);

            GLES20.glVertexAttribPointer(maPositionLoc, 3, GLES20.GL_FLOAT, false, 12, mVerticesBuffer);
            GLES20.glEnableVertexAttribArray(maPositionLoc);

            mTexCoordsBuffer.position(0);
            GLES20.glVertexAttribPointer(maTextureCoordLoc, 2, GLES20.GL_FLOAT, false, 8, mTexCoordsBuffer);
            GLES20.glEnableVertexAttribArray(maTextureCoordLoc);

            GLES20.glUniformMatrix4fv(muSTMatrixLoc, 1, false, mSTMatrix, 0);
            GLES20.glUniformMatrix4fv(muEisMatrixLoc, 1, false, mEisMatrix, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            EGL14.eglSwapBuffers(mEGLDisplay, os.eglSurface);
        }
    }

    public void release() {
        mHandler.post(() -> {
            for (OutputSurface os : mOutputSurfaces) {
                EGL14.eglDestroySurface(mEGLDisplay, os.eglSurface);
            }
            mOutputSurfaces.clear();
            if (mInputSurfaceTexture != null) {
                mInputSurfaceTexture.release();
                mInputSurfaceTexture = null;
            }
            if (mInputSurface != null) {
                mInputSurface.release();
                mInputSurface = null;
            }
            EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(mEGLDisplay);
            mHandlerThread.quitSafely();
        });
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        return program;
    }

    private int loadShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        return shader;
    }

    private static class OutputSurface {
        Surface surface;
        EGLSurface eglSurface;
        boolean cropToAspectRatio;
        OutputSurface(Surface s, EGLSurface es, boolean crop) {
            surface = s;
            eglSurface = es;
            cropToAspectRatio = crop;
        }
    }
}
