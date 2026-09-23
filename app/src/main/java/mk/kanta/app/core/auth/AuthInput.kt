package mk.kanta.app.core.auth

/**
 * Pure input rules for the sign-in sheet. Kept out of the UI so they can be unit
 * tested without Compose.
 */
object AuthInput {

    const val CODE_LENGTH = 6

    /**
     * Normalises whatever reached the code field — typed, pasted, or autofilled —
     * into at most six digits. Pasting "123 456" or "Your code: 123-456" from an
     * email must just work (brief: "paste support").
     */
    fun sanitizeCode(raw: String): String = raw.filter(Char::isDigit).take(CODE_LENGTH)

    /**
     * Deliberately forgiving: this only catches obvious typos before we spend a
     * rate-limited email on them. Supabase is the real validator.
     */
    fun isPlausibleEmail(raw: String): Boolean {
        val email = raw.trim()
        if (email.length !in 5..254 || email.any(Char::isWhitespace)) return false
        val at = email.indexOf('@')
        if (at <= 0 || at != email.lastIndexOf('@')) return false
        val domain = email.substring(at + 1)
        return domain.contains('.') && !domain.startsWith('.') && !domain.endsWith('.')
    }

    fun normalizeEmail(raw: String): String = raw.trim().lowercase()

    /** Display names are shown only to their owner (§8), but must not be empty. */
    fun normalizeDisplayName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ").take(40)

    fun isValidDisplayName(raw: String): Boolean = normalizeDisplayName(raw).length >= 2
}
