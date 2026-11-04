# Keep Room entities
-keep class com.anivault.data.local.entity.** { *; }
-keep class com.anivault.data.local.model.**  { *; }

# Keep Retrofit / Gson model classes
-keep class com.anivault.data.remote.model.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# JSoup
-keep public class org.jsoup.** { public *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }

# Biometric
-keep class androidx.biometric.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# SQLCipher: native code looks up these classes/fields by name, so they must not be renamed.
-keep class net.sqlcipher.** { *; }
-keep class net.zetetic.** { *; }
-dontwarn net.sqlcipher.**

# Gson-serialised payloads (backup files, account/list models).
-keep class com.anivault.data.backup.BackupPayload { *; }
-keepclassmembers class com.anivault.data.local.entity.RemoteIds { *; }
