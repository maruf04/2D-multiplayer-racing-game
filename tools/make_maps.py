#!/usr/bin/env python3
"""
Builds the race tracks from OpenStreetMap data ((c) OpenStreetMap contributors, ODbL).

  python3 tools/make_maps.py fetch [id ...]    download OSM data into tools/osm/ (needs internet)
  python3 tools/make_maps.py build [id ...]    write src/racer/maps/<id>.map and maps/index.txt
  python3 tools/make_maps.py preview           write tools/osm/preview.png (needs Pillow)

The list of tracks and their settings is tools/maps.json:
  id, name, city   - shown in the game
  type             - lake | stadium | park | campus  (what is inside the circuit)
  landmark         - OSM objects the circuit goes around, e.g. "relation/2256206"
  widen            - lakes only: extra water added on every shore, in pixels
  scale            - optional pixels per metre (otherwise chosen for a ~25 second lap)

Requires: pip install shapely numpy
"""
import json, math, os, struct, sys, time, urllib.parse, urllib.request
import numpy as np
from shapely import affinity
from shapely.geometry import LinearRing, LineString, Point, Polygon, box
from shapely.ops import polygonize, unary_union

HERE = os.path.dirname(os.path.abspath(__file__))
OSM_DIR = os.path.join(HERE, 'osm')
OUT_DIR = os.path.join(HERE, '..', 'src', 'racer', 'maps')
OVERPASS = 'https://overpass-api.de/api/interpreter'

HALF = 110.0          # widest road half width (4 lanes) - must match Track.HALF_WIDTH
VERGE = 190           # free space outside the road (grandstands, food stalls)
CLOSE = 150           # fills narrow inlets so the road never overlaps itself
MARGIN = 520          # city shown around the circuit
STEP = 24.0           # race line sample spacing
LAP_TARGET = 10500    # pixels per lap (about 25 seconds)
MAX_PIXELS = 24e6     # keeps the map image under ~100 MB
BANK = {'lake': 120, 'park': 140, 'campus': 140, 'stadium': 260}   # road edge -> landmark
ROLE = {'water': 0, 'island': 1, 'stadium': 2, 'field': 3, 'forest': 4, 'lawn': 5}
STREET_W = {'motorway': 30, 'trunk': 28, 'trunk_link': 18, 'primary': 26, 'primary_link': 16, 'secondary': 22,
            'secondary_link': 16, 'tertiary': 18, 'tertiary_link': 14, 'residential': 13, 'unclassified': 13,
            'living_street': 10, 'service': 8, 'pedestrian': 8}


def load_config():
    return json.load(open(os.path.join(HERE, 'maps.json')))


# ------------------------------------------------------------------ OSM download

def overpass(query):
    body = urllib.parse.urlencode({'data': query}).encode()
    for attempt in range(8):
        try:
            req = urllib.request.Request(OVERPASS, data=body, headers={'User-Agent': 'racing-game-map-builder'})
            with urllib.request.urlopen(req, timeout=400) as r:
                data = r.read()
            if data[:1] == b'{':
                return json.loads(data)
            print('   server busy, retrying...')
        except Exception as e:  # network errors, timeouts
            print('   request failed:', e)
        time.sleep(20 * (attempt + 1))
    raise SystemExit('Overpass API did not answer - try again later')


def fetch(cfg):
    os.makedirs(OSM_DIR, exist_ok=True)
    lm_path = os.path.join(OSM_DIR, cfg['id'] + '_landmark.json')
    if not os.path.exists(lm_path):
        print(cfg['id'], ': landmark')
        q = '[out:json][timeout:120];(' + ''.join(f"{t}({i});" for t, i in (s.split('/') for s in cfg['landmark'])) + ');out geom;'
        json.dump(overpass(q)['elements'], open(lm_path, 'w'))
    area_path = os.path.join(OSM_DIR, cfg['id'] + '_area.json')
    if os.path.exists(area_path):
        return
    geo = Geo(cfg)
    s, w, n, e = geo.bbox()
    print(cfg['id'], f': buildings and streets in {s:.4f},{w:.4f},{n:.4f},{e:.4f}')
    b = f'({s:.5f},{w:.5f},{n:.5f},{e:.5f})'
    q = (f'[out:json][timeout:300][maxsize:900000000];(way["building"]{b};way["highway"]{b};way["bridge"]{b};'
         f'way["natural"="water"]{b};way["leisure"="pitch"]{b};);out geom;')
    out = []
    for el in overpass(q)['elements']:
        if 'geometry' not in el:
            continue
        t = el.get('tags', {})
        keep = {k: t[k] for k in ('building', 'building:levels', 'highway', 'bridge', 'natural', 'leisure', 'sport') if k in t}
        out.append({'t': keep, 'g': [[round(p['lat'], 7), round(p['lon'], 7)] for p in el['geometry']]})
    json.dump(out, open(area_path, 'w'), separators=(',', ':'))
    print('   ', len(out), 'objects')


