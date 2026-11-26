"""
Gera app/src/main/assets/cp_network.json.bin (JSON comprimido com gzip) a partir do GTFS público
da CP com traçados reais do OpenStreetMap (github.com/joaodcp/cp-gtfs-enhanced, dados ODbL).

Conteúdo:
  stations    [[código CP, nome, lat, lon], ...]           estações (código no formato da API: "94-31039")
  shapes      ["polyline codificada", ...]                 traçado de cada percurso, simplificado
  network     ["polyline codificada", ...]                 rede (troços únicos) para desenhar por baixo
  trainShape  {"número do comboio": índice em shapes}

Uso (o zip tem de ser descarregado antes, à parte):
  python -I scripts/build_cp_network.py cp_gtfs_enhanced.zip app/src/main/assets/cp_network.json.bin
"""
import csv
import gzip
import io
import json
import math
import sys
import zipfile
from collections import Counter, defaultdict

SHAPE_TOLERANCE_M = 8.0      # percurso do comboio selecionado
NETWORK_TOLERANCE_M = 20.0   # rede de fundo
GRID = 0.0004                # ~40 m: troços com os mesmos pontos de grelha contam como o mesmo


def read_csv(z, name):
    return list(csv.DictReader(io.StringIO(z.read(name).decode("utf-8-sig"))))


def metres(a, b):
    k = math.cos(math.radians((a[0] + b[0]) / 2))
    dy = (b[0] - a[0]) * 111320
    dx = (b[1] - a[1]) * 111320 * k
    return math.hypot(dx, dy)


def point_segment_distance(p, a, b):
    k = math.cos(math.radians(p[0]))
    ax, ay, bx, by, px, py = a[1] * k, a[0], b[1] * k, b[0], p[1] * k, p[0]
    vx, vy = bx - ax, by - ay
    l2 = vx * vx + vy * vy
    t = 0 if l2 == 0 else max(0, min(1, ((px - ax) * vx + (py - ay) * vy) / l2))
    qx, qy = ax + vx * t - px, ay + vy * t - py
    return math.hypot(qx, qy) * 111320


def simplify(points, tolerance):
    """Douglas-Peucker iterativo."""
    if len(points) < 3:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        i, j = stack.pop()
        best, index = 0.0, -1
        for k in range(i + 1, j):
            d = point_segment_distance(points[k], points[i], points[j])
            if d > best:
                best, index = d, k
        if best > tolerance and index > 0:
            keep[index] = True
            stack.append((i, index))
            stack.append((index, j))
    return [p for p, k in zip(points, keep) if k]


def encode(points):
    """Google "encoded polyline", precisão 5."""
    out = []
    plat = plon = 0
    for lat, lon in points:
        ilat, ilon = round(lat * 1e5), round(lon * 1e5)
        for v in (ilat - plat, ilon - plon):
            v = ~(v << 1) if v < 0 else v << 1
            while v >= 0x20:
                out.append(chr((0x20 | (v & 0x1F)) + 63))
                v >>= 5
            out.append(chr(v + 63))
        plat, plon = ilat, ilon
    return "".join(out)


def main(zip_path, out_path):
    z = zipfile.ZipFile(zip_path)

    stations = []
    for s in read_csv(z, "stops.txt"):
        if s.get("location_type") == "1":
            stations.append([s["stop_id"].replace("_", "-"), s["stop_name"],
                             round(float(s["stop_lat"]), 5), round(float(s["stop_lon"]), 5)])

    raw = defaultdict(list)
    for r in read_csv(z, "shapes.txt"):
        raw[r["shape_id"]].append((int(r["shape_pt_sequence"]), float(r["shape_pt_lat"]), float(r["shape_pt_lon"])))
    shapes_pts = {sid: [(lat, lon) for _, lat, lon in sorted(pts)] for sid, pts in raw.items()}

    # Número do comboio -> traçado mais usado por esse número
    by_number = defaultdict(Counter)
    for t in read_csv(z, "trips.txt"):
        number, shape = (t.get("trip_short_name") or "").strip(), t.get("shape_id")
        if number.isdigit() and shape in shapes_pts:
            by_number[number][shape] += 1

    used = sorted({c.most_common(1)[0][0] for c in by_number.values()})
    index = {sid: i for i, sid in enumerate(used)}
    shapes = [encode(simplify(shapes_pts[sid], SHAPE_TOLERANCE_M)) for sid in used]
    train_shape = {n: index[c.most_common(1)[0][0]] for n, c in sorted(by_number.items(), key=lambda kv: int(kv[0]))}

    # Rede: cada traçado partido em troços entre pontos de grelha; só fica a 1.ª ocorrência de cada troço
    seen = set()
    network = []
    for sid in used:
        pts = simplify(shapes_pts[sid], NETWORK_TOLERANCE_M)
        run = []
        for a, b in zip(pts, pts[1:]):
            key = tuple(sorted(((round(a[0] / GRID), round(a[1] / GRID)), (round(b[0] / GRID), round(b[1] / GRID)))))
            if key in seen:
                if len(run) > 1:
                    network.append(encode(run))
                run = []
                continue
            seen.add(key)
            if not run:
                run = [a]
            run.append(b)
        if len(run) > 1:
            network.append(encode(run))

    data = {"stations": stations, "shapes": shapes, "network": network, "trainShape": train_shape}
    raw_json = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    with open(out_path, "wb") as f:
        f.write(gzip.compress(raw_json, 9))
    print(f"{len(stations)} estações, {len(shapes)} traçados, {len(network)} troços de rede, "
          f"{len(train_shape)} comboios; {len(raw_json) // 1024} KB -> {len(gzip.compress(raw_json, 9)) // 1024} KB gzip")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
