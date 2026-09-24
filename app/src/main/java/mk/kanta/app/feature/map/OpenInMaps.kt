package mk.kanta.app.feature.map

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * §5.2 "Navigate": hands the point to whatever maps app the phone has, through a
 * `geo:` intent — no API, no key. [label] makes the pin show a name (the
 * container code) rather than raw coordinates.
 *
 * A phone with no maps app is rare but real; it simply does nothing rather than
 * crash.
 */
fun Context.openInMaps(lat: Double, lon: Double, label: String) {
    val uri = "geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label)})"
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
    } catch (_: ActivityNotFoundException) {
        // Nothing to hand it to.
    }
}
