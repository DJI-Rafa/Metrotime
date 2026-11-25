/**
 * Metro do Porto a partir do OpenStreetMap (dados © contribuidores do OpenStreetMap, ODbL): as linhas (A, B, Bx, C,
 * D, E, F) com o traçado real dos carris, as estações e a ordem delas em cada sentido. O GTFS aberto que havia (2024)
 * não tinha o traçado da linha F e o da B vinha com os pontos fora de ordem; o portal de dados do Porto mudou de sítio
 * e o ficheiro oficial deixou de estar acessível.
 *
 * Gera as mesmas linhas de um GTFS (routes, stops, trips, stop_times, shapes) para o conversor de transit.js. Não há
 * horários: as horas de passagem são de faz-de-conta (2 min entre estações) só para o formato.
 */

const OVERPASS_URL = "https://overpass-api.de/api/interpreter";
const MDP_QUERY = '[out:json][timeout:120];' +
  'relation["route"="light_rail"]["network"~"Metro do Porto",i](40.95,-8.8,41.45,-8.4)->.r;.r out geom;node(r.r);out;';

function metres(a, b) {
  const k = Math.cos(((a[0] + b[0]) / 2) * Math.PI / 180);
  return Math.hypot((b[0] - a[0]) * 111320, (b[1] - a[1]) * 111320 * k);
}

/** Junta as vias de uma relação pela ordem dela, virando as que vêm ao contrário (como em build_metro_tracks.py). */
function stitch(ways) {
  let line = [];
  let first = true;
  for (const pts of ways) {
    if (!pts.length) continue;
    if (!line.length) { line = pts.slice(); continue; }
    if (first) {
      // A primeira via pode vir ao contrário: vira-a se a segunda encaixa melhor no início dela
      const atEnd = Math.min(metres(line[line.length - 1], pts[0]), metres(line[line.length - 1], pts[pts.length - 1]));
      const atStart = Math.min(metres(line[0], pts[0]), metres(line[0], pts[pts.length - 1]));
      if (atStart < atEnd) line.reverse();
      first = false;
    }
    const end = line[line.length - 1];
    const seg = metres(end, pts[pts.length - 1]) < metres(end, pts[0]) ? pts.slice().reverse() : pts;
    line.push(...(metres(end, seg[0]) < 1 ? seg.slice(1) : seg));
  }
  return line;
}

const pad = (n) => String(n).padStart(2, "0");
const clock = (s) => `${pad(Math.floor(s / 3600))}:${pad(Math.floor(s / 60) % 60)}:${pad(s % 60)}`;

/** Resposta da Overpass (MDP_QUERY) → leitor de "ficheiros GTFS" para buildPackFromRows. */
function metroPortoRows(osm) {
  const elements = (osm && osm.elements) || [];
  const nodes = new Map(elements.filter((e) => e.type === "node").map((e) => [e.id, e]));
  const rels = elements
    .filter((e) => e.type === "relation" && e.tags && e.tags.ref)
    .sort((a, b) => (a.tags.ref.localeCompare(b.tags.ref)) || (a.id - b.id));

  // Uma estação por nome (os dois sentidos têm paragens diferentes, a poucos metros): no meio delas
  const byName = new Map();
  for (const r of rels) {
    for (const m of r.members || []) {
      if (m.type !== "node" || !m.role.startsWith("stop")) continue;
      const n = nodes.get(m.ref);
      const name = n && n.tags && n.tags.name;
      if (!name || !Number.isFinite(n.lat)) continue;
      if (!byName.has(name)) byName.set(name, { pts: [] });
      byName.get(name).pts.push([n.lat, n.lon]);
    }
  }
  const stops = [];
  const stopId = new Map();
  for (const [name, { pts }] of byName) {
    const id = `MDP${stops.length + 1}`;
    stopId.set(name, id);
    const lat = pts.reduce((s, p) => s + p[0], 0) / pts.length;
    const lon = pts.reduce((s, p) => s + p[1], 0) / pts.length;
    stops.push({ stop_id: id, stop_name: name, stop_lat: String(lat), stop_lon: String(lon) });
  }

  const routes = new Map();
  const trips = [];
  const stopTimes = [];
  const shapes = [];
  const seenPerRef = new Map();
  for (const r of rels) {
    const ref = r.tags.ref;
    if (!routes.has(ref)) {
      const longName = (r.tags.name || "").replace(/^Linha\s+\S+:\s*/, "").replace(/\s*⇒\s*/, " - ");
      routes.set(ref, {
        route_id: ref, route_short_name: ref, route_long_name: longName, route_type: "0",
        route_color: (r.tags.colour || "").replace("#", ""),
      });
    }
    const dir = seenPerRef.get(ref) || 0;
    seenPerRef.set(ref, dir + 1);
    const seq = [];
    for (const m of r.members || []) {
      if (m.type !== "node" || !m.role.startsWith("stop")) continue;
      const name = nodes.get(m.ref) && nodes.get(m.ref).tags && nodes.get(m.ref).tags.name;
      const id = name && stopId.get(name);
      if (id && seq[seq.length - 1] !== id) seq.push(id);
    }
    if (seq.length < 2) continue;
    const tripId = `osm-${r.id}`;
    const lastName = stops.find((s) => s.stop_id === seq[seq.length - 1]).stop_name;
    trips.push({
      route_id: ref, service_id: "OSM", trip_id: tripId, trip_headsign: r.tags.to || lastName,
      direction_id: String(dir % 2), shape_id: tripId,
    });
    seq.forEach((id, i) => stopTimes.push({
      trip_id: tripId, arrival_time: clock(i * 120), departure_time: clock(i * 120), stop_id: id, stop_sequence: String(i + 1),
    }));
    const ways = (r.members || [])
      .filter((m) => m.type === "way" && m.role === "" && m.geometry)
      .map((m) => m.geometry.map((g) => [g.lat, g.lon]));
    stitch(ways).forEach(([lat, lon], i) => shapes.push({
      shape_id: tripId, shape_pt_lat: String(lat), shape_pt_lon: String(lon), shape_pt_sequence: String(i + 1),
    }));
  }

  const files = {
    "routes.txt": [...routes.values()],
    "stops.txt": stops,
    "trips.txt": trips,
    "stop_times.txt": stopTimes,
    "shapes.txt": shapes,
  };
  return (name) => files[name] || [];
}

