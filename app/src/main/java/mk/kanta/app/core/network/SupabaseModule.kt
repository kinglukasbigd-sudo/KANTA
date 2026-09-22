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
import javax.inject.Singleton

/**
 * Supabase client (spec §2 "Backend"): postgrest, auth, storage, functions.
 *
 * URL and anon key come from local.properties via BuildConfig (spec §9.2) — never hard-coded.
 * The anon key is a public, RLS-protected key; it is still kept out of git so the backend a
 * build points at is never guessable from the repo.
 */
@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {

    @Provides
    @Singleton
    fun provideSupabaseClient(): SupabaseClient {
        check(BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()) {
            "Missing Supabase credentials. Add SUPABASE_URL and SUPABASE_ANON_KEY to local.properties " +
                "(see local.properties.example) and rebuild."
        }

        return createSupabaseClient(
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
