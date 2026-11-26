/**
 * Horários das outras redes no servidor: a mesma conversão de GTFS que tools/build_transit_packs.py faz, em Node,
 * para a Cloud Function diária. O resultado (gzip de um binário "TPK1") é lido na app por TransitPack.kt.
 *
 * Formato (inteiros em varint, texto em UTF-8 com o tamanho em varint):
 *   "TPK1", código, versão
 *   serviços: n; nome, dias (bit 0 = segunda ... bit 6 = domingo), início, fim, n adds + datas, n removes + datas
 *   linhas: n; route_id, número, nome, cor ("RRGGBB" ou ""), route_type
 *   paragens: n; id, nome, lat * 1e6, (lon + 180) * 1e6
 *   traçados: n; polyline do Google (precisão 5), já simplificada
 *   percursos: n; linha, sentido, destino, traçado + 1 (0 = sem), n paragens + índices,
 *              n perfis + (n paragens - 1) diferenças em segundos,
 *              n viagens + (trip_id, partida em s, perfil, serviço)
 */
const zlib = require("zlib");
const AdmZip = require("adm-zip");

/** Os operadores e de onde vem o GTFS de cada um. */
const TML_PLANS_URL = "https://go.tmlmobilidade.pt/hub/api/v1/0XJC9/plans";
const TML_CODES = ["HF16N", "A3H3M", "KB1F6", "7NTB1", "LTP61"];
/**
 * STCP: o portal de dados do Porto mudou de sítio e o ficheiro oficial deixou de estar acessível; fica a cópia do
 * Mobility Database (paragens, linhas e traçados; horários fora de prazo).
 */
const FIXED_SOURCES = {
  STCP: "https://files.mobilitydatabase.org/mdb-2148/latest.zip",
};
// O Metro do Porto vem do OpenStreetMap (osm.js): o GTFS de 2024 não tinha a linha F e trazia a B baralhada.

// ── CSV ─────────────────────────────────────────────────────────────────────

/** Linhas de um CSV do GTFS como objetos (aspas, vírgulas e quebras de linha dentro de aspas, BOM). */
function parseCsv(text) {
  if (text.charCodeAt(0) === 0xfeff) text = text.slice(1);
  const rows = [];
  let field = "";
  let row = [];
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (quoted) {
      if (ch === '"') {
        if (text[i + 1] === '"') { field += '"'; i++; } else quoted = false;
      } else field += ch;
    } else if (ch === '"') quoted = true;
    else if (ch === ",") { row.push(field); field = ""; }
    else if (ch === "\n" || ch === "\r") {
      if (ch === "\r" && text[i + 1] === "\n") i++;
      row.push(field); field = "";
      if (row.length > 1 || row[0] !== "") rows.push(row);
      row = [];
    } else field += ch;
  }
  if (field !== "" || row.length) { row.push(field); rows.push(row); }
  if (!rows.length) return [];
  const header = rows[0].map((h) => h.trim());
  return rows.slice(1).map((r) => {
    const o = {};
    header.forEach((h, k) => { o[h] = r[k] !== undefined ? r[k] : ""; });
    return o;
  });
}

/** Leitor dos ficheiros de um zip de GTFS (alguns trazem tudo dentro de uma pasta). */
function gtfsReader(buffer) {
  const zip = new AdmZip(buffer);
  const entries = zip.getEntries();
  let prefix = "";
  if (!entries.some((e) => e.entryName === "stops.txt")) {
    const s = entries.find((e) => e.entryName.endsWith("/stops.txt"));
    if (s) prefix = s.entryName.slice(0, -"stops.txt".length);
  }
  return (name) => {
    const entry = zip.getEntry(prefix + name);
    return entry ? parseCsv(entry.getData().toString("utf8")) : [];
  };
}

// ── Geometria ───────────────────────────────────────────────────────────────

/** Douglas-Peucker em metros (aproximação plana local), como o script Python. */
function simplify(points, tol = 4.0) {
  if (points.length < 3) return points;
  const kx = 111320 * Math.cos((points[0][0] * Math.PI) / 180);
  const ky = 110540;
  const xy = points.map((p) => [p[1] * kx, p[0] * ky]);
  const keep = new Array(points.length).fill(false);
  keep[0] = keep[points.length - 1] = true;
  const stack = [[0, points.length - 1]];
  while (stack.length) {
    const [a, b] = stack.pop();
    const [ax, ay] = xy[a];
    const [bx, by] = xy[b];
    const dx = bx - ax;
    const dy = by - ay;
    const L = dx * dx + dy * dy;
    let best = 0;
    let idx = -1;
    for (let i = a + 1; i < b; i++) {
      const [px, py] = xy[i];
      let d;
      if (L === 0) d = Math.hypot(px - ax, py - ay);
      else {
        const t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / L));
        d = Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
      }
      if (d > best) { best = d; idx = i; }
    }
    if (best > tol && idx > 0) {
      keep[idx] = true;
      stack.push([a, idx], [idx, b]);
    }
  }
  return points.filter((_, i) => keep[i]);
}

