package dev.exiftrail.app;

import android.Manifest;
import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.ContentValues;
import android.content.ContentUris;
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
import android.view.PixelCopy;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.LinearLayout;
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
    private WebView mapView;
    private ScrollView scrollView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        from.set(2023, Calendar.JANUARY, 1, 0, 0, 0);
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
        root.setPadding(dp(20), dp(34), dp(20), dp(28));
        root.setBackgroundColor(Color.WHITE);
        scroll.addView(root);

        TextView eyebrow = text("PRIVATE PHOTO ROUTE VIDEO", 14, 0xff0369a1, true);
        eyebrow.setLetterSpacing(.08f);
        root.addView(eyebrow);

        TextView title = text("ExifTrail", 50, 0xff0f172a, true);
        root.addView(title);

        TextView lead = text("Pick a date range. Allow photo access once. ExifTrail scans your photo library and animates where you moved over time.", 24, 0xff111827, true);
        lead.setPadding(0, dp(16), 0, dp(18));
        root.addView(lead);

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
        LinearLayout.LayoutParams createLp = new LinearLayout.LayoutParams(-1, dp(62));
        createLp.setMargins(0, dp(16), 0, dp(16));
        root.addView(createButton, createLp);

        saveButton = primaryButton("Save moving video");
        saveButton.setEnabled(false);
        saveButton.setOnClickListener(v -> saveVideo());
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(-1, dp(62));
        saveLp.setMargins(0, 0, 0, dp(16));
        root.addView(saveButton, saveLp);

        status = text("No upload. Photos are only read on this phone.", 16, 0xff475569, true);
        root.addView(status);

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
        LinearLayout.LayoutParams routeLp = new LinearLayout.LayoutParams(-1, dp(560));
        routeLp.setMargins(0, dp(20), 0, 0);
        root.addView(mapView, routeLp);

        return scroll;
    }

    private void startRouteBuild() {
        if (from.after(to)) {
            status.setText("Start date must be earlier than end date.");
            return;
        }
        if (!hasPhotoPermission()) {
            requestPermissions(photoPermissions(), REQ_PHOTOS);
            return;
        }
        buildRoute();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PHOTOS && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            buildRoute();
        } else {
            status.setText("Photo permission is needed to scan your library by date.");
        }
    }

    private void buildRoute() {
        createButton.setEnabled(false);
        saveButton.setEnabled(false);
        status.setText("Scanning photos in the selected date range...");
        points.clear();

        new Thread(() -> {
            ScanResult result = queryPhotos();
            runOnUiThread(() -> {
                createButton.setEnabled(true);
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
            while (cursor.moveToNext()) {
                total += 1;
                long id = cursor.getLong(idCol);
                long taken = cursor.getLong(dateCol);
                Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                float[] latLng = readLatLngFromColumns(cursor, latCol, lngCol);
                if (latLng == null) latLng = readLatLng(uri);
                if (latLng == null) continue;
                withGps += 1;
                RoutePoint prev = rows.isEmpty() ? null : rows.get(rows.size() - 1);
                if (prev != null && shouldSkip(prev, latLng[0], latLng[1], taken)) continue;
                rows.add(new RoutePoint(latLng[0], latLng[1], taken));
                if (total % 50 == 0) {
                    int scanned = total;
                    int gps = withGps;
                    runOnUiThread(() -> status.setText("Scanning photos... " + scanned + " checked, " + gps + " with GPS"));
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
        if (Build.VERSION.SDK_INT >= 34 && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        return checkSelfPermission(photoPermissions()[0]) == PackageManager.PERMISSION_GRANTED;
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
        fromButton.setText("From\n" + dateFormat.format(new Date(from.getTimeInMillis())));
        toButton.setText("To\n" + dateFormat.format(new Date(to.getTimeInMillis())));
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
        button.setTextSize(18);
        button.setTextColor(Color.WHITE);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setBackgroundColor(0xff0f172a);
        return button;
    }

    private Button secondaryButton() {
        Button button = new Button(this);
        button.setTextSize(15);
        button.setTextColor(0xff0f172a);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return button;
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
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = encoder.createInputSurface();
            encoder.start();
            muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MuxerState muxerState = new MuxerState(muxer);
            int totalFrames = VIDEO_SECONDS * 18;
            Bitmap carSprite = loadVehicleSprite("car");
            Bitmap boatSprite = loadVehicleSprite("boat");
            Bitmap planeSprite = loadVehicleSprite("plane");
            Thread.sleep(1200);
            Bitmap mapBitmap = captureMapFrame(1);
            for (int frame = 0; frame < totalFrames; frame++) {
                float progress = frame / (float) (totalFrames - 1);
                Canvas canvas = surface.lockCanvas(null);
                try {
                    drawVideoFrame(canvas, route, progress, mapBitmap, carSprite, boatSprite, planeSprite);
                } finally {
                    surface.unlockCanvasAndPost(canvas);
                }
                drainEncoder(encoder, info, muxerState, false);
                Thread.sleep(1000L / VIDEO_FPS);
                if (frame % VIDEO_FPS == 0) {
                    int seconds = Math.round(frame / (float) Math.max(1, totalFrames - 1) * VIDEO_SECONDS);
                    runOnUiThread(() -> status.setText("Saving MP4 video... " + seconds + " / " + VIDEO_SECONDS + " sec"));
                }
            }
            encoder.signalEndOfInputStream();
            drainEncoder(encoder, info, muxerState, true);
            if (mapBitmap != null) mapBitmap.recycle();
            carSprite.recycle();
            boatSprite.recycle();
            planeSprite.recycle();
        } finally {
            if (surface != null) surface.release();
            encoder.stop();
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

    private Bitmap captureMapFrame(float progress) throws InterruptedException {
        AtomicReference<Bitmap> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        runOnUiThread(() -> {
            scrollView.scrollTo(0, mapView.getTop());
            mapView.evaluateJavascript("setProgress(" + progress + ",false);marker.setOpacity(0);line.setStyle({opacity:0});panel.style.display='none'", ignored -> mapView.postDelayed(() -> {
                int[] location = new int[2];
                mapView.getLocationOnScreen(location);
                int width = Math.min(mapView.getWidth(), getWindow().getDecorView().getWidth() - location[0]);
                int height = Math.min(mapView.getHeight(), getWindow().getDecorView().getHeight() - location[1]);
                if (width <= 0 || height <= 0) {
                    mapView.evaluateJavascript("marker.setOpacity(1);line.setStyle({opacity:1});panel.style.display='block'", null);
                    latch.countDown();
                    return;
                }
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Rect source = new Rect(location[0], location[1], location[0] + width, location[1] + height);
                PixelCopy.request(getWindow(), source, bitmap, copyResult -> {
                    if (copyResult == PixelCopy.SUCCESS) result.set(bitmap);
                    else bitmap.recycle();
                    mapView.evaluateJavascript("marker.setOpacity(1);line.setStyle({opacity:1});panel.style.display='block'", null);
                    latch.countDown();
                }, new Handler(Looper.getMainLooper()));
            }, 80));
        });
        latch.await(1500, TimeUnit.MILLISECONDS);
        return result.get();
    }

    private void drawVideoFrame(Canvas canvas, List<RoutePoint> route, float progress, Bitmap mapBitmap, Bitmap carSprite, Bitmap boatSprite, Bitmap planeSprite) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.drawColor(0xffdbeafe);
        if (mapBitmap != null) {
            canvas.drawBitmap(mapBitmap, null, new RectF(0, 210, VIDEO_WIDTH, 1050), paint);
        } else {
            paint.setColor(0xfff8fafc);
            paint.setStrokeWidth(2);
            for (int y = 210; y < 1050; y += 80) canvas.drawLine(0, y, VIDEO_WIDTH, y, paint);
            for (int x = 0; x < VIDEO_WIDTH; x += 80) canvas.drawLine(x, 210, x, 1050, paint);
        }

        int currentIndex = Math.max(0, Math.round((route.size() - 1) * progress));
        RectF plot = mapBitmap != null
                ? new RectF(56, 470, VIDEO_WIDTH - 56, 900)
                : new RectF(56, 266, VIDEO_WIDTH - 56, 994);
        Bounds bounds = bounds(route);
        Path full = routePath(route, route.size() - 1, bounds, plot);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(12);
        paint.setColor(0x660f172a);
        canvas.drawPath(full, paint);
        Path active = routePath(route, currentIndex, bounds, plot);
        paint.setStrokeWidth(14);
        paint.setColor(0xff0ea5e9);
        canvas.drawPath(active, paint);
        float[] pos = project(route.get(currentIndex), bounds, plot);
        Bitmap sprite = "plane".equals(vehicleFor(route, currentIndex)) ? planeSprite
                : "boat".equals(vehicleFor(route, currentIndex)) ? boatSprite : carSprite;
        drawVehicle(canvas, pos[0], pos[1], sprite);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff0f172a);
        canvas.drawRoundRect(new RectF(48, 52, VIDEO_WIDTH - 48, 178), 30, 30, paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(38);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        canvas.drawText("ExifTrail", 82, 106, paint);
        paint.setTextSize(25);
        paint.setColor(0xffbfdbfe);
        canvas.drawText(mapDateFormat.format(new Date(route.get(currentIndex).time)), 82, 148, paint);

        paint.setColor(0xff0f172a);
        paint.setTextSize(30);
        canvas.drawText(route.size() + " photo GPS points · " + vehicleFor(route, currentIndex), 56, VIDEO_HEIGHT - 144, paint);
        paint.setColor(0xff64748b);
        paint.setTextSize(23);
        canvas.drawText("Created locally from photo EXIF metadata", 56, VIDEO_HEIGHT - 104, paint);

        paint.setColor(0x220f172a);
        canvas.drawRoundRect(new RectF(56, VIDEO_HEIGHT - 70, VIDEO_WIDTH - 56, VIDEO_HEIGHT - 52), 99, 99, paint);
        paint.setColor(0xff38bdf8);
        canvas.drawRoundRect(new RectF(56, VIDEO_HEIGHT - 70, 56 + (VIDEO_WIDTH - 112) * progress, VIDEO_HEIGHT - 52), 99, 99, paint);
    }

    private void drawVehicle(Canvas canvas, float x, float y, Bitmap sprite) {
        if (sprite == null) return;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(sprite, null, new RectF(x - 92, y - 92, x + 92, y + 92), paint);
    }

    private Bitmap loadVehicleSprite(String kind) throws Exception {
        try (InputStream input = getAssets().open("vehicles/sprites/" + kind + ".png")) {
            Bitmap sprite = BitmapFactory.decodeStream(input);
            if (sprite == null) throw new IllegalStateException("Could not load vehicle sprite: " + kind);
            return sprite;
        }
    }

    private Path routePath(List<RoutePoint> route, int endIndex, Bounds bounds, RectF plot) {
        Path path = new Path();
        for (int i = 0; i <= endIndex; i++) {
            float[] pos = project(route.get(i), bounds, plot);
            if (i == 0) path.moveTo(pos[0], pos[1]);
            else path.lineTo(pos[0], pos[1]);
        }
        return path;
    }

    private float[] project(RoutePoint point, Bounds bounds, RectF plot) {
        double latRange = Math.max(bounds.maxLat - bounds.minLat, .0001);
        double lngRange = Math.max(bounds.maxLng - bounds.minLng, .0001);
        float x = (float) (plot.left + ((point.lng - bounds.minLng) / lngRange) * plot.width());
        float y = (float) (plot.bottom - ((point.lat - bounds.minLat) / latRange) * plot.height());
        return new float[]{x, y};
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

    private void renderMapRoute(List<RoutePoint> route) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < route.size(); i++) {
            RoutePoint p = route.get(i);
            if (i > 0) json.append(',');
            json.append("{lat:").append(p.lat)
                    .append(",lng:").append(p.lng)
                    .append(",time:\"").append(mapDateFormat.format(new Date(p.time))).append("\"")
                    .append(",vehicle:\"").append(vehicleFor(route, i)).append("\"}");
        }
        json.append(']');
        mapView.evaluateJavascript("renderRoute(" + json + ")", null);
    }

    private String mapHtml() {
        return "<!doctype html><html><head>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'>"
                + "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'>"
                + "<style>html,body,#map{height:100%;margin:0;background:#dbeafe}.leaflet-container{font:14px system-ui}.panel{position:absolute;z-index:500;left:14px;right:14px;top:14px;background:rgba(15,23,42,.9);color:white;padding:12px 14px;border-radius:16px;font:800 14px system-ui;box-shadow:0 14px 38px rgba(15,23,42,.24)}.panel small{display:block;margin-top:4px;color:#bfdbfe;font-weight:700}.progress{height:5px;margin-top:10px;background:rgba(255,255,255,.16);border-radius:999px;overflow:hidden}.bar{height:100%;width:0;background:#38bdf8;border-radius:999px}.vehicle{border:0;background:transparent}.vehicle img{width:96px;height:96px;object-fit:contain;pointer-events:none}</style>"
                + "</head><body><div id='map'></div><div id='panel' class='panel'><span id='place'>Route preview appears here</span><small id='time'>Waiting for photo GPS points</small><div class='progress'><div class='bar' id='bar'></div></div></div>"
                + "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>"
                + "<script>"
                + "var map=L.map('map',{zoomControl:false,attributionControl:true,preferCanvas:true});"
                + "L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:18,attribution:'&copy; OpenStreetMap contributors'}).addTo(map);"
                + "map.setView([0,0],2);var full,line,marker,raf,lastPan=0,routePoints=[],latlngs=[];"
                + "function ll(p){return [p.lat,p.lng]}"
                + "function vehicleIcon(kind){kind=kind||'car';return L.divIcon({className:'vehicle',iconSize:[96,96],iconAnchor:[48,48],html:'<img src=\"vehicles/sprites/'+kind+'.png\" width=\"96\" height=\"96\" alt=\"\" />'})}"
                + "function setProgress(t,follow){if(!routePoints.length)return;var exact=(routePoints.length-1)*Math.max(0,Math.min(1,t));var end=Math.max(0,Math.floor(exact));var visible=latlngs.slice(0,end+1);var cur=routePoints[end];line.setLatLngs(visible);marker.setLatLng(ll(cur));marker.setIcon(vehicleIcon(cur.vehicle));document.getElementById('time').textContent=cur.time;document.getElementById('bar').style.width=(Math.max(0,Math.min(1,t))*100).toFixed(1)+'%';if(follow&&performance.now()-lastPan>550){map.panTo(ll(cur),{animate:true,duration:.25});lastPan=performance.now()}}"
                + "function renderRoute(points){document.getElementById('place').textContent=points.length+' route points found';"
                + "if(full)map.removeLayer(full);if(line)map.removeLayer(line);if(marker)map.removeLayer(marker);if(raf)cancelAnimationFrame(raf);"
                + "routePoints=points;latlngs=points.map(ll);"
                + "full=L.polyline(latlngs,{color:'rgba(15,23,42,.22)',weight:7,lineCap:'round',lineJoin:'round'}).addTo(map);"
                + "line=L.polyline([], {color:'#0ea5e9',weight:7}).addTo(map);"
                + "marker=L.marker(ll(points[0]),{icon:vehicleIcon(points[0].vehicle),interactive:false}).addTo(map);"
                + "map.fitBounds(L.latLngBounds(latlngs),{padding:[54,34]});var start=0,duration=Math.min(28000,Math.max(8500,points.length*45));"
                + "setProgress(0,true);function step(ts){if(!start)start=ts;var t=Math.min((ts-start)/duration,1);setProgress(t,true);if(t<1)raf=requestAnimationFrame(step)}"
                + "raf=requestAnimationFrame(step)}"
                + "</script></body></html>";
    }

    private String vehicleFor(List<RoutePoint> route, int index) {
        if (index <= 0) return "car";
        RoutePoint current = route.get(index);
        for (int candidateIndex = index - 1; candidateIndex >= Math.max(0, index - 8); candidateIndex--) {
            RoutePoint candidate = route.get(candidateIndex);
            double distance = distanceKm(candidate.lat, candidate.lng, current.lat, current.lng);
            double hours = Math.max((current.time - candidate.time) / 36e5, 1d / 60d);
            double speed = distance / hours;
            if (distance >= 180 || speed >= 220) return "plane";
            if (distance >= 8 && distance < 80 && speed >= 3 && speed < 80) return "boat";
        }
        return "car";
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

    private static class MuxerState {
        final MediaMuxer muxer;
        boolean started;
        int trackIndex = -1;

        MuxerState(MediaMuxer muxer) {
            this.muxer = muxer;
        }
    }

}
