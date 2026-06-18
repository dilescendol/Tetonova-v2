# =========================================================================
# TetoNova R8 / ProGuard rules (release minify + resource shrink)
#
# Mayoritas library yang dipakai membawa *consumer rules* sendiri yang sudah
# teruji dengan R8, jadi tidak perlu aturan tambahan:
#   OkHttp/Okio, Coil, Media3 (ExoPlayer/HLS/UI/Session), navigation-compose,
#   kotlinx-datetime, DataStore, Jetpack Compose.
#
# Yang WAJIB ditambah hanyalah titik yang bergantung pada refleksi/codegen,
# yaitu kotlinx.serialization (model @Serializable di package data/).
# =========================================================================

# --- kotlinx.serialization -------------------------------------------------
# Pertahankan anotasi + generated serializer untuk semua model @Serializable.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# Companion + method serializer() pada kelas @Serializable di package data.
-keepclassmembers @kotlinx.serialization.Serializable class com.tetonova.app.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# Kelas $$serializer yang di-generate compiler plugin.
-keep,includedescriptorclasses class com.tetonova.app.data.**$$serializer { *; }

# --- Jsoup ----------------------------------------------------------------
# Pure-Java, tidak merefleksi model app; cukup redam warning opsionalnya.
-dontwarn org.jsoup.**

# --- OkHttp / Okio --------------------------------------------------------
# Consumer rules sudah ada; redam warning platform TLS opsional yang
# tidak ikut di-bundle (R8 menganggapnya missing reference).
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
