# Offline World Map Data (`world_map_50m.zip`)

`world_map_50m.zip` contains full-resolution Natural Earth 1:50m vector coastline and lake polygons as well as world city metadata (capitals of all UN countries, state/provincial capitals, and all cities with population $\ge 100{,}000$) used by `OfflineWorldMap` in [`MapPanel.kt`](../../src/com/android/tools/idea/streaming/emulator/dialogs/MapPanel.kt) to render the offline world map in the Emulator Location dialog.

## Data Source

* **Source Dataset:** [Natural Earth](https://www.naturalearthdata.com/) 1:50m Physical Vector Data (`v5.1.2`):
  * `ne_50m_land.shp` (landmasses and islands, plus the Caspian Sea inner ring)
  * `ne_50m_lakes.shp` (major lakes and inland seas, plus islands inside lakes)
  * In `google3`, these shapefiles are mirrored under `//depot/google3/third_party/py/cartopy/data/shapefiles/natural_earth/physical/`.

## Processing Steps

1. **Antimeridian ($\pm 180^\circ$) Stitching:**
   Natural Earth splits polygons that cross the $\pm 180^\circ$ meridian along $\pm 180^\circ$. To prevent vertical seam lines or wrap-around artifacts when rendering in Web Mercator, the split polygon pairs are stitched across $+180^\circ$ by shifting the eastern part's longitudes by $+360^\circ$:
   * Chukotka / Eurasia (`record 1381` + `record 138`)
   * Wrangel Island (`record 1380` + `record 61`)
   * Fiji — Viti Levu (`record 1` + `record 1346`), Vanua Levu (`record 299` + `record 297`), and Taveuni (`record 1345` + `record 298`)
2. **Antarctica Web Mercator Clamping:**
   The Ross Ice Shelf in `record 1379` reaches $-85.19^\circ\text{S}$, slightly south of the Web Mercator projection limit ($\pm 85.0511^\circ$). Coastline vertices are clamped to $\ge -85.00^\circ\text{S}$ and the polygon is closed along the southern Web Mercator boundary from $+180^\circ$ to $-180^\circ$ in $30^\circ$ longitude steps so each delta-encoded step fits in a signed 16-bit integer.
3. **Inland Seas and Lake Islands:**
   * The inner ring of Eurasia (`record 1381`, ring 1) representing the Caspian Sea is moved to the lake polygon list.
   * Inner rings of `ne_50m_lakes.shp` (islands inside major lakes, such as Manitoulin Island in Lake Huron) are appended to the land polygon list.
4. **Full-Resolution Geometry & Topology Validation:**
   * All islands and lakes in the 1:50m dataset (`1,468` land polygons and `413` lake polygons, `76,763` vertices total) are retained at $0.001^\circ$ ($\approx 110\text{ m}$) resolution without vertex decimation (Ramer–Douglas–Peucker is only applied if quantization would introduce a self-intersection in Web Mercator `float32` space).

## Binary Format (`world_map_50m.zip`)

The file is a `zip` archive containing a single entry (`world_map_50m.bin`), which is a big-endian binary stream containing two polygon groups (**land polygons**, followed by **lake polygons**) and a **cities** table:

```text
Group (repeated 2 times: [0] = land polygons, [1] = lake polygons):
  u16 (unsigned short) : polygon_count
  Polygon (repeated polygon_count times):
    u16 (unsigned short) : vertex_count
    First Vertex (v = 0):
      i32 (signed int)   : lat_thousandths  (round(lat * 1000))
      i32 (signed int)   : lon_thousandths  (round(lon * 1000))
    Subsequent Vertices (repeated vertex_count - 1 times):
      i16 (signed short) : delta_lat_thousandths  (round(lat * 1000) - prev_lat_thousandths)
      i16 (signed short) : delta_lon_thousandths  (round(lon * 1000) - prev_lon_thousandths)

Cities:
  u16 (unsigned short) : city_count
  City (repeated city_count times):
    utf (u16 byte_len + UTF-8 bytes) : name
    utf (u16 byte_len + UTF-8 bytes) : region
    i32 (signed int)                 : lat_ten_thousandths  (round(lat * 10000))
    i32 (signed int)                 : lon_ten_thousandths  (round(lon * 10000))
    u8  (unsigned byte)              : flags  (bits 0..3 = minZoom, bit 4 = isCapital)
```

Cities are stored in decreasing display priority order (`minZoom = 1` for UN capitals and cities $\ge 1{,}000{,}000$; `minZoom = 3` for state/provincial capitals and cities $\ge 500{,}000$; `minZoom = 4` for cities $\ge 250{,}000$; `minZoom = 5` for cities $\ge 100{,}000$).

## Generation Script

To regenerate `world_map_50m.zip` from the Natural Earth 1:50m shapefiles:

```python
#!/usr/bin/env python3
import math
import os
import struct
import zipfile

SHP_DIR = "/google/src/files/head/depot/google3/third_party/py/cartopy/data/shapefiles/natural_earth/physical"
OUT_PATH = os.path.join(os.path.dirname(__file__), "world_map_50m.zip")


def read_shp_records(path):
  with open(path, "rb") as f:
    data = f.read()
  pos = 100
  records = []
  while pos < len(data):
    rec_num, content_len = struct.unpack(">II", data[pos : pos + 8])
    pos += 8
    rec_end = pos + content_len * 2
    shape_type = struct.unpack("<I", data[pos : pos + 4])[0]
    if shape_type == 5:
      num_parts, num_points = struct.unpack("<II", data[pos + 36 : pos + 44])
      parts = struct.unpack(f"<{num_parts}I", data[pos + 44 : pos + 44 + 4 * num_parts])
      pts_start = pos + 44 + 4 * num_parts
      pts = struct.unpack(f"<{num_points * 2}d", data[pts_start : pts_start + 16 * num_points])
      rings = []
      for idx in range(num_parts):
        start = parts[idx]
        end = parts[idx + 1] if idx + 1 < num_parts else num_points
        ring = [(pts[2 * k + 1], pts[2 * k]) for k in range(start, end)]
        rings.append(ring)
      records.append((rec_num, rings))
    pos = rec_end
  return records


def lat_to_norm_y(lat):
  clamped = max(-85.05112878, min(85.05112878, lat))
  rad = clamped * math.pi / 180.0
  return (1.0 - math.log(math.tan(rad) + 1.0 / math.cos(rad)) / math.pi) / 2.0


def orient(a, b, c):
  return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])


def on_seg(a, b, p):
  return min(a[0], b[0]) <= p[0] <= max(a[0], b[0]) and min(a[1], b[1]) <= p[1] <= max(a[1], b[1])


def seg_intersect(a, b, c, d):
  o1 = orient(a, b, c)
  o2 = orient(a, b, d)
  o3 = orient(c, d, a)
  o4 = orient(c, d, b)
  if (o1 > 0 > o2 or o1 < 0 < o2) and (o3 > 0 > o4 or o3 < 0 < o4):
    return True
  if abs(o1) < 1e-12 and on_seg(a, b, c):
    return True
  if abs(o2) < 1e-12 and on_seg(a, b, d):
    return True
  if abs(o3) < 1e-12 and on_seg(c, d, a):
    return True
  if abs(o4) < 1e-12 and on_seg(c, d, b):
    return True
  return False


def has_self_intersection_mercator(rpts):
  n = len(rpts)
  if n < 3:
    return True
  mpts = [((lon + 180.0) / 360.0, lat_to_norm_y(lat)) for lat, lon in rpts]
  buckets = {}
  edges = []
  for i in range(n):
    a, b = mpts[i], mpts[(i + 1) % n]
    min_x, max_x = min(a[0], b[0]), max(a[0], b[0])
    min_y, max_y = min(a[1], b[1]), max(a[1], b[1])
    edges.append((a, b, min_x, max_x, min_y, max_y))
    for gx in range(int(min_x * 180), int(max_x * 180) + 1):
      for gy in range(int(min_y * 180), int(max_y * 180) + 1):
        buckets.setdefault((gx, gy), []).append(i)
  seen = set()
  for cell_edges in buckets.values():
    m = len(cell_edges)
    for a_idx in range(m):
      i = cell_edges[a_idx]
      a, b, min_x1, max_x1, min_y1, max_y1 = edges[i]
      for b_idx in range(a_idx + 1, m):
        j = cell_edges[b_idx]
        if abs(i - j) <= 1 or (i == 0 and j == n - 1) or (j == 0 and i == n - 1):
          continue
        pair = (min(i, j), max(i, j))
        if pair in seen:
          continue
        a2, b2, min_x2, max_x2, min_y2, max_y2 = edges[j]
        if max_x1 < min_x2 or max_x2 < min_x1 or max_y1 < min_y2 or max_y2 < min_y1:
          continue
        seen.add(pair)
        if seg_intersect(a, b, a2, b2):
          return True
  return False


def rdp(points, epsilon):
  if len(points) < 3:
    return points
  x1, y1 = points[0]
  x2, y2 = points[-1]
  dx, dy = x2 - x1, y2 - y1
  dmax, index = 0.0, 0
  denom = math.hypot(dx, dy)
  for i in range(1, len(points) - 1):
    px, py = points[i]
    d = math.hypot(px - x1, py - y1) if denom == 0.0 else abs(dy * px - dx * py + x2 * y1 - y2 * x1) / denom
    if d > dmax:
      index, dmax = i, d
  if dmax > epsilon:
    return rdp(points[: index + 1], epsilon)[:-1] + rdp(points[index:], epsilon)
  return [points[0], points[-1]]


def clean_ring(pts):
  rpts = []
  for lat, lon in pts:
    p = (round(lat, 3), round(lon, 3))
    if not rpts or rpts[-1] != p:
      rpts.append(p)
  if len(rpts) > 1 and rpts[0] == rpts[-1]:
    rpts.pop()
  return rpts


def simplify_safe(pts, suffix=None):
  for eps in [0.0, 0.002, 0.005, 0.01, 0.02, 0.05]:
    simplified = rdp(pts, eps) if eps > 0 else pts
    cand = clean_ring(simplified + (suffix if suffix else []))
    if len(cand) >= 3 and not has_self_intersection_mercator(cand):
      return cand
  return None


def main():
  land50 = dict(read_shp_records(os.path.join(SHP_DIR, "ne_50m_land.shp")))
  lakes50 = dict(read_shp_records(os.path.join(SHP_DIR, "ne_50m_lakes.shp")))

  # Stitch polygons split across the +/-180 antimeridian.
  r1381, r138 = land50[1381][0][:-1], land50[138][0][:-1]
  land50[1381][0] = r1381[:2548] + [(lat, lon + 360.0) for lat, lon in r138[:320]] + r1381[2551:]
  del land50[138]

  r1380, r61 = land50[1380][0][:-1], land50[61][0][:-1]
  land50[1380][0] = r1380[:11] + [(lat, lon + 360.0) for lat, lon in (r61[25:] + r61[:19])] + r1380[13:]
  del land50[61]

  r1, r1346 = land50[1][0][:-1], land50[1346][0][:-1]
  land50[1][0] = r1[1:55] + [(lat, lon + 360.0) for lat, lon in (r1346[4:] + r1346[:1])]
  del land50[1346]

  r299, r297 = land50[299][0][:-1], land50[297][0][:-1]
  land50[299][0] = r299[1:4] + [(lat, lon + 360.0) for lat, lon in (r297[5:] + r297[:1])]
  del land50[297]

  r1345, r298 = land50[1345][0][:-1], land50[298][0][:-1]
  land50[1345][0] = r1345[1:3] + [(lat, lon + 360.0) for lat, lon in (r298[4:] + r298[:1])]
  del land50[298]

  # Antarctica (record 1379): clamp coastline vertices to >= -85.00°S so the
  # southern closing edge at -85.05°S stays strictly south of all coastline points.
  r1379 = land50[1379][0][:-1]
  land50[1379][0] = [(max(lat, -85.00), lon) for lat, lon in (r1379[2070:] + r1379[:1804])]

  # Extract Caspian Sea inner ring from Eurasia into lakes.
  caspian = land50[1381][1]
  land50[1381] = [land50[1381][0]]
  lakes50[9999] = [caspian]

  land_rings, lake_rings, island_in_lake_rings = [], [], []
  antarctica_suffix = [(-85.05, float(lon)) for lon in range(180, -181, -30)]

  for rec_num, rings in sorted(land50.items()):
    for r_idx, r in enumerate(rings):
      if r_idx > 0:
        continue
      pts = r[:-1] if r[0] == r[-1] else r
      suffix = antarctica_suffix if rec_num == 1379 else None
      s = simplify_safe(pts, suffix=suffix)
      if s:
        land_rings.append(s)

  for rec_num, rings in sorted(lakes50.items()):
    for r_idx, r in enumerate(rings):
      pts = r[:-1] if r[0] == r[-1] else r
      s = simplify_safe(pts)
      if s:
        (lake_rings if r_idx == 0 else island_in_lake_rings).append(s)

  all_land = land_rings + island_in_lake_rings

  buf = bytearray()
  for group in [all_land, lake_rings]:
    buf += struct.pack(">H", len(group))
    for ring in group:
      buf += struct.pack(">H", len(ring))
      prev_lat, prev_lon = 0, 0
      for idx, (lat, lon) in enumerate(ring):
        ilat, ilon = int(round(lat * 1000)), int(round(lon * 1000))
        if idx == 0:
          buf += struct.pack(">ii", ilat, ilon)
        else:
          buf += struct.pack(">hh", ilat - prev_lat, ilon - prev_lon)
        prev_lat, prev_lon = ilat, ilon

  with zipfile.ZipFile(OUT_PATH, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
    zf.writestr("world_map_50m.bin", bytes(buf))


if __name__ == "__main__":
  main()
```
