const test = require("node:test");
const assert = require("node:assert");
const zlib = require("zlib");
const osm = require("./osm");
const transit = require("./transit");

test("Junta as vias pela ordem, virando as que vêm ao contrário (também a primeira)", () => {
  const line = osm.stitch([
    [[41.02, -8.6], [41.01, -8.6]], // ao contrário
    [[41.02, -8.6], [41.03, -8.6]],
    [[41.05, -8.6], [41.04, -8.6], [41.03, -8.6]], // ao contrário
  ]);
  assert.deepStrictEqual(line.map((p) => p[0]), [41.01, 41.02, 41.03, 41.04, 41.05]);
});

/** Uma linha "Z" com dois sentidos, três estações e duas paragens por estação (uma por sentido). */
function sampleOsm() {
  const node = (id, name, lat) => ({ type: "node", id, lat, lon: -8.6, tags: { name, public_transport: "stop_position" } });
  const way = (lats) => ({ type: "way", role: "", geometry: lats.map((lat) => ({ lat, lon: -8.6 })) });
  return { elements: [
    { type: "relation", id: 1, tags: { ref: "Z", name: "Linha Z: Norte ⇒ Sul", colour: "#E62621", to: "Sul" }, members: [
      { type: "node", ref: 11, role: "stop" }, { type: "node", ref: 12, role: "stop" }, { type: "node", ref: 13, role: "stop" },
      way([41.2, 41.15, 41.1]), way([41.1, 41.05, 41.0]),
    ] },
    { type: "relation", id: 2, tags: { ref: "Z", name: "Linha Z: Sul ⇒ Norte", colour: "#E62621", to: "Norte" }, members: [
      { type: "node", ref: 23, role: "stop_entry_only" }, { type: "node", ref: 22, role: "stop" }, { type: "node", ref: 21, role: "stop_exit_only" },
      way([41.0, 41.1]), way([41.2, 41.1]),
    ] },
    node(11, "Norte", 41.2), node(12, "Meio", 41.1), node(13, "Sul", 41.0),
    node(21, "Norte", 41.2002), node(22, "Meio", 41.1002), node(23, "Sul", 41.0002),
  ] };
}

test("Metro do Porto do OpenStreetMap: uma estação por nome, os dois sentidos e o traçado dos carris", () => {
  const rows = osm.metroPortoRows(sampleOsm());
  assert.deepStrictEqual(rows("stops.txt").map((s) => s.stop_name), ["Norte", "Meio", "Sul"]);
  assert.deepStrictEqual(rows("routes.txt"), [{ route_id: "Z", route_short_name: "Z", route_long_name: "Norte - Sul", route_type: "0", route_color: "E62621" }]);
  assert.deepStrictEqual(rows("trips.txt").map((t) => [t.trip_headsign, t.direction_id]), [["Sul", "0"], ["Norte", "1"]]);

  const { bytes, stats } = transit.buildPackFromRows("MDP", rows, new Date(Date.UTC(2026, 10, 24)));
  assert.strictEqual(stats.routes, 1);
  assert.strictEqual(stats.patterns, 2);
  assert.strictEqual(stats.shapes, 2);
  assert.strictEqual(stats.trips, 0); // sem horários
  assert.ok(zlib.gunzipSync(transit.gzipPack(bytes)).slice(0, 4).toString() === "TPK1");
});

