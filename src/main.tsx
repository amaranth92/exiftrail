import React, { useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import * as exifr from "exifr";
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
  duplicates: number;
  suspicious: number;
};

type PrivacyMode = "exact" | "rounded";

const PHOTO_TYPES = new Set(["image/jpeg", "image/jpg", "image/heic", "image/heif"]);
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

async function readPhotos(files: File[]): Promise<{ points: PhotoPoint[]; summary: ScanSummary }> {
  const rows: Array<PhotoPoint | null> = await Promise.all(
    files.filter(isPhoto).map(async (file, index) => {
      try {
        const tags = (await exifr.parse(file, { gps: true, tiff: true, exif: true })) || {};
        const latitude = Number(tags.latitude);
        const longitude = Number(tags.longitude);
        if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return null;
        return {
          id: `${file.name}-${file.lastModified}-${index}`,
          name: file.name,
          lat: latitude,
          lng: longitude,
          time: getTakenAt(tags, file),
          url: URL.createObjectURL(file),
          enabled: true,
          distanceFromPrevKm: 0,
          suspicious: false,
        } satisfies PhotoPoint;
      } catch {
        return null;
      }
    }),
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
      withoutGps: files.filter(isPhoto).length - sorted.length,
      duplicates: normalized.duplicates,
      suspicious: normalized.points.filter((point) => point.suspicious).length,
    },
  };
}

function formatDate(date: Date) {
  return new Intl.DateTimeFormat("en", { month: "short", day: "2-digit", year: "numeric" }).format(date);
}

function dayKey(date: Date) {
  return date.toISOString().slice(0, 10);
}

function displayPoint(point: PhotoPoint, privacyMode: PrivacyMode): PhotoPoint {
  if (privacyMode === "exact") return point;
  return { ...point, lat: Number(point.lat.toFixed(2)), lng: Number(point.lng.toFixed(2)) };
}

function applyPrivacy(points: PhotoPoint[], privacyMode: PrivacyMode, trimEnds: boolean) {
  const trimmed = trimEnds && points.length > 4 ? points.slice(1, -1) : points;
  return trimmed.map((point) => displayPoint(point, privacyMode));
}

