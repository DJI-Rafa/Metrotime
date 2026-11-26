const functions = require("firebase-functions");
const admin = require("firebase-admin");
const fetch = require("node-fetch");
const { lisbonToday, fromRuicosta, fromComboiosLive, overlayLive, buildSnapshot, sampleKey } = require("./cp");
const { countChanges } = require("./badges");

admin.initializeApp();
const db = admin.database();

const RUICOSTA_URL = "https://comboios.ruicosta.pt/api/cache/trains";
const COMBOIOS_LIVE_URL = "https://comboios.live/api/vehicles";
const HEADERS = { "User-Agent": "Mozilla/5.0 (Metrotime)" };

async function fetchJson(url, name) {
  const res = await fetch(url, { headers: HEADERS, timeout: 15000 });
  if (!res.ok) throw new Error(`${name} HTTP ${res.status}`);
  return res.json();
}

/**
 * As duas fontes ao mesmo tempo: a lista e as horas de origem/destino do ruicosta, com a posição (e a hora dela),
 * o rumo, o estado e o atraso do comboios.live, que chegam frescos. As posições do ruicosta chegavam com minutos
 * de atraso e sem hora: o mapa punha os comboios atrás e a app perdia o comboio em que se ia. Se uma das fontes
 * falhar, fica a outra sozinha.
 */
async function fetchVehicles() {
  const [rc, live] = await Promise.allSettled([
    fetchJson(RUICOSTA_URL, "ruicosta"),
    fetchJson(COMBOIOS_LIVE_URL, "comboios.live"),
  ]);
  const base = rc.status === "fulfilled" ? fromRuicosta(rc.value) : null;
  const fresh = live.status === "fulfilled" ? fromComboiosLive(live.value) : null;
  if (rc.status === "rejected") console.warn("ruicosta falhou:", rc.reason && rc.reason.message);
  if (live.status === "rejected") console.warn("comboios.live falhou:", live.reason && live.reason.message);
  if (base && fresh) return { source: "ruicosta+comboios.live", ...overlayLive(base, fresh) };
  if (base) return { source: "ruicosta", ...base };
  if (fresh) return { source: "comboios.live", ...fresh };
  throw new Error("Nenhuma fonte de comboios respondeu");
}

const SAMPLE_EVERY_MIN = 10;
const SAMPLE_KEEP_DAYS = 7;

/**
 * Guarda uma fotografia dos comboios em cp_samples/<hora UTC> a cada 10 minutos e apaga as com mais de 7 dias.
 * Servem para os testes da app repetirem dias reais (tools/fetch_cp_samples.py traz-as para o projeto).
 */
async function storeSample(vehiclesMap, routesMap, now = new Date()) {
  if (now.getUTCMinutes() % SAMPLE_EVERY_MIN !== 0) return;
  await db.ref(`cp_samples/${sampleKey(now)}`).set({ at: now.getTime(), vehicles: vehiclesMap, routes: routesMap });
  const cutoff = sampleKey(new Date(now.getTime() - SAMPLE_KEEP_DAYS * 86400000));
  const old = await db.ref("cp_samples").orderByKey().endAt(cutoff).limitToFirst(200).get();
  const removals = {};
  old.forEach((c) => { removals[c.key] = null; });
  if (Object.keys(removals).length) await db.ref("cp_samples").update(removals);
}

/** Uma só função para buscar, filtrar e gravar (antes havia duas cópias que já divergiam). */
async function fetchAndStore() {
  const { source, vehicles, suppressed } = await fetchVehicles();
  // Posições da execução anterior: servem para calcular o rumo (seta de direção no mapa)
  const previous = (await db.ref("cp_positions/trains").get()).val() || {};
  const { vehiclesMap, positions, suppressedMap, routesMap } = buildSnapshot({ vehicles, suppressed }, lisbonToday(), Date.now(), previous);
  const count = Object.keys(vehiclesMap).length;
  const suppressedCount = Object.keys(suppressedMap).length;
  const ts = admin.database.ServerValue.TIMESTAMP;

  // Escrita atómica dos três nós
  await db.ref().update({
    "cp_vehicles": { vehicles: vehiclesMap, updatedAt: ts, count, source },
    "cp_positions": { trains: positions, updatedAt: ts },
    "cp_suppressions": { trains: suppressedMap, updatedAt: ts, count: suppressedCount },
    // As paragens de cada comboio: a app calcula onde vai cada um com a mesma conta do painel
    "cp_routes": { trains: routesMap, updatedAt: ts },
  });

  // Amostras reais para os testes da app (a cada 10 min, guardadas 7 dias); um erro aqui não estraga o resto
  try {
    await storeSample(vehiclesMap, routesMap);
  } catch (err) {
    console.warn("Amostra não guardada:", err.message);
  }

  console.log(`[${source}] ${count} comboios ativos, ${suppressedCount} suprimidos`);
  return { source, count, suppressedCount };
}

/**
 * Corre a cada minuto e guarda os comboios em circulação no Realtime Database.
 * A app lê daqui em vez de cada telemóvel chamar as APIs externas.
 *
 * O antigo endpoint HTTP público `refreshCpVehicles` foi removido: qualquer pessoa o podia chamar
 * (custos) e tinha um bug que o fazia falhar. Para testar, usa `npm run shell` e chama
 * `pollCpVehicles()`.
 */
exports.pollCpVehicles = functions
  .runWith({ timeoutSeconds: 60, memory: "256MB" })
  .pubsub.schedule("every 1 minutes")
  .onRun(async () => {
    try {
      await fetchAndStore();
    } catch (error) {
      console.error("Erro a atualizar os comboios:", error.message);
    }
    return null;
  });

// ── Outras redes: horários (uma vez por dia) e posições em direto (de 10 em 10 s) ─────────────────