/**
 * O GTFS oficial do Metro do Porto (horários certos, traçados em retas entre estações) com o traçado real do
 * OpenStreetMap. Cada viagem do GTFS fica com o traçado da relação do OSM da mesma linha cujas pontas ficam mais perto
 * das estações onde a viagem começa e acaba. Não se usa o traçado do GTFS para isto: o da linha A é o mesmo da F
 * (começa em Fânzeres, por onde a A não passa). Sem relação a menos de 3 km, fica o traçado do GTFS.
 */
function withOsmShapes(gtfsRows, osm) {
  const osmRows = metroPortoRows(osm);
  const osmLines = new Map();
  for (const p of osmRows("shapes.txt")) {
    if (!osmLines.has(p.shape_id)) osmLines.set(p.shape_id, []);
    osmLines.get(p.shape_id).push([Number(p.shape_pt_lat), Number(p.shape_pt_lon)]);
  }
  const osmTrips = osmRows("trips.txt").filter((t) => (osmLines.get(t.shape_id) || []).length >= 2);
  const routeShort = new Map(gtfsRows("routes.txt").map((r) => [r.route_id, (r.route_short_name || r.route_id).trim()]));
  const stopAt = new Map(gtfsRows("stops.txt").map((s) => [s.stop_id, [Number(s.stop_lat), Number(s.stop_lon)]]));
  // Primeira e última estação de cada viagem
  const ends = new Map();
  for (const st of gtfsRows("stop_times.txt")) {
    const seq = Number(st.stop_sequence);
    const e = ends.get(st.trip_id) || { min: Infinity, max: -Infinity, first: null, last: null };
    if (seq < e.min) { e.min = seq; e.first = st.stop_id; }
    if (seq > e.max) { e.max = seq; e.last = st.stop_id; }
    ends.set(st.trip_id, e);
  }
  const chosen = new Map(); // "linha|primeira|última" → shape_id do OSM (ou null)
  const used = new Set();
  const trips = gtfsRows("trips.txt").map((t) => {
    const e = ends.get(t.trip_id);
    const short = routeShort.get(t.route_id) || "";
    if (!e || !stopAt.has(e.first) || !stopAt.has(e.last)) return t;
    const key = `${short}|${e.first}|${e.last}`;
    if (!chosen.has(key)) {
      let best = null;
      for (const o of osmTrips) {
        // Bx anda nos carris da B: serve a relação de qualquer uma das duas
        const ref = o.route_id;
        if (!(short === ref || (short.startsWith("B") && ref.startsWith("B")))) continue;
        const line = osmLines.get(o.shape_id);
        const d = metres(stopAt.get(e.first), line[0]) + metres(stopAt.get(e.last), line[line.length - 1]);
        if (!best || d < best.d) best = { d, id: o.shape_id };
      }
      chosen.set(key, best && best.d < 3000 ? best.id : null);
    }
    const id = chosen.get(key);
    if (!id) return t;
    used.add(id);
    return { ...t, shape_id: id };
  });
  const keptGtfs = new Set(trips.map((t) => t.shape_id).filter((id) => !used.has(id)));
  const shapes = gtfsRows("shapes.txt").filter((p) => keptGtfs.has(p.shape_id));
  for (const id of used) {
    osmLines.get(id).forEach(([lat, lon], i) => shapes.push({
      shape_id: id, shape_pt_lat: String(lat), shape_pt_lon: String(lon), shape_pt_sequence: String(i + 1),
    }));
  }
  return (name) => (name === "shapes.txt" ? shapes : name === "trips.txt" ? trips : gtfsRows(name));
}

module.exports = { OVERPASS_URL, MDP_QUERY, stitch, metroPortoRows, withOsmShapes };
