"""
Traçado real das linhas do Metro de Lisboa, para o mapa em direto (os metros andam por cima dele).

Lê as relações route=subway do OpenStreetMap (Overpass API; dados © contribuidores do OpenStreetMap, ODbL),
junta as vias de cada linha num só traçado, orienta-o pela ordem das estações da app (MapConfiguration.kt),
corta-o nas estações terminais e grava:

    app/src/main/assets/metro_tracks.json.bin   (JSON comprimido com gzip)
    {"attribution": "...", "lines": {"Azul": "<polyline codificada, precisão 5>", ...}}

Uso: python tools/build_metro_tracks.py [ficheiro.json com uma resposta da Overpass já guardada]
"""
import gzip
import json
import math
import os
import re
import sys
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG = os.path.join(ROOT, "app", "src", "main", "java", "com", "example", "metrotime", "data", "MapConfiguration.kt")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "metro_tracks.json.bin")
OVERPASS = "https://overpass-api.de/api/interpreter"
QUERY = '[out:json][timeout:120];relation["route"="subway"](38.68,-9.27,38.81,-9.07);out geom;'
LINES = {"Azul": "azul", "Amarela": "amarela", "Verde": "verde", "Vermelha": "vermelha"}


def metres(a, b):
    k = math.cos(math.radians((a[0] + b[0]) / 2))
    return math.hypot((b[0] - a[0]) * 111320, (b[1] - a[1]) * 111320 * k)


def stations():
    text = open(CONFIG, encoding="utf-8").read()
    coords = {m.group(1): (float(m.group(3)), float(m.group(4)))
              for m in re.finditer(r'StationCoord\("(\w+)",\s*"([^"]+)",\s*([-\d.]+),\s*([-\d.]+)\)', text)}
    order = {m.group(1): re.findall(r'"(\w+)"', m.group(2))
             for m in re.finditer(r'"(\w+)" to listOf\(([^)]*)\)', text)}
    return coords, {k: v for k, v in order.items() if k in LINES}


def fetch():
    if len(sys.argv) > 1:
        return json.load(open(sys.argv[1], encoding="utf-8"))
    data = urllib.parse.urlencode({"data": QUERY}).encode()
    req = urllib.request.Request(OVERPASS, data=data, headers={"User-Agent": "metrotime-build-tracks"})
    with urllib.request.urlopen(req, timeout=180) as r:
        return json.load(r)


def stitch(ways):
    """Junta as vias pela ordem da relação, virando as que vêm ao contrário."""
    line = []
    for w in ways:
        pts = [(g["lat"], g["lon"]) for g in w]
        if not pts:
            continue
        if not line:
            line = pts
            continue
        ends = [(metres(line[-1], pts[0]), False, False), (metres(line[-1], pts[-1]), False, True),
                (metres(line[0], pts[-1]), True, False), (metres(line[0], pts[0]), True, True)]
        d, prepend, flip = min(ends)
        if d > 300:  # via solta (ramal, oficina): fica de fora
            continue
        if flip:
            pts = pts[::-1]
        line = (pts + line[1:]) if prepend else (line + pts[1:])
    return line


def project(line, p):
    """Distância ao longo do traçado do ponto mais perto de p, e a que distância fica."""
    best = (float("inf"), 0.0)
    along = 0.0
    for a, b in zip(line, line[1:]):
        seg = metres(a, b)
        k = math.cos(math.radians(p[0]))
        ax, ay, bx, by = a[1] * k, a[0], b[1] * k, b[0]
        px, py = p[1] * k, p[0]
        vx, vy = bx - ax, by - ay
        l2 = vx * vx + vy * vy
        t = 0.0 if l2 == 0 else max(0.0, min(1.0, ((px - ax) * vx + (py - ay) * vy) / l2))
        q = (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
        d = metres(q, p)
        if d < best[0]:
            best = (d, along + seg * t)
        along += seg
    return best[1], best[0]


def cut(line, s0, s1):
    out, along = [], 0.0
    for a, b in zip(line, line[1:]):
        seg = metres(a, b)
        for s in (s0, s1):
            if along <= s <= along + seg and seg > 0:
                t = (s - along) / seg
                q = (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
                if not out or metres(out[-1], q) > 0.5:
                    out.append(q)
        if s0 < along + seg and along + seg < s1:
            out.append(b)
        along += seg
    return out


def encode(points):
    def enc(v):
        v = ~(v << 1) if v < 0 else v << 1
        s = ""
        while v >= 0x20:
            s += chr((0x20 | (v & 0x1f)) + 63)
            v >>= 5
        return s + chr(v + 63)
    out, plat, plon = "", 0, 0
    for lat, lon in points:
        ilat, ilon = round(lat * 1e5), round(lon * 1e5)
        out += enc(ilat - plat) + enc(ilon - plon)
        plat, plon = ilat, ilon
    return out


def main():
    coords, order = stations()
    data = fetch()
    rels = [e for e in data.get("elements", []) if e.get("type") == "relation"]
    result = {}
    for name, key in LINES.items():
        candidates = []
        for r in rels:
            label = " ".join(str(r.get("tags", {}).get(t, "")) for t in ("name", "ref", "colour")).lower()
            if key not in label:
                continue
            ways = [m.get("geometry", []) for m in r.get("members", [])
                    if m.get("type") == "way" and m.get("role", "") in ("", "forward", "backward")]
            line = stitch(ways)
            if len(line) > 1:
                candidates.append(line)
        if not candidates:
            print(f"{name}: sem relação no OSM", file=sys.stderr)
            continue
        stops = order[name]
        # O traçado que melhor passa por todas as estações da linha
        def score(line):
            return sum(project(line, coords[s])[1] for s in stops)
        line = min(candidates, key=score)
        first, _ = project(line, coords[stops[0]])
        last, _ = project(line, coords[stops[-1]])
        if first > last:
            line = line[::-1]
            first, _ = project(line, coords[stops[0]])
            last, _ = project(line, coords[stops[-1]])
        line = cut(line, first, last)
        offs = [(s, project(line, coords[s])) for s in stops]
        along = [a for _, (a, _) in offs]
        worst = max(d for _, (_, d) in offs)
        ordered = all(b >= a - 50 for a, b in zip(along, along[1:]))
        km = along[-1] / 1000
        print(f"{name}: {len(line)} pontos, {km:.1f} km, estação mais longe do traçado a {worst:.0f} m, por ordem: {ordered}")
        if worst > 400 or not ordered:
            print(f"  {name}: traçado duvidoso, fica de fora (a app usa linhas retas)", file=sys.stderr)
            continue
        result[name] = encode(line)
    blob = {"attribution": "© contribuidores do OpenStreetMap (ODbL)", "lines": result}
    with gzip.open(OUT, "wb", compresslevel=9) as f:
        f.write(json.dumps(blob, separators=(",", ":")).encode("utf-8"))
    print(f"gravado {OUT} ({os.path.getsize(OUT)} bytes, {len(result)} linhas)")


if __name__ == "__main__":
    main()
