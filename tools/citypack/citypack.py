#!/usr/bin/env python3
"""Builds the offline data files of one city from OpenStreetMap.

    pip install osmium
    python tools/citypack/citypack.py szczecin --osm zachodniopomorskie-latest.osm.pbf

The city is looked up in data/cities.json. The area covered is the bounding box
of the stops in the city's GTFS feed plus a margin, so nothing has to be drawn
by hand. The OpenStreetMap extract (a .osm.pbf from download.geofabrik.de, for
example) only has to contain that area.

Output:

    data/addresses/<city>.tsv   streets, house numbers and named places, shipped
                                with the app; the format is described at
                                AddressIndex.parse in shared/geocode

The file is plain text on purpose: the app package compresses it anyway, the
Android build tools would unpack a .gz behind our back, and git stores the
difference between two builds instead of two whole files.

    data/walks/<city>.walk      the streets and paths that can be walked, for
                                working out and drawing walks; the format is
                                described at WalkNetwork.read in shared/routing

With --map-source the map of the same area is cut out of a Protomaps basemap
build (see https://maps.protomaps.com/builds), which needs `pip install pmtiles`:

    python tools/citypack/citypack.py szczecin --osm ... \
        --map-source https://build.protomaps.com/20261122.pmtiles

    data/maps/<city>.pmtiles   vector tiles of the area up to --map-maxzoom,
                               shipped with the app

Only the tiles of the area are downloaded, not the whole planet.

The result is derived from OpenStreetMap and may only be distributed under the
Open Database License (ODbL) 1.0, with credit to OpenStreetMap contributors.
"""

import argparse
import csv
import datetime
import gzip
import io
import json
import math
import re
import sys
import urllib.request
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

import osmium

FORMAT = 1
SOURCE = "OpenStreetMap contributors, ODbL 1.0, https://www.openstreetmap.org/copyright"
REPOSITORY = Path(__file__).resolve().parents[2]

# Roads people give as an address or a meeting point; tracks, paths and motorways are not.
STREET_HIGHWAYS = {
    "residential", "living_street", "unclassified", "tertiary", "secondary", "primary", "trunk", "pedestrian", "road",
}

# Named things worth finding by name. A value of None accepts everything under the key except EXCLUDED_AMENITIES.
PLACE_TAGS = {
    "amenity": None,
    "shop": None,
    "office": None,
    "healthcare": None,
    "tourism": {"museum", "hotel", "hostel", "guest_house", "attraction", "gallery", "zoo", "theme_park", "viewpoint"},
    "leisure": {"park", "garden", "stadium", "sports_centre", "fitness_centre", "swimming_pool", "marina", "nature_reserve", "ice_rink"},
    "historic": {"castle", "monument", "fort", "city_gate"},
    "railway": {"station", "halt"},
    "place": {"city", "town", "village", "hamlet", "suburb", "quarter", "neighbourhood"},
    "building": {"church", "cathedral", "university", "hospital", "public", "train_station", "civic", "government"},
}

