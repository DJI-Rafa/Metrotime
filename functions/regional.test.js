const test = require("node:test");
const assert = require("node:assert");
const zlib = require("zlib");
const AdmZip = require("adm-zip");
const regional = require("./regional");
const transit = require("./transit");

const NOW = 1795514960000;

// ── Posições ────────────────────────────────────────────────────────────────

test("GO Hub: só os operadores da camada, posições frescas e id sem o prefixo", () => {
  const out = regional.parseTml({ data: [
    { agency_id: "HF16N", latitude: 38.72555, longitude: -9.437629, created_at: NOW - 30000, vehicle_id: "[HF16N]170",
      route_short_name: "45", current_status: "IN_TRANSIT_TO", trip_id: "[1NN3V][HF16N]M45-1", stop_id: "20977", operational_date: 20261124 },
    { agency_id: "IA9T6", latitude: 38.7, longitude: -9.1, created_at: NOW, vehicle_id: "[IA9T6]1" },
    { agency_id: "7NTB1", latitude: 38.74, longitude: -9.15, created_at: NOW - 10 * 60000, vehicle_id: "[7NTB1]14029" },
    { agency_id: "KB1F6", latitude: 0, longitude: 0, created_at: NOW, vehicle_id: "[KB1F6]C001" },
  ] }, NOW);
  assert.deepStrictEqual(Object.keys(out), ["tml:HF16N|170"]);
  const v = out["tml:HF16N|170"];
  assert.strictEqual(v.l, "45");
  assert.strictEqual(v.t, "[1NN3V][HF16N]M45-1");
  assert.strictEqual(v.sd, 20261124);
  assert.strictEqual(v.la, 38.72555);
});

test("STCP: linha, sentido e viagem das anotações", () => {
  const out = regional.parseStcp([
    { id: "urn:ngsi-ld:Vehicle:porto:stcp:bus:1838", fleetVehicleId: "1838",
      location: { type: "Point", coordinates: [-8.581272125, 41.152194977] },
      annotations: ["stcp:route:107", "stcp:nr_turno:na", "stcp:nr_viagem:107_0_1|340|D1|T1|N15", "stcp:sentido:0"],
      observationDateTime: new Date(NOW - 20000).toISOString() },
    { id: "x", fleetVehicleId: "9", location: { coordinates: [-8.6, 41.1] }, annotations: [], observationDateTime: new Date(NOW - 20 * 60000).toISOString() },
  ], NOW);
  assert.deepStrictEqual(Object.keys(out), ["stcp:STCP|1838"]);
  assert.strictEqual(out["stcp:STCP|1838"].l, "107");
  assert.strictEqual(out["stcp:STCP|1838"].d, 0);
  assert.strictEqual(out["stcp:STCP|1838"].t, "107_0_1|340|D1|T1|N15");
});

test("Eleven: detalhe dá a linha e o destino; a lista usa-os enquanto a cor for a mesma", () => {
  const d = regional.parseElevenDetail({
    route: { nameShort: "SERRA", color: "DB291C" },
    journey: { circulations: [{ stage: { name: "TORRE" } }, { stage: { name: "PRAÇA DO MUNICÍPIO" } }] },
  });
  assert.deepStrictEqual(d, { line: "SERRA", color: "#DB291C", destination: "PRAÇA DO MUNICÍPIO" });
  const out = regional.parseEleven(
    [{ id: "11010288", position: { lat: 40.29, lon: -7.53 }, color: "DB291C" }, { id: "2", position: { lat: 0, lon: 0 } }],
    "cov", NOW, (id, c) => (id === "11010288" && c === "#DB291C" ? { line: "SERRA", destination: "Praça" } : null)
  );
  assert.deepStrictEqual(Object.keys(out), ["eleven:cov|11010288"]);
  assert.strictEqual(out["eleven:cov|11010288"].l, "SERRA");
  assert.strictEqual(out["eleven:cov|11010288"].ds, "Praça");
});

