package tw.newxe.einkcamera;

import android.content.Context;
import android.graphics.Matrix;
import android.util.AttributeSet;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;

/**
 * A {@link TextureView} that displays a landscape camera source upright in the
 * current device orientation. It rotates the camera buffer (90/180/270/0° — see
 * {@link #getUprightRotationDegrees()}) and centre-crops it to fill the view,
 * fitting a portrait box in portrait and a landscape box in landscape.
 */
public class AspectRatioSurfaceView extends TextureView {

    private int mSourceWidth = 0;
    private int mSourceHeight = 0;
    private int mTargetWidth = 0;
    private int mTargetHeight = 0;

    public AspectRatioSurfaceView(Context context) {
        super(context);
    }

    public AspectRatioSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AspectRatioSurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /**
     * Sets the source dimensions (typically from the camera's resolution).
     */
    public void setSourceSize(int width, int height) {
        if (mSourceWidth == width && mSourceHeight == height) {
            return;
        }
        mSourceWidth = width;
        mSourceHeight = height;
        requestLayout();
    }

    public int getSourceWidth() {
        return mSourceWidth;
    }

    public int getSourceHeight() {
        return mSourceHeight;
    }

    public int getTargetWidth() {
        return mTargetWidth;
    }

    public int getTargetHeight() {
        return mTargetHeight;
    }

    /**
     * Sets the target aspect ratio dimensions (e.g. 1280, 720).
     */
    public void setAspectRatio(int width, int height) {
        if (mTargetWidth == width && mTargetHeight == height) {
            return;
        }
        mTargetWidth = width;
        mTargetHeight = height;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int targetW = mTargetWidth > 0 ? mTargetWidth : mSourceWidth;
        int targetH = mTargetHeight > 0 ? mTargetHeight : mSourceHeight;

        if (targetW <= 0 || targetH <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        int parentWidth = MeasureSpec.getSize(widthMeasureSpec);
        int parentHeight = MeasureSpec.getSize(heightMeasureSpec);

        if (parentWidth <= 0 || parentHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        int measuredWidth;
        int measuredHeight;

        // Displayed aspect (measuredWidth : measuredHeight). targetW/targetH are
        // the landscape source/aspect dims. Portrait shows them rotated 90°
        // (targetH : targetW, a tall box); landscape shows them natively
        // (targetW : targetH, a wide box).
        float ratio = isLandscapeOrientation()
                ? (float) targetW / targetH
                : (float) targetH / targetW;

        if (parentWidth < parentHeight * ratio) {
            measuredWidth = parentWidth;
            measuredHeight = (int) (parentWidth / ratio);
        } else {
            measuredHeight = parentHeight;
            measuredWidth = (int) (parentHeight * ratio);
        }

        setMeasuredDimension(measuredWidth, measuredHeight);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed || mSourceWidth > 0) {
            updateTransform(right - left, bottom - top);
        }
    }

    private void updateTransform(int viewWidth, int viewHeight) {
        if (mSourceWidth == 0 || mSourceHeight == 0) return;

        Matrix matrix = new Matrix();
        float centerX = viewWidth / 2f;
        float centerY = viewHeight / 2f;

        // Undo TextureView's default stretch of the buffer to the view box, so the
        // rotation/crop below operate on the buffer at its native pixel size.
        matrix.postScale(1.0f / ((float) viewWidth / mSourceWidth), 1.0f / ((float) viewHeight / mSourceHeight), centerX, centerY);

        // Rotate the landscape camera frame upright for the current device
        // orientation (see getUprightRotationDegrees). The photo/video paths reuse
        // the same value so saved media matches what the preview showed.
        int rotation = getUprightRotationDegrees();
        matrix.postRotate(rotation, centerX, centerY);

        // Center-crop scale to fill the view. A 90°/270° rotation swaps the
        // buffer's axes (its width is now vertical), so width must cover viewHeight
        // and height cover viewWidth; a 0°/180° rotation keeps the axes.
        float scaleX, scaleY;
        if (rotation == 90 || rotation == 270) {
            scaleX = (float) viewWidth / mSourceHeight;
            scaleY = (float) viewHeight / mSourceWidth;
        } else {
            scaleX = (float) viewWidth / mSourceWidth;
            scaleY = (float) viewHeight / mSourceHeight;
        }

        // Pick the larger scale to fill the view (center-crop)
        float finalScale = Math.max(scaleX, scaleY);

        matrix.postScale(finalScale, finalScale, centerX, centerY);

        setTransform(matrix);
    }

    /**
     * Degrees the landscape camera frame must rotate to appear upright in the
     * current device orientation: 90° (portrait / ROTATION_0), 0° (landscape /
     * ROTATION_90), 270° (reverse-portrait / ROTATION_180) or 180° (reverse-
     * landscape / ROTATION_270) — i.e. (90 - rotation) mod 360.
     *
     * <p>screenOrientation lets the OS rotate the whole window to follow the
     * device. That rotation reaches this TextureView's on-screen output but NOT
     * the offline frame paths (QR scan via getBitmap, the native frame grab in
     * capturePhoto, the recorder surface), so the preview matrix bakes the upright
     * rotation in here and the photo/video paths call this same method — keeping
     * saved media oriented exactly like the preview rather than coming out
     * rotated. (Phone assumption: the device's natural orientation is portrait, so
     * ROTATION_0 is upright portrait.)
     */
    public int getUprightRotationDegrees() {
        switch (getDisplayRotation()) {
            case Surface.ROTATION_90:  return 0;
            case Surface.ROTATION_180: return 270;
            case Surface.ROTATION_270: return 180;
            case Surface.ROTATION_0:
            default:                   return 90;
        }
    }

    /** True when the device is held in either landscape orientation. */
    private boolean isLandscapeOrientation() {
        int r = getDisplayRotation();
        return r == Surface.ROTATION_90 || r == Surface.ROTATION_270;
    }

    private int getDisplayRotation() {
        Display display = getDisplay();
        if (display != null) {
            return display.getRotation();
        }
        // getDisplay() is null before the view is attached; fall back to the
        // default display so the very first transform still picks up the rotation.
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        if (wm != null && wm.getDefaultDisplay() != null) {
            return wm.getDefaultDisplay().getRotation();
        }
        return Surface.ROTATION_0;
    }

    /**
     * Re-applies the preview transform for the current display rotation. The
     * portrait/reverse-portrait flip is a 180° rotation that does not change the
     * view's size, so onLayout (and thus updateTransform) may never run on its
     * own — MainActivity calls this from a display-rotation listener so the live
     * preview is re-oriented to match the flipped UI.
     */
    public void refreshTransform() {
        int w = getWidth();
        int h = getHeight();
        if (w > 0 && h > 0) {
            updateTransform(w, h);
        }
    }
}
