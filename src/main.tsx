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
const EXIF_CONCURRENCY = Math.max(4, Math.min(10, (navigator.hardwareConcurrency || 4) + 2));
const ACCEPTED_IMAGES = "image/*,.jpg,.jpeg,.heic,.heif";

function dateInputValue(date: Date) {
  return [date.getFullYear(), String(date.getMonth() + 1).padStart(2, "0"), String(date.getDate()).padStart(2, "0")].join("-");
}

const today = new Date();
const DEFAULT_DATE_RANGE: DateRange = { from: `${today.getFullYear()}-01-01`, to: dateInputValue(today) };

const PHOTO_TYPES = new Set(["image/jpeg", "image/jpg", "image/heic", "image/heif"]);
const ROUTE_SPRITE = "./assets/characters/satgat-walk-8.png";
const TRAIL_SEGMENTS = 8;
const TRAIL_LENGTH = 0.16;
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
  let lastReported = 0;
  const reportEvery = Math.max(1, Math.ceil(photos.length / 100));
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
        if (done === photos.length || done - lastReported >= reportEvery) {
          lastReported = done;
          onProgress?.({ done, total: photos.length });
        }
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
  const crop = viewportCrop(viewport, width, height);
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
      x: ((viewport.width / 2 + (x - centerX) - crop.left) * width) / crop.width,
      y: ((viewport.height / 2 + (y - centerY) - crop.top) * height) / crop.height,
    };
  });
}