# ------------------------------------------------------------------ geometry

class Geo:
    """Landmark geometry for one track, in metres (x east, y south) and in game pixels."""

    def __init__(self, cfg):
        self.cfg = cfg
        self.type = cfg['type']
        els = json.load(open(os.path.join(OSM_DIR, cfg['id'] + '_landmark.json')))
        lats = [p['lat'] for el in els for p in self._points(el)]
        lons = [p['lon'] for el in els for p in self._points(el)]
        self.lat0, self.lon0 = (min(lats) + max(lats)) / 2, (min(lons) + max(lons)) / 2
        self.k = math.cos(math.radians(self.lat0))
        polys = [self._polygon(el) for el in els]
        self.landmark_m = unary_union([p.buffer(0) for p in polys]).buffer(0.5).buffer(-0.5)
        self.bank = BANK[self.type]
        self.d_off = HALF + self.bank
        self.scale = cfg.get('scale') or self._solve_scale()

    @staticmethod
    def _points(el):
        if el['type'] == 'way':
            return el['geometry']
        return [p for m in el['members'] if 'geometry' in m for p in m['geometry']]

    def to_m(self, lat, lon):
        return ((lon - self.lon0) * 111320 * self.k, (self.lat0 - lat) * 110540)

    def _polygon(self, el):
        if el['type'] == 'way':
            return Polygon([self.to_m(p['lat'], p['lon']) for p in el['geometry']])
        def rings(role):
            lines = [LineString([self.to_m(p['lat'], p['lon']) for p in m['geometry']])
                     for m in el['members'] if m.get('role') == role and 'geometry' in m and len(m['geometry']) > 1]
            return unary_union(list(polygonize(unary_union(lines)))) if lines else None
        outer, inner = rings('outer'), rings('inner')
        return outer.difference(inner) if inner is not None else outer

    def core(self, scale):
        """What the road goes around, in pixels: widened water for lakes, the landmark otherwise."""
        base = affinity.scale(self.landmark_m, scale, scale, origin=(0, 0))
        if self.type != 'lake':
            return base, base
        water = base.buffer(self.cfg.get('widen', 0), resolution=16)
        for part in getattr(base, 'geoms', [base]):
            for hole in part.interiors:
                water = water.difference(Polygon(hole))
        return water, base

    def region(self, scale):
        core, _ = self.core(scale)
        r = core.buffer(self.d_off + CLOSE, resolution=24).buffer(-CLOSE, resolution=24)
        return max(getattr(r, 'geoms', [r]), key=lambda g: g.area)

    def _solve_scale(self):
        lo, hi = 0.8, 7.0
        if self.region(hi).exterior.length < LAP_TARGET:
            s = hi
        else:
            for _ in range(30):
                mid = (lo + hi) / 2
                if self.region(mid).exterior.length < LAP_TARGET: lo = mid
                else: hi = mid
            s = (lo + hi) / 2
        while s > 0.5:   # keep the map image a sensible size
            x0, y0, x1, y1 = self.region(s).bounds
            if (x1 - x0 + 2 * (HALF + MARGIN)) * (y1 - y0 + 2 * (HALF + MARGIN)) <= MAX_PIXELS:
                break
            s *= 0.95
        return round(s, 3)

    def bbox(self):
        x0, y0, x1, y1 = self.region(self.scale).bounds
        pad = HALF + MARGIN + 40
        x0, y0, x1, y1 = (x0 - pad) / self.scale, (y0 - pad) / self.scale, (x1 + pad) / self.scale, (y1 + pad) / self.scale
        return (self.lat0 - y1 / 110540, self.lon0 + x0 / (111320 * self.k),
                self.lat0 - y0 / 110540, self.lon0 + x1 / (111320 * self.k))