const crypto = require("crypto");
const transit = require("./transit");
const regional = require("./regional");
const { packJobs } = require("./packs");

async function fetchBuffer(url) {
  const res = await fetch(url, { headers: HEADERS, timeout: 120000 });
  if (!res.ok) throw new Error(`${url} HTTP ${res.status}`);
  return res.buffer();
}

/**
 * Gera os pacotes de horários (o mesmo formato dos assets transit_*.bin) a partir dos GTFS de cada operador e grava-os
 * em transit_packs/<código> (base64) e o índice (versão e tamanho) em transit_packs_index/<código>. A app descarrega só
 * os que mudaram. Um operador que falhe não estraga os outros; sem mudanças não se escreve nada.
 */
async function fetchOverpass(query) {
  // A Overpass recusa (HTTP 406) nomes de navegador: vai com o nome da app. Se o servidor principal estiver
  // sobrecarregado (502, 504), tenta o espelho.
  let last = null;
  for (const url of ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter"]) {
    try {
      const res = await fetch(url, {
        method: "POST", headers: { "User-Agent": "Metrotime/3.9 (app de transportes)" }, body: new URLSearchParams({ data: query }), timeout: 180000,
      });
      if (res.ok) return res.json();
      last = new Error(`Overpass HTTP ${res.status}`);
    } catch (err) {
      last = err;
    }
  }
  throw last;
}

async function buildAndStorePacks(now = new Date()) {
  const jobs = await packJobs({ fetchJson: (url) => fetchJson(url, "planos TML"), fetchBuffer, fetchOverpass }, now);
  const index = (await db.ref("transit_packs_index").get()).val() || {};
  const results = [];
  for (const [code, build] of jobs) {
    try {
      const { bytes, stats } = await build();
      const hash = crypto.createHash("sha1").update(bytes).digest("hex").slice(0, 16);
      if (index[code] && index[code].hash === hash) {
        results.push(`${code}: igual`);
        continue;
      }
      const gz = transit.gzipPack(bytes);
      const meta = { hash, version: stats.version, size: gz.length, trips: stats.trips, updatedAt: admin.database.ServerValue.TIMESTAMP };
      await db.ref().update({
        [`transit_packs/${code}`]: { ...meta, data: gz.toString("base64") },
        [`transit_packs_index/${code}`]: meta,
      });
      results.push(`${code}: ${Math.round(gz.length / 1024)} KB, ${stats.trips} viagens`);
    } catch (err) {
      console.error(`Pacote ${code} falhou:`, err.message);
      results.push(`${code}: erro`);
    }
  }
  console.log("Pacotes de horários:", results.join(" · "));
  return results;
}

/** Todos os dias de madrugada: os horários novos (a TML muda de plano a cada poucas semanas). */
exports.buildTransitPacks = functions
  .region("europe-west1")
  .runWith({ timeoutSeconds: 540, memory: "1GB" })
  .pubsub.schedule("every day 04:15")
  .timeZone("Europe/Lisbon")
  .onRun(async () => {
    await buildAndStorePacks();
    return null;
  });

/** O que a função sabe entre voltas (linhas da Eleven, últimas listas); fica enquanto a instância viver. */
const regionalState = {};
const ROUNDS_PER_RUN = 6;
const ROUND_MS = 10000;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function writeRegional(areas) {
  const ts = admin.database.ServerValue.TIMESTAMP;
  const updates = {};
  for (const [area, vehicles] of Object.entries(areas)) {
    updates[`regional_live/${area}`] = { updatedAt: ts, count: Object.keys(vehicles).length, vehicles };
  }
  await db.ref().update(updates);
}

/**
 * Corre a cada minuto e faz 6 voltas de 10 em 10 s: GO Hub e Eleven em todas, STCP de 30 em 30 s (só muda a esse
 * ritmo). Grava em regional_live/<região>; a app ouve só as regiões que mostra (só lhe chega o que mudou).
 */
exports.pollRegionalLive = functions
  .region("europe-west1")
  .runWith({ timeoutSeconds: 60, memory: "256MB" })
  .pubsub.schedule("every 1 minutes")
  .onRun(async () => {
    const start = Date.now();
    for (let round = 0; round < ROUNDS_PER_RUN; round++) {
      const wait = start + round * ROUND_MS - Date.now();
      if (wait > 0) await sleep(wait);
      // Não pisar a execução seguinte (o agendador volta a chamar daqui a 60 s)
      if (Date.now() - start > 52000) break;
      try {
        const areas = await regional.pollRound({
          fetchJson: (url) => fetchJson(url, "outras redes"),
          state: regionalState,
          nowMs: Date.now(),
          withStcp: round % 3 === 0,
        });
        await writeRegional(areas);
      } catch (error) {
        console.error("Erro a ler as outras redes:", error.message);
      }
    }
    return null;
  });

/**
 * Conta quantas pessoas têm cada distintivo: sempre que alguém guarda os seus em user_badges/<uid>, soma (ou tira)
 * em badge_counts/<distintivo> e em badge_counts/_users. A app mostra "Só 4 % das pessoas da app têm este distintivo".
 */
exports.onBadgesWrite = functions
  .region("europe-west1")
  .database.instance("metrotime-569ec-default-rtdb")
  .ref("/user_badges/{uid}")
  .onWrite(async (change) => {
    const before = change.before.exists() ? change.before.val() : null;
    const after = change.after.exists() ? change.after.val() : null;
    const deltas = countChanges(before, after);
    const updates = {};
    for (const [id, delta] of Object.entries(deltas)) updates[id] = admin.database.ServerValue.increment(delta);
    if (Object.keys(updates).length > 0) await db.ref("badge_counts").update(updates);
    return null;
  });
