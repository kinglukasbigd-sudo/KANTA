// Root build file.
//
// AGP 9 compiles Kotlin itself ("built-in Kotlin"), so `org.jetbrains.kotlin.android` must NOT
// be applied. AGP 9.4.1 ships with KGP 2.2.10; declaring the Compose and serialization plugins
// at the catalog's Kotlin version raises the whole Kotlin toolchain to that version so the
// compiler plugins and the built-in compiler stay in lockstep.
// See https://developer.android.com/build/migrate-to-built-in-kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
