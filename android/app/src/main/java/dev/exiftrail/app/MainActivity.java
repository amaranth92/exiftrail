package dev.exiftrail.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ContentValues;
import android.content.ContentUris;
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
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.ExifInterface;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.Surface;
import android.view.Gravity;
import android.view.View;
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

public class MainActivity extends Activity {
    private static final int REQ_PHOTOS = 92;
    private static final double MIN_POINT_GAP_KM = .05;
    private static final double MAX_REASONABLE_SPEED_KMH = 1000d;
    private static final int VIDEO_WIDTH = 720;
    private static final int VIDEO_HEIGHT = 1280;
    private static final int VIDEO_FPS = 30;
    private static final int VIDEO_SECONDS = 10;

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
    private ScrollView scrollView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        from.set(currentYear, Calendar.JANUARY, 1, 0, 0, 0);
        to.setTimeInMillis(System.currentTimeMillis());
        to.set(Calendar.HOUR_OF_DAY, 23);
        to.set(Calendar.MINUTE, 59);
        to.set(Calendar.SECOND, 59);

        setContentView(buildUi());
        refreshDates();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scrollView = scroll;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), 0, dp(16), dp(32));
        root.setBackgroundColor(Color.WHITE);
        scroll.addView(root);

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
        TextView privacyNote = text("Private by default", 13, 0xff8b95a1, true);
        topbar.addView(privacyNote);
        root.addView(topbar, new LinearLayout.LayoutParams(-1, dp(72)));

        TextView eyebrow = text("YOUR MEMORIES, IN MOTION", 13, 0xff3182f6, true);
        eyebrow.setLetterSpacing(.04f);
        LinearLayout.LayoutParams eyebrowLp = new LinearLayout.LayoutParams(-1, -2);
        eyebrowLp.setMargins(0, dp(22), 0, dp(10));
        root.addView(eyebrow, eyebrowLp);

        TextView title = text("Turn photo memories into a route.", 42, 0xff191f28, true);
        title.setLineSpacing(0, 1.02f);
        root.addView(title);

        TextView lead = text("Allow photos, then ExifTrail turns their time and location metadata into a moving map video.", 19, 0xff4e5968, false);
        lead.setLineSpacing(0, 1.35f);
        LinearLayout.LayoutParams leadLp = new LinearLayout.LayoutParams(-1, -2);
        leadLp.setMargins(0, dp(16), 0, dp(22));
        root.addView(lead, leadLp);

        LinearLayout consent = new LinearLayout(this);
        consent.setOrientation(LinearLayout.VERTICAL);
        consent.setPadding(dp(16), dp(14), dp(16), dp(14));
        consent.setBackground(rounded(0xffffffff, 0xffe5e8eb, 16));
        TextView consentTitle = text("No Google Timeline required.", 15, 0xff191f28, true);
        consent.addView(consentTitle);
        TextView consentBody = text("Your photos stay on this device. ExifTrail reads capture time and GPS, sorts them in order, and prepares a vertical video.", 14, 0xff6b7684, false);
        consentBody.setLineSpacing(0, 1.35f);
        LinearLayout.LayoutParams consentBodyLp = new LinearLayout.LayoutParams(-1, -2);
        consentBodyLp.setMargins(0, dp(6), 0, 0);
        consent.addView(consentBody, consentBodyLp);
        LinearLayout.LayoutParams consentLp = new LinearLayout.LayoutParams(-1, -2);
        consentLp.setMargins(0, 0, 0, dp(18));
        root.addView(consent, consentLp);

        LinearLayout dates = new LinearLayout(this);
        dates.setOrientation(LinearLayout.HORIZONTAL);
        dates.setGravity(Gravity.CENTER);
        dates.setWeightSum(2);
        root.addView(dates);

        fromButton = secondaryButton();
        toButton = secondaryButton();
        dates.addView(fromButton, weighted());
        dates.addView(toButton, weighted());
        fromButton.setOnClickListener(v -> pickDate(from, this::refreshDates));
        toButton.setOnClickListener(v -> pickDate(to, this::refreshDates));

        createButton = primaryButton("Allow photos and create video");
        createButton.setOnClickListener(v -> startRouteBuild());
        LinearLayout.LayoutParams createLp = new LinearLayout.LayoutParams(-1, dp(56));
        createLp.setMargins(0, 0, 0, dp(10));
        root.addView(createButton, createLp);

        saveButton = secondaryActionButton("Save video");
        saveButton.setEnabled(false);
        saveButton.setOnClickListener(v -> saveVideo());
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(-1, dp(56));
        saveLp.setMargins(0, 0, 0, dp(12));
        root.addView(saveButton, saveLp);

        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        statusCard.setBackground(rounded(0xffffffff, 0xffe5e8eb, 16));
        status = text("Allow photo access, then ExifTrail builds a route video from time and GPS metadata.", 15, 0xff191f28, true);
        status.setLineSpacing(0, 1.3f);
        statusCard.addView(status);

        scanProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        scanProgress.setMax(100);
        scanProgress.setProgressTintList(ColorStateList.valueOf(0xff3182f6));
        scanProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(-1, dp(10));
        progressLp.setMargins(0, dp(12), 0, 0);
        statusCard.addView(scanProgress, progressLp);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.setMargins(0, dp(4), 0, dp(20));
        root.addView(statusCard, statusLp);

        mapView = new WebView(this);
        WebSettings settings = mapView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        // The exported MP4 is drawn from this WebView. Software rendering keeps
        // WebView.draw() readable on devices where hardware tiles are not exposed.
        mapView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        mapView.setWebViewClient(new WebViewClient());
        mapView.loadDataWithBaseURL("file:///android_asset/", mapHtml(), "text/html", "UTF-8", null);
        FrameLayout mapCard = new FrameLayout(this);
        mapCard.setBackground(rounded(0xffdbeafe, 0xffe5e8eb, 24));
        mapCard.setClipToOutline(true);
        // The map card is inside the 16dp horizontal root padding. Match the
        // capture aspect to that actual content width so export needs no crop.
        float density = getResources().getDisplayMetrics().density;
        int contentWidthDp = Math.round(getResources().getDisplayMetrics().widthPixels / density) - 32;
        int mapHeightDp = Math.round(contentWidthDp * 840f / 720f);
        mapCard.addView(mapView, new FrameLayout.LayoutParams(-1, dp(mapHeightDp)));
        LinearLayout.LayoutParams routeLp = new LinearLayout.LayoutParams(-1, dp(mapHeightDp));
        routeLp.setMargins(0, 0, 0, 0);
        root.addView(mapCard, routeLp);

        return scroll;
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
        createButton.setEnabled(false);
        saveButton.setEnabled(false);
        status.setText("Scanning photos in the selected date range...");
        scanProgress.setIndeterminate(true);
        scanProgress.setVisibility(View.VISIBLE);
        points.clear();

        new Thread(() -> {
            ScanResult result = queryPhotos();
            runOnUiThread(() -> {
                createButton.setEnabled(true);
                scanProgress.setIndeterminate(false);
                scanProgress.setProgress(0);
                scanProgress.setVisibility(View.GONE);
                points.clear();
                points.addAll(result.points);
                if (points.size() < 2) {
                    status.setText("Scanned " + result.total + " photos in range, found " + result.withGps + " with GPS. Try a wider range or enable camera location tags.");
                } else {
                    status.setText(points.size() + " route points found from " + result.total + " photos. Moving route preview is playing.");
                    saveButton.setEnabled(true);
                    renderMapRoute(points);
                }
            });
        }).start();
    }

    private void saveVideo() {
        if (points.size() < 2) {
            status.setText("Create a route first, then save the moving video.");
            return;
        }
        createButton.setEnabled(false);
        saveButton.setEnabled(false);
        status.setText("Saving MP4 video to Gallery...");

        List<RoutePoint> route = new ArrayList<>(points);
        new Thread(() -> {
            try {
                Uri uri = exportRouteVideo(route);
                runOnUiThread(() -> {
                    createButton.setEnabled(true);
                    saveButton.setEnabled(true);
                    status.setText("Saved moving route video to Gallery: " + uri);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    createButton.setEnabled(true);
                    saveButton.setEnabled(true);
                    status.setText("Video save failed: " + e.getMessage());
                });
            }
        }).start();
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
            runOnUiThread(() -> {
                scanProgress.setIndeterminate(false);
                scanProgress.setMax(Math.max(1, totalPhotos));
                scanProgress.setProgress(0);
            });
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
                    runOnUiThread(() -> {
                        scanProgress.setProgress(scanned);
                        status.setText("Scanning photos... " + scanned + "/" + totalPhotos + " checked, " + gps + " with GPS");
                    });
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

    private float[] readOriginalLatLng(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(MediaStore.setRequireOriginal(uri))) {
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

    private Uri exportRouteVideo(List<RoutePoint> route) throws Exception {
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

        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "rw")) {
            if (pfd == null) throw new IllegalStateException("Could not open video file");
            encodeRouteVideo(route, pfd);
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
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        MediaMuxer muxer = null;
        Surface surface = null;
        boolean encoderStarted = false;
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = encoder.createInputSurface();
            int totalFrames = VIDEO_SECONDS * VIDEO_FPS;
            Bitmap characterSprite = loadCharacterSprite();
            Thread.sleep(1200);
            List<MapSnapshot> mapSnapshots = new ArrayList<>();
            final int localSnapshotCount = 27;
            for (int i = 0; i < localSnapshotCount; i++) {
                float cameraProgress = .85f * i / (float) (localSnapshotCount - 1);
                mapSnapshots.add(captureMapFrame(route, cameraProgress, false));
                int captured = i + 1;
                runOnUiThread(() -> status.setText("Preparing map frames... " + captured + " / " + localSnapshotCount));
            }
            mapSnapshots.add(captureMapFrame(route, 1f, true));
            encoder.start();
            encoderStarted = true;
            muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MuxerState muxerState = new MuxerState(muxer);
            long videoStartNanos = System.nanoTime();
            for (int frame = 0; frame < totalFrames; frame++) {
                float progress = frame / (float) (totalFrames - 1);
                Canvas canvas = surface.lockCanvas(null);
                try {
                    drawVideoFrame(canvas, route, progress, frame, mapSnapshots, characterSprite);
                } finally {
                    surface.unlockCanvasAndPost(canvas);
                }
                drainEncoder(encoder, info, muxerState, false);
                long targetNanos = videoStartNanos + (frame + 1L) * 1_000_000_000L / VIDEO_FPS;
                long remainingNanos = targetNanos - System.nanoTime();
                if (remainingNanos > 0) {
                    Thread.sleep(remainingNanos / 1_000_000L, (int) (remainingNanos % 1_000_000L));
                }
                if (frame % VIDEO_FPS == 0) {
                    int seconds = Math.round(frame / (float) Math.max(1, totalFrames - 1) * VIDEO_SECONDS);
                    runOnUiThread(() -> status.setText("Saving MP4 video... " + seconds + " / " + VIDEO_SECONDS + " sec"));
                }
            }
            encoder.signalEndOfInputStream();
            drainEncoder(encoder, info, muxerState, true);
            for (MapSnapshot snapshot : mapSnapshots) {
                if (snapshot.bitmap != null) snapshot.bitmap.recycle();
            }
            characterSprite.recycle();
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

    private MapSnapshot captureMapFrame(List<RoutePoint> route, float progress, boolean world) throws InterruptedException {
        AtomicReference<Bitmap> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        runOnUiThread(() -> {
            int[] scrollLocation = new int[2];
            int[] mapLocation = new int[2];
            scrollView.getLocationOnScreen(scrollLocation);
            mapView.getLocationOnScreen(mapLocation);
            int targetScroll = scrollView.getScrollY() + mapLocation[1] - scrollLocation[1];
            scrollView.scrollTo(0, Math.max(0, targetScroll));
            String routeVisibility = "marker.setOpacity(1);line.setStyle({opacity:1});full.setStyle({opacity:0})";
            mapView.evaluateJavascript("setCamera(" + progress + "," + world + ");setProgress(" + progress + ",false);" + routeVisibility + ";panel.style.display='none'", ignored -> mapView.postDelayed(() -> {
                int[] location = new int[2];
                mapView.getLocationOnScreen(location);
                int width = Math.min(mapView.getWidth(), getWindow().getDecorView().getWidth() - location[0]);
                int height = Math.min(mapView.getHeight(), getWindow().getDecorView().getHeight() - location[1]);
                if (width <= 0 || height <= 0) {
                    mapView.evaluateJavascript("marker.setOpacity(1);line.setStyle({opacity:1});full.setStyle({opacity:1});panel.style.display='block'", null);
                    latch.countDown();
                    return;
                }
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                // PixelCopy can capture a blank WebView layer while tiles are still
                // compositing. The WebView is software-rendered, so draw its actual
                // content into the bitmap after the tile settle delay instead.
                mapView.draw(new Canvas(bitmap));
                result.set(bitmap);
                mapView.evaluateJavascript("marker.setOpacity(1);line.setStyle({opacity:1});full.setStyle({opacity:1});panel.style.display='block'", null);
                latch.countDown();
            }, world ? 1400 : 1000));
        });
        latch.await(5000, TimeUnit.MILLISECONDS);
        MapSnapshot camera = cameraFor(route, progress, world);
        return new MapSnapshot(result.get(), camera.centerLat, camera.centerLng, camera.zoom, camera.world);
    }

    private void drawVideoFrame(Canvas canvas, List<RoutePoint> route, float progress, int animationFrame, List<MapSnapshot> snapshots, Bitmap characterSprite) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.drawColor(0xffdbeafe);
        RectF mapRect = new RectF(0, 210, VIDEO_WIDTH, 1050);
        MapSnapshot worldSnapshot = snapshots.get(snapshots.size() - 1);
        List<MapSnapshot> localSnapshots = snapshots.subList(0, snapshots.size() - 1);
        int localIndex = Math.min(localSnapshots.size() - 1, Math.max(0, Math.round(Math.min(progress, .85f) / .85f * (localSnapshots.size() - 1))));
        MapSnapshot localSnapshot = localSnapshots.get(localIndex);
        if (progress >= .86f && worldSnapshot.bitmap != null) {
            drawMapBitmap(canvas, worldSnapshot.bitmap, mapRect, paint);
        } else if (progress >= .78f && worldSnapshot.bitmap != null && localSnapshot.bitmap != null) {
            paint.setAlpha((int) (255 * (1f - Math.min(1f, (progress - .78f) / .12f))));
            drawMapBitmap(canvas, localSnapshot.bitmap, mapRect, paint);
            paint.setAlpha((int) (255 * Math.min(1f, (progress - .78f) / .12f)));
            drawMapBitmap(canvas, worldSnapshot.bitmap, mapRect, paint);
            paint.setAlpha(255);
        } else if (localSnapshot.bitmap != null) {
            drawMapBitmap(canvas, localSnapshot.bitmap, mapRect, paint);
        } else {
            paint.setColor(0xfff8fafc);
            paint.setStrokeWidth(2);
            for (int y = 210; y < 1050; y += 80) canvas.drawLine(0, y, VIDEO_WIDTH, y, paint);
            for (int x = 0; x < VIDEO_WIDTH; x += 80) canvas.drawLine(x, 210, x, 1050, paint);
        }

        MapSnapshot frameSnapshot = progress >= .86f ? worldSnapshot : localSnapshot;
        int currentIndex = Math.max(0, Math.min(route.size() - 1, (int) Math.floor((route.size() - 1) * progress)));
        float exact = (route.size() - 1) * progress;
        float fraction = exact - currentIndex;
        RectF plot = mapRect;
        float[] currentPos = project(route.get(currentIndex), frameSnapshot, plot, route);
        float[] nextPos = project(route.get(Math.min(route.size() - 1, currentIndex + 1)), frameSnapshot, plot, route);
        float x = currentPos[0] + (nextPos[0] - currentPos[0]) * fraction;
        float y = currentPos[1] + (nextPos[1] - currentPos[1]) * fraction;
        canvas.save();
        canvas.clipRect(mapRect);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        // The world snapshot already contains Leaflet's complete route. Keep
        // native projection for local animation only so the final frame cannot
        // drift from the map renderer used by the preview.
        if (frameSnapshot.bitmap == null) {
            paint.setStrokeWidth(8);
            paint.setColor(0x660f172a);
            if (frameSnapshot.world) canvas.drawPath(routePath(route, 1f, frameSnapshot, plot, 0), paint);
            int trailStart = Math.max(0, currentIndex - 80);
            Path active = routePath(route, progress, frameSnapshot, plot, trailStart);
            paint.setStrokeWidth(10);
            paint.setColor(0xff0ea5e9);
            canvas.drawPath(active, paint);
        }
        if (frameSnapshot.bitmap == null) {
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
    }

    private void drawMapBitmap(Canvas canvas, Bitmap bitmap, RectF destination, Paint paint) {
        canvas.drawBitmap(bitmap, sourceRect(bitmap, destination), destination, paint);
    }

    private void drawVehicle(Canvas canvas, float x, float y, float dx, int animationFrame, Bitmap sprite) {
        if (sprite == null) return;
        canvas.save();
        canvas.translate(x, y);
        if (dx < 0) canvas.scale(-1f, 1f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        int frameWidth = sprite.getWidth() / 8;
        int frame = (animationFrame / 3) % 8;
        Rect source = new Rect(frame * frameWidth, 0, (frame + 1) * frameWidth, sprite.getHeight());
        canvas.drawBitmap(sprite, source, new RectF(-24, -48, 24, 48), paint);
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
        int endIndex = Math.min(route.size() - 1, Math.max(0, (int) Math.floor((route.size() - 1) * progress)));
        int firstIndex = Math.min(startIndex, endIndex);
        for (int i = firstIndex; i <= endIndex; i++) {
            float[] pos = project(route.get(i), snapshot, plot, route);
            if (i == firstIndex) path.moveTo(pos[0], pos[1]);
            else path.lineTo(pos[0], pos[1]);
        }
        if (progress < 1f && endIndex < route.size() - 1 && endIndex >= firstIndex) {
            float exact = (route.size() - 1) * progress;
            float fraction = exact - endIndex;
            float[] from = project(route.get(endIndex), snapshot, plot, route);
            float[] to = project(route.get(endIndex + 1), snapshot, plot, route);
            path.lineTo(from[0] + (to[0] - from[0]) * fraction, from[1] + (to[1] - from[1]) * fraction);
        }
        return path;
    }

    private float[] project(RoutePoint point, MapSnapshot snapshot, RectF plot, List<RoutePoint> route) {
        double scale = 256d * Math.pow(2d, snapshot.zoom);
        double pointX = ((point.lng + 180d) / 360d) * scale;
        double pointSin = Math.sin(Math.toRadians(Math.max(-85.05112878, Math.min(85.05112878, point.lat))));
        double pointY = (0.5d - Math.log((1d + pointSin) / (1d - pointSin)) / (4d * Math.PI)) * scale;
        double centerX = ((snapshot.centerLng + 180d) / 360d) * scale;
        double centerSin = Math.sin(Math.toRadians(Math.max(-85.05112878, Math.min(85.05112878, snapshot.centerLat))));
        double centerY = (0.5d - Math.log((1d + centerSin) / (1d - centerSin)) / (4d * Math.PI)) * scale;
        float bitmapWidth = snapshot.bitmap == null ? plot.width() : snapshot.bitmap.getWidth();
        float bitmapHeight = snapshot.bitmap == null ? plot.height() : snapshot.bitmap.getHeight();
        Rect source = sourceRect(snapshot.bitmap, plot);
        float bitmapX = bitmapWidth / 2f + (float) (pointX - centerX);
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
        Bounds b = bounds(route);
        double span = Math.max(b.maxLat - b.minLat, b.maxLng - b.minLng);
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
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < route.size(); i++) {
            RoutePoint p = route.get(i);
            if (i > 0) json.append(',');
            json.append("{lat:").append(p.lat)
                    .append(",lng:").append(p.lng)
                    .append(",time:\"").append(mapDateFormat.format(new Date(p.time))).append("\"}");
        }
        json.append(']');
        mapView.evaluateJavascript("renderRoute(" + json + ")", null);
    }

    private String mapHtml() {
        return "<!doctype html><html><head>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'>"
                + "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'>"
                + "<style>html,body,#map{height:100%;margin:0;background:#dbeafe}.leaflet-container{font:14px system-ui}.panel{position:absolute;z-index:500;left:14px;right:14px;top:14px;background:rgba(25,31,40,.92);color:white;padding:12px 14px;border-radius:16px;font:800 14px system-ui;box-shadow:0 10px 24px rgba(25,31,40,.16)}.panel small{display:block;margin-top:4px;color:#c9d8ee;font-weight:700}.progress{height:5px;margin-top:10px;background:rgba(255,255,255,.16);border-radius:999px;overflow:hidden}.bar{height:100%;width:0;background:#5b9bff;border-radius:999px}.vehicle{border:0;background:transparent}.route-character-sprite{display:block;width:24px;height:48px;background-image:url('characters/satgat-walk-8.png');background-repeat:no-repeat;background-size:800% 100%;animation:route-character-walk 800ms steps(1,end) infinite}@keyframes route-character-walk{0%{background-position:0 0}12.5%{background-position:14.286% 0}25%{background-position:28.571% 0}37.5%{background-position:42.857% 0}50%{background-position:57.143% 0}62.5%{background-position:71.429% 0}75%{background-position:85.714% 0}87.5%{background-position:100% 0}100%{background-position:0 0}}</style>"
                + "</head><body><div id='map'></div><div id='panel' class='panel'><span id='place'>Route preview appears here</span><small id='time'>Waiting for photo GPS points</small><div class='progress'><div class='bar' id='bar'></div></div></div>"
                + "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>"
                + "<script>"
                + "var map=L.map('map',{zoomControl:false,attributionControl:true,preferCanvas:true});"
                + "L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:18,attribution:'&copy; OpenStreetMap contributors'}).addTo(map);"
                + "map.setView([0,0],2);var full,line,marker,raf,lastPan=0,routePoints=[],latlngs=[],localZoom=4,cameraMode='local';"
                + "function ll(p){return [p.lat,p.lng]}"
                + "function vehicleIcon(){return L.divIcon({className:'vehicle',iconSize:[24,48],iconAnchor:[12,44],html:'<span class=\"route-character-sprite\" role=\"img\" aria-label=\"route character\"></span>'})}"
                + "function routeZoom(points){var lats=points.map(function(p){return p.lat}),lngs=points.map(function(p){return p.lng}),span=Math.max(Math.max.apply(null,lats)-Math.min.apply(null,lats),Math.max.apply(null,lngs)-Math.min.apply(null,lngs));return span>90?3:span>30?4:span>8?5:span>2?7:span>.5?9:span>.1?11:span>.02?13:span>.005?15:17}"
                + "function setCamera(t,world){if(!routePoints.length)return;var exact=(routePoints.length-1)*Math.max(0,Math.min(1,t));var end=Math.min(routePoints.length-1,Math.floor(exact)),next=routePoints[Math.min(routePoints.length-1,end+1)],cur=routePoints[end],f=exact-end;var point=[cur.lat+(next.lat-cur.lat)*f,cur.lng+(next.lng-cur.lng)*f];if(world){map.fitBounds(L.latLngBounds(latlngs),{padding:[30,30],maxZoom:2,animate:false});cameraMode='world'}else{map.setView(point,localZoom,{animate:false});cameraMode='local'}}"
                + "function setProgress(t,follow){if(!routePoints.length)return;var exact=(routePoints.length-1)*Math.max(0,Math.min(1,t));var end=Math.max(0,Math.floor(exact)),next=routePoints[Math.min(routePoints.length-1,end+1)],cur=routePoints[end],f=exact-end;var point=[cur.lat+(next.lat-cur.lat)*f,cur.lng+(next.lng-cur.lng)*f];var visible=latlngs.slice(0,end+1);visible.push(point);line.setLatLngs(visible);marker.setLatLng(point);document.getElementById('time').textContent=cur.time;document.getElementById('bar').style.width=(Math.max(0,Math.min(1,t))*100).toFixed(1)+'%';if(follow&&performance.now()-lastPan>80){if(t>=.86&&cameraMode!=='world'){map.fitBounds(L.latLngBounds(latlngs),{padding:[30,30],maxZoom:2,animate:true,duration:.8});cameraMode='world'}else if(t<.86){map.setView(point,localZoom,{animate:false});cameraMode='local'}lastPan=performance.now()}}"
                + "function renderRoute(points){document.getElementById('place').textContent=points.length+' route points found';"
                + "if(full)map.removeLayer(full);if(line)map.removeLayer(line);if(marker)map.removeLayer(marker);if(raf)cancelAnimationFrame(raf);"
                + "routePoints=points;latlngs=points.map(ll);"
                + "full=L.polyline(latlngs,{color:'rgba(15,23,42,.22)',weight:7,lineCap:'round',lineJoin:'round'}).addTo(map);"
                + "line=L.polyline([], {color:'#0ea5e9',weight:7}).addTo(map);"
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

        MapSnapshot(Bitmap bitmap, double centerLat, double centerLng, int zoom, boolean world) {
            this.bitmap = bitmap;
            this.centerLat = centerLat;
            this.centerLng = centerLng;
            this.zoom = zoom;
            this.world = world;
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
