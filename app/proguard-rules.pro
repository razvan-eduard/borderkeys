# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
#
# R8 runs only here, in the application module. Library modules contribute rules through
# their consumer-rules.pro; nothing below duplicates those.

# Stack traces are only ever read locally -- there is no crash reporter and no network -- so
# keep them legible instead of uploading a mapping file somewhere.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- JNI ------------------------------------------------------------------------------
# JNI_OnLoad resolves classes and methods by name through RegisterNatives. Renaming either
# turns a link error into a crash on the first keystroke, at which point the keyboard is
# already on screen.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# --- SQLCipher ------------------------------------------------------------------------
# The Java layer is a thin wrapper over libsqlcipher.so and is looked up from native code.
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

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
# No rules for reflection frameworks, DI containers, HTTP clients or serialisers other than
# the one above, because none of those are on the classpath. verifyNoForbiddenDependencies
# fails the build if that ever stops being true.
