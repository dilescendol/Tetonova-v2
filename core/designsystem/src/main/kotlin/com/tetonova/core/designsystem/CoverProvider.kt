package com.tetonova.core.designsystem

/**
 * Indirection so the design system can show real poster covers without depending on networking.
 * The app sets [resolve] at startup (e.g. a Jikan/MAL lookup by title); [Art] calls it lazily and
 * falls back to the gradient placeholder when it's unset or returns null.
 */
object CoverProvider {
    @Volatile
    var resolve: (suspend (String) -> String?)? = null
}
