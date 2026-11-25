"""
Gera app/src/main/assets/cm_timetable.bin a partir do GTFS da Carris Metropolitana (o mesmo formato do
carris_timetable.bin, lido por CarrisTimetable.kt, com a cor de cada linha). Uso:
  python tools/build_cm_timetable.py gtfs.zip app/src/main/assets/cm_timetable.bin [AAAAMMDD]
O GTFS está em https://api.carrismetropolitana.pt/v2/gtfs (cerca de 78 MB; o stop_times.txt tem ~800 MB, lido
aos poucos, uma viagem de cada vez). Só entram os serviços com dias a partir da data dada (por omissão, hoje).

Formato (como o da Carris, "CTT2"): igual ao "CTT1" mas cada linha tem também a cor ("C61D23") e cada paragem
o nome, a posição (lat e lon+180 em milionésimos de grau) e o concelho; as viagens não têm número (a e b a 0):
o horário da CM serve para o planeador e as paragens para a pesquisa, sem rede.
"""
import collections, csv, datetime, gzip, io, sys, zipfile

src, out = sys.argv[1], sys.argv[2]
today = int(sys.argv[3]) if len(sys.argv) > 3 else int(datetime.date.today().strftime('%Y%m%d'))
z = zipfile.ZipFile(src)


def rows(name):
    return csv.DictReader(io.TextIOWrapper(z.open(name), 'utf-8-sig'))


feed = next(rows('feed_info.txt'))
names = set(z.namelist())
cal = {r['service_id']: r for r in rows('calendar.txt')} if 'calendar.txt' in names else {}
exc = collections.defaultdict(lambda: ([], []))
for r in rows('calendar_dates.txt'):
    exc[r['service_id']][0 if r['exception_type'] == '1' else 1].append(int(r['date']))

active = []
for s in sorted(set(cal) | set(exc)):
    c = cal.get(s)
    adds, removes = exc[s]
    if (c and int(c['end_date']) >= today) or any(d >= today for d in adds):
        active.append(s)
svc_idx = {s: i for i, s in enumerate(active)}

trips = {}
for r in rows('trips.txt'):
    if r['service_id'] in svc_idx:
        trips[r['trip_id']] = (r['route_id'], int(r['direction_id'] or 0), svc_idx[r['service_id']])

stop_idx = {}
patterns = collections.OrderedDict()
seen = set()
broken = 0


def finish(tid, lst):
    """Junta uma viagem ao seu percurso (linha, sentido e paragens), com o perfil de tempos em minutos."""
    global broken
    if tid in seen:
        broken += 1  # stop_times fora de ordem: esta viagem já foi fechada antes
        return
    seen.add(tid)
    route, d, svc = trips[tid]
    lst.sort()
    stops = tuple(x[1] for x in lst)
    start = lst[0][2]
    offs = tuple(round((x[2] - start) / 60) for x in lst)
    p = patterns.setdefault((route, d, stops), {'profiles': {}, 'trips': []})
    prof = p['profiles'].setdefault(offs, len(p['profiles']))
    p['trips'].append((start, prof, svc))
    for s in stops:
        stop_idx.setdefault(s, len(stop_idx))


cur, acc = None, []
for r in rows('stop_times.txt'):
    tid = r['trip_id']
    if tid not in trips:
        continue
    if tid != cur:
        if cur is not None:
            finish(cur, acc)
        cur, acc = tid, []
    h, m, s = map(int, (r['arrival_time'] or r['departure_time']).split(':'))
    acc.append((int(r['stop_sequence']), r['stop_id'], h * 3600 + m * 60 + s))
if cur is not None:
    finish(cur, acc)

buf = bytearray()


def varint(n):
    assert n >= 0, n
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            buf.append(b | 0x80)
        else:
            buf.append(b)
            return


def text(s):
    b = s.encode('utf-8')
    varint(len(b))
    buf.extend(b)


buf.extend(b'CTT2')
text(feed['feed_version'])
varint(len(active))
for s in active:
    c = cal.get(s)
    text(s)
    mask = 0
    if c:
        for i, day in enumerate(['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday']):
            if c[day] == '1':
                mask |= 1 << i
    varint(mask)
    varint(int(c['start_date']) if c else 0)
    varint(int(c['end_date']) if c else 0)
    adds, removes = exc[s]
    for lst in (sorted(adds), sorted(removes)):
        varint(len(lst))
        for dd in lst:
            varint(dd)

routes = {r['route_id']: (r['route_short_name'], r['route_long_name'], r.get('route_color') or '') for r in rows('routes.txt')}
used_routes = sorted({route for route, _, _ in patterns})
varint(len(used_routes))
for rid in used_routes:
    short, long_, color = routes.get(rid, (rid, '', ''))
    text(rid)
    text(short)
    text(long_)
    text(color)

SMALL = {'de', 'da', 'do', 'das', 'dos', 'e', 'a', 'o', 'em', 'à', 'ao'}


def nice(name):
    """Os nomes todos em maiúsculas ("BELA VISTA (R AFONSO ALBUQUERQUE 9)") passam a "Bela Vista (R Afonso Albuquerque 9)"."""
    if name != name.upper():
        return name
    words = []
    for i, w in enumerate(name.lower().split(' ')):
        words.append(w if (i > 0 and w in SMALL) else w[:1].upper() + w[1:] if not w.startswith('(') else '(' + w[1:2].upper() + w[2:])
    return ' '.join(words)


meta = {}
for r in rows('stops.txt'):
    area = r.get('municipality_name') or r.get('locality_name') or ''
    meta[r['stop_id']] = (nice(r['stop_name'].strip()), float(r['stop_lat']), float(r['stop_lon']), area)

stops_by_idx = sorted(stop_idx, key=stop_idx.get)
varint(len(stops_by_idx))
for s in stops_by_idx:
    name, lat, lon, area = meta.get(s, (s, 0.0, -180.0, ''))
    text(s)
    text(name)
    varint(round(lat * 1e6))
    varint(round((lon + 180) * 1e6))
    text(area)

varint(len(patterns))
n_trips = n_profiles = 0
for (route, d, stops), p in patterns.items():
    text(route)
    varint(d)
    varint(len(stops))
    for s in stops:
        varint(stop_idx[s])
    profs = sorted(p['profiles'], key=p['profiles'].get)
    varint(len(profs))
    for offs in profs:
        prev = 0
        for o in offs[1:]:
            varint(max(0, o - prev))
            prev = max(prev, o)
    n_profiles += len(profs)
    ts = sorted(p['trips'])
    varint(len(ts))
    for start, prof, svc in ts:
        varint(0); varint(0); varint(start); varint(prof); varint(svc)
    n_trips += len(ts)

data = gzip.compress(bytes(buf), 9)
open(out, 'wb').write(data)
print('feed', feed['feed_version'], 'from', today, 'services', len(active))
print('patterns', len(patterns), 'profiles', n_profiles, 'trips', n_trips, 'stops', len(stops_by_idx), 'out of order', broken)
print('raw', len(buf), 'gz', len(data))
