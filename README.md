# ExifTrail

Local-first moving map videos from your own photos.

I wanted to try the viral travel timeline videos, but I never enabled Google Timeline because I did not want to give continuous location history to Google. Then I realized my travel photos already contain enough timestamp and GPS metadata to rebuild the route locally. So I built this: a local-first travel timeline generator from your own photos.

**Core idea:** No Google location history required. Your photos stay local by default.

## Demo

Sample export from generated test photos: [`public/demo/exiftrail-sample-route.webm`](public/demo/exiftrail-sample-route.webm)

## What It Does

- Android app: lets you choose a From/To date range, allow photo access once, then scans the phone photo library directly through MediaStore.
- Web demo: reads photos selected by the user because browsers cannot scan a full phone gallery by date.
- Reads JPG/JPEG/HEIC/HEIF photo metadata in the browser.
- Extracts EXIF GPS coordinates and capture time.
- Sorts photo points chronologically.
- Removes very close duplicate points.
- Flags suspicious GPS jumps.
- Shows the route on OpenStreetMap via Leaflet.
- Automatically plays a moving route preview with a marker traveling through the route in photo time order.
- Exports a vertical 9:16 route video for Shorts, TikTok, Instagram, Reddit, or Threads.
- Uses the native share sheet when the browser supports sharing generated video files.

## Privacy

- No upload by default.
- Photos are read with the browser File API.
- Original photos are never edited, moved, renamed, or deleted.
- EXIF metadata is used only in memory for the current session.
- Export uses only the route points and thumbnails you reviewed.
- Privacy controls can round route coordinates and drop the first/last stop before export.

## Run Locally

### Android App

This is the main path for the real phone-gallery workflow:

```powershell
cd android
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Open ExifTrail on the phone, choose From/To, then tap **Allow photos and create video**. After Android photo permission is granted, the app scans matching photos by date, extracts GPS EXIF locally, and plays the route on OpenStreetMap with a moving marker.

Map note: ExifTrail uses Leaflet + OpenStreetMap tiles. It does not bulk-prefetch map tiles; it only loads visible map tiles while previewing the route.

### Web Demo

```bash
npm install
npm run dev
```

Open the local Vite URL, select photos, review the moving route, then save/share the video.

## Use On A Phone

1. Open the deployed site on your phone.
2. Optional: set a start/end date.
3. Tap **Allow photos and create video**.
4. Select the album/photos you want to turn into a route video.
5. ExifTrail reads photo time/GPS locally and automatically animates the route.
6. Tap **Save video**.
7. Post the generated vertical video to Reels, TikTok, Shorts, Threads, or Reddit.

Browser note: websites cannot scan a phone photo library by date without a user file picker. The Android app exists for the direct From/To gallery scan flow.

No personal photos are required for automated tests. Synthetic GPS JPEG files live in `public/samples/`.

## MVP Status

Done:

- Multi-photo import
- Concurrent EXIF parsing for larger folders
- Optional date range filter
- Synthetic sample EXIF photo import
- EXIF GPS/time extraction
- GPS-missing photo skip summary
- Chronological route
- Date segment filter
- Near-duplicate cleanup
- Suspicious jump warning
- Basic privacy scrubber
- Leaflet/OpenStreetMap preview
- Animation preview
- 1080x1920 WebM export

Later:

- More reliable HEIC support across browsers
- Optional reverse geocoding, only after clear user consent because it sends coordinates to a lookup service
- Music
- More visual templates
- GPX/KML export
- Richer privacy scrubber

## Share Copy

> I didn't enable Google Timeline, so I rebuilt my travel route from photo EXIF instead.

> A local-first travel timeline generator from your own photos.

> No Google location history required.

If this helped you make a travel route video without turning on continuous location history, a GitHub star helps the project get found.

## License

MIT