function viewportCrop(viewport: GeoViewport, width: number, height: number) {
  const sourceAspect = viewport.width / viewport.height;
  const destinationAspect = width / height;
  if (sourceAspect > destinationAspect) {
    const cropWidth = viewport.height * destinationAspect;
    return { left: (viewport.width - cropWidth) / 2, top: 0, width: cropWidth, height: viewport.height };
  }
  const cropHeight = viewport.width / destinationAspect;
  return { left: 0, top: (viewport.height - cropHeight) / 2, width: viewport.width, height: cropHeight };
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
    // Keep enough camera checkpoints to crossfade the map instead of jumping
    // between a handful of screenshots while the route is moving.
    for (let index = 0; index < 13; index += 1) {
      snapshots.push(await activeMapController.capture(index / 15, false));
    }
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
  const localSnapshot = localSnapshots.at(-1) || fallback;
  const localPosition = Math.min(1, t / 0.8) * Math.max(0, localSnapshots.length - 1);
  const localIndex = Math.min(localSnapshots.length - 1, Math.floor(localPosition));
  const localNext = localSnapshots[Math.min(localSnapshots.length - 1, localIndex + 1)] || localSnapshot;
  const localMix = localPosition - localIndex;
  const transitionMix = Math.max(0, Math.min(1, (t - 0.78) / 0.14));
  const localFrame = t < 0.78 ? { first: localSnapshots[localIndex] || fallback, second: localNext, mix: localMix } : { first: localSnapshot, second: localSnapshot, mix: 0 };
  const useWorld = t >= 0.92 && Boolean(worldSnapshot);

  if (t < 0.78) {
    drawSnapshot(ctx, localFrame.first, localFrame.second, localFrame.mix, 0, 220, canvas.width, 860);
  } else if (worldSnapshot && t < 0.92) {
    drawSnapshot(ctx, localSnapshot, worldSnapshot, transitionMix, 0, 220, canvas.width, 860);
  } else if (worldSnapshot?.image) {
    drawSnapshotImage(ctx, worldSnapshot, 0, 220, canvas.width, 860);
  } else if (localSnapshot.image) {
    drawSnapshotImage(ctx, localSnapshot, 0, 220, canvas.width, 860);
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

  if (useWorld && worldSnapshot) {
    drawRouteLayer(ctx, points, worldSnapshot, t, animationFrame, sprite, canvas.width, 860, false, 1);
  } else if (t >= 0.78 && worldSnapshot) {
    drawRouteLayer(ctx, points, localSnapshot, t, animationFrame, sprite, canvas.width, 860, true, 1 - transitionMix);
    drawRouteLayer(ctx, points, worldSnapshot, t, animationFrame, sprite, canvas.width, 860, true, transitionMix);
    drawRouteLayer(ctx, points, worldSnapshot, 1, animationFrame, sprite, canvas.width, 860, false, transitionMix * 0.86);
  } else {
    drawRouteLayer(ctx, points, localFrame.first, t, animationFrame, sprite, canvas.width, 860, true, 1 - localFrame.mix);
    drawRouteLayer(ctx, points, localFrame.second, t, animationFrame, sprite, canvas.width, 860, true, localFrame.mix);
  }

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

function drawSnapshot(
  ctx: CanvasRenderingContext2D,
  first: MapSnapshot,
  second: MapSnapshot,
  mix: number,
  x: number,
  y: number,
  width: number,
  height: number,
) {
  if (first.image) {
    ctx.globalAlpha = 1 - mix;
    drawSnapshotImage(ctx, first, x, y, width, height);
  }
  if (second.image && mix > 0) {
    ctx.globalAlpha = mix;
    drawSnapshotImage(ctx, second, x, y, width, height);
  }
  ctx.globalAlpha = 1;
}

function drawSnapshotImage(ctx: CanvasRenderingContext2D, snapshot: MapSnapshot, x: number, y: number, width: number, height: number) {
  if (!snapshot.image) return;
  const source = viewportCrop(snapshot.viewport, width, height);
  const scaleX = snapshot.image.width / snapshot.viewport.width;
  const scaleY = snapshot.image.height / snapshot.viewport.height;
  ctx.drawImage(
    snapshot.image,
    source.left * scaleX,
    source.top * scaleY,
    source.width * scaleX,
    source.height * scaleY,
    x,
    y,
    width,
    height,
  );
}

function drawRouteLayer(
  ctx: CanvasRenderingContext2D,
  points: PhotoPoint[],
  snapshot: MapSnapshot,
  progress: number,
  animationFrame: number,
  sprite: HTMLImageElement,
  width: number,
  height: number,
  showCharacter: boolean,
  alpha: number,
) {
  const route = projectViewport(points, snapshot.viewport, width, height);
  ctx.save();
  ctx.globalAlpha = alpha;
  ctx.translate(0, 220);
  ctx.beginPath();
  ctx.rect(0, 0, width, height);
  ctx.clip();
  ctx.strokeStyle = "#0ea5e9";
  ctx.lineWidth = 7;
  ctx.lineJoin = "round";
  ctx.lineCap = "round";
  ctx.shadowBlur = 8;
  ctx.shadowColor = "rgba(14, 165, 233, .48)";
  if (showCharacter) drawTrail(ctx, route, progress);
  else drawPolyline(ctx, route, 1);
  ctx.shadowBlur = 0;

  if (showCharacter) {
    const location = routeLocation(route, progress);
    const current = route[location.index];
    const next = route[Math.min(route.length - 1, location.index + 1)];
    const x = current.x + (next.x - current.x) * location.fraction;
    const y = current.y + (next.y - current.y) * location.fraction;
    drawVehicle(ctx, x, y, animationFrame, sprite);
  }
  ctx.restore();
}

function drawVehicle(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  animationFrame: number,
  sprite: HTMLImageElement,
) {
  ctx.save();
  ctx.translate(x, y);
  const frameWidth = sprite.width / 8;
  const frame = Math.floor(animationFrame / 4) % 8;
  ctx.drawImage(sprite, frame * frameWidth, 0, frameWidth, sprite.height, -24, -48, 48, 96);
  ctx.restore();
}

function routeLocation<T>(route: T[], progress: number) {
  if (route.length < 2) return { index: 0, fraction: 0 };
  const exact = Math.max(0, Math.min(1, progress)) * (route.length - 1);
  const index = Math.min(route.length - 2, Math.floor(exact));
  return { index, fraction: exact - index };
}

function routePoint<T extends { x: number; y: number }>(route: T[], progress: number) {
  const location = routeLocation(route, progress);
  const current = route[location.index];
  const next = route[Math.min(route.length - 1, location.index + 1)];
  return {
    x: current.x + (next.x - current.x) * location.fraction,
    y: current.y + (next.y - current.y) * location.fraction,
  };
}

function drawTrail(ctx: CanvasRenderingContext2D, route: Array<{ x: number; y: number }>, progress: number) {
  if (route.length < 2 || progress <= 0) return;
  const start = Math.max(0, progress - TRAIL_LENGTH);
  const steps = Math.max(12, Math.ceil(route.length * 1.5));
  for (let index = 0; index < steps; index += 1) {
    const fromProgress = start + (progress - start) * (index / steps);
    const toProgress = start + (progress - start) * ((index + 1) / steps);
    const from = routePoint(route, fromProgress);
    const to = routePoint(route, toProgress);
    const headMix = (index + 1) / steps;
    const gradient = ctx.createLinearGradient(from.x, from.y, to.x, to.y);
    gradient.addColorStop(0, `rgba(125, 211, 252, ${0.02 + headMix * 0.32})`);
    gradient.addColorStop(1, `rgba(2, 132, 199, ${0.48 + headMix * 0.52})`);
    ctx.strokeStyle = gradient;
    ctx.lineWidth = 6 + headMix * 4;
    ctx.shadowBlur = 12 + headMix * 10;
    ctx.shadowColor = `rgba(56, 189, 248, ${0.28 + headMix * 0.58})`;
    ctx.beginPath();
    ctx.moveTo(from.x, from.y);
    ctx.lineTo(to.x, to.y);
    ctx.stroke();
  }
  ctx.shadowBlur = 0;
}

function drawPolyline(ctx: CanvasRenderingContext2D, route: Array<{ x: number; y: number }>, progress: number) {
  if (route.length < 2) return;
  const location = routeLocation(route, progress);
  ctx.beginPath();
  ctx.moveTo(route[0].x, route[0].y);
  for (let i = 1; i <= location.index; i += 1) ctx.lineTo(route[i].x, route[i].y);
  const current = route[location.index];
  const next = route[Math.min(route.length - 1, location.index + 1)];
  ctx.lineTo(current.x + (next.x - current.x) * location.fraction, current.y + (next.y - current.y) * location.fraction);
  ctx.stroke();
}

function totalDistance(points: PhotoPoint[]) {
  return points.reduce((sum, point) => sum + point.distanceFromPrevKm, 0);
}

function RouteMap({ points, progress }: { points: PhotoPoint[]; progress: number }) {
  const divRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<L.Map | null>(null);
  const routeRef = useRef<L.Polyline | null>(null);
  const trailLayersRef = useRef<L.Polyline[]>([]);
  const markerRef = useRef<L.Marker | null>(null);
  const cameraModeRef = useRef<"local" | "world">("local");
  const active = points.filter((point) => point.enabled && !point.suspicious);

  useEffect(() => {
    if (!divRef.current || mapRef.current) return;
    mapRef.current = L.map(divRef.current, { zoomControl: false });
    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
      attribution: "&copy; OpenStreetMap contributors",
      maxZoom: 19,
    }).addTo(mapRef.current);
    trailLayersRef.current = Array.from({ length: TRAIL_SEGMENTS }, (_, index) => L.polyline([], {
      color: index < TRAIL_SEGMENTS - 1 ? "#7dd3fc" : "#0284c7",
      weight: 6 + index / TRAIL_SEGMENTS * 4,
      opacity: 0,
      lineCap: "round",
      lineJoin: "round",
      className: "route-trail-segment",
    }).addTo(mapRef.current!));
    routeRef.current = L.polyline([], { color: "#0ea5e9", weight: 8, opacity: 0, lineCap: "round", lineJoin: "round", className: "route-complete" }).addTo(mapRef.current);
    markerRef.current = L.marker([0, 0], { icon: vehicleIcon(), interactive: false }).addTo(mapRef.current);
  }, []);

  useEffect(() => {
    const map = mapRef.current;
    const route = routeRef.current;
    const marker = markerRef.current;
    const active = points.filter((point) => point.enabled && !point.suspicious);
    if (!map || !route || !marker || active.length === 0) return;

    const latLngs = active.map((point) => L.latLng(point.lat, point.lng));
    const location = routeLocation(latLngs, progress);
    const current = latLngs[location.index];
    const next = latLngs[Math.min(latLngs.length - 1, location.index + 1)];
    const point = L.latLng(
      current.lat + (next.lat - current.lat) * location.fraction,
      current.lng + (next.lng - current.lng) * location.fraction,
    );
    const showingFullRoute = progress >= 0.92;
    route.setLatLngs(showingFullRoute ? latLngs : []);
    route.setStyle({ opacity: showingFullRoute ? 1 : 0 });
    const trailStart = Math.max(0, progress - TRAIL_LENGTH);
    trailLayersRef.current.forEach((layer, index) => {
      const fromProgress = trailStart + (progress - trailStart) * (index / TRAIL_SEGMENTS);
      const toProgress = trailStart + (progress - trailStart) * ((index + 1) / TRAIL_SEGMENTS);
      const from = interpolateLatLng(latLngs, fromProgress);
      const to = interpolateLatLng(latLngs, toProgress);
      const opacity = showingFullRoute ? 0 : progress <= 0 ? 0 : 0.04 + (index / TRAIL_SEGMENTS) * 0.96;
      layer.setLatLngs([from, to]);
      layer.setStyle({
        opacity,
        color: index < TRAIL_SEGMENTS - 1 ? "#7dd3fc" : "#0284c7",
        weight: 6 + index / TRAIL_SEGMENTS * 4,
      });
    });
    marker.setLatLng(point);
    if (progress >= 0.92) {
      if (cameraModeRef.current !== "world") {
        map.fitBounds(L.latLngBounds(latLngs), { padding: [30, 30], animate: true, duration: 0.8 });
        cameraModeRef.current = "world";
      }
    } else {
      cameraModeRef.current = "local";
      map.setView(point, localZoom(active), { animate: false });
    }
  }, [points, progress]);

  useEffect(() => {
    const map = mapRef.current;
    const element = divRef.current;
    const active = points.filter((point) => point.enabled && !point.suspicious);
    if (!map || !element || active.length < 2) return;
    activeMapController = {
      capture: async (value, world) => {
        const location = routeLocation(active, value);
        const current = active[location.index];
        const next = active[Math.min(active.length - 1, location.index + 1)];
        const lat = current.lat + (next.lat - current.lat) * location.fraction;
        const lng = current.lng + (next.lng - current.lng) * location.fraction;
        if (world) map.setView([20, 0], 1, { animate: false });
        else map.setView([lat, lng], localZoom(active), { animate: false });
        map.invalidateSize(false);
        const routeOpacity = routeRef.current?.options.opacity ?? 0;
        const trailOpacity = trailLayersRef.current.map((layer) => layer.options.opacity ?? 0);
        routeRef.current?.setStyle({ opacity: 0 });
        trailLayersRef.current.forEach((layer) => layer.setStyle({ opacity: 0 }));
        markerRef.current?.setOpacity(0);
        try {
          await wait(220);
          const image = await html2canvas(element, { backgroundColor: "#dbeafe", imageTimeout: 3_000, logging: false, useCORS: true });
          const size = map.getSize();
          return { image, viewport: { centerLat: map.getCenter().lat, centerLng: map.getCenter().lng, zoom: map.getZoom(), width: size.x, height: size.y, world } };
        } finally {
          routeRef.current?.setStyle({ opacity: routeOpacity });
          trailLayersRef.current.forEach((layer, index) => layer.setStyle({ opacity: trailOpacity[index] ?? 0 }));
          markerRef.current?.setOpacity(1);
        }
      },
    };
    return () => {
      activeMapController = null;
    };
  }, [points]);

  const currentIndex = Math.min(Math.max(0, Math.floor(progress * Math.max(0, active.length - 1))), Math.max(0, active.length - 1));
  return (
    <div className="route-map-shell">
      <div className="map" ref={divRef} />
      <div className="map-overlay" aria-hidden="true">
        <span>ROUTE PREVIEW</span>
        <strong>{active[currentIndex] ? formatDate(active[currentIndex].time) : "Waiting for photos"}</strong>
        <small>{Math.round(progress * 100)}% complete</small>
      </div>
    </div>
  );
}

