# ExifTrail

<p align="center">
  <a href="README.en.md"><strong>🇬🇧 English</strong></a>
  &nbsp; · &nbsp;
  <a href="README.ko.md"><strong>🇰🇷 한국어</strong></a>
</p>

<p align="center">
  <a href="https://amaranth92.github.io/exiftrail/">Live demo</a>
  &nbsp; · &nbsp;
  <a href="https://github.com/amaranth92/exiftrail">GitHub</a>
  &nbsp; · &nbsp;
  <a href="https://github.com/amaranth92/exiftrail/raw/refs/heads/master/public/downloads/ExifTrail-Android.apk">Download Android APK</a>
</p>

![ExifTrail app icon](public/assets/brand/satgat-icon.png)

## What is ExifTrail?

ExifTrail turns the photos already on your phone into a chronological travel
route video. It reads capture time and GPS metadata locally, draws the route on
OpenStreetMap, follows it with a small wandering Satgat character, and exports
a vertical video for sharing.

This is a source-first open-source project. There is no app-store launch plan;
the goal is to publish the code and let people run, inspect, and improve it.

## Why I built it

Google Timeline-style travel animations became popular, so I wanted to make one
for my own trips. I had turned Google Timeline off because I did not want to
continuously give Google my location history, which meant I could not use that
feature.

When I travel, I already take photos. Those photos contain the two pieces of
information needed for a route: when the photo was taken and where it was taken.
ExifTrail uses those existing EXIF facts to recreate the useful part locally,
without enabling a continuous location timeline.

> A Google Timeline-style travel animation from your own photos, without turning
> on Google Timeline.

## Highlights

| Feature | What it means |
| --- | --- |
| Local-first | Photos and EXIF parsing stay on the device by default. |
| Timeline-style route | Photos are sorted by capture time and connected into a route. |
| Video export | The route becomes a vertical video suitable for sharing. |
| Android + web | Use the native gallery workflow on Android or select files in a browser. |
| Source available | Inspect and run the code without an app-store account. |

## See it in motion

The repository includes the sample MP4 created from the project:

<video controls width="320" poster="https://github.com/amaranth92/exiftrail/raw/refs/heads/master/public/docs/screenshots/exiftrail-4-final.jpg">
  <source src="https://github.com/amaranth92/exiftrail/raw/refs/heads/master/public/demo/exiftrail-sample-route.mp4" type="video/mp4">
</video>

[Open or download the sample MP4](https://github.com/amaranth92/exiftrail/raw/refs/heads/master/public/demo/exiftrail-sample-route.mp4)

## Screenshots

<table>
  <tr>
    <td><img src="public/docs/screenshots/exiftrail-1-cropped.png" alt="Home screen with Decody banner" width="220"></td>
    <td><img src="public/docs/screenshots/exiftrail-2-cropped.png" alt="Home screen with Neon Drift banner" width="220"></td>
  </tr>
  <tr>
    <td><img src="public/docs/screenshots/exiftrail-3-cropped.jpg" alt="Video generation progress" width="220"></td>
    <td><img src="public/docs/screenshots/exiftrail-4-final.jpg" alt="Generated route result" width="220"></td>
  </tr>
</table>

## How it works

1. Choose a date range.
2. On Android, allow the photo access needed for the gallery scan. On the web,
   select the photos the browser may read.
3. Read capture time and GPS coordinates from each supported image.
4. Sort photos chronologically and skip images without usable GPS data.
5. Remove near-duplicate points and reject suspiciously large GPS jumps.
6. Draw the route, animate the Satgat character, and export the vertical video.

## Install on an Android phone

This project is not distributed through Google Play. The ready-to-install APK
is provided directly from this GitHub repository.

### 1. Download and install the APK

[Download ExifTrail-Android.apk](https://github.com/amaranth92/exiftrail/raw/refs/heads/master/public/downloads/ExifTrail-Android.apk)

1. Open the download link on your Android phone and save the APK.
2. Open the downloaded file from the notification or the `Downloads` folder.
3. If Android blocks the installation, open **Settings** from that message and
   allow your browser or file manager to **Install unknown apps**. Return to the
   APK and tap it again.
4. Tap **Install**, then **Open**.

This is a direct GitHub build, so Android or Play Protect may show a warning
because it was not installed from Google Play. Only install the APK from this
repository if you trust the source.

### 2. Create your first route video

1. Set the `From` and `To` dates on the first screen.
2. Tap **Allow photos and create video**.
3. When Android asks for photo access, choose **Allow all photos** if you want
   ExifTrail to scan the complete gallery. **Selected photos** limits the scan
   to the photos you choose.
4. Wait while ExifTrail reads the capture time and GPS location from the photos.
5. When the route preview is ready, tap **Download video**.
6. Find the MP4 in the phone Gallery, under `Movies/ExifTrail`.

If Android does not show the permission dialog again, open **Settings → Apps →
ExifTrail → Permissions → Photos and videos** and grant the required access.
Photos without GPS coordinates cannot become route points, so a route may be
empty when the selected photos have no location metadata.

## Choose a version

<details>
<summary><strong>Android: scan a phone gallery by date</strong></summary>

1. Set `From` and `To`.
2. Tap `Allow photos and create video`.
3. On Android 14+, choose full photo access if you want the complete gallery.
4. ExifTrail scans matching photos without opening a separate file picker.
5. Tap `Download video` after the route is ready.

The generated MP4 is saved to `Movies/ExifTrail` in the phone gallery.
</details>

<details>
<summary><strong>Web: select the photos the browser may read</strong></summary>

1. Set the date range if needed.
2. Tap `Allow photos and create video`.
3. Select the photos in the browser file picker.
4. Wait for local EXIF scanning to finish.
5. Review and save the WebM or MP4 export.

Browsers cannot silently scan a phone gallery by date, so the web version uses
the date range to filter the photos you explicitly select.
</details>

## Privacy and map data

- Photos are not uploaded to an ExifTrail server.
- Original files are never edited, moved, renamed, or deleted.
- EXIF data is read in memory for the current session.
- Map tiles are requested from OpenStreetMap through Leaflet.
- The route is drawn as captured. Check for private home, hotel, or workplace
  locations before sharing an export.

## Run the web app locally

```powershell
npm install
npm run dev
```

Useful checks:

```powershell
npm run check
npm run build
npm run smoke
```

## Build Android locally (developers)

```powershell
cd android
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

The Android build needs a connected device or emulator with photo permissions.

## Project layout

```text
src/main.tsx                         Web UI, EXIF scan, map preview, video export
src/route.ts                         Route normalization and duplicate filtering
public/assets/brand/                 App branding
public/assets/characters/            Satgat sprites and asset license
android/app/src/main/java/           Native gallery scan and MP4 renderer
android/app/src/main/assets/         Android runtime assets
tests/smoke.spec.ts                  Browser smoke tests
```

## Contributing

Bug reports, route edge cases, privacy concerns, and small pull requests are
welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a change.
