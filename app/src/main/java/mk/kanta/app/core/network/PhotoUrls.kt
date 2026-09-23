package mk.kanta.app.core.network

import mk.kanta.app.BuildConfig

/**
 * Public URL for a photo in the `photos` bucket (§6: public read).
 * Built here rather than stored per row, so moving the bucket is one change.
 */
fun publicPhotoUrl(path: String): String =
    "${BuildConfig.SUPABASE_URL}/storage/v1/object/public/photos/$path"
