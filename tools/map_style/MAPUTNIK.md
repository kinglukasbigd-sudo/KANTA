# Editing the map look

The two map styles live in:

```
app/src/main/assets/map/kanta_light.json
app/src/main/assets/map/kanta_dark.json
```

Both are ordinary MapLibre styles derived from OpenFreeMap's `positron`.

---

## The fast way: edit the palette

**Every colour the map uses is at the top of each file**, in
`metadata → kanta:palette`. Open the JSON in any editor, change a hex value,
rebuild, done.

```jsonc
{
  "version": 8,
  "name": "Kanta Light",
  "metadata": {
    "kanta:palette": {
      "background": "#F3F5F2",
      "water": "#C4D0D5",
      "park": "#E4ECE3",
      "roadMinor": "#E4E5E1",
      ...
    }
  }
}
```

`KantaMapStyle.kt` reads this palette at load time and writes it over the layers
before MapLibre sees the style. Each layer records which palette entry owns which
paint property:

```jsonc
{
  "id": "park",
  "type": "fill",
  "metadata": { "kanta:paint": { "fill-color": "park" } },
  "paint": { "fill-color": "#E4ECE3" }
}
```

So the palette is what actually wins at runtime. The literal values are kept in
sync by the generator purely so the file still previews correctly in Maputnik.

### What each entry does

| Key | Where you see it |
|---|---|
| `background` | The paper the whole map sits on |
| `water`, `waterway`, `waterLabel` | The Vardar and its labels |
| `park`, `wood` | Green space — very pale by design (§3.4 brief) |
| `residential`, `building`, `buildingOutline` | Built-up areas |
| `roadMinor`, `roadMajor*`, `roadMotorway*`, `path`, `pier` | The street hierarchy |
| `railway`, `railwayDash` | Rail |
| `boundary` | Municipality and country lines |
| `labelText`, `labelTextStrong`, `labelHalo`, `roadLabel` | All map text |
| `aeroway` | The airport |

Keep them quiet. §3 spends colour on container markers and one primary action —
if the basemap competes with an orange "full" marker, the map has failed.

---

## The visual way: Maputnik

Use this to move things around or change more than colour.

1. Open <https://maputnik.github.io/editor/>
2. **Open → Upload style** → pick `kanta_light.json`
3. Edit. The map renders live against OpenFreeMap tiles.
4. **Export → Download style**, and save it back over the same file.

### One rule when using Maputnik

If you change a **colour** in Maputnik, **copy it up into `kanta:palette`**.
Otherwise the palette will overwrite your change the next time the app loads the
style, and it will look like your edit did nothing.

Structural edits — layer order, filters, zoom ranges, line widths, font sizes —
are safe to make in Maputnik and need no extra step.

Do not delete a layer's `metadata.kanta:paint` block; that is the link between
the layer and the palette.

---

## Regenerating from scratch

If you want to pull a fresh copy of OpenFreeMap `positron` and re-apply the Kanta
palette over it — say after they change their tile schema:

```bash
python3 tools/map_style/build_styles.py
```

Standard library only. It caches the base style in `tools/map_style/.cache/`;
delete that folder to force a fresh download.

**This overwrites both JSON files**, so any Maputnik edits you have not folded
back into `build_styles.py` will be lost. The palettes themselves live in that
script, near the top — that is the place to make a colour permanent.

---

## Things worth knowing

**Fonts.** OpenFreeMap serves only Noto Sans glyphs, so map labels are Noto Sans
Regular/Bold. §3.2 asks for Nunito, which is not available as served glyph PBFs —
using it would mean generating and hosting our own. Noto Sans is the closest
humanist sans with full Cyrillic and Albanian coverage. The app's own text is
Nunito; this affects labels drawn inside the map only.

**Label language.** Every label uses
`["coalesce", ["get", "name:mk"], ["get", "name"]]` — Macedonian where OSM has
it, the local name otherwise, which in Skopje is also Cyrillic.

**Markers are not in these files.** Container and suggestion markers are drawn in
Kotlin by `MarkerBitmapFactory` and registered as style images at runtime, per
§3.4. Their colours come from `MarkerColors` in the design system, not from this
palette. That separation is deliberate: the basemap is background, the markers
are the message.

**Attribution is not optional.** §7 and the ODbL require
"© OpenStreetMap contributors · OpenFreeMap" to stay visible on the map. It is
rendered by the app, not by the style, so editing these files cannot remove it —
please don't remove it elsewhere either.
