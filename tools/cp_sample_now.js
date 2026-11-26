#!/usr/bin/env node
/**
 * Faz uma amostra dos comboios CP no mesmo formato que a Cloud Function guarda em cp_samples, sem o Firebase:
 * lê as duas fontes (ruicosta e comboios.live), passa-as pelo mesmo código da função (functions/cp.js) e grava
 * { at, vehicles, routes } num ficheiro. Serve para criar casos de teste da app a partir de um momento real.
 *
 *   node tools/cp_sample_now.js <saída.json>                       (vai buscar as duas fontes agora)
 *   node tools/cp_sample_now.js <saída.json> <ruicosta.json> <live.json> <at em ms>   (ficheiros já descarregados)
 */
const fs = require("fs");
const path = require("path");
const cp = require(path.join(__dirname, "..", "functions", "cp.js"));

const RUICOSTA_URL = "https://comboios.ruicosta.pt/api/cache/trains";
const COMBOIOS_LIVE_URL = "https://comboios.live/api/vehicles";

async function fetchJson(url) {
  const res = await fetch(url, { headers: { "User-Agent": "Mozilla/5.0 (Metrotime)" } });
  if (!res.ok) throw new Error(`${url} HTTP ${res.status}`);
  return res.json();
}

async function main() {
  const [out, rcFile, liveFile, atArg] = process.argv.slice(2);
  if (!out) {
    console.error("Uso: node tools/cp_sample_now.js <saída.json> [ruicosta.json live.json at_ms]");
    process.exit(2);
  }
  let rc;
  let live;
  let at;
  if (rcFile && liveFile) {
    rc = JSON.parse(fs.readFileSync(rcFile, "utf8"));
    live = JSON.parse(fs.readFileSync(liveFile, "utf8"));
    at = Number(atArg) || fs.statSync(liveFile).mtimeMs;
  } else {
    at = Date.now();
    [rc, live] = await Promise.all([fetchJson(RUICOSTA_URL), fetchJson(COMBOIOS_LIVE_URL)]);
  }
  const now = new Date(at);
  const base = cp.fromRuicosta(rc, cp.lisbonSeconds(now));
  const fresh = cp.fromComboiosLive(live);
  const merged = cp.overlayLive(base, fresh);
  const { vehiclesMap, routesMap } = cp.buildSnapshot(merged, cp.lisbonToday(now), at, {});
  fs.writeFileSync(out, JSON.stringify({ at, vehicles: vehiclesMap, routes: routesMap }, null, 1));
  console.log(`${Object.keys(vehiclesMap).length} comboios, ${Object.keys(routesMap).length} percursos → ${out}`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
