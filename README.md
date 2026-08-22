# ExifTrail

Local-first travel timeline videos from your own photos.

I wanted to try the viral travel timeline videos, but I never enabled Google Timeline because I did not want to give continuous location history to Google. Then I realized my travel photos already contain enough timestamp and GPS metadata to rebuild the route locally. So I built this: a local-first travel timeline generator from your own photos.

**Core idea:** No Google location history required. Your photos stay local by default.

## What It Does

- Reads JPG/JPEG/HEIC/HEIF photo metadata in the browser.
- Extracts EXIF GPS coordinates and capture time.
- Sorts photo points chronologically.
- Removes very close duplicate points.
- Flags suspicious GPS jumps.
- Filters the route by date segment.
- Lets you add a local trip/city label for the exported story.
- Can soften exported coordinates or hide the first and last stop before export.
- Shows the route on OpenStreetMap via Leaflet.
- Plays a route animation preview.
- Exports a vertical 9:16 WebM route video for Shorts, TikTok, Instagram, or Reddit.

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

Open the local Vite URL, choose travel photos, review the route, then export a WebM.

No sample photos are required to try the UI. Click **Load demo route** to preview the animation and export flow with in-memory demo points.

For a real EXIF parsing smoke test, use the synthetic GPS photos in `public/samples/`. They are generated test images, not personal photos.

## MVP Status

Done:

- Multi-photo import
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
