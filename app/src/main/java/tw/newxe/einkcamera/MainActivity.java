package tw.newxe.einkcamera;

import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.InsetDrawable;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.Layout;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;

import tw.newxe.einkcamera.eis.EisGlProcessor;
import tw.newxe.einkcamera.eis.EisManager;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final int FOCUS_AUTO_HIDE_MS = 4000;
    private static final int FOCUS_DEFAULT_SIZE_DP = 140;
    private static final int FOCUS_SELECTED_SIZE_DP = 84;
    private static final int FOCUS_GAP_DP = 14;
    private static final int TRACK_HEIGHT_DP = 140;
    private static final int SUN_SIZE_DP = 28;
    private static final int LONG_PRESS_TIMEOUT = 500;
    // Grace period before the "no camera" reminder pops, so a camera already
    // plugged in at launch/resume has time to finish onConnect (USBMonitor polls
    // roughly every 2s) instead of the reminder flashing for a moment.
    private static final int NO_CAMERA_REMINDER_DELAY_MS = 2000;

    private USBMonitor mUsbMonitor;
    private UVCCamera mCamera;
    private UVCCamera mPendingCamera;

    // screenOrientation="fullSensor" lets the OS rotate the whole window (UI +
    // system bars) to follow the device. A 180° flip (portrait<->reverse-portrait
    // or landscape<->reverse-landscape) does NOT change the Configuration and does
    // not resize the preview, so neither onConfigurationChanged nor onLayout fires
    // — this listener is the signal we use to re-apply AspectRatioSurfaceView's
    // transform so the live feed re-orients with the flipped UI.
    private DisplayManager mDisplayManager;
    private final DisplayManager.DisplayListener mDisplayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (mSurfaceView != null) {
                mSurfaceView.refreshTransform();
            }
        }
    };

    // Landscape relayout. The OS rotates the window to landscape (so icons, the
    // preview and the system bars are all upright), but the toolbars are anchored
    // to the layout's top/bottom and would stretch across the now-wide screen.
    // applyOrientationLayout re-pins them as left/right vertical strips of the
    // same 72dp thickness instead. We keep configChanges=orientation so the
    // Activity is NOT recreated (the camera stays connected), which means a
    // layout-land resource would never be selected — hence the programmatic
    // ConstraintSet rebuild. The portrait constraints are captured verbatim from
    // the inflated XML so returning to portrait restores it exactly.
    private ConstraintLayout mRootLayout;
    private ConstraintLayout mTopUiOverlay;
    private ConstraintLayout mBottomBar;
    private LinearLayout mLeftFunctions;
    private ConstraintSet mPortraitRootCs;
    private ConstraintSet mPortraitTopCs;
    private ConstraintSet mPortraitBottomCs;
    // True while the side-strip (landscape) layout is active, where the timer and
    // toast are stood up with a 270° rotation. Used to pixel-snap that rotation
    // (snapRotatedTextView) so the toast's 1dp hairline border stays crisp.
    private boolean mIsLandscape;
    // Mirror of PREF_TOOLBAR_REVERSED — loaded in onCreate, toggled by the
    // horizontal-fling listener on mBottomBar. applyToolbarReversal reads it
    // to decide whether to swap the function-button group and the "more"
    // button to the opposite edges of the portrait bottom bar.
    private boolean mIsToolbarReversed;

    private AspectRatioSurfaceView mSurfaceView;
    private View mFocusContainer;
    private View mFocusIndicator;
    private View mExposureTrack;
    private ImageView mExposureSun;
    private View mFocusUiRoot;
    private ImageView mBtnQrToggle;
    private ImageView mBtnAspectRatio;
    private ImageView mBtnCountdown;
    private ImageView mBtnVideoQuality;
    private ImageView mBtnStabilization;
    private View mContainerQr;
    private View mContainerAspectRatio;
    private View mContainerCountdown;
    private View mContainerVideoQuality;
    private View mContainerStabilization;
    private View mContainerMore;
    private View mContainerClose;
    private ImageView mBtnMore;
    private ImageView mLastPicture;
    private ImageView mSharedImageView;
    private FrameLayout mQrOverlayContainer;

    // SCAN-from-shared-image state. When set, performQrScan reads from this
    // bitmap (one-shot, no recycle) and updateViewPosition lays overlays out
    // over mSharedImageView instead of mSurfaceView. Set by handleIncomingIntent
    // via ACTION_SEND / ACTION_VIEW (or by user picking an image from inside
    // SCAN mode), cleared by exitImageScanMode().
    private boolean mIsImageScanMode = false;
    private Bitmap mScanImageBitmap;
    // ML Kit's bounding boxes are in scanImageBitmap pixel space; the view
    // shows it via fitCenter, so we cache the scale + letterbox offset once
    // per load and reuse them in updateViewPosition.
    private float mScanImageScale = 1f;
    private int mScanImageOffsetX = 0;
    private int mScanImageOffsetY = 0;
    private boolean mImageScanFoundAny = false;
    private int mImageScanAttempts = 0;

    // True while the focus frame is meant to stay pinned at mFocusUiRoot's
    // centre (SCAN mode's default indicator + the image-scan equivalent).
    // mFocusUiRoot's bounds can change after the initial placement — a USB
    // hot-plug fires onConnect → setSourceSize, replaceSurfaceView swaps in
    // a 0×0 view at onStart, etc — and the focus container's absolute
    // setX/setY don't reflow with the parent. mFocusUiRoot's layout listener
    // re-centres the frame whenever this flag is set.
    private boolean mFocusFollowsCentre = false;

    private Uri mLastPhotoUri;
    // MIME type for the "open in gallery" intent — varies because the last
    // captured item can be either a photo or a video.
    private String mLastPhotoMimeType = "image/*";
    private TextView mTvRecordingTimer;
    private TextView mTvCustomToast;

    private int mCountdownSeconds = 0; // 0 (off), 3, 10
    private boolean mIsCountdownRunning = false;

    private LinearLayout mModeMenuSidebar;
    private View mBtnModePhoto;
    private View mBtnModeVideo;
    private View mBtnModeScan;

    // "More features" sidebar (About + Auto Run + Grid). Same look as
    // mModeMenuSidebar but anchored bottom-right under btn_more and without
    // drag-reordering.
    private LinearLayout mMoreMenuSidebar;
    private View mBtnMoreAbout;
    private View mBtnMoreAutoRun;
    private View mBtnMoreGrid;
    // Rule-of-thirds alignment overlay on top of the preview. The View + drawable
    // already exist in the layout; this is the only code path that toggles it.
    private View mGridOverlay;

    private enum CameraMode { PHOTO, VIDEO, SCAN }
    private CameraMode mCurrentMode = CameraMode.PHOTO;

    // User-customisable order of the long-press mode selector. Persisted in
    // SharedPreferences as a comma-separated list of CameraMode.name() — small
    // enough that JSON / Set<String> overhead isn't worth it. Mirrors the
    // physical order of the three FrameLayouts inside mModeMenuSidebar.
    // Package-visible so AutoRunActivity can read/write the same store.
    static final String PREFS_NAME = "usb_cam_viewer_prefs";
    private static final String PREF_MODE_ORDER = "mode_menu_order";
    private static final String PREF_GRID_ENABLED = "grid_enabled";
    // When true the portrait bottom toolbar is mirrored: the shutter/action
    // button and the function-button group sit at the right edge, the "more"
    // button at the left — a left-handed-friendly arrangement toggled by a
    // horizontal swipe-fling on the bottom bar. Stored persistently so the
    // preference survives app restarts.
    private static final String PREF_TOOLBAR_REVERSED = "toolbar_reversed";
    // Auto Run settings (configured in AutoRunActivity). Keyword/whitelist values
    // are stored as the raw multi-line text the user typed; both MainActivity and
    // AutoRunActivity split them the same way (one entry per non-blank line).
    static final String PREF_AUTO_SAVE_ENABLED = "auto_save_enabled";
    static final String PREF_AUTO_SAVE_KEYWORDS = "auto_save_keywords";
    static final String PREF_AUTO_OPEN_ENABLED = "auto_open_enabled";
    static final String PREF_AUTO_OPEN_WHITELIST = "auto_open_whitelist";
    private final List<CameraMode> mModeOrder = new ArrayList<>(Arrays.asList(
            CameraMode.PHOTO, CameraMode.VIDEO, CameraMode.SCAN));

    // True while a mode-menu item is being long-press-dragged for reorder.
    // Set in the long-press runnable, cleared on ACTION_UP/CANCEL. While set:
    // the dragged FrameLayout keeps its child index but follows the finger via
    // pure translationY (no removeView/addView, so the gesture is never
    // cancelled and the move is butter-smooth across multiple slots), the
    // other items slide aside to open a gap at the drop target, and the click
    // that would normally fire setCameraMode is suppressed via the per-item
    // touch listener's wasReordering flag.
    //
    // Why translation-only instead of reordering children live: removeView()
    // on the dragged view calls cancelTouchTarget(), which synchronously
    // delivers ACTION_CANCEL and ends the drag — that's why the old live-swap
    // could only ever step one slot before dying. And keeping the dragged
    // view's layout slot fixed means getTop() never goes stale mid-gesture, so
    // there's no one-frame jump as it moves. The real child-order change is
    // baked in once, at drop (bakeModeReorder).
    private boolean mIsReorderingModes = false;
    private View mReorderDraggingView;
    private float mReorderDragStartRawY = 0f;
    // Captured when the drag begins (child layout is static for the whole
    // gesture, so these stay valid): the dragged view's start index, the
    // per-slot pitch (item height + divider), the item height, and the live
    // drop-target index that the siblings are currently making room for.
    private int mReorderFromIndex = 0;
    private int mReorderToIndex = 0;
    private int mReorderSlotStep = 0;
    private int mReorderItemHeight = 0;

    private enum VideoQuality { HD, FHD, QHD }
    private VideoQuality mCurrentVideoQuality = VideoQuality.HD;

    // PHOTO-mode crop selector. NATIVE leaves the view at the sensor's native
    // aspect (e.g. 4:3 for 2592×1944). ONE_ONE/SIXTEEN_NINE keep the camera at
    // native max and only narrow the view's aspect target, so the matrix
    // transform centre-crops the largest 1:1 / 16:9 region the sensor can give.
    private enum PhotoAspectRatio { NATIVE, ONE_ONE, SIXTEEN_NINE }
    private PhotoAspectRatio mPhotoAspectRatio = PhotoAspectRatio.NATIVE;
    private boolean mForceShowAspectRatioOnce = false;
    // The EIS toggle forces a preview rebuild via updateCameraResolution(true).
    // That rebuild's tryStartPendingPreview tail normally fires the video-quality
    // toast (showPreviewSizeToast). The toggle shows its own "Stabilization:
    // ON/OFF" toast, so this one-shot suppresses the quality toast for that single
    // rebuild — otherwise the two stack on top of each other. Consumed in
    // showPreviewSizeToast.
    private boolean mSuppressPreviewSizeToastOnce = false;

    private BarcodeScanner mBarcodeScanner;
    private boolean mIsScanning = false;
    private boolean mQrScanEnabled = false;
    private final Map<String, QrOverlayData> mActiveQrOverlays = new HashMap<>();
    // Auto Run: rawValue -> last time we auto-acted on it. A code that lingers in
    // frame (or leaves and re-enters within the window) must not re-save / re-open
    // repeatedly, so each value is suppressed for AUTO_RUN_COOLDOWN_MS after firing.
    // Cleared when scanning stops (stopQrScanning / exitImageScanMode) so a fresh
    // scan session starts clean.
    private final Map<String, Long> mAutoRunLastFired = new HashMap<>();
    private static final long AUTO_RUN_COOLDOWN_MS = 10_000L;
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private final Runnable mHideFocusRunnable = this::hideFocusUi;
    private final Runnable mQrScanRunnable = this::performQrScan;
    // Delayed presence check (re-armed each onStart). Fires the reminder only
    // when nothing is connected AND nothing is physically plugged in — a device
    // that is attached but still mid-handshake (e.g. the OS permission prompt is
    // up) is left alone so onConnect can arrive without the reminder flashing.
    private final Runnable mNoCameraCheckRunnable = () -> {
        if (isCameraConnected()) return;
        if (hasAttachedUvcCamera()) return;
        showNoCameraReminder();
    };
    private final Runnable mHideCustomToastRunnable = () -> {
        mTvCustomToast.setVisibility(View.GONE);
        if (mCurrentMode == CameraMode.PHOTO) {
            updateCountdownDisplay();
        }
    };

    private int mBrightness = 50;
    private float mDragStartY = 0f;
    private int mDragStartBrightness = 50;
    private float mTrackTopY = 0f;
    private int mTrackHeightPx = 0;
    private boolean mIsLongPressing = false;
    private boolean mFocusTriggered = false;

    private boolean mIsRecording = false;
    private MediaRecorder mMediaRecorder;
    private long mRecordingStartTime = 0L;
    private volatile boolean mIsChangingResolution = false;
    // Work that has to wait for an in-flight resolution change to finish AND for
    // the post-replacement layout to settle. Currently used so SCAN mode's
    // centred focus indicator gets correct mFocusUiRoot dimensions when SCAN
    // is entered straight from VIDEO mode.
    private Runnable mPendingPostResolutionChange;
    // Set in onStop, consumed in onStart. When the activity returns from background
    // we replace the TextureView with a fresh instance before letting the USB monitor
    // re-trigger onConnect — same rationale as updateCameraResolution, see onStart.
    private boolean mNeedsFreshSurfaceView = false;
    // Modal "no camera" reminder. Shown when no UVC camera is connected, telling
    // the user to plug / re-plug the device. It is non-cancelable, so the toolbar
    // behind it stays locked until the user taps the confirm button (the
    // requirement) — or until a camera connects, which auto-dismisses it.
    private AlertDialog mNoCameraDialog;
    // True between onStart and onStop. showNoCameraReminder checks this so the
    // onDisconnect that fires synchronously from onStop's mCamera.close() can't
    // pop a dialog onto an already-stopped Activity (window leak).
    private boolean mIsStarted = false;
    // Highest size from getSupportedSizeList() captured at onConnect time. PHOTO and
    // SCAN modes want the camera at its native max for maximum detail (still capture
    // and barcode decoding); VIDEO mode drives down to mCurrentVideoQuality. We
    // remember the max so leaving VIDEO mode can restore it without re-querying.
    private int mNativeMaxPreviewWidth = 0;
    private int mNativeMaxPreviewHeight = 0;

    // Guards capturePhoto so a second tap during an in-flight capture doesn't
    // register a second IFrameCallback. Also read from the callback (USB thread)
    // to discard any extra frames that arrive before setFrameCallback(null) takes
    // effect, so we only process and save one frame per shutter press.
    private volatile boolean mPendingPhotoFrame = false;

    // The "Last Picture" thumbnail opens mLastPhotoUri, but a freshly-shot photo
    // only lands in mLastPhotoUri at the very end of its async save chain (grab
    // frame on the USB thread → rotate/crop on a worker → MediaStore insert +
    // JPEG compress + write on another worker → post the new Uri to the UI). For
    // a full-resolution photo that chain is a few hundred ms; tapping the
    // thumbnail inside that window would open the *previous* capture, and a
    // second tap (after the save lands) would then open the right one. To close
    // that gap, mPendingPhotoSaves counts the capture→save chains still running;
    // a tap while it's > 0 sets mOpenLastPhotoWhenSaved so the open is deferred
    // until savePhotoAsync posts the new Uri. Both are touched only on the UI
    // thread (capturePhoto, savePhotoAsync's post, onStop, onDisconnect's
    // runOnUiThread), so no synchronisation is needed.
    private int mPendingPhotoSaves = 0;
    private boolean mOpenLastPhotoWhenSaved = false;

    private boolean mIsStabilizationEnabled = false;
    private EisManager mEisManager;
    private EisGlProcessor mEisGlProcessor;
    private Surface mEisInputSurface;
    private Surface mMediaRecorderSurface;

    private final Runnable mUpdateTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (mIsRecording) {
                long millis = SystemClock.elapsedRealtime() - mRecordingStartTime;
                int seconds = (int) (millis / 1000);
                int minutes = seconds / 60;
                int hours = minutes / 60;
                seconds = seconds % 60;
                minutes = minutes % 60;
                mTvRecordingTimer.setText(String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds));
                fitRotatedTextWidth(mTvRecordingTimer);
                mUiHandler.postDelayed(this, 1000);
            }
        }
    };

    private final Runnable mLongPressRunnable = () -> {
        mIsLongPressing = true;
        mExposureTrack.setAlpha(1f);
        mExposureTrack.setVisibility(VISIBLE);
        mExposureSun.setAlpha(1f);
        mExposureSun.setVisibility(VISIBLE);
        // Initially place exposure sun at current brightness level
        float sunCenterY = sunCenterFromBrightness(mBrightness);
        mExposureSun.setY(sunCenterY - mExposureSun.getHeight() / 2f);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PERMISSION_DENIED) {
            requestCameraPermission();
        }

        EdgeToEdge.enable(this);
        var flags = WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;
        getWindow().setFlags(flags, flags);

        setContentView(R.layout.activity_main);
        mRootLayout = findViewById(R.id.main);
        mSurfaceView = findViewById(R.id.camera_surface_view);
        mQrOverlayContainer = findViewById(R.id.qr_overlay_container);
        mFocusContainer = findViewById(R.id.focus_container);
        mFocusIndicator = findViewById(R.id.focus_indicator);
        mExposureTrack = findViewById(R.id.exposure_track);
        mExposureSun = findViewById(R.id.exposure_sun);
        mFocusUiRoot = findViewById(R.id.focus_ui_root);
        mModeMenuSidebar = findViewById(R.id.mode_menu_sidebar);
        mBtnModePhoto = findViewById(R.id.btn_mode_photo);
        mBtnModeVideo = findViewById(R.id.btn_mode_video);
        mBtnModeScan = findViewById(R.id.btn_mode_scan);

        loadModeOrder();
        applyModeOrder();
        installModeReorderTouchListener(mBtnModePhoto, CameraMode.PHOTO);
        installModeReorderTouchListener(mBtnModeVideo, CameraMode.VIDEO);
        installModeReorderTouchListener(mBtnModeScan, CameraMode.SCAN);

        mBtnQrToggle = findViewById(R.id.btn_qr_toggle);
        mBtnQrToggle.setOnClickListener(v -> {
            if (mModeMenuSidebar.getVisibility() == View.VISIBLE) {
                mModeMenuSidebar.setVisibility(View.GONE);
                return;
            }
            // No-camera lock policy: with no UVC camera attached the action
            // taps (shutter / record-start) are dead, but long-presses stay
            // live — the mode menu below must remain reachable. Stop-recording
            // is exempt too: a hot-unplug mid-recording leaves mIsRecording
            // set (onDisconnect doesn't stop the recorder) and this tap is the
            // only way to end and save the take.
            if (isNoCameraLocked() && !mIsRecording) return;
            if (mCurrentMode == CameraMode.PHOTO) {
                if (mIsCountdownRunning) return;
                flashSelection(mContainerQr, mBtnQrToggle);
                if (mCountdownSeconds > 0) {
                    v.post(this::startPhotoCountdown);
                } else {
                    v.post(this::capturePhoto);
                }
            } else if (mCurrentMode == CameraMode.VIDEO) {
                flashSelection(mContainerQr, mBtnQrToggle);
                if (mIsRecording) {
                    v.post(this::stopRecording);
                } else {
                    v.post(this::startRecording);
                }
            } else if (mCurrentMode == CameraMode.SCAN) {
                // Scanning is always active in SCAN mode, button can be used for other actions if needed
            }
        });

        mBtnQrToggle.setOnLongClickListener(v -> {
            if (mIsRecording) return true;
            // Mutually exclusive with the more-features menu.
            if (mMoreMenuSidebar != null) mMoreMenuSidebar.setVisibility(View.GONE);
            mModeMenuSidebar.setVisibility(mModeMenuSidebar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            return true;
        });

        mBtnAspectRatio = findViewById(R.id.btn_aspect_ratio);
        mBtnAspectRatio.setOnClickListener(v -> {
            // Guarded against re-entry while a previous swap is still
            // running — every cycle goes through updateCameraResolution, which
            // disables UI on mIsChangingResolution.
            if (mCurrentMode != CameraMode.PHOTO) return;
            if (mIsChangingResolution || mCamera == null) return;
            mForceShowAspectRatioOnce = true;
            cyclePhotoAspectRatio();
            flashSelection(mContainerAspectRatio, mBtnAspectRatio);
        });

        mBtnCountdown = findViewById(R.id.btn_countdown);
        mBtnCountdown.setOnClickListener(v -> {
            if (mIsCountdownRunning || isNoCameraLocked()) return;
            cycleCountdown();
            flashSelection(mContainerCountdown, mBtnCountdown);
        });

        mBtnVideoQuality = findViewById(R.id.btn_video_quality);
        updateVideoQualityIcon();
        mBtnVideoQuality.setOnClickListener(v -> {
            if (mIsRecording || mIsChangingResolution || isNoCameraLocked()) return;
            cycleVideoQuality();
            flashSelection(mContainerVideoQuality, mBtnVideoQuality);
        });

        mBtnStabilization = findViewById(R.id.btn_stabilization);
        updateStabilizationIcon();
        mBtnStabilization.setOnClickListener(v -> {
            if (mIsChangingResolution || mIsRecording || mCurrentMode != CameraMode.VIDEO
                    || isNoCameraLocked()) {
                return;
            }
            mIsStabilizationEnabled = !mIsStabilizationEnabled;
            updateStabilizationIcon();
            showCustomToast(getString(R.string.stabilization_status,
                    getString(mIsStabilizationEnabled ? R.string.stabilization_on : R.string.stabilization_off)));

            // The forced rebuild below ends in tryStartPendingPreview, whose tail
            // fires the video-quality (preview-size) toast. Suppress that one so
            // it doesn't stack on top of the stabilization toast just shown.
            mSuppressPreviewSizeToastOnce = true;

            // Force a Surface-attach rebuild even though the camera resolution
            // didn't change — tryStartPendingPreview is the only place that
            // creates / destroys the EisGlProcessor and rebinds setPreviewDisplay
            // to either the EIS input Surface or the raw TextureView Surface.
            // Without forceRebuild the size-equality short-circuit in
            // updateCameraResolution returns immediately and the toggle would
            // never actually enable / disable EIS.
            updateCameraResolution(true);
            flashSelection(mContainerStabilization, mBtnStabilization);
        });


        mContainerQr = findViewById(R.id.container_qr);
        mContainerAspectRatio = findViewById(R.id.container_aspect_ratio);
        mContainerCountdown = findViewById(R.id.container_countdown);
        mContainerVideoQuality = findViewById(R.id.container_video_quality);
        mContainerStabilization = findViewById(R.id.container_stabilization);

        mContainerMore = findViewById(R.id.container_more);
        mContainerClose = findViewById(R.id.container_close);

        mTopUiOverlay = findViewById(R.id.top_ui_overlay);
        mBottomBar = findViewById(R.id.bottom_bar);
        mLeftFunctions = findViewById(R.id.left_functions);
        // Load the toolbar-reversed preference before applyOrientationLayout
        // so the first layout pass (below) already reflects the saved state.
        mIsToolbarReversed = getPrefs().getBoolean(PREF_TOOLBAR_REVERSED, false);
        // Capture the inflated (portrait) constraints verbatim so returning to
        // portrait restores the XML layout exactly; only landscape is rebuilt.
        mPortraitRootCs = new ConstraintSet();
        mPortraitRootCs.clone(mRootLayout);
        mPortraitTopCs = new ConstraintSet();
        mPortraitTopCs.clone(mTopUiOverlay);
        mPortraitBottomCs = new ConstraintSet();
        mPortraitBottomCs.clone(mBottomBar);

        mBtnMore = findViewById(R.id.btn_more);
        mMoreMenuSidebar = findViewById(R.id.more_menu_sidebar);
        mBtnMoreAbout = findViewById(R.id.btn_more_about);
        mBtnMoreAutoRun = findViewById(R.id.btn_more_auto_run);
        mBtnMoreGrid = findViewById(R.id.btn_more_grid);
        mGridOverlay = findViewById(R.id.grid_overlay);

        // Restore the persisted grid-overlay state (defaults to off, and stays
        // hidden in SCAN — see applyGridOverlayVisibility). setCameraMode below
        // re-applies for the start mode; this keeps the very first frame correct.
        applyGridOverlayVisibility();

        // Both tap and long-press toggle the more-features sidebar — the button
        // has no other action, and the requirement is "long-press shows a menu".
        mBtnMore.setOnClickListener(v -> toggleMoreMenu());
        mBtnMore.setOnLongClickListener(v -> {
            toggleMoreMenu();
            return true;
        });

        installMoreMenuItemListener(mBtnMoreAbout,
                () -> startActivity(new Intent(this, AboutActivity.class)), false);
        installMoreMenuItemListener(mBtnMoreAutoRun,
                () -> startActivity(new Intent(this, AutoRunActivity.class)), false);
        installMoreMenuItemListener(mBtnMoreGrid, this::toggleGridOverlay, true);

        ImageView btnClose = findViewById(R.id.btn_close);
        // Listen on the whole container, not the inner icon. container_close is
        // 72dp but btn_close is only a 48dp icon centred inside it, so the old
        // listener left a ~12dp dead border on every side. flashSelection lights
        // up the entire container as feedback, so that border *looks* tappable —
        // a tap landing there highlighted nothing and did nothing, which read as
        // the close button "needing several presses". Driving the click from the
        // container makes the hit area match the visible/feedback area. (The
        // container once also had a ripple foreground; it was removed because
        // ripple + flashSelection together read as a stray gray shadow
        // animation on top of the black flash.)
        mContainerClose.setOnClickListener(v -> {
            flashSelection(mContainerClose, btnClose);
            v.postDelayed(this::finish, 200);
        });

        mLastPicture = findViewById(R.id.last_picture);
        mSharedImageView = findViewById(R.id.shared_image_view);
        mTvRecordingTimer = findViewById(R.id.tv_recording_timer);
        mTvCustomToast = findViewById(R.id.tv_custom_toast);
        // The toast is wrap_content, so it re-measures to a different pixel size on
        // every message; the timer changes width as it ticks. Re-snap the 270°
        // landscape rotation whenever either is re-laid-out so a short message
        // (aspect-ratio label, countdown "Off") can't land on a fractional pixel
        // and blur its hairline border. See snapRotatedTextView.
        View.OnLayoutChangeListener snapOnLayout =
                (v, l, t, r, b, ol, ot, or, ob) -> snapRotatedTextView(v);
        mTvRecordingTimer.addOnLayoutChangeListener(snapOnLayout);
        mTvCustomToast.addOnLayoutChangeListener(snapOnLayout);
        // Now that the toolbars and the long text views exist, set the layout for
        // the current orientation (rebuilds to side strips if we launched in
        // landscape; a no-op restore in portrait).
        applyOrientationLayout(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
        // Horizontal-swipe fling on the bottom bar toggles the toolbar mirror
        // (shutter left <-> right) in portrait. Installed once here; the
        // listener checks mIsLandscape so it's a no-op in landscape.
        installToolbarFlingListener();

        mLastPicture.setOnClickListener(v -> {
            if (mPendingPhotoSaves > 0) {
                // A just-shot photo is still being written; mLastPhotoUri still
                // points at the previous capture. Opening now would show the old
                // one, so defer until savePhotoAsync lands the new Uri.
                mOpenLastPhotoWhenSaved = true;
                return;
            }
            openLastPhoto();
        });

        mSurfaceView.setSurfaceTextureListener(mSurfaceTextureListener);
        mSurfaceView.setOnTouchListener(this::onPreviewTouch);
        mExposureSun.setOnTouchListener(this::onSunTouch);

        // mFocusUiRoot is constrained to mSurfaceView, which resizes whenever
        // the camera attaches, the resolution changes, or replaceSurfaceView
        // swaps in a fresh 0×0 view on resume. The focus container's setX/setY
        // are absolute within mFocusUiRoot and don't reflow when that parent
        // grows or shrinks — so the centre indicator drifts off-centre after
        // any of those events. Re-centre on every layout change while the
        // indicator is in "follow centre" mode.
        mFocusUiRoot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (!mFocusFollowsCentre) return;
            if (r - l <= 0 || b - t <= 0) return;
            centreFocusIndicator();
        });
        mSharedImageView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (mIsImageScanMode) recomputeScanImageMapping();
        });

        // Initial mode = top of the user's customised order. mCurrentMode is
        // still PHOTO from the field initialiser at this point; calling
        // setCameraMode with the new top runs all SCAN-entry side effects
        // (start QR scanner, force native max, place centre focus indicator)
        // when SCAN has been promoted. Falls back to mCurrentMode if the
        // stored order is somehow empty (loadModeOrder would have left it
        // unchanged, but this is the defensive read).
        CameraMode startMode = mModeOrder.isEmpty() ? mCurrentMode : mModeOrder.get(0);
        setCameraMode(startMode);
        updateControlBar();
        updateButtonStates();

        mUsbMonitor = new USBMonitor(this, mUsbMonitorOnDeviceConnectListener);
        mEisManager = new EisManager(this);
        mBarcodeScanner = BarcodeScanning.getClient();
        // The last-photo thumbnail is loaded in onStart (which always follows
        // onCreate), so a resume after the user deleted the photo from the
        // gallery refreshes the reference instead of leaving a stale Uri.

        // Route incoming SEND/VIEW intents and launcher-shortcut mode hints.
        // setCameraMode has already run once with the top of the user's
        // reorder list (PHOTO when the menu hasn't been customised); a shortcut
        // extra here can still override that, and a shared image will switch
        // to SCAN and start the static-image flow.
        handleLaunchModeExtra(getIntent());
        handleIncomingImageIntent(getIntent());
        handleUsbAttachIntent(getIntent());
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // configChanges includes orientation, so the Activity is NOT recreated on
        // a portrait<->landscape rotation (the camera stays connected). Only that
        // transition reaches here — a 180° flip keeps the same orientation. Re-pin
        // the toolbars for the new shape, then refresh the preview transform once
        // the new bounds are laid out.
        applyOrientationLayout(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE);
        if (mSurfaceView != null) {
            mSurfaceView.post(mSurfaceView::refreshTransform);
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private static void setViewSize(View v, int width, int height) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) return;
        lp.width = width;
        lp.height = height;
        v.setLayoutParams(lp);
    }

    // Re-pins the two toolbars for the given orientation. Portrait re-applies the
    // constraints captured verbatim from the inflated XML (so it's restored
    // exactly); landscape rebuilds them as left/right vertical strips of the same
    // 72dp thickness — top bar -> left, bottom bar -> right — stacks the buttons
    // along each strip at their portrait size, and stands the long text elements
    // (recording timer / countdown / toast) up vertically, centred in the left
    // strip. The OS has already rotated the window, so the icons, preview and
    // system bars are upright; this only fixes the bar geometry.
    private void applyOrientationLayout(boolean landscape) {
        if (mRootLayout == null) return;
        mIsLandscape = landscape;

        int bar = dpToPx(72);   // strip thickness == portrait bar height
        int btn = dpToPx(64);   // button length along the strip
        int icon40 = dpToPx(40);

        View[] functionButtons = {
                mContainerQr, mContainerVideoQuality, mContainerAspectRatio,
                mContainerStabilization, mContainerCountdown
        };

        if (!landscape) {
            // Snapshot the runtime visibility of the toast BEFORE the
            // ConstraintSet restore below resets it. tv_custom_toast is
            // android:visibility="gone" in the XML, so mPortraitTopCs
            // (captured at onCreate) records GONE and applyTo would clobber
            // a toast mid-display on every landscape→portrait rotation.
            // The timer doesn't need a snapshot — mIsRecording is its source
            // of truth and is checked below.
            int toastVis = mTvCustomToast.getVisibility();

            // Restore portrait verbatim from the captured constraints.
            mPortraitRootCs.applyTo(mRootLayout);
            mPortraitTopCs.applyTo(mTopUiOverlay);
            mPortraitBottomCs.applyTo(mBottomBar);
            // LinearLayout orientation and the LinearLayout children's sizes are
            // not stored in a ConstraintSet, so restore them explicitly.
            mLeftFunctions.setOrientation(LinearLayout.HORIZONTAL);
            for (View b : functionButtons) {
                setViewSize(b, btn, ViewGroup.LayoutParams.MATCH_PARENT);
            }
            // Undo the landscape text tweaks.
            mTopUiOverlay.setClipChildren(true);
            mTopUiOverlay.setClipToPadding(true);
            mTvRecordingTimer.setMaxLines(Integer.MAX_VALUE);
            mTvCustomToast.setMaxLines(Integer.MAX_VALUE);
            mTvRecordingTimer.setRotation(0f);
            mTvCustomToast.setRotation(0f);
            // Drop the landscape pixel-snap nudge; unrotated views need none.
            mTvRecordingTimer.setTranslationX(0f);
            mTvRecordingTimer.setTranslationY(0f);
            mTvCustomToast.setTranslationX(0f);
            mTvCustomToast.setTranslationY(0f);
            // Re-apply the runtime visibility captured above so live overlays
            // survive the rotation. The timer uses mIsRecording as the source
            // of truth (more reliable than the snapshot, which could race with
            // stopRecording); the toast has no such flag, so use the snapshot.
            mTvRecordingTimer.setVisibility(mIsRecording ? VISIBLE : GONE);
            mTvCustomToast.setVisibility(toastVis);
            // The ConstraintSet restore above resets the bottom-bar anchors to
            // their XML defaults, so re-apply the toolbar mirror if the user
            // had it reversed. No-op when mIsToolbarReversed is false.
            applyToolbarReversal();
            return;
        }

        // ---- Root: top bar -> left strip, bottom bar -> right strip ----
        ConstraintSet root = new ConstraintSet();
        root.clone(mRootLayout);

        root.clear(R.id.top_ui_overlay);
        root.constrainWidth(R.id.top_ui_overlay, bar);
        root.constrainHeight(R.id.top_ui_overlay, ConstraintSet.MATCH_CONSTRAINT);
        root.connect(R.id.top_ui_overlay, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START);
        root.connect(R.id.top_ui_overlay, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP);
        root.connect(R.id.top_ui_overlay, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM);

        root.clear(R.id.bottom_bar);
        root.constrainWidth(R.id.bottom_bar, bar);
        root.constrainHeight(R.id.bottom_bar, ConstraintSet.MATCH_CONSTRAINT);
        root.connect(R.id.bottom_bar, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END);
        root.connect(R.id.bottom_bar, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP);
        root.connect(R.id.bottom_bar, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM);

        // Mode / more menus pop out to the left of the right strip, aligned with
        // their anchor buttons (action button at the strip top, more at the bottom).
        int menuW = dpToPx(64);
        root.clear(R.id.mode_menu_sidebar);
        root.constrainWidth(R.id.mode_menu_sidebar, menuW);
        root.constrainHeight(R.id.mode_menu_sidebar, ConstraintSet.WRAP_CONTENT);
        root.connect(R.id.mode_menu_sidebar, ConstraintSet.END, R.id.bottom_bar, ConstraintSet.START);
        root.connect(R.id.mode_menu_sidebar, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP);

        root.clear(R.id.more_menu_sidebar);
        root.constrainWidth(R.id.more_menu_sidebar, menuW);
        root.constrainHeight(R.id.more_menu_sidebar, ConstraintSet.WRAP_CONTENT);
        root.connect(R.id.more_menu_sidebar, ConstraintSet.END, R.id.bottom_bar, ConstraintSet.START);
        root.connect(R.id.more_menu_sidebar, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM);
        // ConstraintSet.clear() resets a view's visibility to VISIBLE, which would
        // pop both (normally GONE) menus open on rotation — restore GONE so a
        // rotation never opens a menu the user didn't tap.
        root.setVisibility(R.id.mode_menu_sidebar, View.GONE);
        root.setVisibility(R.id.more_menu_sidebar, View.GONE);
        root.applyTo(mRootLayout);

        // ---- Left strip internals: thumbnail top, close bottom, text centred ----
        ConstraintSet top = new ConstraintSet();
        top.clone(mTopUiOverlay);

        top.clear(R.id.container_thumbnail);
        top.constrainWidth(R.id.container_thumbnail, icon40);
        top.constrainHeight(R.id.container_thumbnail, icon40);
        top.connect(R.id.container_thumbnail, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START);
        top.connect(R.id.container_thumbnail, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END);
        top.connect(R.id.container_thumbnail, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, dpToPx(16));

        top.clear(R.id.container_close);
        top.constrainWidth(R.id.container_close, ConstraintSet.MATCH_CONSTRAINT);
        top.constrainHeight(R.id.container_close, bar);
        top.connect(R.id.container_close, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START);
        top.connect(R.id.container_close, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END);
        top.connect(R.id.container_close, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM);
        // Centre the timer/toast on the strip's vertical centre LINE (both edges
        // pinned to the zero-width guideline) rather than between the strip's
        // start and end. Pinning both edges to one line keeps the text fixed on
        // the centre AND lets wrap_content measure at its natural single-line
        // width (overflowing the 72dp strip) instead of being capped/truncated to
        // it. After the 270° rotation that natural width becomes the vertical
        // extent, growing/shrinking symmetrically about the fixed centre.
        for (int tvId : new int[]{R.id.tv_recording_timer, R.id.tv_custom_toast}) {
            top.connect(tvId, ConstraintSet.START, R.id.top_bar_center_guideline, ConstraintSet.START);
            top.connect(tvId, ConstraintSet.END, R.id.top_bar_center_guideline, ConstraintSet.END);
            top.connect(tvId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP);
            top.connect(tvId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM);
            top.constrainedWidth(tvId, false);
            top.constrainWidth(tvId, ConstraintSet.WRAP_CONTENT);
        }
        top.applyTo(mTopUiOverlay);

        // ---- Right strip internals: functions stacked from top, more at bottom ----
        ConstraintSet bottom = new ConstraintSet();
        bottom.clone(mBottomBar);

        bottom.clear(R.id.left_functions);
        bottom.constrainWidth(R.id.left_functions, ConstraintSet.MATCH_CONSTRAINT);
        bottom.constrainHeight(R.id.left_functions, ConstraintSet.WRAP_CONTENT);
        bottom.connect(R.id.left_functions, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START);
        bottom.connect(R.id.left_functions, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END);
        bottom.connect(R.id.left_functions, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP);

        bottom.clear(R.id.container_more);
        bottom.constrainWidth(R.id.container_more, ConstraintSet.MATCH_CONSTRAINT);
        bottom.constrainHeight(R.id.container_more, bar);
        bottom.connect(R.id.container_more, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START);
        bottom.connect(R.id.container_more, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END);
        bottom.connect(R.id.container_more, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM);
        bottom.applyTo(mBottomBar);

        // Stack the function buttons vertically at their portrait size.
        mLeftFunctions.setOrientation(LinearLayout.VERTICAL);
        for (View b : functionButtons) {
            setViewSize(b, ViewGroup.LayoutParams.MATCH_PARENT, btn);
        }

        // Let the now-overflowing single-line text spill outside the 72dp strip
        // (pre-rotation) without being clipped; the 270° rotation brings it back
        // inside the strip's full height.
        mTopUiOverlay.setClipChildren(false);
        mTopUiOverlay.setClipToPadding(false);
        // Single line so long content (stabilization toast, timer) never wraps to
        // two lines, and stand the text up with its baseline facing right (270°,
        // i.e. reads bottom-to-top) as requested.
        mTvRecordingTimer.setMaxLines(1);
        mTvCustomToast.setMaxLines(1);
        mTvRecordingTimer.setRotation(270f);
        mTvCustomToast.setRotation(270f);
        // The constraint changes above re-lay-out both views, which fires the
        // OnLayoutChangeListener that snaps them — but only if their bounds
        // actually change. On a portrait->landscape rotation with unchanged text
        // the bounds can be identical, so fit + snap explicitly here too.
        fitRotatedTextWidth(mTvRecordingTimer);
        fitRotatedTextWidth(mTvCustomToast);
        snapRotatedTextView(mTvRecordingTimer);
        snapRotatedTextView(mTvCustomToast);
        // The ConstraintSet rebuild above resets the bottom-bar anchors to
        // their landscape defaults (functions at top, more at bottom), so
        // re-apply the toolbar mirror if the user had it reversed. This
        // makes the reversed state carry over from portrait to landscape
        // and vice-versa, as the user expects.
        applyToolbarReversal();
    }

    // Pins an explicit content-sized width on a rotated (landscape) text view so
    // its full single-line text shows; restores WRAP_CONTENT in portrait.
    //
    // ConstraintLayout measures a wrap_content child AT_MOST the parent strip's
    // width (72dp), so with maxLines(1) any single-line string wider than the
    // strip — "Stabilization ON", the 00:00:00 timer — is clipped to the strip at
    // measure time (it showed as "Stabili"). We size the width to the text's own
    // desired width (Layout.getDesiredWidth is exactly what TextView measures)
    // plus padding, which sidesteps that cap; the 270° rotation then maps the
    // width onto the screen's (ample) height. Called from each setText site (and
    // on orientation change) — NOT from the layout pass — so there is no
    // re-entrant measure/relayout, which is what previously blanked short toasts.
    // Content narrower than the strip is unchanged (same width either way).
    private void fitRotatedTextWidth(TextView tv) {
        ViewGroup.LayoutParams lp = tv.getLayoutParams();
        if (lp == null) return;
        int want;
        if (mIsLandscape) {
            CharSequence text = tv.getText();
            float textW = (text == null) ? 0f : Layout.getDesiredWidth(text, tv.getPaint());
            want = (int) Math.ceil(textW) + tv.getPaddingLeft() + tv.getPaddingRight();
        } else {
            want = ViewGroup.LayoutParams.WRAP_CONTENT;
        }
        if (lp.width != want) {
            lp.width = want;
            tv.setLayoutParams(lp);
        }
    }

    // A view rotated by an exact 90°/270° multiple is re-sampled by the GPU when
    // it is composited. The 270° rotation is about the view's centre, so the
    // rotated content keeps that centre and its bounding box's top-left sits at
    // (centreX - h/2, centreY - w/2). When that point lands on a fractional pixel
    // the resample bilinearly blurs the content — most visibly the toast's 1dp
    // hairline border, which then reads as a thick, fuzzy line. Whether it lands
    // whole depends on the view's pixel size, so a SHORT message (the aspect-ratio
    // label, the countdown "Off") blurs while a longer one happens to stay crisp.
    //
    // Nudge translation by < 1px so that bounding-box origin is whole. A 90°
    // rotation at scale 1 onto a whole-pixel origin maps every source texel onto a
    // destination texel exactly, so the border renders at its true 1dp for any
    // message length and screen density. No-op unless we're in the rotated layout.
    private void snapRotatedTextView(View v) {
        if (!mIsLandscape) return;
        int w = v.getWidth();
        int h = v.getHeight();
        if (w == 0 || h == 0) return;
        v.setPivotX(w / 2f);
        v.setPivotY(h / 2f);
        float boxLeft = v.getLeft() + (w - h) / 2f;
        float boxTop = v.getTop() + (h - w) / 2f;
        v.setTranslationX(Math.round(boxLeft) - boxLeft);
        v.setTranslationY(Math.round(boxTop) - boxTop);
    }

    // --- Toolbar reversal ---------------------------------------------------

    // A swipe-fling on the toolbar toggles mIsToolbarReversed, which mirrors
    // the shutter/action button and the "more" button to opposite edges of
    // the toolbar strip. In portrait the fling is horizontal (left<->right
    // swap on the bottom bar); in landscape it's vertical (top<->bottom swap
    // on the right-side strip). Both detectors are fed from
    // Activity.dispatchTouchEvent (not setOnTouchListener) because ViewGroup
    // only forwards events to its own touch listener when no child consumes
    // them — a tap on a button would never reach the listener. dispatchTouchEvent
    // sees every event regardless of which child consumes it, so the
    // GestureDetector gets a complete DOWN→MOVE→UP stream.
    private GestureDetector mToolbarFlingDetectorVertical;

    // Horizontal-fling detector on the bottom bar. A left or right swipe
    // (GestureDetector standard fling: velocity > threshold, mostly-horizontal
    // travel) toggles mIsToolbarReversed and re-arranges the bottom bar so the
    // shutter/action button moves to the opposite edge. The detector is fed
    // from Activity.dispatchTouchEvent (not from a setOnTouchListener on
    // mBottomBar) because ViewGroup only forwards events to its own touch
    // listener when no child consumes them — a tap on a button would never
    // reach the listener, breaking fling detection when the swipe starts on a
    // button. dispatchTouchEvent sees every event regardless of which child
    // consumes it, so the GestureDetector gets a complete DOWN→MOVE→UP stream.
    private GestureDetector mToolbarFlingDetector;

    private void installToolbarFlingListener() {
        // Portrait: horizontal fling on the bottom bar.
        mToolbarFlingDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2,
                                    float velocityX, float velocityY) {
                if (mIsLandscape) return false;
                if (Math.abs(velocityX) < 600) return false;
                if (Math.abs(velocityX) < Math.abs(velocityY)) return false;
                mIsToolbarReversed = !mIsToolbarReversed;
                getPrefs().edit()
                        .putBoolean(PREF_TOOLBAR_REVERSED, mIsToolbarReversed)
                        .apply();
                applyToolbarReversal();
                return true;
            }
        });
        // Landscape: vertical fling on the right-side strip (mBottomBar).
        mToolbarFlingDetectorVertical = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2,
                                    float velocityX, float velocityY) {
                if (!mIsLandscape) return false;
                if (Math.abs(velocityY) < 600) return false;
                if (Math.abs(velocityY) < Math.abs(velocityX)) return false;
                mIsToolbarReversed = !mIsToolbarReversed;
                getPrefs().edit()
                        .putBoolean(PREF_TOOLBAR_REVERSED, mIsToolbarReversed)
                        .apply();
                applyToolbarReversal();
                return true;
            }
        });
    }

    // Re-arranges the toolbar to match mIsToolbarReversed. In portrait the
    // function-button group and the "more" button swap left/right edges; in
    // landscape they swap top/bottom within the right-side strip. The mode/more
    // menu sidebars are mirrored too so they still pop out beside their anchor
    // buttons.
    //
    // This method is idempotent: it reads mIsToolbarReversed as the source of
    // truth and arranges the views to match. The LinearLayout children are
    // re-ordered to a known order (XML order or its reverse) rather than
    // toggled, so calling it twice with the same value is a no-op. Safe to
    // call from both applyOrientationLayout (after ConstraintSet restore) and
    // onFling (no restore has run) — both branches set anchors explicitly.
    private void applyToolbarReversal() {
        if (mLeftFunctions == null || mContainerMore == null) return;

        // 1. Arrange the function-group children to match the flag. The XML
        //    order is: qr, video_quality, aspect_ratio, stabilization,
        //    countdown. When reversed, the first child should be countdown.
        //    This check is orientation-independent — the child order inside
        //    mLeftFunctions is the same whether the LinearLayout is horizontal
        //    (portrait) or vertical (landscape); only the visual direction
        //    differs.
        int n = mLeftFunctions.getChildCount();
        View firstChild = n > 0 ? mLeftFunctions.getChildAt(0) : null;
        boolean currentlyReversed = n > 0
                && firstChild != null
                && firstChild.getId() == R.id.container_countdown;
        if (mIsToolbarReversed != currentlyReversed) {
            for (int i = n - 1; i >= 0; i--) {
                View child = mLeftFunctions.getChildAt(i);
                mLeftFunctions.removeViewAt(i);
                mLeftFunctions.addView(child);
            }
        }

        // 2. Set the anchors. In portrait the swap axis is start/end (left/
        //    right); in landscape it's top/bottom. All four affected views
        //    (function group, more button, mode menu, more menu) are set
        //    explicitly in both branches so the method works regardless of
        //    whether a ConstraintSet restore ran before it.
        ConstraintLayout.LayoutParams lpFunctions =
                (ConstraintLayout.LayoutParams) mLeftFunctions.getLayoutParams();
        ConstraintLayout.LayoutParams lpMore =
                (ConstraintLayout.LayoutParams) mContainerMore.getLayoutParams();
        ConstraintLayout.LayoutParams lpMode =
                (ConstraintLayout.LayoutParams) mModeMenuSidebar.getLayoutParams();
        ConstraintLayout.LayoutParams lpMoreMenu =
                (ConstraintLayout.LayoutParams) mMoreMenuSidebar.getLayoutParams();

        if (mIsLandscape) {
            // Landscape: bottom_bar is the right-side strip. Default
            // (non-reversed): functions at top, more at bottom. Reversed:
            // functions at bottom, more at top.
            if (mIsToolbarReversed) {
                lpFunctions.topToTop = ConstraintLayout.LayoutParams.UNSET;
                lpFunctions.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
                lpMore.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
                lpMore.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
                // Mode menu follows the shutter (now at bottom), more menu
                // follows the more button (now at top). Both still pop out
                // to the left of the right strip (END->START of bottom_bar).
                lpMode.topToTop = ConstraintLayout.LayoutParams.UNSET;
                lpMode.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
                lpMoreMenu.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
                lpMoreMenu.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
            } else {
                lpFunctions.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
                lpFunctions.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
                lpMore.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
                lpMore.topToTop = ConstraintLayout.LayoutParams.UNSET;
                lpMode.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
                lpMode.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
                lpMoreMenu.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
                lpMoreMenu.topToTop = ConstraintLayout.LayoutParams.UNSET;
            }
        } else {
            // Portrait: default (non-reversed): functions at left, more at
            // right. Reversed: functions at right, more at left.
            if (mIsToolbarReversed) {
                lpFunctions.startToStart = ConstraintLayout.LayoutParams.UNSET;
                lpFunctions.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
                lpMore.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
                lpMore.endToEnd = ConstraintLayout.LayoutParams.UNSET;
                lpMode.startToStart = ConstraintLayout.LayoutParams.UNSET;
                lpMode.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
                lpMoreMenu.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
                lpMoreMenu.endToEnd = ConstraintLayout.LayoutParams.UNSET;
            } else {
                lpFunctions.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
                lpFunctions.endToEnd = ConstraintLayout.LayoutParams.UNSET;
                lpMore.startToStart = ConstraintLayout.LayoutParams.UNSET;
                lpMore.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
                lpMode.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
                lpMode.endToEnd = ConstraintLayout.LayoutParams.UNSET;
                lpMoreMenu.startToStart = ConstraintLayout.LayoutParams.UNSET;
                lpMoreMenu.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
            }
        }
        mLeftFunctions.setLayoutParams(lpFunctions);
        mContainerMore.setLayoutParams(lpMore);
        mModeMenuSidebar.setLayoutParams(lpMode);
        mMoreMenuSidebar.setLayoutParams(lpMoreMenu);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Activity is singleTask-like in practice (default launchMode + the
        // launcher intent-filter), so a second share lands here instead of
        // onCreate. Re-run the same routing — handleIncomingImageIntent will
        // swap the displayed bitmap and restart scanning.
        setIntent(intent);
        handleLaunchModeExtra(intent);
        handleIncomingImageIntent(intent);
        handleUsbAttachIntent(intent);
    }

    private void handleLaunchModeExtra(Intent intent) {
        if (intent == null) return;
        String launchMode = intent.getStringExtra("tw.newxe.einkcamera.EXTRA_LAUNCH_MODE");
        if (launchMode == null) return;
        // Consume so a configuration change / re-entry doesn't reapply it.
        intent.removeExtra("tw.newxe.einkcamera.EXTRA_LAUNCH_MODE");
        try {
            CameraMode target = CameraMode.valueOf(launchMode);
            if (target != mCurrentMode) {
                setCameraMode(target);
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    // The manifest USB_DEVICE_ATTACHED intent-filter (+ @xml/device_filter) is
    // the sole permission path: once the user ticks "always open with this app"
    // in the system dialog, the OS launches us with the matching UVC device
    // ALREADY granted and hands it over via EXTRA_DEVICE. We forward it to
    // requestPermission, which short-circuits to processConnect (no prompt)
    // whenever hasPermission() is already true — so this is silent on every
    // attach after the first. USBMonitor's own onReceive only updatePermission()s
    // on attach (it never processConnect()s, and register() deliberately omits
    // the attach action because "it never comes on some devices"), so without
    // this hook the auto-granted device would just sit there until the 2s poll —
    // the exact "plugged in, opened later, no preview" gap the filter exists to
    // close. requestPermission tolerates a device that's already connected, so
    // the overlap with the poll is harmless.
    private void handleUsbAttachIntent(Intent intent) {
        if (intent == null) return;
        if (!USBMonitor.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) return;
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device == null || !isUvcCamera(device)) return;
        if (mUsbMonitor != null) {
            mUsbMonitor.requestPermission(device);
        }
    }

    // ACTION_SEND (single image via EXTRA_STREAM) and ACTION_VIEW with an
    // image/* Uri both land here. We decode the bitmap off the main thread,
    // then enter "image scan" sub-state of SCAN mode: camera surface hidden,
    // shared_image_view shows the bitmap, performQrScan runs against the
    // bitmap directly. Failures (no Uri, decode error) just show a toast and
    // leave the app in whatever mode it was in.
    private void handleIncomingImageIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;
        Uri imageUri = null;
        if (Intent.ACTION_SEND.equals(action)) {
            String type = intent.getType();
            if (type == null || !type.startsWith("image/")) return;
            imageUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            String type = intent.getType();
            // VIEW may arrive without a type when the data Uri's scheme is
            // content:// and the system resolved us via the mime filter.
            if (type != null && !type.startsWith("image/")) return;
            imageUri = intent.getData();
        }
        if (imageUri == null) return;

        // Consume so a config change doesn't redecode the same Uri (and so
        // back-to-camera-mode-then-rotate doesn't pull us back into scan).
        intent.setAction(null);
        intent.setData(null);
        intent.removeExtra(Intent.EXTRA_STREAM);

        final Uri uri = imageUri;
        new Thread(() -> {
            Bitmap bitmap = decodeBitmapFromUri(uri);
            mUiHandler.post(() -> {
                if (bitmap == null) {
                    showCustomToast(getString(R.string.image_load_failed));
                    return;
                }
                enterImageScanMode(bitmap);
            });
        }, "decode-shared-image").start();
    }

    // Bounded decode to keep memory + ML Kit happy on huge gallery shots.
    // 4096px on the long edge is well above what ML Kit needs for QR codes
    // and matches the threshold we use elsewhere (capturePhoto saves at
    // sensor native, which is typically <= this).
    private Bitmap decodeBitmapFromUri(Uri uri) {
        final int maxEdge = 4096;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            int longEdge = Math.max(bounds.outWidth, bounds.outHeight);
            while (longEdge / sample > maxEdge) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private void enterImageScanMode(Bitmap bitmap) {
        // Clear any previous static bitmap before swapping. The old one is
        // owned by us (decodeBitmapFromUri allocated it), so safe to recycle.
        if (mScanImageBitmap != null && mScanImageBitmap != bitmap) {
            mScanImageBitmap.recycle();
        }
        mScanImageBitmap = bitmap;
        mIsImageScanMode = true;
        mImageScanFoundAny = false;
        mImageScanAttempts = 0;

        mSharedImageView.setImageBitmap(bitmap);
        mSharedImageView.setVisibility(VISIBLE);
        mSurfaceView.setVisibility(View.INVISIBLE);

        // Force SCAN mode UI without going through the camera-resolution
        // gymnastics setCameraMode does — we're not driving the camera here.
        // Clear any existing overlays from a live SCAN session first.
        mQrOverlayContainer.removeAllViews();
        mActiveQrOverlays.clear();
        mUiHandler.removeCallbacks(mQrScanRunnable);

        // Exposure HUD is camera-only — hide it. The centre focus frame, on
        // the other hand, is kept for UI consistency with live SCAN mode
        // (showFocusUi below); hideFocusUi already no-ops in SCAN.
        mExposureTrack.setVisibility(View.INVISIBLE);
        mExposureSun.setVisibility(View.GONE);

        mCurrentMode = CameraMode.SCAN;
        mBtnQrToggle.setImageResource(R.drawable.ic_qr_code_scanner);
        mModeMenuSidebar.setVisibility(View.GONE);
        updateControlBar();
        updateButtonStates();
        updateCountdownDisplay();
        // Static-image scan is still SCAN — keep the grid hidden here too.
        applyGridOverlayVisibility();

        // Compute fitCenter scale + offset once, and place the SCAN centre
        // focus indicator over the shared image. mSharedImageView may not yet
        // be laid out (we just made it VISIBLE), so post.
        mFocusFollowsCentre = true;
        mSharedImageView.post(() -> {
            recomputeScanImageMapping();
            if (mCurrentMode != CameraMode.SCAN) return;
            centreFocusIndicator();
        });

        // Kick off one-shot scan. mQrScanRunnable's modeAllowsScan guard
        // accepts SCAN; the image-scan branch in performQrScan handles the
        // single-shot semantics (no re-post once mImageScanFoundAny).
        mQrScanEnabled = true;
        mUiHandler.post(mQrScanRunnable);
    }

    private void recomputeScanImageMapping() {
        if (mScanImageBitmap == null) return;
        float viewW = mSharedImageView.getWidth();
        float viewH = mSharedImageView.getHeight();
        float bmpW = mScanImageBitmap.getWidth();
        float bmpH = mScanImageBitmap.getHeight();
        if (viewW <= 0 || viewH <= 0 || bmpW <= 0 || bmpH <= 0) {
            mScanImageScale = 1f;
            mScanImageOffsetX = 0;
            mScanImageOffsetY = 0;
            return;
        }
        // fitCenter: scale by the smaller of the two ratios, then centre.
        float scale = Math.min(viewW / bmpW, viewH / bmpH);
        mScanImageScale = scale;
        mScanImageOffsetX = (int) ((viewW - bmpW * scale) / 2f);
        mScanImageOffsetY = (int) ((viewH - bmpH * scale) / 2f);
    }

    private void exitImageScanMode() {
        if (!mIsImageScanMode) return;
        mIsImageScanMode = false;
        // setCameraMode is the only entry path that calls us today; the flag
        // will be cleared there too if we're leaving SCAN. Clearing here too
        // is cheap insurance for any future direct caller.
        mFocusFollowsCentre = (mCurrentMode == CameraMode.SCAN);
        mSharedImageView.setVisibility(View.GONE);
        mSharedImageView.setImageDrawable(null);
        mSurfaceView.setVisibility(VISIBLE);
        if (mScanImageBitmap != null) {
            mScanImageBitmap.recycle();
            mScanImageBitmap = null;
        }
        mQrOverlayContainer.removeAllViews();
        mActiveQrOverlays.clear();
        mAutoRunLastFired.clear();
        mUiHandler.removeCallbacks(mQrScanRunnable);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // Feed the toolbar-fling detector(s) before any child consumes the
        // event. dispatchTouchEvent sees the full DOWN→MOVE→UP stream even
        // when a button child consumes them, so the GestureDetector can
        // recognise a fling that starts on a button and ends in empty space.
        // The active detector depends on orientation: portrait uses a
        // horizontal-fling detector, landscape a vertical one. Only events
        // that begin on the toolbar strip (mBottomBar in both orientations)
        // seed the detector, so a swipe on the preview or top bar is ignored.
        GestureDetector detector = mIsLandscape
                ? mToolbarFlingDetectorVertical : mToolbarFlingDetector;
        if (detector != null && mBottomBar != null) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                Rect barRect = new Rect();
                mBottomBar.getGlobalVisibleRect(barRect);
                if (barRect.contains((int) ev.getRawX(), (int) ev.getRawY())) {
                    detector.onTouchEvent(ev);
                }
            } else {
                detector.onTouchEvent(ev);
            }
        }
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            int x = (int) ev.getRawX();
            int y = (int) ev.getRawY();
            if (mModeMenuSidebar != null && mModeMenuSidebar.getVisibility() == View.VISIBLE) {
                Rect menuRect = new Rect();
                mModeMenuSidebar.getGlobalVisibleRect(menuRect);
                if (!menuRect.contains(x, y)) {
                    // Check if it's the toggle button itself to let its own listener handle it
                    Rect btnRect = new Rect();
                    mBtnQrToggle.getGlobalVisibleRect(btnRect);
                    if (!btnRect.contains(x, y)) {
                        mModeMenuSidebar.setVisibility(View.GONE);
                    }
                }
            }
            if (mMoreMenuSidebar != null && mMoreMenuSidebar.getVisibility() == View.VISIBLE) {
                Rect menuRect = new Rect();
                mMoreMenuSidebar.getGlobalVisibleRect(menuRect);
                if (!menuRect.contains(x, y)) {
                    // Let the more button's own listener handle taps on itself.
                    Rect btnRect = new Rect();
                    mBtnMore.getGlobalVisibleRect(btnRect);
                    if (!btnRect.contains(x, y)) {
                        mMoreMenuSidebar.setVisibility(View.GONE);
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    // Toggles the more-features sidebar, keeping it mutually exclusive with the
    // mode selector so the two boxes never overlap on screen.
    private void toggleMoreMenu() {
        if (mModeMenuSidebar != null) mModeMenuSidebar.setVisibility(View.GONE);
        boolean show = mMoreMenuSidebar.getVisibility() != View.VISIBLE;
        if (show) {
            // Grid is a PHOTO/VIDEO framing aid only, so the menu doesn't even
            // offer it in SCAN mode. The LinearLayout's showDividers="middle"
            // skips dividers around a GONE child, so About is left clean.
            mBtnMoreGrid.setVisibility(mCurrentMode == CameraMode.SCAN ? View.GONE : View.VISIBLE);
        }
        mMoreMenuSidebar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // Lightweight tap handler for the more-menu items. No drag-reordering (unlike
    // the mode menu). On tap it flashes the same black-background / white-icon
    // selection animation the bottom toolbar buttons use (see flashSelection and
    // setModeItemPressedBackground), then dismisses the menu and runs the action.
    // The 200ms delay mirrors flashSelection so the flash is actually visible
    // before the sidebar closes — dismissing immediately would hide it.
    // requiresCamera items follow the no-camera lock policy: the tap is fully
    // dead while nothing is attached — no flash, no menu dismiss — because the
    // grid is a framing aid over the live preview and has nothing to frame.
    // About / Auto Run pass false and stay usable without a camera.
    private void installMoreMenuItemListener(View item, Runnable action, boolean requiresCamera) {
        item.setOnClickListener(v -> {
            if (requiresCamera && isNoCameraLocked()) return;
            setModeItemPressedBackground(item, true);
            mUiHandler.postDelayed(() -> {
                setModeItemPressedBackground(item, false);
                mMoreMenuSidebar.setVisibility(View.GONE);
                action.run();
            }, 200);
        });
    }

    private void toggleGridOverlay() {
        // Grid is a framing aid for PHOTO/VIDEO only; it stays hidden in SCAN
        // (applyGridOverlayVisibility) and toggleMoreMenu hides the grid button
        // itself in SCAN, so this can't be reached there — no SCAN guard needed.
        // Flip the saved preference (the source of truth) rather than the current
        // visibility — visibility can be forced GONE independently of the pref.
        boolean enabled = !getPrefs().getBoolean(PREF_GRID_ENABLED, false);
        getPrefs().edit().putBoolean(PREF_GRID_ENABLED, enabled).apply();
        applyGridOverlayVisibility();
        // No toast on toggle — the grid appearing/disappearing is obvious on screen.
    }

    // Grid overlay is a PHOTO/VIDEO framing aid — force it hidden in SCAN mode
    // regardless of the saved preference, and restore the preference on the way
    // back out. Single choke point so setCameraMode, the menu toggle, and the
    // image-scan sub-mode all agree on when the grid is shown.
    private void applyGridOverlayVisibility() {
        boolean show = mCurrentMode != CameraMode.SCAN
                && getPrefs().getBoolean(PREF_GRID_ENABLED, false);
        mGridOverlay.setVisibility(show ? VISIBLE : GONE);
    }

    private void loadLastPhoto() {
        new Thread(() -> {
            // Sort by DATE_ADDED (not DATE_TAKEN): savePhoto() writes via
            // Bitmap.compress, producing JPEGs with no EXIF DateTime, so
            // MediaStore leaves DATE_TAKEN null on every row this app inserts.
            // DESC sorting on an all-null column is undefined in SQLite —
            // after a full app close the thumbnail would land on an arbitrary
            // row. DATE_ADDED is always populated by the provider on insert.
            long imageDate = Long.MIN_VALUE;
            Uri imageUri = queryNewest(
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                            : MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Images.Media.RELATIVE_PATH);
            long videoDate = Long.MIN_VALUE;
            Uri videoUri = queryNewest(
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                            : MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Video.Media.RELATIVE_PATH);

            // queryNewest packs DATE_ADDED into the Uri fragment so we can
            // compare the two collections without juggling parallel arrays.
            if (imageUri != null) imageDate = Long.parseLong(imageUri.getFragment());
            if (videoUri != null) videoDate = Long.parseLong(videoUri.getFragment());

            final boolean pickVideo = videoUri != null && (imageUri == null || videoDate > imageDate);
            final Uri pickedUri;
            final String pickedMime;
            final Bitmap videoThumb;
            if (pickVideo) {
                pickedUri = stripFragment(videoUri);
                pickedMime = "video/*";
                videoThumb = extractVideoFirstFrame(pickedUri);
            } else if (imageUri != null) {
                pickedUri = stripFragment(imageUri);
                pickedMime = "image/*";
                videoThumb = null;
            } else {
                // No photos or videos left in DCIM/EinkCamera (e.g. the user just
                // deleted the only capture from the gallery). Clear the stale
                // reference and blank the thumbnail so a tap can't open a dead Uri.
                mUiHandler.post(() -> {
                    mLastPhotoUri = null;
                    mLastPicture.setImageDrawable(null);
                });
                return;
            }

            mUiHandler.post(() -> {
                mLastPhotoUri = pickedUri;
                mLastPhotoMimeType = pickedMime;
                if (videoThumb != null) {
                    mLastPicture.setImageBitmap(videoThumb);
                } else {
                    mLastPicture.setImageURI(pickedUri);
                }
            });
        }, "load-last-photo").start();
    }

    // Returns the newest content URI in `collection` (an Images or Video
    // MediaStore collection), filtered to DCIM/EinkCamera on Q+. The row's
    // DATE_ADDED is stashed in the Uri fragment so the caller can compare
    // across collections without a wrapper class. Returns null when empty.
    private Uri queryNewest(Uri collection, String relativePathColumn) {
        String selection = null;
        String[] selectionArgs = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = relativePathColumn + " LIKE ?";
            selectionArgs = new String[]{"DCIM/EinkCamera%"};
        }
        String[] projection = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED};
        try (Cursor cursor = getContentResolver().query(
                collection, projection, selection, selectionArgs,
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                long dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED));
                return Uri.withAppendedPath(collection, String.valueOf(id))
                        .buildUpon().fragment(String.valueOf(dateAdded)).build();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Uri stripFragment(Uri uri) {
        return uri.buildUpon().fragment(null).build();
    }

    private Bitmap extractVideoFirstFrame(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            // OPTION_CLOSEST_SYNC at t=0 returns the first keyframe quickly.
            // MediaRecorder output always starts on a keyframe, so this *is*
            // the first frame for our own captures — no need for the slower
            // OPTION_CLOSEST that can decode forward to a non-sync frame.
            return retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Exception e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mIsStarted = true;
        // Returning from background (e.g. user switched to gallery and came back):
        // onStop closed the camera and a fresh UVCCamera will be created when the
        // USB monitor re-fires onConnect. The TextureView's internal SurfaceTexture
        // may have been destroyed and recreated by the OS during the pause, but in
        // practice rebinding the new camera to that recycled SurfaceTexture often
        // produces a permanent black preview — same failure mode as in-place
        // resolution switching (see updateCameraResolution). The cure is identical:
        // hand the new camera a brand-new TextureView/SurfaceTexture/BufferQueue,
        // so onConnect's runnable (which sets source size + isAvailable check) and
        // the listener's onSurfaceTextureAvailable both operate on a clean producer.
        if (mNeedsFreshSurfaceView) {
            mNeedsFreshSurfaceView = false;
            replaceSurfaceView(0, 0, 0, 0);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PERMISSION_GRANTED) {
            mUsbMonitor.register();
        }
        // Re-evaluate after the grace period; covers both initial launch
        // (onCreate → onStart) and resume. tryStartPendingPreview cancels this
        // the moment a camera connects.
        scheduleNoCameraCheck();
        if (mDisplayManager == null) {
            mDisplayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        }
        if (mDisplayManager != null) {
            mDisplayManager.registerDisplayListener(mDisplayListener, mUiHandler);
        }
        // Refresh the thumbnail + last-media reference on every resume. The user
        // can delete the photo from the gallery while we're backgrounded; this
        // re-points mLastPhotoUri at the newest surviving item (or clears it when
        // nothing remains) so a tap doesn't open a dead Uri and crash.
        loadLastPhoto();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PERMISSION_GRANTED) {
            if (mUsbMonitor != null) {
                mUsbMonitor.register();
            }
        } else {
        }
    }

    @Override
    protected void onStop() {
        // Clear before mCamera.close() below: close() → UsbControlBlock.close()
        // fires onDisconnect synchronously, and showNoCameraReminder must see
        // the Activity as already stopped so it doesn't leak a dialog window.
        mIsStarted = false;
        if (mDisplayManager != null) {
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
        }
        dismissNoCameraReminder();
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.close();
            mCamera = null;
        }
        mPendingPhotoFrame = false;
        // Drop any deferred thumbnail-open and reset the in-flight save counter
        // so a capture interrupted by a stop can't leave the counter stuck > 0
        // (which would defer every future tap forever). Any worker still writing
        // decrements through Math.max and is harmless.
        mPendingPhotoSaves = 0;
        mOpenLastPhotoWhenSaved = false;
        mUsbMonitor.unregister();

        hideFocusUi();
        // Flag so the next onStart swaps in a fresh TextureView before the USB
        // monitor re-registers and onConnect attaches a new UVCCamera.
        mNeedsFreshSurfaceView = true;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (mBarcodeScanner != null) {
            mBarcodeScanner.close();
        }
        if (mScanImageBitmap != null) {
            mScanImageBitmap.recycle();
            mScanImageBitmap = null;
        }
        mUsbMonitor.destroy();
        mUsbMonitor = null;
        super.onDestroy();
    }

    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            tryStartPendingPreview(new Surface(surface));
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
        }
    };

    private void updateStabilizationIcon() {
        // The glyph never swaps — on/off is announced by the toast on toggle.
        // The only on-button cue is the tint: green when ON, otherwise the
        // toolbar's default black (matching every other toolbar button).
        if (mIsStabilizationEnabled) {
            mBtnStabilization.setColorFilter(0xFF34C759); // Green tint when ON
        } else {
            mBtnStabilization.setColorFilter(0xFF000000);
        }
    }

    private void tryStartPendingPreview(Surface surface) {
        if (mPendingCamera == null) {
            return;
        }
        UVCCamera camera = mPendingCamera;
        mPendingCamera = null;

        if (mCurrentMode == CameraMode.VIDEO && mIsStabilizationEnabled) {
            // EisGlProcessor needs the camera's *actual sensor* resolution (source),
            // not the video-quality target. On a 4:3 sensor at QHD the camera runs
            // at 2592×1944 while the target is 2560×1440 — EisGlProcessor uses
            // mWidth/mHeight as the input aspect for per-surface center-crop, so
            // it must be the real sensor size (4:3) to detect the aspect mismatch
            // against the MediaRecorder's 16:9 surface.
            int sourceW = mSurfaceView.getSourceWidth();
            int sourceH = mSurfaceView.getSourceHeight();
            if (sourceW <= 0 || sourceH <= 0) {
                sourceW = mSurfaceView.getTargetWidth();
                sourceH = mSurfaceView.getTargetHeight();
            }

            if (mEisGlProcessor != null) {
                mEisGlProcessor.release();
            }
            mEisManager.start();
            mEisGlProcessor = new EisGlProcessor(mEisManager, sourceW, sourceH);

            // Pin the preview SurfaceTexture's buffer to the camera's source
            // resolution. Without this the buffer defaults to the TextureView's
            // on-screen size, and updateTransform — which undoes TextureView's
            // default stretch using mSourceWidth/mSourceHeight — would mis-
            // transform the EIS-rendered buffer. At source size the buffer
            // matches what updateTransform expects, so the preview's center-crop
            // (rotate + scale-to-fill) works exactly as in the non-EIS path.
            SurfaceTexture st = mSurfaceView.getSurfaceTexture();
            if (st != null) {
                st.setDefaultBufferSize(sourceW, sourceH);
            }

            // Preview surface: cropToAspectRatio=false — let
            // AspectRatioSurfaceView.updateTransform handle center-crop so the
            // preview framing matches the non-EIS path. GL-level crop here would
            // compound with the TextureView transform (double-crop / over-zoom).
            mEisGlProcessor.addOutputSurface(surface, false);
            mEisInputSurface = mEisGlProcessor.getInputSurface();

            camera.setPreviewDisplay(mEisInputSurface);
        } else {
            if (mEisGlProcessor != null) {
                mEisGlProcessor.release();
                mEisGlProcessor = null;
                mEisInputSurface = null;
                mEisManager.stop();
            }
            camera.setPreviewDisplay(surface);
        }
        
        camera.startPreview();
        mCamera = camera;

        // A camera is live now — tear down the "no camera" reminder (and cancel
        // any pending presence check) so it auto-dismisses without the user
        // having to tap confirm.
        dismissNoCameraReminder();

        // UVCCamera lazily populates the PU control min/max limits — the
        // setBrightness/setContrast/setSharpness/setGain setters all guard on
        // `range = max - min > 0` and silently no-op until those limits are
        // loaded. open()/setPreviewSize()/startPreview() do NOT load them; the
        // only entry points that do are updateCameraParams(),
        // checkSupportFlag(), and getBrightness(int). Without this call the
        // exposure track's setBrightness drops on the floor — preview never
        // brightens or dims, and the photo (taken from the preview bitmap)
        // captures the unchanged frame. The call is idempotent (gated on
        // mControlSupports/mProcSupports being 0).
        try {
            camera.updateCameraParams();
        } catch (Throwable ignored) {
        }

        applyBrightness(mBrightness);

        if (mCurrentMode == CameraMode.SCAN) {
            startQrScanning();
        }

        // Align preview to the mode's selected aspect — VIDEO honours the
        // quality button, PHOTO honours the 4:3/1:1/16:9 selector. onConnect
        // always opens at native max so on resume from background these may
        // need adjusting. When a swap actually fires we defer the Toast so it
        // reflects the final size; otherwise (no resize needed, or SCAN mode)
        // show it now.
        if (mCurrentMode == CameraMode.VIDEO && previewNeedsVideoQualityResize()) {
            updateCameraResolution();
        } else if (mCurrentMode == CameraMode.PHOTO && previewNeedsPhotoAspectResize()) {
            applyPhotoAspectRatio();
        } else {
            showPreviewSizeToast();
        }
    }

    private boolean previewNeedsVideoQualityResize() {
        if (mCamera == null) return false;
        int targetWidth, targetHeight;
        switch (mCurrentVideoQuality) {
            case FHD: targetWidth = 1920; targetHeight = 1080; break;
            case QHD: targetWidth = 2560; targetHeight = 1440; break;
            case HD:
            default: targetWidth = 1280; targetHeight = 720; break;
        }
        Size bestSize = getBestSupportedSize(targetWidth, targetHeight);
        if (bestSize == null) return false;
        return mSurfaceView.getSourceWidth() != bestSize.width
                || mSurfaceView.getSourceHeight() != bestSize.height
                || mSurfaceView.getTargetWidth() != targetWidth
                || mSurfaceView.getTargetHeight() != targetHeight;
    }

    private void showPreviewSizeToast() {
        // Swallow exactly one call when an EIS toggle armed the suppression —
        // the toggle already showed its own toast and this rebuild's quality
        // toast would otherwise overlap it.
        if (mSuppressPreviewSizeToastOnce) {
            mSuppressPreviewSizeToastOnce = false;
            return;
        }
        // Quality feedback is mode-specific and deliberately coarse — we no
        // longer surface raw WxH numbers:
        //   PHOTO — the aspect ratio (1:1 / 4:3 / 16:9), which is the only
        //           knob the photo selector exposes.
        //   VIDEO — the quality tier label (720p / 1080p / 1440p) taken
        //           straight from mCurrentVideoQuality rather than the actual
        //           frame size: on a 2592×1944-max camera QHD crops down from
        //           2592×1944 to 2560×1440, and the tier name is what the user
        //           picked, so it's the honest label.
        //   SCAN  — nothing; resolution is irrelevant in scan mode.
        String message;
        if (mCurrentMode == CameraMode.PHOTO) {
            // Prefer the aspect target; fall back to source if target is 0
            // (a transient pre-aspect-set state).
            int w = mSurfaceView.getTargetWidth();
            int h = mSurfaceView.getTargetHeight();
            if (w <= 0 || h <= 0) {
                w = mSurfaceView.getSourceWidth();
                h = mSurfaceView.getSourceHeight();
            }
            message = (w > 0 && h > 0) ? aspectRatioLabel(w, h) : "";
        } else if (mCurrentMode == CameraMode.VIDEO) {
            message = getString(videoQualityLabelRes());
        } else {
            message = "";
        }
        mForceShowAspectRatioOnce = false;
        if (message.isEmpty()) return;
        showCustomToast(message);
    }

    private int videoQualityLabelRes() {
        switch (mCurrentVideoQuality) {
            case FHD: return R.string.video_quality_fhd;
            case QHD: return R.string.video_quality_qhd;
            case HD:
            default: return R.string.video_quality_hd;
        }
    }

    private String aspectRatioLabel(int w, int h) {
        int a = w, b = h;
        while (b != 0) { int t = b; b = a % b; a = t; }
        if (a == 0) return "";
        return (w / a) + ":" + (h / a);
    }

    private void setCameraMode(CameraMode mode) {
        CameraMode oldMode = mCurrentMode;
        // Any explicit mode change (user tapped the sidebar, or a programmatic
        // setCameraMode) leaves the static-image scan sub-state. The shared
        // image goes away; live camera takes back the viewport.
        if (mIsImageScanMode) {
            exitImageScanMode();
        }
        mCurrentMode = mode;

        // Stop current special behaviors
        if (oldMode == CameraMode.SCAN && mCurrentMode != CameraMode.SCAN) {
            stopQrScanning();
            // Immediate hide when switching away from SCAN
            mUiHandler.removeCallbacks(mHideFocusRunnable);
            mFocusContainer.setVisibility(View.INVISIBLE);
            mExposureTrack.setVisibility(View.INVISIBLE);
            mExposureSun.setVisibility(View.GONE);
        }

        mModeMenuSidebar.setVisibility(View.GONE);

        // Entering SCAN: restore camera's native max so the barcode
        // decoder gets the most detail. Also cancel any photo cropping.
        if (mCurrentMode == CameraMode.SCAN && oldMode != CameraMode.SCAN) {
            mPhotoAspectRatio = PhotoAspectRatio.NATIVE;
            if (mNativeMaxPreviewWidth > 0 && mNativeMaxPreviewHeight > 0) {
                updateCameraResolution(mNativeMaxPreviewWidth, mNativeMaxPreviewHeight);
            }
            // Clear any focus HUD left over from a PHOTO/VIDEO tap. The manual
            // focus square + exposure track + sun may still be on screen with a
            // pending auto-hide that hideFocusUi() will refuse to run in SCAN.
            // centreFocusIndicator() below only re-centres mFocusContainer when
            // it's already visible — it never touches the track or sun — so
            // without this reset the exposure sun stays orphaned at its old
            // PHOTO/VIDEO position. Wipe the HUD now so SCAN entry starts clean
            // and the centred default focus frame is drawn from scratch.
            mUiHandler.removeCallbacks(mHideFocusRunnable);
            mFocusContainer.setVisibility(View.INVISIBLE);
            mExposureTrack.setVisibility(View.INVISIBLE);
            mExposureSun.setVisibility(View.GONE);
        }

        // Centre-follow is a SCAN-only behaviour. Clear it before PHOTO/VIDEO
        // so a stale tap doesn't get re-centred by the layout listener.
        if (mCurrentMode != CameraMode.SCAN) {
            mFocusFollowsCentre = false;
        }

        switch (mCurrentMode) {
            case PHOTO:
                mBtnQrToggle.setImageResource(R.drawable.ic_photo_camera);
                // Restore the user's last-chosen photo aspect. Covers entering
                // PHOTO from VIDEO (where preview was at video target) and
                // from SCAN (where preview was native max) — and is a guard-
                // protected no-op when the surface already matches.
                applyPhotoAspectRatio();
                break;
            case VIDEO:
                mBtnQrToggle.setImageResource(R.drawable.ic_videocam);
                // Align the preview to whatever quality the HD/FHD/QHD button is
                // currently showing. Without this the preview keeps the camera's
                // native max from onConnect (e.g. 1920×1080), even though the
                // recording will be cut at the selected quality — so what the
                // user previews and what gets recorded would disagree until the
                // first manual quality cycle. updateCameraResolution short-circuits
                // when the physical size already matches.
                if (oldMode != CameraMode.VIDEO) {
                    updateCameraResolution();
                }
                break;
            case SCAN:
                mBtnQrToggle.setImageResource(R.drawable.ic_qr_code_scanner);
                // Immediately start scanning in SCAN mode
                mQrScanEnabled = true;
                startQrScanning();

                // Show default focus UI at the centre of mFocusUiRoot. Tricky
                // when we came from VIDEO mode: the revert-to-native-max
                // resolution change kicked off above runs on a worker thread
                // and replaces the TextureView asynchronously, so a plain
                // post() here would read the still-VIDEO-shaped dimensions and
                // freeze the focus square off-centre. Defer until the change
                // finishes — onResolutionChangeFinished re-posts so layout has
                // a frame to settle.
                mFocusFollowsCentre = true;
                Runnable scanFocusCentre = () -> {
                    if (mCurrentMode != CameraMode.SCAN) return;
                    centreFocusIndicator();
                };
                if (mIsChangingResolution) {
                    mPendingPostResolutionChange = scanFocusCentre;
                } else {
                    mFocusUiRoot.post(scanFocusCentre);
                }
                break;
        }

        updateControlBar();
        updateButtonStates();
        updateCountdownDisplay();
        // Hide the framing grid when entering SCAN, restore it when leaving.
        applyGridOverlayVisibility();
    }

    // --- Mode-menu reorder ----------------------------------------------------

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    // Loads the user-customised order from prefs into mModeOrder. Falls back
    // to the default PHOTO/VIDEO/SCAN order if the stored string is missing,
    // unparseable, or doesn't contain exactly one of each mode (e.g. after
    // adding a new CameraMode in a future version).
    private void loadModeOrder() {
        String stored = getPrefs().getString(PREF_MODE_ORDER, null);
        if (stored == null || stored.isEmpty()) return;
        List<CameraMode> parsed = new ArrayList<>(3);
        for (String name : stored.split(",")) {
            try {
                CameraMode m = CameraMode.valueOf(name);
                if (!parsed.contains(m)) parsed.add(m);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (parsed.size() != CameraMode.values().length) return;
        mModeOrder.clear();
        mModeOrder.addAll(parsed);
    }

    private void saveModeOrder() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mModeOrder.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(mModeOrder.get(i).name());
        }
        // commit() rather than apply(): drag/drop produces a single small
        // write, and we'd rather block this UI frame for ~1ms than risk the
        // pending async write being killed when the user backgrounds the app
        // immediately after reordering. The user-visible symptom that drove
        // this choice was "order resets after switching away and back".
        getPrefs().edit().putString(PREF_MODE_ORDER, sb.toString()).commit();
    }

    // Rearranges the three FrameLayout children in mModeMenuSidebar to match
    // mModeOrder. Dividers are drawn by LinearLayout.showDividers="middle"
    // (not Views), so reordering only touches the three mode buttons. Called
    // once during onCreate after loadModeOrder, and again after each drop.
    private void applyModeOrder() {
        for (int i = 0; i < mModeOrder.size(); i++) {
            View target = viewForMode(mModeOrder.get(i));
            int current = mModeMenuSidebar.indexOfChild(target);
            if (current == i || current < 0) continue;
            mModeMenuSidebar.removeView(target);
            mModeMenuSidebar.addView(target, i);
        }
    }

    private View viewForMode(CameraMode mode) {
        switch (mode) {
            case PHOTO: return mBtnModePhoto;
            case VIDEO: return mBtnModeVideo;
            case SCAN:  return mBtnModeScan;
            default: throw new IllegalArgumentException(mode.name());
        }
    }

    private CameraMode modeForView(View view) {
        if (view == mBtnModePhoto) return CameraMode.PHOTO;
        if (view == mBtnModeVideo) return CameraMode.VIDEO;
        if (view == mBtnModeScan)  return CameraMode.SCAN;
        return null;
    }

    // Single OnTouchListener that handles both the normal tap (→ setCameraMode)
    // and the long-press-then-drag reorder. We can't use OnClick + OnLongClick
    // here: OnLongClick fires once and returns, but we want continuous drag
    // tracking *after* the long-press fires. The pattern mirrors the QR-overlay
    // drag handler (search for createQrTextView): post a "becomes draggable"
    // runnable on ACTION_DOWN, cancel it if the finger moves > slop or lifts
    // before the timeout, otherwise enter drag mode and translate the view
    // until ACTION_UP.
    //
    // Pressed visual: ACTION_DOWN paints the item with the same black
    // background other toolbar buttons use for their flash-selected state
    // (btn_toolbar_selected_bg). It stays black while waiting for the
    // long-press to fire; the long-press runnable reverts to the normal
    // white background and then lifts the item for drag — matching the
    // requested "press = black, long-press = back to white + draggable".
    private void installModeReorderTouchListener(View item, CameraMode mode) {
        item.setOnTouchListener(new View.OnTouchListener() {
            private boolean wasReordering = false;
            private boolean dragArmed = false;
            private float downX, downY;

            private final Runnable enterDragRunnable = () -> {
                if (mIsRecording) return;
                // Restore the default item background before lifting — once
                // the long-press fires, the visual feedback becomes the white
                // bordered "lifted square" (beginModeReorderDrag), NOT the
                // black pressed background.
                setModeItemPressedBackground(item, false);
                wasReordering = true;
                beginModeReorderDrag(item);
            };

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        mReorderDragStartRawY = downY;
                        wasReordering = false;
                        dragArmed = true;
                        setModeItemPressedBackground(v, true);
                        mUiHandler.postDelayed(enterDragRunnable, 400);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        if (mIsReorderingModes && mReorderDraggingView == v) {
                            // Free-follow the finger, clamped so the lifted
                            // square never escapes the white menu box.
                            float dy = event.getRawY() - mReorderDragStartRawY;
                            int top = v.getTop();
                            float minTy = -top;
                            float maxTy = (mModeMenuSidebar.getHeight() - mReorderItemHeight) - top;
                            if (dy < minTy) dy = minTy;
                            if (dy > maxTy) dy = maxTy;
                            v.setTranslationY(dy);
                            updateModeReorderTarget(event.getRawY());
                            return true;
                        }
                        // Cancel the pending long-press if the finger drifts
                        // before it fires — same touch-slop threshold the QR
                        // overlay drag uses for consistency.
                        if (dragArmed) {
                            float adx = Math.abs(event.getRawX() - downX);
                            float ady = Math.abs(event.getRawY() - downY);
                            if (adx > 12 || ady > 12) {
                                mUiHandler.removeCallbacks(enterDragRunnable);
                                dragArmed = false;
                                setModeItemPressedBackground(v, false);
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mUiHandler.removeCallbacks(enterDragRunnable);
                        dragArmed = false;
                        if (mIsReorderingModes && mReorderDraggingView == v) {
                            finishReorderDrag(v);
                            // Suppress the click that would otherwise switch
                            // modes when the user simply lifts off after a drag.
                            return true;
                        }
                        setModeItemPressedBackground(v, false);
                        if (!wasReordering && event.getActionMasked() == MotionEvent.ACTION_UP) {
                            setCameraMode(mode);
                        }
                        return true;
                }
                return false;
            }
        });
    }

    // Apply / remove the black "selected" backdrop on a mode-menu FrameLayout
    // AND tint its inner ImageView white, matching the black-bg-with-white-icon
    // feedback the bottom toolbar buttons use (see setButtonSelected). The
    // icon lives one layer deeper here because the touch target is the
    // wrapping FrameLayout — pull it out via getChildAt rather than threading
    // a dedicated ImageView field through every call site.
    private void setModeItemPressedBackground(View item, boolean pressed) {
        ImageView icon = null;
        if (item instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) item;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View c = vg.getChildAt(i);
                if (c instanceof ImageView) {
                    icon = (ImageView) c;
                    break;
                }
            }
        }
        if (pressed) {
            item.setBackgroundResource(R.drawable.btn_toolbar_selected_bg);
            if (icon != null) icon.setColorFilter(0xFFFFFFFF);
        } else {
            // Plain transparent idle state. The menu items deliberately carry
            // no ripple background: the black flash above is the only pressed
            // feedback — a theme ripple underneath it played a second gray
            // "shadow" animation on every more-menu tap. (The mode-menu items
            // never showed theirs anyway; their OnTouchListener consumes
            // ACTION_DOWN, so the pressed state that drives a ripple never
            // arms.)
            item.setBackground(null);
            if (icon != null) icon.setColorFilter(0xFF000000);
        }
    }

    // Long-press fired: lift the item out as a free-floating white bordered
    // square. We deliberately keep its child index — it moves by translationY
    // alone (see the mIsReorderingModes field comment for why reordering the
    // children live breaks the gesture), so the layout is frozen for the whole
    // drag and we can snapshot the slot pitch / item height up front. The black
    // divider lines are swapped for a transparent same-size drawable so the
    // travelling square is never sliced by one.
    private void beginModeReorderDrag(View item) {
        mIsReorderingModes = true;
        mReorderDraggingView = item;
        mReorderFromIndex = mModeMenuSidebar.indexOfChild(item);
        mReorderToIndex = mReorderFromIndex;
        mReorderItemHeight = item.getHeight();
        int count = mModeMenuSidebar.getChildCount();
        // Slot pitch = item height + divider. Children are uniform and frozen
        // for the gesture, so two adjacent layout tops give it directly.
        mReorderSlotStep = count >= 2
                ? mModeMenuSidebar.getChildAt(1).getTop() - mModeMenuSidebar.getChildAt(0).getTop()
                : mReorderItemHeight;
        mModeMenuSidebar.setDividerDrawable(
                ContextCompat.getDrawable(this, R.drawable.mode_menu_divider_clear));
        applyModeItemDraggingAppearance(item, true);
    }

    // The "lifted" look while dragging: the same white box + black stroke the
    // menu itself uses, so the moving item reads as one complete framed square
    // rather than a transparent cell that the divider lines slice through. The
    // elevation isn't for a shadow (the sidebar/parent clip it to the 64dp
    // column) — it's to force the dragged square to draw ABOVE the siblings it
    // overlaps as it travels. Reverting restores the ripple background the
    // layout declared; the inner icon stays black throughout.
    private void applyModeItemDraggingAppearance(View item, boolean dragging) {
        if (dragging) {
            item.setBackgroundResource(R.drawable.mode_menu_bg);
            item.setElevation(10f * getResources().getDisplayMetrics().density);
        } else {
            // Back to the transparent idle state — same rationale as
            // setModeItemPressedBackground: menu items carry no ripple.
            item.setBackground(null);
            item.setElevation(0f);
        }
    }

    // Called on every ACTION_MOVE while a mode item is being dragged. Maps the
    // finger to a drop-target slot (equal-slot floor mapping — robust, and it
    // supports jumping across several slots in a single move), then slides every
    // *other* item to open a gap at that target: items between the dragged
    // item's origin and the target shift one slot toward the vacated origin.
    // The dragged view's own index is never touched here — it's floating above
    // on its elevation, glued to the finger by the caller's translationY.
    private void updateModeReorderTarget(float fingerRawY) {
        int count = mModeMenuSidebar.getChildCount();
        if (count <= 1 || mReorderSlotStep <= 0) return;

        int[] loc = new int[2];
        mModeMenuSidebar.getLocationOnScreen(loc);
        float fingerInParent = fingerRawY - loc[1];
        int top0 = mModeMenuSidebar.getChildAt(0).getTop();

        int target = (int) Math.floor((fingerInParent - top0) / (float) mReorderSlotStep);
        if (target < 0) target = 0;
        if (target > count - 1) target = count - 1;
        if (target == mReorderToIndex) return;
        mReorderToIndex = target;

        for (int i = 0; i < count; i++) {
            View child = mModeMenuSidebar.getChildAt(i);
            if (child == mReorderDraggingView) continue;
            float ty = 0f;
            if (mReorderFromIndex < i && i <= target) {
                ty = -mReorderSlotStep;       // dragged is moving down past this item
            } else if (target <= i && i < mReorderFromIndex) {
                ty = mReorderSlotStep;        // dragged is moving up past this item
            }
            child.animate().translationY(ty).setDuration(120).start();
        }
    }

    // Drop: settle the floating square into the open gap (its resting position
    // is exactly target-from slots away from where it started), then bake the
    // new order into the child list. mIsReorderingModes is cleared up-front so
    // the re-entrant ACTION_CANCEL that removeView() can fire inside
    // bakeModeReorder is a no-op.
    private void finishReorderDrag(View v) {
        mIsReorderingModes = false;
        mReorderDraggingView = null;
        final int target = mReorderToIndex;
        float resting = (target - mReorderFromIndex) * (float) mReorderSlotStep;
        v.animate()
                .translationY(resting)
                .setDuration(140)
                .withEndAction(() -> bakeModeReorder(v, target))
                .start();
    }

    // Commit the reorder exactly once. Every view (the dragged square and the
    // shifted siblings) is already sitting at its final on-screen position via
    // translationY, so we move the dragged child to its new index and then zero
    // all translations + restore the real dividers from inside a one-shot
    // pre-draw — i.e. in the same frame the new layout takes effect — so nothing
    // flashes back to a stale position in between.
    private void bakeModeReorder(View dragged, int targetIndex) {
        mModeMenuSidebar.removeView(dragged);
        mModeMenuSidebar.addView(dragged, targetIndex);
        rebuildModeOrderFromChildren();
        mModeMenuSidebar.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        mModeMenuSidebar.getViewTreeObserver().removeOnPreDrawListener(this);
                        for (int i = 0; i < mModeMenuSidebar.getChildCount(); i++) {
                            mModeMenuSidebar.getChildAt(i).setTranslationY(0f);
                        }
                        applyModeItemDraggingAppearance(dragged, false);
                        mModeMenuSidebar.setDividerDrawable(ContextCompat.getDrawable(
                                MainActivity.this, R.drawable.mode_menu_divider));
                        return true;
                    }
                });
        saveModeOrder();
    }

    // Re-derive mModeOrder from the sidebar's child order after a drop. The
    // sidebar holds only the three mode FrameLayouts, so child index == order
    // index.
    private void rebuildModeOrderFromChildren() {
        mModeOrder.clear();
        for (int i = 0; i < mModeMenuSidebar.getChildCount(); i++) {
            CameraMode m = modeForView(mModeMenuSidebar.getChildAt(i));
            if (m != null) mModeOrder.add(m);
        }
    }

    private void updateButtonStates() {
        setButtonSelected(mContainerQr, mBtnQrToggle, false);
        setButtonSelected(mContainerAspectRatio, mBtnAspectRatio, false);
        setButtonSelected(mContainerCountdown, mBtnCountdown, false);
        setButtonSelected(mContainerVideoQuality, mBtnVideoQuality, false);
        setButtonSelected(mContainerStabilization, mBtnStabilization, false);
        setButtonSelected(mContainerMore, mBtnMore, false);
        setButtonSelected(mContainerClose, findViewById(R.id.btn_close), false);
    }

    private void flashSelection(View container, ImageView button) {
        setButtonSelected(container, button, true);
        mUiHandler.postDelayed(() -> {
            setButtonSelected(container, button, false);
        }, 200);
    }

    private void setButtonSelected(View container, ImageView button, boolean selected) {
        if (selected) {
            container.setBackgroundResource(R.drawable.btn_toolbar_selected_bg);
            button.setColorFilter(0xFFFFFFFF);
        } else {
            container.setBackground(null);
            button.setColorFilter(0xFF000000);
        }
    }

    private void updateControlBar() {
        // Main action button (container_qr) should always be visible now
        mContainerQr.setVisibility(VISIBLE);

        if (mCurrentMode == CameraMode.PHOTO) {
            mContainerAspectRatio.setVisibility(VISIBLE);
            mContainerCountdown.setVisibility(VISIBLE);
            mContainerVideoQuality.setVisibility(GONE);
            mContainerStabilization.setVisibility(GONE);
        } else if (mCurrentMode == CameraMode.VIDEO) {
            mContainerAspectRatio.setVisibility(GONE);
            mContainerCountdown.setVisibility(GONE);
            mContainerVideoQuality.setVisibility(VISIBLE);
            mContainerStabilization.setVisibility(VISIBLE);
        } else if (mCurrentMode == CameraMode.SCAN) {
            mContainerAspectRatio.setVisibility(GONE);
            mContainerCountdown.setVisibility(GONE);
            mContainerVideoQuality.setVisibility(GONE);
            mContainerStabilization.setVisibility(GONE);
        }
    }

    private void startQrScanning() {
        if (mCurrentMode != CameraMode.PHOTO && mCurrentMode != CameraMode.SCAN) return;
        mQrScanEnabled = true;
        optimizeCameraForQr(true);
        mUiHandler.removeCallbacks(mQrScanRunnable);
        mUiHandler.post(mQrScanRunnable);
    }

    private void stopQrScanning() {
        mQrScanEnabled = false;
        optimizeCameraForQr(false);
        mUiHandler.removeCallbacks(mQrScanRunnable);
        mQrOverlayContainer.removeAllViews();
        mActiveQrOverlays.clear();
        // Forget Auto Run cooldowns so the next scan session re-acts on these codes.
        mAutoRunLastFired.clear();
    }

    private void optimizeCameraForQr(boolean enable) {
        if (mCamera == null) return;
        // Brightness/contrast/sharpness overrides were removed deliberately:
        // ML Kit's QR decoder already runs its own adaptive thresholding, so
        // pre-applied contrast/sharpness boosts crush the grey-scale gradient
        // it relies on at module edges and the UVC sharpness control's
        // unsharp-mask ringing produces spurious edges that the finder-pattern
        // detector mis-locks onto. Brightness reset is also unhelpful — it
        // silently overrides the user's exposure-track choice on every SCAN
        // entry/exit. Autofocus is the one image-quality factor Google's ML
        // Kit docs actually call out, so that stays.
        if (enable) {
            try { mCamera.setAutoFocus(true); } catch (Throwable ignored) {}
        }
    }

    private void performQrScan() {
        boolean modeAllowsScan = mCurrentMode == CameraMode.PHOTO || mCurrentMode == CameraMode.SCAN;
        if (!modeAllowsScan || !mQrScanEnabled || mIsScanning) {
            if (modeAllowsScan && mQrScanEnabled && !mIsScanning && (mCamera != null || mIsImageScanMode)) {
                mUiHandler.postDelayed(mQrScanRunnable, 500);
            }
            return;
        }

        // Image-scan branch: bitmap source is the user-shared still, not the
        // camera preview. Skip the 720x720 SCAN crop (we want the full image
        // since QR could be anywhere) and run a single-shot decode. No
        // recycle on the static bitmap — we still need it for display, and
        // exitImageScanMode owns its lifecycle.
        if (mIsImageScanMode) {
            if (mScanImageBitmap == null || mScanImageBitmap.isRecycled()) return;
            mIsScanning = true;
            mImageScanAttempts++;
            InputImage staticImage = InputImage.fromBitmap(mScanImageBitmap, 0);
            mBarcodeScanner.process(staticImage)
                    .addOnSuccessListener(barcodes -> {
                        updateQrOverlays(barcodes);
                        mIsScanning = false;
                        if (!barcodes.isEmpty()) {
                            mImageScanFoundAny = true;
                        }
                        // Source bitmap is static. After the first successful
                        // detect we stop running — overlays are pinned by the
                        // mIsImageScanMode skip in updateQrOverlays. If we
                        // haven't found anything after a few tries the image
                        // simply has no QR; tell the user and stop polling.
                        if (mImageScanFoundAny || !mIsImageScanMode || !mQrScanEnabled) return;
                        if (mImageScanAttempts >= 3) {
                            showCustomToast(getString(R.string.no_qr_found));
                        } else {
                            mUiHandler.postDelayed(mQrScanRunnable, 600);
                        }
                    })
                    .addOnFailureListener(e -> {
                        mIsScanning = false;
                        if (mImageScanFoundAny || !mIsImageScanMode || !mQrScanEnabled) return;
                        if (mImageScanAttempts >= 3) {
                            showCustomToast(getString(R.string.no_qr_found));
                        } else {
                            mUiHandler.postDelayed(mQrScanRunnable, 1000);
                        }
                    });
            return;
        }

        if (mCamera == null) {
            mUiHandler.postDelayed(mQrScanRunnable, 500);
            return;
        }

        Bitmap bitmap = mSurfaceView.getBitmap();
        if (bitmap == null) {
            mUiHandler.postDelayed(mQrScanRunnable, 500);
            return;
        }

        // SCAN mode: crop the bitmap to a fixed 720x720 px square at the
        // centre of the preview before handing it to ML Kit. A fixed pixel
        // ROI (rather than tracking mFocusContainer) keeps the decode window
        // density-independent — on xxhdpi a 140dp square is ~420px and would
        // shrink the working area below what ML Kit's finder-pattern detector
        // wants for codes that span most of the on-screen aim square; 720x720
        // sits comfortably above that floor while still trimming the typical
        // ~1080x1440 preview by roughly 2/3. If the preview is smaller than
        // 720 in either axis we clamp to the bitmap dimension on that axis.
        // PHOTO mode keeps the full frame — its focus square follows the
        // user's tap and shouldn't gate barcode detection.
        Bitmap scanBitmap = bitmap;
        int cropOffsetX = 0;
        int cropOffsetY = 0;
        if (mCurrentMode == CameraMode.SCAN) {
            int target = 720;
            int w = Math.min(target, bitmap.getWidth());
            int h = Math.min(target, bitmap.getHeight());
            int left = (bitmap.getWidth() - w) / 2;
            int top = (bitmap.getHeight() - h) / 2;
            if (w > 0 && h > 0 && (w < bitmap.getWidth() || h < bitmap.getHeight())) {
                try {
                    scanBitmap = Bitmap.createBitmap(bitmap, left, top, w, h);
                    cropOffsetX = left;
                    cropOffsetY = top;
                } catch (OutOfMemoryError | IllegalArgumentException e) {
                    // Fall back to full-frame scanning if the crop allocation
                    // fails for any reason.
                    scanBitmap = bitmap;
                }
            }
        }

        // createBitmap() copies pixels into a fresh allocation, so the original
        // capture can be released immediately. When no crop happened scanBitmap
        // *is* bitmap and we keep it for the callback to recycle.
        if (scanBitmap != bitmap) {
            bitmap.recycle();
        }
        final Bitmap scanFinal = scanBitmap;
        final int dx = cropOffsetX;
        final int dy = cropOffsetY;
        final boolean translateBounds = (dx != 0 || dy != 0);

        mIsScanning = true;
        InputImage image = InputImage.fromBitmap(scanFinal, 0);
        mBarcodeScanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    if (translateBounds) {
                        // ML Kit returns bounding boxes relative to the cropped
                        // bitmap; updateQrOverlays / updateViewPosition expect
                        // coordinates relative to the full preview. Each Barcode
                        // instance is only referenced from this single callback,
                        // so mutating the Rect in place is safe.
                        for (Barcode b : barcodes) {
                            android.graphics.Rect r = b.getBoundingBox();
                            if (r != null) r.offset(dx, dy);
                        }
                    }
                    updateQrOverlays(barcodes);
                    scanFinal.recycle();
                    mIsScanning = false;
                    if (mQrScanEnabled && (mCurrentMode == CameraMode.PHOTO || mCurrentMode == CameraMode.SCAN)) {
                        mUiHandler.postDelayed(mQrScanRunnable, 200);
                    }
                })
                .addOnFailureListener(e -> {
                    scanFinal.recycle();
                    mIsScanning = false;
                    if (mQrScanEnabled && (mCurrentMode == CameraMode.PHOTO || mCurrentMode == CameraMode.SCAN)) {
                        mUiHandler.postDelayed(mQrScanRunnable, 500);
                    }
                });
    }

    private void updateQrOverlays(List<Barcode> barcodes) {
        long now = System.currentTimeMillis();

        // Deduplicate barcodes in the current frame to prevent creating multiple boxes for the same content
        Map<String, Barcode> uniqueBarcodes = new LinkedHashMap<>();
        for (Barcode barcode : barcodes) {
            String rawValue = barcode.getRawValue();
            if (rawValue != null) {
                // If multiple exist, the first one's position is used
                if (!uniqueBarcodes.containsKey(rawValue)) {
                    uniqueBarcodes.put(rawValue, barcode);
                }
            }
        }

        // 1. Update or Add newly detected barcodes
        for (Barcode barcode : uniqueBarcodes.values()) {
            String rawValue = barcode.getRawValue();
            android.graphics.Rect bounds = barcode.getBoundingBox();
            if (bounds == null) continue;

            QrOverlayData data = mActiveQrOverlays.get(rawValue);
            if (data == null) {
                // New detection
                TextView tv = createQrTextView(rawValue);
                mQrOverlayContainer.addView(tv);
                data = new QrOverlayData(tv, bounds, now);
                mActiveQrOverlays.put(rawValue, data);
                // First appearance of this value in the current overlay set — the
                // natural "newly scanned" signal for Auto Run. A code that stays
                // in frame never re-enters this branch (the dedup map + the
                // mActiveQrOverlays guard keep it on the update path); the cooldown
                // inside maybeAutoRun covers the stale-removal/re-detect case and
                // the single-shot image-scan case (both funnel through here).
                maybeAutoRun(rawValue);
            } else {
                // Existing - update position and time
                data.lastBounds = bounds;
                data.lastDetectedTime = now;
            }
            if (!data.isDragged) {
                updateViewPosition(data.view, data.lastBounds);
            }
        }

        // 2. Remove stale overlays (older than 5 seconds). Skip this in image
        // scan mode: the source bitmap doesn't change, so re-detection won't
        // refresh lastDetectedTime — overlays should persist until the user
        // leaves SCAN-from-image. Drag-mode entries are always preserved.
        if (!mIsImageScanMode) {
            Iterator<Map.Entry<String, QrOverlayData>> it = mActiveQrOverlays.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, QrOverlayData> entry = it.next();
                QrOverlayData data = entry.getValue();
                if (now - data.lastDetectedTime > 5000 && !data.isDragged) {
                    mQrOverlayContainer.removeView(data.view);
                    it.remove();
                }
            }
        }
    }

    private TextView createQrTextView(String text) {
        TextView tv = new TextView(this);
        // Ensure LayoutParams are WRAP_CONTENT so it can shrink
        tv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        tv.setText(formatQrText(text));
        tv.setTextColor(0xFF000000);
        tv.setBackgroundResource(R.drawable.qr_result_bg);
        tv.setPadding(16, 8, 16, 8);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);

        // Set max width to roughly 25 characters to trigger wrapping
        float density = getResources().getDisplayMetrics().density;
        // 25 chars * 14sp * factor (roughly 260dp)
        tv.setMaxWidth((int) (260 * density));

        tv.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(text));
                startActivity(intent);
            } catch (Exception e) {
            }
        });

        tv.setOnTouchListener(new View.OnTouchListener() {
            private float lastX, lastY;
            private boolean isLongPressed = false;
            private boolean isProcessingDrop = false;
            private final Runnable copyRunnable = () -> {
                copyToClipboard(text);
            };
            private final Runnable dragRunnable = () -> {
                isLongPressed = true;
                QrOverlayData data = mActiveQrOverlays.get(text);
                if (data != null) {
                    data.isDragged = true;
                }
                tv.setAlpha(0.7f);
                tv.setScaleX(1.1f);
                tv.setScaleY(1.1f);
                showCustomToast(getString(R.string.drag_mode_enabled));
            };

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = event.getRawX();
                        lastY = event.getRawY();
                        isLongPressed = false;
                        isProcessingDrop = false;
                        mUiHandler.postDelayed(copyRunnable, 500);
                        mUiHandler.postDelayed(dragRunnable, 1000);

                        // Reset timer when user starts touching the overlay
                        QrOverlayData data = mActiveQrOverlays.get(text);
                        if (data != null) {
                            data.lastDetectedTime = System.currentTimeMillis();
                        }
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - lastX;
                        float dy = event.getRawY() - lastY;

                        if (!isLongPressed) {
                            if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                                mUiHandler.removeCallbacks(copyRunnable);
                                mUiHandler.removeCallbacks(dragRunnable);
                            }
                        } else {
                            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) v.getLayoutParams();
                            params.leftMargin += (int) dx;
                            params.topMargin += (int) dy;
                            v.setLayoutParams(params);
                            lastX = event.getRawX();
                            lastY = event.getRawY();
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mUiHandler.removeCallbacks(copyRunnable);
                        mUiHandler.removeCallbacks(dragRunnable);

                        if (isLongPressed && !isProcessingDrop) {
                            if (event.getAction() == MotionEvent.ACTION_UP && isInQrDropZone(v)) {
                                isProcessingDrop = true;
                                saveTextAsQrCode(text);
                                v.post(() -> {
                                    mQrOverlayContainer.removeView(v);
                                    mActiveQrOverlays.remove(text);
                                });
                            } else {
                                tv.setAlpha(1.0f);
                                tv.setScaleX(1.0f);
                                tv.setScaleY(1.0f);
                            }
                        } else if (!isLongPressed && event.getAction() == MotionEvent.ACTION_UP) {
                            v.performClick();
                        }
                        return true;
                }
                return false;
            }
        });

        return tv;
    }

    private void showCustomToast(String message) {
        if (mTvCustomToast == null) return;
        mTvRecordingTimer.setVisibility(GONE); // Hide top timer if toast is showing
        mTvCustomToast.setText(message);
        fitRotatedTextWidth(mTvCustomToast);
        mTvCustomToast.setVisibility(View.VISIBLE);
        mUiHandler.removeCallbacks(mHideCustomToastRunnable);
        mUiHandler.postDelayed(mHideCustomToastRunnable, 2000);
    }

    private void cycleCountdown() {
        String msg;
        if (mCountdownSeconds == 0) {
            mCountdownSeconds = 3;
            msg = getString(R.string.countdown_3s);
        } else if (mCountdownSeconds == 3) {
            mCountdownSeconds = 10;
            msg = getString(R.string.countdown_10s);
        } else {
            mCountdownSeconds = 0;
            msg = getString(R.string.countdown_off);
        }
        showCustomToast(msg);
    }

    private void updateCountdownDisplay() {
        if (mCurrentMode == CameraMode.PHOTO && mCountdownSeconds > 0 && !mIsCountdownRunning) {
            mTvRecordingTimer.setText(String.valueOf(mCountdownSeconds));
            fitRotatedTextWidth(mTvRecordingTimer);
            mTvRecordingTimer.setVisibility(VISIBLE);
        } else if (!mIsCountdownRunning) {
            mTvRecordingTimer.setVisibility(GONE);
        }
    }

    private void startPhotoCountdown() {
        if (mIsCountdownRunning) return;
        mIsCountdownRunning = true;
        mBtnQrToggle.setEnabled(false);
        mBtnCountdown.setEnabled(false);
        mBtnAspectRatio.setEnabled(false);
        mBtnModePhoto.setEnabled(false);
        mBtnModeVideo.setEnabled(false);
        mBtnModeScan.setEnabled(false);

        new CountDownTimer(mCountdownSeconds * 1000L, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int secondsRemaining = (int) Math.ceil(millisUntilFinished / 1000.0);
                mTvRecordingTimer.setText(String.valueOf(secondsRemaining));
                fitRotatedTextWidth(mTvRecordingTimer);
                mTvRecordingTimer.setVisibility(VISIBLE);
            }

            @Override
            public void onFinish() {
                mIsCountdownRunning = false;
                mBtnQrToggle.setEnabled(true);
                mBtnCountdown.setEnabled(true);
                mBtnAspectRatio.setEnabled(true);
                mBtnModePhoto.setEnabled(true);
                mBtnModeVideo.setEnabled(true);
                mBtnModeScan.setEnabled(true);
                updateCountdownDisplay();
                capturePhoto();
            }
        }.start();
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("QR Result", text);
        if (clipboard != null) {
            clipboard.setPrimaryClip(clip);
            showCustomToast(getString(R.string.copied_to_clipboard));
        }
    }

    private boolean isInQrDropZone(View view) {
        // Drop zone is top-left, slightly larger than last_picture (48dp + margin)
        float density = getResources().getDisplayMetrics().density;
        float zoneWidth = 80 * density;
        float zoneHeight = 80 * density;

        int[] location = new int[2];
        view.getLocationInWindow(location);
        int viewLeft = location[0];
        int viewTop = location[1];

        // Check if ANY part of the view overlaps with the (0,0, zoneWidth, zoneHeight) area in window coordinates
        return viewLeft < zoneWidth && viewTop < zoneHeight;
    }

    private void saveTextAsQrCode(String text) {
        Bitmap qrBitmap = generateQrBitmap(text);
        if (qrBitmap != null) {
            savePhotoAsync(qrBitmap);
            showCustomToast(getString(R.string.qr_saved_to_album));
        } else {
            showCustomToast(getString(R.string.qr_generate_failed));
        }
    }

    // Apply the user's Auto Run rules to a freshly-scanned code (called from the
    // new-detection branch of updateQrOverlays, for both live and image scan).
    // Runs on the UI thread — we're inside the ML Kit success callback on the main
    // looper, and saveTextAsQrCode delegates its disk write to savePhotoAsync.
    //
    // Spec: if both rules match the same code, save the QR first, then open the
    // link — so the save block runs before the open block below.
    private void maybeAutoRun(String rawValue) {
        if (rawValue == null || rawValue.isEmpty()) return;

        // Cooldown: ignore a value we already acted on within the window. This is
        // what keeps a lingering / re-entering code from re-saving or relaunching
        // the browser on every (re)detection.
        long now = System.currentTimeMillis();
        Long last = mAutoRunLastFired.get(rawValue);
        if (last != null && now - last < AUTO_RUN_COOLDOWN_MS) return;

        SharedPreferences prefs = getPrefs();
        boolean autoSave = prefs.getBoolean(PREF_AUTO_SAVE_ENABLED, false);
        boolean autoOpen = prefs.getBoolean(PREF_AUTO_OPEN_ENABLED, false);
        if (!autoSave && !autoOpen) return;

        boolean matchedSave = autoSave
                && matchesAnyKeyword(rawValue, splitLines(prefs.getString(PREF_AUTO_SAVE_KEYWORDS, "")));

        boolean matchedOpen = false;
        if (autoOpen && (rawValue.startsWith("http://") || rawValue.startsWith("https://"))) {
            try {
                String host = Uri.parse(rawValue).getHost();
                matchedOpen = host != null
                        && hostMatchesWhitelist(host, splitLines(prefs.getString(PREF_AUTO_OPEN_WHITELIST, "")));
            } catch (Exception ignored) {
                // Malformed URI — treat as no link match.
            }
        }

        if (!matchedSave && !matchedOpen) return;
        // Only stamp the cooldown once we actually act, so a non-matching code
        // isn't needlessly suppressed (it can't match later anyway, but this keeps
        // the map limited to codes we've reacted to).
        mAutoRunLastFired.put(rawValue, now);

        // Save first (per spec) ...
        if (matchedSave) {
            saveTextAsQrCode(rawValue);
        }
        // ... then open the link.
        if (matchedOpen) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(rawValue));
                startActivity(intent);
            } catch (Exception ignored) {
                // No app to handle the link, or activity-start blocked — ignore,
                // matching the manual tap-to-open handler in createQrTextView.
            }
        }
    }

    // Substring match, case-insensitive. An empty keyword list never matches, so a
    // rule toggled on with no keywords entered does nothing.
    private boolean matchesAnyKeyword(String text, List<String> keywords) {
        if (text == null || keywords.isEmpty()) return false;
        String haystack = text.toLowerCase(Locale.ROOT);
        for (String kw : keywords) {
            if (haystack.contains(kw.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    // A host matches the whitelist if it equals a listed domain or is a subdomain
    // of it (host endsWith "." + domain). Leading "www." / "." on a listed entry is
    // stripped so "www.example.com" and "example.com" behave the same. Empty list
    // never matches.
    private boolean hostMatchesWhitelist(String host, List<String> domains) {
        if (host == null || domains.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        for (String raw : domains) {
            String d = raw.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            while (d.startsWith(".")) d = d.substring(1);
            if (d.isEmpty()) continue;
            if (h.equals(d) || h.endsWith("." + d)) return true;
        }
        return false;
    }

    // Split a stored multi-line keyword/whitelist string into trimmed, non-blank
    // entries (one per line). Used for both Auto Run prefs.
    private List<String> splitLines(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String line : raw.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private Bitmap generateQrBitmap(String text) {
        try {
            int qrSize = 512;
            // MultiFormatWriter defaults to ISO-8859-1, which mangles CJK text into "?".
            // Force UTF-8 so Chinese (and other non-Latin) content round-trips correctly through ML Kit's decoder.
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix bitMatrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, qrSize, qrSize, hints);
            
            // Create a larger bitmap to fit text at the bottom
            int labelHeight = 80;
            int totalHeight = qrSize + labelHeight;
            Bitmap bitmap = Bitmap.createBitmap(qrSize, totalHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);

            // Draw QR Code
            Paint qrPaint = new Paint();
            for (int x = 0; x < qrSize; x++) {
                for (int y = 0; y < qrSize; y++) {
                    qrPaint.setColor(bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                    canvas.drawPoint(x, y, qrPaint);
                }
            }

            // Extract Keyword
            String keyword = extractKeyword(text);

            // Draw Text Label
            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(Color.BLACK);
            textPaint.setTextSize(40f);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setFakeBoldText(true);

            float xPos = qrSize / 2f;
            float yPos = qrSize + (labelHeight / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f);
            canvas.drawText(keyword, xPos, yPos, textPaint);

            return bitmap;
        } catch (WriterException e) {
            return null;
        }
    }

    private String extractKeyword(String text) {
        if (text.startsWith("http://") || text.startsWith("https://")) {
            try {
                Uri uri = Uri.parse(text);
                String host = uri.getHost();
                if (host != null) {
                    // Remove common prefixes
                    host = host.replaceFirst("^www\\.", "");
                    String[] parts = host.split("\\.");
                    if (parts.length >= 2) {
                        // Logic based on examples:
                        // abc.na.jo.com -> jo (second to last)
                        // jouio.tw -> jouio (first part of two)
                        
                        // If it's something like "jouio.tw", parts[0] is jouio.
                        // If it's "abc.na.jo.com", parts[parts.length-2] is jo.
                        String target = parts[parts.length - 2];
                        if (target.length() > 5) {
                            return target.substring(0, 5);
                        }
                        return target;
                    }
                    return host.length() > 5 ? host.substring(0, 5) : host;
                }
            } catch (Exception ignored) {}
        }
        
        // Plain text or fallback
        if (text.length() > 5) {
            return text.substring(0, 5);
        }
        return text;
    }

    private String formatQrText(String text) {
        if (text.startsWith("http://") || text.startsWith("https://")) {
            // Remove protocol and www.
            String display = text.replaceFirst("https?://(www\\.)?", "");
            // Remove trailing slash if present
            if (display.endsWith("/")) {
                display = display.substring(0, display.length() - 1);
            }
            // Truncate links only
            if (display.length() > 25) {
                return display.substring(0, 25) + "...";
            }
            return display;
        }
        // Return plain text as is (will wrap due to setMaxWidth)
        return text;
    }

    private void updateViewPosition(View view, android.graphics.Rect bounds) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (params == null) {
            params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }

        // 1. Calculate positions to translate from preview coordinates to container coordinates.
        // In image-scan mode the "preview" is mSharedImageView, and ML Kit returned bounds
        // in bitmap-pixel space — we have to apply the fitCenter scale + letterbox offset
        // computed in recomputeScanImageMapping so overlays land on top of the visible image.
        View anchor = mIsImageScanMode ? mSharedImageView : mSurfaceView;
        int[] previewLocation = new int[2];
        anchor.getLocationOnScreen(previewLocation);
        int[] containerLocation = new int[2];
        mQrOverlayContainer.getLocationOnScreen(containerLocation);

        // 2. Initial position relative to the preview
        int relativeLeft, relativeTop;
        if (mIsImageScanMode) {
            float scale = mScanImageScale;
            relativeLeft = mScanImageOffsetX + (int) (bounds.left * scale);
            relativeTop = mScanImageOffsetY + (int) (bounds.top * scale);
        } else {
            relativeLeft = bounds.centerX() - (bounds.width() / 2);
            relativeTop = bounds.centerY() - (bounds.height() / 2);
        }

        // 3. Measure the view to get its size (since it's WRAP_CONTENT)
        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int viewWidth = view.getMeasuredWidth();
        int viewHeight = view.getMeasuredHeight();

        // 4. Set initial rect in container coordinates
        int left = previewLocation[0] - containerLocation[0] + relativeLeft;
        int top = previewLocation[1] - containerLocation[1] + relativeTop;
        android.graphics.Rect currentRect = new android.graphics.Rect(left, top, left + viewWidth, top + viewHeight);

        // 5. Simple overlap prevention by shifting downwards
        boolean shifted;
        do {
            shifted = false;
            for (QrOverlayData other : mActiveQrOverlays.values()) {
                if (other.view == view) continue;

                // Get the other view's current rect in the container
                FrameLayout.LayoutParams otherParams = (FrameLayout.LayoutParams) other.view.getLayoutParams();
                if (otherParams == null) continue;

                android.graphics.Rect otherRect = new android.graphics.Rect(
                        otherParams.leftMargin,
                        otherParams.topMargin,
                        otherParams.leftMargin + other.view.getMeasuredWidth(),
                        otherParams.topMargin + other.view.getMeasuredHeight()
                );

                if (android.graphics.Rect.intersects(currentRect, otherRect)) {
                    // Overlap detected! Shift current view down below the other view
                    currentRect.offset(0, otherRect.bottom - currentRect.top + 8); // 8px gap
                    shifted = true;
                    break; // Re-check from the beginning after shifting
                }
            }
        } while (shifted);

        params.leftMargin = currentRect.left;
        params.topMargin = currentRect.top;
        view.setLayoutParams(params);
    }

    private static class QrOverlayData {
        final TextView view;
        android.graphics.Rect lastBounds;
        long lastDetectedTime;
        boolean isDragged = false;

        QrOverlayData(TextView view, android.graphics.Rect bounds, long time) {
            this.view = view;
            this.lastBounds = bounds;
            this.lastDetectedTime = time;
        }
    }


    // --- Apple-style focus / exposure UI -------------------------------------

    private boolean onPreviewTouch(View v, MotionEvent event) {
        if (mCamera == null || mCurrentMode == CameraMode.SCAN) {
            return false;
        }
        float absX = event.getX();
        float absY = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDragStartY = event.getRawY();
                mDragStartBrightness = mBrightness;
                mIsLongPressing = false;
                mFocusTriggered = false;

                // Check if user is clicking near the existing exposure track area
                // for long-press recovery (Requirement 3).
                float density = getResources().getDisplayMetrics().density;
                float touchSlop = 40 * density; // Relaxed hit area for recovery
                boolean nearTrackX = mExposureSun.getVisibility() == VISIBLE &&
                        Math.abs(absX - (mExposureTrack.getX() + mExposureTrack.getWidth() / 2f)) < touchSlop;
                boolean nearTrackY = absY >= (mTrackTopY - touchSlop) && absY <= (mTrackTopY + mTrackHeightPx + touchSlop);
                boolean nearTrack = nearTrackX && nearTrackY;

                if (nearTrack) {
                    mUiHandler.postDelayed(mLongPressRunnable, LONG_PRESS_TIMEOUT);
                } else {
                    // Normal focus: resets exposure and moves UI
                    mFocusTriggered = true;
                    showFocusUi(absX, absY, true);
                    try {
                        mCamera.setAutoFocus(true);
                    } catch (Throwable ignored) {
                    }
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (mIsLongPressing) {
                    updateBrightnessFromDrag(event.getRawY());
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean wasLongPressing = mIsLongPressing;
                mUiHandler.removeCallbacks(mLongPressRunnable);
                mIsLongPressing = false;

                if (wasLongPressing) {
                    scheduleHideFocusUi();
                } else if (!mFocusTriggered) {
                    // If it was a tap near the track, but not a long-press, 
                    // and we haven't triggered focus yet (because we were waiting for long-press),
                    // we should trigger a new focus there now.
                    float d = getResources().getDisplayMetrics().density;
                    float slop = 40 * d;
                    boolean nX = mExposureSun.getVisibility() == VISIBLE &&
                            Math.abs(absX - (mExposureTrack.getX() + mExposureTrack.getWidth() / 2f)) < slop;
                    boolean nY = absY >= (mTrackTopY - slop) && absY <= (mTrackTopY + mTrackHeightPx + slop);
                    
                    if (nX && nY && event.getActionMasked() == MotionEvent.ACTION_UP) {
                        showFocusUi(absX, absY, true);
                        try {
                            mCamera.setAutoFocus(true);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                return true;
        }
        return false;
    }

    // Centres the SCAN-mode default focus frame within mFocusUiRoot. First
    // call goes through showFocusUi (full setup + entry animation); subsequent
    // calls from the layout listener just snap setX/setY so the frame doesn't
    // re-animate every time mFocusUiRoot's bounds change.
    private void centreFocusIndicator() {
        int w = mFocusUiRoot.getWidth();
        int h = mFocusUiRoot.getHeight();
        if (w <= 0 || h <= 0) return;
        if (mFocusContainer.getVisibility() != VISIBLE) {
            showFocusUi(w / 2f, h / 2f, false);
            return;
        }
        // mFocusContainer is wrap_content around mFocusIndicator, whose size
        // in centre-follow mode is FOCUS_DEFAULT_SIZE_DP. Computing from the
        // dp constant avoids stale getWidth() values during a layout pass.
        float density = getResources().getDisplayMetrics().density;
        float focusSize = FOCUS_DEFAULT_SIZE_DP * density;
        mFocusContainer.setX(w / 2f - focusSize / 2f);
        mFocusContainer.setY(h / 2f - focusSize / 2f);
    }

    private void showFocusUi(float centerX, float centerY, boolean isManual) {
        float density = getResources().getDisplayMetrics().density;
        int focusSize = (int) ((isManual ? FOCUS_SELECTED_SIZE_DP : FOCUS_DEFAULT_SIZE_DP) * density);
        int sunSize = (int) (SUN_SIZE_DP * density);
        int trackHeight = (int) (TRACK_HEIGHT_DP * density);
        int gap = (int) (FOCUS_GAP_DP * density);

        // Update UI components based on manual/auto
        if (isManual) {
            mFocusIndicator.setBackgroundResource(R.drawable.ic_focus_selected_frame);
            // Requirement 4: Reset brightness on manual click
            mBrightness = 50;
            applyBrightness(mBrightness);
        } else {
            mFocusIndicator.setBackgroundResource(R.drawable.ic_focus_default);
        }

        ViewGroup.LayoutParams lp = mFocusIndicator.getLayoutParams();
        lp.width = focusSize;
        lp.height = focusSize;
        mFocusIndicator.setLayoutParams(lp);

        // All coordinates are now relative to mFocusUiRoot (viewfinder)
        float surfaceWidth = mFocusUiRoot.getWidth();

        // Place track + sun to the right of the focus square; flip left if no room.
        // Horizontal avoidance (Requirement 2): keep flipping logic
        boolean roomOnRight = centerX + focusSize / 2f + gap + sunSize <= surfaceWidth;
        float trackX, sunX;
        if (roomOnRight) {
            trackX = centerX + focusSize / 2f + gap + sunSize / 2f - 0.5f;
            sunX = centerX + focusSize / 2f + gap;
        } else {
            trackX = centerX - focusSize / 2f - gap - sunSize / 2f - 0.5f;
            sunX = centerX - focusSize / 2f - gap - sunSize;
        }

        // Requirement 1: Remove Y-clamping for submerging effect
        float trackY = centerY - trackHeight / 2f;
        mTrackTopY = trackY;
        mTrackHeightPx = trackHeight;

        float sunCenterY = sunCenterFromBrightness(mBrightness);
        float sunY = sunCenterY - sunSize / 2f;

        mFocusContainer.setVisibility(VISIBLE);
        mExposureTrack.setVisibility(INVISIBLE); // Requirement 4: Hide track by default
        mExposureSun.setVisibility(isManual ? VISIBLE : GONE); // Exposure sun only for manual

        mExposureTrack.setAlpha(1f);
        mExposureSun.setAlpha(1f);

        mFocusContainer.setX(centerX - focusSize / 2f);
        mFocusContainer.setY(centerY - focusSize / 2f);
        mExposureTrack.setX(trackX);
        mExposureTrack.setY(trackY);
        mExposureSun.setX(sunX);
        mExposureSun.setY(sunY);

        mFocusContainer.setAlpha(0f);
        mFocusContainer.setScaleX(1.5f);
        mFocusContainer.setScaleY(1.5f);
        mFocusContainer.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(180)
                .start();

        scheduleHideFocusUi();
    }

    private float sunCenterFromBrightness(int brightness) {
        // brightness 100 = top of track, 0 = bottom.
        return mTrackTopY + (1f - brightness / 100f) * mTrackHeightPx;
    }

    private void scheduleHideFocusUi() {
        mUiHandler.removeCallbacks(mHideFocusRunnable);
        mUiHandler.postDelayed(mHideFocusRunnable, FOCUS_AUTO_HIDE_MS);
    }

    private void hideFocusUi() {
        mUiHandler.removeCallbacks(mHideFocusRunnable);
        if (mCurrentMode == CameraMode.SCAN) {
            // Keep focus UI visible in SCAN mode
            return;
        }
        fadeOut(mFocusContainer);
        fadeOut(mExposureTrack);
        fadeOut(mExposureSun);
    }

    private void fadeOut(View view) {
        if (view.getVisibility() != VISIBLE) {
            return;
        }
        view.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction(() -> view.setVisibility(INVISIBLE))
                .start();
    }

    private boolean onSunTouch(View v, MotionEvent event) {
        if (mCamera == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDragStartY = event.getRawY();
                mDragStartBrightness = mBrightness;
                mUiHandler.removeCallbacks(mHideFocusRunnable);
                mIsLongPressing = false;
                mUiHandler.postDelayed(mLongPressRunnable, LONG_PRESS_TIMEOUT);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (mTrackHeightPx <= 0) {
                    return true;
                }
                if (mIsLongPressing) {
                    updateBrightnessFromDrag(event.getRawY());
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mUiHandler.removeCallbacks(mLongPressRunnable);
                if (mIsLongPressing) {
                    mIsLongPressing = false;
                    scheduleHideFocusUi();
                }
                return true;
        }
        return false;
    }

    private void updateBrightnessFromDrag(float rawY) {
        if (mTrackHeightPx <= 0) return;
        float dy = mDragStartY - rawY;
        int delta = Math.round(dy / mTrackHeightPx * 100f);
        int newBrightness = Math.max(0, Math.min(100, mDragStartBrightness + delta));
        if (newBrightness != mBrightness) {
            mBrightness = newBrightness;
            applyBrightness(mBrightness);
            int sunSize = mExposureSun.getHeight();
            mExposureSun.setY(sunCenterFromBrightness(mBrightness) - sunSize / 2f);
        }
    }

    private void cycleVideoQuality() {
        if (mIsChangingResolution || mIsRecording || mCamera == null) return;
        switch (mCurrentVideoQuality) {
            case HD: mCurrentVideoQuality = VideoQuality.FHD; break;
            case FHD: mCurrentVideoQuality = VideoQuality.QHD; break;
            case QHD: mCurrentVideoQuality = VideoQuality.HD; break;
        }
        updateVideoQualityIcon();
        updateCameraResolution();
    }

    private void cyclePhotoAspectRatio() {
        switch (mPhotoAspectRatio) {
            case NATIVE: mPhotoAspectRatio = PhotoAspectRatio.ONE_ONE; break;
            case ONE_ONE: mPhotoAspectRatio = PhotoAspectRatio.SIXTEEN_NINE; break;
            case SIXTEEN_NINE: mPhotoAspectRatio = PhotoAspectRatio.NATIVE; break;
        }
        applyPhotoAspectRatio();
    }

    // Computes [targetW, targetH] for the current PhotoAspectRatio relative to
    // the camera's native max. Returns null when the camera hasn't connected
    // yet. The crops are deliberately "largest fitting" — for a 4:3 2592×1944
    // sensor that means 1:1 → 1944×1944 (full sensor height, centre-cropped
    // horizontally) and 16:9 → 2592×1458 (full sensor width, centre-cropped
    // vertically). getBestSupportedSize for these targets falls back to native
    // max in step 2/3, so the camera keeps producing at full resolution and
    // only the view's aspect target changes.
    private int[] computePhotoAspectTarget() {
        if (mNativeMaxPreviewWidth <= 0 || mNativeMaxPreviewHeight <= 0) return null;
        int w, h;
        switch (mPhotoAspectRatio) {
            case ONE_ONE: {
                int side = Math.min(mNativeMaxPreviewWidth, mNativeMaxPreviewHeight);
                w = side; h = side;
                break;
            }
            case SIXTEEN_NINE: {
                if ((long) mNativeMaxPreviewWidth * 9 >= (long) mNativeMaxPreviewHeight * 16) {
                    // Sensor is at least as wide as 16:9 — fill height, crop width.
                    h = mNativeMaxPreviewHeight;
                    w = h * 16 / 9;
                } else {
                    // Sensor is taller than 16:9 — fill width, crop height.
                    w = mNativeMaxPreviewWidth;
                    h = w * 9 / 16;
                }
                break;
            }
            case NATIVE:
            default:
                w = mNativeMaxPreviewWidth;
                h = mNativeMaxPreviewHeight;
                break;
        }
        return new int[]{w, h};
    }

    private void applyPhotoAspectRatio() {
        int[] target = computePhotoAspectTarget();
        if (target == null) return;
        updateCameraResolution(target[0], target[1]);
        if (!mIsChangingResolution && mForceShowAspectRatioOnce) {
            showPreviewSizeToast();
        }
    }

    private boolean previewNeedsPhotoAspectResize() {
        if (mCamera == null) return false;
        int[] target = computePhotoAspectTarget();
        if (target == null) return false;
        Size bestSize = getBestSupportedSize(target[0], target[1]);
        if (bestSize == null) return false;
        return mSurfaceView.getSourceWidth() != bestSize.width
                || mSurfaceView.getSourceHeight() != bestSize.height
                || mSurfaceView.getTargetWidth() != target[0]
                || mSurfaceView.getTargetHeight() != target[1];
    }

    private void updateCameraResolution() {
        updateCameraResolution(false);
    }

    // forceRebuild=true skips the size-equality short-circuit, so the Surface
    // attach rendezvous runs again even when the camera is already at the
    // target resolution. Used by the EIS toggle: enabling / disabling EIS
    // changes which Surface the camera writes to (TextureView vs EIS input),
    // but doesn't change the camera's resolution — so we have to force a swap
    // to re-run tryStartPendingPreview where the EIS pipeline is wired up.
    private void updateCameraResolution(boolean forceRebuild) {
        int targetWidth, targetHeight;
        switch (mCurrentVideoQuality) {
            case FHD: targetWidth = 1920; targetHeight = 1080; break;
            case QHD: targetWidth = 2560; targetHeight = 1440; break;
            case HD:
            default: targetWidth = 1280; targetHeight = 720; break;
        }
        updateCameraResolution(targetWidth, targetHeight, forceRebuild);
    }

    private void updateCameraResolution(int targetWidth, int targetHeight) {
        updateCameraResolution(targetWidth, targetHeight, false);
    }

    private void updateCameraResolution(int targetWidth, int targetHeight, boolean forceRebuild) {
        if (mCamera == null || mIsChangingResolution || mIsRecording) return;
        if (targetWidth <= 0 || targetHeight <= 0) return;

        Size bestSize = getBestSupportedSize(targetWidth, targetHeight);
        if (bestSize == null) return;

        final int finalWidth = bestSize.width;
        final int finalHeight = bestSize.height;
        final int finalTargetWidth = targetWidth;
        final int finalTargetHeight = targetHeight;

        // Skip the (expensive, briefly-flickering) TextureView replacement only
        // when BOTH the physical source size and the aspect-ratio target match.
        // Checking source alone is not enough: when the camera's native max is
        // bigger than QHD but has a non-16:9 aspect (e.g. a 4032×3024 4:3
        // sensor), getBestSupportedSize falls back to native max for QHD as
        // well, so leaving QHD for PHOTO/SCAN keeps the same source — but the
        // view's aspect target is still 2560×1440 (16:9) and the preview stays
        // crop-locked to 16:9. Comparing target catches that case.
        if (!forceRebuild
                && mSurfaceView.getSourceWidth() == finalWidth
                && mSurfaceView.getSourceHeight() == finalHeight
                && mSurfaceView.getTargetWidth() == finalTargetWidth
                && mSurfaceView.getTargetHeight() == finalTargetHeight) {
            return;
        }

        mIsChangingResolution = true;
        mBtnVideoQuality.setEnabled(false);

        // Resolution switching is unreliable with any in-place Surface rebinding.
        // Tested and failed: single setPreviewDisplay rebind on the same camera;
        // destroy + reopen with up to 1s delay; double setPreviewDisplay bracketing
        // startPreview to dodge the native prepare_preview() timing window. In all
        // cases the camera keeps producing frames at the new resolution (recording
        // works — it goes through the separate native mCaptureWindow path) but the
        // preview surface stays black, because rebinding a new ANativeWindow to a
        // SurfaceTexture that already had a producer leaves the BufferQueue in a
        // state where consumed frames don't reach TextureView.
        //
        // The only Surface-attach path that's reliable here is the original
        // onConnect rendezvous — because it operates on a brand-new SurfaceTexture.
        // So we reproduce that condition: stopPreview + setPreviewSize on the same
        // UVCCamera (don't destroy — UVCCamera.destroy() cascades to
        // UsbControlBlock.close() which fires our own onDisconnect listener), park
        // the camera in mPendingCamera, then swap mSurfaceView for a fresh
        // AspectRatioSurfaceView instance. The new TextureView fires
        // onSurfaceTextureAvailable when attached, which drives tryStartPendingPreview
        // — exact same code path as a fresh USB connect.
        new Thread(() -> {
            synchronized (MainActivity.this) {
                UVCCamera camera = mCamera;
                if (camera == null) {
                    runOnUiThread(this::onResolutionChangeFinished);
                    return;
                }
                try { camera.stopPreview(); } catch (Throwable ignored) {}
                try {
                    camera.setPreviewSize(finalWidth, finalHeight, UVCCamera.FRAME_FORMAT_MJPEG);
                } catch (Throwable e) {
                    try {
                        camera.setPreviewSize(finalWidth, finalHeight, UVCCamera.DEFAULT_PREVIEW_MODE);
                    } catch (Throwable e1) {
                        runOnUiThread(this::onResolutionChangeFinished);
                        return;
                    }
                }
            }

            runOnUiThread(() -> {
                if (mCamera == null) {
                    onResolutionChangeFinished();
                    return;
                }
                mPendingCamera = mCamera;
                mCamera = null;
                replaceSurfaceView(finalWidth, finalHeight, finalTargetWidth, finalTargetHeight);
                onResolutionChangeFinished();
            });
        }).start();
    }

    // Replaces mSurfaceView with a fresh AspectRatioSurfaceView instance in the
    // same parent slot. Used by updateCameraResolution to force a brand-new
    // SurfaceTexture/BufferQueue — see the comment in updateCameraResolution for
    // why in-place Surface rebinding doesn't work. The new view fires
    // onSurfaceTextureAvailable when attached, which drives tryStartPendingPreview.
    private void replaceSurfaceView(int srcW, int srcH, int targetW, int targetH) {
        AspectRatioSurfaceView oldView = mSurfaceView;
        ViewGroup parent = (ViewGroup) oldView.getParent();
        if (parent == null) return;

        int index = parent.indexOfChild(oldView);
        ViewGroup.LayoutParams lp = oldView.getLayoutParams();
        int id = oldView.getId();

        AspectRatioSurfaceView newView = new AspectRatioSurfaceView(this);
        newView.setId(id);
        newView.setLayoutParams(lp);
        newView.setSurfaceTextureListener(mSurfaceTextureListener);
        newView.setOnTouchListener(this::onPreviewTouch);
        newView.setSourceSize(srcW, srcH);
        newView.setAspectRatio(targetW, targetH);

        parent.removeView(oldView);
        parent.addView(newView, index);
        mSurfaceView = newView;
    }

    private void onResolutionChangeFinished() {
        mIsChangingResolution = false;
        if (!mIsRecording) {
            mBtnVideoQuality.setEnabled(true);
        }
        Runnable pending = mPendingPostResolutionChange;
        mPendingPostResolutionChange = null;
        if (pending != null) {
            // Double-post: replaceSurfaceView's addView triggered a layout
            // request but the layout pass hasn't run yet — it happens after
            // this UI runnable returns. One post lets the layout pass run; the
            // second runs the callback once mFocusUiRoot reports final size.
            mUiHandler.post(() -> mUiHandler.post(pending));
        }
    }

    private Size getBestSupportedSize(int targetWidth, int targetHeight) {
        if (mCamera == null) return null;
        List<Size> sizes = mCamera.getSupportedSizeList();
        if (sizes == null || sizes.isEmpty()) return null;

        Size bestMatch = null;
        float targetAspectRatio = (float) targetWidth / targetHeight;

        // 1. Look for exact match
        for (Size size : sizes) {
            if (size.width == targetWidth && size.height == targetHeight) {
                return size;
            }
        }

        // 2. Look for the smallest size that is >= target in both dimensions
        //    and has a similar or larger aspect ratio (to allow center-crop)
        for (Size size : sizes) {
            if (size.width >= targetWidth && size.height >= targetHeight) {
                if (bestMatch == null || (size.width * size.height < bestMatch.width * bestMatch.height)) {
                    bestMatch = size;
                }
            }
        }

        // 3. Fallback to any size if no perfect "larger" container found
        if (bestMatch == null) {
            bestMatch = sizes.get(0);
        }

        return bestMatch;
    }

    private void updateVideoQualityIcon() {
        int resId;
        switch (mCurrentVideoQuality) {
            case HD: resId = R.drawable.ic_resolution_hd; break;
            case FHD: resId = R.drawable.ic_resolution_fhd; break;
            case QHD: resId = R.drawable.ic_resolution_qhd; break;
            default: resId = R.drawable.ic_video_quality; break;
        }
        mBtnVideoQuality.setImageResource(resId);
    }

    private void applyBrightness(int brightness) {
        if (mCamera == null) return;
        try {
            mCamera.setBrightness(brightness);
        } catch (Throwable ignored) {
        }
    }

    private boolean isUvcCamera(UsbDevice device) {
        if (device.getDeviceClass() == 239 && device.getDeviceSubclass() == 2) return true;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            if (device.getInterface(i).getInterfaceClass() == 14) return true; // USB_CLASS_VIDEO
        }
        return false;
    }

    // --- Shutter --------------------------------------------------------------

    private void startRecording() {
        if (mCamera == null || mIsRecording) return;
        mIsRecording = true;

        // UI state updates on main thread
        mContainerVideoQuality.setEnabled(false);

        // Snapshot the upright rotation now, on the UI thread (the worker thread
        // below configures MediaRecorder). 90/180/270/0° for the four device
        // orientations — the same value the preview and photo capture use — so
        // video is recorded with the orientation the user framed.
        final int orientationHint = mSurfaceView.getUprightRotationDegrees();

        new Thread(() -> {
            String fileName = String.format(Locale.US, "USBCam_%d.mp4", System.currentTimeMillis());
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/EinkCamera");
            }

            Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                mUiHandler.post(() -> {
                    mIsRecording = false;
                    mContainerVideoQuality.setEnabled(true);
                });
                return;
            }

            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "rw")) {
                if (pfd == null) {
                    mUiHandler.post(() -> {
                        mIsRecording = false;
                        mContainerVideoQuality.setEnabled(true);
                    });
                    return;
                }

                mMediaRecorder = new MediaRecorder();
                mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mMediaRecorder.setOutputFile(pfd.getFileDescriptor());
                mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);

                int targetWidth, targetHeight;
                switch (mCurrentVideoQuality) {
                    case FHD: targetWidth = 1920; targetHeight = 1080; break;
                    case QHD: targetWidth = 2560; targetHeight = 1440; break;
                    default: targetWidth = 1280; targetHeight = 720; break;
                }
                mMediaRecorder.setVideoSize(targetWidth, targetHeight);
                mMediaRecorder.setVideoEncodingBitRate(10000000);
                mMediaRecorder.setVideoFrameRate(30);
                mMediaRecorder.setOrientationHint(orientationHint);

                mMediaRecorder.prepare();

                mMediaRecorderSurface = mMediaRecorder.getSurface();
                if (mIsStabilizationEnabled && mEisGlProcessor != null) {
                    // Camera is already writing to mEisInputSurface via
                    // setPreviewDisplay (set up in tryStartPendingPreview).
                    // EisGlProcessor multiplexes that to every registered
                    // output surface, so we just add the recorder's surface
                    // and let EIS render the stabilized frames into it.
                    // Calling startCapture(mEisInputSurface) here would make
                    // the camera write twice to the same SurfaceTexture and
                    // halve the effective frame rate per consumer.
                    //
                    // cropToAspectRatio=true: the MediaRecorder surface buffer
                    // is set to the user's 16:9 quality tier (e.g. 2560×1440),
                    // but the camera may be running at a 4:3 sensor mode (e.g.
                    // 2592×1944) — EisGlProcessor center-crops the input texture
                    // to the surface's aspect so the recorded video preserves
                    // the correct proportions. The preview surface (added in
                    // tryStartPendingPreview with cropToAspectRatio=false) is
                    // handled by AspectRatioSurfaceView.updateTransform instead.
                    mEisGlProcessor.addOutputSurface(mMediaRecorderSurface, true);
                } else {
                    mCamera.startCapture(mMediaRecorderSurface);
                }
                mMediaRecorder.start();

                mUiHandler.post(() -> {
                    mRecordingStartTime = SystemClock.elapsedRealtime();
                    mTvRecordingTimer.setVisibility(VISIBLE);
                    mTvRecordingTimer.setText(R.string.default_timer_text);
                    fitRotatedTextWidth(mTvRecordingTimer);
                    mUiHandler.post(mUpdateTimerRunnable);
                });
            } catch (Exception e) {
                mUiHandler.post(() -> {
                    mIsRecording = false;
                    mContainerVideoQuality.setEnabled(true);
                    if (mMediaRecorder != null) {
                        try {
                            mMediaRecorder.release();
                        } catch (Exception ignored) {}
                        mMediaRecorder = null;
                    }
                });
            }
        }, "recording-start").start();
    }

    private void stopRecording() {
        if (!mIsRecording) return;
        mIsRecording = false;

        new Thread(() -> {
            // Detach the recorder Surface from the EIS output list BEFORE the
            // recorder is released. EisGlProcessor holds an EGLSurface bound
            // to this Surface; releasing the recorder invalidates the Surface
            // and any in-flight render to it would crash the GL thread.
            if (mEisGlProcessor != null && mMediaRecorderSurface != null) {
                mEisGlProcessor.removeOutputSurface(mMediaRecorderSurface);
                mMediaRecorderSurface = null;
            }

            try {
                if (mMediaRecorder != null) {
                    try {
                        mMediaRecorder.stop();
                    } catch (RuntimeException e) {
                        // Handle case where stop is called too soon after start
                    }
                    mMediaRecorder.release();
                    mMediaRecorder = null;
                }
            } catch (Exception ignored) {}

            if (mCamera != null) {
                try {
                    // Safe even when EIS was used (we never called startCapture
                    // in that branch — UVCCamera.stopCapture() is a no-op when
                    // mCaptureWindow is NULL).
                    mCamera.stopCapture();
                } catch (Exception ignored) {}
            }

            mUiHandler.post(() -> {
                mTvRecordingTimer.setVisibility(GONE);
                mUiHandler.removeCallbacks(mUpdateTimerRunnable);
                mContainerVideoQuality.setEnabled(true);
                // Scan the file so it appears in the gallery
                mUiHandler.postDelayed(this::loadLastPhoto, 1000);
            });
        }, "recording-stop").start();
    }

    private void capturePhoto() {
        final UVCCamera camera = mCamera;
        if (camera == null) {
            return;
        }
        if (mPendingPhotoFrame) {
            // Capture already in flight; ignore a second tap.
            return;
        }
        // We capture at the camera's native preview resolution rather than
        // mSurfaceView.getBitmap(), which only samples the TextureView at
        // view-pixel size and discards the rest of the sensor's detail. PHOTO
        // mode keeps the camera at mNativeMaxPreviewWidth/Height (see
        // updateCameraResolution / onConnect), so srcW × srcH here is the
        // sensor's full preview-stream resolution.
        final int srcW = mSurfaceView.getSourceWidth();
        final int srcH = mSurfaceView.getSourceHeight();
        if (srcW <= 0 || srcH <= 0) {
            return;
        }
        final int targetW = mSurfaceView.getTargetWidth();
        final int targetH = mSurfaceView.getTargetHeight();
        // Snapshot the upright rotation at shutter time (UI thread, where the
        // display rotation is reliable). 90/180/270/0° for the four device
        // orientations — the same value the preview matrix uses — so the saved
        // photo matches what the user framed instead of coming out rotated.
        final int rotationDeg = mSurfaceView.getUprightRotationDegrees();

        flashShutter();
        mPendingPhotoFrame = true;
        // Mark a capture→save chain in flight so a thumbnail tap before the new
        // photo is written defers instead of opening the previous one. Matched
        // by the decrement in savePhotoAsync's post (or the catch below if the
        // frame callback never registers).
        mPendingPhotoSaves++;

        // IFrameCallback runs on the USB thread inside the native preview
        // pipeline; it's independent of setPreviewDisplay's ANativeWindow
        // path, so registering it does NOT disturb the on-screen preview.
        // We grab a single frame, immediately deregister, and hand the bytes
        // off to a worker thread so the USB thread isn't blocked encoding.
        //
        // RGBX (not NV21) because the lib's NV21 path emits the chroma
        // plane in an order that Android's ImageFormat.NV21 misreads — the
        // empirical symptom is that decoded photos drop the red channel.
        // `uvc_any2rgbx` decodes MJPEG straight to R,G,B,X bytes via
        // libjpeg-turbo, which copyPixelsFromBuffer can pour directly into
        // an ARGB_8888 bitmap (whose in-memory byte order is also R,G,B,A).
        IFrameCallback callback = new IFrameCallback() {
            @Override
            public void onFrame(ByteBuffer frame) {
                if (!mPendingPhotoFrame) {
                    // Extra frame delivered between our first accept and
                    // setFrameCallback(null) taking effect — discard.
                    return;
                }
                int expected = srcW * srcH * 4;
                if (frame.remaining() < expected) {
                    return;
                }
                byte[] rgbx = new byte[expected];
                frame.get(rgbx, 0, expected);
                mPendingPhotoFrame = false;
                // DO NOT call camera.setFrameCallback(null, 0) on this
                // thread. UVCPreview holds capture_mutex around onFrame,
                // and setFrameCallback tries to acquire the same mutex,
                // which deadlocks the preview thread (the symptom is the
                // on-screen preview freezing the moment the shutter is
                // pressed). Post the deregister to the UI thread instead.
                mUiHandler.post(() -> {
                    try {
                        if (mCamera == camera) {
                            camera.setFrameCallback(null, 0);
                        }
                    } catch (Throwable ignored) {
                    }
                });
                new Thread(() -> processCapturedFrame(rgbx, srcW, srcH, targetW, targetH, rotationDeg),
                        "photo-process").start();
            }
        };
        try {
            camera.setFrameCallback(callback, UVCCamera.PIXEL_FORMAT_RGBX);
        } catch (Throwable t) {
            mPendingPhotoFrame = false;
            // No frame will arrive, so savePhotoAsync won't run to balance the
            // increment above — undo it here.
            mPendingPhotoSaves = Math.max(0, mPendingPhotoSaves - 1);
        }
    }

    // Lays the RGBX bytes directly into an ARGB_8888 bitmap, then applies
    // the same portrait rotate + aspect-crop the old getBitmap() path used.
    // No format conversion is needed: Android's ARGB_8888 native byte order
    // is R,G,B,A, which matches RGBX with the alpha byte ignored (libuvc
    // fills it with 0xFF).
    private void processCapturedFrame(byte[] rgbx, int w, int h, int targetW, int targetH, int rotationDeg) {
        Bitmap landscape = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        landscape.copyPixelsFromBuffer(ByteBuffer.wrap(rgbx));
        Matrix m = new Matrix();
        // rotationDeg is 90/180/270/0° for the four device orientations, matching
        // the preview transform so the saved photo is oriented the same as what
        // the user framed (see AspectRatioSurfaceView.getUprightRotationDegrees).
        m.postRotate(rotationDeg);
        Bitmap rotated = Bitmap.createBitmap(landscape, 0, 0, w, h, m, true);
        if (rotated != landscape) {
            landscape.recycle();
        }
        // Crop to the user-selected aspect (1:1, 16:9, …). A 90°/270° rotation
        // makes the bitmap portrait, so the wanted output is targetH:targetW;
        // a 0°/180° rotation leaves it landscape, so the output is targetW:targetH.
        boolean landscapeOut = (rotationDeg == 0 || rotationDeg == 180);
        int aspectW = landscapeOut ? targetW : targetH;
        int aspectH = landscapeOut ? targetH : targetW;
        Bitmap finalBitmap = cropToAspect(rotated, aspectW, aspectH);
        if (finalBitmap != rotated) {
            rotated.recycle();
        }
        savePhotoAsync(finalBitmap);
    }

    private void flashShutter() {
        // Brief animation on the action button for tactile feedback.
        mBtnQrToggle.animate()
                .scaleX(0.8f).scaleY(0.8f)
                .setDuration(80)
                .withEndAction(() -> mBtnQrToggle.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(120)
                        .start())
                .start();
    }

    // Centre-crops the already-rotated capture bitmap to the desired output
    // aspect aspectW:aspectH (width:height). The preview rotates the camera frame
    // upright but ALWAYS at the sensor's native aspect, so capturePhoto applies
    // the user's chosen aspect (1:1, 16:9, …) here. The caller passes the aspect
    // already oriented for the current rotation (portrait targetH:targetW,
    // landscape targetW:targetH). Returns the input unchanged when the aspect is
    // 0 or the bitmap already matches.
    private static Bitmap cropToAspect(Bitmap rotated, int aspectW, int aspectH) {
        if (rotated == null) return null;
        if (aspectW <= 0 || aspectH <= 0) return rotated;
        int rW = rotated.getWidth();
        int rH = rotated.getHeight();
        // Want output ratio rW'/rH' = aspectW/aspectH. Compare current via
        // cross-multiplication.
        long cur = (long) rW * aspectH;
        long want = (long) rH * aspectW;
        int cropW, cropH;
        if (cur > want) {
            // Bitmap is too wide — keep height, shrink width.
            cropH = rH;
            cropW = (int) ((long) rH * aspectW / aspectH);
        } else if (cur < want) {
            // Bitmap is too tall — keep width, shrink height.
            cropW = rW;
            cropH = (int) ((long) rW * aspectH / aspectW);
        } else {
            return rotated;
        }
        if (cropW <= 0 || cropH <= 0 || cropW > rW || cropH > rH) return rotated;
        int x = (rW - cropW) / 2;
        int y = (rH - cropH) / 2;
        return Bitmap.createBitmap(rotated, x, y, cropW, cropH);
    }

    // Opens the most recent capture (photo or video) in the system viewer.
    // Shared by the thumbnail tap and the deferred-open path that fires once an
    // in-flight save has landed (see mPendingPhotoSaves).
    private void openLastPhoto() {
        if (mLastPhotoUri == null) return;
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(mLastPhotoUri, mLastPhotoMimeType);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (Exception e) {
            // ActivityNotFoundException when no app handles the type, or the Uri
            // went stale because the file was deleted from the gallery while we
            // were backgrounded. Either way, don't crash: toast and refresh the
            // thumbnail so a deleted item stops lingering.
            showCustomToast(getString(R.string.cannot_open_media));
            loadLastPhoto();
        }
    }

    private void savePhotoAsync(Bitmap bitmap) {
        new Thread(() -> {
            Uri uri = savePhoto(bitmap);
            bitmap.recycle();
            mUiHandler.post(() -> {
                // This save chain is done (success or failure). Math.max guards
                // the underflow when onStop/onDisconnect reset the counter to 0
                // while this worker was still writing.
                mPendingPhotoSaves = Math.max(0, mPendingPhotoSaves - 1);
                if (uri != null) {
                    mLastPhotoUri = uri;
                    mLastPhotoMimeType = "image/*";
                    mLastPicture.setImageURI(uri);
                }
                // A thumbnail tap during the save was deferred. Once the last
                // in-flight save lands, open the newest saved item — mLastPhotoUri
                // now holds it (or the previous one if every save failed, in which
                // case opening the previous is the best we can do).
                if (mOpenLastPhotoWhenSaved && mPendingPhotoSaves == 0) {
                    mOpenLastPhotoWhenSaved = false;
                    openLastPhoto();
                }
            });
        }, "photo-save").start();
    }

    private Uri savePhoto(Bitmap bitmap) {
        String fileName = String.format(Locale.US, "USBCam_%d.jpg", System.currentTimeMillis());
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/EinkCamera");
        }
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            return null;
        }
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) {
                return null;
            }
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                return uri;
            }
        } catch (IOException e) {
            // Log error
        }
        return null;
    }

    // --- USB callbacks --------------------------------------------------------

    private final USBMonitor.OnDeviceConnectListener mUsbMonitorOnDeviceConnectListener = new USBMonitor.OnDeviceConnectListener() {

        @Override
        public void onAttach(UsbDevice device) {
            if (isUvcCamera(device)) {
                mUsbMonitor.requestPermission(device);
            }
        }

        @Override
        public void onDettach(UsbDevice device) {
        }

        @Override
        public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
            UVCCamera camera = new UVCCamera();
            camera.open(ctrlBlock);

            var previewWidth = UVCCamera.DEFAULT_PREVIEW_WIDTH;
            var previewHeight = UVCCamera.DEFAULT_PREVIEW_HEIGHT;

            var supportedSizes = camera.getSupportedSizeList();
            if (!supportedSizes.isEmpty()) {
                var bestSupportedSize = supportedSizes.get(0);
                previewWidth = bestSupportedSize.width;
                previewHeight = bestSupportedSize.height;
            }

            try {
                camera.setPreviewSize(previewWidth, previewHeight, UVCCamera.FRAME_FORMAT_MJPEG);
            } catch (final IllegalArgumentException e) {
                try {
                    camera.setPreviewSize(previewWidth, previewHeight, UVCCamera.DEFAULT_PREVIEW_MODE);
                } catch (final IllegalArgumentException e1) {
                    camera.destroy();
                    return;
                }
            }

            final int finalPreviewWidth = previewWidth;
            final int finalPreviewHeight = previewHeight;
            final UVCCamera finalCamera = camera;
            runOnUiThread(() -> {
                // Toast is shown from tryStartPendingPreview once the preview
                // has settled at its final size — onConnect always opens at
                // native max, but a VIDEO-mode resume immediately swaps down
                // to the selected quality, so showing the native-max value
                // here would mislead the user about what's being recorded.
                mPendingCamera = finalCamera;
                mNativeMaxPreviewWidth = finalPreviewWidth;
                mNativeMaxPreviewHeight = finalPreviewHeight;
                mSurfaceView.setSourceSize(finalPreviewWidth, finalPreviewHeight);
                mSurfaceView.setAspectRatio(finalPreviewWidth, finalPreviewHeight);
                if (mSurfaceView.isAvailable()) {
                    tryStartPendingPreview(new Surface(mSurfaceView.getSurfaceTexture()));
                }
            });
        }

        @Override
        public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
            if (mCamera != null) {
                mCamera.stopPreview();
                mCamera.close();
                mCamera = null;
            }
            mPendingCamera = null;
            // Drop any in-flight capture so the next attach can start fresh
            // — otherwise the flag stays set and capturePhoto becomes a no-op.
            mPendingPhotoFrame = false;

            runOnUiThread(() -> {
                hideFocusUi();
                // A hot-unplug mid-capture means the pending frame may never
                // arrive to balance the save counter; clear it (and any deferred
                // open) on the UI thread so future taps aren't stuck deferred.
                mPendingPhotoSaves = 0;
                mOpenLastPhotoWhenSaved = false;
                // onStop's own mCamera.close() also lands here, but mIsStarted is
                // already false by then and onStart rebuilds the surface, so skip;
                // shared-image scan needs no camera either. What remains is a real
                // hot-unplug while running: the preview is now frozen on the last
                // frame the camera delivered. Swap in a fresh blank TextureView to
                // wipe that frame (same clean-surface mechanism as resume — it
                // also leaves a clean BufferQueue ready for the eventual re-plug),
                // which clears the viewport to the white background, THEN put the
                // reminder over it and lock the toolbar until the user confirms.
                if (!mIsStarted || isFinishing() || mIsImageScanMode) return;
                replaceSurfaceView(0, 0, 0, 0);
                showNoCameraReminder();
            });
        }

        @Override
        public void onCancel(UsbDevice device) {
        }
    };

    // Re-arm the delayed presence check. Called from onStart so both initial
    // launch and resume re-evaluate once the grace period has elapsed.
    private void scheduleNoCameraCheck() {
        mUiHandler.removeCallbacks(mNoCameraCheckRunnable);
        mUiHandler.postDelayed(mNoCameraCheckRunnable, NO_CAMERA_REMINDER_DELAY_MS);
    }

    // Modal reminder telling the user to plug / re-plug the camera. Non-cancelable
    // so the toolbar behind it is unreachable until the user taps confirm — that
    // is how "lock the other keys until confirm" is satisfied (the dialog is the
    // only thing the touch system will deliver events to while it's up).
    // "Connected" spans onConnect → onDisconnect, including the windows where
    // the open camera is parked in mPendingCamera and mCamera is briefly null
    // (initial surface rendezvous, resolution swap, stabilization toggle). The
    // no-camera lock on the toolbar taps keys off this, so those transient
    // windows must not read as "unplugged".
    private boolean isCameraConnected() {
        return mCamera != null || mPendingCamera != null;
    }

    // Kill-switch for the no-camera button lock. Flip to false to exercise the
    // bottom toolbar without a UVC device plugged in (the underlying actions
    // are all individually guarded on mCamera == null, so unlocked taps are
    // harmless — they just flash and no-op at the hardware level).
    private static final boolean NO_CAMERA_LOCK_ENABLED = true;

    // True when a tap should be swallowed by the no-camera lock policy —
    // every locked button checks this rather than isCameraConnected()
    // directly, so the lock has a single on/off point.
    private boolean isNoCameraLocked() {
        return NO_CAMERA_LOCK_ENABLED && !isCameraConnected();
    }

    private void showNoCameraReminder() {
        // Never onto a stopped/finishing Activity (window leak — see onStop), and
        // never in shared-image scan mode, where working without a camera is the
        // whole point.
        if (!mIsStarted || isFinishing() || mIsImageScanMode) return;
        if (mNoCameraDialog != null && mNoCameraDialog.isShowing()) return;
        // Positive listener stays null: the builder-installed one dismisses
        // synchronously on tap, which would collapse the dialog in the same
        // frame the flash below is applied. The real handler goes on the
        // Button after show() (getButton returns null before that).
        mNoCameraDialog = new AlertDialog.Builder(MainActivity.this, R.style.BlackButtonAlertDialog)
                .setTitle(R.string.no_camera_title)
                .setMessage(R.string.no_camera_text)
                .setCancelable(false)
                .setPositiveButton(R.string.ok, null)
                .show();
        // Same black-flash feedback as the toolbar / menu items: drop the stock
        // gray ripple background (ripple + flash double up, as on the old close
        // button), flash black-bg/white-text on tap, then dismiss after the
        // 200ms flashSelection uses so the flash is actually visible. Dismiss
        // through a local capture, not mNoCameraDialog — a camera connecting
        // inside the 200ms window runs dismissNoCameraReminder, which nulls
        // the field; dismiss() on an already-dismissed dialog is a no-op, so
        // the delayed call is then harmless.
        final AlertDialog dialog = mNoCameraDialog;
        Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        ok.setBackground(null);
        // A bare black background fills the Button's bounds, but the stock
        // button bar leaves a different gap under the button than beside it,
        // so the flashed rectangle sat lopsided in the dialog's corner — the
        // requirement is equal clearance from the black rect to the dialog's
        // bottom and side edges. The button itself must NOT move (the idle
        // dialog has to keep its stock layout), so the correction lives in
        // the flash drawable, not in margins: after the first layout, measure
        // the button's real gaps to the visible dialog edges (decor bounds
        // minus the window background's shadow insets — theme metrics vary
        // across API levels, so measure rather than hardcode) and shrink the
        // flash rect on the deficient axis until both gaps match the larger
        // one. The horizontal gap is taken on the side nearest the dialog
        // edge: supportsRtl is on, and RTL puts the positive button on the
        // left.
        final int[] flashInset = new int[2]; // [horizontal, vertical]
        ok.post(() -> {
            if (dialog.getWindow() == null) return;
            View decor = dialog.getWindow().getDecorView();
            Rect inset = new Rect();
            if (decor.getBackground() != null) decor.getBackground().getPadding(inset);
            int[] decorLoc = new int[2];
            int[] okLoc = new int[2];
            decor.getLocationOnScreen(decorLoc);
            ok.getLocationOnScreen(okLoc);
            boolean rtl = decor.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            int sideGap = rtl
                    ? okLoc[0] - (decorLoc[0] + inset.left)
                    : (decorLoc[0] + decor.getWidth() - inset.right) - (okLoc[0] + ok.getWidth());
            int bottomGap = (decorLoc[1] + decor.getHeight() - inset.bottom) - (okLoc[1] + ok.getHeight());
            if (sideGap < 0 || bottomGap < 0) return;
            // Guard against degenerate measurements eating the whole rect —
            // if the needed inset would collapse it, leave the flash square.
            if (sideGap > bottomGap && (sideGap - bottomGap) * 2 < ok.getHeight()) {
                flashInset[1] = sideGap - bottomGap;
            } else if (bottomGap > sideGap && (bottomGap - sideGap) * 2 < ok.getWidth()) {
                flashInset[0] = bottomGap - sideGap;
            }
        });
        ok.setOnClickListener(v -> {
            // Symmetric inset on the deficient axis: the near edge gains the
            // measured shortfall so both clearances match, and the opposite
            // edge gains the same so the label stays centered in the black.
            ok.setBackground(new InsetDrawable(
                    ContextCompat.getDrawable(MainActivity.this, R.drawable.btn_toolbar_selected_bg),
                    flashInset[0], flashInset[1], flashInset[0], flashInset[1]));
            ok.setTextColor(0xFFFFFFFF);
            mUiHandler.postDelayed(dialog::dismiss, 200);
        });
    }

    private void dismissNoCameraReminder() {
        mUiHandler.removeCallbacks(mNoCameraCheckRunnable);
        if (mNoCameraDialog != null) {
            mNoCameraDialog.dismiss();
            mNoCameraDialog = null;
        }
    }

    // Whether a UVC device is physically attached right now, independent of
    // permission/connect state. Used by the presence check to leave a still-
    // connecting device (e.g. awaiting the OS permission prompt) alone.
    private boolean hasAttachedUvcCamera() {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usbManager == null) return false;
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (isUvcCamera(device)) return true;
        }
        return false;
    }

    private void requestCameraPermission() {
        AlertDialog.Builder cameraDialogBuilder = new AlertDialog.Builder(MainActivity.this, R.style.BlackButtonAlertDialog);
        cameraDialogBuilder.setTitle(R.string.camera_permission);
        cameraDialogBuilder.setMessage(R.string.camera_permission_text);
        cameraDialogBuilder.setPositiveButton(R.string.ok, (dialog, which) ->
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 0)
        );
        cameraDialogBuilder.show();
    }
}
