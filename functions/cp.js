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

/** Formato da API comboios.ruicosta.pt/api/cache/trains (objeto indexado por comboio). */
function fromRuicosta(data) {
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
    });
  }
  return { vehicles, suppressed };
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
  for (const v of vehicles) {
    const hasGps = Boolean(v.latitude && v.longitude);
    if (!isActive({ status: v.status, runDate: v.runDate, delay: v.delay, hasGps }, today)) continue;
    // O mesmo número em dois dias (perto da meia-noite): fica o mais recente
    const prev = vehiclesMap[v.trainNumber];
    if (prev && (prev.runDate || "") >= (v.runDate || "")) continue;
    vehiclesMap[v.trainNumber] = v;
    delete positions[v.trainNumber];
    if (hasGps) {
      const lat = parseFloat(v.latitude);
      const lon = parseFloat(v.longitude);
      if (Number.isFinite(lat) && Number.isFinite(lon) && lat !== 0 && lon !== 0) {
        const position = { lat, lon, ts: v.timestamp || now };
        // Rumo: só quando andou mais de 50 m desde a última posição; senão mantém o anterior
        const prev = previous && previous[v.trainNumber];
        if (prev && Number.isFinite(prev.lat) && Number.isFinite(prev.lon)) {
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
  return { vehiclesMap, positions, suppressedMap };
}

module.exports = { lisbonToday, addDays, isActive, fromRuicosta, fromComboiosLive, buildSnapshot, bearing, metres };