test("Uma volta: junta tudo por região, abre poucos detalhes da Eleven e guarda a lista anterior se uma fonte falhar", async () => {
  const calls = [];
  const fetchJson = async (url) => {
    calls.push(url);
    if (url.includes("tmlmobilidade")) return { data: [{ agency_id: "LTP61", latitude: 38.7, longitude: -9.14, created_at: NOW, vehicle_id: "[LTP61]77", route_short_name: "SX" }] };
    if (url.includes("fiware")) return [{ fleetVehicleId: "1", location: { coordinates: [-8.6, 41.15] }, annotations: ["stcp:route:200"], observationDateTime: new Date(NOW).toISOString() }];
    if (url.startsWith("https://cov.") && url.endsWith("/locations?passengerInfo=true")) {
      return Array.from({ length: 8 }, (_, i) => ({ id: String(i), position: { lat: 40.28, lon: -7.5 }, color: "DB291C" }));
    }
    if (url.startsWith("https://cov.") && url.includes("/locations/")) return { route: { nameShort: "10", color: "DB291C" }, journey: { circulations: [] } };
    throw new Error("sem resposta");
  };
  const state = {};
  const areas = await regional.pollRound({ fetchJson, state, nowMs: NOW, withStcp: true, detailsPerNetwork: 5 });
  assert.deepStrictEqual(Object.keys(areas.LISBOA), ["tml:LTP61|77"]);
  assert.deepStrictEqual(Object.keys(areas.PORTO), ["stcp:STCP|1"]);
  const cov = Object.values(areas.CENTRO).filter((v) => v.o === "eleven:cov");
  assert.strictEqual(cov.length, 8);
  // Só 5 detalhes por volta: 5 com linha, 3 ainda sem
  assert.strictEqual(cov.filter((v) => v.l === "10").length, 5);
  assert.strictEqual(calls.filter((u) => u.includes("cov.") && u.includes("/locations/")).length, 5);

  // Na volta seguinte o GO Hub falha: fica a lista anterior; a STCP (sem leitura nesta volta) também
  const failing = async (url) => (url.includes("tmlmobilidade") ? Promise.reject(new Error("down")) : fetchJson(url));
  const next = await regional.pollRound({ fetchJson: failing, state, nowMs: NOW + 10000, withStcp: false });
  assert.deepStrictEqual(Object.keys(next.LISBOA), ["tml:LTP61|77"]);
  assert.deepStrictEqual(Object.keys(next.PORTO), ["stcp:STCP|1"]);
  assert.strictEqual(Object.values(next.CENTRO).filter((v) => v.l === "10").length, 8);
});

// ── Horários ────────────────────────────────────────────────────────────────

function sampleGtfs() {
  const zip = new AdmZip();
  const add = (name, text) => zip.addFile(name, Buffer.from(text, "utf8"));
  add("routes.txt", "route_id,route_short_name,route_long_name,route_type,route_color\nR1,SX,\"Seixal - Cais do Sodré\",4,0077c8\n");
  add("stops.txt", "stop_id,stop_name,stop_lat,stop_lon\nA,Seixal,38.64,-9.10\nB,Cais do Sodré,38.705,-9.145\nZ,Sem viagens,38.6,-9.0\n");
  add("calendar.txt", "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nU,1,1,1,1,1,0,0,20260101,20271231\nOLD,1,1,1,1,1,0,0,20200101,20201231\n");
  add("calendar_dates.txt", "service_id,date,exception_type\nU,20261225,2\n");
  add("trips.txt", "route_id,service_id,trip_id,trip_headsign,direction_id,shape_id\nR1,U,T1,Cais do Sodré,0,S1\nR1,U,T2,Cais do Sodré,0,S1\nR1,OLD,T3,Cais do Sodré,0,S1\n");
  add("stop_times.txt", "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nT1,08:00:00,08:00:00,A,1\nT1,08:20:00,08:20:00,B,2\nT2,25:10:00,25:10:00,A,1\nT2,25:30:00,25:30:00,B,2\nT3,09:00:00,09:00:00,A,1\nT3,09:20:00,09:20:00,B,2\n");
  add("shapes.txt", "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\nS1,38.64,-9.10,1\nS1,38.67,-9.12,2\nS1,38.705,-9.145,3\n");
  return zip.toBuffer();
}

