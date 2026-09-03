# R8 rules for Convoy.
#
# The build already asked for minification and resource shrinking, but this
# file did not exist — so a release build could not be produced at all.
#
# Everything below exists because R8 cannot see the use. It removes and
# renames anything it believes is unreachable, and every rule here covers a
# path where the caller is reflection, a native library, or an annotation
# processor, none of which R8 can follow.

# ── Line numbers in crash reports ────────────────────────────────
# Without this a stack trace from a release build is a list of obfuscated
# names with no line numbers, which makes a user-reported crash almost
# impossible to place. The mapping file in build/outputs/mapping/ is what
# turns these back into real names — keep it for every release you ship.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Generic signatures and annotations, needed by Retrofit and Gson to work
# out what type to deserialize into.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# ── Our API models ───────────────────────────────────────────────
# THE most important rule here.
#
# Gson populates these by reflection and never runs a Kotlin constructor.
# R8 has no idea the fields are used, so without this it strips them, and
# every response silently deserializes to an object full of nulls — which
# then crashes far away from the cause, in a composable.
#
# @SerializedName protects the JSON name, not the field's existence.
-keep class com.convoy.mobile.dataModel.** { *; }
-keepclassmembers class com.convoy.mobile.dataModel.** { <fields>; }

# Enums are looked up by name (valueOf) both by Gson and by our own
# preference parsing, so their constants must survive.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Anything annotated for Gson, wherever it lives.
-keepclasseswithmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── Gson itself ──────────────────────────────────────────────────
-dontwarn sun.misc.**
-keep class com.google.gson.stream.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ── Retrofit / OkHttp ────────────────────────────────────────────
# Retrofit builds implementations from the interface's annotations and
# generic return types at runtime.
-keep,allowobfuscation interface com.convoy.mobile.interfaces.**
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# Kotlin suspend functions on Retrofit interfaces return through Continuation
# and R8 must not strip the type information that makes that work.
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── Socket.IO ────────────────────────────────────────────────────
# Event handlers are dispatched by string name through reflection, so the
# listener plumbing cannot be traced statically.
-keep class io.socket.** { *; }
-keep class org.json.** { *; }
-dontwarn io.socket.**

# ── MapLibre ─────────────────────────────────────────────────────
# Calls back into Java from native code. Renaming anything it looks up by
# name produces a crash inside the renderer with no usable stack.
-keep class org.maplibre.android.** { *; }
-keep class org.maplibre.geojson.** { *; }
-keep interface org.maplibre.android.** { *; }
-dontwarn org.maplibre.**

# ── Hilt / Dagger ────────────────────────────────────────────────
# Mostly covered by the libraries' own consumer rules; these cover the
# generated entry points R8 sees no direct reference to.
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel
-dontwarn dagger.hilt.**

# ── Coil ─────────────────────────────────────────────────────────
-dontwarn coil.**

# ── Compose ──────────────────────────────────────────────────────
# Compose ships its own rules; this only silences warnings from optional
# desktop-only classes that are not on the Android classpath.
-dontwarn androidx.compose.**

# ── Our own entry points ─────────────────────────────────────────
# Referenced from the manifest by name, so R8 sees no code path to them.
-keep class com.convoy.mobile.ConvoyApplication { *; }
-keep class com.convoy.mobile.activities.** { *; }
-keep class com.convoy.mobile.service.** { *; }

# Kotlin metadata, so reflection-based libraries can still read class shape.
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
