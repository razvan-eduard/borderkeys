# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
#
# Merged into the consuming application's R8 run. Only the JNI surface needs pinning: every
# other class in this module is reached from Kotlin and may be renamed freely.

# JNI_OnLoad + RegisterNatives binds by fully qualified class name and by method
# name/signature. Both sides of that contract have to survive obfuscation, and
# includedescriptorclasses keeps the parameter types from being renamed out from under the
# signature string.
-keep,includedescriptorclasses class com.borderkeys.predict.NativePredictor {
    native <methods>;
    <init>(...);
    public static final com.borderkeys.predict.NativePredictor INSTANCE;
}

# The InputMethodService is instantiated by the system from the name written in the manifest.
-keep class com.borderkeys.ime.BorderKeysService { <init>(); }
