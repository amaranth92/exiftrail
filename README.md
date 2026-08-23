# ExifTrail

![ExifTrail app icon](public/assets/brand/satgat-icon.png)

Turn the photos already on your phone into a chronological travel route video.
ExifTrail reads capture time and GPS metadata locally, follows the route with a
small wandering Satgat character, and exports a vertical video for social media.

## Why I built it

I wanted to make the travel timeline videos that are becoming popular, but I did
not enable Google Timeline because I did not want to give Google a continuous
record of my location history. My travel photos already had the two pieces of
information needed for a route: when a photo was taken and where it was taken.

ExifTrail was built around that idea:

> A Google Timeline-style travel animation from your own photos, without turning
> on Google Timeline.

The project is open source and free to run. The default workflow keeps photo
files and EXIF parsing on the device.

## Demo

- Live web app: <https://amaranth92.github.io/exiftrail/>
- GitHub repository: <https://github.com/amaranth92/exiftrail>
- Sample export: [`public/demo/exiftrail-sample-route.webm`](public/demo/exiftrail-sample-route.webm)

## How it works

1. Choose a date range. The default range is January 1 of the current year to
   today.
2. Allow photo access or select photos.
3. ExifTrail reads capture time and GPS coordinates from each supported image.
4. Photos are sorted chronologically. Images without usable GPS are skipped.
5. A route is drawn on OpenStreetMap. The camera follows the Satgat character
   at a close zoom for short routes and pulls back to a world view at the end.
6. Review the route, then save or share the vertical video.

## Choose the right version

### Android app: scan a phone gallery by date

The Android app is the direct phone-gallery workflow.

1. Set `From` and `To`.
2. Tap `Allow photos and create video`.
3. Grant photo and media-location permission.
4. The app scans matching photos in the phone gallery without opening a file
   picker.
5. Tap `Save moving video` after the route is ready.

The MP4 is saved to `Movies/ExifTrail` in the phone gallery.

### Web app: select the photos the browser may read

Browsers cannot silently scan a phone gallery by date. On the web, the date
range filters the photos you select through the browser file picker.

1. Set `From` and `To` if needed.
2. Tap `Allow photos and create video`.
3. Select the photos to scan.
4. Wait for local EXIF scanning to finish.
5. Tap `Save video` or use the native share sheet when supported.

The web export is a `WebM` or `MP4` file depending on browser recording support.

## Privacy and data flow

- Photos are not uploaded to an ExifTrail server.
- Original files are never edited, moved, renamed, or deleted.
- EXIF data is read in memory for the current session.
- Only photos with usable GPS coordinates can become route points.
- The map uses Leaflet and OpenStreetMap tiles. Visible map tiles are requested
  from OpenStreetMap, so keep the required attribution and follow its tile
  policy. The photo library itself is not sent to OpenStreetMap.
- Do not publish a route that exposes a private home, hotel, or workplace. The
  current export draws the route as captured; redact sensitive points before
  sharing if necessary.

## Supported images and route rules

- Supported web formats: JPG, JPEG, HEIC, and HEIF.
- GPS latitude and longitude are required for a route point.
- Capture time comes from EXIF when available; the browser falls back to the
  file modification time when EXIF capture time is missing.
- Chronological sorting is applied before drawing the route.
- Very close duplicate points are removed.
- Suspiciously large GPS jumps are flagged and excluded from the active route.
- A route needs at least two valid points.

## Build and test locally

### Web

```powershell
npm install
npm run dev
```

Open the Vite URL shown in the terminal.

```powershell
npm run check
npm run build
npm run smoke
```

### Android

```powershell
cd android
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

The Android app needs a connected device or emulator with photo permissions.

## Project layout

```text
src/main.tsx                         Web UI, EXIF scan, map preview, WebM export
src/route.ts                          Route normalization and duplicate filtering
public/assets/characters/satgat-walk-8.png
                                      Eight-frame generated Satgat walking sprite
public/assets/brand/satgat-icon.png   App and README icon
android/app/src/main/java/...         Native gallery scan and MP4 renderer
android/app/src/main/assets/...       Android runtime assets
tests/smoke.spec.ts                   Browser smoke tests
```

## Funding

If ExifTrail saves you time, a small sponsorship helps keep the local-first
workflow and Android export maintained. The repository includes a GitHub
Sponsors button through [`.github/FUNDING.yml`](.github/FUNDING.yml).

## License

MIT. The Satgat character and app icon are generated project assets; see
[`public/assets/characters/LICENSE.txt`](public/assets/characters/LICENSE.txt).
