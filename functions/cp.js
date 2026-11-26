/**
 * Lógica pura (sem Firebase) para normalizar e filtrar os comboios em circulação.
 * Separada do index.js para poder ser testada localmente com `node`.
 *
 * Atrasos dos veículos: SEGUNDOS (tanto no ruicosta como no comboios.live).
 */

const ENDED = new Set(["COMPLETED", "CANCELLED", "UNKNOWN"]);
const RUNNING = new Set(["IN_TRANSIT", "AT_STATION", "NEAR_NEXT", "AT_ORIGIN"]);

/** Data de hoje em Lisboa, no formato YYYY-MM-DD. */
function lisbonToday(now = new Date()) {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "Europe/Lisbon", year: "numeric", month: "2-digit", day: "2-digit",
  }).format(now);
}

function addDays(isoDate, n) {
  const d = new Date(isoDate + "T12:00:00Z");
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}

function str(v) {
  return v === undefined || v === null || v === "" ? null : String(v);
}

function entity(e) {
  return e ? { code: str(e.code), designation: str(e.designation) } : null;
}

/**
 * Filtro de comboios "fantasma" — a mesma regra que a app usa (CpVehicleFilter.kt).
 * O runDate é comparado com a data de LISBOA (antes era "24 h desde a meia-noite UTC",
 * o que cortava comboios que passavam a meia-noite).
 */
function isActive({ status, runDate, delay, hasGps }, today) {
  if (ENDED.has(status)) return false;
  if (runDate) {
    const d = String(runDate).slice(0, 10);
    if (d > today || d < addDays(today, -1)) return false;
    if (d < today && !RUNNING.has(status)) return false; // de ontem, só se ainda estiver a circular
  }
  const delaySec = Number.isFinite(delay) ? delay : 0;
  if (delaySec > 6 * 3600) return false;
  if (status === "NOT_STARTED" && (delaySec > 3600 || !hasGps)) return false;
  if (status === "NULL" && !hasGps) return false;
  return true;
}

/** "HH:mm:ss" ou "HH:mm" → segundos desde a meia-noite (null se não for uma hora). */
function secondsOf(t) {
  if (t === undefined || t === null || t === "") return null;
  const p = String(t).split(":").map(Number);
  if (p.length < 2 || p.some((x) => !Number.isFinite(x))) return null;
  return p[0] * 3600 + p[1] * 60 + (p[2] || 0);
}

/** Segundos desde a meia-noite, agora, em Lisboa. */
function lisbonSeconds(now = new Date()) {
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: "Europe/Lisbon", hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false,
  }).formatToParts(now);
  const get = (type) => Number(parts.find((x) => x.type === type).value);
  return (get("hour") % 24) * 3600 + get("minute") * 60 + get("second");
}

/** Há quanto tempo foi [sec] (segundos do dia), visto de [nowSec]; perto da meia-noite conta o dia anterior. */
function ageSeconds(sec, nowSec) {
  let d = nowSec - sec;
  if (d > 43200) d -= 86400;
  if (d <= -43200) d += 86400;
  return d;
}

/**
 * A última passagem registada pela CP: o ruicosta traz em data.stops a hora real a que o comboio passou em cada
 * ponto do percurso (também nos apeadeiros onde não para), com segundos. Fica a mais recente que já aconteceu
 * (as que estão no futuro são previsões). Dá a estação, a hora "HH:mm:ss" e se já saiu de lá ([left]): parado
 * numa estação, a CP regista só a chegada; a partida aparece quando sai (de passagem, as duas iguais).
 */
function lastPassage(stops, nowSec = lisbonSeconds()) {
  let best = null;
  for (const code of Object.keys(stops || {})) {
    const s = stops[code] || {};
    for (const [t, left] of [[s.arrival, false], [s.departure, true]]) {
      const sec = secondsOf(t);
      if (sec === null) continue;
      const age = ageSeconds(sec, nowSec);
      // Horas no futuro são previsões (5 s de folga para relógios diferentes)
      if (age < -5) continue;
      // Na mesma hora, a partida ganha (de passagem: chegou e saiu)
      if (!best || age < best.age || (age === best.age && left && !best.left)) best = { code, time: String(t), age, left };
    }
  }
  return best ? { code: best.code, time: best.time, left: best.left } : null;
}

/**
 * As paragens comerciais do comboio (fixed.trainStops), compactas, para a app calcular onde vai cada comboio:
 * "AAAA-MM-DD|código,chegada,partida,suprimida;…" com as horas do horário "HH:mm" (vazias quando não há).
 */
function routeOf(fixed, runDate) {
  const stops = (fixed && fixed.trainStops) || [];
  if (stops.length < 2 || !runDate) return null;
  const parts = [];
  for (const s of stops) {
    const code = s && s.station && s.station.code;
    if (!code) return null;
    parts.push([code, s.arrival || "", s.departure || "", s.supression ? 1 : 0].join(","));
  }
  return `${String(runDate).slice(0, 10)}|${parts.join(";")}`;
}

