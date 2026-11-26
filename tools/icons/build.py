#!/usr/bin/env python3
"""Turns Material icons into the vector drawables the app uses. Every icon in
the app comes from here, so that all of them are of one set.

    python tools/icons/build.py

Downloads each icon in ICONS as SVG from the @material-design-icons/svg package
(Google's Material icons, Apache License 2.0) and writes
androidApp/src/main/res/drawable/ic_<name>.xml. Add a name to ICONS to get
another icon; names are those listed at https://fonts.google.com/icons.
"""

import re
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "https://cdn.jsdelivr.net/npm/@material-design-icons/svg@0.14.15/filled"
TARGET = Path(__file__).resolve().parents[2] / "androidApp" / "src" / "main" / "res" / "drawable"

ICONS = [
    # What a saved place can be marked with.
    "bookmark", "bookmark_border", "home", "work", "school", "shopping_cart", "restaurant", "local_cafe", "fitness_center",
    "local_hospital", "park", "pets", "child_care", "sports_soccer", "local_library", "favorite", "star",
    # Kinds of search results (stop, address, named place), the map and the user's own location.
    "signpost", "location_on", "location_city", "map", "my_location", "history",
    # Journeys.
    "directions_walk", "swap_vert", "arrow_forward", "chevron_right", "hourglass_empty", "sync_alt", "trip_origin", "schedule",
    # Live data: delays, service alerts, and vehicles of each kind moving on the map.
    "sensors", "warning", "open_in_new", "directions_bus", "tram", "train", "subway", "directions_boat",
    # Controls.
    "arrow_back", "close", "search", "clear", "check", "settings", "edit", "add",
]

# Icons that point somewhere, drawn mirrored in right-to-left languages.
AUTO_MIRRORED = {"arrow_back", "arrow_forward", "chevron_right"}

SVG = "{http://www.w3.org/2000/svg}"


def number(text):
    return float(text)


def compact(value):
    return f"{value:.3f}".rstrip("0").rstrip(".")


def circle(cx, cy, rx, ry):
    """An ellipse as path data: two half turns."""
    return (
        f"M{compact(cx - rx)},{compact(cy)}"
        f"a{compact(rx)},{compact(ry)} 0 1,0 {compact(2 * rx)},0"
        f"a{compact(rx)},{compact(ry)} 0 1,0 {compact(-2 * rx)},0z"
    )


def path_data(element):
    """Path data for one SVG shape, or None for a shape that draws nothing."""
    if element.get("fill") == "none":
        return None
    tag = element.tag.removeprefix(SVG)
    if tag == "path":
        return element.get("d")
    if tag == "circle":
        radius = number(element.get("r"))
        return circle(number(element.get("cx")), number(element.get("cy")), radius, radius)
    if tag == "ellipse":
        return circle(number(element.get("cx")), number(element.get("cy")), number(element.get("rx")), number(element.get("ry")))
    if tag == "rect":
        if element.get("rx") or element.get("ry"):
            raise ValueError("rounded rectangles are not handled")
        x, y = number(element.get("x", "0")), number(element.get("y", "0"))
        width, height = number(element.get("width")), number(element.get("height"))
        return f"M{compact(x)},{compact(y)}h{compact(width)}v{compact(height)}h{compact(-width)}z"
    if tag == "polygon":
        points = re.split(r"[\s,]+", element.get("points").strip())
        pairs = [f"{points[i]},{points[i + 1]}" for i in range(0, len(points), 2)]
        return "M" + "L".join(pairs) + "z"
    raise ValueError(f"<{tag}> is not handled")


def shapes(element):
    """Every drawing element under [element], in order. Groups only hold other elements here."""
    for child in element:
        tag = child.tag.removeprefix(SVG)
        if tag == "g":
            if child.get("transform"):
                raise ValueError("transformed groups are not handled")
            yield from shapes(child)
        elif tag not in ("title", "desc", "defs"):
            yield child


def convert(name):
    request = urllib.request.Request(f"{PACKAGE}/{name}.svg", headers={"User-Agent": "dojazd-icons"})
    with urllib.request.urlopen(request, timeout=60) as response:
        svg = ET.fromstring(response.read())
    if svg.get("viewBox") != "0 0 24 24":
        raise ValueError(f"unexpected size {svg.get('viewBox')}")

    lines = [
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="24dp"',
        '    android:height="24dp"',
        '    android:viewportWidth="24"',
        '    android:viewportHeight="24"',
    ]
    if name in AUTO_MIRRORED:
        lines.append('    android:autoMirrored="true"')
    lines[-1] += ">"
    for shape in shapes(svg):
        data = path_data(shape)
        if data is None:
            continue
        lines.append("    <path")
        lines.append('        android:fillColor="#FF000000"')
        if shape.get("fill-rule") == "evenodd":
            lines.append('        android:fillType="evenOdd"')
        lines.append(f'        android:pathData="{data}" />')
    lines.append("</vector>")
    (TARGET / f"ic_{name}.xml").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def main():
    for name in ICONS:
        try:
            convert(name)
        except ValueError as problem:
            raise SystemExit(f"{name}: {problem}")
    print(f"{len(ICONS)} icons written to {TARGET}")


if __name__ == "__main__":
    main()