function svgThumb(label: string, color: string) {
  return `data:image/svg+xml,${encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" width="320" height="220"><rect width="320" height="220" fill="${color}"/><text x="28" y="120" font-family="Arial" font-size="34" fill="white" font-weight="700">${label}</text></svg>`,
  )}`;
}

function demoPoints(): PhotoPoint[] {
  const rows = [
    ["Perth", -31.9523, 115.8613, "2024-02-01T09:00:00Z", "#0ea5e9"],
    ["Fremantle", -32.0569, 115.7439, "2024-02-01T13:30:00Z", "#14b8a6"],
    ["Rottnest", -32.006, 115.512, "2024-02-02T10:15:00Z", "#f59e0b"],
    ["Margaret River", -33.9536, 115.0739, "2024-02-03T16:10:00Z", "#ef4444"],
    ["Albany", -35.0269, 117.8837, "2024-02-05T11:20:00Z", "#8b5cf6"],
  ] as const;

  return normalizeRoute(
    rows.map(([name, lat, lng, time, color], index) => ({
      id: `demo-${index}`,
      name: `${name}.jpg`,
      lat,
      lng,
      time: new Date(time),
      url: svgThumb(name, color),
      enabled: true,
      distanceFromPrevKm: 0,
      suspicious: false,
    })),
  ).points;
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

async function exportWebm(points: PhotoPoint[], tripLabel: string) {
  const active = points.filter((point) => point.enabled && !point.suspicious);
  if (active.length < 2) throw new Error("Need at least two valid route points.");

  const canvas = document.createElement("canvas");
  canvas.width = 1080;
  canvas.height = 1920;
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("Canvas is not available.");

  const route = project(active, canvas.width, 1220);
  const thumbs = await Promise.all(active.slice(0, 8).map((point) => loadImage(point.url)));
  const stream = canvas.captureStream(30);
  const options = MediaRecorder.isTypeSupported("video/webm") ? { mimeType: "video/webm" } : undefined;
  const recorder = new MediaRecorder(stream, options);
  const chunks: Blob[] = [];
  recorder.ondataavailable = (event) => event.data.size && chunks.push(event.data);

  recorder.start();
  const frames = 270;
  for (let frame = 0; frame < frames; frame += 1) {
    const t = frame / (frames - 1);
    drawVideoFrame(ctx, canvas, route, thumbs, t, tripLabel);
    await new Promise((resolve) => requestAnimationFrame(resolve));
  }
  recorder.stop();
  await new Promise((resolve) => (recorder.onstop = resolve));

  const blob = new Blob(chunks, { type: "video/webm" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = "exiftrail-route.webm";
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}

function drawVideoFrame(
  ctx: CanvasRenderingContext2D,
  canvas: HTMLCanvasElement,
  route: Array<PhotoPoint & { x: number; y: number }>,
  thumbs: HTMLImageElement[],
  t: number,
  tripLabel: string,
) {
  ctx.fillStyle = "#0b1020";
  ctx.fillRect(0, 0, canvas.width, canvas.height);

  ctx.strokeStyle = "rgba(148, 163, 184, .16)";
  ctx.lineWidth = 2;
  for (let x = 90; x < canvas.width; x += 90) {
    ctx.beginPath();
    ctx.moveTo(x, 0);
    ctx.lineTo(x, 1320);
    ctx.stroke();
  }
  for (let y = 90; y < 1320; y += 90) {
    ctx.beginPath();
    ctx.moveTo(0, y);
    ctx.lineTo(canvas.width, y);
    ctx.stroke();
  }

  ctx.font = "700 62px Inter, system-ui";
  ctx.fillStyle = "#f8fafc";
  ctx.fillText("ExifTrail", 72, 120);
  ctx.font = "34px Inter, system-ui";
  ctx.fillStyle = "#94a3b8";
  ctx.fillText(tripLabel || "Travel route rebuilt from local photo metadata", 72, 174);

  ctx.strokeStyle = "rgba(255,255,255,.18)";
  ctx.lineWidth = 12;
  ctx.lineJoin = "round";
  drawPolyline(ctx, route, 1);

  ctx.strokeStyle = "#38bdf8";
  ctx.lineWidth = 14;
  ctx.shadowBlur = 24;
  ctx.shadowColor = "#38bdf8";
  drawPolyline(ctx, route, t);
  ctx.shadowBlur = 0;

  const current = route[Math.min(route.length - 1, Math.floor(t * route.length))];
  ctx.fillStyle = "#f59e0b";
  ctx.beginPath();
  ctx.arc(current.x, current.y, 22, 0, Math.PI * 2);
  ctx.fill();

  ctx.fillStyle = "#111827";
  ctx.fillRect(0, 1320, canvas.width, 600);
  ctx.fillStyle = "#f8fafc";
  ctx.font = "700 44px Inter, system-ui";
  ctx.fillText(`${formatDate(route[0].time)} - ${formatDate(route.at(-1)!.time)}`, 72, 1406);
  ctx.font = "30px Inter, system-ui";
  ctx.fillStyle = "#cbd5e1";
  ctx.fillText(`${route.length} photo points · ${totalDistance(route).toFixed(1)} km`, 72, 1458);

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

function drawPolyline(ctx: CanvasRenderingContext2D, route: Array<{ x: number; y: number }>, progress: number) {
  const last = Math.max(1, Math.floor((route.length - 1) * progress));
  ctx.beginPath();
  ctx.moveTo(route[0].x, route[0].y);
  for (let i = 1; i <= last; i += 1) ctx.lineTo(route[i].x, route[i].y);
  ctx.stroke();
}

function totalDistance(points: PhotoPoint[]) {
  return points.reduce((sum, point) => sum + point.distanceFromPrevKm, 0);
}

function RouteMap({ points, progress }: { points: PhotoPoint[]; progress: number }) {
  const divRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<L.Map | null>(null);
  const routeRef = useRef<L.Polyline | null>(null);
  const markerRef = useRef<L.CircleMarker | null>(null);

  useEffect(() => {
    if (!divRef.current || mapRef.current) return;
    mapRef.current = L.map(divRef.current, { zoomControl: false });
    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
      attribution: "&copy; OpenStreetMap contributors",
      maxZoom: 19,
    }).addTo(mapRef.current);
    routeRef.current = L.polyline([], { color: "#0ea5e9", weight: 5 }).addTo(mapRef.current);
    markerRef.current = L.circleMarker([0, 0], { radius: 8, color: "#f97316", fillOpacity: 1 }).addTo(mapRef.current);
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
    marker.setLatLng(visible.at(-1)!);
    map.fitBounds(L.latLngBounds(latLngs), { padding: [30, 30] });
  }, [points, progress]);

  return <div className="map" ref={divRef} />;
}

function App() {
  const folderInputRef = useRef<HTMLInputElement>(null);
  const [points, setPoints] = useState<PhotoPoint[]>([]);
  const [summary, setSummary] = useState<ScanSummary | null>(null);
  const [progress, setProgress] = useState(1);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("Choose travel photos to rebuild a route locally.");
  const [day, setDay] = useState("all");
  const [tripLabel, setTripLabel] = useState("My travel route");
  const [privacyMode, setPrivacyMode] = useState<PrivacyMode>("exact");
  const [trimEnds, setTrimEnds] = useState(false);

  useEffect(() => () => points.forEach((point) => URL.revokeObjectURL(point.url)), [points]);
  useEffect(() => {
    folderInputRef.current?.setAttribute("webkitdirectory", "");
  }, []);

  const days = useMemo(() => Array.from(new Set(points.map((point) => dayKey(point.time)))), [points]);
  const filtered = useMemo(
    () => points.filter((point) => day === "all" || dayKey(point.time) === day),
    [day, points],
  );
  const privatePoints = useMemo(
    () => applyPrivacy(filtered, privacyMode, trimEnds),
    [filtered, privacyMode, trimEnds],
  );
  const active = useMemo(() => privatePoints.filter((point) => point.enabled && !point.suspicious), [privatePoints]);

  async function onFiles(files: FileList | null) {
    if (!files) return;
    setBusy(true);
    setMessage("Reading EXIF locally. Nothing is uploaded.");
    points.forEach((point) => URL.revokeObjectURL(point.url));
    const result = await readPhotos([...files]);
    setPoints(result.points);
    setSummary(result.summary);
    setDay("all");
    setProgress(1);
    setBusy(false);
    setMessage(result.points.length ? "Route ready. Review points before export." : "No GPS-tagged photos found.");
  }

  function loadDemo() {
    points.forEach((point) => point.url.startsWith("blob:") && URL.revokeObjectURL(point.url));
    const demo = demoPoints();
    setPoints(demo);
    setSummary({ total: demo.length, withGps: demo.length, withoutGps: 0, duplicates: 0, suspicious: 0 });
    setTripLabel("Western Australia demo route");
    setDay("all");
    setProgress(1);
    setMessage("Demo route loaded. Real photos still stay local when you choose your own.");
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
    <main>
      <section className="hero">
        <div>
          <p className="eyebrow">Local-first travel timeline generator</p>
          <h1>ExifTrail</h1>
          <p className="lead">
            Rebuild a viral travel route video from your own photo EXIF GPS and timestamps. No Google
            location history required.
          </p>
          <div className="actions">
            <label className="primary">
              Choose photos
              <input type="file" multiple accept="image/jpeg,image/heic,image/heif" onChange={(e) => onFiles(e.target.files)} />
            </label>
            <label className="primary">
              Choose folder
              <input ref={folderInputRef} type="file" multiple accept="image/jpeg,image/heic,image/heif" onChange={(e) => onFiles(e.target.files)} />
            </label>
            <button disabled={active.length < 2 || busy} onClick={play}>Preview animation</button>
            <button disabled={active.length < 2 || busy} onClick={() => exportWebm(privatePoints, tripLabel).catch((err) => setMessage(err.message))}>
              Export WebM
            </button>
            <button disabled={busy} onClick={loadDemo}>Load demo route</button>
          </div>
          <p className="privacy">No upload by default. Original photos are never edited, moved, or deleted.</p>
        </div>
        <div className="phone">
          {active.length > 1 ? <RouteMap points={privatePoints} progress={progress} /> : <div className="empty">Route preview appears here</div>}
        </div>
      </section>

      <section className="panel">
        <div className="controls" aria-label="Route controls">
          <label>
            Trip or city label
            <input value={tripLabel} onChange={(e) => setTripLabel(e.target.value)} maxLength={60} />
          </label>
          <label>
            Date segment
            <select value={day} onChange={(e) => setDay(e.target.value)}>
              <option value="all">All days</option>
              {days.map((value) => <option value={value} key={value}>{value}</option>)}
            </select>
          </label>
          <label>
            Location privacy
            <select value={privacyMode} onChange={(e) => setPrivacyMode(e.target.value as PrivacyMode)}>
              <option value="exact">Exact coordinates</option>
              <option value="rounded">Soften to about 1 km</option>
            </select>
          </label>
          <label className="check">
            <input type="checkbox" checked={trimEnds} onChange={(e) => setTrimEnds(e.target.checked)} />
            Hide first and last stop
          </label>
        </div>
        <div className="stats">
          <strong>{message}</strong>
          {summary && (
            <span>
              {active.length} export points · {days.length || 0} day segments · {summary.withGps}/{summary.total} with GPS · {summary.withoutGps} skipped · {summary.duplicates} near-duplicates ·{" "}
              {summary.suspicious} jump warnings
            </span>
          )}
        </div>
        <div className="points">
          {filtered.map((point) => (
            <label className={point.suspicious ? "point suspicious" : "point"} key={point.id}>
              <input
                type="checkbox"
                checked={point.enabled}
                onChange={(e) => setPoints((rows) => rows.map((row) => row.id === point.id ? { ...row, enabled: e.target.checked } : row))}
              />
              <img src={point.url} alt="" />
              <span>
                <b>{point.name}</b>
                <small>{formatDate(point.time)} · {point.lat.toFixed(4)}, {point.lng.toFixed(4)}</small>
                {point.suspicious && <em>Possible GPS jump. Excluded from route/export.</em>}
              </span>
            </label>
          ))}
        </div>
      </section>
    </main>
  );
}

createRoot(document.getElementById("root")!).render(<App />);
