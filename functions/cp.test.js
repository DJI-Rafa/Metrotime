// Testes da lógica pura da Cloud Function: `npm test` (node --test) dentro de functions/.
const test = require("node:test");
const assert = require("node:assert");
const cp = require("./cp");

const at = (h, m, s = 0) => h * 3600 + m * 60 + s;

/** Um comboio como vem do ruicosta (a estrutura real, reduzida). */
function ruicostaTrain({ number = 125, status = "IN_TRANSIT", stops = {}, trainStops }) {
  return {
    train_id: number,
    status,
    data: {
      status: {
        trainNumber: number, runDate: "2026-11-23", delay: 855, lastStation: "94-31039",
        latitude: "39.359", longitude: "-8.565", status, hasDisruptions: false,
      },
      stops,
    },
    db: {
      trainNumber: number,
      trainService: { code: 1, designation: "Alfa Pendular" },
      trainOrigin: { code: "94-30007", designation: "Lisboa Santa Apolonia" },
      trainDestination: { code: "94-2006", designation: "Porto Campanha" },
    },
    fixed: {
      trainNumber: number,
      trainStops: trainStops || [
        { station: { code: "94-30007" }, arrival: null, departure: "12:00", ETD: "12:00" },
        { station: { code: "94-31039" }, arrival: "12:08", departure: "12:09", ETA: "12:07", ETD: "12:11" },
        { station: { code: "94-36004" }, arrival: "13:45", departure: "13:47", supression: { code: "x" } },
        { station: { code: "94-2006" }, arrival: "14:48", departure: null },
      ],
    },
  };
}

test("secondsOf lê HH:mm e HH:mm:ss", () => {
  assert.strictEqual(cp.secondsOf("12:51:53"), at(12, 51, 53));
  assert.strictEqual(cp.secondsOf("12:51"), at(12, 51));
  assert.strictEqual(cp.secondsOf(""), null);
  assert.strictEqual(cp.secondsOf("abc"), null);
});

test("ageSeconds atravessa a meia-noite", () => {
  assert.strictEqual(cp.ageSeconds(at(23, 50), at(0, 5)), 15 * 60);
  assert.strictEqual(cp.ageSeconds(at(0, 5), at(23, 50)), -15 * 60);
  assert.strictEqual(cp.ageSeconds(at(12, 0), at(12, 1)), 60);
});

test("lastPassage fica com a passagem mais recente que já aconteceu", () => {
  const stops = {
    "94-31005": { arrival: "12:05:47", departure: "12:05:47" },
    "94-32284": { arrival: "12:51:53", departure: "12:51:53" },
    "94-31039": { arrival: "12:07:28", departure: "12:11:46" },
    // Uma previsão no futuro não conta
    "94-36004": { arrival: "13:59:00", departure: "14:01:00" },
  };
  assert.deepStrictEqual(cp.lastPassage(stops, at(13, 1, 53)), { code: "94-32284", time: "12:51:53", left: true });
  assert.strictEqual(cp.lastPassage({}, at(13, 0)), null);
  assert.strictEqual(cp.lastPassage(undefined, at(13, 0)), null);
});

test("lastPassage perto da meia-noite", () => {
  const stops = {
    A: { arrival: "23:50:00", departure: "23:51:00" },
    B: { arrival: "00:03:10", departure: "00:03:10" },
  };
  assert.deepStrictEqual(cp.lastPassage(stops, at(0, 5)), { code: "B", time: "00:03:10", left: true });
  // Às 23:55 a passagem das 00:03 ainda é do futuro
  assert.deepStrictEqual(cp.lastPassage(stops, at(23, 55)), { code: "A", time: "23:51:00", left: true });
});

test("lastPassage: parado numa estação só tem a chegada", () => {
  const stops = {
    "94-53629": { arrival: "12:55:05", departure: "12:56:03" },
    "94-53546": { arrival: "13:01:38", departure: null },
  };
  assert.deepStrictEqual(cp.lastPassage(stops, at(13, 2)), { code: "94-53546", time: "13:01:38", left: false });
});

test("routeOf compacta as paragens comerciais", () => {
  const route = cp.routeOf(ruicostaTrain({}).fixed, "2026-11-23T00:00:00");
  assert.strictEqual(route, "2026-11-23|94-30007,,12:00,0;94-31039,12:08,12:09,0;94-36004,13:45,13:47,1;94-2006,14:48,,0");
  assert.strictEqual(cp.routeOf({ trainStops: [] }, "2026-11-23"), null);
  assert.strictEqual(cp.routeOf(null, "2026-11-23"), null);
  assert.strictEqual(cp.routeOf({ trainStops: [{ station: {} }, { station: { code: "A" } }] }, "2026-11-23"), null);
});

test("fromRuicosta junta a última passagem e o percurso", () => {
  const train = ruicostaTrain({ stops: { "94-32284": { arrival: "12:51:53", departure: "12:51:53" } } });
  const { vehicles } = cp.fromRuicosta([train], at(13, 1, 53));
  assert.strictEqual(vehicles.length, 1);
  assert.strictEqual(vehicles[0].passCode, "94-32284");
  assert.strictEqual(vehicles[0].passTime, "12:51:53");
  // De passagem (chegada = partida): já saiu
  assert.strictEqual(vehicles[0].passLeft, true);
  assert.ok(vehicles[0].route.startsWith("2026-11-23|94-30007,,12:00,0;"));
});

test("buildSnapshot põe o percurso em routesMap e não no veículo", () => {
  const train = ruicostaTrain({ stops: { "94-32284": { arrival: "12:51:53", departure: "12:51:53" } } });
  const base = cp.fromRuicosta([train], at(13, 1, 53));
  const snap = cp.buildSnapshot(base, "2026-11-23", Date.UTC(2026, 10, 23, 13, 1, 53), {});
  assert.ok(snap.routesMap[125].startsWith("2026-11-23|"));
  assert.strictEqual(snap.vehiclesMap[125].route, undefined);
  assert.strictEqual(snap.vehiclesMap[125].passCode, "94-32284");
});

test("overlayLive mantém a passagem e o percurso do ruicosta", () => {
  const base = cp.fromRuicosta([ruicostaTrain({ stops: { X: { arrival: "12:50:00" } } })], at(13, 0));
  const live = { vehicles: [{ trainNumber: 125, runDate: "2026-11-23", delay: 900, latitude: "39.4", longitude: "-8.5", status: "IN_TRANSIT", timestamp: 1 }], suppressed: [] };
  const merged = cp.overlayLive(base, live);
  assert.strictEqual(merged.vehicles[0].passCode, "X");
  assert.ok(merged.vehicles[0].route);
  assert.strictEqual(merged.vehicles[0].latitude, "39.4");
  assert.strictEqual(merged.vehicles[0].delay, 900);
});

test("sampleKey é ordenável e sem caracteres proibidos no Firebase", () => {
  const key = cp.sampleKey(new Date(Date.UTC(2026, 10, 23, 13, 0, 42)));
  assert.strictEqual(key, "2026-11-23T13-00Z");
  assert.ok(!/[.#$\[\]/]/.test(key));
  assert.ok(cp.sampleKey(new Date(Date.UTC(2026, 10, 16, 13, 0))) < key);
});
