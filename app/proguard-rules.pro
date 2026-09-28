# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
#
# R8 runs only here, in the application module. Library modules contribute rules through
# their consumer-rules.pro.

# Legible stack traces, without a mapping file.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- JNI ------------------------------------------------------------------------------
# JNI_OnLoad resolves classes and methods by name through RegisterNatives, so every native
# method is kept, unrenamed and with its class, including one with no Kotlin caller.
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}

# --- SQLCipher ------------------------------------------------------------------------
# The Java layer is a thin wrapper over libsqlcipher.so and is looked up from native code.
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

# --- Tink, via androidx.security:security-crypto -----------------------------------------
# Tink is compiled against Error Prone's annotations and JSR-305, which are compile-time only
# and absent at runtime.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**

# --- kotlinx.serialization -------------------------------------------------------------
# The compiler plugin generates a `Companion.serializer()` and a `$$serializer` object per
# @Serializable class; both are reached reflectively by the runtime the first time a theme is
# read from DataStore.
-keepclassmembers class com.borderkeys.** {
    *** Companion;
}
-keepclasseswithmembers class com.borderkeys.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class com.borderkeys.**
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$* *;
}
-keepclassmembers class com.borderkeys.**$$serializer {
    *** descriptor;
}

# --- Room -------------------------------------------------------------------------------
# Entities and DAOs are generated against exact field names; the generated implementations
# are what R8 sees, so only the schema-bearing types need pinning.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# --- Absent by design -------------------------------------------------------------------
# No rules for reflection frameworks, DI containers, HTTP clients or other serialisers: none
# are on the classpath, and verifyNoForbiddenDependencies fails the build if one is.