/** Google "encoded polyline" (precisão 5). */
function encodePolyline(points) {
  let out = "";
  let plat = 0;
  let plon = 0;
  for (const [lat, lon] of points) {
    const ilat = Math.round(lat * 1e5);
    const ilon = Math.round(lon * 1e5);
    for (let v of [ilat - plat, ilon - plon]) {
      v = v < 0 ? ~(v << 1) : v << 1;
      while (v >= 0x20) {
        out += String.fromCharCode((0x20 | (v & 0x1f)) + 63);
        v >>= 5;
      }
      out += String.fromCharCode(v + 63);
    }
    plat = ilat;
    plon = ilon;
  }
  return out;
}

// ── Binário ─────────────────────────────────────────────────────────────────

class Writer {
  constructor() { this.parts = []; this.cur = []; }
  byte(b) { this.cur.push(b); if (this.cur.length >= 65536) this.flush(); }
  flush() { if (this.cur.length) { this.parts.push(Buffer.from(this.cur)); this.cur = []; } }
  varint(n) {
    if (!Number.isInteger(n) || n < 0) throw new Error(`varint inválido: ${n}`);
    while (true) {
      const b = n % 128;
      n = Math.floor(n / 128);
      if (n) this.byte(b | 0x80); else { this.byte(b); return; }
    }
  }
  text(s) {
    const data = Buffer.from(s, "utf8");
    this.varint(data.length);
    this.flush();
    this.parts.push(data);
  }
  ints(xs) { this.varint(xs.length); for (const x of xs) this.varint(x); }
  raw(s) { for (const c of Buffer.from(s, "ascii")) this.byte(c); }
  buffer() { this.flush(); return Buffer.concat(this.parts); }
}

function secs(text) {
  const [h, m, s] = text.trim().split(":").map(Number);
  return h * 3600 + m * 60 + (s || 0);
}

function dateInt(d) {
  return d.getUTCFullYear() * 10000 + (d.getUTCMonth() + 1) * 100 + d.getUTCDate();
}

/**
 * GTFS (zip) → pacote TPK1 sem compressão. [today]: os serviços que acabaram antes da véspera ficam de fora.
 * Devolve também uns números para o registo.
 */
function buildPack(code, zipBuffer, today = new Date()) {
  return buildPackFromRows(code, gtfsReader(zipBuffer), today);
}

/** Comprimento de um caminho em metros (aproximação plana). */
function pathMetres(points) {
  let total = 0;
  for (let i = 1; i < points.length; i++) {
    const [a, b] = [points[i - 1], points[i]];
    const k = Math.cos(((a[0] + b[0]) / 2) * Math.PI / 180);
    total += Math.hypot((b[0] - a[0]) * 111320, (b[1] - a[1]) * 111320 * k);
  }
  return total;
}

/**
 * O mesmo, a partir de um leitor de ficheiros GTFS ([rows](nome) → linhas): serve também para dados que não vêm num
 * zip (o Metro do Porto, do OpenStreetMap).
 */
