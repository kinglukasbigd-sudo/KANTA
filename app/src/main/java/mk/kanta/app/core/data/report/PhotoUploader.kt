package mk.kanta.app.core.data.report

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import mk.kanta.app.core.data.di.IoDispatcher
import mk.kanta.app.core.data.remote.KantaError
import mk.kanta.app.core.data.remote.KantaResult
import mk.kanta.app.core.data.remote.toKantaError
import mk.kanta.app.core.network.SupabaseClientHolder
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts an already privacy-processed photo (§8: faces blurred, EXIF gone) into the
 * `photos` bucket at `{folder}/{uid}/{id}.jpg` — §6 storage paths: `reports/`,
 * `containers/`, `container_requests/`, `suggestions/`. The storage policy only
 * accepts a user's own folder, so the uid comes from the session, never the caller.
 *
 * Upsert, so the same photo sent twice (a retry) overwrites rather than duplicates.
 */
@Singleton
class PhotoUploader @Inject constructor(
    private val supabase: SupabaseClientHolder,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun upload(folder: String, id: String, file: File): KantaResult<String> {
        if (!supabase.isConfigured) return KantaResult.Failure(KantaError.BackendNotConfigured)
        return withContext(io) {
            try {
                val uid = supabase.client.auth.currentUserOrNull()?.id
                    ?: return@withContext KantaResult.Failure(KantaError.NotSignedIn)
                val path = "$folder/$uid/$id.jpg"
                supabase.client.storage.from("photos").upload(path, file.readBytes()) {
                    upsert = true
                    contentType = ContentType.Image.JPEG
                }
                KantaResult.Success(path)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                KantaResult.Failure(t.toKantaError())
            }
        }
    }

    companion object {
        const val REPORTS = "reports"
        const val CONTAINERS = "containers"
        const val REQUESTS = "container_requests"
        const val SUGGESTIONS = "suggestions"
    }
}
