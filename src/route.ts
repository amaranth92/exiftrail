export type RoutePoint = {
  lat: number;
  lng: number;
  time: Date;
  distanceFromPrevKm: number;
  suspicious: boolean;
};

export const MIN_POINT_GAP_M = 50;
export const SUSPICIOUS_SPEED_KMH = 1000;

export function haversineKm(a: Pick<RoutePoint, "lat" | "lng">, b: Pick<RoutePoint, "lat" | "lng">) {
  const rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad;
  const dLng = (b.lng - a.lng) * rad;
  const lat1 = a.lat * rad;
  const lat2 = b.lat * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) ** 2;
  return 6371 * 2 * Math.asin(Math.sqrt(h));
}

export function normalizeRoute<T extends RoutePoint>(points: T[]) {
  const sorted = [...points].sort((a, b) => +a.time - +b.time);
  const deduped: T[] = [];
  let duplicates = 0;

  for (const point of sorted) {
    const prev = deduped.at(-1);
    if (prev && haversineKm(prev, point) * 1000 < MIN_POINT_GAP_M) {
      duplicates += 1;
      continue;
    }
    if (prev) {
      point.distanceFromPrevKm = haversineKm(prev, point);
      const hours = Math.max((+point.time - +prev.time) / 36e5, 0.01);
      point.suspicious = point.distanceFromPrevKm / hours > SUSPICIOUS_SPEED_KMH;
    }
    deduped.push(point);
  }

  return { points: deduped, duplicates };
}