EXCLUDED_AMENITIES = {
    "parking", "parking_space", "parking_entrance", "bicycle_parking", "motorcycle_parking", "bench", "waste_basket",
    "waste_disposal", "recycling", "vending_machine", "atm", "post_box", "parcel_locker", "telephone", "toilets",
    "shelter", "drinking_water", "fountain", "clock", "charging_station", "bicycle_repair_station", "hunting_stand",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("city", help="id of the city in data/cities.json")
    parser.add_argument("--osm", required=True, type=Path, help="OpenStreetMap extract (.osm.pbf) containing the city")
    parser.add_argument("--gtfs", type=Path, help="GTFS archive to take the stops from; downloaded when not given")
    parser.add_argument("--margin-km", type=float, default=2.0, help="how far beyond the outermost stops to reach")
    parser.add_argument("--out", type=Path, default=REPOSITORY / "data" / "addresses", help="output directory")
    parser.add_argument("--map-source", help="URL of a PMTiles basemap to cut the map of the area from")
    parser.add_argument("--map-maxzoom", type=int, default=14, help="most detailed zoom level to keep in the map")
    parser.add_argument("--map-out", type=Path, default=REPOSITORY / "data" / "maps", help="output directory of the map")
    args = parser.parse_args()

    registry = json.loads((REPOSITORY / "data" / "cities.json").read_text(encoding="utf-8"))
    city = next((c for c in registry["cities"] if c["id"] == args.city), None)
    if city is None:
        sys.exit(f"No city '{args.city}' in data/cities.json")

    bounds = service_area(city, args.gtfs, args.margin_km)
    print("Area (south, west, north, east): " + ", ".join(f"{value:.4f}" for value in bounds))

    date = data_date(args.osm)
    args.out.mkdir(parents=True, exist_ok=True)
    build_addresses(args.osm, bounds, args.out / f"{args.city}.tsv", date)
    walks = REPOSITORY / "data" / "walks"
    walks.mkdir(parents=True, exist_ok=True)
    build_walks(args.osm, bounds, walks / f"{args.city}.walk")
    if args.map_source:
        args.map_out.mkdir(parents=True, exist_ok=True)
        build_map(args.map_source, bounds, args.map_maxzoom, args.map_out / f"{args.city}.pmtiles")


def service_area(city, gtfs_path, margin_km):
    """The bounding box of the city's stops, widened by the margin."""
    if gtfs_path is None:
        print(f"Downloading {city['gtfs']['url']}")
        request = urllib.request.Request(city["gtfs"]["url"], headers={"User-Agent": "dojazd-citypack"})
        with urllib.request.urlopen(request, timeout=120) as response:
            archive = io.BytesIO(response.read())
    else:
        archive = gtfs_path
    with zipfile.ZipFile(archive) as feed:
        name = next(n for n in feed.namelist() if n.rsplit("/", 1)[-1] == "stops.txt")
        with feed.open(name) as stops:
            rows = csv.DictReader(io.TextIOWrapper(stops, encoding="utf-8-sig"))
            points = [(float(row["stop_lat"]), float(row["stop_lon"])) for row in rows if row.get("stop_lat")]
    points = [(lat, lon) for lat, lon in points if lat != 0 or lon != 0]
    if not points:
        sys.exit("The GTFS feed has no stops with coordinates")
    south, north = min(p[0] for p in points), max(p[0] for p in points)
    west, east = min(p[1] for p in points), max(p[1] for p in points)
    lat_margin = margin_km / 111.32
    lon_margin = lat_margin / math.cos(math.radians((south + north) / 2))
    return south - lat_margin, west - lon_margin, north + lat_margin, east + lon_margin


def data_date(osm_path):
    """The day the OpenStreetMap data is from, recorded in the output; today when the file does not say."""
    reader = osmium.io.Reader(str(osm_path))
    try:
        stamp = reader.header().get("osmosis_replication_timestamp")
    finally:
        reader.close()
    if stamp:
        return datetime.date.fromisoformat(stamp[:10])
    return datetime.date.today()


def center_of(obj):
    """A point for the object: its position, or the middle of its outline. None when it has no usable geometry."""
    if obj.is_node():
        return (obj.location.lat, obj.location.lon) if obj.location.valid() else None
    if obj.is_way():
        nodes = obj.nodes
    elif obj.is_area() and not obj.from_way():
        nodes = [node for ring in obj.outer_rings() for node in ring]
    else:
        return None
    located = [(node.lat, node.lon) for node in nodes if node.location.valid()]
    if not located:
        return None
    return sum(p[0] for p in located) / len(located), sum(p[1] for p in located) / len(located)


def middle_of(way):
    """A point that lies on the road itself, unlike the average of a curved road's nodes."""
    located = [(node.lat, node.lon) for node in way.nodes if node.location.valid()]
    return located[len(located) // 2] if located else None


def clean(text):
    """Field text safe for a tab-separated line."""
    return re.sub(r"\s+", " ", text or "").strip()


def title(name):
    """Street names are mapped as "aleja Wojska Polskiego"; at the start of a line they read better capitalised."""
    return name[:1].upper() + name[1:]


def distance_m(a, b):
    lat = math.radians((a[0] + b[0]) / 2)
    return math.hypot((a[0] - b[0]) * 111_320, (a[1] - b[1]) * 111_320 * math.cos(lat))


def natural(number):
    """Sort key putting 2 before 10 and 10 before 10A."""
    match = re.match(r"\d+", number)
    return (int(match.group()) if match else math.inf, number.lower())


def build_addresses(osm_path, bounds, target, date):
    south, west, north, east = bounds

    def inside(point):
        return point is not None and south <= point[0] <= north and west <= point[1] <= east

    addresses = {}   # (street, town, number) -> point
    roads = defaultdict(list)   # name -> points on roads of that name
    places = {}   # (name, detail) -> point

    processor = (
        osmium.FileProcessor(str(osm_path))
        .with_locations()
        .with_areas()
        .with_filter(osmium.filter.KeyFilter("addr:housenumber", "name"))
    )
    for obj in processor:
        # A closed way arrives both as a way and as an area; relations only as areas.
        if not (obj.is_node() or obj.is_way() or (obj.is_area() and not obj.from_way())):
            continue
        tags = obj.tags
        number = clean(tags.get("addr:housenumber"))
        street = title(clean(tags.get("addr:street") or tags.get("addr:place")))
        name = clean(tags.get("name"))

        if obj.is_way() and name and tags.get("highway") in STREET_HIGHWAYS:
            point = middle_of(obj)
            if inside(point):
                roads[title(name)].append(point)
            continue

        is_address = bool(number and street)
        is_place = bool(name) and any(
            key in tags
            and (allowed is None or tags[key] in allowed)
            and not (key == "amenity" and tags[key] in EXCLUDED_AMENITIES)
            for key, allowed in PLACE_TAGS.items()
        )
        if not (is_address or is_place):
            continue
        point = center_of(obj)
        if not inside(point):
            continue
        if is_address:
            town = clean(tags.get("addr:city") or (tags.get("addr:place") if tags.get("addr:street") else ""))
            addresses.setdefault((street, town, number), point)
        if is_place:
            detail = f"{street} {number}" if is_address else clean(tags.get("addr:city"))
            places.setdefault((name, detail), point)

    addresses = with_towns(addresses)

    by_street = defaultdict(list)   # (street, town) -> [(number, point)]
    for (street, town, number), point in addresses.items():
        by_street[(street, town)].append((number, point))

    streets = []   # (name, town, point, numbers)
    for (street, town), numbers in by_street.items():
        mean = (sum(p[0] for _, p in numbers) / len(numbers), sum(p[1] for _, p in numbers) / len(numbers))
        # The street's own point is one of its addresses, so that it is certainly on the street.
        point = min((p for _, p in numbers), key=lambda p: distance_m(p, mean))
        streets.append((street, town, point, sorted(numbers, key=lambda item: natural(item[0]))))

    # Roads without a single address still deserve to be found by name.
    known = defaultdict(list)
    for street, _, point, _ in streets:
        known[street].append(point)
    town_at = nearest_town(addresses)
    unnumbered = 0
    for name, points in roads.items():
        for cluster in clusters(points, 2_000):
            center = cluster[len(cluster) // 2]
            if any(distance_m(center, point) < 2_000 for point in known[name]):
                continue
            streets.append((name, town_at(center), center, []))
            unnumbered += 1

    streets.sort(key=lambda s: (s[0].casefold(), s[1].casefold(), s[2]))
    place_rows = sorted(((name, detail, point) for (name, detail), point in places.items()), key=lambda p: (p[0].casefold(), p[1].casefold(), p[2]))

    def coordinates(point):
        return f"{point[0]:.5f}\t{point[1]:.5f}"

    lines = [
        f"#dojazd-addresses\t{FORMAT}",
        f"#source\t{SOURCE}",
        f"#built\t{date.isoformat()}",
        "#bounds\t" + "\t".join(f"{value:.4f}" for value in bounds),
    ]
    for name, town, point, numbers in streets:
        lines.append(f"S\t{name}\t{town}\t{coordinates(point)}")
        lines.extend(f"N\t{number}\t{coordinates(p)}" for number, p in numbers)
    lines.extend(f"P\t{name}\t{detail}\t{coordinates(point)}" for name, detail, point in place_rows)

    target.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")

    print(f"{len(addresses)} addresses on {len(by_street)} streets, {unnumbered} more streets without addresses, {len(place_rows)} named places")
    print(f"{target}: {target.stat().st_size / 1e6:.2f} MB")


def with_towns(addresses):
    """Fills in the town of addresses mapped without one, from the other addresses of the same street nearby."""
    towns_of_street = defaultdict(list)   # street -> [(town, point)]
    for (street, town, _), point in addresses.items():
        if town:
            towns_of_street[street].append((town, point))
    result = {}
    for (street, town, number), point in addresses.items():
        if not town:
            near = [t for t, p in towns_of_street[street] if distance_m(p, point) < 1_500]
            if near:
                town = Counter(near).most_common(1)[0][0]
        result.setdefault((street, town, number), point)
    return result


def nearest_town(addresses):
    """Returns a function giving the town of the closest address within about half a kilometre, or ''."""
    cell = 0.005
    grid = defaultdict(list)
    for (_, town, _), point in addresses.items():
        if town:
            grid[(int(point[0] / cell), int(point[1] / cell))].append((town, point))

    def lookup(point):
        row, column = int(point[0] / cell), int(point[1] / cell)
        best, best_distance = "", 600
        for r in (row - 1, row, row + 1):
            for c in (column - 1, column, column + 1):
                for town, other in grid.get((r, c), ()):
                    distance = distance_m(point, other)
                    if distance < best_distance:
                        best, best_distance = town, distance
        return best

    return lookup


def clusters(points, max_gap_m):
    """Groups points so that streets of the same name in different towns stay apart."""
    groups = []
    for point in sorted(points):
        for group in groups:
            if any(distance_m(point, other) < max_gap_m for other in group):
                group.append(point)
                break
        else:
            groups.append([point])
    return groups


# Ways open to pedestrians. Motorways and trunk roads are left out; so is anything tagged as closed to walkers.
WALKABLE_HIGHWAYS = {
    "footway", "path", "pedestrian", "steps", "living_street", "residential", "service", "unclassified",
    "tertiary", "tertiary_link", "secondary", "secondary_link", "primary", "primary_link", "track", "cycleway",
    "road", "corridor", "platform",
}
CLOSED = {"no", "private"}
OPEN = {"yes", "designated", "permissive"}


def build_walks(osm_path, bounds, target):
    """Writes the network of walkable ways: every point of every way, and which points each way connects."""
    south, west, north, east = bounds
    index_of = {}   # OpenStreetMap node id -> position in `points`
    points = []   # (lat, lon) in millionths of a degree
    chains = []   # one list of point positions per way
    meters = 0.0

    processor = osmium.FileProcessor(str(osm_path)).with_locations().with_filter(osmium.filter.KeyFilter("highway"))
    for obj in processor:
        if not obj.is_way():
            continue
        tags = obj.tags
        if tags.get("highway") not in WALKABLE_HIGHWAYS or tags.get("area") == "yes":
            continue
        foot = tags.get("foot")
        if foot in CLOSED or (tags.get("access") in CLOSED and foot not in OPEN):
            continue
        nodes = [(node.ref, node.lat, node.lon) for node in obj.nodes if node.location.valid()]
        if len(nodes) < 2 or not any(south <= lat <= north and west <= lon <= east for _, lat, lon in nodes):
            continue
        chain = []
        for ref, lat, lon in nodes:
            position = index_of.get(ref)
            if position is None:
                position = index_of[ref] = len(points)
                points.append((round(lat * 1e6), round(lon * 1e6)))
            chain.append(position)
        chains.append(chain)
        meters += sum(distance_m((a[1], a[2]), (b[1], b[2])) for a, b in zip(nodes, nodes[1:]))

    # Numbers are stored as differences from the previous one, in as few bytes as they need. Points are
    # numbered in the order the ways visit them, so both the coordinates and the chains come out tiny.
    out = bytearray(b"DJWALK\x01")

    def unsigned(value):
        while value >= 0x80:
            out.append((value & 0x7F) | 0x80)
            value >>= 7
        out.append(value)

    def signed(value):
        unsigned((value << 1) ^ (value >> 63))

    unsigned(len(points))
    previous_lat = previous_lon = 0
    for lat, lon in points:
        signed(lat - previous_lat)
        signed(lon - previous_lon)
        previous_lat, previous_lon = lat, lon
    unsigned(len(chains))
    previous = 0
    for chain in chains:
        unsigned(len(chain))
        for position in chain:
            signed(position - previous)
            previous = position
    target.write_bytes(out)
    print(f"{len(points)} points on {len(chains)} walkable ways, {meters / 1000:.0f} km")
    print(f"{target}: {target.stat().st_size / 1e6:.2f} MB")


def build_map(source, bounds, max_zoom, target):
    """Copies the tiles covering the area out of a remote PMTiles archive, fetching nothing else of it."""
    from pmtiles.tile import deserialize_directory, deserialize_header, find_tile, zxy_to_tileid
    from pmtiles.writer import Writer

    south, west, north, east = bounds

    def fetch(offset, length):
        headers = {"Range": f"bytes={offset}-{offset + length - 1}", "User-Agent": "dojazd-citypack"}
        with urllib.request.urlopen(urllib.request.Request(source, headers=headers), timeout=120) as response:
            data = response.read()
        if len(data) != length:
            sys.exit(f"{source} does not support range requests")
        return data

    header = deserialize_header(fetch(0, 127))
    root = deserialize_directory(fetch(header["root_offset"], header["root_length"]))
    metadata = json.loads(gzip.decompress(fetch(header["metadata_offset"], header["metadata_length"])))
    leaves = {}

    def entry_of(tile_id):
        entries = root
        for _ in range(4):
            entry = find_tile(entries, tile_id)
            if entry is None or entry.run_length > 0:
                return entry
            # A run length of zero marks a pointer to a deeper directory.
            if entry.offset not in leaves:
                leaves[entry.offset] = deserialize_directory(fetch(header["leaf_directory_offset"] + entry.offset, entry.length))
            entries = leaves[entry.offset]
        return None

    def column(lon, zoom):
        return min(int((lon + 180) / 360 * (1 << zoom)), (1 << zoom) - 1)

    def row(lat, zoom):
        y = (1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2
        return min(int(y * (1 << zoom)), (1 << zoom) - 1)

    max_zoom = min(max_zoom, header["max_zoom"])
    tiles = {}   # tile id -> (offset, length) in the source
    for zoom in range(header["min_zoom"], max_zoom + 1):
        for x in range(column(west, zoom), column(east, zoom) + 1):
            for y in range(row(north, zoom), row(south, zoom) + 1):
                tile_id = zxy_to_tileid(zoom, x, y)
                entry = entry_of(tile_id)
                if entry is not None:
                    tiles[tile_id] = (entry.offset, entry.length)

    # Neighbouring tiles lie next to each other in the archive, so a few big requests fetch them all.
    contents = {}
    wanted = sorted(set(tiles.values()))
    start = 0
    while start < len(wanted):
        end = start
        while (
            end + 1 < len(wanted)
            and wanted[end + 1][0] - (wanted[end][0] + wanted[end][1]) <= 256_000
            and wanted[end + 1][0] + wanted[end + 1][1] - wanted[start][0] <= 16_000_000
        ):
            end += 1
        first = wanted[start][0]
        block = fetch(header["tile_data_offset"] + first, wanted[end][0] + wanted[end][1] - first)
        for offset, length in wanted[start:end + 1]:
            contents[offset] = block[offset - first:offset - first + length]
        start = end + 1

    with open(target, "wb") as output:
        writer = Writer(output)
        for tile_id in sorted(tiles):
            writer.write_tile(tile_id, contents[tiles[tile_id][0]])
        writer.finalize(
            {
                "tile_type": header["tile_type"],
                "tile_compression": header["tile_compression"],
                "min_lon_e7": round(west * 1e7),
                "min_lat_e7": round(south * 1e7),
                "max_lon_e7": round(east * 1e7),
                "max_lat_e7": round(north * 1e7),
                "center_zoom": min(12, max_zoom),
                "center_lon_e7": round((west + east) / 2 * 1e7),
                "center_lat_e7": round((south + north) / 2 * 1e7),
            },
            metadata,
        )
    print(f"{len(tiles)} map tiles up to zoom {max_zoom}")
    print(f"{target}: {target.stat().st_size / 1e6:.2f} MB")


if __name__ == "__main__":
    main()