test("GTFS oficial com o traçado do OpenStreetMap: cada viagem fica com a relação que liga as estações das pontas dela", () => {
  const files = {
    "routes.txt": [{ route_id: "Z", route_short_name: "Z" }],
    "trips.txt": [{ route_id: "Z", trip_id: "t1", shape_id: "ZG" }, { route_id: "Z", trip_id: "t2", shape_id: "ZG" }],
    "stops.txt": [
      { stop_id: "N", stop_name: "Norte", stop_lat: "41.2", stop_lon: "-8.6" },
      { stop_id: "S", stop_name: "Sul", stop_lat: "41.0", stop_lon: "-8.6" },
    ],
    "stop_times.txt": [
      { trip_id: "t1", stop_id: "N", stop_sequence: "1" }, { trip_id: "t1", stop_id: "S", stop_sequence: "2" },
      { trip_id: "t2", stop_id: "S", stop_sequence: "1" }, { trip_id: "t2", stop_id: "N", stop_sequence: "2" },
    ],
    // O GTFS usa o MESMO traçado nos dois sentidos (e noutra linha), como a A e a F no do Metro do Porto
    "shapes.txt": [
      { shape_id: "ZG", shape_pt_lat: "41.3", shape_pt_lon: "-8.5", shape_pt_sequence: "1" },
      { shape_id: "ZG", shape_pt_lat: "41.0", shape_pt_lon: "-8.6", shape_pt_sequence: "2" },
    ],
  };
  const rows = osm.withOsmShapes((n) => files[n] || [], sampleOsm());
  const trips = rows("trips.txt");
  const of = (id) => rows("shapes.txt").filter((p) => p.shape_id === id).map((p) => Number(p.shape_pt_lat));
  // Norte → Sul: a relação 1 (5 pontos); Sul → Norte: a relação 2
  assert.deepStrictEqual(of(trips[0].shape_id), [41.2, 41.15, 41.1, 41.05, 41.0]);
  assert.deepStrictEqual(of(trips[1].shape_id), [41.0, 41.1, 41.2]);
  // O traçado do GTFS, que já ninguém usa, sai
  assert.deepStrictEqual(of("ZG"), []);
});

test("Um traçado com os pontos fora de ordem não é usado (a linha desenha-se pelas paragens)", () => {
  const files = {
    "routes.txt": [{ route_id: "B", route_short_name: "B", route_long_name: "", route_type: "0" }],
    "stops.txt": [
      { stop_id: "1", stop_name: "A", stop_lat: "41.30", stop_lon: "-8.70" },
      { stop_id: "2", stop_name: "B", stop_lat: "41.20", stop_lon: "-8.65" },
      { stop_id: "3", stop_name: "C", stop_lat: "41.10", stop_lon: "-8.60" },
    ],
    "trips.txt": [
      { route_id: "B", service_id: "X", trip_id: "bom", trip_headsign: "C", direction_id: "0", shape_id: "S1" },
      { route_id: "B", service_id: "X", trip_id: "mau", trip_headsign: "A", direction_id: "1", shape_id: "S2" },
    ],
    "stop_times.txt": [
      { trip_id: "bom", arrival_time: "08:00:00", stop_id: "1", stop_sequence: "1" },
      { trip_id: "bom", arrival_time: "08:10:00", stop_id: "2", stop_sequence: "2" },
      { trip_id: "bom", arrival_time: "08:20:00", stop_id: "3", stop_sequence: "3" },
      { trip_id: "mau", arrival_time: "09:00:00", stop_id: "3", stop_sequence: "1" },
      { trip_id: "mau", arrival_time: "09:10:00", stop_id: "2", stop_sequence: "2" },
      { trip_id: "mau", arrival_time: "09:20:00", stop_id: "1", stop_sequence: "3" },
    ],
    "shapes.txt": [
      // S1 segue as paragens; S2 salta de uma ponta à outra várias vezes
      ...[[41.30, -8.70], [41.25, -8.675], [41.20, -8.65], [41.10, -8.60]].map(([la, lo], i) => ({ shape_id: "S1", shape_pt_lat: String(la), shape_pt_lon: String(lo), shape_pt_sequence: String(i) })),
      ...[[41.10, -8.60], [41.30, -8.70], [41.10, -8.60], [41.30, -8.70], [41.20, -8.65], [41.30, -8.70]].map(([la, lo], i) => ({ shape_id: "S2", shape_pt_lat: String(la), shape_pt_lon: String(lo), shape_pt_sequence: String(i) })),
    ],
  };
  const { stats } = transit.buildPackFromRows("T", (n) => files[n] || [], new Date(Date.UTC(2026, 10, 24)));
  assert.strictEqual(stats.patterns, 2);
  assert.strictEqual(stats.shapes, 1); // só o S1
});