/** Chave de uma amostra em cp_samples: a hora UTC ao minuto, ordenável ("2026-11-23T13-00Z"). */
function sampleKey(date) {
  return date.toISOString().slice(0, 16).replace(":", "-") + "Z";
}

/** Formato da API comboios.ruicosta.pt/api/cache/trains (objeto indexado por comboio). */
function fromRuicosta(data, nowSec = lisbonSeconds()) {
  const vehicles = [];
  const suppressed = [];
  for (const key of Object.keys(data || {})) {
    const train = data[key] || {};
    const trainData = train.data && train.data.status ? train.data.status : null;
    const dbData = train.db || train.fixed || null;
    const topStatus = train.status;
    const nestedStatus = trainData ? trainData.status : null;
    const tNum = parseInt(trainData ? trainData.trainNumber : train.train_id, 10);
    if (isNaN(tNum)) continue;

    const base = {
      trainNumber: tNum,
      service: entity(dbData && dbData.trainService),
      origin: entity(dbData && dbData.trainOrigin),
      destination: entity(dbData && dbData.trainDestination),
    };

    if (topStatus === "CANCELLED" || nestedStatus === "CANCELLED") {
      suppressed.push(base);
      continue;
    }
    if (!trainData) continue;
    // Estado de topo ERROR = cache desatualizada (API da CP inacessível)
    if (topStatus === "ERROR" || topStatus === "COMPLETED") continue;

    const status = nestedStatus || (train.fixed ? train.fixed.status : null) || topStatus;
    const stops = (train.fixed && train.fixed.trainStops) || [];
    const first = stops.length ? stops[0] : null;
    const last = stops.length ? stops[stops.length - 1] : null;
    const pass = lastPassage(train.data && train.data.stops, nowSec);
    const route = routeOf(train.fixed, trainData.runDate);

    vehicles.push({
      ...base,
      runDate: str(trainData.runDate),
      delay: parseInt(trainData.delay, 10) || 0,
      lastStation: str(trainData.lastStation),
      latitude: str(trainData.latitude),
      longitude: str(trainData.longitude),
      status: str(status),
      hasDisruptions: Boolean(trainData.hasDisruptions),
      timestamp: Number(trainData.timestamp) || null,
      originTime: first ? str(first.departure || first.arrival) : null,
      originETA: first ? str(first.ETD || first.ETA) : null,
      destinationTime: last ? str(last.arrival || last.departure) : null,
      destinationETA: last ? str(last.ETA || last.ETD) : null,
      passCode: pass ? pass.code : null,
      passTime: pass ? pass.time : null,
      passLeft: pass ? pass.left : null,
      // Só para cp_routes (o buildSnapshot tira-o do veículo)
      ...(route ? { route } : {}),
    });
  }
  return { vehicles, suppressed };
}

/** Formato da API comboios.live/api/vehicles ({ vehicles: [...] }). */
function fromComboiosLive(json) {
  const vehicles = [];
  const suppressed = [];
  for (const v of (json && json.vehicles) || []) {
    const tNum = parseInt(v.trainNumber, 10);
    if (isNaN(tNum)) continue;
    const base = {
      trainNumber: tNum,
      service: entity(v.service),
      origin: entity(v.origin),
      destination: entity(v.destination),
    };
    if (v.status === "CANCELLED") {
      suppressed.push(base);
      continue;
    }
    vehicles.push({
      ...base,
      runDate: str(v.runDate),
      delay: parseInt(v.delay, 10) || 0,
      lastStation: str(v.lastStation),
      latitude: str(v.latitude),
      longitude: str(v.longitude),
      status: str(v.status),
      hasDisruptions: Boolean(v.hasDisruptions),
      timestamp: Number(v.timestamp) || null,
      originTime: null,
      originETA: null,
      destinationTime: null,
      destinationETA: null,
      // Só quando vem (o Firebase não aceita undefined)
      ...(Number.isFinite(v.bearing) ? { bearing: Math.round(v.bearing) } : {}),
    });
  }
  return { vehicles, suppressed };
}

/**
 * Junta as duas fontes: os comboios do ruicosta ([base], com as horas de origem e destino) com o que vem fresco
 * do comboios.live ([live]): posição e a hora dela, rumo, estado, última estação e atraso — tudo da mesma leitura,
 * para não se misturar uma posição nova com uma última estação velha. Só se junta o mesmo comboio no mesmo dia;
 * os que só o comboios.live tem também entram (o buildSnapshot escolhe se houver dois com o mesmo número).
 */
