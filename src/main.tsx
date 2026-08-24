import React, { useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import * as exifr from "exifr";
import html2canvas from "html2canvas";
import { normalizeRoute } from "./route";
import "./styles.css";

type PhotoPoint = {
  id: string;
  name: string;
  lat: number;
  lng: number;
  time: Date;
  url: string;
  enabled: boolean;
  distanceFromPrevKm: number;
  suspicious: boolean;
};

type ScanSummary = {
  total: number;
  withGps: number;
  withoutGps: number;
  outsideRange: number;
  duplicates: number;
  suspicious: number;
  scanned: number;
};

type ScanProgress = { done: number; total: number };
type ExportResult = { blob: Blob; filename: string };
type DateRange = { from: string; to: string };
type GeoViewport = { centerLat: number; centerLng: number; zoom: number; width: number; height: number; world: boolean };
type MapSnapshot = { image: HTMLCanvasElement | null; viewport: GeoViewport };
const EXIF_CONCURRENCY = Math.max(4, Math.min(8, navigator.hardwareConcurrency || 4));
const ACCEPTED_IMAGES = "image/*,.jpg,.jpeg,.heic,.heif";

function dateInputValue(date: Date) {
  return [date.getFullYear(), String(date.getMonth() + 1).padStart(2, "0"), String(date.getDate()).padStart(2, "0")].join("-");
}

const today = new Date();
const DEFAULT_DATE_RANGE: DateRange = { from: `${today.getFullYear()}-01-01`, to: dateInputValue(today) };

const PHOTO_TYPES = new Set(["image/jpeg", "image/jpg", "image/heic", "image/heif"]);
const ROUTE_SPRITE = "./assets/characters/satgat-walk-8.png";
const SAMPLE_RANGE: DateRange = { from: "2024-01-01", to: "2024-12-31" };
const SAMPLE_PHOTOS = [
  "00-no-gps.jpg",
  "01-perth.jpg",
  "02-fremantle.jpg",
  "03-rottnest.jpg",
  "04-margaret-river.jpg",
  "05-albany.jpg",
];
let activeMapController: {
  capture: (progress: number, world: boolean) => Promise<MapSnapshot>;
} | null = null;

function isPhoto(file: File) {
  const lower = file.name.toLowerCase();
  return PHOTO_TYPES.has(file.type) || /\.(jpe?g|heic|heif)$/.test(lower);
}

function getTakenAt(tags: Record<string, unknown>, file: File) {
  const raw = tags.DateTimeOriginal || tags.CreateDate || tags.ModifyDate || tags["36867"];
  if (raw instanceof Date) return raw;
  if (typeof raw === "string") return new Date(raw.replace(/^(\d{4}):(\d{2}):(\d{2})/, "$1-$2-$3"));
  return new Date(file.lastModified);
}

async function mapLimit<T, R>(items: T[], limit: number, worker: (item: T, index: number) => Promise<R>) {
  const results: R[] = new Array(items.length);
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(limit, items.length) }, async () => {
      while (next < items.length) {
        const index = next;
        next += 1;
        results[index] = await worker(items[index], index);
      }
    }),
  );
  return results;
}