function buildPackFromRows(code, rows, today = new Date()) {
  const since = dateInt(new Date(today.getTime() - 86400000));
  const feed = rows("feed_info.txt")[0] || {};
  const version = feed.feed_version || feed.feed_end_date || String(dateInt(today));

  const cal = new Map(rows("calendar.txt").map((r) => [r.service_id, r]));
  const exc = new Map();
  for (const r of rows("calendar_dates.txt")) {
    if (!exc.has(r.service_id)) exc.set(r.service_id, [[], []]);
    exc.get(r.service_id)[r.exception_type.trim() === "1" ? 0 : 1].push(Number(r.date));
  }
  const allServices = [...new Set([...cal.keys(), ...exc.keys()])].sort();
  const active = allServices.filter((s) => {
    const c = cal.get(s);
    const adds = exc.has(s) ? exc.get(s)[0] : [];
    return (c && Number(c.end_date) >= since) || adds.some((d) => d >= since);
  });
  const svcIdx = new Map(active.map((s, i) => [s, i]));

  const routes = new Map();
  for (const r of rows("routes.txt")) {
    let color = (r.route_color || "").trim().toUpperCase();
    if (color.length !== 6) color = "";
    const short = (r.route_short_name || "").trim() || (r.line_short_name || "").trim();
    const name = (r.route_long_name || "").trim() || (r.line_long_name || "").trim();
    routes.set(r.route_id, [short, name, color, Number((r.route_type || "3").trim() || 3)]);
  }

  const stops = new Map();
  for (const r of rows("stops.txt")) {
    const lt = (r.location_type || "0").trim();
    if (lt !== "" && lt !== "0") continue;
    const lat = Number(r.stop_lat);
    const lon = Number(r.stop_lon);
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) continue;
    stops.set(r.stop_id, [r.stop_name.trim(), lat, lon]);
  }

  const shapesRaw = new Map();
  for (const r of rows("shapes.txt")) {
    if (!shapesRaw.has(r.shape_id)) shapesRaw.set(r.shape_id, []);
    shapesRaw.get(r.shape_id).push([Number(r.shape_pt_sequence), Number(r.shape_pt_lat), Number(r.shape_pt_lon)]);
  }

  const trips = new Map();
  for (const r of rows("trips.txt")) {
    if (!routes.has(r.route_id)) continue;
    trips.set(r.trip_id, [r.route_id, Number((r.direction_id || "0").trim() || 0), r.service_id,
      (r.trip_headsign || "").trim(), (r.shape_id || "").trim()]);
  }

  const times = new Map();
  for (const r of rows("stop_times.txt")) {
    if (!trips.has(r.trip_id) || !stops.has(r.stop_id)) continue;
    const t = (r.arrival_time || "").trim() || (r.departure_time || "").trim();
    if (!t) continue;
    if (!times.has(r.trip_id)) times.set(r.trip_id, []);
    times.get(r.trip_id).push([Number(r.stop_sequence), r.stop_id, secs(t)]);
  }

  const stopIdx = new Map();
  const shapeIdx = new Map();
  const patterns = new Map();
  // Traçados estragados (pontos fora de ordem, como o da linha B do Metro do Porto no GTFS de 2024): muito mais
  // compridos do que o caminho pelas paragens. Sem eles a app desenha a linha pelas paragens.
  const shapeOk = new Map();
  const usableShape = (shape, seq) => {
    if (!shape || !shapesRaw.has(shape)) return "";
    const key = `${shape}|${seq.length}|${seq[0]}|${seq[seq.length - 1]}`;
    if (!shapeOk.has(key)) {
      const pts = shapesRaw.get(shape).slice().sort((a, b) => a[0] - b[0]).map((x) => [x[1], x[2]]);
      const viaStops = pathMetres(seq.map((s) => [stops.get(s)[1], stops.get(s)[2]]));
      shapeOk.set(key, pathMetres(pts) <= viaStops * 1.6 + 1000);
    }
    return shapeOk.get(key) ? shape : "";
  };

  for (const [tid, lst] of times) {
    const [route, d, svc, headsign0, shape0] = trips.get(tid);
    lst.sort((a, b) => a[0] - b[0]);
    const seq = lst.map((x) => x[1]);
    if (seq.length < 2) continue;
    const shape = usableShape(shape0, seq);
    const headsign = headsign0 || stops.get(seq[seq.length - 1])[0];
    const start = lst[0][2];
    const offs = lst.map((x) => x[2] - start);
    const diffs = [];
    for (let i = 1; i < offs.length; i++) diffs.push(Math.max(0, offs[i] - offs[i - 1]));
    const key = JSON.stringify([route, d, headsign, shape, seq]);
    if (!patterns.has(key)) patterns.set(key, { route, d, headsign, shape, seq, profiles: new Map(), trips: [] });
    const p = patterns.get(key);
    const pk = diffs.join(",");
    if (!p.profiles.has(pk)) p.profiles.set(pk, { idx: p.profiles.size, diffs });
    if (svcIdx.has(svc)) p.trips.push([tid, start, p.profiles.get(pk).idx, svcIdx.get(svc)]);
    for (const s of seq) if (!stopIdx.has(s)) stopIdx.set(s, stopIdx.size);
    if (shape && shapesRaw.has(shape) && !shapeIdx.has(shape)) shapeIdx.set(shape, shapeIdx.size);
  }
  for (const s of stops.keys()) if (!stopIdx.has(s)) stopIdx.set(s, stopIdx.size);

  const routeList = [...routes.keys()];
  const routePos = new Map(routeList.map((r, i) => [r, i]));

  const w = new Writer();
  w.raw("TPK1");
  w.text(code);
  w.text(String(version));

  w.varint(active.length);
  const DAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"];
  for (const s of active) {
    const c = cal.get(s);
    let days = 0;
    let start = 0;
    let end = 0;
    if (c) {
      DAYS.forEach((k, i) => { if ((c[k] || "0").trim() === "1") days |= 1 << i; });
      start = Number(c.start_date);
      end = Number(c.end_date);
    }
    const [adds, removes] = exc.get(s) || [[], []];
    w.text(s);
    w.varint(days);
    w.varint(start);
    w.varint(end);
    w.ints([...adds].sort((a, b) => a - b));
    w.ints([...removes].sort((a, b) => a - b));
  }

  w.varint(routeList.length);
  for (const r of routeList) {
    const [short, name, color, type] = routes.get(r);
    w.text(r); w.text(short); w.text(name); w.text(color); w.varint(type);
  }

  const orderedStops = [...stopIdx.keys()].sort((a, b) => stopIdx.get(a) - stopIdx.get(b));
  w.varint(orderedStops.length);
  for (const s of orderedStops) {
    const [name, lat, lon] = stops.get(s);
    w.text(s); w.text(name);
    w.varint(Math.round(lat * 1e6));
    w.varint(Math.round((lon + 180) * 1e6));
  }

  const orderedShapes = [...shapeIdx.keys()].sort((a, b) => shapeIdx.get(a) - shapeIdx.get(b));
  w.varint(orderedShapes.length);
  for (const sh of orderedShapes) {
    const pts = shapesRaw.get(sh).sort((a, b) => a[0] - b[0]).map((x) => [x[1], x[2]]);
    w.text(encodePolyline(simplify(pts)));
  }

  w.varint(patterns.size);
  let nTrips = 0;
  for (const p of patterns.values()) {
    w.varint(routePos.get(p.route));
    w.varint(p.d);
    w.text(p.headsign);
    w.varint(shapeIdx.has(p.shape) ? shapeIdx.get(p.shape) + 1 : 0);
    w.ints(p.seq.map((s) => stopIdx.get(s)));
    const profiles = [...p.profiles.values()].sort((a, b) => a.idx - b.idx);
    w.varint(profiles.length);
    for (const pr of profiles) for (const x of pr.diffs) w.varint(x);
    const ts = p.trips.sort((a, b) => a[1] - b[1]);
    w.varint(ts.length);
    for (const [tid, start, prof, svc] of ts) { w.text(tid); w.varint(start); w.varint(prof); w.varint(svc); }
    nTrips += ts.length;
  }

  return {
    bytes: w.buffer(),
    stats: { code, version: String(version), stops: orderedStops.length, routes: routeList.length,
      shapes: orderedShapes.length, patterns: patterns.size, trips: nTrips, services: active.length },
  };
}

