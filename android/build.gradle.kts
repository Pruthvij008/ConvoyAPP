// Versions pinned to the toolchain that is known to build on this machine:
// Gradle 7.6.4 + AGP 7.4.2 + Kotlin 1.9.22 + JDK 17.
plugins {
    id("com.android.application") version "7.4.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
    id("com.google.dagger.hilt.android") version "2.51.1" apply false
}