# ------------------------------------------------------------------ build one track

def build(cfg):
    geo = Geo(cfg)
    s = geo.scale
    core, base = geo.core(s)
    region = geo.region(s)
    ring = LinearRing(region.exterior.coords).simplify(2.0)
    n = int(ring.length // STEP)
    pts = np.array([ring.interpolate(i * ring.length / n).coords[0] for i in range(n)])
    area = 0.5 * np.sum(pts[:, 0] * np.roll(pts[:, 1], -1) - np.roll(pts[:, 0], -1) * pts[:, 1])
    if area > 0:
        pts = pts[::-1]      # anti-clockwise on screen (y points down)
    ang = np.arctan2(np.roll(pts[:, 1], -1) - pts[:, 1], np.roll(pts[:, 0], -1) - pts[:, 0])
    turn = np.abs((np.roll(ang, -1) - ang + np.pi) % (2 * np.pi) - np.pi)
    score = [sum(turn[(i + j) % n] for j in range(-22, 8)) for i in range(n)]   # straight start + grid
    pts = np.roll(pts, -int(np.argmin(score)), axis=0)

    worst = 1e9
    for i in range(n):
        d = np.hypot(pts[:, 0] - pts[i, 0], pts[:, 1] - pts[i, 1])
        idx = np.arange(n)
        sep = np.minimum(np.abs(idx - i), n - np.abs(idx - i))
        worst = min(worst, d[sep > 30].min())
    if worst < 2 * HALF + 80:
        raise SystemExit(f"{cfg['id']}: road would overlap itself ({worst:.0f}px)")

    minx = pts[:, 0].min() - HALF - MARGIN
    miny = pts[:, 1].min() - HALF - MARGIN
    W = int(pts[:, 0].max() + HALF + MARGIN - minx)
    H = int(pts[:, 1].max() + HALF + MARGIN - miny)
    def world(x, y): return (int(round(x - minx)), int(round(y - miny)))
    road = LinearRing(pts)
    keep_out = road.buffer(max(HALF + VERGE, geo.d_off + 15))
    world_box = box(minx, miny, minx + W, miny + H)

    rings = []   # (role, [(x, y), ...])
    def add_poly(poly, outer_role, hole_role=None, tol=1.5):
        for part in getattr(poly, 'geoms', [poly]):
            if part.is_empty or part.geom_type != 'Polygon' or part.area < 50:
                continue
            ext = np.array(part.exterior.simplify(tol).coords)[:-1]
            rings.append((outer_role, [world(x, y) for x, y in ext]))
            if hole_role is not None:
                for h in part.interiors:
                    rings.append((hole_role, [world(x, y) for x, y in np.array(LinearRing(h).simplify(1.0).coords)[:-1]]))

    water = core if geo.type == 'lake' else None
    if geo.type == 'lake':
        add_poly(core, ROLE['water'], ROLE['island'])
    elif geo.type == 'stadium':
        add_poly(base, ROLE['stadium'])
    elif geo.type == 'park':
        add_poly(base, ROLE['forest'])
    else:
        add_poly(base, ROLE['lawn'])

    # ------------------------------------------------ surroundings from the area download
    feats = json.load(open(os.path.join(OSM_DIR, cfg['id'] + '_area.json')))
    def px(g): return [(x * s, y * s) for x, y in (geo.to_m(a, b) for a, b in g)]
    buildings, streets, bridges, pitches, ponds = [], [], [], [], []
    for f in feats:
        t, g = f['t'], f['g']
        if len(g) < 2:
            continue
        if t.get('leisure') == 'pitch' and len(g) >= 4:
            pitches.append(Polygon(px(g)).buffer(0))
        if t.get('natural') == 'water' and len(g) >= 4:
            ponds.append(Polygon(px(g)).buffer(0))
        if 'building' in t and len(g) >= 4:
            p = Polygon(px(g)).buffer(0)
            if p.is_empty or p.geom_type != 'Polygon' or p.area < 40 or not world_box.contains(p):
                continue
            if p.intersects(keep_out) or (water is not None and p.intersects(water)):
                continue
            if geo.type == 'stadium' and p.intersects(base.buffer(-5)):
                continue
            try:
                lv = max(1, min(20, int(float(t.get('building:levels', '')))))
            except ValueError:
                lv = 0
            buildings.append((lv, [world(x, y) for x, y in np.array(p.exterior.simplify(0.8).coords)[:-1]]))
        elif t.get('highway') in STREET_W and 'bridge' not in t:
            line = LineString(px(g)).intersection(world_box).difference(keep_out.buffer(-10))
            if water is not None:
                line = line.difference(water)
            if geo.type == 'stadium':
                line = line.difference(base)
            for part in ([line] if line.geom_type == 'LineString' else list(getattr(line, 'geoms', []))):
                if part.geom_type == 'LineString' and part.length > 10:
                    streets.append((STREET_W[t['highway']], [world(x, y) for x, y in part.coords]))
        if geo.type == 'lake' and ('bridge' in t) and t.get('highway'):
            line = LineString(px(g))
            if line.length < 3000 and line.intersects(base):
                bridges.append((line, t.get('highway')))

    # ponds inside the circuit (campus lakes, park ponds) and outside it in the city
    for p in ponds:
        if p.is_empty or p.area < 400 or p.intersects(keep_out) or not world_box.contains(p):
            continue
        if water is not None and p.intersects(water):
            continue
        add_poly(p, ROLE['water'], ROLE['island'])

    if geo.type == 'stadium':
        inside = [p for p in pitches if not p.is_empty and p.geom_type == 'Polygon' and base.contains(p.centroid)]
        big = max(inside, key=lambda p: p.area) if inside else None
        if big is not None and big.area > 0.3 * base.area:
            field = big
        else:
            x0, y0, x1, y1 = base.bounds
            field = base.buffer(-0.2 * min(x1 - x0, y1 - y0))
        add_poly(field, ROLE['field'])

    bridge_rows = []
    if water is not None:
        bank_limit = water.buffer(18)
        for line, kind in bridges:
            cut = line.intersection(base)
            c = cut.centroid if not cut.is_empty else line.centroid
            (x0, y0), (x1, y1) = line.coords[0], line.coords[-1]
            L = math.hypot(x1 - x0, y1 - y0)
            if L < 1:
                continue
            dx, dy = (x1 - x0) / L, (y1 - y0) / L
            ends = []
            for sgn in (1, -1):
                d = 0
                while d < 900 and bank_limit.contains(Point(c.x + sgn * dx * d, c.y + sgn * dy * d)):
                    d += 2
                ends.append((c.x + sgn * dx * d, c.y + sgn * dy * d))
            if any(road.distance(Point(*e)) < HALF + 20 for e in ends):
                continue
            mid = ((ends[0][0] + ends[1][0]) / 2, (ends[0][1] + ends[1][1]) / 2)
            if any(math.hypot(mid[0] - b[0], mid[1] - b[1]) < 40 for b in bridge_rows):
                continue
            width = 26 if kind in ('residential', 'tertiary', 'secondary', 'primary', 'trunk', 'unclassified') else 14
            bridge_rows.append((mid[0], mid[1], *world(*ends[0]), *world(*ends[1]), width))

    # ------------------------------------------------ write the .map file
    os.makedirs(OUT_DIR, exist_ok=True)
    def utf(sv):
        b = sv.encode('utf-8')
        return struct.pack('>H', len(b)) + b
    out = bytearray(b'RMAP') + struct.pack('>h', 1)
    out += utf(cfg['id']) + utf(cfg['name']) + utf(cfg['city']) + utf(geo.type)
    out += struct.pack('>fhii', s, geo.bank, W, H)
    out += struct.pack('>h', n)
    for x, y in pts:
        out += struct.pack('>hh', *world(x, y))
    out += struct.pack('>h', len(rings))
    for role, pts_r in rings:
        out += struct.pack('>bh', role, len(pts_r))
        for x, y in pts_r:
            out += struct.pack('>hh', x, y)
    out += struct.pack('>h', len(bridge_rows))
    for b in bridge_rows:
        out += struct.pack('>hhhhh', *b[2:])
    out += struct.pack('>i', len(streets))
    for w, line in streets:
        out += struct.pack('>bh', w, len(line))
        for x, y in line:
            out += struct.pack('>hh', x, y)
    out += struct.pack('>i', len(buildings))
    for lv, poly in buildings:
        out += struct.pack('>bh', lv, len(poly))
        for x, y in poly:
            out += struct.pack('>hh', x, y)
    open(os.path.join(OUT_DIR, cfg['id'] + '.map'), 'wb').write(out)
    print(f"{cfg['id']:11s} scale={s:5.2f}px/m lap={ring.length:6.0f}px world={W}x{H} rings={len(rings)} "
          f"bridges={len(bridge_rows)} buildings={len(buildings)} streets={len(streets)} gap={worst:.0f}px")


def write_index(configs):
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, 'index.txt'), 'w') as f:
        for c in configs:
            if os.path.exists(os.path.join(OUT_DIR, c['id'] + '.map')):
                f.write(f"{c['id']}|{c['name']}|{c['city']}|{c['type']}\n")


