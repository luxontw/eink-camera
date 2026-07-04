package tw.newxe.einkcamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * Rule-of-thirds alignment grid drawn straight onto the canvas.
 *
 * Replaces the old {@code @drawable/grid_overlay} vector. That drawable had a
 * fixed 300x300 viewport and was stretched to fill this view, which tracks the
 * (portrait, taller-than-wide) camera preview bounds. Under that non-uniform
 * scale a single strokeWidth="1" rendered at 1*(viewHeight/300)px on the
 * horizontal lines but only 1*(viewWidth/300)px on the verticals — so the
 * horizontals came out visibly fatter than the verticals. Drawing the lines
 * with a px stroke here keeps all four the same weight at any aspect ratio.
 *
 * The four lines are stroked as one Path in a single drawPath call rather than
 * four separate drawLine calls. With a translucent paint, separate draws blend
 * each line onto the canvas independently, so the four intersections — where a
 * vertical and a horizontal overlap — get the alpha applied twice and read as a
 * brighter/different shade. A single path is rasterised into one coverage mask
 * (overlaps unioned, not added), so every pixel of the grid is the same 50%.
 */
public class GridOverlayView extends View {

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();

    public GridOverlayView(Context context) {
        this(context, null);
    }

    public GridOverlayView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GridOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setColor(Color.WHITE);
        mPaint.setAlpha(128); // 50% — matches the previous overlay's strokeAlpha
        // 1dp, uniform across both axes (the whole point of this class).
        mPaint.setStrokeWidth(getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mPath.reset();
        if (w <= 0 || h <= 0) return;
        float x1 = w / 3f, x2 = w * 2f / 3f;
        float y1 = h / 3f, y2 = h * 2f / 3f;
        mPath.moveTo(x1, 0f); mPath.lineTo(x1, h);
        mPath.moveTo(x2, 0f); mPath.lineTo(x2, h);
        mPath.moveTo(0f, y1); mPath.lineTo(w, y1);
        mPath.moveTo(0f, y2); mPath.lineTo(w, y2);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mPath.isEmpty()) canvas.drawPath(mPath, mPaint);
    }
}
