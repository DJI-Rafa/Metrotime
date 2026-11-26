/**
 * Posições em direto das outras redes, lidas pelo servidor e gravadas no Realtime Database por região
 * (regional_live/LISBOA, PORTO, NORTE, CENTRO). A app ouve só as regiões que mostra, em vez de cada telemóvel
 * perguntar a 12 fontes: menos bateria e dados, e uma só leitura às fontes por volta para todos os utilizadores.
 *
 * Fontes (as mesmas da app, data/regional):
 *  - GO Hub da TML: MobiCascais, TCB, MTS, Fertagus, Transtejo/Soflusa (CC BY 4.0);
 *  - Eleven Systems: Covilhã, Viseu Dão Lafões, Guimarães, Castelo Branco, Vila Real, Guarda, Lamego, TTM, Sabugal;
 *  - STCP: broker FIWARE da Porto Digital.
 */

const TML_POSITIONS = "https://go.tmlmobilidade.pt/hub/api/v1/0XJC9/vehicles/positions";
const STCP_POSITIONS = "https://broker.fiware.urbanplatform.portodigital.pt/v2/entities" +
  "?q=vehicleType==bus&limit=1000&attrs=location,annotations,observationDateTime,fleetVehicleId&options=keyValues";
const elevenBase = (code) => `https://${code}.elevensystems.pt/api`;

/** agency_id do GO Hub → chave do operador na app. */
const TML_AGENCIES = { HF16N: "tml:HF16N", A3H3M: "tml:A3H3M", KB1F6: "tml:KB1F6", "7NTB1": "tml:7NTB1", LTP61: "tml:LTP61" };

/** Redes da Eleven e a região de cada uma (como em RegionalOperators.ELEVEN). */
const ELEVEN = [
  { code: "cov", area: "CENTRO" }, { code: "vdl", area: "CENTRO" }, { code: "gmr", area: "NORTE" },
  { code: "mcb", area: "CENTRO" }, { code: "uvr", area: "NORTE" }, { code: "etug", area: "CENTRO" },
  { code: "lam", area: "NORTE" }, { code: "ttm", area: "NORTE" }, { code: "tps", area: "CENTRO" },
];

const AREAS = ["LISBOA", "PORTO", "NORTE", "CENTRO"];

const round5 = (x) => Math.round(x * 1e5) / 1e5;