async function readPhotos(
  files: File[],
  onProgress?: (progress: ScanProgress) => void,
  range?: DateRange,
): Promise<{ points: PhotoPoint[]; summary: ScanSummary }> {
  const photos = files.filter(isPhoto).sort((a, b) => b.lastModified - a.lastModified);
  const fromTime = range?.from ? +new Date(`${range.from}T00:00:00`) : -Infinity;
  const toTime = range?.to ? +new Date(`${range.to}T23:59:59`) : Infinity;
  let done = 0;
  let outsideRange = 0;
  const rows: Array<PhotoPoint | null> = await mapLimit(
    photos,
    EXIF_CONCURRENCY,
    async (file, index) => {
      try {
        const tags = (await exifr.parse(file, { gps: true, tiff: true, exif: true })) || {};
        const latitude = Number(tags.latitude);
        const longitude = Number(tags.longitude);
        if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return null;
        const time = getTakenAt(tags, file);
        if (+time < fromTime || +time > toTime) {
          outsideRange += 1;
          return null;
        }
        return {
          id: `${file.name}-${file.lastModified}-${index}`,
          name: file.name,
          lat: latitude,
          lng: longitude,
          time,
          url: URL.createObjectURL(file),
          enabled: true,
          distanceFromPrevKm: 0,
          suspicious: false,
        } satisfies PhotoPoint;
      } catch {
        return null;
      } finally {
        done += 1;
        onProgress?.({ done, total: photos.length });
      }
    },
  );

  const sorted = rows.filter((row): row is PhotoPoint => row !== null);
  const normalized = normalizeRoute(sorted);
  const keep = new Set(normalized.points);
  sorted.filter((point) => !keep.has(point)).forEach((point) => URL.revokeObjectURL(point.url));

  return {
    points: normalized.points,
    summary: {
      total: files.filter(isPhoto).length,
      withGps: sorted.length,
      withoutGps: photos.length - sorted.length - outsideRange,
      outsideRange,
      duplicates: normalized.duplicates,
      suspicious: normalized.points.filter((point) => point.suspicious).length,
      scanned: photos.length,
    },
  };
}

function formatDate(date: Date) {
  return new Intl.DateTimeFormat("en", { month: "short", day: "2-digit", year: "numeric" }).format(date);
}

function project(points: PhotoPoint[], width: number, height: number) {
  const lats = points.map((point) => point.lat);
  const lngs = points.map((point) => point.lng);
  const minLat = Math.min(...lats);
  const maxLat = Math.max(...lats);
  const minLng = Math.min(...lngs);
  const maxLng = Math.max(...lngs);
  const pad = 110;
  const latSpan = maxLat - minLat || 0.01;
  const lngSpan = maxLng - minLng || 0.01;

  return points.map((point) => ({
    ...point,
    x: pad + ((point.lng - minLng) / lngSpan) * (width - pad * 2),
    y: pad + (1 - (point.lat - minLat) / latSpan) * (height - pad * 2),
  }));
}

async function loadImage(src: string) {
  const img = new Image();
  img.src = src;
  await img.decode().catch(() => undefined);
  return img;
}

function videoMime() {
  if (!("MediaRecorder" in window)) return null;
  const types = [
    "video/mp4;codecs=h264",
    "video/mp4",
    "video/webm;codecs=vp9",
    "video/webm;codecs=vp8",
    "video/webm",
  ];
  return types.find((type) => MediaRecorder.isTypeSupported(type)) || "";
}

function extensionForMime(mime: string) {
  return mime.includes("mp4") ? "mp4" : "webm";
}

function localZoom(points: PhotoPoint[]) {
  const latSpan = Math.max(...points.map((point) => point.lat)) - Math.min(...points.map((point) => point.lat));
  const lngSpan = Math.max(...points.map((point) => point.lng)) - Math.min(...points.map((point) => point.lng));
  const span = Math.max(latSpan, lngSpan);
  if (span > 90) return 3;
  if (span > 30) return 4;
  if (span > 8) return 5;
  if (span > 2) return 7;
  if (span > 0.5) return 9;
  if (span > 0.1) return 11;
  if (span > 0.02) return 13;
  if (span > 0.005) return 15;
  return 17;
}

function vehicleIcon() {
  return L.divIcon({
    className: "vehicle-marker",
    iconSize: [24, 48],
    iconAnchor: [12, 44],
    html: `<span class="route-character-sprite" style="--route-sprite: url('${ROUTE_SPRITE}')" role="img" aria-label="route character"></span>`,
  });
}