def preview(configs):
    from PIL import Image, ImageDraw   # only needed for previews
    tiles = []
    for c in configs:
        path = os.path.join(OUT_DIR, c['id'] + '.map')
        if not os.path.exists(path):
            continue
        data = open(path, 'rb').read()
        o = 6
        def rd(fmt):
            nonlocal o
            v = struct.unpack_from(fmt, data, o); o += struct.calcsize(fmt); return v
        for _ in range(4):
            ln, = rd('>H'); o += ln
        sc, bank, W, H = rd('>fhii')
        n, = rd('>h'); center = [rd('>hh') for _ in range(n)]
        nr, = rd('>h'); rings = []
        for _ in range(nr):
            role, cnt = rd('>bh'); rings.append((role, [rd('>hh') for _ in range(cnt)]))
        nb, = rd('>h'); brs = [rd('>hhhhh') for _ in range(nb)]
        ns, = rd('>i'); streets = []
        for _ in range(ns):
            w, cnt = rd('>bh'); streets.append((w, [rd('>hh') for _ in range(cnt)]))
        nbu, = rd('>i'); blds = []
        for _ in range(nbu):
            lv, cnt = rd('>bh'); blds.append([rd('>hh') for _ in range(cnt)])
        k = 300 / max(W, H)
        im = Image.new('RGB', (int(W * k) + 1, int(H * k) + 1), (170, 166, 156)); dr = ImageDraw.Draw(im)
        for w, line in streets: dr.line([(x * k, y * k) for x, y in line], fill=(90, 90, 95), width=1)
        for b in blds: dr.polygon([(x * k, y * k) for x, y in b], fill=(220, 210, 195))
        colors = {0: (44, 112, 180), 1: (70, 138, 62), 2: (150, 150, 155), 3: (70, 160, 70), 4: (30, 100, 40), 5: (120, 180, 100)}
        for role, r in rings: dr.polygon([(x * k, y * k) for x, y in r], fill=colors[role])
        for b in brs: dr.line([(b[0] * k, b[1] * k), (b[2] * k, b[3] * k)], fill=(220, 190, 140), width=2)
        dr.line([(x * k, y * k) for x, y in center + center[:1]], fill=(50, 50, 55), width=max(2, int(200 * k)))
        x0, y0 = center[0]; dr.ellipse((x0 * k - 4, y0 * k - 4, x0 * k + 4, y0 * k + 4), fill='white')
        x1, y1 = center[6]; dr.ellipse((x1 * k - 3, y1 * k - 3, x1 * k + 3, y1 * k + 3), fill='yellow')
        dr.text((4, 4), c['id'], fill='black')
        tiles.append(im)
    sheet = Image.new('RGB', (310 * 5, 310 * ((len(tiles) + 4) // 5)), (40, 40, 40))
    for i, t in enumerate(tiles):
        sheet.paste(t, ((i % 5) * 310, (i // 5) * 310))
    sheet.save(os.path.join(OSM_DIR, 'preview.png'))


if __name__ == '__main__':
    if len(sys.argv) < 2 or sys.argv[1] not in ('fetch', 'build', 'preview'):
        print(__doc__)
        sys.exit(1)
    configs = load_config()
    chosen = [c for c in configs if len(sys.argv) < 3 or c['id'] in sys.argv[2:]]
    if sys.argv[1] == 'fetch':
        for c in chosen: fetch(c)
    elif sys.argv[1] == 'build':
        for c in chosen: build(c)
        write_index(configs)
    else:
        preview(configs)
