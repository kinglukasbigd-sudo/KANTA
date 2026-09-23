package mk.kanta.app.core.data.remote

/**
 * The outcome of a backend call: either a value or a [KantaError] the UI can render.
 *
 * Kotlin's own `Result` is not used because it carries a `Throwable`, and by the
 * time a failure reaches a ViewModel it should already be a decided, translatable
 * error — not something each screen re-interprets.
 */
sealed interface KantaResult<out T> {

    data class Success<T>(val data: T) : KantaResult<T>

    data class Failure(val error: KantaError) : KantaResult<Nothing>

    /** In-flight. Emitted first by the repository's flows so the UI can show a skeleton (§8). */
    data object Loading : KantaResult<Nothing>

    val dataOrNull: T? get() = (this as? Success)?.data

    val errorOrNull: KantaError? get() = (this as? Failure)?.error

    val isLoading: Boolean get() = this is Loading

    fun <R> map(transform: (T) -> R): KantaResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
        Loading -> Loading
    }

}

// Interface members cannot be inline, so these are extensions — same call-site
// ergonomics, no virtual dispatch on a lambda.
inline fun <T> KantaResult<T>.onSuccess(action: (T) -> Unit): KantaResult<T> {
    if (this is KantaResult.Success) action(data)
    return this
}

inline fun <T> KantaResult<T>.onFailure(action: (KantaError) -> Unit): KantaResult<T> {
    if (this is KantaResult.Failure) action(error)
    return this
}