/** O pacote comprimido, como os ficheiros transit_*.bin da app. */
function gzipPack(bytes) {
  return zlib.gzipSync(bytes, { level: 9 });
}

/** Os planos ativos da TML: código do operador → endereço do GTFS normalizado. */
function activeTmlPlans(plansJson, now = new Date()) {
  const today = dateInt(now);
  const out = {};
  for (const p of (plansJson && plansJson.data) || []) {
    if (!TML_CODES.includes(p.agency_id) || !p.operation_gtfs_normalized_url) continue;
    const inRange = Number(p.active_from) <= today && today <= Number(p.active_until);
    if (!(p.is_active || inRange)) continue;
    // Se houver mais do que um, fica o que começou mais tarde
    if (!out[p.agency_id] || Number(p.active_from) > out[p.agency_id].from) {
      out[p.agency_id] = { url: p.operation_gtfs_normalized_url, from: Number(p.active_from) };
    }
  }
  return Object.fromEntries(Object.entries(out).map(([k, v]) => [k, v.url]));
}

module.exports = {
  TML_PLANS_URL, TML_CODES, FIXED_SOURCES,
  parseCsv, gtfsReader, simplify, encodePolyline, pathMetres, buildPack, buildPackFromRows, gzipPack, activeTmlPlans, dateInt,
};