function overlayLive(base, live) {
  const day = (d) => (d ? String(d).slice(0, 10) : "");
  const byKey = new Map();
  for (const l of live.vehicles) byKey.set(`${l.trainNumber}|${day(l.runDate)}`, l);
  const used = new Set();
  const vehicles = base.vehicles.map((v) => {
    const key = `${v.trainNumber}|${day(v.runDate)}`;
    const l = byKey.get(key);
    if (!l) return v;
    used.add(key);
    return {
      ...v,
      delay: l.delay,
      lastStation: l.lastStation || v.lastStation,
      latitude: l.latitude || v.latitude,
      longitude: l.longitude || v.longitude,
      status: l.status || v.status,
      hasDisruptions: l.hasDisruptions || v.hasDisruptions,
      timestamp: l.timestamp || v.timestamp,
      ...(l.bearing !== undefined ? { bearing: l.bearing } : {}),
    };
  });
  for (const [key, l] of byKey) if (!used.has(key)) vehicles.push(l);
  const cancelled = new Set(base.suppressed.map((s) => s.trainNumber));
  const suppressed = base.suppressed.concat(live.suppressed.filter((s) => !cancelled.has(s.trainNumber)));
  return { vehicles, suppressed };
}

/**
 * O mesmo número em dois dias (perto da meia-noite): fica o que está a circular e, entre iguais, o mais recente.
 * Antes ficava sempre o mais recente, e o de hoje ainda por partir tirava do mapa o de ontem que ia a meio.
 */
function preferVehicle(a, b) {
  const ra = RUNNING.has(a.status);
  const rb = RUNNING.has(b.status);
  if (ra !== rb) return ra;
  return (a.runDate || "") > (b.runDate || "");
}

/** Metros entre dois pontos (aproximação plana, suficiente a estas distâncias). */
function metres(lat1, lon1, lat2, lon2) {
  const k = Math.cos(((lat1 + lat2) / 2) * Math.PI / 180);
  const dy = (lat2 - lat1) * 111320;
  const dx = (lon2 - lon1) * 111320 * k;
  return Math.sqrt(dx * dx + dy * dy);
}

/** Rumo em graus (0 = norte, 90 = este) de um ponto para outro. */
function bearing(lat1, lon1, lat2, lon2) {
  const r = Math.PI / 180;
  const dLon = (lon2 - lon1) * r;
  const y = Math.sin(dLon) * Math.cos(lat2 * r);
  const x = Math.cos(lat1 * r) * Math.sin(lat2 * r) - Math.sin(lat1 * r) * Math.cos(lat2 * r) * Math.cos(dLon);
  return Math.round(((Math.atan2(y, x) / r) + 360) % 360);
}

/**
 * Aplica o filtro e constrói os três nós escritos no Firebase:
 *  - vehicles:   dados completos (lista "Comboios em circulação")
 *  - positions:  só lat/lon/ts/rumo (poucos KB) para a deteção "a bordo"
 *  - suppressed: comboios suprimidos
 * [previous] = cp_positions/trains da execução anterior, para calcular para onde vai cada comboio.
 */
function buildSnapshot({ vehicles, suppressed }, today, now = Date.now(), previous = {}) {
  const vehiclesMap = {};
  const positions = {};
  const routesMap = {};
  for (const withRoute of vehicles) {
    // O percurso não vai no veículo (cp_vehicles é lido muitas vezes): vai para cp_routes
    const { route, ...v } = withRoute;
    const hasGps = Boolean(v.latitude && v.longitude);
    if (!isActive({ status: v.status, runDate: v.runDate, delay: v.delay, hasGps }, today)) continue;
    // O mesmo número em dois dias (perto da meia-noite): fica o que está a circular
    const prev = vehiclesMap[v.trainNumber];
    if (prev && !preferVehicle(v, prev)) continue;
    vehiclesMap[v.trainNumber] = v;
    delete positions[v.trainNumber];
    if (route) routesMap[v.trainNumber] = route;
    else delete routesMap[v.trainNumber];
    if (hasGps) {
      const lat = parseFloat(v.latitude);
      const lon = parseFloat(v.longitude);
      if (Number.isFinite(lat) && Number.isFinite(lon) && lat !== 0 && lon !== 0) {
        // ts: a hora da posição (comboios.live); sem ela, a desta leitura
        const position = { lat, lon, ts: v.timestamp || now };
        // Rumo: o da fonte; senão, só quando andou mais de 50 m desde a última posição (senão mantém o anterior)
        const prev = previous && previous[v.trainNumber];
        if (Number.isFinite(v.bearing)) {
          position.bearing = v.bearing;
        } else if (prev && Number.isFinite(prev.lat) && Number.isFinite(prev.lon)) {
          if (metres(prev.lat, prev.lon, lat, lon) > 50) position.bearing = bearing(prev.lat, prev.lon, lat, lon);
          else if (Number.isFinite(prev.bearing)) position.bearing = prev.bearing;
        }
        positions[v.trainNumber] = position;
        if (position.bearing !== undefined) vehiclesMap[v.trainNumber] = { ...v, bearing: position.bearing };
      }
    }
  }
  const suppressedMap = {};
  for (const s of suppressed) suppressedMap[s.trainNumber] = s;
  return { vehiclesMap, positions, suppressedMap, routesMap };
}

module.exports = {
  lisbonToday, addDays, isActive, fromRuicosta, fromComboiosLive, overlayLive, preferVehicle, buildSnapshot, bearing, metres,
  secondsOf, lisbonSeconds, ageSeconds, lastPassage, routeOf, sampleKey,
};
