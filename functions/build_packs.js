/**
 * Gera os pacotes de horários que vão dentro da app (app/src/main/assets/transit_*.bin), com o mesmo conversor da
 * Cloud Function diária. A app usa-os enquanto não descarrega os do servidor (ou sem rede).
 *
 * Uso (na pasta functions):  node build_packs.js [pasta de saída]
 */
const fs = require("fs");
const path = require("path");
const fetch = require("node-fetch");
const { packJobs } = require("./packs");
const transit = require("./transit");

const HEADERS = { "User-Agent": "Mozilla/5.0 (Metrotime)" };
const out = process.argv[2] || path.join(__dirname, "..", "app", "src", "main", "assets");

const io = {
  fetchJson: async (url) => {
    const res = await fetch(url, { headers: HEADERS, timeout: 30000 });
    if (!res.ok) throw new Error(`${url} HTTP ${res.status}`);
    return res.json();
  },
  fetchBuffer: async (url) => {
    const res = await fetch(url, { headers: HEADERS, timeout: 120000 });
    if (!res.ok) throw new Error(`${url} HTTP ${res.status}`);
    return res.buffer();
  },
  fetchOverpass: async (query) => {
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
  },
};

(async () => {
  for (const [code, build] of await packJobs(io)) {
    try {
      const { bytes, stats } = await build();
      const file = path.join(out, `transit_${code.toLowerCase()}.bin`);
      const gz = transit.gzipPack(bytes);
      fs.writeFileSync(file, gz);
      console.log(`${code.padEnd(6)} ${stats.stops} paragens, ${stats.routes} linhas, ${stats.shapes} traçados, ` +
        `${stats.patterns} percursos, ${stats.trips} viagens → ${path.basename(file)} (${Math.round(gz.length / 1024)} KB)`);
    } catch (err) {
      console.error(`${code}: falhou (${err.message})`);
      process.exitCode = 1;
    }
  }
})();