function wait(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function projectViewport(points: PhotoPoint[], viewport: GeoViewport, width: number, height: number) {
  const scale = 256 * 2 ** viewport.zoom;
  const centerX = ((viewport.centerLng + 180) / 360) * scale;
  const centerLat = Math.max(-85.05112878, Math.min(85.05112878, viewport.centerLat));
  const centerSin = Math.sin((centerLat * Math.PI) / 180);
  const centerY = (0.5 - Math.log((1 + centerSin) / (1 - centerSin)) / (4 * Math.PI)) * scale;
  return points.map((point) => {
    const lat = Math.max(-85.05112878, Math.min(85.05112878, point.lat));
    const sin = Math.sin((lat * Math.PI) / 180);
    const x = ((point.lng + 180) / 360) * scale;
    const y = (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * scale;
    return {
      ...point,
      x: width / 2 + ((x - centerX) * width) / viewport.width,
      y: height / 2 + ((y - centerY) * height) / viewport.height,
    };
  });
}

function downloadBlob(blob: Blob, filename: string) {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}

async function shareOrDownloadVideo(result: ExportResult) {
  const file = new File([result.blob], result.filename, { type: result.blob.type });
  const share = navigator as Navigator & {
    canShare?: (data: { files: File[] }) => boolean;
    share?: (data: { files: File[]; title: string; text: string }) => Promise<void>;
  };
  if (share.canShare?.({ files: [file] }) && share.share) {
    try {
      await share.share({
        files: [file],
        title: "ExifTrail route video",
        text: "Travel route rebuilt from local photo metadata.",
      });
      return;
    } catch {
      // Some embedded browsers reject the share sheet; keep download as the reliable fallback.
    }
  }
  downloadBlob(result.blob, result.filename);
}

async function exportVideo(points: PhotoPoint[], tripLabel: string): Promise<ExportResult> {
  const active = points.filter((point) => point.enabled && !point.suspicious);
  if (active.length < 2) throw new Error("Need at least two valid route points.");
  const mime = videoMime();
  if (mime === null) {
    throw new Error("This browser cannot record video from canvas. Try Chrome on Android/desktop, or Safari 17+.");
  }

  const canvas = document.createElement("canvas");
  canvas.width = 1080;
  canvas.height = 1920;
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("Canvas is not available.");

  const fallbackViewport: GeoViewport = { centerLat: active[0].lat, centerLng: active[0].lng, zoom: localZoom(active), width: 1, height: 1, world: false };
  const thumbs = await Promise.all(active.slice(0, 8).map((point) => loadImage(point.url)));
  const sprite = await loadImage(ROUTE_SPRITE);
  const snapshots: MapSnapshot[] = [];
  if (activeMapController) {
    for (const progress of [0, 0.2, 0.4, 0.6, 0.8]) snapshots.push(await activeMapController.capture(progress, false));
    snapshots.push(await activeMapController.capture(1, true));
  }
  const stream = canvas.captureStream(30);
  const options = mime ? { mimeType: mime } : undefined;
  const recorder = new MediaRecorder(stream, options);
  const chunks: Blob[] = [];
  recorder.ondataavailable = (event) => event.data.size && chunks.push(event.data);

  recorder.start();
  const frames = 270;
  for (let frame = 0; frame < frames; frame += 1) {
    const t = frame / (frames - 1);
    const snapshot = snapshots.at(-1) || { image: null, viewport: fallbackViewport };
    drawVideoFrame(ctx, canvas, active, thumbs, sprite, snapshots, snapshot, t, frame, tripLabel);
    await new Promise((resolve) => requestAnimationFrame(resolve));
  }
  recorder.stop();
  await new Promise((resolve) => (recorder.onstop = resolve));

  const type = recorder.mimeType || mime || "video/webm";
  const blob = new Blob(chunks, { type });
  return { blob, filename: `exiftrail-route.${extensionForMime(type)}` };
}

function drawVideoFrame(
  ctx: CanvasRenderingContext2D,
  canvas: HTMLCanvasElement,
  points: PhotoPoint[],
  thumbs: HTMLImageElement[],
  sprite: HTMLImageElement,
  snapshots: MapSnapshot[],
  fallback: MapSnapshot,
  t: number,
  animationFrame: number,
  tripLabel: string,
) {
  ctx.fillStyle = "#0b1020";
  ctx.fillRect(0, 0, canvas.width, canvas.height);

  const localSnapshots = snapshots.slice(0, -1);
  const worldSnapshot = snapshots.at(-1);
  const localIndex = Math.min(localSnapshots.length - 1, Math.floor((t / 0.82) * localSnapshots.length));
  const localSnapshot = localSnapshots[localIndex] || fallback;
  const useWorld = t >= 0.86 && Boolean(worldSnapshot);
  const route = projectViewport(points, useWorld && worldSnapshot ? worldSnapshot.viewport : localSnapshot.viewport, canvas.width, 860);
  if (useWorld && worldSnapshot?.image) {
    ctx.drawImage(worldSnapshot.image, 0, 220, canvas.width, 860);
  } else if (t >= 0.78 && worldSnapshot?.image && localSnapshot.image) {
    ctx.globalAlpha = Math.min(1, (t - 0.78) / 0.12);
    ctx.drawImage(localSnapshot.image, 0, 220, canvas.width, 860);
    ctx.globalAlpha = 1 - Math.min(1, (t - 0.78) / 0.12);
    ctx.drawImage(worldSnapshot.image, 0, 220, canvas.width, 860);
    ctx.globalAlpha = 1;
  } else if (localSnapshot.image) {
    ctx.drawImage(localSnapshot.image, 0, 220, canvas.width, 860);
  }
  if (!localSnapshot.image && !worldSnapshot?.image) {
    ctx.fillStyle = "#dbeafe";
    ctx.fillRect(0, 220, canvas.width, 860);
    ctx.strokeStyle = "rgba(148, 163, 184, .24)";
    ctx.lineWidth = 2;
    for (let x = 0; x < canvas.width; x += 90) {
      ctx.beginPath();
      ctx.moveTo(x, 220);
      ctx.lineTo(x, 1080);
      ctx.stroke();
    }
    for (let y = 220; y < 1080; y += 90) {
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(canvas.width, y);
      ctx.stroke();
    }
  }

  ctx.font = "700 62px Inter, system-ui";
  ctx.fillStyle = "#f8fafc";
  ctx.fillText(tripLabel || "A journey in motion", 72, 120);
  ctx.font = "34px Inter, system-ui";
  ctx.fillStyle = "#94a3b8";
  ctx.fillText(`${formatDate(points[0].time)} - ${formatDate(points.at(-1)!.time)}`, 72, 174);

  ctx.save();
  ctx.translate(0, 220);
  ctx.strokeStyle = "rgba(15,23,42,.26)";
  ctx.lineWidth = 8;
  ctx.lineJoin = "round";
  const currentIndex = Math.min(route.length - 1, Math.floor(t * (route.length - 1)));
  const trailStart = Math.max(0, currentIndex - 80);
  if (useWorld) drawPolyline(ctx, route, 1, 0);

  ctx.strokeStyle = "#0ea5e9";
  ctx.lineWidth = 10;
  ctx.shadowBlur = 12;
  ctx.shadowColor = "#0ea5e9";
  drawPolyline(ctx, route, t, trailStart);
  ctx.shadowBlur = 0;

  const exact = (route.length - 1) * t;
  const next = route[Math.min(route.length - 1, currentIndex + 1)];
  const current = route[currentIndex];
  const mix = exact - currentIndex;
  const x = current.x + (next.x - current.x) * mix;
  const y = current.y + (next.y - current.y) * mix;
  drawVehicle(ctx, x, y, next.x - current.x, animationFrame, sprite);
  ctx.restore();

  ctx.fillStyle = "#111827";
  ctx.fillRect(0, 1320, canvas.width, 600);
  ctx.fillStyle = "#f8fafc";
  ctx.font = "700 44px Inter, system-ui";
  ctx.fillText("A moving story built from photo memories", 72, 1406);
  ctx.font = "30px Inter, system-ui";
  ctx.fillStyle = "#cbd5e1";
  ctx.fillText(`${formatDate(points[0].time)} - ${formatDate(points.at(-1)!.time)}`, 72, 1458);

  thumbs.forEach((img, index) => {
    const x = 72 + (index % 4) * 238;
    const y = 1520 + Math.floor(index / 4) * 160;
    ctx.save();
    ctx.beginPath();
    ctx.roundRect(x, y, 196, 128, 24);
    ctx.clip();
    ctx.drawImage(img, x, y, 196, 128);
    ctx.restore();
  });
}

function drawVehicle(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  dx: number,
  animationFrame: number,
  sprite: HTMLImageElement,
) {
  ctx.save();
  ctx.translate(x, y);
  if (dx < 0) ctx.scale(-1, 1);
  const frameWidth = sprite.width / 8;
  const frame = Math.floor(animationFrame / 3) % 8;
  ctx.drawImage(sprite, frame * frameWidth, 0, frameWidth, sprite.height, -24, -48, 48, 96);
  ctx.restore();
}

function drawPolyline(ctx: CanvasRenderingContext2D, route: Array<{ x: number; y: number }>, progress: number, startIndex = 0) {
  const last = Math.max(1, Math.floor((route.length - 1) * progress));
  ctx.beginPath();
  ctx.moveTo(route[startIndex].x, route[startIndex].y);
  for (let i = startIndex + 1; i <= last; i += 1) ctx.lineTo(route[i].x, route[i].y);
  ctx.stroke();
}

function totalDistance(points: PhotoPoint[]) {
  return points.reduce((sum, point) => sum + point.distanceFromPrevKm, 0);
}

function RouteMap({ points, progress }: { points: PhotoPoint[]; progress: number }) {
  const divRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<L.Map | null>(null);
  const routeRef = useRef<L.Polyline | null>(null);
  const markerRef = useRef<L.Marker | null>(null);
  const cameraModeRef = useRef<"local" | "world">("local");

  useEffect(() => {
    if (!divRef.current || mapRef.current) return;
    mapRef.current = L.map(divRef.current, { zoomControl: false });
    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
      attribution: "&copy; OpenStreetMap contributors",
      maxZoom: 19,
    }).addTo(mapRef.current);
    routeRef.current = L.polyline([], { color: "#0ea5e9", weight: 5 }).addTo(mapRef.current);
    markerRef.current = L.marker([0, 0], { icon: vehicleIcon(), interactive: false }).addTo(mapRef.current);
  }, []);

  useEffect(() => {
    const map = mapRef.current;
    const route = routeRef.current;
    const marker = markerRef.current;
    const active = points.filter((point) => point.enabled && !point.suspicious);
    if (!map || !route || !marker || active.length === 0) return;

    const latLngs = active.map((point) => L.latLng(point.lat, point.lng));
    const visible = latLngs.slice(0, Math.max(1, Math.ceil(latLngs.length * progress)));
    route.setLatLngs(visible);
    const currentIndex = Math.min(active.length - 1, Math.max(0, Math.ceil(active.length * progress) - 1));
    marker.setLatLng(visible.at(-1)!);
    marker.setIcon(vehicleIcon());
    if (progress >= 0.86) {
      if (cameraModeRef.current !== "world") {
        map.fitBounds(L.latLngBounds(latLngs), { padding: [30, 30], animate: true, duration: 0.8 });
        cameraModeRef.current = "world";
      }
    } else {
      cameraModeRef.current = "local";
      map.setView(visible.at(-1)!, localZoom(active), { animate: false });
    }
  }, [points, progress]);

  useEffect(() => {
    const map = mapRef.current;
    const element = divRef.current;
    const active = points.filter((point) => point.enabled && !point.suspicious);
    if (!map || !element || active.length < 2) return;
    activeMapController = {
      capture: async (value, world) => {
        const index = Math.min(active.length - 1, Math.floor(value * (active.length - 1)));
        if (world) map.setView([20, 0], 1, { animate: false });
        else map.setView([active[index].lat, active[index].lng], localZoom(active), { animate: false });
        map.invalidateSize(false);
        routeRef.current?.setStyle({ opacity: 0 });
        markerRef.current?.setOpacity(0);
        try {
          await wait(350);
          const image = await html2canvas(element, { backgroundColor: "#dbeafe", imageTimeout: 3_000, logging: false, useCORS: true });
          const size = map.getSize();
          return { image, viewport: { centerLat: map.getCenter().lat, centerLng: map.getCenter().lng, zoom: map.getZoom(), width: size.x, height: size.y, world } };
        } finally {
          routeRef.current?.setStyle({ opacity: 1 });
          markerRef.current?.setOpacity(1);
        }
      },
    };
    return () => {
      activeMapController = null;
    };
  }, [points]);

  return <div className="map" ref={divRef} />;
}

