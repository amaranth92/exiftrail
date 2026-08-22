# ExifTrail

Local-first travel timeline videos from your own photos.

I wanted to try the viral travel timeline videos, but I never enabled Google Timeline because I did not want to give continuous location history to Google. Then I realized my travel photos already contain enough timestamp and GPS metadata to rebuild the route locally. So I built this: a local-first travel timeline generator from your own photos.

**Core idea:** No Google location history required. Your photos stay local by default.

## Demo

Sample export from generated test photos: [`public/demo/exiftrail-sample-route.webm`](public/demo/exiftrail-sample-route.webm)

## What It Does

- Lets mobile users select travel photos from the phone photo picker.
- Asks you to choose a photo library folder before scanning anything.
- Recursively scans the selected folder for photos, so you do not have to pick images one by one.
- Reads JPG/JPEG/HEIC/HEIF photo metadata in the browser.
- Extracts EXIF GPS coordinates and capture time.
- Sorts photo points chronologically.
- Starts with the latest 90 days by default, with an option to switch to all GPS photos.
- Removes very close duplicate points.
- Flags suspicious GPS jumps.
- Filters the route by date segment.
- Lets you add a local trip/city label for the exported story.
- Can soften exported coordinates or hide the first and last stop before export.
- Shows the route on OpenStreetMap via Leaflet.
- Plays a route animation preview.
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

```bash
npm install
npm run dev
```

Open the local Vite URL, choose travel photos, review the route, then save/share the video.

## Use On A Phone

1. Open the deployed site on your phone.
2. Tap **Select travel photos**.
3. Select the travel photos you want to turn into a route video.
4. Review the route, hide sensitive stops if needed, and tap **Preview**.
5. Tap **Save / share video**.
6. Post the generated vertical video to Reels, TikTok, Shorts, Threads, or Reddit.

Browser note: websites cannot secretly scan a phone photo library. The user must explicitly choose photos first. That is intentional and matches the privacy goal of the project.

No personal photos are required to try the UI:

- Tap **Try demo** to preview the animation and export flow with in-memory demo points.
- Tap **Test EXIF sample** to run the same import path against synthetic GPS JPEG files in `public/samples/`. They are generated test images, not personal photos.

## MVP Status

Done:

- Multi-photo import
- Folder import in browsers that support `webkitdirectory`
- Read-only photo library folder scan with the File System Access API where supported
- Concurrent EXIF parsing for larger folders
- Recent 90-day route scope by default
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
