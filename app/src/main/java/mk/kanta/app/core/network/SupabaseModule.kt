package mk.kanta.app.core.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import mk.kanta.app.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the Supabase client (spec §2 "Backend"): postgrest, auth, storage, functions.
 *
 * URL and anon key come from local.properties via BuildConfig (spec §9.2) — never
 * hard-coded. The anon key is a public, RLS-protected key; it is still kept out of
 * git so the backend a build points at is never guessable from the repo.
 *
 * Construction is LAZY and [isConfigured] is separate on purpose. Building the
 * client eagerly in a @Provides meant a checkout with empty credentials crashed
 * the moment anything injected the repository — including the map, which is
 * supposed to be browsable from cache without an account (§2) and to survive
 * being offline (§8). Now a missing backend is an error the UI can show, not a
 * dead screen.
 */
@Singleton
class SupabaseClientHolder @Inject constructor() {

    val isConfigured: Boolean =
        BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    val client: SupabaseClient by lazy {
        check(isConfigured) {
            "Missing Supabase credentials. Add SUPABASE_URL and SUPABASE_ANON_KEY to " +
                "local.properties (see local.properties.example) and rebuild."
        }
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
        ) {
            install(Postgrest)
            install(Auth)
            install(Storage)
            install(Functions)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {

    /**
     * Provided for call sites that genuinely need the client. Anything that must
     * tolerate a missing backend should inject [SupabaseClientHolder] instead and
     * check [SupabaseClientHolder.isConfigured] first.
     */
    @Provides
    @Singleton
    fun provideSupabaseClient(holder: SupabaseClientHolder): SupabaseClient = holder.client
}