function interpolateLatLng(route: L.LatLng[], progress: number) {
  const location = routeLocation(route, progress);
  const current = route[location.index];
  const next = route[Math.min(route.length - 1, location.index + 1)];
  return L.latLng(
    current.lat + (next.lat - current.lat) * location.fraction,
    current.lng + (next.lng - current.lng) * location.fraction,
  );
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
  const previewFrameRef = useRef<number | null>(null);

  useEffect(() => () => {
    if (previewFrameRef.current !== null) cancelAnimationFrame(previewFrameRef.current);
    points.forEach((point) => URL.revokeObjectURL(point.url));
  }, [points]);

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
    if (previewFrameRef.current !== null) cancelAnimationFrame(previewFrameRef.current);
    previewFrameRef.current = null;
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
    if (previewFrameRef.current !== null) cancelAnimationFrame(previewFrameRef.current);
    previewFrameRef.current = null;
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
    if (previewFrameRef.current !== null) cancelAnimationFrame(previewFrameRef.current);
    let start = 0;
    let lastPaint = 0;
    const step = (now: number) => {
      if (!start) start = now;
      const next = Math.min((now - start) / 5000, 1);
      if (next >= 1 || now - lastPaint >= 33) {
        lastPaint = now;
        setProgress(next);
      }
      if (next < 1) previewFrameRef.current = requestAnimationFrame(step);
      else previewFrameRef.current = null;
    };
    setProgress(0);
    previewFrameRef.current = requestAnimationFrame(step);
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
