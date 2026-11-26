"""
Gera app/src/main/assets/carris_timetable.bin a partir do GTFS da Carris (gzip de um binário simples,
lido por CarrisTimetable.kt). Uso: python tools/build_carris_timetable.py gtfs.zip app/src/main/assets/carris_timetable.bin [AAAAMMDD]
O GTFS da Carris está em https://gateway.carris.pt/gateway/gtfs/api/v2.11/GTFS (cerca de 48 MB).
Só entram os serviços ainda em vigor a partir da data dada (por omissão, hoje).

Formato (inteiros em varint, texto em UTF-8 com o tamanho em varint):
  "CTT1", feedDate
  serviços: n; nome, dias (bit 0 = segunda ... bit 6 = domingo), início, fim, n adds + datas, n removes + datas
  linhas: n; route_id, número, nome
  paragens: n; id
  percursos: n; route_id, sentido, n paragens + índices, n perfis + (n-1) diferenças em minutos,
             n viagens + (a, b, partida em s, perfil, serviço)  com trip_id = f"{a}_{feedDate}_{route}_{b}"
"""
import collections, csv, datetime, gzip, io, re, sys, zipfile

src, out = sys.argv[1], sys.argv[2]
today = int(sys.argv[3]) if len(sys.argv) > 3 else int(datetime.date.today().strftime('%Y%m%d'))
z = zipfile.ZipFile(src)


def rows(name):
    return csv.DictReader(io.TextIOWrapper(z.open(name), 'utf-8-sig'))


feed = next(rows('feed_info.txt'))
cal = {r['service_id']: r for r in rows('calendar.txt')}
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

trips = {r['trip_id']: (r['route_id'], int(r['direction_id'] or 0), r['service_id'])
         for r in rows('trips.txt') if r['service_id'] in svc_idx}

times = collections.defaultdict(list)
for r in rows('stop_times.txt'):
    if r['trip_id'] not in trips:
        continue
    h, m, s = map(int, (r['arrival_time'] or r['departure_time']).split(':'))
    times[r['trip_id']].append((int(r['stop_sequence']), r['stop_id'], h * 3600 + m * 60 + s))

# Datas dos trip_id: todas iguais (a versão do GTFS)
dates = collections.Counter(t.split('_')[1] for t in trips)
feed_date = dates.most_common(1)[0][0]
pat_re = re.compile(r'^(\d+)_' + feed_date + r'_(.+)_(\d+)$')

stop_idx = {}
patterns = collections.OrderedDict()
skipped = 0
for tid, lst in times.items():
    route, d, svc = trips[tid]
    m = pat_re.match(tid)
    if not m or m.group(2) != route:
        skipped += 1
        continue
    lst.sort()
    stops = tuple(x[1] for x in lst)
    start = lst[0][2]
    offs = tuple(round((x[2] - start) / 60) for x in lst)
    key = (route, d, stops)
    p = patterns.setdefault(key, {'profiles': {}, 'trips': []})
    prof = p['profiles'].setdefault(offs, len(p['profiles']))
    p['trips'].append((int(m.group(1)), int(m.group(3)), start, prof, svc_idx[svc]))
    for s in stops:
        stop_idx.setdefault(s, len(stop_idx))

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


buf.extend(b'CTT1')
text(feed_date)
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

routes = {r['route_id']: (r['route_short_name'], r['route_long_name']) for r in rows('routes.txt')}
used_routes = sorted({route for route, _, _ in patterns})
varint(len(used_routes))
for rid in used_routes:
    short, long_ = routes.get(rid, (rid.split('_')[0], ''))
    text(rid)
    text(short)
    text(long_)

stops_by_idx = sorted(stop_idx, key=stop_idx.get)
varint(len(stops_by_idx))
for s in stops_by_idx:
    text(s)

varint(len(patterns))
n_trips = 0
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
    ts = sorted(p['trips'], key=lambda t: t[2])
    varint(len(ts))
    for a, b, start, prof, svc in ts:
        varint(a); varint(b); varint(start); varint(prof); varint(svc)
    n_trips += len(ts)

data = gzip.compress(bytes(buf), 9)
open(out, 'wb').write(data)
print('feed', feed['feed_version'], 'trip date', feed_date, 'services', active)
print('patterns', len(patterns), 'trips', n_trips, 'stops', len(stops_by_idx), 'skipped', skipped)
print('raw', len(buf), 'gz', len(data))