/** "0ba852" → "#0BA852"; null se não for uma cor. */
function color(hex) {
  const h = (hex || "").trim().replace(/^#/, "");
  return /^[0-9a-fA-F]{6}$/.test(h) ? "#" + h.toUpperCase() : null;
}

/**
 * Um veículo compacto para o Realtime Database (nomes curtos: cada byte conta para quem ouve):
 * o operador, id, la/lo, l (linha), c (cor), s (estado), t (viagem), st (paragem), d (sentido),
 * sd (dia de serviço), at (hora da posição, ms), ds (destino).
 */
function compact(v) {
  const out = {};
  for (const [k, val] of Object.entries(v)) if (val !== null && val !== undefined && val !== "") out[k] = val;
  return out;
}

/** Chave no Realtime Database ("tml:HF16N|170"): sem . $ # [ ] /. */
function keyOf(op, id) {
  return `${op}|${id}`.replace(/[.$#[\]/]/g, "_");
}

/** Veículos do GO Hub (só os operadores da camada); posições com mais de 3 min ficam de fora. */
function parseTml(json, nowMs) {
  const out = {};
  for (const v of (json && json.data) || []) {
    const op = TML_AGENCIES[v.agency_id];
    if (!op) continue;
    const lat = Number(v.latitude);
    const lon = Number(v.longitude);
    if (!Number.isFinite(lat) || !Number.isFinite(lon) || (lat === 0 && lon === 0)) continue;
    const at = Number(v.created_at) || Number(v.received_at) || 0;
    if (at > 0 && nowMs - at > 3 * 60000) continue;
    const id = String(v.vehicle_id || "").replace(/^\[[^\]]*\]/, "");
    if (!id) continue;
    out[keyOf(op, id)] = compact({
      o: op, id, la: round5(lat), lo: round5(lon), l: v.route_short_name, s: v.current_status,
      t: v.trip_id, st: v.stop_id, sd: Number(v.operational_date) || null, at,
    });
  }
  return out;
}

/** Autocarros da STCP (linha, sentido e viagem nas anotações); posições com mais de 5 min ficam de fora. */
function parseStcp(list, nowMs) {
  const out = {};
  for (const v of list || []) {
    const c = v.location && v.location.coordinates;
    if (!c) continue;
    const lon = Number(c[0]);
    const lat = Number(c[1]);
    if (!Number.isFinite(lat) || !Number.isFinite(lon) || (lat === 0 && lon === 0)) continue;
    const note = (p) => {
      const s = (v.annotations || []).find((a) => typeof a === "string" && a.startsWith(p));
      const val = s ? s.slice(p.length) : null;
      return val && val !== "na" ? val : null;
    };
    const at = Date.parse(v.observationDateTime || "") || 0;
    if (at > 0 && nowMs - at > 5 * 60000) continue;
    const id = v.fleetVehicleId || String(v.id || "").split(":").pop();
    if (!id) continue;
    const dir = note("stcp:sentido:");
    out[keyOf("stcp:STCP", id)] = compact({
      o: "stcp:STCP", id: String(id), la: round5(lat), lo: round5(lon), l: note("stcp:route:"),
      t: note("stcp:nr_viagem:"), d: dir !== null ? Number(dir) : null, at,
    });
  }
  return out;
}

/** Lista de uma rede Eleven: posição e cor (a linha vem de [lineFor], do que já se sabe de cada veículo). */
function parseEleven(list, code, nowMs, lineFor) {
  const out = {};
  const op = `eleven:${code}`;
  for (const v of list || []) {
    const p = v.position || {};
    const lat = Number(p.lat);
    const lon = Number(p.lon);
    if (!v.id || !Number.isFinite(lat) || !Number.isFinite(lon) || (lat === 0 && lon === 0)) continue;
    const c = color(v.color);
    const known = lineFor(String(v.id), c) || {};
    out[keyOf(op, v.id)] = compact({
      o: op, id: String(v.id), la: round5(lat), lo: round5(lon), c, l: known.line, ds: known.destination, at: nowMs,
    });
  }
  return out;
}

/** Detalhe de um veículo Eleven: a linha, a cor e o destino (a última paragem da viagem). */
function parseElevenDetail(json) {
  const route = (json && json.route) || {};
  const circ = (json && json.journey && json.journey.circulations) || [];
  const last = circ.length ? circ[circ.length - 1].stage : null;
  return {
    line: route.nameShort || null,
    color: color(route.color),
    destination: last && last.name ? last.name : null,
  };
}

/**
 * Uma volta de leitura. [state] guarda entre voltas (e entre execuções na mesma instância) o que se sabe das linhas
 * da Eleven e as últimas listas de cada fonte (se uma falhar, fica a anterior até 2 min).
 * [fetchJson](url) devolve o JSON ou lança. Devolve { LISBOA: {...}, PORTO: {...}, NORTE: {...}, CENTRO: {...} }.
 */
async function pollRound({ fetchJson, state, nowMs, withStcp, detailsPerNetwork = 5 }) {
  state.lines = state.lines || new Map();
  state.last = state.last || {};
  const keep = (name, fresh) => {
    if (fresh) { state.last[name] = { at: nowMs, data: fresh }; return fresh; }
    const old = state.last[name];
    return old && nowMs - old.at < 2 * 60000 ? old.data : {};
  };

  const jobs = [];
  jobs.push(fetchJson(TML_POSITIONS).then((j) => parseTml(j, nowMs)).catch((e) => { console.warn("TML:", e.message); return null; })
    .then((r) => ["TML", r]));
  if (withStcp) {
    jobs.push(fetchJson(STCP_POSITIONS).then((j) => parseStcp(j, nowMs)).catch((e) => { console.warn("STCP:", e.message); return null; })
      .then((r) => ["STCP", r]));
  }
  for (const net of ELEVEN) {
    jobs.push((async () => {
      try {
        const list = await fetchJson(`${elevenBase(net.code)}/locations?passengerInfo=true`);
        // Abre os veículos de que não se sabe a linha (ou que mudaram de cor = de linha), poucos de cada vez
        const stale = (list || []).filter((v) => {
          const k = `${net.code}|${v.id}`;
          const known = state.lines.get(k);
          return !known || known.listColor !== color(v.color) || nowMs - known.at > 8 * 60000;
        }).sort((a, b) => ((state.lines.get(`${net.code}|${a.id}`) || {}).at || 0) - ((state.lines.get(`${net.code}|${b.id}`) || {}).at || 0));
        for (const v of stale.slice(0, detailsPerNetwork)) {
          try {
            const d = parseElevenDetail(await fetchJson(`${elevenBase(net.code)}/locations/${v.id}`));
            state.lines.set(`${net.code}|${v.id}`, { ...d, listColor: color(v.color), at: nowMs });
          } catch (e) { /* fica para a próxima volta */ }
        }
        const alive = new Set((list || []).map((v) => `${net.code}|${v.id}`));
        for (const k of state.lines.keys()) if (k.startsWith(`${net.code}|`) && !alive.has(k)) state.lines.delete(k);
        const parsed = parseEleven(list, net.code, nowMs, (id, c) => {
          const known = state.lines.get(`${net.code}|${id}`);
          return known && known.listColor === c ? known : null;
        });
        return [`ELEVEN:${net.code}`, parsed];
      } catch (e) {
        console.warn(`Eleven ${net.code}:`, e.message);
        return [`ELEVEN:${net.code}`, null];
      }
    })());
  }

  const results = await Promise.all(jobs);
  const areas = Object.fromEntries(AREAS.map((a) => [a, {}]));
  for (const [name, fresh] of results) {
    const data = keep(name, fresh);
    const area = name === "TML" ? "LISBOA" : name === "STCP" ? "PORTO" : ELEVEN.find((n) => `ELEVEN:${n.code}` === name).area;
    Object.assign(areas[area], data);
  }
  if (!withStcp) Object.assign(areas.PORTO, keep("STCP", null));
  return areas;
}

module.exports = {
  TML_POSITIONS, STCP_POSITIONS, ELEVEN, AREAS,
  color, keyOf, parseTml, parseStcp, parseEleven, parseElevenDetail, pollRound,
};
