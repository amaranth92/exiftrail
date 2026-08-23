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

## 한국어 안내

ExifTrail은 휴대폰에 저장된 사진의 촬영 시간과 GPS 메타데이터를 이용해,
시간순 여행 경로를 지도 위에서 따라가는 세로형 영상으로 만들어주는 오픈소스
앱입니다. 사진을 직접 서버에 올리지 않고 기기 안에서 경로를 계산하는 것을
기본 원칙으로 합니다.

### 만든 이유

최근 유행하는 Google 지도 타임라인 형태의 여행 영상을 만들어보고 싶었지만,
지속적인 위치 기록을 Google에 제공하는 것은 원하지 않았습니다. 대신 여행 중
촬영한 사진에는 촬영 시각과 장소 정보가 남아 있으므로, 사진 자체의 EXIF
메타데이터만으로 비슷한 기록을 만들 수 있도록 ExifTrail을 개발했습니다.

### 사용 방법

#### Android 앱

Android 앱은 휴대폰 사진첩을 날짜 범위로 직접 검색하는 방식입니다.

1. `From`과 `To` 날짜를 정합니다. 기본값은 올해 1월 1일부터 오늘까지입니다.
2. `Allow photos and create video`를 누릅니다.
3. 사진 및 미디어 위치 권한을 허용합니다.
4. 앱이 파일 선택창을 띄우지 않고 해당 기간의 사진을 자동으로 검색합니다.
5. 경로가 만들어지면 `Save moving video`를 눌러 저장합니다.

완성된 MP4는 휴대폰 사진첩의 `Movies/ExifTrail` 폴더에 저장됩니다.

#### 웹 앱

브라우저는 보안상 휴대폰 사진첩 전체를 날짜만으로 몰래 읽을 수 없습니다.
따라서 웹 앱에서는 날짜 범위가 선택한 사진을 필터링하는 기준으로 사용됩니다.

1. 필요하면 `From`과 `To` 날짜를 수정합니다.
2. `Allow photos and create video`를 누릅니다.
3. 브라우저 파일 선택창에서 분석할 사진을 허용합니다.
4. 로컬 EXIF 분석이 끝날 때까지 기다립니다.
5. 경로를 확인한 뒤 `Save video` 또는 지원되는 경우 공유 기능을 사용합니다.

브라우저 환경에 따라 결과 파일은 WebM 또는 MP4로 저장됩니다.

### 경로가 만들어지는 방식

- 사진의 촬영 시각을 기준으로 오래된 사진부터 정렬합니다.
- GPS 위도와 경도가 없는 사진은 경로에서 제외합니다.
- 거의 같은 위치의 중복 좌표는 줄입니다.
- 비정상적으로 먼 좌표 이동은 자동으로 제외합니다.
- 최소 두 개의 유효한 GPS 지점이 있어야 경로를 만들 수 있습니다.
- 김삿갓 캐릭터가 가까운 지도 화면에서 시간순으로 이동합니다.
- 이동이 끝나면 카메라가 뒤로 빠지며 전체 여행 경로를 보여줍니다.

### 개인정보 및 지도 사용

- 사진 원본은 ExifTrail 서버로 업로드하지 않습니다.
- 사진을 수정하거나 이동하거나 삭제하지 않습니다.
- 현재 세션에서만 EXIF 정보를 메모리로 읽습니다.
- 지도 화면은 Leaflet과 OpenStreetMap 타일을 사용합니다.
- 집, 숙소, 직장처럼 공개하면 안 되는 위치가 포함될 수 있으니 공유 전에
  경로를 확인해야 합니다.

### 개발 및 테스트

```powershell
npm install
npm run dev
npm run check
npm run build
npm run smoke
```

Android APK를 빌드하려면 다음을 실행합니다.

```powershell
cd android
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

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
