package mk.kanta.app.feature.map

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Loads `assets/map/kanta_light.json` / `kanta_dark.json` and applies the palette
 * that sits at the top of each file.
 *
 * The point of the exercise (user brief: "put ALL map colours at the top of each
 * JSON so I can change the look in one place") is that
 * `metadata["kanta:palette"]` is authoritative. Each layer carries
 * `metadata["kanta:paint"]`, a map of paint property → palette key, and this
 * class rewrites the layer's paint values from the palette before MapLibre ever
 * sees the style.
 *
 * The literal values are also kept correct in the file by the generator, so the
 * style still opens and previews properly in Maputnik — but the palette is what
 * actually wins at runtime.
 */
object KantaMapStyle {

    private const val LIGHT_ASSET = "map/kanta_light.json"
    private const val DARK_ASSET = "map/kanta_dark.json"

    private const val PALETTE_KEY = "kanta:palette"
    private const val PAINT_KEY = "kanta:paint"

    fun assetFor(darkTheme: Boolean): String = if (darkTheme) DARK_ASSET else LIGHT_ASSET

    /**
     * Returns the style JSON with the palette applied.
     *
     * A malformed style is not recoverable — the map would render as a blank
     * grey rectangle with no clue why — so problems here throw rather than
     * silently degrade.
     */
    fun load(context: Context, darkTheme: Boolean): String {
        val raw = context.assets.open(assetFor(darkTheme)).bufferedReader().use { it.readText() }
        val style = JSONObject(raw)
        val palette = style.optJSONObject("metadata")?.optJSONObject(PALETTE_KEY)
            ?: return raw // No palette: use the file exactly as authored.

        val layers = style.optJSONArray("layers") ?: return style.toString()
        applyPalette(layers, palette)
        return style.toString()
    }

    private fun applyPalette(layers: JSONArray, palette: JSONObject) {
        for (index in 0 until layers.length()) {
            val layer = layers.optJSONObject(index) ?: continue
            val mapping = layer.optJSONObject("metadata")?.optJSONObject(PAINT_KEY) ?: continue

            val paint = layer.optJSONObject("paint") ?: JSONObject().also {
                layer.put("paint", it)
            }

            for (property in mapping.keys()) {
                val paletteKey = mapping.optString(property)
                val colour = palette.optString(paletteKey, "")
                if (colour.isNotEmpty()) {
                    paint.put(property, colour)
                }
            }
        }
    }

    /**
     * The palette itself, for UI that has to sit on top of the map and match it —
     * the attribution line and the offline banner both need the map's own
     * background tone rather than the app surface.
     */
    fun palette(context: Context, darkTheme: Boolean): Map<String, String> {
        val raw = context.assets.open(assetFor(darkTheme)).bufferedReader().use { it.readText() }
        val palette = JSONObject(raw).optJSONObject("metadata")?.optJSONObject(PALETTE_KEY)
            ?: return emptyMap()

        return buildMap {
            for (key in palette.keys()) {
                put(key, palette.optString(key))
            }
        }
    }
}
