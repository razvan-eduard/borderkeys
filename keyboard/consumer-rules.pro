# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
#
# Merged into the consuming application's R8 run.

# RegisterNatives binds by class name and by method name and signature, parameter types included.
-keep,includedescriptorclasses class com.borderkeys.predict.NativePredictor {
    native <methods>;
    <init>(...);
    public static final com.borderkeys.predict.NativePredictor INSTANCE;
}

# Instantiated by the system from its manifest name.
-keep class com.borderkeys.ime.BorderKeysService { <init>(); }