function App() {
  const [points, setPoints] = useState<PhotoPoint[]>([]);
  const [summary, setSummary] = useState<ScanSummary | null>(null);
  const [progress, setProgress] = useState(1);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("Start with the sample route or choose your own photos. Your photos stay on this device.");
  const [scanProgress, setScanProgress] = useState<ScanProgress | null>(null);
  const [exporting, setExporting] = useState(false);
  const [dateRange, setDateRange] = useState<DateRange>(DEFAULT_DATE_RANGE);

  useEffect(() => () => points.forEach((point) => URL.revokeObjectURL(point.url)), [points]);

  const active = useMemo(() => points.filter((point) => point.enabled && !point.suspicious), [points]);

  async function onFiles(files: FileList | null) {
    if (!files) return;
    await loadFiles([...files]);
  }

  async function loadFiles(files: File[], range = dateRange) {
    if (range.from && range.to && range.from > range.to) {
      setMessage("The start date must be earlier than the end date.");
      return;
    }
    setBusy(true);
    setScanProgress({ done: 0, total: files.filter(isPhoto).length });
    setMessage("Scanning photos locally. Nothing is uploaded.");
    points.forEach((point) => URL.revokeObjectURL(point.url));

    const result = await readPhotos(files, setScanProgress, range);
    setPoints(result.points);
    setSummary(result.summary);
    setBusy(false);
    setScanProgress(null);

    if (result.points.length >= 2) {
      setMessage("Route video preview is ready. Save it and post it anywhere.");
      setTimeout(play, 50);
    } else {
      setMessage("No usable route points found. GPS metadata is required, or try the sample route first.");
    }
  }

  async function trySample() {
    setBusy(true);
    setMessage("Loading the sample route...");
    try {
      const files = await Promise.all(
        SAMPLE_PHOTOS.map(async (name) => {
          const response = await fetch(`./samples/${name}`);
          if (!response.ok) throw new Error("Sample photos could not be loaded.");
          const blob = await response.blob();
          return new File([blob], name, { type: blob.type || "image/jpeg" });
        }),
      );
      setDateRange(SAMPLE_RANGE);
      await loadFiles(files, SAMPLE_RANGE);
    } catch (err) {
      setBusy(false);
      setScanProgress(null);
      setMessage(err instanceof Error ? err.message : "Sample route could not be loaded.");
    }
  }

  async function saveVideo() {
    setExporting(true);
    setMessage("Rendering a vertical route video on this device...");
    try {
      const result = await exportVideo(points, "My travel route");
      await shareOrDownloadVideo(result);
      setMessage("Video ready. Share it to Reels, TikTok, Shorts, Reddit, or Threads.");
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "Video export failed.");
    } finally {
      setExporting(false);
    }
  }

  function play() {
    let start = 0;
    const step = (now: number) => {
      if (!start) start = now;
      const next = Math.min((now - start) / 5000, 1);
      setProgress(next);
      if (next < 1) requestAnimationFrame(step);
    };
    setProgress(0);
    requestAnimationFrame(step);
  }

  return (
    <main className="app-shell">
      <header className="topbar">
        <div className="brand"><img className="brand-mark" src="./assets/brand/satgat-icon.png" alt="" />ExifTrail</div>
        <span className="topbar-note">Private by default</span>
      </header>

      <section className="hero simple">
        <div className="intro">
          <p className="eyebrow">TRAVEL PHOTO → ROUTE VIDEO</p>
          <h1>Turn travel photos into a route video.</h1>
          <p className="lead">
            Use the GPS and capture time already inside your photos to create a shareable 9:16 travel route video.
          </p>
          <div className="consent">
            <b>Private by default. No Google Timeline required.</b>
            <span>
              ExifTrail reads metadata locally, sorts your route, and prepares the video on your device. Photos are never uploaded.
            </span>
          </div>
          <div className="date-range" aria-label="Optional date range">
            <label>
              From
              <input type="date" value={dateRange.from} onChange={(e) => setDateRange((value) => ({ ...value, from: e.target.value }))} />
            </label>
            <label>
              To
              <input type="date" value={dateRange.to} onChange={(e) => setDateRange((value) => ({ ...value, to: e.target.value }))} />
            </label>
          </div>
          <div className="actions minimal">
            <label className="primary cta">
              Allow photos and create video
              <input type="file" multiple accept={ACCEPTED_IMAGES} onChange={(e) => onFiles(e.target.files)} />
            </label>
            <button className="secondary cta" type="button" disabled={busy || exporting} onClick={trySample}>
              {busy ? "Preparing route..." : "Try sample route"}
            </button>
            {active.length >= 2 && (
              <button className="primary cta save-action" type="button" disabled={busy || exporting} onClick={saveVideo}>
                {exporting ? "Rendering video..." : "Save video"}
              </button>
            )}
          </div>
          <p className="format-note">JPG, JPEG, HEIC · GPS metadata creates route points · sample included</p>
          <p className="privacy">Original photos are never edited, moved, or deleted.</p>
        </div>
        <div className="phone" aria-label="Route preview">
          {active.length > 1 ? (
            <RouteMap points={points} progress={progress} />
          ) : (
            <div className="sample-preview">
              <video
                className="sample-video"
                controls
                playsInline
                preload="metadata"
                poster="./docs/screenshots/exiftrail-4-final.jpg"
                aria-label="Sample ExifTrail travel route video"
              >
                <source src="./demo/exiftrail-sample-route.mp4" type="video/mp4" />
                <source src="./demo/exiftrail-sample-route.webm" type="video/webm" />
              </video>
              <span className="sample-label">Sample output</span>
            </div>
          )}
        </div>
      </section>

      <section className="panel compact">
        <div className="stats">
          <div className="status-copy">
            <strong>{message}</strong>
            {summary && (
              <span>
                {active.length} route points · {summary.withoutGps} photos skipped without GPS · {summary.duplicates} near-duplicates removed
                {summary.outsideRange ? ` · ${summary.outsideRange} outside date range` : ""}
              </span>
            )}
          </div>
          {scanProgress && scanProgress.total > 0 && (
            <div className="scan-progress" aria-live="polite">
              <div className="scan-progress-heading">
                <span>Scanning photos</span>
                <strong>{scanProgress.done}/{scanProgress.total}</strong>
              </div>
              <div
                className="scan-progress-track"
                role="progressbar"
                aria-label="Photo scanning progress"
                aria-valuemin={0}
                aria-valuemax={scanProgress.total}
                aria-valuenow={scanProgress.done}
              >
                <span style={{ width: `${(scanProgress.done / scanProgress.total) * 100}%` }} />
              </div>
            </div>
          )}
        </div>
      </section>
    </main>
  );
}

createRoot(document.getElementById("root")!).render(<App />);
