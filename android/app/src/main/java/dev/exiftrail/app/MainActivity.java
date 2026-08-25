package dev.exiftrail.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ContentValues;
import android.content.ContentUris;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.ExifInterface;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.Surface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int REQ_PHOTOS = 92;
    private static final double MIN_POINT_GAP_KM = .05;
    private static final double MAX_REASONABLE_SPEED_KMH = 1000d;
    private static final int VIDEO_WIDTH = 720;
    private static final int VIDEO_HEIGHT = 1280;
    private static final int OUTPUT_WIDTH = 1080;
    private static final int OUTPUT_HEIGHT = 1920;
    private static final int VIDEO_FPS = 30;
    private static final int VIDEO_SECONDS = 10;
    private static final int AD_ROTATION_MS = 5000;
    // The generated strip is 2172px wide and has a two-pixel dirty seam before frame 6.
    private static final int[] CHARACTER_FRAME_LEFT = {0, 271, 542, 813, 1084, 1361, 1629, 1901};
    private static final int[] CHARACTER_FRAME_RIGHT = {271, 542, 813, 1084, 1359, 1629, 1901, 2172};

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final SimpleDateFormat mapDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
    private final Calendar from = Calendar.getInstance();
    private final Calendar to = Calendar.getInstance();
    private final List<RoutePoint> points = new ArrayList<>();

    private Button fromButton;
    private Button toButton;
    private Button createButton;
    private Button saveButton;
    private TextView status;
    private ProgressBar scanProgress;
    private WebView mapView;
    private VideoView previewVideo;
    private ScrollView scrollView;
    private FrameLayout landingArea;
    private ImageView landingCharacter;
    private LinearLayout dateRow;
    private FrameLayout mapCard;
    private View statusCard;
    private View loadingView;
    private TextView loadingStatus;
    private ImageView adImage;
    private TextView adTitle;
    private TextView adDescription;
    private TextView adCta;
    private TextView adIndicator;
    private View adPanel;
    private FrameLayout adBannerView;
    private int adIndex;
    private float adTouchStartX;
    private float adTouchStartY;
    private boolean adGestureHorizontal;
    private boolean adTransitioning;
    private Bitmap[] adBitmaps;
    private volatile boolean destroyed;
    private volatile Thread activeTask;
    private boolean activityResumed;
    private boolean userPausedPreview;
    private final Handler adHandler = new Handler(Looper.getMainLooper());
    private final Runnable rotateAd = new Runnable() {
        @Override
        public void run() {
            if (adImage == null) return;
            slideAd(adIndex + 1, 1);
            adHandler.postDelayed(this, AD_ROTATION_MS);
        }
    };
    private AnimationDrawable landingAnimation;
    private AnimationDrawable loadingAnimation;
    private File preparedVideoFile;
    private boolean mapReady;
    private List<RoutePoint> pendingMapRoute;

    private static final AdBanner[] AD_BANNERS = {
            new AdBanner(
                    "neon-tower.png",
                    "Neon Tower",
                    "Stack higher. Drift beyond the stars.",
                    "https://play.google.com/store/apps/details?id=com.bible.neontower",
                    0xc921193d, Color.WHITE, 0xffdbeafe, 0xffffb52e, 0xff191f28
            ),
            new AdBanner(
                    "neon-bricks.png",
                    "Neon Bricks",
                    "Break the neon wall. Chase the high score.",
                    "https://play.google.com/store/apps/details?id=com.ultraneongalaxy.bricks",
                    0xc91b123e, Color.WHITE, 0xffeadcff, 0xffff4fc3, Color.WHITE
            ),
            new AdBanner(
                    "neon-drift-arcflare.png",
                    "Neon Drift: Arcflare",
                    "Drift, dodge, survive the neon lanes.",
                    "https://play.google.com/store/apps/details?id=com.aussiepus.arcflare",
                    0xc90b1634, Color.WHITE, 0xffd9f8ff, 0xff32d9ff, 0xff071426
            ),
            new AdBanner(
                    "decody.png",
                    "Decody",
                    "Turn pet sounds into human words.",
                    "https://play.google.com/store/apps/details?id=com.aussiepus.decody",
                    0xdffff4e8, 0xff2b211c, 0xff614b3d, 0xffff704d, Color.WHITE
            )
    };

    private static final class AdBanner {
        final String asset;
        final String title;
        final String description;
        final String link;
        final int panelColor;
        final int titleColor;
        final int descriptionColor;
        final int ctaColor;
        final int ctaTextColor;

        AdBanner(String asset, String title, String description, String link,
                 int panelColor, int titleColor, int descriptionColor,
                 int ctaColor, int ctaTextColor) {
            this.asset = asset;
            this.title = title;
            this.description = description;
            this.link = link;
            this.panelColor = panelColor;
            this.titleColor = titleColor;
            this.descriptionColor = descriptionColor;
            this.ctaColor = ctaColor;
            this.ctaTextColor = ctaTextColor;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        from.set(currentYear, Calendar.JANUARY, 1, 0, 0, 0);
        to.setTimeInMillis(System.currentTimeMillis());
        to.set(Calendar.HOUR_OF_DAY, 23);
        to.set(Calendar.MINUTE, 59);
        to.set(Calendar.SECOND, 59);
        adBitmaps = new Bitmap[AD_BANNERS.length];

        setContentView(buildUi());
        refreshDates();
    }

    private View buildUi() {
        FrameLayout screen = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        scrollView = scroll;
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(10), dp(20), dp(44));
        root.setBackgroundColor(Color.WHITE);
        scroll.addView(root);
        screen.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout topbar = new LinearLayout(this);
        topbar.setGravity(Gravity.CENTER_VERTICAL);
        topbar.setPadding(0, 0, 0, dp(8));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(32), dp(32));
        logoLp.setMargins(0, dp(18), dp(10), dp(18));
        topbar.addView(logo, logoLp);
        TextView brand = text("ExifTrail", 19, 0xff191f28, true);
        topbar.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(topbar, new LinearLayout.LayoutParams(-1, dp(72)));

        landingArea = new FrameLayout(this);
        LinearLayout landingControls = new LinearLayout(this);
        landingControls.setOrientation(LinearLayout.VERTICAL);
        landingControls.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams landingControlsLp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        landingControlsLp.setMargins(0, dp(12), 0, dp(12));
        landingArea.addView(landingControls, landingControlsLp);
        root.addView(landingArea, new LinearLayout.LayoutParams(-1, 0, 1f));

        landingCharacter = new ImageView(this);
        landingAnimation = loadCharacterAnimation();
        if (landingAnimation != null) landingCharacter.setImageDrawable(landingAnimation);
        landingCharacter.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams landingCharacterLp = new LinearLayout.LayoutParams(dp(200), dp(200));
        landingCharacterLp.setMargins(0, 0, 0, dp(8));
        landingControls.addView(landingCharacter, landingCharacterLp);
        landingCharacter.post(() -> {
            if (landingAnimation != null) landingAnimation.start();
        });

        TextView landingTitle = text("Turn memories into journeys", 17, 0xff191f28, true);
        landingTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams landingTitleLp = new LinearLayout.LayoutParams(-1, -2);
        landingTitleLp.setMargins(0, 0, 0, dp(18));
        landingControls.addView(landingTitle, landingTitleLp);

        dateRow = new LinearLayout(this);
        dateRow.setOrientation(LinearLayout.HORIZONTAL);
        dateRow.setGravity(Gravity.CENTER);
        dateRow.setWeightSum(2);
        LinearLayout.LayoutParams dateRowLp = new LinearLayout.LayoutParams(-1, -2);
        dateRowLp.setMargins(0, 0, 0, dp(12));
        landingControls.addView(dateRow, dateRowLp);

        fromButton = secondaryButton();
        toButton = secondaryButton();
        dateRow.addView(fromButton, weighted());
        dateRow.addView(toButton, weighted());
        fromButton.setOnClickListener(v -> pickDate(from, this::refreshDates));
        toButton.setOnClickListener(v -> pickDate(to, this::refreshDates));

        createButton = primaryButton("Allow photos and create video");
        createButton.setOnClickListener(v -> startRouteBuild());
        LinearLayout.LayoutParams createLp = new LinearLayout.LayoutParams(-1, dp(56));
        createLp.setMargins(0, 0, 0, dp(18));
        landingControls.addView(createButton, createLp);

        FrameLayout adBanner = buildAdBanner();
        LinearLayout.LayoutParams adLp = new LinearLayout.LayoutParams(-1, dp(100));
        adLp.setMargins(0, 0, 0, dp(18));
        landingControls.addView(adBanner, adLp);

        mapCard = new FrameLayout(this);
        mapCard.setBackground(rounded(0xffdbeafe, 0xffe5e8eb, 24));
        mapCard.setClipToOutline(true);
        float density = getResources().getDisplayMetrics().density;
        int contentWidthDp = Math.round(getResources().getDisplayMetrics().widthPixels / density) - 40;
        int mapHeightDp = Math.round(contentWidthDp * 840f / 720f);
        int previewHeightDp = Math.round(contentWidthDp * OUTPUT_HEIGHT / (float) OUTPUT_WIDTH);
        mapView = new WebView(this);
        WebSettings settings = mapView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        mapView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        mapView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                mapReady = true;
                view.postDelayed(() -> view.evaluateJavascript("map.invalidateSize(false)", null), 250);
                if (pendingMapRoute != null) {
                    List<RoutePoint> route = pendingMapRoute;
                    pendingMapRoute = null;
                    renderMapRoute(route);
                }
            }
        });
        mapReady = false;
        mapView.loadDataWithBaseURL("file:///android_asset/", mapHtml(), "text/html", "UTF-8", null);
        mapCard.addView(mapView, new FrameLayout.LayoutParams(-1, dp(mapHeightDp)));
        previewVideo = new VideoView(this);
        previewVideo.setBackgroundColor(Color.BLACK);
        previewVideo.setVisibility(View.GONE);
        previewVideo.setContentDescription("Generated route video preview. Tap to pause or play.");
        previewVideo.setOnClickListener(v -> {
            if (previewVideo.isPlaying()) {
                previewVideo.pause();
                userPausedPreview = true;
            } else {
                userPausedPreview = false;
                previewVideo.start();
            }
        });
        mapCard.addView(previewVideo, new FrameLayout.LayoutParams(-1, -1));
        mapCard.setVisibility(View.GONE);
        root.addView(mapCard, new LinearLayout.LayoutParams(-1, dp(previewHeightDp)));

        saveButton = secondaryActionButton("Download video");
        saveButton.setEnabled(false);
        saveButton.setVisibility(View.GONE);
        saveButton.setOnClickListener(v -> saveVideo());
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(-1, dp(56));
        saveLp.setMargins(0, dp(12), 0, dp(12));
        root.addView(saveButton, saveLp);

        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        statusCard.setBackground(rounded(0xffffffff, 0xffe5e8eb, 16));
        status = text("Allow photo access, then ExifTrail builds a route video from time and GPS metadata.", 15, 0xff191f28, true);
        status.setLineSpacing(0, 1.3f);
        statusCard.addView(status);
        this.statusCard = statusCard;
        statusCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.setMargins(0, 0, 0, dp(20));
        root.addView(statusCard, statusLp);

        loadingView = new LinearLayout(this);
        ((LinearLayout) loadingView).setOrientation(LinearLayout.VERTICAL);
        ((LinearLayout) loadingView).setGravity(Gravity.CENTER);
        ((LinearLayout) loadingView).setPadding(dp(32), 0, dp(32), 0);
        loadingView.setBackgroundColor(Color.WHITE);
        loadingView.setClickable(true);
        loadingView.setFocusable(true);
        ImageView loadingCharacter = new ImageView(this);
        loadingAnimation = loadCharacterAnimation();
        if (loadingAnimation != null) loadingCharacter.setImageDrawable(loadingAnimation);
        loadingCharacter.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        ((LinearLayout) loadingView).addView(loadingCharacter, new LinearLayout.LayoutParams(dp(132), dp(132)));
        TextView loadingTitle = text("Building your journey", 22, 0xff191f28, true);
        LinearLayout.LayoutParams loadingTitleLp = new LinearLayout.LayoutParams(-1, -2);
        loadingTitleLp.setMargins(0, dp(20), 0, dp(6));
        ((LinearLayout) loadingView).addView(loadingTitle, loadingTitleLp);
        loadingStatus = text("Scanning photos...", 15, 0xff6b7684, false);
        loadingStatus.setGravity(Gravity.CENTER);
        ((LinearLayout) loadingView).addView(loadingStatus, new LinearLayout.LayoutParams(-1, -2));
        scanProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        scanProgress.setMax(100);
        scanProgress.setProgressTintList(ColorStateList.valueOf(0xff3182f6));
        LinearLayout.LayoutParams loadingProgressLp = new LinearLayout.LayoutParams(-1, dp(10));
        loadingProgressLp.setMargins(0, dp(18), 0, 0);
        ((LinearLayout) loadingView).addView(scanProgress, loadingProgressLp);
        loadingView.setVisibility(View.GONE);
        screen.addView(loadingView, new FrameLayout.LayoutParams(-1, -1));

        return screen;
    }

    private FrameLayout buildAdBanner() {
        FrameLayout banner = new FrameLayout(this);
        adBannerView = banner;
        banner.setBackground(rounded(0xff111827, 0xffdbe7f8, 16));
        banner.setClipToOutline(true);
        banner.setFocusable(true);
        int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        adImage = new ImageView(this);
        adImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        adImage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        banner.addView(adImage, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(10), dp(8));
        panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        adPanel = panel;
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(dp(214), -1, Gravity.START);
        panelLp.setMargins(dp(5), dp(5), 0, dp(5));
        banner.addView(panel, panelLp);

        adTitle = text("", 20, Color.WHITE, true);
        adTitle.setMaxLines(2);
        adTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        panel.addView(adTitle, titleLp);
        adDescription = text("", 10, 0xffdbeafe, false);
        adDescription.setMaxLines(2);
        adDescription.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams descriptionLp = new LinearLayout.LayoutParams(-1, -2);
        descriptionLp.setMargins(0, dp(1), 0, dp(4));
        panel.addView(adDescription, descriptionLp);
        adCta = text("Explore now", 10, Color.WHITE, true);
        adCta.setGravity(Gravity.CENTER);
        adCta.setPadding(dp(10), 0, dp(10), 0);
        panel.addView(adCta, new LinearLayout.LayoutParams(dp(92), dp(24)));

        adIndicator = text("", 10, Color.WHITE, true);
        adIndicator.setGravity(Gravity.CENTER);
        adIndicator.setPadding(dp(8), 0, dp(8), 0);
        adIndicator.setBackground(rounded(0x99000000, 0x00000000, 12));
        adIndicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams indicatorLp = new FrameLayout.LayoutParams(-2, dp(24), Gravity.END | Gravity.BOTTOM);
        indicatorLp.setMargins(0, 0, dp(8), dp(8));
        banner.addView(adIndicator, indicatorLp);

        banner.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(AD_BANNERS[adIndex].link))));
        banner.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                adTouchStartX = event.getX();
                adTouchStartY = event.getY();
                adGestureHorizontal = false;
                adHandler.removeCallbacks(rotateAd);
                adImage.animate().cancel();
                adPanel.animate().cancel();
                adImage.setTranslationX(0);
                adImage.setAlpha(1f);
                adPanel.setTranslationX(0);
                adPanel.setAlpha(1f);
                adTransitioning = false;
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float deltaX = event.getX() - adTouchStartX;
                float deltaY = event.getY() - adTouchStartY;
                if (!adGestureHorizontal && Math.abs(deltaX) > touchSlop
                        && Math.abs(deltaX) > Math.abs(deltaY)) {
                    adGestureHorizontal = true;
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                float deltaX = event.getX() - adTouchStartX;
                float deltaY = event.getY() - adTouchStartY;
                if (adGestureHorizontal && Math.abs(deltaX) >= dp(32)) {
                    int direction = deltaX < 0 ? 1 : -1;
                    slideAd(adIndex + direction, direction);
                } else if (Math.abs(deltaX) <= touchSlop && Math.abs(deltaY) <= touchSlop) {
                    v.performClick();
                }
                v.getParent().requestDisallowInterceptTouchEvent(false);
                resetAdRotation();
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
                resetAdRotation();
                return true;
            }
            return true;
        });
        banner.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
                if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                    slideAd(adIndex + 1, 1);
                    resetAdRotation();
                    return true;
                }
                if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                    slideAd(adIndex - 1, -1);
                    resetAdRotation();
                    return true;
                }
                return super.performAccessibilityAction(host, action, arguments);
            }
        });
        showAd(0);
        return banner;
    }

    private void showAd(int index) {
        int nextIndex = (index % AD_BANNERS.length + AD_BANNERS.length) % AD_BANNERS.length;
        AdBanner ad = AD_BANNERS[nextIndex];
        if (adBitmaps[nextIndex] == null) {
            try (InputStream input = getAssets().open("ad-banners/" + ad.asset)) {
                adBitmaps[nextIndex] = BitmapFactory.decodeStream(input);
            } catch (Exception ignored) {
                return;
            }
        }
        if (adBitmaps[nextIndex] == null) return;
        adIndex = nextIndex;
        adImage.setImageBitmap(adBitmaps[adIndex]);
        adTitle.setText(ad.title);
        adTitle.setTextColor(ad.titleColor);
        adDescription.setText(ad.description);
        adDescription.setTextColor(ad.descriptionColor);
        adCta.setTextColor(ad.ctaTextColor);
        adCta.setBackground(rounded(ad.ctaColor, ad.ctaColor, 12));
        adPanel.setBackground(rounded(ad.panelColor, 0x00000000, 14));
        adIndicator.setText((adIndex + 1) + " / " + AD_BANNERS.length);
        adBannerView.setContentDescription(ad.title + ". " + ad.description + ". Ad "
                + (adIndex + 1) + " of " + AD_BANNERS.length + ". Swipe left or right for another ad.");
    }

    private void slideAd(int index, int direction) {
        if (adTransitioning) return;
        adTransitioning = true;
        float distance = dp(28) * direction;
        adImage.animate().translationX(-distance).alpha(.25f).setDuration(110).withEndAction(() -> {
            showAd(index);
            adImage.setTranslationX(distance);
            adImage.animate().translationX(0).alpha(1f).setDuration(170).withEndAction(() -> adTransitioning = false).start();
        }).start();
        adPanel.animate().translationX(-distance).alpha(.2f).setDuration(110).withEndAction(() -> {
            adPanel.setTranslationX(distance);
            adPanel.animate().translationX(0).alpha(1f).setDuration(170).start();
        }).start();
    }

    private void resetAdRotation() {
        adHandler.removeCallbacks(rotateAd);
        if (adImage != null && loadingView != null && loadingView.getVisibility() != View.VISIBLE) {
            adHandler.postDelayed(rotateAd, AD_ROTATION_MS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        resetAdRotation();
        if (previewVideo != null && previewVideo.getVisibility() == View.VISIBLE && !userPausedPreview) {
            previewVideo.start();
        }
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        adHandler.removeCallbacks(rotateAd);
        if (previewVideo != null && previewVideo.isPlaying()) previewVideo.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        Thread task = activeTask;
        if (task != null) task.interrupt();
        adHandler.removeCallbacks(rotateAd);
        if (adImage != null) adImage.animate().cancel();
        if (adPanel != null) adPanel.animate().cancel();
        if (previewVideo != null) previewVideo.stopPlayback();
        if (mapView != null) mapView.destroy();
        if (adImage != null) adImage.setImageDrawable(null);
        if (adBitmaps != null) {
            for (Bitmap bitmap : adBitmaps) {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            }
        }
        super.onDestroy();
    }

    private AnimationDrawable loadCharacterAnimation() {
        try (InputStream input = getAssets().open("characters/satgat-walk-8.png")) {
            Bitmap sheet = BitmapFactory.decodeStream(input);
            if (sheet == null) return null;
            AnimationDrawable animation = new AnimationDrawable();
            for (int i = 0; i < CHARACTER_FRAME_LEFT.length; i++) {
                int left = Math.min(sheet.getWidth(), CHARACTER_FRAME_LEFT[i]);
                int right = Math.min(sheet.getWidth(), CHARACTER_FRAME_RIGHT[i]);
                Bitmap frame = Bitmap.createBitmap(sheet, left, 0, right - left, sheet.getHeight());
                animation.addFrame(new BitmapDrawable(getResources(), frame), 150);
            }
            animation.setOneShot(false);
            sheet.recycle();
            return animation;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void startRouteBuild() {
        if (from.after(to)) {
            status.setText("Start date must be earlier than end date.");
            return;
        }
        if (!hasPhotoPermission()) {
            requestPhotoAccess();
            return;
        }
        buildRoute();
    }

    private void showLoadingScreen() {
        adHandler.removeCallbacks(rotateAd);
        if (previewVideo != null) {
            previewVideo.setOnPreparedListener(null);
            previewVideo.setOnErrorListener(null);
            previewVideo.stopPlayback();
            previewVideo.setVisibility(View.GONE);
        }
        if (mapView != null) mapView.setVisibility(View.VISIBLE);
        loadingView.setVisibility(View.VISIBLE);
        scanProgress.setIndeterminate(false);
        scanProgress.setMax(100);
        scanProgress.setProgress(0);
        loadingStatus.setText("Scanning photos...");
        if (loadingAnimation != null) loadingAnimation.start();
        fromButton.setEnabled(false);
        toButton.setEnabled(false);
        createButton.setEnabled(false);
        saveButton.setEnabled(false);
    }

    private void updateLoadingProgress(int progress, String message) {
        runOnUiThread(() -> {
            if (destroyed) return;
            scanProgress.setIndeterminate(false);
            scanProgress.setMax(100);
            scanProgress.setProgress(Math.max(0, Math.min(100, progress)));
            loadingStatus.setText(message);
        });
    }

    private void hideLoadingScreen() {
        loadingView.setVisibility(View.GONE);
        if (loadingAnimation != null) loadingAnimation.stop();
        fromButton.setEnabled(true);
        toButton.setEnabled(true);
        createButton.setEnabled(true);
        resetAdRotation();
    }

    private void requestPhotoAccess() {
        if (Build.VERSION.SDK_INT >= 34
                && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED) {
            // In partial-access mode Android considers both media permissions
            // granted. Request READ_MEDIA_IMAGES again to reopen the full-access dialog.
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQ_PHOTOS);
            return;
        }
        requestPermissions(photoPermissions(), REQ_PHOTOS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PHOTOS && hasPhotoPermission()) {
            buildRoute();
        } else {
            scanProgress.setVisibility(View.GONE);
            status.setText("Selected photo access is active. You can continue with the photos currently allowed on this phone.");
            new AlertDialog.Builder(this)
                    .setTitle("Photo access is ready")
                    .setMessage("Android currently allows a selected set of photos. Press Allow and ExifTrail will immediately scan the allowed photos between From and To. No file picker is used.")
                    // Android cannot silently upgrade partial access to full access.
                    // Continue with the photos the user has already allowed.
                    .setPositiveButton("Allow and continue", (dialog, which) -> buildRoute())
                    .setNegativeButton("Close", null)
                    .show();
        }
    }

    private void buildRoute() {
        compactLandingArea();
        showLoadingScreen();
        mapCard.setVisibility(View.VISIBLE);
        statusCard.setVisibility(View.GONE);
        if (preparedVideoFile != null) {
            preparedVideoFile.delete();
            preparedVideoFile = null;
        }
        points.clear();

        Thread task = new Thread(() -> {
            ScanResult result = queryPhotos();
            if (destroyed || Thread.currentThread().isInterrupted()) return;
            if (result.points.size() < 2) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    points.clear();
                    status.setText("Scanned " + result.total + " photos in range, found " + result.withGps + " with GPS. Try a wider range or enable camera location tags.");
                    statusCard.setVisibility(View.VISIBLE);
                    mapCard.setVisibility(View.GONE);
                    saveButton.setVisibility(View.GONE);
                    hideLoadingScreen();
                });
                return;
            }
            try {
                List<RoutePoint> route = new ArrayList<>(result.points);
                updateLoadingProgress(0, "Preparing your moving map video...");
                File videoFile = prepareRouteVideo(route);
                runOnUiThread(() -> {
                    if (destroyed) return;
                    points.clear();
                    points.addAll(route);
                    preparedVideoFile = videoFile;
                    status.setText(points.size() + " route points found from " + result.total + " photos. Your video is ready.");
                    statusCard.setVisibility(View.VISIBLE);
                    mapCard.setVisibility(View.VISIBLE);
                    saveButton.setVisibility(View.VISIBLE);
                    saveButton.setEnabled(true);
                    showPreparedVideo();
                    hideLoadingScreen();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    status.setText("Video preparation failed: " + e.getMessage());
                    statusCard.setVisibility(View.VISIBLE);
                    mapCard.setVisibility(View.GONE);
                    saveButton.setVisibility(View.GONE);
                    hideLoadingScreen();
                });
            }
        });
        activeTask = task;
        task.start();
    }

    private void saveVideo() {
        if (preparedVideoFile == null || !preparedVideoFile.exists()) {
            status.setText("Create a route first, then download the prepared video.");
            return;
        }
        showLoadingScreen();
        loadingStatus.setText("Saving video to Gallery...");
        updateLoadingProgress(0, "Saving video to Gallery...");
        Thread task = new Thread(() -> {
            try {
                Uri uri = publishPreparedVideo(preparedVideoFile);
                runOnUiThread(() -> {
                    if (destroyed) return;
                    hideLoadingScreen();
                    statusCard.setVisibility(View.VISIBLE);
                    mapCard.setVisibility(View.VISIBLE);
                    saveButton.setVisibility(View.VISIBLE);
                    saveButton.setEnabled(true);
                    status.setText("Saved moving route video to Gallery: " + uri);
                    showPreparedVideo();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    hideLoadingScreen();
                    statusCard.setVisibility(View.VISIBLE);
                    saveButton.setVisibility(View.VISIBLE);
                    saveButton.setEnabled(true);
                    status.setText("Video save failed: " + e.getMessage());
                    showPreparedVideo();
                });
            }
        });
        activeTask = task;
        task.start();
    }

    private void showPreparedVideo() {
        if (preparedVideoFile == null || !preparedVideoFile.exists()) return;
        userPausedPreview = false;
        mapView.setVisibility(View.INVISIBLE);
        previewVideo.setVisibility(View.VISIBLE);
        previewVideo.setBackgroundColor(Color.BLACK);
        previewVideo.setOnPreparedListener(player -> {
            player.setLooping(true);
            previewVideo.setBackgroundColor(Color.TRANSPARENT);
            if (activityResumed && !userPausedPreview) previewVideo.start();
        });
        previewVideo.setOnErrorListener((player, what, extra) -> {
            previewVideo.setBackgroundColor(Color.BLACK);
            previewVideo.setContentDescription("Route video preview is unavailable. The prepared video can still be downloaded.");
            status.setText("Preview playback is unavailable on this device. The prepared video can still be downloaded.");
            statusCard.setVisibility(View.VISIBLE);
            return true;
        });
        previewVideo.setVideoPath(preparedVideoFile.getAbsolutePath());
    }

    private ScanResult queryPhotos() {
        List<RoutePoint> rows = new ArrayList<>();
        int total = 0;
        int withGps = 0;
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.LATITUDE,
                MediaStore.Images.Media.LONGITUDE
        };
        String selection = MediaStore.Images.Media.DATE_TAKEN + " BETWEEN ? AND ?";
        String[] args = {String.valueOf(from.getTimeInMillis()), String.valueOf(to.getTimeInMillis())};
        String order = MediaStore.Images.Media.DATE_TAKEN + " ASC";

        try (Cursor cursor = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                order
        )) {
            if (cursor == null) return new ScanResult(rows, 0, 0);
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN);
            int latCol = cursor.getColumnIndex(MediaStore.Images.Media.LATITUDE);
            int lngCol = cursor.getColumnIndex(MediaStore.Images.Media.LONGITUDE);
            int totalPhotos = cursor.getCount();
            updateLoadingProgress(0, "Scanning photos... 0/" + totalPhotos + " checked");
            while (cursor.moveToNext()) {
                total += 1;
                long id = cursor.getLong(idCol);
                long taken = cursor.getLong(dateCol);
                Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                // Prefer the original EXIF location. MediaStore's latitude and
                // longitude columns can be rounded or stale after gallery edits.
                float[] latLng = readLatLng(uri);
                if (latLng == null) latLng = readLatLngFromColumns(cursor, latCol, lngCol);
                if (latLng == null) continue;
                withGps += 1;
                RoutePoint prev = rows.isEmpty() ? null : rows.get(rows.size() - 1);
                if (prev != null && shouldSkip(prev, latLng[0], latLng[1], taken)) continue;
                rows.add(new RoutePoint(latLng[0], latLng[1], taken));
                if (total % 10 == 0 || total == totalPhotos) {
                    int scanned = total;
                    int gps = withGps;
                    int progress = Math.round(scanned * 55f / Math.max(1, totalPhotos));
                    updateLoadingProgress(progress, "Scanning photos... " + scanned + "/" + totalPhotos + " checked, " + gps + " with GPS");
                }
            }
        }
        return new ScanResult(rows, total, withGps);
    }

    private boolean shouldSkip(RoutePoint prev, double lat, double lng, long taken) {
        double distance = distanceKm(prev.lat, prev.lng, lat, lng);
        if (distance < MIN_POINT_GAP_KM) return true;
        double hours = Math.max((taken - prev.time) / 36e5, .01);
        return distance / hours > MAX_REASONABLE_SPEED_KMH;
    }

    private float[] readLatLngFromColumns(Cursor cursor, int latCol, int lngCol) {
        if (latCol < 0 || lngCol < 0 || cursor.isNull(latCol) || cursor.isNull(lngCol)) return null;
        double lat = cursor.getDouble(latCol);
        double lng = cursor.getDouble(lngCol);
        if (lat == 0d && lng == 0d) return null;
        return new float[]{(float) lat, (float) lng};
    }

    private float[] readLatLng(Uri uri) {
        if (Build.VERSION.SDK_INT >= 29 && hasMediaLocationPermission()) {
            float[] original = readOriginalLatLng(uri);
            if (original != null) return original;
        }
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) return null;
            ExifInterface exif = new ExifInterface(input);
            float[] latLng = new float[2];
            return exif.getLatLong(latLng) ? latLng : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void compactLandingArea() {
        if (landingArea == null) return;
        if (landingCharacter != null) landingCharacter.setVisibility(View.GONE);
        if (landingAnimation != null) landingAnimation.stop();
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) landingArea.getLayoutParams();
        params.height = LinearLayout.LayoutParams.WRAP_CONTENT;
        params.weight = 0f;
        landingArea.setLayoutParams(params);
    }

    private float[] readOriginalLatLng(Uri uri) {
        Uri source = Build.VERSION.SDK_INT >= 29 ? MediaStore.setRequireOriginal(uri) : uri;
        try (InputStream input = getContentResolver().openInputStream(source)) {
            if (input == null) return null;
            ExifInterface exif = new ExifInterface(input);
            float[] latLng = new float[2];
            return exif.getLatLong(latLng) ? latLng : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean hasPhotoPermission() {
        if (checkSelfPermission(photoPermissions()[0]) != PackageManager.PERMISSION_GRANTED) return false;
        if (Build.VERSION.SDK_INT >= 29 && !hasMediaLocationPermission()) return false;
        // On Android 14+, full access grants READ_MEDIA_IMAGES and may leave
        // the selected-photo permission granted as well. Check the full-image
        // permission itself instead of treating that companion grant as partial.
        return Build.VERSION.SDK_INT < 34
                || checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasMediaLocationPermission() {
        return Build.VERSION.SDK_INT < 29 || checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private String[] photoPermissions() {
        if (Build.VERSION.SDK_INT >= 34) {
            return new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.ACCESS_MEDIA_LOCATION};
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.ACCESS_MEDIA_LOCATION};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private void pickDate(Calendar target, Runnable afterPick) {
        DatePickerDialog dialog = new DatePickerDialog(
                this,
                (DatePicker view, int year, int month, int dayOfMonth) -> {
                    target.set(Calendar.YEAR, year);
                    target.set(Calendar.MONTH, month);
                    target.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                    if (target == to) {
                        target.set(Calendar.HOUR_OF_DAY, 23);
                        target.set(Calendar.MINUTE, 59);
                        target.set(Calendar.SECOND, 59);
                    } else {
                        target.set(Calendar.HOUR_OF_DAY, 0);
                        target.set(Calendar.MINUTE, 0);
                        target.set(Calendar.SECOND, 0);
                    }
                    afterPick.run();
                },
                target.get(Calendar.YEAR),
                target.get(Calendar.MONTH),
                target.get(Calendar.DAY_OF_MONTH)
        );
        dialog.show();
    }

    private void refreshDates() {
        fromButton.setText("From  " + dateFormat.format(new Date(from.getTimeInMillis())));
        toButton.setText("To  " + dateFormat.format(new Date(to.getTimeInMillis())));
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.08f);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return view;
    }

    private Button primaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        button.setMinHeight(dp(56));
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setBackground(rounded(0xff3182f6, 0xff3182f6, 14));
        return button;
    }

    private Button secondaryButton() {
        Button button = new Button(this);
        button.setTextSize(14);
        button.setTextColor(0xff191f28);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        button.setMinHeight(dp(58));
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(rounded(0xffffffff, 0xffe5e8eb, 14));
        return button;
    }

    private Button secondaryActionButton(String value) {
        Button button = secondaryButton();
        button.setText(value);
        button.setTextColor(0xff3182f6);
        return button;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(64), 1);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private File prepareRouteVideo(List<RoutePoint> route) throws Exception {
        File file = new File(getCacheDir(), "exiftrail-prepared-route.mp4");
        if (file.exists() && !file.delete()) throw new IllegalStateException("Could not replace prepared video");
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_TRUNCATE
        )) {
            encodeRouteVideo(route, pfd);
        }
        return file;
    }

    private Uri publishPreparedVideo(File source) throws Exception {
        ContentValues values = new ContentValues();
        String name = "ExifTrail-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".mp4";
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ExifTrail");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
        }

        Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("Could not create video file");
        try (FileInputStream input = new FileInputStream(source);
             OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IllegalStateException("Could not open Gallery file");
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            long length = Math.max(1, source.length());
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (destroyed || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Video save was cancelled");
                }
                output.write(buffer, 0, read);
                total += read;
                updateLoadingProgress((int) Math.min(100, total * 100 / length), "Saving video to Gallery...");
            }
        } catch (Exception e) {
            getContentResolver().delete(uri, null, null);
            throw e;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
        }
        return uri;
    }

    private void encodeRouteVideo(List<RoutePoint> route, ParcelFileDescriptor pfd) throws Exception {
        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        int outputWidth = VIDEO_WIDTH;
        int outputHeight = VIDEO_HEIGHT;
        try {
            MediaCodecInfo.VideoCapabilities videoCapabilities = encoder.getCodecInfo()
                    .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    .getVideoCapabilities();
            if (videoCapabilities.areSizeAndRateSupported(OUTPUT_WIDTH, OUTPUT_HEIGHT, VIDEO_FPS)) {
                outputWidth = OUTPUT_WIDTH;
                outputHeight = OUTPUT_HEIGHT;
            }
        } catch (RuntimeException ignored) {
            // Keep the known-compatible 720x1280 path when codec capabilities are incomplete.
        }
        float outputScale = outputWidth / (float) VIDEO_WIDTH;
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outputWidth, outputHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, outputWidth == OUTPUT_WIDTH ? 10_000_000 : 4_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        MediaMuxer muxer = null;
        Surface surface = null;
        boolean encoderStarted = false;
        Bitmap characterSprite = null;
        List<MapSnapshot> mapSnapshots = new ArrayList<>();
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = encoder.createInputSurface();
            int totalFrames = VIDEO_SECONDS * VIDEO_FPS;
            characterSprite = loadCharacterSprite();
            Thread.sleep(1200);
            CountDownLatch routeReady = new CountDownLatch(1);
            runOnUiThread(() -> mapView.evaluateJavascript(
                    "map.invalidateSize(false);renderRoute(" + routeJson(route) + ");routePoints.length",
                    value -> routeReady.countDown()
            ));
            if (!routeReady.await(5000, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Map renderer did not become ready in time");
            }
            final int localSnapshotCount = 36;
            final int transitionSnapshotCount = 8;
            for (int i = 0; i < localSnapshotCount; i++) {
                float cameraProgress = .85f * i / (float) (localSnapshotCount - 1);
                mapSnapshots.add(captureMapFrame(route, cameraProgress, false,
                        "setCamera(" + cameraProgress + ",false)", cameraProgress));
                int captured = i + 1;
                int totalSnapshots = localSnapshotCount + transitionSnapshotCount + 1;
                int prepProgress = Math.round(captured * 55f / totalSnapshots);
                updateLoadingProgress(prepProgress, "Preparing map frames... " + captured + " / " + totalSnapshots + " (" + prepProgress + "%)");
            }
            for (int i = 0; i < transitionSnapshotCount; i++) {
                float transitionProgress = i / (float) (transitionSnapshotCount - 1);
                mapSnapshots.add(captureMapFrame(route, transitionProgress, false,
                        "setTransition(" + transitionProgress + ")", .78f + .12f * transitionProgress));
                int captured = localSnapshotCount + i + 1;
                int totalSnapshots = localSnapshotCount + transitionSnapshotCount + 1;
                int prepProgress = Math.round(captured * 55f / totalSnapshots);
                updateLoadingProgress(prepProgress, "Preparing map frames... " + captured + " / " + totalSnapshots + " (" + prepProgress + "%)");
            }
            mapSnapshots.add(captureMapFrame(route, 1f, true, "setCamera(1,true)", 1f));
            runOnUiThread(() -> mapView.evaluateJavascript(
                    "marker.setOpacity(1);line.setStyle({opacity:1});full.setStyle({opacity:1});document.body.classList.remove('exporting');document.getElementById('panel').style.display='block'",
                    null));
            int totalSnapshots = localSnapshotCount + transitionSnapshotCount + 1;
            updateLoadingProgress(55, "Preparing map frames... " + totalSnapshots + " / " + totalSnapshots + " (55%)");
            encoder.start();
            encoderStarted = true;
            muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MuxerState muxerState = new MuxerState(muxer);
            String periodLabel = dateFormat.format(from.getTime()) + " - " + dateFormat.format(to.getTime());
            long videoStartNanos = System.nanoTime();
            for (int frame = 0; frame < totalFrames; frame++) {
                if (destroyed || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Video generation was cancelled");
                }
                float progress = frame / (float) (totalFrames - 1);
                Canvas canvas = surface.lockCanvas(null);
                try {
                    drawVideoFrame(canvas, route, progress, frame, mapSnapshots, characterSprite, outputScale, periodLabel,
                            localSnapshotCount, transitionSnapshotCount);
                } finally {
                    surface.unlockCanvasAndPost(canvas);
                }
                drainEncoder(encoder, info, muxerState, false);
                long targetNanos = videoStartNanos + (frame + 1L) * 1_000_000_000L / VIDEO_FPS;
                long remainingNanos = targetNanos - System.nanoTime();
                if (remainingNanos > 0) {
                    Thread.sleep(remainingNanos / 1_000_000L, (int) (remainingNanos % 1_000_000L));
                }
                if (frame % VIDEO_FPS == 0 || frame == totalFrames - 1) {
                    int seconds = Math.round(frame / (float) Math.max(1, totalFrames - 1) * VIDEO_SECONDS);
                    int encodeProgress = 55 + Math.round((frame + 1) * 45f / totalFrames);
                    updateLoadingProgress(encodeProgress, "Preparing MP4 video... " + seconds + " / " + VIDEO_SECONDS + " sec (" + encodeProgress + "%)");
                }
            }
            encoder.signalEndOfInputStream();
            drainEncoder(encoder, info, muxerState, true);
        } finally {
            if (surface != null) surface.release();
            if (encoderStarted) encoder.stop();
            encoder.release();
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (Exception ignored) {
                }
                muxer.release();
            }
            for (MapSnapshot snapshot : mapSnapshots) {
                if (snapshot.bitmap != null && !snapshot.bitmap.isRecycled()) snapshot.bitmap.recycle();
            }
            if (characterSprite != null && !characterSprite.isRecycled()) characterSprite.recycle();
        }
    }

    private void drainEncoder(MediaCodec encoder, MediaCodec.BufferInfo info, MuxerState muxerState, boolean endOfStream) {
        while (true) {
            int outputIndex = encoder.dequeueOutputBuffer(info, endOfStream ? 10_000 : 0);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return;
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxerState.trackIndex = muxerState.muxer.addTrack(encoder.getOutputFormat());
                muxerState.muxer.start();
                muxerState.started = true;
            } else if (outputIndex >= 0) {
                ByteBuffer data = encoder.getOutputBuffer(outputIndex);
                if (data != null && info.size > 0 && muxerState.started) {
                    data.position(info.offset);
                    data.limit(info.offset + info.size);
                    muxerState.muxer.writeSampleData(muxerState.trackIndex, data, info);
                }
                encoder.releaseOutputBuffer(outputIndex, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }
    }

    private MapSnapshot captureMapFrame(List<RoutePoint> route, float progress, boolean world,
                                        String cameraCommand, float routeProgress) throws InterruptedException {
        if (destroyed || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Map capture was cancelled");
        }
        AtomicReference<Bitmap> result = new AtomicReference<>();
        AtomicReference<MapProjection> projection = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        runOnUiThread(() -> {
            if (destroyed) {
                latch.countDown();
                return;
            }
            int[] scrollLocation = new int[2];
            int[] mapLocation = new int[2];
            scrollView.getLocationOnScreen(scrollLocation);
            mapView.getLocationOnScreen(mapLocation);
            int targetScroll = scrollView.getScrollY() + mapLocation[1] - scrollLocation[1];
            scrollView.scrollTo(0, Math.max(0, targetScroll));
            String routeVisibility = "if(marker)marker.setOpacity(0);if(line)line.setStyle({opacity:0});if(full)full.setStyle({opacity:0})";
            String captureState = "document.body.classList.add('exporting');if(raf){cancelAnimationFrame(raf);raf=0;}"
                    + cameraCommand + ";setProgress(" + routeProgress + ",false);" + routeVisibility
                    + ";document.getElementById('panel').style.display='none';({centerLat:map.getCenter().lat,centerLng:map.getCenter().lng,zoom:map.getZoom(),mapWidth:map.getSize().x,mapHeight:map.getSize().y,points:routePoints.map(function(p){var q=map.latLngToContainerPoint([p.lat,p.lng]);return [q.x,q.y]})})";
            mapView.evaluateJavascript(captureState, value -> {
                if (destroyed) {
                    latch.countDown();
                    return;
                }
                try {
                    projection.set(MapProjection.from(value));
                } catch (Exception ignored) {
                }
                mapView.postDelayed(() -> {
                if (destroyed) {
                    latch.countDown();
                    return;
                }
                int[] location = new int[2];
                mapView.getLocationOnScreen(location);
                int width = Math.min(mapView.getWidth(), getWindow().getDecorView().getWidth() - location[0]);
                int height = Math.min(mapView.getHeight(), getWindow().getDecorView().getHeight() - location[1]);
                if (width <= 0 || height <= 0) {
                    latch.countDown();
                    return;
                }
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
                // PixelCopy can capture a blank WebView layer while tiles are still
                // compositing. The WebView is software-rendered, so draw its actual
                // content into the bitmap after the tile settle delay instead.
                mapView.draw(new Canvas(bitmap));
                result.set(bitmap);
                latch.countDown();
                }, world ? 1400 : 1000);
            });
        });
        if (!latch.await(5000, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("Map tiles did not settle in time");
        }
        if (destroyed || Thread.currentThread().isInterrupted()) {
            Bitmap bitmap = result.get();
            if (bitmap != null) bitmap.recycle();
            throw new InterruptedException("Map capture was cancelled");
        }
        if (result.get() == null) throw new IllegalStateException("Map frame capture returned no image");
        MapSnapshot camera = cameraFor(route, routeProgress, world);
        MapProjection actualProjection = projection.get();
        if (actualProjection != null) {
            camera = new MapSnapshot(result.get(), actualProjection.centerLat, actualProjection.centerLng,
                    (int) Math.round(actualProjection.zoom), world, actualProjection.x, actualProjection.y,
                    actualProjection.mapWidth, actualProjection.mapHeight);
            return camera;
        }
        return new MapSnapshot(result.get(), camera.centerLat, camera.centerLng, camera.zoom, camera.world);
    }

    private void drawVideoFrame(Canvas canvas, List<RoutePoint> route, float progress, int animationFrame,
                                List<MapSnapshot> snapshots, Bitmap characterSprite, float outputScale,
                                String periodLabel, int localSnapshotCount, int transitionSnapshotCount) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.drawColor(0xffdbeafe);
        canvas.save();
        canvas.scale(outputScale, outputScale);
        RectF mapRect = new RectF(0, 210, VIDEO_WIDTH, 1050);
        List<MapSnapshot> localSnapshots = snapshots.subList(0, localSnapshotCount);
        List<MapSnapshot> transitionSnapshots = snapshots.subList(localSnapshotCount, localSnapshotCount + transitionSnapshotCount);
        MapSnapshot worldSnapshot = snapshots.get(localSnapshotCount + transitionSnapshotCount);
        float localPosition = Math.min(progress, .85f) / .85f * (localSnapshots.size() - 1);
        int localIndex = Math.min(localSnapshots.size() - 1, Math.max(0, (int) Math.floor(localPosition)));
        MapSnapshot localSnapshot = localSnapshots.get(localIndex);
        MapSnapshot frameSnapshot;
        if (progress >= .90f) {
            frameSnapshot = worldSnapshot;
        } else if (progress >= .78f) {
            float transitionPosition = (progress - .78f) / .12f * (transitionSnapshots.size() - 1);
            int transitionIndex = Math.min(transitionSnapshots.size() - 1, Math.max(0, Math.round(transitionPosition)));
            frameSnapshot = transitionSnapshots.get(transitionIndex);
        } else {
            frameSnapshot = localSnapshot;
        }
        if (frameSnapshot.bitmap != null) {
            drawMapBitmap(canvas, frameSnapshot.bitmap, mapRect, paint);
        } else if (localSnapshot.bitmap != null) {
            drawMapBitmap(canvas, localSnapshot.bitmap, mapRect, paint);
        } else {
            paint.setColor(0xfff8fafc);
            paint.setStrokeWidth(2);
            for (int y = 210; y < 1050; y += 80) canvas.drawLine(0, y, VIDEO_WIDTH, y, paint);
            for (int x = 0; x < VIDEO_WIDTH; x += 80) canvas.drawLine(x, 210, x, 1050, paint);
        }

        RouteLocation location = locationAt(route, progress);
        int currentIndex = location.index;
        float fraction = location.fraction;
        RectF plot = mapRect;
        float[] currentPos = projectAt(currentIndex, frameSnapshot, plot, route);
        float[] nextPos = projectAt(Math.min(route.size() - 1, currentIndex + 1), frameSnapshot, plot, route);
        float x = currentPos[0] + (nextPos[0] - currentPos[0]) * fraction;
        float y = currentPos[1] + (nextPos[1] - currentPos[1]) * fraction;
        canvas.save();
        canvas.clipRect(mapRect);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(6);
        paint.setColor(0xff0ea5e9);
        if (progress < .78f) {
            canvas.drawPath(routePath(route, progress, localSnapshot, plot, 0), paint);
        } else if (progress < .90f) {
            canvas.drawPath(routePath(route, progress, frameSnapshot, plot, 0), paint);
        } else {
            canvas.drawPath(routePath(route, 1f, worldSnapshot, plot, 0), paint);
        }
        if (progress < .86f) {
            drawVehicle(canvas, x, y, nextPos[0] - currentPos[0], animationFrame, characterSprite);
        }
        canvas.restore();

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff0f172a);
        canvas.drawRoundRect(new RectF(48, 52, VIDEO_WIDTH - 48, 178), 30, 30, paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(38);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        canvas.drawText("A journey in motion", 82, 106, paint);
        paint.setTextSize(25);
        paint.setColor(0xffbfdbfe);
        canvas.drawText(mapDateFormat.format(new Date(route.get(currentIndex).time)), 82, 148, paint);
        paint.setTextSize(20);
        paint.setColor(0xffdbeafe);
        canvas.drawText("Period " + periodLabel, 82, 170, paint);

        paint.setColor(0xff0f172a);
        paint.setTextSize(30);
        canvas.drawText("A moving story built from photo memories", 56, VIDEO_HEIGHT - 144, paint);
        paint.setColor(0xff64748b);
        paint.setTextSize(23);
        canvas.drawText("Created locally from photo EXIF metadata", 56, VIDEO_HEIGHT - 104, paint);

        paint.setColor(0x220f172a);
        canvas.drawRoundRect(new RectF(56, VIDEO_HEIGHT - 70, VIDEO_WIDTH - 56, VIDEO_HEIGHT - 52), 99, 99, paint);
        paint.setColor(0xff38bdf8);
        canvas.drawRoundRect(new RectF(56, VIDEO_HEIGHT - 70, 56 + (VIDEO_WIDTH - 112) * progress, VIDEO_HEIGHT - 52), 99, 99, paint);
        canvas.restore();
    }

    private void drawMapBitmap(Canvas canvas, Bitmap bitmap, RectF destination, Paint paint) {
        canvas.drawBitmap(bitmap, sourceRect(bitmap, destination), destination, paint);
    }

    private void drawVehicle(Canvas canvas, float x, float y, float dx, int animationFrame, Bitmap sprite) {
        if (sprite == null) return;
        canvas.save();
        canvas.translate(x, y);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        int frame = (animationFrame / 5) % CHARACTER_FRAME_LEFT.length;
        Rect source = new Rect(
                Math.min(sprite.getWidth(), CHARACTER_FRAME_LEFT[frame]),
                0,
                Math.min(sprite.getWidth(), CHARACTER_FRAME_RIGHT[frame]),
                sprite.getHeight()
        );
        canvas.drawBitmap(sprite, source, new RectF(-18, -72, 18, 0), paint);
        canvas.restore();
    }

    private Bitmap loadCharacterSprite() throws Exception {
        try (InputStream input = getAssets().open("characters/satgat-walk-8.png")) {
            Bitmap sprite = BitmapFactory.decodeStream(input);
            if (sprite == null) throw new IllegalStateException("Could not load route character sprite");
            return sprite;
        }
    }

    private Path routePath(List<RoutePoint> route, float progress, MapSnapshot snapshot, RectF plot, int startIndex) {
        Path path = new Path();
        RouteLocation location = locationAt(route, progress);
        int endIndex = location.index;
        int firstIndex = Math.min(startIndex, endIndex);
        for (int i = firstIndex; i <= endIndex; i++) {
            float[] pos = projectAt(i, snapshot, plot, route);
            if (i == firstIndex) path.moveTo(pos[0], pos[1]);
            else path.lineTo(pos[0], pos[1]);
        }
        if (progress < 1f && endIndex < route.size() - 1 && endIndex >= firstIndex) {
            float[] from = projectAt(endIndex, snapshot, plot, route);
            float[] to = projectAt(endIndex + 1, snapshot, plot, route);
            path.lineTo(from[0] + (to[0] - from[0]) * location.fraction,
                    from[1] + (to[1] - from[1]) * location.fraction);
        }
        return path;
    }

    private float[] projectAt(int index, MapSnapshot snapshot, RectF plot, List<RoutePoint> route) {
        if (snapshot.projectedX != null && snapshot.projectedY != null
                && index >= 0 && index < snapshot.projectedX.length
                && snapshot.mapWidth > 0f && snapshot.mapHeight > 0f) {
            float bitmapWidth = snapshot.bitmap == null ? plot.width() : snapshot.bitmap.getWidth();
            float bitmapHeight = snapshot.bitmap == null ? plot.height() : snapshot.bitmap.getHeight();
            Rect source = sourceRect(snapshot.bitmap, plot);
            float bitmapX = snapshot.projectedX[index] * bitmapWidth / snapshot.mapWidth;
            float bitmapY = snapshot.projectedY[index] * bitmapHeight / snapshot.mapHeight;
            return new float[]{
                    plot.left + (bitmapX - source.left) * plot.width() / source.width(),
                    plot.top + (bitmapY - source.top) * plot.height() / source.height()
            };
        }
        return project(route.get(index), snapshot, plot, route);
    }

    private RouteLocation locationAt(List<RoutePoint> route, float progress) {
        if (route.size() < 2) return new RouteLocation(0, 0f);
        float clamped = Math.max(0f, Math.min(1f, progress));
        float exact = clamped * (route.size() - 1);
        int index = Math.min(route.size() - 2, (int) Math.floor(exact));
        return new RouteLocation(index, exact - index);
    }

    private float[] project(RoutePoint point, MapSnapshot snapshot, RectF plot, List<RoutePoint> route) {
        double scale = 256d * Math.pow(2d, snapshot.zoom);
        double pointX = ((point.lng + 180d) / 360d) * scale;
        double pointSin = Math.sin(Math.toRadians(Math.max(-85.05112878, Math.min(85.05112878, point.lat))));
        double pointY = (0.5d - Math.log((1d + pointSin) / (1d - pointSin)) / (4d * Math.PI)) * scale;
        double centerX = ((snapshot.centerLng + 180d) / 360d) * scale;
        double centerSin = Math.sin(Math.toRadians(Math.max(-85.05112878, Math.min(85.05112878, snapshot.centerLat))));
        double centerY = (0.5d - Math.log((1d + centerSin) / (1d - centerSin)) / (4d * Math.PI)) * scale;
        double deltaX = pointX - centerX;
        while (deltaX > scale / 2d) deltaX -= scale;
        while (deltaX < -scale / 2d) deltaX += scale;
        float bitmapWidth = snapshot.bitmap == null ? plot.width() : snapshot.bitmap.getWidth();
        float bitmapHeight = snapshot.bitmap == null ? plot.height() : snapshot.bitmap.getHeight();
        Rect source = sourceRect(snapshot.bitmap, plot);
        float bitmapX = bitmapWidth / 2f + (float) deltaX;
        float bitmapY = bitmapHeight / 2f + (float) (pointY - centerY);
        return new float[]{
                plot.left + (bitmapX - source.left) * plot.width() / source.width(),
                plot.top + (bitmapY - source.top) * plot.height() / source.height()
        };
    }

    private Rect sourceRect(Bitmap bitmap, RectF destination) {
        if (bitmap == null) return new Rect(0, 0, Math.round(destination.width()), Math.round(destination.height()));
        float sourceAspect = bitmap.getWidth() / (float) bitmap.getHeight();
        float destinationAspect = destination.width() / destination.height();
        Rect source = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        if (sourceAspect > destinationAspect) {
            int cropWidth = Math.round(bitmap.getHeight() * destinationAspect);
            int left = (bitmap.getWidth() - cropWidth) / 2;
            source.set(left, 0, left + cropWidth, bitmap.getHeight());
        } else if (sourceAspect < destinationAspect) {
            int cropHeight = Math.round(bitmap.getWidth() / destinationAspect);
            int top = (bitmap.getHeight() - cropHeight) / 2;
            source.set(0, top, bitmap.getWidth(), top + cropHeight);
        }
        return source;
    }

    private Bounds bounds(List<RoutePoint> route) {
        Bounds b = new Bounds();
        for (RoutePoint point : route) {
            b.minLat = Math.min(b.minLat, point.lat);
            b.maxLat = Math.max(b.maxLat, point.lat);
            b.minLng = Math.min(b.minLng, point.lng);
            b.maxLng = Math.max(b.maxLng, point.lng);
        }
        double latPad = Math.max((b.maxLat - b.minLat) * .12, .02);
        double lngPad = Math.max((b.maxLng - b.minLng) * .12, .02);
        b.minLat -= latPad;
        b.maxLat += latPad;
        b.minLng -= lngPad;
        b.maxLng += lngPad;
        return b;
    }

    private int localZoom(List<RoutePoint> route) {
        double minLat = Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE;
        double minLng = Double.MAX_VALUE;
        double maxLng = -Double.MAX_VALUE;
        for (RoutePoint point : route) {
            minLat = Math.min(minLat, point.lat);
            maxLat = Math.max(maxLat, point.lat);
            minLng = Math.min(minLng, point.lng);
            maxLng = Math.max(maxLng, point.lng);
        }
        double span = Math.max(maxLat - minLat, maxLng - minLng);
        if (span > 90) return 3;
        if (span > 30) return 4;
        if (span > 8) return 5;
        if (span > 2) return 7;
        if (span > .5) return 9;
        if (span > .1) return 11;
        if (span > .02) return 13;
        if (span > .005) return 15;
        return 17;
    }

    private MapSnapshot cameraFor(List<RoutePoint> route, float progress, boolean world) {
        if (world) {
            return new MapSnapshot(null, 20d, 0d, 1, true);
        }
        float exact = Math.max(0f, Math.min(1f, progress)) * (route.size() - 1);
        int index = Math.min(route.size() - 1, (int) Math.floor(exact));
        RoutePoint from = route.get(index);
        RoutePoint to = route.get(Math.min(route.size() - 1, index + 1));
        float fraction = exact - index;
        return new MapSnapshot(null, from.lat + (to.lat - from.lat) * fraction, from.lng + (to.lng - from.lng) * fraction, localZoom(route), false);
    }

    private void renderMapRoute(List<RoutePoint> route) {
        if (!mapReady) {
            pendingMapRoute = new ArrayList<>(route);
            return;
        }
        mapView.evaluateJavascript("map.invalidateSize(false);renderRoute(" + routeJson(route) + ")", null);
    }

    private String routeJson(List<RoutePoint> route) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < route.size(); i++) {
            RoutePoint p = route.get(i);
            if (i > 0) json.append(',');
            json.append("{lat:").append(p.lat)
                    .append(",lng:").append(p.lng)
                    .append(",time:").append(p.time)
                    .append(",label:\"").append(mapDateFormat.format(new Date(p.time))).append("\"}");
        }
        return json.append(']').toString();
    }

    private String mapHtml() {
        return "<!doctype html><html><head>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'>"
                + "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'>"
                + "<style>html,body,#map{height:100%;margin:0;background:#dbeafe}.leaflet-container{font:14px system-ui}.panel{position:absolute;z-index:500;left:14px;right:14px;top:14px;background:rgba(25,31,40,.92);color:white;padding:12px 14px;border-radius:16px;font:800 14px system-ui;box-shadow:0 10px 24px rgba(25,31,40,.16)}body.exporting .panel{display:none!important}.panel small{display:block;margin-top:4px;color:#c9d8ee;font-weight:700}.progress{height:5px;margin-top:10px;background:rgba(255,255,255,.16);border-radius:999px;overflow:hidden}.bar{height:100%;width:0;background:#5b9bff;border-radius:999px}.vehicle{border:0;background:transparent;filter:drop-shadow(0 2px 2px rgba(15,23,42,.38))}.route-character-sprite{display:block;width:32px;height:64px;background-image:url('characters/satgat-walk-8.png');background-repeat:no-repeat;background-size:800% 100%;image-rendering:pixelated;animation:route-character-walk 1200ms steps(1,end) infinite}@keyframes route-character-walk{0%{background-position:0 0}12.5%{background-position:14.286% 0}25%{background-position:28.571% 0}37.5%{background-position:42.857% 0}50%{background-position:57.143% 0}62.5%{background-position:71.429% 0}75%{background-position:85.714% 0}87.5%{background-position:100% 0}100%{background-position:0 0}}</style>"
                + "</head><body><div id='map'></div><div id='panel' class='panel'><span id='place'>Route preview appears here</span><small id='time'>Waiting for photo GPS points</small><div class='progress'><div class='bar' id='bar'></div></div></div>"
                + "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>"
                + "<script>"
                + "var map=L.map('map',{zoomControl:false,attributionControl:true,preferCanvas:false});"
                + "L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:18,attribution:'&copy; OpenStreetMap contributors'}).addTo(map);"
                + "map.setView([0,0],2);var full,line,marker,raf,lastPan=0,routePoints=[],latlngs=[],localZoom=4,cameraMode='local';"
                + "function ll(p){return [p.lat,p.lng]}"
                + "function vehicleIcon(){return L.divIcon({className:'vehicle',iconSize:[32,64],iconAnchor:[16,58],html:'<span class=\"route-character-sprite\" role=\"img\" aria-label=\"route character\"></span>'})}"
                + "function routeZoom(points){var lats=points.map(function(p){return p.lat}),lngs=points.map(function(p){return p.lng}),span=Math.max(Math.max.apply(null,lats)-Math.min.apply(null,lats),Math.max.apply(null,lngs)-Math.min.apply(null,lngs));return span>90?3:span>30?4:span>8?5:span>2?7:span>.5?9:span>.1?11:span>.02?13:span>.005?15:17}"
                + "function routeAt(t){var clamped=Math.max(0,Math.min(1,t)),exact=clamped*(routePoints.length-1),end=Math.min(routePoints.length-2,Math.floor(exact)),cur=routePoints[end],next=routePoints[Math.min(routePoints.length-1,end+1)],f=exact-end;return {end:end,cur:cur,next:next,f:f,point:[cur.lat+(next.lat-cur.lat)*f,cur.lng+(next.lng-cur.lng)*f]}}"
                + "function setCamera(t,world){if(!routePoints.length)return;var a=routeAt(t),point=a.point;if(world){map.fitBounds(L.latLngBounds(latlngs),{padding:[30,30],maxZoom:2,animate:false});cameraMode='world'}else{map.setView(point,localZoom,{animate:false});cameraMode='local'}}"
                + "function setTransition(t){if(!routePoints.length)return;var start=routeAt(.78).point,bounds=L.latLngBounds(latlngs),end=bounds.getCenter(),worldZoom=Math.min(2,map.getBoundsZoom(bounds,false,L.point(30,30))),lat=start[0]+(end.lat-start[0])*t,lng=start[1]+(end.lng-start[1])*t,zoom=localZoom+(worldZoom-localZoom)*t;map.setView([lat,lng],zoom,{animate:false});cameraMode='transition'}"
                + "function setProgress(t,follow){if(!routePoints.length)return;var a=routeAt(t),point=a.point,visible=latlngs.slice(0,a.end+1);visible.push(point);line.setLatLngs(visible);marker.setLatLng(point);document.getElementById('time').textContent=a.cur.label;document.getElementById('bar').style.width=(Math.max(0,Math.min(1,t))*100).toFixed(1)+'%';if(follow&&performance.now()-lastPan>80){if(t>=.86&&cameraMode!=='world'){map.fitBounds(L.latLngBounds(latlngs),{padding:[30,30],maxZoom:2,animate:true,duration:.8});cameraMode='world'}else if(t<.86){map.setView(point,localZoom,{animate:false});cameraMode='local'}lastPan=performance.now()}}"
                + "function renderRoute(points){document.getElementById('place').textContent=points.length+' route points found';"
                + "if(full)map.removeLayer(full);if(line)map.removeLayer(line);if(marker)map.removeLayer(marker);if(raf)cancelAnimationFrame(raf);"
                + "routePoints=points;latlngs=points.map(ll);"
                + "full=L.polyline(latlngs,{color:'#0ea5e9',opacity:1,weight:5,lineCap:'round',lineJoin:'round'}).addTo(map);"
                + "line=L.polyline([], {color:'#0ea5e9',weight:5,lineCap:'round',lineJoin:'round'}).addTo(map);"
                + "marker=L.marker(ll(points[0]),{icon:vehicleIcon(),interactive:false}).addTo(map);"
                + "localZoom=routeZoom(points);setCamera(0,false);var start=0,duration=10000;"
                + "setProgress(0,true);function step(ts){if(!start)start=ts;var t=Math.min((ts-start)/duration,1);setProgress(t,true);if(t<1)raf=requestAnimationFrame(step)}"
                + "raf=requestAnimationFrame(step)}"
                + "</script></body></html>";
    }

    private static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double rad = Math.PI / 180;
        double dLat = (lat2 - lat1) * rad;
        double dLng = (lng2 - lng1) * rad;
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371 * 2 * Math.asin(Math.sqrt(a));
    }

    private static class RoutePoint {
        final double lat;
        final double lng;
        final long time;

        RoutePoint(double lat, double lng, long time) {
            this.lat = lat;
            this.lng = lng;
            this.time = time;
        }
    }

    private static class RouteLocation {
        final int index;
        final float fraction;

        RouteLocation(int index, float fraction) {
            this.index = index;
            this.fraction = fraction;
        }
    }

    private static class ScanResult {
        final List<RoutePoint> points;
        final int total;
        final int withGps;

        ScanResult(List<RoutePoint> points, int total, int withGps) {
            this.points = points;
            this.total = total;
            this.withGps = withGps;
        }
    }

    private static class Bounds {
        double minLat = Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE;
        double minLng = Double.MAX_VALUE;
        double maxLng = -Double.MAX_VALUE;
    }

    private static class MapSnapshot {
        final Bitmap bitmap;
        final double centerLat;
        final double centerLng;
        final int zoom;
        final boolean world;
        final float[] projectedX;
        final float[] projectedY;
        final float mapWidth;
        final float mapHeight;

        MapSnapshot(Bitmap bitmap, double centerLat, double centerLng, int zoom, boolean world) {
            this(bitmap, centerLat, centerLng, zoom, world, null, null, 0f, 0f);
        }

        MapSnapshot(Bitmap bitmap, double centerLat, double centerLng, int zoom, boolean world,
                    float[] projectedX, float[] projectedY, float mapWidth, float mapHeight) {
            this.bitmap = bitmap;
            this.centerLat = centerLat;
            this.centerLng = centerLng;
            this.zoom = zoom;
            this.world = world;
            this.projectedX = projectedX;
            this.projectedY = projectedY;
            this.mapWidth = mapWidth;
            this.mapHeight = mapHeight;
        }
    }

    private static class MapProjection {
        final double centerLat;
        final double centerLng;
        final double zoom;
        final float mapWidth;
        final float mapHeight;
        final float[] x;
        final float[] y;

        MapProjection(double centerLat, double centerLng, double zoom, float mapWidth, float mapHeight, float[] x, float[] y) {
            this.centerLat = centerLat;
            this.centerLng = centerLng;
            this.zoom = zoom;
            this.mapWidth = mapWidth;
            this.mapHeight = mapHeight;
            this.x = x;
            this.y = y;
        }

        static MapProjection from(String value) throws Exception {
            JSONObject object = new JSONObject(value);
            JSONArray points = object.getJSONArray("points");
            float[] x = new float[points.length()];
            float[] y = new float[points.length()];
            for (int i = 0; i < points.length(); i++) {
                JSONArray point = points.getJSONArray(i);
                x[i] = (float) point.getDouble(0);
                y[i] = (float) point.getDouble(1);
            }
            return new MapProjection(
                    object.getDouble("centerLat"),
                    object.getDouble("centerLng"),
                    object.getDouble("zoom"),
                    (float) object.getDouble("mapWidth"),
                    (float) object.getDouble("mapHeight"),
                    x,
                    y
            );
        }
    }

    private static class MuxerState {
        final MediaMuxer muxer;
        boolean started;
        int trackIndex = -1;

        MuxerState(MediaMuxer muxer) {
            this.muxer = muxer;
        }
    }

}
