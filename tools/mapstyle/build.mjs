// Generates everything the offline map needs besides the map file itself, into
// androidApp/src/main/assets/map/:
//
//   style-light.json, style-dark.json   Protomaps basemap styles with Polish labels
//   sprites/                            the icons those styles use
//   fonts/                              the glyphs those styles use, for the alphabets worth shipping;
//                                       the app's build adds an empty file for every other range of
//                                       characters of the fonts listed in fonts/fonts.txt
//
//   cd tools/mapstyle && npm install && npm run build
//
// Run it again after changing the version of @protomaps/basemaps in package.json,
// which has to match the basemap version of the map files built by tools/citypack.

import { layers, namedFlavor } from "@protomaps/basemaps";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ASSETS = "https://raw.githubusercontent.com/protomaps/basemaps-assets/main";
const target = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../androidApp/src/main/assets/map");
const flavors = ["light", "dark"];

// Font names as the styles spell them, and the folder each gets. The folders have
// no spaces so that the glyph URL needs no escaping.
const fonts = {
  "Noto Sans Regular": "NotoSans-Regular",
  "Noto Sans Medium": "NotoSans-Medium",
  "Noto Sans Italic": "NotoSans-Italic",
  "Noto Sans Devanagari Regular v1": "NotoSansDevanagari-Regular",
};

// Glyphs come in files of 256 characters. These three hold what labels in a Polish
// city are written with: basic and western European Latin, the central European
// letters (ą, ł, ż, also č, ő and the like), and dashes and quotation marks.
// Add ranges here when a city needs another alphabet, e.g. 1024 for Cyrillic.
const shippedRanges = new Set([0, 256, 8192]);
const fontsWithGlyphs = new Set(["Noto Sans Regular", "Noto Sans Medium", "Noto Sans Italic"]);

async function download(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`${response.status} for ${url}`);
  return Buffer.from(await response.arrayBuffer());
}

function write(file, data) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, data);
}

fs.rmSync(target, { recursive: true, force: true });

for (const flavor of flavors) {
  const style = {
    version: 8,
    // The app puts the location of the map file here; it is only known on the device.
    sources: { protomaps: { type: "vector", url: "{pmtiles}", attribution: "© OpenStreetMap" } },
    glyphs: "asset://map/fonts/{fontstack}/{range}.pbf",
    sprite: `asset://map/sprites/${flavor}`,
    layers: layers("protomaps", namedFlavor(flavor), { lang: "pl" }),
  };
  let json = JSON.stringify(style);
  for (const [name, folder] of Object.entries(fonts)) json = json.replaceAll(`"${name}"`, `"${folder}"`);
  if (json.includes('"Noto Sans')) throw new Error("The style uses a font this script does not know");
  write(path.join(target, `style-${flavor}.json`), json);

  for (const suffix of [".json", ".png", "@2x.json", "@2x.png"]) {
    write(path.join(target, "sprites", flavor + suffix), await download(`${ASSETS}/sprites/v4/${flavor}${suffix}`));
  }
}

let shipped = 0;
for (const [name, folder] of Object.entries(fonts)) {
  if (!fontsWithGlyphs.has(name)) continue;
  for (const start of shippedRanges) {
    const range = `${start}-${start + 255}`;
    write(path.join(target, "fonts", folder, `${range}.pbf`), await download(`${ASSETS}/fonts/${encodeURIComponent(name)}/${range}.pbf`));
    shipped++;
  }
}
write(path.join(target, "fonts", "OFL.txt"), await download(`${ASSETS}/fonts/OFL.txt`));
// Read by the app's build, which must provide a file for every range of every font a style may ask for.
write(path.join(target, "fonts", "fonts.txt"), Object.values(fonts).join("\n") + "\n");

const size = (dir) =>
  fs.readdirSync(dir, { withFileTypes: true, recursive: true })
    .filter((entry) => entry.isFile())
    .reduce((sum, entry) => sum + fs.statSync(path.join(entry.parentPath, entry.name)).size, 0);
console.log(`${flavors.length} styles, ${shipped} glyph files with glyphs, ${(size(target) / 1e6).toFixed(2)} MB in ${target}`);
