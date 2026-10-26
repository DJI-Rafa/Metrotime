const functions = require("firebase-functions");
const admin = require("firebase-admin");
const fetch = require("node-fetch");
const { lisbonToday, fromRuicosta, fromComboiosLive, buildSnapshot } = require("./cp");

admin.initializeApp();
const db = admin.database();

const RUICOSTA_URL = "https://comboios.ruicosta.pt/api/cache/trains";
const COMBOIOS_LIVE_URL = "https://comboios.live/api/vehicles";
const HEADERS = { "User-Agent": "Mozilla/5.0 (Metrotime)" };

/** Fonte principal: ruicosta (traz horas de origem/destino). Alternativa: comboios.live. */
async function fetchVehicles() {
  try {
    const res = await fetch(RUICOSTA_URL, { headers: HEADERS, timeout: 15000 });
    if (!res.ok) throw new Error(`ruicosta HTTP ${res.status}`);
    return { source: "ruicosta", ...fromRuicosta(await res.json()) };
  } catch (err) {
    console.warn("ruicosta falhou, a usar comboios.live:", err.message);
    const res = await fetch(COMBOIOS_LIVE_URL, { headers: HEADERS, timeout: 15000 });
    if (!res.ok) throw new Error(`comboios.live HTTP ${res.status}`);
    return { source: "comboios.live", ...fromComboiosLive(await res.json()) };
  }
}

/** Uma só função para buscar, filtrar e gravar (antes havia duas cópias que já divergiam). */
async function fetchAndStore() {
  const { source, vehicles, suppressed } = await fetchVehicles();
  // Posições da execução anterior: servem para calcular o rumo (seta de direção no mapa)
  const previous = (await db.ref("cp_positions/trains").get()).val() || {};
  const { vehiclesMap, positions, suppressedMap } = buildSnapshot({ vehicles, suppressed }, lisbonToday(), Date.now(), previous);
  const count = Object.keys(vehiclesMap).length;
  const suppressedCount = Object.keys(suppressedMap).length;
  const ts = admin.database.ServerValue.TIMESTAMP;

  // Escrita atómica dos três nós
  await db.ref().update({
    "cp_vehicles": { vehicles: vehiclesMap, updatedAt: ts, count, source },
    "cp_positions": { trains: positions, updatedAt: ts },
    "cp_suppressions": { trains: suppressedMap, updatedAt: ts, count: suppressedCount },
  });

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