/** Lê o pacote como o TransitPack.kt (só o que o teste confere). */
function readPack(buf) {
  let pos = 0;
  const varint = () => { let r = 0; let m = 1; while (true) { const b = buf[pos++]; r += (b & 0x7f) * m; if (!(b & 0x80)) return r; m *= 128; } };
  const text = () => { const n = varint(); const s = buf.slice(pos, pos + n).toString("utf8"); pos += n; return s; };
  const ints = () => Array.from({ length: varint() }, varint);
  assert.strictEqual(buf.slice(0, 4).toString("ascii"), "TPK1"); pos = 4;
  const code = text(); text();
  const services = Array.from({ length: varint() }, () => ({ name: text(), days: varint(), start: varint(), end: varint(), adds: ints(), removes: ints() }));
  const routes = Array.from({ length: varint() }, () => ({ id: text(), short: text(), name: text(), color: text(), type: varint() }));
  const stops = Array.from({ length: varint() }, () => ({ id: text(), name: text(), lat: varint() / 1e6, lon: varint() / 1e6 - 180 }));
  const shapes = Array.from({ length: varint() }, text);
  const patterns = Array.from({ length: varint() }, () => {
    const p = { route: varint(), dir: varint(), headsign: text(), shape: varint() - 1, stops: ints() };
    p.profiles = Array.from({ length: varint() }, () => Array.from({ length: p.stops.length - 1 }, varint));
    p.trips = Array.from({ length: varint() }, () => ({ id: text(), start: varint(), profile: varint(), service: varint() }));
    return p;
  });
  return { code, services, routes, stops, shapes, patterns };
}

test("Pacote: serviços em vigor, paragens todas, viagens depois da meia-noite e o mesmo formato da app", () => {
  const { bytes, stats } = transit.buildPack("LTP61", sampleGtfs(), new Date(Date.UTC(2026, 10, 24)));
  const pack = readPack(zlib.gunzipSync(transit.gzipPack(bytes)));
  assert.strictEqual(pack.code, "LTP61");
  // O serviço de 2020 fica de fora; o U com a exceção do Natal
  assert.deepStrictEqual(pack.services.map((s) => s.name), ["U"]);
  assert.deepStrictEqual(pack.services[0].removes, [20261225]);
  assert.strictEqual(pack.services[0].days, 0b11111);
  assert.deepStrictEqual(pack.routes[0], { id: "R1", short: "SX", name: "Seixal - Cais do Sodré", color: "0077C8", type: 4 });
  // A paragem sem viagens também vai (aparece no mapa)
  assert.deepStrictEqual(pack.stops.map((s) => s.name), ["Seixal", "Cais do Sodré", "Sem viagens"]);
  assert.strictEqual(pack.patterns.length, 1);
  const p = pack.patterns[0];
  assert.strictEqual(p.headsign, "Cais do Sodré");
  assert.deepStrictEqual(p.profiles, [[1200]]);
  // Só as viagens de serviços em vigor, pela hora de partida (a das 25:10 passa da meia-noite)
  assert.deepStrictEqual(p.trips.map((t) => [t.id, t.start]), [["T1", 8 * 3600], ["T2", 25 * 3600 + 600]]);
  assert.strictEqual(stats.trips, 2);
  assert.ok(pack.shapes[0].length > 0);
});

test("CSV com aspas, vírgulas e BOM", () => {
  const rows = transit.parseCsv("﻿a,b\n\"x, y\",\"diz \"\"olá\"\"\"\r\n1,2\n");
  assert.deepStrictEqual(rows, [{ a: "x, y", b: 'diz "olá"' }, { a: "1", b: "2" }]);
});

test("Planos ativos da TML: o mais recente de cada operador da camada", () => {
  const plans = { data: [
    { agency_id: "HF16N", active_from: 20261005, active_until: 20261016, is_active: false, operation_gtfs_normalized_url: "old" },
    { agency_id: "HF16N", active_from: 20261017, active_until: 20271231, is_active: true, operation_gtfs_normalized_url: "new" },
    { agency_id: "IA9T6", active_from: 20261012, active_until: 20261231, is_active: true, operation_gtfs_normalized_url: "carris" },
  ] };
  assert.deepStrictEqual(transit.activeTmlPlans(plans, new Date(Date.UTC(2026, 10, 24))), { HF16N: "new" });
});

test("Polyline: o exemplo da documentação do Google", () => {
  assert.strictEqual(transit.encodePolyline([[38.5, -120.2], [40.7, -120.95], [43.252, -126.453]]), "_p~iF~ps|U_ulLnnqC_mqNvxq`@");
});
